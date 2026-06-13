#include "kinetic_assembly/physx_world.hpp"

#include "kinetic_assembly/physx_context.hpp"

namespace kinetic_assembly {
WorldHandle create_world(double gravity_x, double gravity_y, double gravity_z, float fixed_time_step, int max_sub_steps, bool enable_gpu_dynamics) {
    return context().create_world(gravity_x, gravity_y, gravity_z, fixed_time_step, max_sub_steps, enable_gpu_dynamics);
}

void destroy_world(WorldHandle handle) {
    context().destroy_world(handle);
}

void step_world(WorldHandle handle, float delta_seconds) {
    context().step_world(handle, delta_seconds);
}

bool is_world_gpu_dynamics_enabled(WorldHandle handle) {
    return context().is_world_gpu_dynamics_enabled(handle);
}

std::string world_gpu_dynamics_status(WorldHandle handle) {
    return context().world_gpu_dynamics_status(handle);
}

std::uint64_t create_box_shape(WorldHandle world, float half_extent_x, float half_extent_y, float half_extent_z) {
    return context().create_box_shape(world, half_extent_x, half_extent_y, half_extent_z);
}

std::uint64_t create_static_plane(WorldHandle world, double normal_x, double normal_y, double normal_z, double distance) {
    return context().create_static_plane(world, normal_x, normal_y, normal_z, distance);
}

std::uint64_t create_static_body(
    WorldHandle world,
    std::uint64_t shape,
    double position_x,
    double position_y,
    double position_z,
    double rotation_x,
    double rotation_y,
    double rotation_z,
    double rotation_w
) {
    return context().create_static_body(
        world,
        shape,
        position_x,
        position_y,
        position_z,
        rotation_x,
        rotation_y,
        rotation_z,
        rotation_w
    );
}

std::uint64_t create_dynamic_body(
    WorldHandle world,
    std::uint64_t shape,
    double position_x,
    double position_y,
    double position_z,
    double rotation_x,
    double rotation_y,
    double rotation_z,
    double rotation_w,
    float mass
) {
    return context().create_dynamic_body(
        world,
        shape,
        position_x,
        position_y,
        position_z,
        rotation_x,
        rotation_y,
        rotation_z,
        rotation_w,
        mass
    );
}

std::uint64_t create_dynamic_compound_box_body(
    WorldHandle world,
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
    return context().create_dynamic_compound_box_body(
        world,
        boxes,
        box_count,
        position_x,
        position_y,
        position_z,
        rotation_x,
        rotation_y,
        rotation_z,
        rotation_w,
        mass
    );
}

std::uint64_t create_dynamic_compound_box_body_with_mass_properties(
    WorldHandle world,
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
    return context().create_dynamic_compound_box_body_with_mass_properties(
        world,
        boxes,
        box_count,
        position_x,
        position_y,
        position_z,
        rotation_x,
        rotation_y,
        rotation_z,
        rotation_w,
        mass,
        center_of_mass_x,
        center_of_mass_y,
        center_of_mass_z,
        inertia_x,
        inertia_y,
        inertia_z
    );
}

std::uint64_t create_deformable_volume_box(
    WorldHandle world,
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
    return context().create_deformable_volume_box(
        world,
        center_x,
        center_y,
        center_z,
        width,
        height,
        depth,
        density,
        youngs,
        poissons,
        dynamic_friction,
        damping,
        max_edge_length,
        voxels
    );
}

int get_deformable_volume_info(std::uint64_t deformable_volume, int* output) {
    return context().get_deformable_volume_info(deformable_volume, output);
}

int get_deformable_volume_vertices(std::uint64_t deformable_volume, double* output, int max_vertices) {
    return context().get_deformable_volume_vertices(deformable_volume, output, max_vertices);
}

void destroy_deformable_volume(std::uint64_t deformable_volume) {
    context().destroy_deformable_volume(deformable_volume);
}

bool read_body_states(WorldHandle world, const std::uint64_t* bodies, int body_count, double* output) {
    return context().read_body_states(world, bodies, body_count, output);
}

bool get_body_pose(std::uint64_t body, double* output) {
    return PhysXContext::get_body_pose(body, output);
}

void set_body_pose(
    std::uint64_t body,
    double position_x,
    double position_y,
    double position_z,
    double rotation_x,
    double rotation_y,
    double rotation_z,
    double rotation_w
) {
    PhysXContext::set_body_pose(
        body,
        position_x,
        position_y,
        position_z,
        rotation_x,
        rotation_y,
        rotation_z,
        rotation_w
    );
}

void set_linear_velocity(std::uint64_t body, double velocity_x, double velocity_y, double velocity_z) {
    PhysXContext::set_linear_velocity(body, velocity_x, velocity_y, velocity_z);
}

bool get_linear_velocity(std::uint64_t body, double* output) {
    return PhysXContext::get_linear_velocity(body, output);
}

void set_angular_velocity(std::uint64_t body, double velocity_x, double velocity_y, double velocity_z) {
    PhysXContext::set_angular_velocity(body, velocity_x, velocity_y, velocity_z);
}

bool get_angular_velocity(std::uint64_t body, double* output) {
    return PhysXContext::get_angular_velocity(body, output);
}

bool apply_linear_impulse(std::uint64_t body, double impulse_x, double impulse_y, double impulse_z) {
    return PhysXContext::apply_linear_impulse(body, impulse_x, impulse_y, impulse_z);
}

bool apply_angular_impulse(std::uint64_t body, double impulse_x, double impulse_y, double impulse_z) {
    return PhysXContext::apply_angular_impulse(body, impulse_x, impulse_y, impulse_z);
}

bool apply_impulse_at_point(std::uint64_t body, double impulse_x, double impulse_y, double impulse_z, double point_x, double point_y, double point_z) {
    return PhysXContext::apply_impulse_at_point(body, impulse_x, impulse_y, impulse_z, point_x, point_y, point_z);
}

std::uint64_t create_fixed_joint(
    WorldHandle world,
    std::uint64_t first_body,
    std::uint64_t second_body,
    bool collide_connected,
    float break_force,
    float break_torque
) {
    return context().create_fixed_joint(world, first_body, second_body, collide_connected, break_force, break_torque);
}

std::uint64_t create_fixed_joint_with_local_frames(
    WorldHandle world,
    std::uint64_t first_body,
    std::uint64_t second_body,
    const double* local_frames,
    bool collide_connected,
    float break_force,
    float break_torque
) {
    return context().create_fixed_joint_with_local_frames(world, first_body, second_body, local_frames, collide_connected, break_force, break_torque);
}

std::uint64_t create_distance_joint(
    WorldHandle world,
    std::uint64_t first_body,
    std::uint64_t second_body,
    float min_distance,
    float max_distance,
    float stiffness,
    float damping,
    bool collide_connected,
    float break_force,
    float break_torque
) {
    return context().create_distance_joint(
        world,
        first_body,
        second_body,
        min_distance,
        max_distance,
        stiffness,
        damping,
        collide_connected,
        break_force,
        break_torque
    );
}

std::uint64_t create_distance_joint_with_local_anchors(
    WorldHandle world,
    std::uint64_t first_body,
    std::uint64_t second_body,
    const double* local_anchors,
    float min_distance,
    float max_distance,
    float stiffness,
    float damping,
    bool collide_connected,
    float break_force,
    float break_torque
) {
    return context().create_distance_joint_with_local_anchors(
        world,
        first_body,
        second_body,
        local_anchors,
        min_distance,
        max_distance,
        stiffness,
        damping,
        collide_connected,
        break_force,
        break_torque
    );
}

std::uint64_t create_revolute_joint_with_local_frames(
    WorldHandle world,
    std::uint64_t first_body,
    std::uint64_t second_body,
    const double* local_frames,
    bool collide_connected,
    float break_force,
    float break_torque
) {
    return context().create_revolute_joint_with_local_frames(
        world,
        first_body,
        second_body,
        local_frames,
        collide_connected,
        break_force,
        break_torque
    );
}

std::uint64_t create_prismatic_joint_with_local_frames(
    WorldHandle world,
    std::uint64_t first_body,
    std::uint64_t second_body,
    const double* local_frames,
    bool collide_connected,
    float break_force,
    float break_torque
) {
    return context().create_prismatic_joint_with_local_frames(
        world,
        first_body,
        second_body,
        local_frames,
        collide_connected,
        break_force,
        break_torque
    );
}

void destroy_joint(std::uint64_t joint) {
    context().destroy_joint(joint);
}

void destroy_body(std::uint64_t body) {
    PhysXContext::destroy_body(body);
}

void destroy_shape(std::uint64_t shape) {
    PhysXContext::destroy_shape(shape);
}
}
