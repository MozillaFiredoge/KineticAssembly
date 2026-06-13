package com.firedoge.kineticassembly.minecraft.scene;

import java.util.Objects;

import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsVector;

public record PhysicsObjectSnapshot(
        PhysicsObjectId id,
        PhysicsObjectType type,
        PhysicsPose pose,
        PhysicsVector linearVelocity,
        PhysicsVector angularVelocity,
        boolean closed
) {
    public PhysicsObjectSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(linearVelocity, "linearVelocity");
        Objects.requireNonNull(angularVelocity, "angularVelocity");
    }
}
