#include "kinetic_assembly/physx_context.hpp"

#include <cmath>
#include <utility>

#if KINETIC_ASSEMBLY_WITH_PHYSX
#include "extensions/PxExtensionsAPI.h"
#include "extensions/PxDistanceJoint.h"
#include "extensions/PxFixedJoint.h"
#include "extensions/PxPrismaticJoint.h"
#include "extensions/PxRevoluteJoint.h"
#include "extensions/PxRigidBodyExt.h"
#endif

#if KINETIC_ASSEMBLY_WITH_DEFORMABLE_VOLUME
#include "extensions/PxCudaHelpersExt.h"
#include "extensions/PxDeformableVolumeExt.h"
#endif

namespace kinetic_assembly {
namespace {
#if KINETIC_ASSEMBLY_WITH_PHYSX
template <typename T>
void release_px(T*& object) {
    if (object != nullptr) {
        object->release();
        object = nullptr;
    }
}

template <typename T>
std::uint64_t to_handle(T* pointer) {
    return static_cast<std::uint64_t>(reinterpret_cast<std::uintptr_t>(pointer));
}

template <typename T>
T* from_handle(std::uint64_t handle) {
    return reinterpret_cast<T*>(static_cast<std::uintptr_t>(handle));
}

physx::PxTransform make_transform(
    double position_x,
    double position_y,
    double position_z,
    double rotation_x,
    double rotation_y,
    double rotation_z,
    double rotation_w
) {
    physx::PxQuat rotation(
        static_cast<physx::PxReal>(rotation_x),
        static_cast<physx::PxReal>(rotation_y),
        static_cast<physx::PxReal>(rotation_z),
        static_cast<physx::PxReal>(rotation_w)
    );
    if (rotation.magnitudeSquared() <= 0.0f) {
        rotation = physx::PxQuat(0.0f, 0.0f, 0.0f, 1.0f);
    } else {
        rotation.normalize();
    }
    return physx::PxTransform(
        physx::PxVec3(
            static_cast<physx::PxReal>(position_x),
            static_cast<physx::PxReal>(position_y),
            static_cast<physx::PxReal>(position_z)
        ),
        rotation
    );
}

physx::PxFilterFlags ccd_filter_shader(
    physx::PxFilterObjectAttributes attributes0,
    physx::PxFilterData,
    physx::PxFilterObjectAttributes attributes1,
    physx::PxFilterData,
    physx::PxPairFlags& pair_flags,
    const void*,
    physx::PxU32
) {
    if (physx::PxFilterObjectIsTrigger(attributes0) || physx::PxFilterObjectIsTrigger(attributes1)) {
        pair_flags = physx::PxPairFlag::eTRIGGER_DEFAULT;
        return physx::PxFilterFlag::eDEFAULT;
    }

    pair_flags = physx::PxPairFlag::eCONTACT_DEFAULT | physx::PxPairFlag::eDETECT_CCD_CONTACT;
    return physx::PxFilterFlag::eDEFAULT;
}

bool finite_positive(float value) {
    return std::isfinite(value) && value > 0.0f;
}

bool finite_non_negative(float value) {
    return std::isfinite(value) && value >= 0.0f;
}

bool finite_transform_values(const double* values, int offset) {
    if (values == nullptr) {
        return false;
    }
    for (int i = 0; i < 7; ++i) {
        if (!std::isfinite(values[offset + i])) {
            return false;
        }
    }
    return true;
}

bool finite_vector_values(const double* values, int offset) {
    if (values == nullptr) {
        return false;
    }
    for (int i = 0; i < 3; ++i) {
        if (!std::isfinite(values[offset + i])) {
            return false;
        }
    }
    return true;
}
#endif
}

#if KINETIC_ASSEMBLY_WITH_PHYSX
struct DeformableVolumeState {
#if KINETIC_ASSEMBLY_WITH_DEFORMABLE_VOLUME && PX_SUPPORT_GPU_PHYSX
    physx::PxDeformableVolume* volume = nullptr;
    physx::PxDeformableVolumeMaterial* material = nullptr;
    physx::PxVec4* collision_positions_pinned = nullptr;
    physx::PxCudaContextManager* cuda_context_manager = nullptr;
    physx::PxU32 collision_vertex_count = 0;
    physx::PxU32 collision_tetrahedron_count = 0;
    physx::PxU32 simulation_vertex_count = 0;
    physx::PxU32 simulation_tetrahedron_count = 0;
#endif
};

struct JointState {
    physx::PxJoint* joint = nullptr;
    physx::PxRigidActor* first_body = nullptr;
    physx::PxRigidActor* second_body = nullptr;
};
#endif

PhysXContext::~PhysXContext() {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    std::lock_guard<std::mutex> lock(mutex_);
    for (auto& entry : worlds_) {
        release_world(*entry.second);
    }
    worlds_.clear();
#if PX_SUPPORT_GPU_PHYSX
    release_px(cuda_context_manager_);
#endif
    if (extensions_initialized_) {
        PxCloseExtensions();
        extensions_initialized_ = false;
    }
    release_px(physics_);
    release_px(foundation_);
#endif
}

std::uint64_t PhysXContext::next_handle() {
    return next_handle_.fetch_add(1, std::memory_order_relaxed);
}

WorldHandle PhysXContext::create_world(double gravity_x, double gravity_y, double gravity_z, float, int, bool enable_gpu_dynamics) {
    std::lock_guard<std::mutex> lock(mutex_);

#if KINETIC_ASSEMBLY_WITH_PHYSX
    if (!ensure_initialized()) {
        return 0;
    }

    auto world = std::make_unique<WorldState>();
    world->gpu_dynamics_requested = enable_gpu_dynamics;
    world->gpu_dynamics_status = enable_gpu_dynamics ? "requested" : "not_requested";
    world->dispatcher = physx::PxDefaultCpuDispatcherCreate(2);
    if (world->dispatcher == nullptr) {
        release_world(*world);
        return 0;
    }

    auto make_scene_desc = [&]() {
        physx::PxSceneDesc scene_desc(physics_->getTolerancesScale());
        scene_desc.gravity = physx::PxVec3(
            static_cast<physx::PxReal>(gravity_x),
            static_cast<physx::PxReal>(gravity_y),
            static_cast<physx::PxReal>(gravity_z)
        );
        scene_desc.cpuDispatcher = world->dispatcher;
        scene_desc.filterShader = ccd_filter_shader;
        scene_desc.flags |= physx::PxSceneFlag::eENABLE_CCD;
        scene_desc.ccdMaxPasses = 4;
        return scene_desc;
    };

    physx::PxSceneDesc scene_desc = make_scene_desc();
    bool gpu_scene_requested = false;
    if (enable_gpu_dynamics) {
#if PX_SUPPORT_GPU_PHYSX
        std::string cuda_status;
        if (ensure_cuda_context_manager(cuda_status)) {
            scene_desc.cudaContextManager = cuda_context_manager_;
            scene_desc.flags |= physx::PxSceneFlag::eENABLE_GPU_DYNAMICS;
            scene_desc.flags |= physx::PxSceneFlag::eENABLE_STABILIZATION;
            scene_desc.broadPhaseType = physx::PxBroadPhaseType::eGPU;
            scene_desc.gpuMaxNumPartitions = 8;
            scene_desc.solverType = physx::PxSolverType::eTGS;
            gpu_scene_requested = true;
            world->gpu_dynamics_status = "gpu_scene_requested";
        } else {
            world->gpu_dynamics_status = cuda_status;
        }
#else
        world->gpu_dynamics_status = "unsupported_platform";
#endif
    }

    world->scene = physics_->createScene(scene_desc);
    if (world->scene == nullptr && gpu_scene_requested) {
        gpu_scene_requested = false;
        world->gpu_dynamics_status = "gpu_scene_create_failed_cpu_fallback";
        physx::PxSceneDesc cpu_scene_desc = make_scene_desc();
        world->scene = physics_->createScene(cpu_scene_desc);
    }
    world->gpu_dynamics_enabled = gpu_scene_requested && world->scene != nullptr;
    if (world->gpu_dynamics_enabled) {
        world->gpu_dynamics_status = "enabled";
#if PX_SUPPORT_GPU_PHYSX
        gpu_world_count_++;
#endif
    }
    world->material = physics_->createMaterial(0.6f, 0.6f, 0.0f);

    if (world->scene == nullptr || world->material == nullptr) {
        release_world(*world);
        return 0;
    }

    WorldHandle handle = next_handle();
    worlds_.emplace(handle, std::move(world));
    return handle;
#else
    return 0;
#endif
}

void PhysXContext::destroy_world(WorldHandle handle) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto found = worlds_.find(handle);
    if (found == worlds_.end()) {
        return;
    }
#if KINETIC_ASSEMBLY_WITH_PHYSX
    release_world(*found->second);
#endif
    worlds_.erase(found);
}

