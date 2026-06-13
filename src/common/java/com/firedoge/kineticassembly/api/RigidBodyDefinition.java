package com.firedoge.kineticassembly.api;

import java.util.Objects;

public record RigidBodyDefinition(
        PhysicsBodyType type,
        PhysicsPose initialPose,
        PhysicsShape shape,
        PhysicsMaterial material,
        float mass
) {
    public RigidBodyDefinition {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(initialPose, "initialPose");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(material, "material");
        if (type == PhysicsBodyType.DYNAMIC && mass <= 0.0F) {
            throw new IllegalArgumentException("Dynamic bodies must have positive mass");
        }
    }

    public static RigidBodyDefinition dynamic(PhysicsPose pose, PhysicsShape shape, float mass) {
        return new RigidBodyDefinition(PhysicsBodyType.DYNAMIC, pose, shape, PhysicsMaterial.DEFAULT, mass);
    }

    public static RigidBodyDefinition staticBody(PhysicsPose pose, PhysicsShape shape) {
        return new RigidBodyDefinition(PhysicsBodyType.STATIC, pose, shape, PhysicsMaterial.DEFAULT, 0.0F);
    }
}
