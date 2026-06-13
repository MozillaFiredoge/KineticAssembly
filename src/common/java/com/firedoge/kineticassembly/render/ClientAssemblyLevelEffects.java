package com.firedoge.kineticassembly.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class ClientAssemblyLevelEffects {
    private ClientAssemblyLevelEffects() {
    }

    public static void addDestroyBlockParticles(
            ClientLevel level,
            ParticleEngine particleEngine,
            ClientAssemblyEffectProjection.Projection projection,
            BlockPos pos,
            BlockState state
    ) {
        state.getShape(level, pos).forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
            double sizeX = Math.min(1.0D, maxX - minX);
            double sizeY = Math.min(1.0D, maxY - minY);
            double sizeZ = Math.min(1.0D, maxZ - minZ);
            int countX = Math.max(2, (int) Math.ceil(sizeX / 0.25D));
            int countY = Math.max(2, (int) Math.ceil(sizeY / 0.25D));
            int countZ = Math.max(2, (int) Math.ceil(sizeZ / 0.25D));

            for (int x = 0; x < countX; x++) {
                for (int y = 0; y < countY; y++) {
                    for (int z = 0; z < countZ; z++) {
                        double fractionX = ((double) x + 0.5D) / (double) countX;
                        double fractionY = ((double) y + 0.5D) / (double) countY;
                        double fractionZ = ((double) z + 0.5D) / (double) countZ;
                        Vec3 plot = new Vec3(
                                (double) pos.getX() + fractionX * sizeX + minX,
                                (double) pos.getY() + fractionY * sizeY + minY,
                                (double) pos.getZ() + fractionZ * sizeZ + minZ
                        );
                        Vec3 world = projection.toWorld(plot);
                        Vec3 worldSpeed = projection.directionToWorld(new Vec3(
                                fractionX - 0.5D,
                                fractionY - 0.5D,
                                fractionZ - 0.5D
                        ));
                        particleEngine.add(
                                new ProjectedTerrainParticle(
                                        level,
                                        world,
                                        worldSpeed,
                                        state,
                                        pos
                                ).updateSprite(state, pos)
                        );
                    }
                }
            }
        });
    }

    public static boolean addBreakingBlockParticle(
            ClientLevel level,
            ParticleEngine particleEngine,
            ClientAssemblyEffectProjection.Projection projection,
            BlockPos pos,
            Direction side
    ) {
        BlockState state = level.getBlockState(pos);
        if (state.getRenderShape() == RenderShape.INVISIBLE || !state.shouldSpawnTerrainParticles()) {
            return false;
        }

        Vec3 plot = sampleHitParticlePos(level, pos, state.getShape(level, pos).bounds(), side);
        Vec3 world = projection.toWorld(plot);
        particleEngine.add(
                ((ProjectedTerrainParticle) new ProjectedTerrainParticle(
                        level,
                        world,
                        Vec3.ZERO,
                        state,
                        pos
                ).updateSprite(state, pos)).setPower(0.2F).scale(0.6F)
        );
        return true;
    }

    private static Vec3 sampleHitParticlePos(ClientLevel level, BlockPos pos, AABB bounds, Direction side) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        double particleX = (double) x + randomIn(level, bounds.maxX - bounds.minX - 0.2D) + 0.1D + bounds.minX;
        double particleY = (double) y + randomIn(level, bounds.maxY - bounds.minY - 0.2D) + 0.1D + bounds.minY;
        double particleZ = (double) z + randomIn(level, bounds.maxZ - bounds.minZ - 0.2D) + 0.1D + bounds.minZ;
        if (side == Direction.DOWN) {
            particleY = (double) y + bounds.minY - 0.1D;
        }
        if (side == Direction.UP) {
            particleY = (double) y + bounds.maxY + 0.1D;
        }
        if (side == Direction.NORTH) {
            particleZ = (double) z + bounds.minZ - 0.1D;
        }
        if (side == Direction.SOUTH) {
            particleZ = (double) z + bounds.maxZ + 0.1D;
        }
        if (side == Direction.WEST) {
            particleX = (double) x + bounds.minX - 0.1D;
        }
        if (side == Direction.EAST) {
            particleX = (double) x + bounds.maxX + 0.1D;
        }
        return new Vec3(particleX, particleY, particleZ);
    }

    private static double randomIn(ClientLevel level, double width) {
        return level.random.nextDouble() * Math.max(0.0D, width);
    }
}