void PhysXContext::step_world(WorldHandle handle, float delta_seconds) {
    if (delta_seconds <= 0.0f) {
        return;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    auto found = worlds_.find(handle);
    if (found == worlds_.end()) {
        return;
    }
#if KINETIC_ASSEMBLY_WITH_PHYSX
    physx::PxScene* scene = found->second->scene;
    if (scene == nullptr) {
        return;
    }
    scene->simulate(delta_seconds);
    scene->fetchResults(true);
#endif
}

bool PhysXContext::is_world_gpu_dynamics_enabled(WorldHandle handle) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto found = worlds_.find(handle);
    if (found == worlds_.end()) {
        return false;
    }
#if KINETIC_ASSEMBLY_WITH_PHYSX
    return found->second->gpu_dynamics_enabled;
#else
    return false;
#endif
}

std::string PhysXContext::world_gpu_dynamics_status(WorldHandle handle) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto found = worlds_.find(handle);
    if (found == worlds_.end()) {
        return "unknown_world";
    }
#if KINETIC_ASSEMBLY_WITH_PHYSX
    return found->second->gpu_dynamics_status;
#else
    return "physx_not_linked";
#endif
}

std::uint64_t PhysXContext::create_box_shape(WorldHandle world_handle, float half_extent_x, float half_extent_y, float half_extent_z) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    if (half_extent_x <= 0.0f || half_extent_y <= 0.0f || half_extent_z <= 0.0f) {
        return 0;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    auto found = worlds_.find(world_handle);
    if (found == worlds_.end() || found->second->material == nullptr) {
        return 0;
    }
    physx::PxShape* shape = physics_->createShape(
        physx::PxBoxGeometry(half_extent_x, half_extent_y, half_extent_z),
        *found->second->material
    );
    return to_handle(shape);
#else
    return 0;
#endif
}

std::uint64_t PhysXContext::create_static_plane(WorldHandle world_handle, double normal_x, double normal_y, double normal_z, double distance) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    std::lock_guard<std::mutex> lock(mutex_);
    auto found = worlds_.find(world_handle);
    if (found == worlds_.end() || found->second->scene == nullptr || found->second->material == nullptr) {
        return 0;
    }

    double length = std::sqrt(normal_x * normal_x + normal_y * normal_y + normal_z * normal_z);
    if (length <= 0.0) {
        return 0;
    }

    physx::PxPlane plane(
        static_cast<physx::PxReal>(normal_x / length),
        static_cast<physx::PxReal>(normal_y / length),
        static_cast<physx::PxReal>(normal_z / length),
        static_cast<physx::PxReal>(distance / length)
    );
    physx::PxRigidStatic* body = physx::PxCreatePlane(*physics_, plane, *found->second->material);
    if (body == nullptr) {
        return 0;
    }
    found->second->scene->addActor(*body);
    return to_handle(body);
#else
    return 0;
#endif
}

std::uint64_t PhysXContext::create_static_body(
    WorldHandle world_handle,
    std::uint64_t shape_handle,
    double position_x,
    double position_y,
    double position_z,
    double rotation_x,
    double rotation_y,
    double rotation_z,
    double rotation_w
) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    std::lock_guard<std::mutex> lock(mutex_);
    auto found = worlds_.find(world_handle);
    physx::PxShape* shape = from_handle<physx::PxShape>(shape_handle);
    if (found == worlds_.end() || found->second->scene == nullptr || shape == nullptr) {
        return 0;
    }

    physx::PxRigidStatic* body = physics_->createRigidStatic(make_transform(
        position_x,
        position_y,
        position_z,
        rotation_x,
        rotation_y,
        rotation_z,
        rotation_w
    ));
    if (body == nullptr) {
        return 0;
    }
    if (!body->attachShape(*shape)) {
        body->release();
        return 0;
    }
    found->second->scene->addActor(*body);
    return to_handle(body);
#else
    return 0;
#endif
}

std::uint64_t PhysXContext::create_dynamic_body(
    WorldHandle world_handle,
    std::uint64_t shape_handle,
    double position_x,
    double position_y,
    double position_z,
    double rotation_x,
    double rotation_y,
    double rotation_z,
    double rotation_w,
    float mass
) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    if (mass <= 0.0f) {
        return 0;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    auto found = worlds_.find(world_handle);
    physx::PxShape* shape = from_handle<physx::PxShape>(shape_handle);
    if (found == worlds_.end() || found->second->scene == nullptr || shape == nullptr) {
        return 0;
    }

    physx::PxRigidDynamic* body = physics_->createRigidDynamic(make_transform(
        position_x,
        position_y,
        position_z,
        rotation_x,
        rotation_y,
        rotation_z,
        rotation_w
    ));
    if (body == nullptr) {
        return 0;
    }
    if (!body->attachShape(*shape)) {
        body->release();
        return 0;
    }
    body->setRigidBodyFlag(physx::PxRigidBodyFlag::eENABLE_CCD, true);
    physx::PxRigidBodyExt::updateMassAndInertia(*body, mass);
    found->second->scene->addActor(*body);
    return to_handle(body);
#else
    return 0;
#endif
}

