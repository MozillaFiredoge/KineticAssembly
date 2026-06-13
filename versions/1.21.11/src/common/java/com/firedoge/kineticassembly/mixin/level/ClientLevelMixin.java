package com.firedoge.kineticassembly.mixin.level;

import com.firedoge.kineticassembly.minecraft.assembly.ClientAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainerHolder;
import com.firedoge.kineticassembly.render.ClientAssemblyEffectProjection;
import com.firedoge.kineticassembly.render.ClientAssemblyLevelEffects;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
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

        ClientAssemblyLevelEffects.addDestroyBlockParticles(
                kinetic_assembly$self(),
                minecraft.particleEngine,
                projection,
                pos,
                state
        );
        ci.cancel();
    }

    @Inject(method = "addBreakingBlockEffect(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/phys/HitResult;)V", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$crackPlotBlockParticles(BlockPos pos, Direction side, HitResult hitResult, CallbackInfo ci) {
        ClientAssemblyEffectProjection.Projection projection = kinetic_assembly$projection(pos);
        if (projection == null) {
            return;
        }

        ClientAssemblyLevelEffects.addBreakingBlockParticle(
                kinetic_assembly$self(),
                minecraft.particleEngine,
                projection,
                pos,
                side
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
    private ClientLevel kinetic_assembly$self() {
        return (ClientLevel) (Object) this;
    }
}
