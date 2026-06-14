package com.firedoge.kineticassembly.mechanics;

import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record MechanicsAssemblySnapshot(
        MechanicsAssemblyId id,
        ResourceKey<Level> levelKey,
        MechanicsOwner owner,
        MechanicsBodySnapshot body,
        BlockPos minSourcePos,
        BlockPos maxSourcePos,
        int blockCount,
        int visualCount,
        int dirtyBlockCount,
        boolean debugProxy
) {
    public MechanicsAssemblySnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(levelKey, "levelKey");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(body, "body");
        minSourcePos = Objects.requireNonNull(minSourcePos, "minSourcePos").immutable();
        maxSourcePos = Objects.requireNonNull(maxSourcePos, "maxSourcePos").immutable();
        if (minSourcePos.getX() > maxSourcePos.getX()
                || minSourcePos.getY() > maxSourcePos.getY()
                || minSourcePos.getZ() > maxSourcePos.getZ()) {
            throw new IllegalArgumentException("minSourcePos must not be greater than maxSourcePos");
        }
        if (blockCount <= 0) {
            throw new IllegalArgumentException("blockCount must be positive");
        }
        if (visualCount < 0) {
            throw new IllegalArgumentException("visualCount must not be negative");
        }
        if (dirtyBlockCount < 0) {
            throw new IllegalArgumentException("dirtyBlockCount must not be negative");
        }
    }

    public boolean dirty() {
        return dirtyBlockCount > 0;
    }

    public boolean singleBlock() {
        return blockCount == 1;
    }
}
