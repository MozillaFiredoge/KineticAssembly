package com.firedoge.kineticassembly.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class ProjectedTerrainParticle extends TerrainParticle {
    public ProjectedTerrainParticle(
            ClientLevel level,
            Vec3 worldPosition,
            Vec3 worldSpeed,
            BlockState state,
            BlockPos spritePos
    ) {
        super(level, worldPosition.x, worldPosition.y, worldPosition.z, worldSpeed.x, worldSpeed.y, worldSpeed.z, state, spritePos);
    }
}
