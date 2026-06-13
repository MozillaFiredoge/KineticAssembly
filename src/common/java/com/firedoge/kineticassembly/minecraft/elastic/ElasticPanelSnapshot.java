package com.firedoge.kineticassembly.minecraft.elastic;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record ElasticPanelSnapshot(
        UUID id,
        ResourceKey<Level> levelKey,
        double centerX,
        double topY,
        double centerZ,
        double width,
        double depth,
        double stiffness,
        double maxDeflection,
        double deflection,
        double load,
        int signal,
        boolean powered,
        boolean outputBlocked,
        BlockPos outputPos,
        UUID visualEntityId
) {
    public ElasticPanelSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(levelKey, "levelKey");
        Objects.requireNonNull(outputPos, "outputPos");
        Objects.requireNonNull(visualEntityId, "visualEntityId");
    }
}
