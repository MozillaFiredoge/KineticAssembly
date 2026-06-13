package com.firedoge.kineticassembly.backend.physx;

import com.firedoge.kineticassembly.nativebridge.NativeLibraryLoader;

public final class PhysXNative {
    public static final String LIBRARY_NAME = "kinetic_assembly_native";
    private static volatile boolean angularVelocityNativeAvailable = true;
    private static volatile boolean linearImpulseNativeAvailable = true;
    private static volatile boolean angularImpulseNativeAvailable = true;
    private static volatile boolean pointImpulseNativeAvailable = true;
    private static volatile boolean bodyStateBatchNativeAvailable = true;
    private static volatile boolean fixedJointNativeAvailable = true;
    private static volatile boolean distanceJointNativeAvailable = true;
    private static volatile boolean revoluteJointNativeAvailable = true;
    private static volatile boolean prismaticJointNativeAvailable = true;
    private static volatile boolean destroyJointNativeAvailable = true;

    private PhysXNative() {
    }

    public static synchronized void load() {
        NativeLibraryLoader.load(LIBRARY_NAME);
    }

    public static boolean isLoaded() {
        return NativeLibraryLoader.isLoaded(LIBRARY_NAME);
    }

    public static boolean isPhysXLinked() {
        return isLoaded() && nativeIsPhysXLinked();
    }

    static native boolean nativeIsPhysXLinked();

    static native long nativeCreateWorld(double gravityX, double gravityY, double gravityZ, float fixedTimeStep, int maxSubSteps, boolean enableGpuDynamics);

    static native void nativeDestroyWorld(long worldHandle);

    static native void nativeStepWorld(long worldHandle, float deltaSeconds);

    static native boolean nativeIsWorldGpuDynamicsEnabled(long worldHandle);

    static native String nativeGetWorldGpuDynamicsStatus(long worldHandle);

    static native long nativeCreateBoxShape(long worldHandle, float halfExtentX, float halfExtentY, float halfExtentZ);

    static native long nativeCreateStaticPlane(long worldHandle, double normalX, double normalY, double normalZ, double distance);

    static native long nativeCreateStaticBody(
            long worldHandle,
            long shapeHandle,
            double positionX,
            double positionY,
            double positionZ,
            double rotationX,
            double rotationY,
            double rotationZ,
            double rotationW
    );

    static native long nativeCreateDynamicBody(
            long worldHandle,
            long shapeHandle,
            double positionX,
            double positionY,
            double positionZ,
            double rotationX,
            double rotationY,
            double rotationZ,
            double rotationW,
            float mass
    );

    static native long nativeCreateDynamicCompoundBoxBody(
            long worldHandle,
            double[] boxes,
            int boxCount,
            double positionX,
            double positionY,
            double positionZ,
            double rotationX,
            double rotationY,
            double rotationZ,
            double rotationW,
            float mass
    );

    static native long nativeCreateDynamicCompoundBoxBodyWithMassProperties(
            long worldHandle,
            double[] boxes,
            int boxCount,
            double positionX,
            double positionY,
            double positionZ,
            double rotationX,
            double rotationY,
            double rotationZ,
            double rotationW,
            float mass,
            double centerOfMassX,
            double centerOfMassY,
            double centerOfMassZ,
            double inertiaX,
            double inertiaY,
            double inertiaZ
    );

    static native long nativeCreateDeformableVolumeBox(
            long worldHandle,
            double centerX,
            double centerY,
            double centerZ,
            float width,
            float height,
            float depth,
            float density,
            float youngs,
            float poissons,
            float dynamicFriction,
            float damping,
            float maxEdgeLength,
            int voxels
    );

    static native boolean nativeGetDeformableVolumeInfo(long deformableVolumeHandle, int[] output);

    static native int nativeGetDeformableVolumeVertices(long deformableVolumeHandle, double[] output, int maxVertices);

    static native void nativeDestroyDeformableVolume(long deformableVolumeHandle);

    static native boolean nativeGetBodyPose(long bodyHandle, double[] output);

    static native void nativeSetBodyPose(
            long bodyHandle,
            double positionX,
            double positionY,
            double positionZ,
            double rotationX,
            double rotationY,
            double rotationZ,
            double rotationW
    );

    static native void nativeSetLinearVelocity(long bodyHandle, double velocityX, double velocityY, double velocityZ);

    static native boolean nativeGetLinearVelocity(long bodyHandle, double[] output);

