package com.firedoge.kineticassembly.mixin.level;

import com.firedoge.kineticassembly.minecraft.assembly.ClientAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainerHolder;
import com.firedoge.kineticassembly.render.ClientAssemblyEffectProjection;
import com.firedoge.kineticassembly.render.ProjectedTerrainParticle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin implements AssemblyContainerHolder {
    @Shadow
    private Minecraft minecraft;

    @Unique
    private final ClientAssemblyContainer kinetic_assembly$assemblyContainer =
            new ClientAssemblyContainer((ClientLevel) (Object) this);
    @Unique
    private boolean kinetic_assembly$projectingAssemblyEffect;

    @Override
    public AssemblyContainer kinetic_assembly$assemblyContainer() {
        return kinetic_assembly$assemblyContainer;
    }

    @Inject(
            method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void kinetic_assembly$playPlotSeededSound(
            Entity player,
            double x,
            double y,
            double z,
            Holder<SoundEvent> sound,
            SoundSource category,
            float volume,
            float pitch,
            long seed,
            CallbackInfo ci
    ) {
        if (kinetic_assembly$projectingAssemblyEffect) {
            return;
        }
        ClientAssemblyEffectProjection.Projection projection = kinetic_assembly$projection(BlockPos.containing(x, y, z));
        if (projection == null) {
            return;
        }
        Vec3 world = projection.toWorld(new Vec3(x, y, z));

        kinetic_assembly$projectingAssemblyEffect = true;
        try {
            kinetic_assembly$self().playSeededSound(player, world.x, world.y, world.z, sound, category, volume, pitch, seed);
        } finally {
            kinetic_assembly$projectingAssemblyEffect = false;
        }
        ci.cancel();
    }

    @Inject(
            method = "playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void kinetic_assembly$playPlotLocalSound(
            double x,
            double y,
            double z,
            SoundEvent sound,
            SoundSource category,
            float volume,
            float pitch,
            boolean distanceDelay,
            CallbackInfo ci
    ) {
        if (kinetic_assembly$projectingAssemblyEffect) {
            return;
        }
        ClientAssemblyEffectProjection.Projection projection = kinetic_assembly$projection(BlockPos.containing(x, y, z));
        if (projection == null) {
            return;
        }
        Vec3 world = projection.toWorld(new Vec3(x, y, z));

        kinetic_assembly$projectingAssemblyEffect = true;
        try {
            kinetic_assembly$self().playLocalSound(world.x, world.y, world.z, sound, category, volume, pitch, distanceDelay);
        } finally {
            kinetic_assembly$projectingAssemblyEffect = false;
        }
        ci.cancel();
    }

    @Inject(
            method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void kinetic_assembly$addPlotParticle(
            ParticleOptions particleData,
            double x,
            double y,
            double z,
            double xSpeed,
            double ySpeed,
            double zSpeed,
            CallbackInfo ci
    ) {
        if (kinetic_assembly$projectingAssemblyEffect) {
            return;
        }
        ClientAssemblyEffectProjection.Projection projection = kinetic_assembly$projection(BlockPos.containing(x, y, z));
        if (projection == null) {
            return;
        }

        Vec3 world = projection.toWorld(new Vec3(x, y, z));
        Vec3 worldSpeed = projection.directionToWorld(new Vec3(xSpeed, ySpeed, zSpeed));
        kinetic_assembly$projectingAssemblyEffect = true;
        try {
            kinetic_assembly$self().addParticle(particleData, world.x, world.y, world.z, worldSpeed.x, worldSpeed.y, worldSpeed.z);
        } finally {
            kinetic_assembly$projectingAssemblyEffect = false;
        }
        ci.cancel();
    }

    @Inject(
            method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDDDD)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void kinetic_assembly$addPlotParticle(
            ParticleOptions particleData,
            boolean forceAlwaysRender,
            boolean decreased,
            double x,
            double y,
            double z,
            double xSpeed,
            double ySpeed,
            double zSpeed,
            CallbackInfo ci
    ) {
        if (kinetic_assembly$projectingAssemblyEffect) {
            return;
        }
        ClientAssemblyEffectProjection.Projection projection = kinetic_assembly$projection(BlockPos.containing(x, y, z));
        if (projection == null) {
            return;
        }

        Vec3 world = projection.toWorld(new Vec3(x, y, z));
        Vec3 worldSpeed = projection.directionToWorld(new Vec3(xSpeed, ySpeed, zSpeed));
        kinetic_assembly$projectingAssemblyEffect = true;
        try {
            kinetic_assembly$self().addParticle(particleData, forceAlwaysRender, decreased, world.x, world.y, world.z, worldSpeed.x, worldSpeed.y, worldSpeed.z);
        } finally {
            kinetic_assembly$projectingAssemblyEffect = false;
        }
        ci.cancel();
    }

    @Inject(method = "addDestroyBlockEffect", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$destroyPlotBlockParticles(BlockPos pos, BlockState state, CallbackInfo ci) {
        ClientAssemblyEffectProjection.Projection projection = kinetic_assembly$projection(pos);
        if (projection == null || state.isAir()) {
            return;
        }

        VoxelShape shape = state.getShape(kinetic_assembly$self(), pos);
        shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
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
                        minecraft.particleEngine.add(
                                new ProjectedTerrainParticle(
                                        kinetic_assembly$self(),
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
        ci.cancel();
    }

    @Inject(method = "addBreakingBlockEffect(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/phys/HitResult;)V", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$crackPlotBlockParticles(BlockPos pos, Direction side, HitResult hitResult, CallbackInfo ci) {
        ClientAssemblyEffectProjection.Projection projection = kinetic_assembly$projection(pos);
        if (projection == null) {
            return;
        }

        BlockState state = kinetic_assembly$self().getBlockState(pos);
        if (state.getRenderShape() == RenderShape.INVISIBLE || !state.shouldSpawnTerrainParticles()) {
            ci.cancel();
            return;
        }

        AabbSample sample = kinetic_assembly$sampleHitParticlePos(pos, state.getShape(kinetic_assembly$self(), pos).bounds(), side);
        Vec3 world = projection.toWorld(sample.plotPosition());
        minecraft.particleEngine.add(
                ((ProjectedTerrainParticle) new ProjectedTerrainParticle(
                        kinetic_assembly$self(),
                        world,
                        Vec3.ZERO,
                        state,
                        pos
                ).updateSprite(state, pos)).setPower(0.2F).scale(0.6F)
        );
        ci.cancel();
    }

    @Inject(method = "setSectionDirtyWithNeighbors", at = @At("HEAD"))
    private void kinetic_assembly$markPlotLightDirty(int sectionX, int sectionY, int sectionZ, CallbackInfo ci) {
        kinetic_assembly$assemblyContainer.markPlotLightChanged(sectionX, sectionZ);
    }

    @Unique
    private ClientAssemblyEffectProjection.Projection kinetic_assembly$projection(BlockPos plotPos) {
        return ClientAssemblyEffectProjection.projection(kinetic_assembly$self(), plotPos)
                .orElse(null);
    }

    @Unique
    private AabbSample kinetic_assembly$sampleHitParticlePos(BlockPos pos, AABB bounds, Direction side) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        double particleX = (double) x + kinetic_assembly$randomIn(bounds.maxX - bounds.minX - 0.2D) + 0.1D + bounds.minX;
        double particleY = (double) y + kinetic_assembly$randomIn(bounds.maxY - bounds.minY - 0.2D) + 0.1D + bounds.minY;
        double particleZ = (double) z + kinetic_assembly$randomIn(bounds.maxZ - bounds.minZ - 0.2D) + 0.1D + bounds.minZ;
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
        return new AabbSample(new Vec3(particleX, particleY, particleZ));
    }

    @Unique
    private double kinetic_assembly$randomIn(double width) {
        return kinetic_assembly$self().random.nextDouble() * Math.max(0.0D, width);
    }

    @Unique
    private ClientLevel kinetic_assembly$self() {
        return (ClientLevel) (Object) this;
    }

    private record AabbSample(Vec3 plotPosition) {
    }
}
