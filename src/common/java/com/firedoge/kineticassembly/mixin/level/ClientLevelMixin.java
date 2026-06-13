package com.firedoge.kineticassembly.mixin.level;

import com.firedoge.kineticassembly.minecraft.assembly.ClientAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainerHolder;
import com.firedoge.kineticassembly.render.ClientAssemblyEffectProjection;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin implements AssemblyContainerHolder {
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
            method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void kinetic_assembly$playPlotSeededSound(
            Player player,
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
            method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;ZDDDDDD)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void kinetic_assembly$addPlotParticle(
            ParticleOptions particleData,
            boolean forceAlwaysRender,
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
            kinetic_assembly$self().addParticle(particleData, forceAlwaysRender, world.x, world.y, world.z, worldSpeed.x, worldSpeed.y, worldSpeed.z);
        } finally {
            kinetic_assembly$projectingAssemblyEffect = false;
        }
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
