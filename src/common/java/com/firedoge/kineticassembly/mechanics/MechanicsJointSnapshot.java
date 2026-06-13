package com.firedoge.kineticassembly.mechanics;

import java.util.Objects;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record MechanicsJointSnapshot(
        MechanicsJointId id,
        ResourceKey<Level> levelKey,
        MechanicsJointType type,
        MechanicsBodyId firstBodyId,
        MechanicsBodyId secondBodyId,
        boolean closed
) {
    public MechanicsJointSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(levelKey, "levelKey");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(firstBodyId, "firstBodyId");
        Objects.requireNonNull(secondBodyId, "secondBodyId");
    }
}
