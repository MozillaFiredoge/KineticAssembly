#include "kinetic_assembly/jni_bridge.hpp"

#include "kinetic_assembly/physx_world.hpp"

#include <vector>

JNIEXPORT jboolean JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeIsPhysXLinked(
    JNIEnv*,
    jclass
) {
    return kinetic_assembly::is_physx_linked() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateWorld(
    JNIEnv*,
    jclass,
    jdouble gravity_x,
    jdouble gravity_y,
    jdouble gravity_z,
    jfloat fixed_time_step,
    jint max_sub_steps,
    jboolean enable_gpu_dynamics
) {
    return static_cast<jlong>(kinetic_assembly::create_world(
        gravity_x,
        gravity_y,
        gravity_z,
        fixed_time_step,
        max_sub_steps,
        enable_gpu_dynamics == JNI_TRUE
    ));
}

JNIEXPORT void JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeDestroyWorld(
    JNIEnv*,
    jclass,
    jlong world_handle
) {
    kinetic_assembly::destroy_world(static_cast<kinetic_assembly::WorldHandle>(world_handle));
}

JNIEXPORT void JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeStepWorld(
    JNIEnv*,
    jclass,
    jlong world_handle,
    jfloat delta_seconds
) {
    kinetic_assembly::step_world(static_cast<kinetic_assembly::WorldHandle>(world_handle), delta_seconds);
}

JNIEXPORT jboolean JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeIsWorldGpuDynamicsEnabled(
    JNIEnv*,
    jclass,
    jlong world_handle
) {
    return kinetic_assembly::is_world_gpu_dynamics_enabled(static_cast<kinetic_assembly::WorldHandle>(world_handle)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeGetWorldGpuDynamicsStatus(
    JNIEnv* env,
    jclass,
    jlong world_handle
) {
    return env->NewStringUTF(kinetic_assembly::world_gpu_dynamics_status(static_cast<kinetic_assembly::WorldHandle>(world_handle)).c_str());
}

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateBoxShape(
    JNIEnv*,
    jclass,
    jlong world_handle,
    jfloat half_extent_x,
    jfloat half_extent_y,
    jfloat half_extent_z
) {
    return static_cast<jlong>(kinetic_assembly::create_box_shape(
        static_cast<kinetic_assembly::WorldHandle>(world_handle),
        half_extent_x,
        half_extent_y,
        half_extent_z
    ));
}

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateStaticPlane(
    JNIEnv*,
    jclass,
    jlong world_handle,
    jdouble normal_x,
    jdouble normal_y,
    jdouble normal_z,
    jdouble distance
) {
    return static_cast<jlong>(kinetic_assembly::create_static_plane(
        static_cast<kinetic_assembly::WorldHandle>(world_handle),
        normal_x,
        normal_y,
        normal_z,
        distance
    ));
}

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateStaticBody(
    JNIEnv*,
    jclass,
    jlong world_handle,
    jlong shape_handle,
    jdouble position_x,
    jdouble position_y,
    jdouble position_z,
    jdouble rotation_x,
    jdouble rotation_y,
    jdouble rotation_z,
    jdouble rotation_w
) {
    return static_cast<jlong>(kinetic_assembly::create_static_body(
        static_cast<kinetic_assembly::WorldHandle>(world_handle),
        static_cast<std::uint64_t>(shape_handle),
        position_x,
        position_y,
        position_z,
        rotation_x,
        rotation_y,
        rotation_z,
        rotation_w
    ));
}

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateDynamicBody(
    JNIEnv*,
    jclass,
    jlong world_handle,
    jlong shape_handle,
    jdouble position_x,
    jdouble position_y,
    jdouble position_z,
    jdouble rotation_x,
    jdouble rotation_y,
    jdouble rotation_z,
    jdouble rotation_w,
    jfloat mass
) {
    return static_cast<jlong>(kinetic_assembly::create_dynamic_body(
        static_cast<kinetic_assembly::WorldHandle>(world_handle),
        static_cast<std::uint64_t>(shape_handle),
        position_x,
        position_y,
        position_z,
        rotation_x,
        rotation_y,
        rotation_z,
        rotation_w,
        mass
    ));
}

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateDynamicCompoundBoxBody(
    JNIEnv* env,
    jclass,
    jlong world_handle,
    jdoubleArray boxes,
    jint box_count,
    jdouble position_x,
    jdouble position_y,
    jdouble position_z,
    jdouble rotation_x,
    jdouble rotation_y,
    jdouble rotation_z,
    jdouble rotation_w,
    jfloat mass
) {
    if (boxes == nullptr || box_count <= 0 || env->GetArrayLength(boxes) < box_count * 6) {
        return 0;
    }

    jdouble* values = env->GetDoubleArrayElements(boxes, nullptr);
    if (values == nullptr) {
        return 0;
    }
    std::uint64_t body = kinetic_assembly::create_dynamic_compound_box_body(
        static_cast<kinetic_assembly::WorldHandle>(world_handle),
        values,
        static_cast<int>(box_count),
        position_x,
        position_y,
        position_z,
        rotation_x,
        rotation_y,
        rotation_z,
        rotation_w,
        mass
    );
    env->ReleaseDoubleArrayElements(boxes, values, JNI_ABORT);
    return static_cast<jlong>(body);
}

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateDynamicCompoundBoxBodyWithMassProperties(
    JNIEnv* env,
    jclass,
    jlong world_handle,
    jdoubleArray boxes,
    jint box_count,
    jdouble position_x,
    jdouble position_y,
    jdouble position_z,
    jdouble rotation_x,
    jdouble rotation_y,
    jdouble rotation_z,
    jdouble rotation_w,
    jfloat mass,
    jdouble center_of_mass_x,
    jdouble center_of_mass_y,
    jdouble center_of_mass_z,
    jdouble inertia_x,
    jdouble inertia_y,
    jdouble inertia_z
) {
    if (boxes == nullptr || box_count <= 0 || env->GetArrayLength(boxes) < box_count * 6) {
        return 0;
    }

    jdouble* values = env->GetDoubleArrayElements(boxes, nullptr);
    if (values == nullptr) {
        return 0;
    }
    std::uint64_t body = kinetic_assembly::create_dynamic_compound_box_body_with_mass_properties(
        static_cast<kinetic_assembly::WorldHandle>(world_handle),
        values,
        static_cast<int>(box_count),
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
    env->ReleaseDoubleArrayElements(boxes, values, JNI_ABORT);
    return static_cast<jlong>(body);
}

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateDeformableVolumeBox(
    JNIEnv*,
    jclass,
    jlong world_handle,
    jdouble center_x,
    jdouble center_y,
    jdouble center_z,
    jfloat width,
    jfloat height,
    jfloat depth,
    jfloat density,
    jfloat youngs,
    jfloat poissons,
    jfloat dynamic_friction,
    jfloat damping,
    jfloat max_edge_length,
    jint voxels
) {
    return static_cast<jlong>(kinetic_assembly::create_deformable_volume_box(
        static_cast<kinetic_assembly::WorldHandle>(world_handle),
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
    ));
}

JNIEXPORT jboolean JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeGetDeformableVolumeInfo(
    JNIEnv* env,
    jclass,
    jlong deformable_volume_handle,
    jintArray output
) {
    if (output == nullptr || env->GetArrayLength(output) < 4) {
        return JNI_FALSE;
    }
    int values[4] = {};
    if (kinetic_assembly::get_deformable_volume_info(static_cast<std::uint64_t>(deformable_volume_handle), values) != 4) {
        return JNI_FALSE;
    }
    jint java_values[4] = {
        static_cast<jint>(values[0]),
        static_cast<jint>(values[1]),
        static_cast<jint>(values[2]),
        static_cast<jint>(values[3])
    };
    env->SetIntArrayRegion(output, 0, 4, java_values);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeGetDeformableVolumeVertices(
    JNIEnv* env,
    jclass,
    jlong deformable_volume_handle,
    jdoubleArray output,
    jint max_vertices
) {
    if (output == nullptr || max_vertices <= 0 || env->GetArrayLength(output) < max_vertices * 3) {
        return 0;
    }
    jdouble* values = env->GetDoubleArrayElements(output, nullptr);
    if (values == nullptr) {
        return 0;
    }
    int copied = kinetic_assembly::get_deformable_volume_vertices(
        static_cast<std::uint64_t>(deformable_volume_handle),
        values,
        static_cast<int>(max_vertices)
    );
    env->ReleaseDoubleArrayElements(output, values, 0);
    return static_cast<jint>(copied);
}

JNIEXPORT void JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeDestroyDeformableVolume(
    JNIEnv*,
    jclass,
    jlong deformable_volume_handle
) {
    kinetic_assembly::destroy_deformable_volume(static_cast<std::uint64_t>(deformable_volume_handle));
}

JNIEXPORT jboolean JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeGetBodyPose(
    JNIEnv* env,
    jclass,
    jlong body_handle,
    jdoubleArray output
) {
    if (output == nullptr || env->GetArrayLength(output) < 7) {
        return JNI_FALSE;
    }
    jdouble values[7] = {};
    if (!kinetic_assembly::get_body_pose(static_cast<std::uint64_t>(body_handle), values)) {
        return JNI_FALSE;
    }
    env->SetDoubleArrayRegion(output, 0, 7, values);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeSetBodyPose(
    JNIEnv*,
    jclass,
    jlong body_handle,
    jdouble position_x,
    jdouble position_y,
    jdouble position_z,
    jdouble rotation_x,
    jdouble rotation_y,
    jdouble rotation_z,
    jdouble rotation_w
) {
    kinetic_assembly::set_body_pose(
        static_cast<std::uint64_t>(body_handle),
        position_x,
        position_y,
        position_z,
        rotation_x,
        rotation_y,
        rotation_z,
        rotation_w
    );
}

JNIEXPORT void JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeSetLinearVelocity(
    JNIEnv*,
    jclass,
    jlong body_handle,
    jdouble velocity_x,
    jdouble velocity_y,
    jdouble velocity_z
) {
    kinetic_assembly::set_linear_velocity(
        static_cast<std::uint64_t>(body_handle),
        velocity_x,
        velocity_y,
        velocity_z
    );
}

JNIEXPORT jboolean JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeGetLinearVelocity(
    JNIEnv* env,
    jclass,
    jlong body_handle,
    jdoubleArray output
) {
    if (output == nullptr || env->GetArrayLength(output) < 3) {
        return JNI_FALSE;
    }
    jdouble values[3] = {};
    if (!kinetic_assembly::get_linear_velocity(static_cast<std::uint64_t>(body_handle), values)) {
        return JNI_FALSE;
    }
    env->SetDoubleArrayRegion(output, 0, 3, values);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeSetAngularVelocity(
    JNIEnv*,
    jclass,
    jlong body_handle,
    jdouble velocity_x,
    jdouble velocity_y,
    jdouble velocity_z
) {
    kinetic_assembly::set_angular_velocity(
        static_cast<std::uint64_t>(body_handle),
        velocity_x,
        velocity_y,
        velocity_z
    );
}

JNIEXPORT jboolean JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeGetAngularVelocity(
    JNIEnv* env,
    jclass,
    jlong body_handle,
    jdoubleArray output
) {
    if (output == nullptr || env->GetArrayLength(output) < 3) {
        return JNI_FALSE;
    }
    jdouble values[3] = {};
    if (!kinetic_assembly::get_angular_velocity(static_cast<std::uint64_t>(body_handle), values)) {
        return JNI_FALSE;
    }
    env->SetDoubleArrayRegion(output, 0, 3, values);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeReadBodyStates(
    JNIEnv* env,
    jclass,
    jlong world_handle,
    jlongArray body_handles,
    jdoubleArray output
) {
    if (body_handles == nullptr || output == nullptr) {
        return JNI_FALSE;
    }

    const jsize body_count = env->GetArrayLength(body_handles);
    constexpr int stride = 13;
    if (body_count < 0 || env->GetArrayLength(output) < body_count * stride) {
        return JNI_FALSE;
    }
    if (body_count == 0) {
        return JNI_TRUE;
    }

    jlong* raw_handles = env->GetLongArrayElements(body_handles, nullptr);
    if (raw_handles == nullptr) {
        return JNI_FALSE;
    }
    jdouble* output_values = env->GetDoubleArrayElements(output, nullptr);
    if (output_values == nullptr) {
        env->ReleaseLongArrayElements(body_handles, raw_handles, JNI_ABORT);
        return JNI_FALSE;
    }

    std::vector<std::uint64_t> handles(static_cast<std::size_t>(body_count));
    for (jsize i = 0; i < body_count; i++) {
        handles[static_cast<std::size_t>(i)] = static_cast<std::uint64_t>(raw_handles[i]);
    }

    const bool success = kinetic_assembly::read_body_states(
        static_cast<kinetic_assembly::WorldHandle>(world_handle),
        handles.data(),
        static_cast<int>(body_count),
        output_values
    );

    env->ReleaseLongArrayElements(body_handles, raw_handles, JNI_ABORT);
    env->ReleaseDoubleArrayElements(output, output_values, success ? 0 : JNI_ABORT);
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeApplyLinearImpulse(
    JNIEnv*,
    jclass,
    jlong body_handle,
    jdouble impulse_x,
    jdouble impulse_y,
    jdouble impulse_z
) {
    return kinetic_assembly::apply_linear_impulse(
        static_cast<std::uint64_t>(body_handle),
        impulse_x,
        impulse_y,
        impulse_z
    ) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeApplyAngularImpulse(
    JNIEnv*,
    jclass,
    jlong body_handle,
    jdouble impulse_x,
    jdouble impulse_y,
    jdouble impulse_z
) {
    return kinetic_assembly::apply_angular_impulse(
        static_cast<std::uint64_t>(body_handle),
        impulse_x,
        impulse_y,
        impulse_z
    ) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeApplyImpulseAtPoint(
    JNIEnv*,
    jclass,
    jlong body_handle,
    jdouble impulse_x,
    jdouble impulse_y,
    jdouble impulse_z,
    jdouble point_x,
    jdouble point_y,
    jdouble point_z
) {
    return kinetic_assembly::apply_impulse_at_point(
        static_cast<std::uint64_t>(body_handle),
        impulse_x,
        impulse_y,
        impulse_z,
        point_x,
        point_y,
        point_z
    ) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeApplyForce(
    JNIEnv*,
    jclass,
    jlong body_handle,
    jdouble force_x,
    jdouble force_y,
    jdouble force_z
) {
    return kinetic_assembly::apply_force(
        static_cast<std::uint64_t>(body_handle),
        force_x,
        force_y,
        force_z
    ) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeApplyTorque(
    JNIEnv*,
    jclass,
    jlong body_handle,
    jdouble torque_x,
    jdouble torque_y,
    jdouble torque_z
) {
    return kinetic_assembly::apply_torque(
        static_cast<std::uint64_t>(body_handle),
        torque_x,
        torque_y,
        torque_z
    ) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeApplyForceAtPoint(
    JNIEnv*,
    jclass,
    jlong body_handle,
    jdouble force_x,
    jdouble force_y,
    jdouble force_z,
    jdouble point_x,
    jdouble point_y,
    jdouble point_z
) {
    return kinetic_assembly::apply_force_at_point(
        static_cast<std::uint64_t>(body_handle),
        force_x,
        force_y,
        force_z,
        point_x,
        point_y,
        point_z
    ) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateFixedJoint(
    JNIEnv*,
    jclass,
    jlong world_handle,
    jlong first_body_handle,
    jlong second_body_handle,
    jboolean collide_connected,
    jfloat break_force,
    jfloat break_torque
) {
    return static_cast<jlong>(kinetic_assembly::create_fixed_joint(
        static_cast<kinetic_assembly::WorldHandle>(world_handle),
        static_cast<std::uint64_t>(first_body_handle),
        static_cast<std::uint64_t>(second_body_handle),
        collide_connected == JNI_TRUE,
        break_force,
        break_torque
    ));
}

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateFixedJointWithLocalFrames(
    JNIEnv* env,
    jclass,
    jlong world_handle,
    jlong first_body_handle,
    jlong second_body_handle,
    jdoubleArray local_frames,
    jboolean collide_connected,
    jfloat break_force,
    jfloat break_torque
) {
    if (local_frames == nullptr || env->GetArrayLength(local_frames) < 14) {
        return 0;
    }
    jdouble* values = env->GetDoubleArrayElements(local_frames, nullptr);
    if (values == nullptr) {
        return 0;
    }
    std::uint64_t joint = kinetic_assembly::create_fixed_joint_with_local_frames(
        static_cast<kinetic_assembly::WorldHandle>(world_handle),
        static_cast<std::uint64_t>(first_body_handle),
        static_cast<std::uint64_t>(second_body_handle),
        values,
        collide_connected == JNI_TRUE,
        break_force,
        break_torque
    );
    env->ReleaseDoubleArrayElements(local_frames, values, JNI_ABORT);
    return static_cast<jlong>(joint);
}

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateDistanceJoint(
    JNIEnv*,
    jclass,
    jlong world_handle,
    jlong first_body_handle,
    jlong second_body_handle,
    jfloat min_distance,
    jfloat max_distance,
    jfloat stiffness,
    jfloat damping,
    jboolean collide_connected,
    jfloat break_force,
    jfloat break_torque
) {
    return static_cast<jlong>(kinetic_assembly::create_distance_joint(
        static_cast<kinetic_assembly::WorldHandle>(world_handle),
        static_cast<std::uint64_t>(first_body_handle),
        static_cast<std::uint64_t>(second_body_handle),
        min_distance,
        max_distance,
        stiffness,
        damping,
        collide_connected == JNI_TRUE,
        break_force,
        break_torque
    ));
}

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateDistanceJointWithLocalAnchors(
    JNIEnv* env,
    jclass,
    jlong world_handle,
    jlong first_body_handle,
    jlong second_body_handle,
    jdoubleArray local_anchors,
    jfloat min_distance,
    jfloat max_distance,
    jfloat stiffness,
    jfloat damping,
    jboolean collide_connected,
    jfloat break_force,
    jfloat break_torque
) {
    if (local_anchors == nullptr || env->GetArrayLength(local_anchors) < 6) {
        return 0;
    }
    jdouble* values = env->GetDoubleArrayElements(local_anchors, nullptr);
    if (values == nullptr) {
        return 0;
    }
    std::uint64_t joint = kinetic_assembly::create_distance_joint_with_local_anchors(
        static_cast<kinetic_assembly::WorldHandle>(world_handle),
        static_cast<std::uint64_t>(first_body_handle),
        static_cast<std::uint64_t>(second_body_handle),
        values,
        min_distance,
        max_distance,
        stiffness,
        damping,
        collide_connected == JNI_TRUE,
        break_force,
        break_torque
    );
    env->ReleaseDoubleArrayElements(local_anchors, values, JNI_ABORT);
    return static_cast<jlong>(joint);
}

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateRevoluteJointWithLocalFrames(
    JNIEnv* env,
    jclass,
    jlong world_handle,
    jlong first_body_handle,
    jlong second_body_handle,
    jdoubleArray local_frames,
    jboolean collide_connected,
    jfloat break_force,
    jfloat break_torque
) {
    if (local_frames == nullptr || env->GetArrayLength(local_frames) < 14) {
        return 0;
    }
    jdouble* values = env->GetDoubleArrayElements(local_frames, nullptr);
    if (values == nullptr) {
        return 0;
    }
    std::uint64_t joint = kinetic_assembly::create_revolute_joint_with_local_frames(
        static_cast<kinetic_assembly::WorldHandle>(world_handle),
        static_cast<std::uint64_t>(first_body_handle),
        static_cast<std::uint64_t>(second_body_handle),
        values,
        collide_connected == JNI_TRUE,
        break_force,
        break_torque
    );
    env->ReleaseDoubleArrayElements(local_frames, values, JNI_ABORT);
    return static_cast<jlong>(joint);
}

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreatePrismaticJointWithLocalFrames(
    JNIEnv* env,
    jclass,
    jlong world_handle,
    jlong first_body_handle,
    jlong second_body_handle,
    jdoubleArray local_frames,
    jboolean collide_connected,
    jfloat break_force,
    jfloat break_torque
) {
    if (local_frames == nullptr || env->GetArrayLength(local_frames) < 14) {
        return 0;
    }
    jdouble* values = env->GetDoubleArrayElements(local_frames, nullptr);
    if (values == nullptr) {
        return 0;
    }
    std::uint64_t joint = kinetic_assembly::create_prismatic_joint_with_local_frames(
        static_cast<kinetic_assembly::WorldHandle>(world_handle),
        static_cast<std::uint64_t>(first_body_handle),
        static_cast<std::uint64_t>(second_body_handle),
        values,
        collide_connected == JNI_TRUE,
        break_force,
        break_torque
    );
    env->ReleaseDoubleArrayElements(local_frames, values, JNI_ABORT);
    return static_cast<jlong>(joint);
}

JNIEXPORT void JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeDestroyJoint(
    JNIEnv*,
    jclass,
    jlong joint_handle
) {
    kinetic_assembly::destroy_joint(static_cast<std::uint64_t>(joint_handle));
}

JNIEXPORT void JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeDestroyBody(
    JNIEnv*,
    jclass,
    jlong body_handle
) {
    kinetic_assembly::destroy_body(static_cast<std::uint64_t>(body_handle));
}

JNIEXPORT void JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeDestroyShape(
    JNIEnv*,
    jclass,
    jlong shape_handle
) {
    kinetic_assembly::destroy_shape(static_cast<std::uint64_t>(shape_handle));
}
