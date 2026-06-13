package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.Objects;

import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsQuaternion;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.mechanics.MechanicsBodySnapshot;

import net.minecraft.world.phys.AABB;

public final class AssemblyTransform {
    private final PhysicsPose pose;

    private AssemblyTransform(PhysicsPose pose) {
        this.pose = Objects.requireNonNull(pose, "pose");
    }

    public static AssemblyTransform from(MechanicsBodySnapshot body) {
        Objects.requireNonNull(body, "body");
        return new AssemblyTransform(body.pose());
    }

    public static AssemblyTransform from(PhysicsPose pose) {
        return new AssemblyTransform(pose);
    }

    public PhysicsPose pose() {
        return pose;
    }

    public PhysicsVector worldToLocal(PhysicsVector worldPosition) {
        Objects.requireNonNull(worldPosition, "worldPosition");
        PhysicsVector position = pose.position();
        return inverseRotate(pose.rotation(), new PhysicsVector(
                worldPosition.x() - position.x(),
                worldPosition.y() - position.y(),
                worldPosition.z() - position.z()
        ));
    }

    public PhysicsVector worldDirectionToLocal(PhysicsVector worldDirection) {
        Objects.requireNonNull(worldDirection, "worldDirection");
        return inverseRotate(pose.rotation(), worldDirection);
    }

    public PhysicsVector localDirectionToWorld(PhysicsVector localDirection) {
        Objects.requireNonNull(localDirection, "localDirection");
        return rotate(pose.rotation(), localDirection);
    }

    public PhysicsVector localToWorld(PhysicsVector localPosition) {
        Objects.requireNonNull(localPosition, "localPosition");
        PhysicsVector rotated = rotate(pose.rotation(), localPosition);
        PhysicsVector position = pose.position();
        return new PhysicsVector(
                position.x() + rotated.x(),
                position.y() + rotated.y(),
                position.z() + rotated.z()
        );
    }

    public AABB localAabbToWorldBounds(AABB localBox) {
        Objects.requireNonNull(localBox, "localBox");
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int xIndex = 0; xIndex < 2; xIndex++) {
            double x = xIndex == 0 ? localBox.minX : localBox.maxX;
            for (int yIndex = 0; yIndex < 2; yIndex++) {
                double y = yIndex == 0 ? localBox.minY : localBox.maxY;
                for (int zIndex = 0; zIndex < 2; zIndex++) {
                    double z = zIndex == 0 ? localBox.minZ : localBox.maxZ;
                    PhysicsVector world = localToWorld(new PhysicsVector(x, y, z));
                    minX = Math.min(minX, world.x());
                    minY = Math.min(minY, world.y());
                    minZ = Math.min(minZ, world.z());
                    maxX = Math.max(maxX, world.x());
                    maxY = Math.max(maxY, world.y());
                    maxZ = Math.max(maxZ, world.z());
                }
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public AABB worldAabbToLocalBounds(AABB worldBox) {
        Objects.requireNonNull(worldBox, "worldBox");
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int xIndex = 0; xIndex < 2; xIndex++) {
            double x = xIndex == 0 ? worldBox.minX : worldBox.maxX;
            for (int yIndex = 0; yIndex < 2; yIndex++) {
                double y = yIndex == 0 ? worldBox.minY : worldBox.maxY;
                for (int zIndex = 0; zIndex < 2; zIndex++) {
                    double z = zIndex == 0 ? worldBox.minZ : worldBox.maxZ;
                    PhysicsVector local = worldToLocal(new PhysicsVector(x, y, z));
                    minX = Math.min(minX, local.x());
                    minY = Math.min(minY, local.y());
                    minZ = Math.min(minZ, local.z());
                    maxX = Math.max(maxX, local.x());
                    maxY = Math.max(maxY, local.y());
                    maxZ = Math.max(maxZ, local.z());
                }
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static PhysicsVector inverseRotate(PhysicsQuaternion rotation, PhysicsVector vector) {
        PhysicsQuaternion normalized = normalize(rotation);
        return rotate(new PhysicsQuaternion(-normalized.x(), -normalized.y(), -normalized.z(), normalized.w()), vector);
    }

    private static PhysicsVector rotate(PhysicsQuaternion rotation, PhysicsVector vector) {
        PhysicsQuaternion q = normalize(rotation);
        double ux = q.x();
        double uy = q.y();
        double uz = q.z();
        double s = q.w();
        double vx = vector.x();
        double vy = vector.y();
        double vz = vector.z();

        double dotUv = ux * vx + uy * vy + uz * vz;
        double dotUu = ux * ux + uy * uy + uz * uz;
        double crossX = uy * vz - uz * vy;
        double crossY = uz * vx - ux * vz;
        double crossZ = ux * vy - uy * vx;
        double scale = s * s - dotUu;

        return new PhysicsVector(
                2.0D * dotUv * ux + scale * vx + 2.0D * s * crossX,
                2.0D * dotUv * uy + scale * vy + 2.0D * s * crossY,
                2.0D * dotUv * uz + scale * vz + 2.0D * s * crossZ
        );
    }

    private static PhysicsQuaternion normalize(PhysicsQuaternion rotation) {
        Objects.requireNonNull(rotation, "rotation");
        double length = Math.sqrt(
                rotation.x() * rotation.x()
                        + rotation.y() * rotation.y()
                        + rotation.z() * rotation.z()
                        + rotation.w() * rotation.w()
        );
        if (length <= 1.0E-12D || Double.isNaN(length)) {
            return PhysicsQuaternion.IDENTITY;
        }
        return new PhysicsQuaternion(
                rotation.x() / length,
                rotation.y() / length,
                rotation.z() / length,
                rotation.w() / length
        );
    }
}