std::uint64_t PhysXContext::create_dynamic_compound_box_body(
    WorldHandle world_handle,
    const double* boxes,
    int box_count,
    double position_x,
    double position_y,
    double position_z,
    double rotation_x,
    double rotation_y,
    double rotation_z,
    double rotation_w,
    float mass
) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    if (mass <= 0.0f || boxes == nullptr || box_count <= 0) {
        return 0;
    }
    for (int i = 0; i < box_count; ++i) {
        const int offset = i * 6;
        const double half_x = boxes[offset + 3];
        const double half_y = boxes[offset + 4];
        const double half_z = boxes[offset + 5];
        if (!std::isfinite(boxes[offset])
            || !std::isfinite(boxes[offset + 1])
            || !std::isfinite(boxes[offset + 2])
            || !std::isfinite(half_x)
            || !std::isfinite(half_y)
            || !std::isfinite(half_z)
            || half_x <= 0.0
            || half_y <= 0.0
            || half_z <= 0.0) {
            return 0;
        }
    }

    std::lock_guard<std::mutex> lock(mutex_);
    auto found = worlds_.find(world_handle);
    if (found == worlds_.end() || found->second->scene == nullptr || found->second->material == nullptr) {
        return 0;
    }

    physx::PxRigidDynamic* body = physics_->createRigidDynamic(make_transform(
        position_x,
        position_y,
        position_z,
        rotation_x,
        rotation_y,
        rotation_z,
        rotation_w
    ));
    if (body == nullptr) {
        return 0;
    }

    for (int i = 0; i < box_count; ++i) {
        const int offset = i * 6;
        physx::PxShape* shape = physics_->createShape(
            physx::PxBoxGeometry(
                static_cast<physx::PxReal>(boxes[offset + 3]),
                static_cast<physx::PxReal>(boxes[offset + 4]),
                static_cast<physx::PxReal>(boxes[offset + 5])
            ),
            *found->second->material
        );
        if (shape == nullptr) {
            body->release();
            return 0;
        }

        shape->setLocalPose(physx::PxTransform(physx::PxVec3(
            static_cast<physx::PxReal>(boxes[offset]),
            static_cast<physx::PxReal>(boxes[offset + 1]),
            static_cast<physx::PxReal>(boxes[offset + 2])
        )));
        if (!body->attachShape(*shape)) {
            shape->release();
            body->release();
            return 0;
        }
        shape->release();
    }

    body->setRigidBodyFlag(physx::PxRigidBodyFlag::eENABLE_CCD, true);
    physx::PxRigidBodyExt::updateMassAndInertia(*body, mass);
    found->second->scene->addActor(*body);
    return to_handle(body);
#else
    return 0;
#endif
}

std::uint64_t PhysXContext::create_dynamic_compound_box_body_with_mass_properties(
    WorldHandle world_handle,
    const double* boxes,
    int box_count,
    double position_x,
    double position_y,
    double position_z,
    double rotation_x,
    double rotation_y,
    double rotation_z,
    double rotation_w,
    float mass,
    double center_of_mass_x,
    double center_of_mass_y,
    double center_of_mass_z,
    double inertia_x,
    double inertia_y,
    double inertia_z
) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    if (!std::isfinite(mass) || mass <= 0.0f || boxes == nullptr || box_count <= 0
        || !std::isfinite(center_of_mass_x)
        || !std::isfinite(center_of_mass_y)
        || !std::isfinite(center_of_mass_z)
        || !std::isfinite(inertia_x)
        || !std::isfinite(inertia_y)
        || !std::isfinite(inertia_z)
        || inertia_x <= 0.0
        || inertia_y <= 0.0
        || inertia_z <= 0.0) {
        return 0;
    }
    for (int i = 0; i < box_count; ++i) {
        const int offset = i * 6;
        const double half_x = boxes[offset + 3];
        const double half_y = boxes[offset + 4];
        const double half_z = boxes[offset + 5];
        if (!std::isfinite(boxes[offset])
            || !std::isfinite(boxes[offset + 1])
            || !std::isfinite(boxes[offset + 2])
            || !std::isfinite(half_x)
            || !std::isfinite(half_y)
            || !std::isfinite(half_z)
            || half_x <= 0.0
            || half_y <= 0.0
            || half_z <= 0.0) {
            return 0;
        }
    }

    std::lock_guard<std::mutex> lock(mutex_);
    auto found = worlds_.find(world_handle);
    if (found == worlds_.end() || found->second->scene == nullptr || found->second->material == nullptr) {
        return 0;
    }

    physx::PxRigidDynamic* body = physics_->createRigidDynamic(make_transform(
        position_x,
        position_y,
        position_z,
        rotation_x,
        rotation_y,
        rotation_z,
        rotation_w
    ));
    if (body == nullptr) {
        return 0;
    }

    for (int i = 0; i < box_count; ++i) {
        const int offset = i * 6;
        physx::PxShape* shape = physics_->createShape(
            physx::PxBoxGeometry(
                static_cast<physx::PxReal>(boxes[offset + 3]),
                static_cast<physx::PxReal>(boxes[offset + 4]),
                static_cast<physx::PxReal>(boxes[offset + 5])
            ),
            *found->second->material
        );
        if (shape == nullptr) {
            body->release();
            return 0;
        }

        shape->setLocalPose(physx::PxTransform(physx::PxVec3(
            static_cast<physx::PxReal>(boxes[offset]),
            static_cast<physx::PxReal>(boxes[offset + 1]),
            static_cast<physx::PxReal>(boxes[offset + 2])
        )));
        if (!body->attachShape(*shape)) {
            shape->release();
            body->release();
            return 0;
        }
        shape->release();
    }

    body->setRigidBodyFlag(physx::PxRigidBodyFlag::eENABLE_CCD, true);
    body->setCMassLocalPose(physx::PxTransform(physx::PxVec3(
        static_cast<physx::PxReal>(center_of_mass_x),
        static_cast<physx::PxReal>(center_of_mass_y),
        static_cast<physx::PxReal>(center_of_mass_z)
    )));
    body->setMass(mass);
    body->setMassSpaceInertiaTensor(physx::PxVec3(
        static_cast<physx::PxReal>(inertia_x),
        static_cast<physx::PxReal>(inertia_y),
        static_cast<physx::PxReal>(inertia_z)
    ));
    found->second->scene->addActor(*body);
    return to_handle(body);
#else
    return 0;
#endif
}

