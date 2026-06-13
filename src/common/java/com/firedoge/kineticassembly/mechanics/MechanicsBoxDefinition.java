package com.firedoge.kineticassembly.mechanics;

import java.util.Objects;

import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsVector;

public record MechanicsBoxDefinition(
        PhysicsPose pose,
        PhysicsVector halfExtents,
        float mass,
        MechanicsBodyRole role
) {
    public MechanicsBoxDefinition {
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(halfExtents, "halfExtents");
        if (halfExtents.x() <= 0.0D || halfExtents.y() <= 0.0D || halfExtents.z() <= 0.0D) {
            throw new IllegalArgumentException("Box half extents must be positive");
        }
        if (mass <= 0.0F) {
            throw new IllegalArgumentException("Box mass must be positive");
        }
        if (role == null) {
            role = MechanicsBodyRole.GAMEPLAY;
        }
    }

    public static MechanicsBoxDefinition gameplayDynamicBox(PhysicsPose pose, PhysicsVector halfExtents, float mass) {
        return new MechanicsBoxDefinition(pose, halfExtents, mass, MechanicsBodyRole.GAMEPLAY);
    }
}
