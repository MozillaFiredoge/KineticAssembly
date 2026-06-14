package com.firedoge.kineticassembly.mechanics;

import java.util.Objects;

import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsVector;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record MechanicsBodySnapshot(
        MechanicsBodyId id,
        ResourceKey<Level> levelKey,
        MechanicsBodyType type,
        MechanicsBodyRole role,
        MechanicsOwner owner,
        PhysicsPose pose,
        PhysicsVector linearVelocity,
        PhysicsVector angularVelocity,
        PhysicsVector halfExtents,
        float mass,
        boolean closed
) {
    public MechanicsBodySnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(levelKey, "levelKey");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(linearVelocity, "linearVelocity");
        Objects.requireNonNull(angularVelocity, "angularVelocity");
        Objects.requireNonNull(halfExtents, "halfExtents");
    }
}
