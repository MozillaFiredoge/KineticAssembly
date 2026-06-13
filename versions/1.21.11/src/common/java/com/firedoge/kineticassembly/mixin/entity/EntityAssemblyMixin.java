package com.firedoge.kineticassembly.mixin.entity;

import java.util.UUID;

import javax.annotation.Nullable;

import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityCollisionAccess;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityKicking;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityOrientation;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityPositioning;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPlotProjection;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyTrackingPointSavedData;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyVehicleRiding;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.serialization.Codec;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityAssemblyMixin {
    @Shadow
    private Level level;

    @Shadow
    @Nullable
    public abstract Entity getVehicle();

    @Shadow
    public abstract boolean hasPassenger(Entity entity);

    @Shadow
    public abstract void setPos(Vec3 position);

    @Shadow
    public abstract void setPosRaw(double x, double y, double z);

    @Shadow
    public abstract double getX();

    @Shadow
    public abstract double getY();

    @Shadow
    public abstract double getZ();

    @Shadow
    public abstract Vec3 position();

    @Shadow
    public abstract float getYRot();

    @Shadow
    public abstract Vec3 getDeltaMovement();

    @Shadow
    public abstract void setDeltaMovement(Vec3 movement);

    @Shadow
    private static Vec3 getInputVector(Vec3 relative, float motionScaler, float facing) {
        throw new AssertionError();
    }

    @Inject(method = "rideTick", at = @At("TAIL"))
    private void kinetic_assembly$kickRiderOutOfContainedAssemblyVehicle(CallbackInfo ci) {
        Entity self = kinetic_assembly$self();
        Entity vehicle = getVehicle();
        if (vehicle == null || !AssemblyEntityKicking.shouldKick(self)) {
            return;
        }

        AssemblyVehicleRiding.positionPassengerInWorldIfPlotLocal(vehicle, self);
    }

    @Inject(method = "positionRider(Lnet/minecraft/world/entity/Entity;)V", at = @At("TAIL"))
    private void kinetic_assembly$kickPositionedRiderOutOfContainedAssembly(Entity passenger, CallbackInfo ci) {
        if (!hasPassenger(passenger)) {
            return;
        }

        AssemblyVehicleRiding.positionPassengerInWorld(kinetic_assembly$self(), passenger);
    }

    @WrapOperation(
            method = "applyMovementEmissionAndPlaySound",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/Vec3;horizontalDistance()D"
            )
    )
    private double kinetic_assembly$assemblyLocalWalkDistance(Vec3 movement, Operation<Double> original) {
        Vec3 localMovement = AssemblyEntityOrientation.worldToLocal(kinetic_assembly$self(), movement);
        return localMovement == null ? original.call(movement) : original.call(localMovement);
    }

    @Inject(method = "moveRelative", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$moveRelativeInAssemblyOrientation(float amount, Vec3 relative, CallbackInfo ci) {
        Vec3 localInput = getInputVector(relative, amount, getYRot());
        Vec3 orientedInput = AssemblyEntityOrientation.localToWorld(kinetic_assembly$self(), localInput);
        if (orientedInput == null) {
            return;
        }
        setDeltaMovement(getDeltaMovement().add(orientedInput));
        ci.cancel();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
            method = "saveWithoutId",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/storage/ValueOutput;store(Ljava/lang/String;Lcom/mojang/serialization/Codec;Ljava/lang/Object;)V",
                    ordinal = 0
            )
    )
    private void kinetic_assembly$savePassengerAssemblyPosition(
            ValueOutput output,
            String key,
            Codec codec,
            Object originalPosition
    ) {
        Entity self = kinetic_assembly$self();
        if (!AssemblyEntityKicking.shouldKick(self)) {
            output.store(key, codec, originalPosition);
            return;
        }

        Entity vehicle = getVehicle();
        if (vehicle == null) {
            output.store(key, codec, originalPosition);
            return;
        }

        AssemblyPlotProjection projection = AssemblyEntityPositioning.containingProjection(vehicle);
        if (projection == null) {
            output.store(key, codec, originalPosition);
            return;
        }

        if (self instanceof ServerPlayer serverPlayer) {
            Vec3 plotPosition = projection.worldToPlot(self.position());
            UUID loginPoint = AssemblyTrackingPointSavedData.getOrLoad(serverPlayer.level())
                    .generateTrackingPoint(serverPlayer, projection, plotPosition);
            if (loginPoint != null) {
                output.store(AssemblyTrackingPointSavedData.LOGIN_POINT_TAG, UUIDUtil.CODEC, loginPoint);
            }
        }

        output.store(key, Vec3.CODEC, new Vec3(getX(), getY(), getZ()));
    }

    @Inject(
            method = "load",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;setPosRaw(DDD)V",
                    shift = At.Shift.AFTER
            )
    )
    private void kinetic_assembly$loadAssemblyLoginPoint(ValueInput input, CallbackInfo ci) {
        if (!(level instanceof ServerLevel serverLevel)
                || input.read(AssemblyTrackingPointSavedData.LOGIN_POINT_TAG, UUIDUtil.CODEC).isEmpty()) {
            return;
        }

        UUID loginPoint = input.read(AssemblyTrackingPointSavedData.LOGIN_POINT_TAG, UUIDUtil.CODEC).orElseThrow();
        AssemblyTrackingPointSavedData.TakenLoginPoint point =
                AssemblyTrackingPointSavedData.getOrLoad(serverLevel).take(serverLevel, loginPoint, true);
        if (point == null) {
            return;
        }

        Vec3 position = point.position();
        setPosRaw(position.x, position.y, position.z);
        Entity self = kinetic_assembly$self();
        if (point.assemblyId() != null && self instanceof AssemblyEntityCollisionAccess access) {
            access.kinetic_assembly$setTrackingAssemblyId(point.assemblyId());
            access.kinetic_assembly$setPlotPosition(point.localAnchor());
            access.kinetic_assembly$setAssemblyState(null);
            AssemblyEntityPositioning.setOldPosNoMovement(self);
        }
    }

    @Unique
    private Entity kinetic_assembly$self() {
        return (Entity) (Object) this;
    }
}