std::uint64_t PhysXContext::create_deformable_volume_box(
    WorldHandle world_handle,
    double center_x,
    double center_y,
    double center_z,
    float width,
    float height,
    float depth,
    float density,
    float youngs,
    float poissons,
    float dynamic_friction,
    float damping,
    float max_edge_length,
    int voxels
) {
#if KINETIC_ASSEMBLY_WITH_DEFORMABLE_VOLUME && PX_SUPPORT_GPU_PHYSX
    if (!finite_positive(width)
        || !finite_positive(height)
        || !finite_positive(depth)
        || !finite_positive(density)
        || !finite_positive(youngs)
        || !std::isfinite(poissons)
        || poissons < 0.0f
        || poissons >= 0.5f
        || !std::isfinite(dynamic_friction)
        || dynamic_friction < 0.0f
        || !std::isfinite(damping)
        || damping < 0.0f
        || !std::isfinite(max_edge_length)
        || voxels < 1) {
        return 0;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    auto found = worlds_.find(world_handle);
    if (found == worlds_.end()
        || found->second->scene == nullptr
        || !found->second->gpu_dynamics_enabled
        || cuda_context_manager_ == nullptr) {
        return 0;
    }

    physx::PxDeformableVolumeMaterial* material = physics_->createDeformableVolumeMaterial(
        youngs,
        poissons,
        dynamic_friction,
        damping
    );
    if (material == nullptr) {
        return 0;
    }
    material->setMaterialModel(physx::PxDeformableVolumeMaterialModel::eNEO_HOOKEAN);

    physx::PxDeformableVolume* volume = physx::PxDeformableVolumeExt::createDeformableVolumeBox(
        physx::PxTransform(
            physx::PxVec3(
                static_cast<physx::PxReal>(center_x),
                static_cast<physx::PxReal>(center_y),
                static_cast<physx::PxReal>(center_z)
            ),
            physx::PxQuat(physx::PxIdentity)
        ),
        physx::PxVec3(width, height, depth),
        *material,
        *cuda_context_manager_,
        max_edge_length,
        density,
        static_cast<physx::PxU32>(voxels),
        1.0f
    );
    if (volume == nullptr || volume->getCollisionMesh() == nullptr || volume->getSimulationMesh() == nullptr) {
        release_px(volume);
        release_px(material);
        return 0;
    }

    volume->setDeformableBodyFlag(physx::PxDeformableBodyFlag::eDISABLE_SELF_COLLISION, true);
    volume->setLinearDamping(damping);
    volume->setSolverIterationCounts(16);
    found->second->scene->addActor(*volume);

    auto state = std::make_unique<DeformableVolumeState>();
    state->volume = volume;
    state->material = material;
    state->cuda_context_manager = cuda_context_manager_;
    state->collision_vertex_count = volume->getCollisionMesh()->getNbVertices();
    state->collision_tetrahedron_count = volume->getCollisionMesh()->getNbTetrahedrons();
    state->simulation_vertex_count = volume->getSimulationMesh()->getNbVertices();
    state->simulation_tetrahedron_count = volume->getSimulationMesh()->getNbTetrahedrons();
    state->collision_positions_pinned = PX_EXT_PINNED_MEMORY_ALLOC(physx::PxVec4, *cuda_context_manager_, state->collision_vertex_count);
    if (state->collision_positions_pinned == nullptr) {
        release_deformable_volume(*state);
        return 0;
    }

    std::uint64_t handle = next_handle();
    found->second->deformable_volumes.emplace(handle, std::move(state));
    return handle;
#else
    return 0;
#endif
}

int PhysXContext::get_deformable_volume_info(std::uint64_t deformable_volume, int* output) {
#if KINETIC_ASSEMBLY_WITH_DEFORMABLE_VOLUME && PX_SUPPORT_GPU_PHYSX
    if (output == nullptr) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    DeformableVolumeState* state = find_deformable_volume(deformable_volume);
    if (state == nullptr || state->volume == nullptr) {
        return 0;
    }
    output[0] = static_cast<int>(state->collision_vertex_count);
    output[1] = static_cast<int>(state->collision_tetrahedron_count);
    output[2] = static_cast<int>(state->simulation_vertex_count);
    output[3] = static_cast<int>(state->simulation_tetrahedron_count);
    return 4;
#else
    return 0;
#endif
}

int PhysXContext::get_deformable_volume_vertices(std::uint64_t deformable_volume, double* output, int max_vertices) {
#if KINETIC_ASSEMBLY_WITH_DEFORMABLE_VOLUME && PX_SUPPORT_GPU_PHYSX
    if (output == nullptr || max_vertices <= 0) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    DeformableVolumeState* state = find_deformable_volume(deformable_volume);
    if (state == nullptr
        || state->volume == nullptr
        || state->collision_positions_pinned == nullptr
        || state->cuda_context_manager == nullptr) {
        return 0;
    }

    physx::PxScopedCudaLock cuda_lock(*state->cuda_context_manager);
    state->cuda_context_manager->getCudaContext()->memcpyDtoH(
        state->collision_positions_pinned,
        reinterpret_cast<CUdeviceptr>(state->volume->getPositionInvMassBufferD()),
        state->collision_vertex_count * sizeof(physx::PxVec4)
    );

    int count = static_cast<int>(state->collision_vertex_count);
    if (count > max_vertices) {
        count = max_vertices;
    }
    for (int i = 0; i < count; ++i) {
        const physx::PxVec4& position = state->collision_positions_pinned[i];
        const int offset = i * 3;
        output[offset] = position.x;
        output[offset + 1] = position.y;
        output[offset + 2] = position.z;
    }
    return count;
#else
    return 0;
#endif
}

void PhysXContext::destroy_deformable_volume(std::uint64_t deformable_volume) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    std::lock_guard<std::mutex> lock(mutex_);
    for (auto& world : worlds_) {
        auto found = world.second->deformable_volumes.find(deformable_volume);
        if (found == world.second->deformable_volumes.end()) {
            continue;
        }
        release_deformable_volume(*found->second);
        world.second->deformable_volumes.erase(found);
        return;
    }
#endif
}

bool PhysXContext::read_body_states(WorldHandle world_handle, const std::uint64_t* body_handles, int body_count, double* output) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    if (body_count < 0 || body_handles == nullptr || output == nullptr) {
        return false;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    auto found_world = worlds_.find(world_handle);
    if (found_world == worlds_.end() || found_world->second->scene == nullptr) {
        return false;
    }

    constexpr int stride = 13;
    for (int i = 0; i < body_count; i++) {
        physx::PxRigidActor* body = from_handle<physx::PxRigidActor>(body_handles[i]);
        if (body == nullptr || body->getScene() != found_world->second->scene) {
            return false;
        }

        const physx::PxTransform pose = body->getGlobalPose();
        const int offset = i * stride;
        output[offset] = pose.p.x;
        output[offset + 1] = pose.p.y;
        output[offset + 2] = pose.p.z;
        output[offset + 3] = pose.q.x;
        output[offset + 4] = pose.q.y;
        output[offset + 5] = pose.q.z;
        output[offset + 6] = pose.q.w;

        physx::PxRigidDynamic* dynamic = body->is<physx::PxRigidDynamic>();
        if (dynamic == nullptr) {
            output[offset + 7] = 0.0;
            output[offset + 8] = 0.0;
            output[offset + 9] = 0.0;
            output[offset + 10] = 0.0;
            output[offset + 11] = 0.0;
            output[offset + 12] = 0.0;
            continue;
        }

        const physx::PxVec3 linear_velocity = dynamic->getLinearVelocity();
        const physx::PxVec3 angular_velocity = dynamic->getAngularVelocity();
        output[offset + 7] = linear_velocity.x;
        output[offset + 8] = linear_velocity.y;
        output[offset + 9] = linear_velocity.z;
        output[offset + 10] = angular_velocity.x;
        output[offset + 11] = angular_velocity.y;
        output[offset + 12] = angular_velocity.z;
    }
    return true;
#else
    return false;
#endif
}

