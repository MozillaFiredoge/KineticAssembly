package com.firedoge.kineticassembly.api;

import java.util.Objects;

public record DeformableVolumeDefinition(
        PhysicsVector center,
        PhysicsVector dimensions,
        float density,
        float youngs,
        float poissons,
        float dynamicFriction,
        float damping,
        float maxEdgeLength,
        int voxels
) {
    public DeformableVolumeDefinition {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(dimensions, "dimensions");
        if (dimensions.x() <= 0.0D || dimensions.y() <= 0.0D || dimensions.z() <= 0.0D) {
            throw new IllegalArgumentException("Deformable volume dimensions must be positive");
        }
        if (density <= 0.0F) {
            throw new IllegalArgumentException("Deformable volume density must be positive");
        }
        if (youngs <= 0.0F) {
            throw new IllegalArgumentException("Young's modulus must be positive");
        }
        if (poissons < 0.0F || poissons >= 0.5F) {
            throw new IllegalArgumentException("Poisson ratio must be in [0, 0.5)");
        }
        if (dynamicFriction < 0.0F) {
            throw new IllegalArgumentException("Dynamic friction must not be negative");
        }
        if (damping < 0.0F) {
            throw new IllegalArgumentException("Damping must not be negative");
        }
        if (maxEdgeLength < 0.0F) {
            throw new IllegalArgumentException("Max edge length must not be negative");
        }
        if (voxels < 1) {
            throw new IllegalArgumentException("Voxel resolution must be positive");
        }
    }
}
