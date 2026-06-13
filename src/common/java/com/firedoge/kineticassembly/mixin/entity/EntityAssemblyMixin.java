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

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
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
            method = "move",
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

    @Redirect(
            method = "saveWithoutId",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/nbt/CompoundTag;put(Ljava/lang/String;Lnet/minecraft/nbt/Tag;)Lnet/minecraft/nbt/Tag;",
                    ordinal = 0
            )
    )
    private Tag kinetic_assembly$savePassengerAssemblyPosition(CompoundTag tag, String key, Tag originalPosition) {
        Entity self = kinetic_assembly$self();
        if (!AssemblyEntityKicking.shouldKick(self)) {
            return tag.put(key, originalPosition);
        }

        Entity vehicle = getVehicle();
        if (vehicle == null) {
            return tag.put(key, originalPosition);
        }

        AssemblyPlotProjection projection = AssemblyEntityPositioning.containingProjection(vehicle);
        if (projection == null) {
            return tag.put(key, originalPosition);
        }

        if (self instanceof ServerPlayer serverPlayer) {
            Vec3 plotPosition = projection.worldToPlot(self.position());
            UUID loginPoint = AssemblyTrackingPointSavedData.getOrLoad(serverPlayer.serverLevel())
                    .generateTrackingPoint(serverPlayer, projection, plotPosition);
            if (loginPoint != null) {
                tag.putUUID(AssemblyTrackingPointSavedData.LOGIN_POINT_TAG, loginPoint);
            }
        }

        return tag.put(key, kinetic_assembly$newDoubleList(getX(), getY(), getZ()));
    }

    @Inject(
            method = "load",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;setPosRaw(DDD)V",
                    shift = At.Shift.AFTER
            )
    )
    private void kinetic_assembly$loadAssemblyLoginPoint(CompoundTag tag, CallbackInfo ci) {
        if (!(level instanceof ServerLevel serverLevel)
                || !tag.contains(AssemblyTrackingPointSavedData.LOGIN_POINT_TAG)) {
            return;
        }

        UUID loginPoint = tag.getUUID(AssemblyTrackingPointSavedData.LOGIN_POINT_TAG);
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
        tag.remove("RootVehicle");
    }

    @Unique
    private Entity kinetic_assembly$self() {
        return (Entity) (Object) this;
    }

    @Unique
    private static ListTag kinetic_assembly$newDoubleList(double... values) {
        ListTag tag = new ListTag();
        for (double value : values) {
            tag.add(DoubleTag.valueOf(value));
        }
        return tag;
    }
}
