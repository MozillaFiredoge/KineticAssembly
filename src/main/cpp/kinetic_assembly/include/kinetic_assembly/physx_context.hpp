#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

#ifndef KINETIC_ASSEMBLY_WITH_PHYSX
#define KINETIC_ASSEMBLY_WITH_PHYSX 0
#endif

#ifndef KINETIC_ASSEMBLY_WITH_DEFORMABLE_VOLUME
#define KINETIC_ASSEMBLY_WITH_DEFORMABLE_VOLUME 0
#endif

#if KINETIC_ASSEMBLY_WITH_PHYSX
#include "PxPhysicsAPI.h"
#endif

namespace kinetic_assembly {
using WorldHandle = std::uint64_t;

struct DeformableVolumeState;
struct JointState;

struct WorldState {
#if KINETIC_ASSEMBLY_WITH_PHYSX
    physx::PxScene* scene = nullptr;
    physx::PxDefaultCpuDispatcher* dispatcher = nullptr;
    physx::PxMaterial* material = nullptr;
    std::unordered_map<std::uint64_t, std::unique_ptr<DeformableVolumeState>> deformable_volumes;
    std::unordered_map<std::uint64_t, std::unique_ptr<JointState>> joints;
    bool gpu_dynamics_requested = false;
    bool gpu_dynamics_enabled = false;
    std::string gpu_dynamics_status = "not_requested";
#endif
};

class PhysXContext {
public:
    PhysXContext() = default;
    ~PhysXContext();

    std::uint64_t next_handle();
    WorldHandle create_world(double gravity_x, double gravity_y, double gravity_z, float fixed_time_step, int max_sub_steps, bool enable_gpu_dynamics);
    void destroy_world(WorldHandle handle);
    void step_world(WorldHandle handle, float delta_seconds);
    bool is_world_gpu_dynamics_enabled(WorldHandle handle);
    std::string world_gpu_dynamics_status(WorldHandle handle);
    std::uint64_t create_box_shape(WorldHandle world, float half_extent_x, float half_extent_y, float half_extent_z);
    std::uint64_t create_static_plane(WorldHandle world, double normal_x, double normal_y, double normal_z, double distance);
    std::uint64_t create_static_body(WorldHandle world, std::uint64_t shape, double position_x, double position_y, double position_z, double rotation_x, double rotation_y, double rotation_z, double rotation_w);
    std::uint64_t create_dynamic_body(WorldHandle world, std::uint64_t shape, double position_x, double position_y, double position_z, double rotation_x, double rotation_y, double rotation_z, double rotation_w, float mass);
    std::uint64_t create_dynamic_compound_box_body(WorldHandle world, const double* boxes, int box_count, double position_x, double position_y, double position_z, double rotation_x, double rotation_y, double rotation_z, double rotation_w, float mass);
    std::uint64_t create_dynamic_compound_box_body_with_mass_properties(WorldHandle world, const double* boxes, int box_count, double position_x, double position_y, double position_z, double rotation_x, double rotation_y, double rotation_z, double rotation_w, float mass, double center_of_mass_x, double center_of_mass_y, double center_of_mass_z, double inertia_x, double inertia_y, double inertia_z);
    std::uint64_t create_deformable_volume_box(WorldHandle world, double center_x, double center_y, double center_z, float width, float height, float depth, float density, float youngs, float poissons, float dynamic_friction, float damping, float max_edge_length, int voxels);
    int get_deformable_volume_info(std::uint64_t deformable_volume, int* output);
    int get_deformable_volume_vertices(std::uint64_t deformable_volume, double* output, int max_vertices);
    void destroy_deformable_volume(std::uint64_t deformable_volume);
    bool read_body_states(WorldHandle world, const std::uint64_t* bodies, int body_count, double* output);
    static bool get_body_pose(std::uint64_t body, double* output);
    static void set_body_pose(std::uint64_t body, double position_x, double position_y, double position_z, double rotation_x, double rotation_y, double rotation_z, double rotation_w);
    static void set_linear_velocity(std::uint64_t body, double velocity_x, double velocity_y, double velocity_z);
    static bool get_linear_velocity(std::uint64_t body, double* output);
    static void set_angular_velocity(std::uint64_t body, double velocity_x, double velocity_y, double velocity_z);
    static bool get_angular_velocity(std::uint64_t body, double* output);
    static bool apply_linear_impulse(std::uint64_t body, double impulse_x, double impulse_y, double impulse_z);
    static bool apply_angular_impulse(std::uint64_t body, double impulse_x, double impulse_y, double impulse_z);
    static bool apply_impulse_at_point(std::uint64_t body, double impulse_x, double impulse_y, double impulse_z, double point_x, double point_y, double point_z);
    static bool apply_force(std::uint64_t body, double force_x, double force_y, double force_z);
    static bool apply_torque(std::uint64_t body, double torque_x, double torque_y, double torque_z);
    static bool apply_force_at_point(std::uint64_t body, double force_x, double force_y, double force_z, double point_x, double point_y, double point_z);
    std::uint64_t create_fixed_joint(WorldHandle world, std::uint64_t first_body, std::uint64_t second_body, bool collide_connected, float break_force, float break_torque);
    std::uint64_t create_fixed_joint_with_local_frames(WorldHandle world, std::uint64_t first_body, std::uint64_t second_body, const double* local_frames, bool collide_connected, float break_force, float break_torque);
    std::uint64_t create_distance_joint(WorldHandle world, std::uint64_t first_body, std::uint64_t second_body, float min_distance, float max_distance, float stiffness, float damping, bool collide_connected, float break_force, float break_torque);
    std::uint64_t create_distance_joint_with_local_anchors(WorldHandle world, std::uint64_t first_body, std::uint64_t second_body, const double* local_anchors, float min_distance, float max_distance, float stiffness, float damping, bool collide_connected, float break_force, float break_torque);
    std::uint64_t create_revolute_joint_with_local_frames(WorldHandle world, std::uint64_t first_body, std::uint64_t second_body, const double* local_frames, bool collide_connected, float break_force, float break_torque);
    std::uint64_t create_prismatic_joint_with_local_frames(WorldHandle world, std::uint64_t first_body, std::uint64_t second_body, const double* local_frames, bool collide_connected, float break_force, float break_torque);
    void destroy_joint(std::uint64_t joint);
    static void destroy_body(std::uint64_t body);
    static void destroy_shape(std::uint64_t shape);

private:
#if KINETIC_ASSEMBLY_WITH_PHYSX
    bool ensure_initialized();
    bool ensure_cuda_context_manager(std::string& status);
    void release_world(WorldState& world);
    void release_deformable_volume(DeformableVolumeState& deformable_volume);
    static void release_joint(JointState& joint);
    DeformableVolumeState* find_deformable_volume(std::uint64_t deformable_volume);

    physx::PxDefaultAllocator allocator_;
    physx::PxDefaultErrorCallback error_callback_;
    physx::PxFoundation* foundation_ = nullptr;
    physx::PxPhysics* physics_ = nullptr;
    bool extensions_initialized_ = false;
#if PX_SUPPORT_GPU_PHYSX
    physx::PxCudaContextManager* cuda_context_manager_ = nullptr;
    bool cuda_context_manager_attempted_ = false;
    int gpu_world_count_ = 0;
    std::string cuda_context_manager_status_ = "not_requested";
#endif
#endif

    std::mutex mutex_;
    std::atomic_uint64_t next_handle_{1};
    std::unordered_map<WorldHandle, std::unique_ptr<WorldState>> worlds_;
};

bool is_physx_linked();
PhysXContext& context();
}
