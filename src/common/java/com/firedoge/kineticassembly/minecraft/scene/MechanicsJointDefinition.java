package com.firedoge.kineticassembly.minecraft.scene;

import java.util.Objects;

import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.mechanics.MechanicsJointType;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

record MechanicsJointDefinition(
        boolean collideConnected,
        PhysicsPose firstLocalFrame,
        PhysicsPose secondLocalFrame,
        PhysicsVector firstLocalAnchor,
        PhysicsVector secondLocalAnchor,
        float minDistance,
        float maxDistance,
        float stiffness,
        float damping
) {
    MechanicsJointDefinition {
        Objects.requireNonNull(firstLocalFrame, "firstLocalFrame");
        Objects.requireNonNull(secondLocalFrame, "secondLocalFrame");
        Objects.requireNonNull(firstLocalAnchor, "firstLocalAnchor");
        Objects.requireNonNull(secondLocalAnchor, "secondLocalAnchor");
    }

    static MechanicsJointDefinition frames(PhysicsPose firstLocalFrame, PhysicsPose secondLocalFrame, boolean collideConnected) {
        return new MechanicsJointDefinition(
                collideConnected,
                firstLocalFrame,
                secondLocalFrame,
                PhysicsVector.ZERO,
                PhysicsVector.ZERO,
                0.0F,
                0.0F,
                0.0F,
                0.0F
        );
    }

    static MechanicsJointDefinition distance(
            PhysicsVector firstLocalAnchor,
            PhysicsVector secondLocalAnchor,
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping,
            boolean collideConnected
    ) {
        if (!Float.isFinite(minDistance) || minDistance < 0.0F) {
            throw new IllegalArgumentException("minDistance must be a finite non-negative float");
        }
        if (!Float.isFinite(maxDistance) || maxDistance <= 0.0F) {
            throw new IllegalArgumentException("maxDistance must be a finite positive float");
        }
        if (minDistance > maxDistance) {
            throw new IllegalArgumentException("minDistance must be <= maxDistance");
        }
        if (!Float.isFinite(stiffness) || stiffness < 0.0F) {
            throw new IllegalArgumentException("stiffness must be a finite non-negative float");
        }
        if (!Float.isFinite(damping) || damping < 0.0F) {
            throw new IllegalArgumentException("damping must be a finite non-negative float");
        }
        return new MechanicsJointDefinition(
                collideConnected,
                PhysicsPose.IDENTITY,
                PhysicsPose.IDENTITY,
                firstLocalAnchor,
                secondLocalAnchor,
                minDistance,
                maxDistance,
                stiffness,
                damping
        );
    }
}

record PersistedMechanicsJoint(
        PhysicsJointId id,
        ResourceKey<Level> levelKey,
        MechanicsJointType type,
        PhysicsObjectId firstBodyId,
        PhysicsObjectId secondBodyId,
        MechanicsJointDefinition definition
) {
    PersistedMechanicsJoint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(levelKey, "levelKey");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(firstBodyId, "firstBodyId");
        Objects.requireNonNull(secondBodyId, "secondBodyId");
        Objects.requireNonNull(definition, "definition");
        if (firstBodyId.equals(secondBodyId)) {
            throw new IllegalArgumentException("Persisted mechanics joints require two different bodies");
        }
    }
}

enum MechanicsJointRestoreResult {
    RESTORED,
    ALREADY_ACTIVE,
    DEFERRED,
    FAILED
}
