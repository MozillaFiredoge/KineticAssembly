package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.Objects;

import net.minecraft.core.BlockPos;

public record AssemblyBounds(BlockPos sourceOrigin, BlockPos minSourcePos, BlockPos maxSourcePos) {
    public AssemblyBounds {
        Objects.requireNonNull(sourceOrigin, "sourceOrigin");
        Objects.requireNonNull(minSourcePos, "minSourcePos");
        Objects.requireNonNull(maxSourcePos, "maxSourcePos");
    }

    public static AssemblyBounds from(BlockPos first, BlockPos second) {
        BlockPos min = new BlockPos(
                Math.min(first.getX(), second.getX()),
                Math.min(first.getY(), second.getY()),
                Math.min(first.getZ(), second.getZ())
        );
        BlockPos max = new BlockPos(
                Math.max(first.getX(), second.getX()),
                Math.max(first.getY(), second.getY()),
                Math.max(first.getZ(), second.getZ())
        );
        return new AssemblyBounds(min, min, max);
    }

    public int width() {
        return maxSourcePos.getX() - minSourcePos.getX() + 1;
    }

    public int height() {
        return maxSourcePos.getY() - minSourcePos.getY() + 1;
    }

    public int depth() {
        return maxSourcePos.getZ() - minSourcePos.getZ() + 1;
    }

    public int volume() {
        return width() * height() * depth();
    }

    public BlockPos toLocal(BlockPos sourcePos) {
        return new BlockPos(
                sourcePos.getX() - sourceOrigin.getX(),
                sourcePos.getY() - sourceOrigin.getY(),
                sourcePos.getZ() - sourceOrigin.getZ()
        );
    }

    public BlockPos toLocalIn(AssemblyBounds parent) {
        Objects.requireNonNull(parent, "parent");
        return new BlockPos(
                sourceOrigin.getX() - parent.sourceOrigin().getX(),
                sourceOrigin.getY() - parent.sourceOrigin().getY(),
                sourceOrigin.getZ() - parent.sourceOrigin().getZ()
        );
    }
}
