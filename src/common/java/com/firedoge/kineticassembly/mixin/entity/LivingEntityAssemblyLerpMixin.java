package com.firedoge.kineticassembly.mixin.entity;

import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityCollision;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityCollisionAccess;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityKicking;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPlotProjection;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyVectors;

import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityAssemblyLerpMixin extends Entity implements AssemblyEntityCollisionAccess {
    @Shadow
    protected int lerpSteps;

    @Shadow
    protected double lerpYRot;

    @Shadow
    protected double lerpXRot;

    @Unique
    private Vec3 kinetic_assembly$plotLerpTarget = Vec3.ZERO;

    @Unique
    private int kinetic_assembly$plotLerpSteps;

    @Unique
    private int kinetic_assembly$plotRotLerpSteps;

    public LivingEntityAssemblyLerpMixin(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    public void kinetic_assembly$plotLerpTo(Vec3 position, int lerpSteps) {
        kinetic_assembly$plotLerpTarget = position;
        kinetic_assembly$plotLerpSteps = lerpSteps;
    }

    @Inject(method = "recreateFromPacket", at = @At("TAIL"))
    private void kinetic_assembly$recreateFromAssemblyAddPacket(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        Vec3 packetPosition = new Vec3(packet.getX(), packet.getY(), packet.getZ());
        AssemblyPlotProjection projection = AssemblyEntityCollision.spawnPacketProjection(level(), packetPosition);
        if (projection == null) {
            return;
        }
        if (!AssemblyEntityKicking.shouldKick(this)) {
            return;
        }

        Vec3 worldPosition = projection.plotToWorld(packetPosition);
        if (!AssemblyVectors.finite(worldPosition)) {
            return;
        }
        setPos(worldPosition.x, worldPosition.y, worldPosition.z);
        kinetic_assembly$setTrackingAssemblyId(projection.id());
        kinetic_assembly$setPlotPosition(packetPosition);
    }

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void kinetic_assembly$setupAssemblyPlotLerp(CallbackInfo ci) {
        if (kinetic_assembly$plotPosition() != null && lerpSteps > 0) {
            kinetic_assembly$plotRotLerpSteps = lerpSteps;
            lerpSteps = 0;
        }
    }

    @Inject(
            method = "aiStep",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;setDeltaMovement(DDD)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void kinetic_assembly$applyAssemblyPlotLerp(CallbackInfo ci) {
        Vec3 plotPosition = kinetic_assembly$plotPosition();
        if (plotPosition == null) {
            kinetic_assembly$plotLerpSteps = 0;
            kinetic_assembly$plotRotLerpSteps = 0;
            return;
        }

        if (kinetic_assembly$plotLerpSteps > 0) {
            kinetic_assembly$setPlotPosition(plotPosition.lerp(kinetic_assembly$plotLerpTarget, 1.0D / kinetic_assembly$plotLerpSteps));
            kinetic_assembly$plotLerpSteps--;
        }
        if (kinetic_assembly$plotRotLerpSteps > 0) {
            double yRotDifference = Mth.wrapDegrees(lerpYRot - getYRot());
            setYRot(getYRot() + (float) yRotDifference / kinetic_assembly$plotRotLerpSteps);
            setXRot(getXRot() + (float) (lerpXRot - getXRot()) / kinetic_assembly$plotRotLerpSteps);
            kinetic_assembly$plotRotLerpSteps--;
            setRot(getYRot(), getXRot());
        }
    }
}
