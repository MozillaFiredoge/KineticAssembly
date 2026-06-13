package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.Objects;

public record AssemblyBreakResult(
        AssemblyPickResult pick,
        boolean removedAssembly,
        int remainingBlocks,
        int dirtyBlocks,
        int removedVisuals,
        int connectedComponents,
        int createdAssemblies
) {
    public AssemblyBreakResult {
        Objects.requireNonNull(pick, "pick");
        if (remainingBlocks < 0) {
            throw new IllegalArgumentException("remainingBlocks must not be negative");
        }
        if (dirtyBlocks < 0) {
            throw new IllegalArgumentException("dirtyBlocks must not be negative");
        }
        if (removedVisuals < 0) {
            throw new IllegalArgumentException("removedVisuals must not be negative");
        }
        if (connectedComponents < 0) {
            throw new IllegalArgumentException("connectedComponents must not be negative");
        }
        if (createdAssemblies < 0) {
            throw new IllegalArgumentException("createdAssemblies must not be negative");
        }
    }
}