    static native void nativeSetAngularVelocity(long bodyHandle, double velocityX, double velocityY, double velocityZ);

    static native boolean nativeGetAngularVelocity(long bodyHandle, double[] output);

    static native boolean nativeReadBodyStates(long worldHandle, long[] bodyHandles, double[] output);

    static native boolean nativeApplyLinearImpulse(long bodyHandle, double impulseX, double impulseY, double impulseZ);

    static native boolean nativeApplyAngularImpulse(long bodyHandle, double impulseX, double impulseY, double impulseZ);

    static native boolean nativeApplyImpulseAtPoint(
            long bodyHandle,
            double impulseX,
            double impulseY,
            double impulseZ,
            double pointX,
            double pointY,
            double pointZ
    );

    static native long nativeCreateFixedJoint(
            long worldHandle,
            long firstBodyHandle,
            long secondBodyHandle,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    );

    static native long nativeCreateFixedJointWithLocalFrames(
            long worldHandle,
            long firstBodyHandle,
            long secondBodyHandle,
            double[] localFrames,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    );

    static native long nativeCreateDistanceJoint(
            long worldHandle,
            long firstBodyHandle,
            long secondBodyHandle,
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    );

    static native long nativeCreateDistanceJointWithLocalAnchors(
            long worldHandle,
            long firstBodyHandle,
            long secondBodyHandle,
            double[] localAnchors,
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    );

    static native long nativeCreateRevoluteJointWithLocalFrames(
            long worldHandle,
            long firstBodyHandle,
            long secondBodyHandle,
            double[] localFrames,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    );

    static native long nativeCreatePrismaticJointWithLocalFrames(
            long worldHandle,
            long firstBodyHandle,
            long secondBodyHandle,
            double[] localFrames,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    );

    static native void nativeDestroyJoint(long jointHandle);

    static void setAngularVelocity(long bodyHandle, double velocityX, double velocityY, double velocityZ) {
        if (!angularVelocityNativeAvailable) {
            return;
        }
        try {
            nativeSetAngularVelocity(bodyHandle, velocityX, velocityY, velocityZ);
        } catch (UnsatisfiedLinkError error) {
            angularVelocityNativeAvailable = false;
        }
    }

    static boolean getAngularVelocity(long bodyHandle, double[] output) {
        if (!angularVelocityNativeAvailable) {
            return false;
        }
        try {
            return nativeGetAngularVelocity(bodyHandle, output);
        } catch (UnsatisfiedLinkError error) {
            angularVelocityNativeAvailable = false;
            return false;
        }
    }

    static boolean applyLinearImpulse(long bodyHandle, double impulseX, double impulseY, double impulseZ) {
        if (!linearImpulseNativeAvailable) {
            return false;
        }
        try {
            return nativeApplyLinearImpulse(bodyHandle, impulseX, impulseY, impulseZ);
        } catch (UnsatisfiedLinkError error) {
            linearImpulseNativeAvailable = false;
            return false;
        }
    }

    static boolean applyAngularImpulse(long bodyHandle, double impulseX, double impulseY, double impulseZ) {
        if (!angularImpulseNativeAvailable) {
            return false;
        }
        try {
            return nativeApplyAngularImpulse(bodyHandle, impulseX, impulseY, impulseZ);
        } catch (UnsatisfiedLinkError error) {
            angularImpulseNativeAvailable = false;
            return false;
        }
    }

    static boolean applyImpulseAtPoint(
            long bodyHandle,
            double impulseX,
            double impulseY,
            double impulseZ,
            double pointX,
            double pointY,
            double pointZ
    ) {
        if (!pointImpulseNativeAvailable) {
            return false;
        }
        try {
            return nativeApplyImpulseAtPoint(bodyHandle, impulseX, impulseY, impulseZ, pointX, pointY, pointZ);
        } catch (UnsatisfiedLinkError error) {
            pointImpulseNativeAvailable = false;
            return false;
        }
    }

    static boolean readBodyStates(long worldHandle, long[] bodyHandles, double[] output) {
        if (!bodyStateBatchNativeAvailable) {
            return false;
        }
        try {
            return nativeReadBodyStates(worldHandle, bodyHandles, output);
        } catch (UnsatisfiedLinkError error) {
            bodyStateBatchNativeAvailable = false;
            return false;
        }
    }

