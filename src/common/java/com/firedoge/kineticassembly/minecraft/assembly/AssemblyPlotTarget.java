package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public record AssemblyPlotTarget(
        AssemblyId id,
        BlockPos localPos,
        BlockState blockState
) {
    public AssemblyPlotTarget {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(localPos, "localPos");
        Objects.requireNonNull(blockState, "blockState");
    }
}
