package com.firedoge.kineticassembly.minecraft.fem;

import java.util.Objects;

import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.minecraft.scene.PhysicsObjectId;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record FemVolumeSnapshot(
        PhysicsObjectId id,
        ResourceKey<Level> levelKey,
        FemVolumeKind kind,
        PhysicsVector center,
        PhysicsVector dimensions,
        float density,
        float youngs,
        int voxels,
        int collisionVertexCount,
        int collisionTetrahedronCount,
        int simulationVertexCount,
        int simulationTetrahedronCount,
        int visualMarkerCount,
        double maxDisplacement,
        double averageDisplacement,
        double minScale,
        double maxScale,
        double volumeRatio
) {
    public FemVolumeSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(levelKey, "levelKey");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(dimensions, "dimensions");
    }
}
