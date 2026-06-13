package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.Objects;

import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsQuaternion;
import com.firedoge.kineticassembly.api.PhysicsVector;

public record AssemblyPoseFrame(
        AssemblyId id,
        long epoch,
        PhysicsPose previousPose,
        PhysicsPose currentPose
) {
    public AssemblyPoseFrame {
        Objects.requireNonNull(id, "id");
        if (epoch < 0L) {
            throw new IllegalArgumentException("epoch must be non-negative");
        }
        Objects.requireNonNull(previousPose, "previousPose");
        Objects.requireNonNull(currentPose, "currentPose");
    }

    public static AssemblyPoseFrame initial(AssemblyId id, PhysicsPose pose) {
        Objects.requireNonNull(pose, "pose");
        return new AssemblyPoseFrame(id, 0L, pose, pose);
    }

    public AssemblyPoseFrame advance(PhysicsPose pose) {
        Objects.requireNonNull(pose, "pose");
        if (currentPose.equals(pose)) {
            return this;
        }
        return new AssemblyPoseFrame(id, epoch + 1L, currentPose, pose);
    }

    public PhysicsPose pose() {
        return currentPose;
    }

    public PhysicsPose interpolatedPose(double partialTick) {
        double alpha = clamp(partialTick, 0.0D, 1.0D);
        PhysicsVector previousPosition = previousPose.position();
        PhysicsVector currentPosition = currentPose.position();
        PhysicsVector position = new PhysicsVector(
                previousPosition.x() + (currentPosition.x() - previousPosition.x()) * alpha,
                previousPosition.y() + (currentPosition.y() - previousPosition.y()) * alpha,
                previousPosition.z() + (currentPosition.z() - previousPosition.z()) * alpha
        );
        return new PhysicsPose(position, slerp(previousPose.rotation(), currentPose.rotation(), alpha));
    }

    public AssemblyTransform previousTransform() {
        return AssemblyTransform.from(previousPose);
    }

    public AssemblyTransform currentTransform() {
        return AssemblyTransform.from(currentPose);
    }

    public AssemblyTransform interpolatedTransform(double partialTick) {
        return AssemblyTransform.from(interpolatedPose(partialTick));
    }

    private static PhysicsQuaternion slerp(PhysicsQuaternion from, PhysicsQuaternion to, double alpha) {
        PhysicsQuaternion start = normalize(from);
        PhysicsQuaternion end = normalize(to);
        double endX = end.x();
        double endY = end.y();
        double endZ = end.z();
        double endW = end.w();
        double dot = start.x() * endX + start.y() * endY + start.z() * endZ + start.w() * endW;

        if (dot < 0.0D) {
            dot = -dot;
            endX = -endX;
            endY = -endY;
            endZ = -endZ;
            endW = -endW;
        }

        if (dot > 0.9995D) {
            return normalize(new PhysicsQuaternion(
                    start.x() + (endX - start.x()) * alpha,
                    start.y() + (endY - start.y()) * alpha,
                    start.z() + (endZ - start.z()) * alpha,
                    start.w() + (endW - start.w()) * alpha
            ));
        }

        double theta0 = Math.acos(clamp(dot, -1.0D, 1.0D));
        double sinTheta0 = Math.sin(theta0);
        if (Math.abs(sinTheta0) <= 1.0E-12D) {
            return start;
        }

        double theta = theta0 * alpha;
        double sinTheta = Math.sin(theta);
        double scaleStart = Math.cos(theta) - dot * sinTheta / sinTheta0;
        double scaleEnd = sinTheta / sinTheta0;
        return normalize(new PhysicsQuaternion(
                scaleStart * start.x() + scaleEnd * endX,
                scaleStart * start.y() + scaleEnd * endY,
                scaleStart * start.z() + scaleEnd * endZ,
                scaleStart * start.w() + scaleEnd * endW
        ));
    }

    private static PhysicsQuaternion normalize(PhysicsQuaternion quaternion) {
        Objects.requireNonNull(quaternion, "quaternion");
        double length = Math.sqrt(
                quaternion.x() * quaternion.x()
                        + quaternion.y() * quaternion.y()
                        + quaternion.z() * quaternion.z()
                        + quaternion.w() * quaternion.w()
        );
        if (length <= 1.0E-12D || !Double.isFinite(length)) {
            return PhysicsQuaternion.IDENTITY;
        }
        return new PhysicsQuaternion(
                quaternion.x() / length,
                quaternion.y() / length,
                quaternion.z() / length,
                quaternion.w() / length
        );
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