bool PhysXContext::get_body_pose(std::uint64_t body_handle, double* output) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    physx::PxRigidActor* body = from_handle<physx::PxRigidActor>(body_handle);
    if (body == nullptr || output == nullptr) {
        return false;
    }
    const physx::PxTransform pose = body->getGlobalPose();
    output[0] = pose.p.x;
    output[1] = pose.p.y;
    output[2] = pose.p.z;
    output[3] = pose.q.x;
    output[4] = pose.q.y;
    output[5] = pose.q.z;
    output[6] = pose.q.w;
    return true;
#else
    return false;
#endif
}

void PhysXContext::set_body_pose(
    std::uint64_t body_handle,
    double position_x,
    double position_y,
    double position_z,
    double rotation_x,
    double rotation_y,
    double rotation_z,
    double rotation_w
) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    physx::PxRigidActor* body = from_handle<physx::PxRigidActor>(body_handle);
    if (body == nullptr) {
        return;
    }
    body->setGlobalPose(make_transform(
        position_x,
        position_y,
        position_z,
        rotation_x,
        rotation_y,
        rotation_z,
        rotation_w
    ));
#endif
}

void PhysXContext::set_linear_velocity(std::uint64_t body_handle, double velocity_x, double velocity_y, double velocity_z) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    physx::PxRigidActor* body = from_handle<physx::PxRigidActor>(body_handle);
    if (body == nullptr) {
        return;
    }
    physx::PxRigidDynamic* dynamic = body->is<physx::PxRigidDynamic>();
    if (dynamic == nullptr) {
        return;
    }
    dynamic->setLinearVelocity(physx::PxVec3(
        static_cast<physx::PxReal>(velocity_x),
        static_cast<physx::PxReal>(velocity_y),
        static_cast<physx::PxReal>(velocity_z)
    ));
#endif
}

void PhysXContext::set_angular_velocity(std::uint64_t body_handle, double velocity_x, double velocity_y, double velocity_z) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    physx::PxRigidActor* body = from_handle<physx::PxRigidActor>(body_handle);
    if (body == nullptr) {
        return;
    }
    physx::PxRigidDynamic* dynamic = body->is<physx::PxRigidDynamic>();
    if (dynamic == nullptr) {
        return;
    }
    dynamic->setAngularVelocity(physx::PxVec3(
        static_cast<physx::PxReal>(velocity_x),
        static_cast<physx::PxReal>(velocity_y),
        static_cast<physx::PxReal>(velocity_z)
    ));
#endif
}

bool PhysXContext::get_linear_velocity(std::uint64_t body_handle, double* output) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    physx::PxRigidActor* body = from_handle<physx::PxRigidActor>(body_handle);
    if (body == nullptr || output == nullptr) {
        return false;
    }
    physx::PxRigidDynamic* dynamic = body->is<physx::PxRigidDynamic>();
    if (dynamic == nullptr) {
        return false;
    }
    const physx::PxVec3 velocity = dynamic->getLinearVelocity();
    output[0] = velocity.x;
    output[1] = velocity.y;
    output[2] = velocity.z;
    return true;
#else
    return false;
#endif
}

bool PhysXContext::get_angular_velocity(std::uint64_t body_handle, double* output) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    physx::PxRigidActor* body = from_handle<physx::PxRigidActor>(body_handle);
    if (body == nullptr || output == nullptr) {
        return false;
    }
    physx::PxRigidDynamic* dynamic = body->is<physx::PxRigidDynamic>();
    if (dynamic == nullptr) {
        return false;
    }
    const physx::PxVec3 velocity = dynamic->getAngularVelocity();
    output[0] = velocity.x;
    output[1] = velocity.y;
    output[2] = velocity.z;
    return true;
#else
    return false;
#endif
}

bool PhysXContext::apply_linear_impulse(std::uint64_t body_handle, double impulse_x, double impulse_y, double impulse_z) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    physx::PxRigidActor* body = from_handle<physx::PxRigidActor>(body_handle);
    if (body == nullptr) {
        return false;
    }
    physx::PxRigidDynamic* dynamic = body->is<physx::PxRigidDynamic>();
    if (dynamic == nullptr) {
        return false;
    }
    dynamic->addForce(
        physx::PxVec3(
            static_cast<physx::PxReal>(impulse_x),
            static_cast<physx::PxReal>(impulse_y),
            static_cast<physx::PxReal>(impulse_z)
        ),
        physx::PxForceMode::eIMPULSE,
        true
    );
    return true;
#else
    return false;
#endif
}

bool PhysXContext::apply_angular_impulse(std::uint64_t body_handle, double impulse_x, double impulse_y, double impulse_z) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    physx::PxRigidActor* body = from_handle<physx::PxRigidActor>(body_handle);
    if (body == nullptr) {
        return false;
    }
    physx::PxRigidDynamic* dynamic = body->is<physx::PxRigidDynamic>();
    if (dynamic == nullptr) {
        return false;
    }
    dynamic->addTorque(
        physx::PxVec3(
            static_cast<physx::PxReal>(impulse_x),
            static_cast<physx::PxReal>(impulse_y),
            static_cast<physx::PxReal>(impulse_z)
        ),
        physx::PxForceMode::eIMPULSE,
        true
    );
    return true;
#else
    return false;
#endif
}

bool PhysXContext::apply_impulse_at_point(
    std::uint64_t body_handle,
    double impulse_x,
    double impulse_y,
    double impulse_z,
    double point_x,
    double point_y,
    double point_z
) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    physx::PxRigidActor* body = from_handle<physx::PxRigidActor>(body_handle);
    if (body == nullptr) {
        return false;
    }
    physx::PxRigidDynamic* dynamic = body->is<physx::PxRigidDynamic>();
    if (dynamic == nullptr) {
        return false;
    }
    physx::PxRigidBodyExt::addForceAtPos(
        *dynamic,
        physx::PxVec3(
            static_cast<physx::PxReal>(impulse_x),
            static_cast<physx::PxReal>(impulse_y),
            static_cast<physx::PxReal>(impulse_z)
        ),
        physx::PxVec3(
            static_cast<physx::PxReal>(point_x),
            static_cast<physx::PxReal>(point_y),
            static_cast<physx::PxReal>(point_z)
        ),
        physx::PxForceMode::eIMPULSE,
        true
    );
    return true;
#else
    return false;
#endif
}

bool PhysXContext::apply_force(std::uint64_t body_handle, double force_x, double force_y, double force_z) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    physx::PxRigidActor* body = from_handle<physx::PxRigidActor>(body_handle);
    if (body == nullptr) {
        return false;
    }
    physx::PxRigidDynamic* dynamic = body->is<physx::PxRigidDynamic>();
    if (dynamic == nullptr) {
        return false;
    }
    dynamic->addForce(
        physx::PxVec3(
            static_cast<physx::PxReal>(force_x),
            static_cast<physx::PxReal>(force_y),
            static_cast<physx::PxReal>(force_z)
        ),
        physx::PxForceMode::eFORCE,
        true
    );
    return true;
