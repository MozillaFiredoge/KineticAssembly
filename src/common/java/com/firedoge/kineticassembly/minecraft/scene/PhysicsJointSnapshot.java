package com.firedoge.kineticassembly.minecraft.scene;

import java.util.Objects;

import com.firedoge.kineticassembly.api.PhysicsJointType;

public record PhysicsJointSnapshot(
        PhysicsJointId id,
        PhysicsJointType type,
        PhysicsObjectId firstBodyId,
        PhysicsObjectId secondBodyId,
        boolean closed
) {
    public PhysicsJointSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(firstBodyId, "firstBodyId");
        Objects.requireNonNull(secondBodyId, "secondBodyId");
    }
}
