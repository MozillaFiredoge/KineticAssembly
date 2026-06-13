package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.List;
import java.util.Objects;

import com.firedoge.kineticassembly.mechanics.MechanicsBodySnapshot;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public record AssemblySnapshot(
        AssemblyId id,
        ResourceKey<Level> levelKey,
        AssemblyLifecycleState state,
        AssemblyPlot plot,
        MechanicsBodySnapshot body,
        AssemblyBounds bounds,
        List<AssemblyBlock> blocks,
        int visualCount,
        int dirtyBlockCount
) {
    public AssemblySnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(levelKey, "levelKey");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(plot, "plot");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(bounds, "bounds");
        blocks = List.copyOf(blocks);
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("blocks must not be empty");
        }
        if (visualCount < 0) {
            throw new IllegalArgumentException("visualCount must not be negative");
        }
        if (dirtyBlockCount < 0) {
            throw new IllegalArgumentException("dirtyBlockCount must not be negative");
        }
    }

    public int blockCount() {
        return blocks.size();
    }

    public boolean assembly() {
        return blockCount() > 1;
    }

    public boolean dirty() {
        return dirtyBlockCount > 0;
    }

    public AssemblyBlock firstBlock() {
        return blocks.get(0);
    }

    public BlockState firstBlockState() {
        return firstBlock().blockState();
    }
}