#else
    return false;
#endif
}

bool PhysXContext::apply_torque(std::uint64_t body_handle, double torque_x, double torque_y, double torque_z) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    physx::PxRigidActor* body = from_handle<physx::PxRigidActor>(body_handle);
    if (body == nullptr) {
        return false;
    }
    physx::PxRigidDynamic* dynamic = body->is<physx::PxRigidDynamic>();
    if (dynamic == nullptr) {
        return false;
    }
    dynamic->addTorque(
        physx::PxVec3(
            static_cast<physx::PxReal>(torque_x),
            static_cast<physx::PxReal>(torque_y),
            static_cast<physx::PxReal>(torque_z)
        ),
        physx::PxForceMode::eFORCE,
        true
    );
    return true;
#else
    return false;
#endif
}

bool PhysXContext::apply_force_at_point(
    std::uint64_t body_handle,
    double force_x,
    double force_y,
    double force_z,
    double point_x,
    double point_y,
    double point_z
) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    physx::PxRigidActor* body = from_handle<physx::PxRigidActor>(body_handle);
    if (body == nullptr) {
        return false;
    }
    physx::PxRigidDynamic* dynamic = body->is<physx::PxRigidDynamic>();
    if (dynamic == nullptr) {
        return false;
    }
    physx::PxRigidBodyExt::addForceAtPos(
        *dynamic,
        physx::PxVec3(
            static_cast<physx::PxReal>(force_x),
            static_cast<physx::PxReal>(force_y),
            static_cast<physx::PxReal>(force_z)
        ),
        physx::PxVec3(
            static_cast<physx::PxReal>(point_x),
            static_cast<physx::PxReal>(point_y),
            static_cast<physx::PxReal>(point_z)
        ),
        physx::PxForceMode::eFORCE,
        true
    );
    return true;
#else
    return false;
#endif
}

std::uint64_t PhysXContext::create_fixed_joint(
    WorldHandle world_handle,
    std::uint64_t first_body_handle,
    std::uint64_t second_body_handle,
    bool collide_connected,
    float break_force,
    float break_torque
) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    if (first_body_handle == 0
        || second_body_handle == 0
        || first_body_handle == second_body_handle
        || !finite_positive(break_force)
        || !finite_positive(break_torque)) {
        return 0;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    auto found = worlds_.find(world_handle);
    if (found == worlds_.end() || found->second->scene == nullptr || physics_ == nullptr) {
        return 0;
    }

    physx::PxRigidActor* first_body = from_handle<physx::PxRigidActor>(first_body_handle);
    physx::PxRigidActor* second_body = from_handle<physx::PxRigidActor>(second_body_handle);
    if (first_body == nullptr
        || second_body == nullptr
        || first_body->getScene() != found->second->scene
        || second_body->getScene() != found->second->scene) {
        return 0;
    }

    const physx::PxTransform first_pose = first_body->getGlobalPose();
    const physx::PxTransform second_pose = second_body->getGlobalPose();
    const physx::PxTransform first_local_frame(physx::PxIdentity);
    const physx::PxTransform second_local_frame = second_pose.transformInv(first_pose);

    physx::PxFixedJoint* joint = physx::PxFixedJointCreate(
        *physics_,
        first_body,
        first_local_frame,
        second_body,
        second_local_frame
    );
    if (joint == nullptr) {
        return 0;
    }
    joint->setConstraintFlag(physx::PxConstraintFlag::eCOLLISION_ENABLED, collide_connected);
    joint->setBreakForce(break_force, break_torque);

    auto state = std::make_unique<JointState>();
    state->joint = joint;
    state->first_body = first_body;
    state->second_body = second_body;
    std::uint64_t handle = next_handle();
    found->second->joints.emplace(handle, std::move(state));
    return handle;
#else
    return 0;
#endif
}

std::uint64_t PhysXContext::create_fixed_joint_with_local_frames(
    WorldHandle world_handle,
    std::uint64_t first_body_handle,
    std::uint64_t second_body_handle,
    const double* local_frames,
    bool collide_connected,
    float break_force,
    float break_torque
) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    if (first_body_handle == 0
        || second_body_handle == 0
        || first_body_handle == second_body_handle
        || !finite_positive(break_force)
        || !finite_positive(break_torque)
        || !finite_transform_values(local_frames, 0)
        || !finite_transform_values(local_frames, 7)) {
        return 0;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    auto found = worlds_.find(world_handle);
    if (found == worlds_.end() || found->second->scene == nullptr || physics_ == nullptr) {
        return 0;
    }

    physx::PxRigidActor* first_body = from_handle<physx::PxRigidActor>(first_body_handle);
    physx::PxRigidActor* second_body = from_handle<physx::PxRigidActor>(second_body_handle);
    if (first_body == nullptr
        || second_body == nullptr
        || first_body->getScene() != found->second->scene
        || second_body->getScene() != found->second->scene) {
        return 0;
    }

    physx::PxFixedJoint* joint = physx::PxFixedJointCreate(
        *physics_,
        first_body,
        make_transform(
            local_frames[0],
            local_frames[1],
            local_frames[2],
            local_frames[3],
            local_frames[4],
            local_frames[5],
            local_frames[6]
        ),
        second_body,
        make_transform(
            local_frames[7],
            local_frames[8],
            local_frames[9],
            local_frames[10],
            local_frames[11],
            local_frames[12],
            local_frames[13]
        )
    );
    if (joint == nullptr) {
        return 0;
    }
    joint->setConstraintFlag(physx::PxConstraintFlag::eCOLLISION_ENABLED, collide_connected);
    joint->setBreakForce(break_force, break_torque);

    auto state = std::make_unique<JointState>();
    state->joint = joint;
    state->first_body = first_body;
    state->second_body = second_body;
    std::uint64_t handle = next_handle();
    found->second->joints.emplace(handle, std::move(state));
    return handle;
#else
    return 0;
#endif
}