    static long createFixedJoint(
            long worldHandle,
            long firstBodyHandle,
            long secondBodyHandle,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    ) {
        if (!fixedJointNativeAvailable) {
            return 0L;
        }
        try {
            return nativeCreateFixedJoint(worldHandle, firstBodyHandle, secondBodyHandle, collideConnected, breakForce, breakTorque);
        } catch (UnsatisfiedLinkError error) {
            fixedJointNativeAvailable = false;
            return 0L;
        }
    }

    static long createFixedJointWithLocalFrames(
            long worldHandle,
            long firstBodyHandle,
            long secondBodyHandle,
            double[] localFrames,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    ) {
        if (!fixedJointNativeAvailable) {
            return 0L;
        }
        try {
            return nativeCreateFixedJointWithLocalFrames(
                    worldHandle,
                    firstBodyHandle,
                    secondBodyHandle,
                    localFrames,
                    collideConnected,
                    breakForce,
                    breakTorque
            );
        } catch (UnsatisfiedLinkError error) {
            fixedJointNativeAvailable = false;
            return 0L;
        }
    }

    static long createDistanceJoint(
            long worldHandle,
            long firstBodyHandle,
            long secondBodyHandle,
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    ) {
        if (!distanceJointNativeAvailable) {
            return 0L;
        }
        try {
            return nativeCreateDistanceJoint(
                    worldHandle,
                    firstBodyHandle,
                    secondBodyHandle,
                    minDistance,
                    maxDistance,
                    stiffness,
                    damping,
                    collideConnected,
                    breakForce,
                    breakTorque
            );
        } catch (UnsatisfiedLinkError error) {
            distanceJointNativeAvailable = false;
            return 0L;
        }
    }

    static long createDistanceJointWithLocalAnchors(
            long worldHandle,
            long firstBodyHandle,
            long secondBodyHandle,
            double[] localAnchors,
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    ) {
        if (!distanceJointNativeAvailable) {
            return 0L;
        }
        try {
            return nativeCreateDistanceJointWithLocalAnchors(
                    worldHandle,
                    firstBodyHandle,
                    secondBodyHandle,
                    localAnchors,
                    minDistance,
                    maxDistance,
                    stiffness,
                    damping,
                    collideConnected,
                    breakForce,
                    breakTorque
            );
        } catch (UnsatisfiedLinkError error) {
            distanceJointNativeAvailable = false;
            return 0L;
        }
    }

    static long createRevoluteJointWithLocalFrames(
            long worldHandle,
            long firstBodyHandle,
            long secondBodyHandle,
            double[] localFrames,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    ) {
        if (!revoluteJointNativeAvailable) {
            return 0L;
        }
        try {
            return nativeCreateRevoluteJointWithLocalFrames(
                    worldHandle,
                    firstBodyHandle,
                    secondBodyHandle,
                    localFrames,
                    collideConnected,
                    breakForce,
                    breakTorque
            );
        } catch (UnsatisfiedLinkError error) {
            revoluteJointNativeAvailable = false;
            return 0L;
        }
    }

    static long createPrismaticJointWithLocalFrames(
            long worldHandle,
            long firstBodyHandle,
            long secondBodyHandle,
            double[] localFrames,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    ) {
        if (!prismaticJointNativeAvailable) {
            return 0L;
        }
        try {
            return nativeCreatePrismaticJointWithLocalFrames(
                    worldHandle,
                    firstBodyHandle,
                    secondBodyHandle,
                    localFrames,
                    collideConnected,
                    breakForce,
                    breakTorque
            );
        } catch (UnsatisfiedLinkError error) {
            prismaticJointNativeAvailable = false;
            return 0L;
        }
    }

    static void destroyJoint(long jointHandle) {
        if (!destroyJointNativeAvailable) {
            return;
        }
        try {
            nativeDestroyJoint(jointHandle);
        } catch (UnsatisfiedLinkError error) {
            destroyJointNativeAvailable = false;
        }
    }

    static boolean fixedJointNativeAvailable() {
        return fixedJointNativeAvailable;
    }

    static boolean distanceJointNativeAvailable() {
        return distanceJointNativeAvailable;
    }

    static boolean revoluteJointNativeAvailable() {
        return revoluteJointNativeAvailable;
    }

    static boolean prismaticJointNativeAvailable() {
        return prismaticJointNativeAvailable;
    }

    static native void nativeDestroyBody(long bodyHandle);

    static native void nativeDestroyShape(long shapeHandle);
}
