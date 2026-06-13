#pragma once

#include <jni.h>

extern "C" {
JNIEXPORT jboolean JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeIsPhysXLinked(
    JNIEnv* env,
    jclass type
);

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateWorld(
    JNIEnv* env,
    jclass type,
    jdouble gravity_x,
    jdouble gravity_y,
    jdouble gravity_z,
    jfloat fixed_time_step,
    jint max_sub_steps,
    jboolean enable_gpu_dynamics
);

JNIEXPORT void JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeDestroyWorld(
    JNIEnv* env,
    jclass type,
    jlong world_handle
);

JNIEXPORT void JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeStepWorld(
    JNIEnv* env,
    jclass type,
    jlong world_handle,
    jfloat delta_seconds
);

JNIEXPORT jboolean JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeIsWorldGpuDynamicsEnabled(
    JNIEnv* env,
    jclass type,
    jlong world_handle
);

JNIEXPORT jstring JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeGetWorldGpuDynamicsStatus(
    JNIEnv* env,
    jclass type,
    jlong world_handle
);

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateBoxShape(
    JNIEnv* env,
    jclass type,
    jlong world_handle,
    jfloat half_extent_x,
    jfloat half_extent_y,
    jfloat half_extent_z
);

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateStaticPlane(
    JNIEnv* env,
    jclass type,
    jlong world_handle,
    jdouble normal_x,
    jdouble normal_y,
    jdouble normal_z,
    jdouble distance
);

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateStaticBody(
    JNIEnv* env,
    jclass type,
    jlong world_handle,
    jlong shape_handle,
    jdouble position_x,
    jdouble position_y,
    jdouble position_z,
    jdouble rotation_x,
    jdouble rotation_y,
    jdouble rotation_z,
    jdouble rotation_w
);

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateDynamicBody(
    JNIEnv* env,
    jclass type,
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
);

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateDynamicCompoundBoxBody(
    JNIEnv* env,
    jclass type,
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
);

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateDynamicCompoundBoxBodyWithMassProperties(
    JNIEnv* env,
    jclass type,
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
);

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateDeformableVolumeBox(
    JNIEnv* env,
    jclass type,
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
);

JNIEXPORT jboolean JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeGetDeformableVolumeInfo(
    JNIEnv* env,
    jclass type,
    jlong deformable_volume_handle,
    jintArray output
);

JNIEXPORT jint JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeGetDeformableVolumeVertices(
    JNIEnv* env,
    jclass type,
    jlong deformable_volume_handle,
    jdoubleArray output,
    jint max_vertices
);

JNIEXPORT void JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeDestroyDeformableVolume(
    JNIEnv* env,
    jclass type,
    jlong deformable_volume_handle
);

JNIEXPORT jboolean JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeGetBodyPose(
    JNIEnv* env,
    jclass type,
    jlong body_handle,
    jdoubleArray output
);

JNIEXPORT void JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeSetBodyPose(
    JNIEnv* env,
    jclass type,
    jlong body_handle,
    jdouble position_x,
    jdouble position_y,
    jdouble position_z,
    jdouble rotation_x,
    jdouble rotation_y,
    jdouble rotation_z,
    jdouble rotation_w
);

JNIEXPORT void JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeSetLinearVelocity(
    JNIEnv* env,
    jclass type,
    jlong body_handle,
    jdouble velocity_x,
    jdouble velocity_y,
    jdouble velocity_z
);

JNIEXPORT jboolean JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeGetLinearVelocity(
    JNIEnv* env,
    jclass type,
    jlong body_handle,
    jdoubleArray output
);

JNIEXPORT void JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeSetAngularVelocity(
    JNIEnv* env,
    jclass type,
    jlong body_handle,
    jdouble velocity_x,
    jdouble velocity_y,
    jdouble velocity_z
);

JNIEXPORT jboolean JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeGetAngularVelocity(
    JNIEnv* env,
    jclass type,
    jlong body_handle,
    jdoubleArray output
);

JNIEXPORT jboolean JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeReadBodyStates(
    JNIEnv* env,
    jclass type,
    jlong world_handle,
    jlongArray body_handles,
    jdoubleArray output
);

JNIEXPORT jboolean JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeApplyLinearImpulse(
    JNIEnv* env,
    jclass type,
    jlong body_handle,
    jdouble impulse_x,
    jdouble impulse_y,
    jdouble impulse_z
);

JNIEXPORT jboolean JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeApplyAngularImpulse(
    JNIEnv* env,
    jclass type,
    jlong body_handle,
    jdouble impulse_x,
    jdouble impulse_y,
    jdouble impulse_z
);

JNIEXPORT jboolean JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeApplyImpulseAtPoint(
    JNIEnv* env,
    jclass type,
    jlong body_handle,
    jdouble impulse_x,
    jdouble impulse_y,
    jdouble impulse_z,
    jdouble point_x,
    jdouble point_y,
    jdouble point_z
);

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateFixedJoint(
    JNIEnv* env,
    jclass type,
    jlong world_handle,
    jlong first_body_handle,
    jlong second_body_handle,
    jboolean collide_connected,
    jfloat break_force,
    jfloat break_torque
);

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateFixedJointWithLocalFrames(
    JNIEnv* env,
    jclass type,
    jlong world_handle,
    jlong first_body_handle,
    jlong second_body_handle,
    jdoubleArray local_frames,
    jboolean collide_connected,
    jfloat break_force,
    jfloat break_torque
);

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateDistanceJoint(
    JNIEnv* env,
    jclass type,
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
);

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateDistanceJointWithLocalAnchors(
    JNIEnv* env,
    jclass type,
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
);

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreateRevoluteJointWithLocalFrames(
    JNIEnv* env,
    jclass type,
    jlong world_handle,
    jlong first_body_handle,
    jlong second_body_handle,
    jdoubleArray local_frames,
    jboolean collide_connected,
    jfloat break_force,
    jfloat break_torque
);

JNIEXPORT jlong JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeCreatePrismaticJointWithLocalFrames(
    JNIEnv* env,
    jclass type,
    jlong world_handle,
    jlong first_body_handle,
    jlong second_body_handle,
    jdoubleArray local_frames,
    jboolean collide_connected,
    jfloat break_force,
    jfloat break_torque
);

JNIEXPORT void JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeDestroyJoint(
    JNIEnv* env,
    jclass type,
    jlong joint_handle
);

JNIEXPORT void JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeDestroyBody(
    JNIEnv* env,
    jclass type,
    jlong body_handle
);

JNIEXPORT void JNICALL Java_com_firedoge_kineticassembly_backend_physx_PhysXNative_nativeDestroyShape(
    JNIEnv* env,
    jclass type,
    jlong shape_handle
);
}