std::uint64_t PhysXContext::create_distance_joint(
    WorldHandle world_handle,
    std::uint64_t first_body_handle,
    std::uint64_t second_body_handle,
    float min_distance,
    float max_distance,
    float stiffness,
    float damping,
    bool collide_connected,
    float break_force,
    float break_torque
) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    if (first_body_handle == 0
        || second_body_handle == 0
        || first_body_handle == second_body_handle
        || !finite_non_negative(min_distance)
        || !finite_positive(max_distance)
        || min_distance > max_distance
        || !finite_non_negative(stiffness)
        || !finite_non_negative(damping)
        || !finite_positive(break_force)
        || !finite_positive(break_torque)) {
        return 0;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    auto found = worlds_.find(world_handle);
    if (found == worlds_.end() || found->second->scene == nullptr || physics_ == nullptr) {
        return 0;
    }

    physx::PxRigidActor* first_body = from_handle<physx::PxRigidActor>(first_body_handle);
    physx::PxRigidActor* second_body = from_handle<physx::PxRigidActor>(second_body_handle);
    if (first_body == nullptr
        || second_body == nullptr
        || first_body->getScene() != found->second->scene
        || second_body->getScene() != found->second->scene) {
        return 0;
    }

    physx::PxDistanceJoint* joint = physx::PxDistanceJointCreate(
        *physics_,
        first_body,
        physx::PxTransform(physx::PxIdentity),
        second_body,
        physx::PxTransform(physx::PxIdentity)
    );
    if (joint == nullptr) {
        return 0;
    }

    joint->setMinDistance(min_distance);
    joint->setMaxDistance(max_distance);
    joint->setTolerance(0.01f);
    joint->setStiffness(stiffness);
    joint->setDamping(damping);
    joint->setDistanceJointFlag(physx::PxDistanceJointFlag::eMIN_DISTANCE_ENABLED, true);
    joint->setDistanceJointFlag(physx::PxDistanceJointFlag::eMAX_DISTANCE_ENABLED, true);
    joint->setDistanceJointFlag(physx::PxDistanceJointFlag::eSPRING_ENABLED, stiffness > 0.0f || damping > 0.0f);
    joint->setConstraintFlag(physx::PxConstraintFlag::eCOLLISION_ENABLED, collide_connected);
    joint->setBreakForce(break_force, break_torque);

    auto state = std::make_unique<JointState>();
    state->joint = joint;
    state->first_body = first_body;
    state->second_body = second_body;
    std::uint64_t handle = next_handle();
    found->second->joints.emplace(handle, std::move(state));
    return handle;
#else
    return 0;
#endif
}

std::uint64_t PhysXContext::create_distance_joint_with_local_anchors(
    WorldHandle world_handle,
    std::uint64_t first_body_handle,
    std::uint64_t second_body_handle,
    const double* local_anchors,
    float min_distance,
    float max_distance,
    float stiffness,
    float damping,
    bool collide_connected,
    float break_force,
    float break_torque
) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    if (first_body_handle == 0
        || second_body_handle == 0
        || first_body_handle == second_body_handle
        || !finite_vector_values(local_anchors, 0)
        || !finite_vector_values(local_anchors, 3)
        || !finite_non_negative(min_distance)
        || !finite_positive(max_distance)
        || min_distance > max_distance
        || !finite_non_negative(stiffness)
        || !finite_non_negative(damping)
        || !finite_positive(break_force)
        || !finite_positive(break_torque)) {
        return 0;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    auto found = worlds_.find(world_handle);
    if (found == worlds_.end() || found->second->scene == nullptr || physics_ == nullptr) {
        return 0;
    }

    physx::PxRigidActor* first_body = from_handle<physx::PxRigidActor>(first_body_handle);
    physx::PxRigidActor* second_body = from_handle<physx::PxRigidActor>(second_body_handle);
    if (first_body == nullptr
        || second_body == nullptr
        || first_body->getScene() != found->second->scene
        || second_body->getScene() != found->second->scene) {
        return 0;
    }

    physx::PxDistanceJoint* joint = physx::PxDistanceJointCreate(
        *physics_,
        first_body,
        make_transform(local_anchors[0], local_anchors[1], local_anchors[2], 0.0, 0.0, 0.0, 1.0),
        second_body,
        make_transform(local_anchors[3], local_anchors[4], local_anchors[5], 0.0, 0.0, 0.0, 1.0)
    );
    if (joint == nullptr) {
        return 0;
    }

    joint->setMinDistance(min_distance);
    joint->setMaxDistance(max_distance);
    joint->setTolerance(0.01f);
    joint->setStiffness(stiffness);
    joint->setDamping(damping);
    joint->setDistanceJointFlag(physx::PxDistanceJointFlag::eMIN_DISTANCE_ENABLED, true);
    joint->setDistanceJointFlag(physx::PxDistanceJointFlag::eMAX_DISTANCE_ENABLED, true);
    joint->setDistanceJointFlag(physx::PxDistanceJointFlag::eSPRING_ENABLED, stiffness > 0.0f || damping > 0.0f);
    joint->setConstraintFlag(physx::PxConstraintFlag::eCOLLISION_ENABLED, collide_connected);
    joint->setBreakForce(break_force, break_torque);

    auto state = std::make_unique<JointState>();
    state->joint = joint;
    state->first_body = first_body;
    state->second_body = second_body;
    std::uint64_t handle = next_handle();
    found->second->joints.emplace(handle, std::move(state));
    return handle;
#else
    return 0;
#endif
}

std::uint64_t PhysXContext::create_revolute_joint_with_local_frames(
    WorldHandle world_handle,
    std::uint64_t first_body_handle,
    std::uint64_t second_body_handle,
    const double* local_frames,
    bool collide_connected,
    float break_force,
    float break_torque
) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    if (first_body_handle == 0
        || second_body_handle == 0
        || first_body_handle == second_body_handle
        || !finite_positive(break_force)
        || !finite_positive(break_torque)
        || !finite_transform_values(local_frames, 0)
        || !finite_transform_values(local_frames, 7)) {
        return 0;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    auto found = worlds_.find(world_handle);
    if (found == worlds_.end() || found->second->scene == nullptr || physics_ == nullptr) {
        return 0;
    }

    physx::PxRigidActor* first_body = from_handle<physx::PxRigidActor>(first_body_handle);
    physx::PxRigidActor* second_body = from_handle<physx::PxRigidActor>(second_body_handle);
    if (first_body == nullptr
        || second_body == nullptr
        || first_body->getScene() != found->second->scene
        || second_body->getScene() != found->second->scene) {
        return 0;
    }

    physx::PxRevoluteJoint* joint = physx::PxRevoluteJointCreate(
        *physics_,
        first_body,
        make_transform(
            local_frames[0],
            local_frames[1],
            local_frames[2],
            local_frames[3],
            local_frames[4],
            local_frames[5],
            local_frames[6]
        ),
        second_body,
        make_transform(
            local_frames[7],
            local_frames[8],
            local_frames[9],
            local_frames[10],
            local_frames[11],
            local_frames[12],
            local_frames[13]
        )
    );
    if (joint == nullptr) {
        return 0;
    }
    joint->setConstraintFlag(physx::PxConstraintFlag::eCOLLISION_ENABLED, collide_connected);
    joint->setBreakForce(break_force, break_torque);

    auto state = std::make_unique<JointState>();
    state->joint = joint;
    state->first_body = first_body;
    state->second_body = second_body;
    std::uint64_t handle = next_handle();
    found->second->joints.emplace(handle, std::move(state));
    return handle;
#else
    return 0;
#endif
}

std::uint64_t PhysXContext::create_prismatic_joint_with_local_frames(
    WorldHandle world_handle,
    std::uint64_t first_body_handle,
    std::uint64_t second_body_handle,
    const double* local_frames,
    bool collide_connected,
    float break_force,
    float break_torque
) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    if (first_body_handle == 0
        || second_body_handle == 0
        || first_body_handle == second_body_handle
        || !finite_positive(break_force)
        || !finite_positive(break_torque)
        || !finite_transform_values(local_frames, 0)
        || !finite_transform_values(local_frames, 7)) {
        return 0;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    auto found = worlds_.find(world_handle);
    if (found == worlds_.end() || found->second->scene == nullptr || physics_ == nullptr) {
        return 0;
    }

    physx::PxRigidActor* first_body = from_handle<physx::PxRigidActor>(first_body_handle);
    physx::PxRigidActor* second_body = from_handle<physx::PxRigidActor>(second_body_handle);
    if (first_body == nullptr
        || second_body == nullptr
        || first_body->getScene() != found->second->scene
        || second_body->getScene() != found->second->scene) {
        return 0;
    }

    physx::PxPrismaticJoint* joint = physx::PxPrismaticJointCreate(
        *physics_,
        first_body,
        make_transform(
            local_frames[0],
            local_frames[1],
            local_frames[2],
            local_frames[3],
            local_frames[4],
            local_frames[5],
            local_frames[6]
        ),
        second_body,
        make_transform(
            local_frames[7],
            local_frames[8],
            local_frames[9],
            local_frames[10],
            local_frames[11],
            local_frames[12],
            local_frames[13]
        )
    );
    if (joint == nullptr) {
        return 0;
    }
    joint->setConstraintFlag(physx::PxConstraintFlag::eCOLLISION_ENABLED, collide_connected);
    joint->setBreakForce(break_force, break_torque);

    auto state = std::make_unique<JointState>();
    state->joint = joint;
    state->first_body = first_body;
    state->second_body = second_body;
    std::uint64_t handle = next_handle();
    found->second->joints.emplace(handle, std::move(state));
    return handle;
#else
    return 0;
#endif
}

void PhysXContext::destroy_joint(std::uint64_t joint_handle) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    std::lock_guard<std::mutex> lock(mutex_);
    for (auto& world : worlds_) {
        auto found = world.second->joints.find(joint_handle);
        if (found == world.second->joints.end()) {
            continue;
        }
        release_joint(*found->second);
        world.second->joints.erase(found);
        return;
    }
#else
    (void) joint_handle;
#endif
}

void PhysXContext::destroy_body(std::uint64_t body_handle) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    physx::PxRigidActor* body = from_handle<physx::PxRigidActor>(body_handle);
    if (body != nullptr) {
        body->release();
    }
#endif
}

void PhysXContext::destroy_shape(std::uint64_t shape_handle) {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    physx::PxShape* shape = from_handle<physx::PxShape>(shape_handle);
    if (shape != nullptr) {
        shape->release();
    }
#endif
}

#if KINETIC_ASSEMBLY_WITH_PHYSX
bool PhysXContext::ensure_initialized() {
    if (foundation_ != nullptr && physics_ != nullptr) {
        return true;
    }

    foundation_ = PxCreateFoundation(PX_PHYSICS_VERSION, allocator_, error_callback_);
    if (foundation_ == nullptr) {
        return false;
    }

    physics_ = PxCreatePhysics(PX_PHYSICS_VERSION, *foundation_, physx::PxTolerancesScale(), false, nullptr);
    if (physics_ == nullptr) {
        release_px(foundation_);
        return false;
    }

    if (!extensions_initialized_) {
        if (!PxInitExtensions(*physics_, nullptr)) {
            release_px(physics_);
            release_px(foundation_);
            return false;
        }
        extensions_initialized_ = true;
    }

    return true;
}

#if PX_SUPPORT_GPU_PHYSX
bool PhysXContext::ensure_cuda_context_manager(std::string& status) {
    if (cuda_context_manager_ != nullptr) {
        status = "shared_cuda_context_ready";
        return true;
    }
    if (cuda_context_manager_attempted_) {
        status = cuda_context_manager_status_;
        return false;
    }

    cuda_context_manager_attempted_ = true;
    physx::PxCudaContextManagerDesc cuda_desc;
    cuda_context_manager_ = PxCreateCudaContextManager(*foundation_, cuda_desc);
    if (cuda_context_manager_ == nullptr) {
        cuda_context_manager_status_ = "cuda_context_manager_unavailable";
        status = cuda_context_manager_status_;
        return false;
    }
    if (!cuda_context_manager_->contextIsValid()) {
        release_px(cuda_context_manager_);
        cuda_context_manager_status_ = "cuda_context_invalid";
        status = cuda_context_manager_status_;
        return false;
    }

    cuda_context_manager_status_ = "shared_cuda_context_ready";
    status = cuda_context_manager_status_;
    return true;
}
#else
bool PhysXContext::ensure_cuda_context_manager(std::string& status) {
    status = "unsupported_platform";
    return false;
}
#endif

void PhysXContext::release_world(WorldState& world) {
    for (auto& entry : world.joints) {
        release_joint(*entry.second);
    }
    world.joints.clear();
    for (auto& entry : world.deformable_volumes) {
        release_deformable_volume(*entry.second);
    }
    world.deformable_volumes.clear();
    release_px(world.scene);
    release_px(world.material);
    release_px(world.dispatcher);
#if PX_SUPPORT_GPU_PHYSX
    if (world.gpu_dynamics_enabled) {
        world.gpu_dynamics_enabled = false;
        gpu_world_count_--;
        if (gpu_world_count_ <= 0) {
            gpu_world_count_ = 0;
            release_px(cuda_context_manager_);
            cuda_context_manager_attempted_ = false;
            cuda_context_manager_status_ = "not_requested";
        }
    }
#endif
}

void PhysXContext::release_joint(JointState& joint) {
    release_px(joint.joint);
    joint.first_body = nullptr;
    joint.second_body = nullptr;
}

void PhysXContext::release_deformable_volume(DeformableVolumeState& deformable_volume) {
#if KINETIC_ASSEMBLY_WITH_DEFORMABLE_VOLUME && PX_SUPPORT_GPU_PHYSX
    release_px(deformable_volume.volume);
    release_px(deformable_volume.material);
    if (deformable_volume.collision_positions_pinned != nullptr && deformable_volume.cuda_context_manager != nullptr) {
        PX_EXT_PINNED_MEMORY_FREE(*deformable_volume.cuda_context_manager, deformable_volume.collision_positions_pinned);
        deformable_volume.collision_positions_pinned = nullptr;
    }
    deformable_volume.cuda_context_manager = nullptr;
    deformable_volume.collision_vertex_count = 0;
    deformable_volume.collision_tetrahedron_count = 0;
    deformable_volume.simulation_vertex_count = 0;
    deformable_volume.simulation_tetrahedron_count = 0;
#else
    (void) deformable_volume;
#endif
}

DeformableVolumeState* PhysXContext::find_deformable_volume(std::uint64_t deformable_volume) {
    for (auto& world : worlds_) {
        auto found = world.second->deformable_volumes.find(deformable_volume);
        if (found != world.second->deformable_volumes.end()) {
            return found->second.get();
        }
    }
    return nullptr;
}
#endif

bool is_physx_linked() {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    return true;
#else
    return false;
#endif
}

PhysXContext& context() {
    static PhysXContext instance;
    return instance;
}
}
