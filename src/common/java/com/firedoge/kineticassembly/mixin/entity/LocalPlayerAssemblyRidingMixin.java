package com.firedoge.kineticassembly.mixin.entity;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityCollisionAccess;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityOrientation;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityKicking;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityPositioning;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPlotProjection;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyVehicleRiding;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyVectors;
import com.mojang.authlib.GameProfile;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerAssemblyRidingMixin extends Player {
    @Unique
    private int kinetic_assembly$rideDebugTicks;

    protected LocalPlayerAssemblyRidingMixin(Level level, BlockPos pos, float yRot, GameProfile profile) {
        super(level, pos, yRot, profile);
    }

    @Redirect(
            method = "aiStep",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/Vec3;add(DDD)Lnet/minecraft/world/phys/Vec3;",
                    ordinal = 0
            )
    )
    private Vec3 kinetic_assembly$modifyAssemblyFlightDirection(Vec3 movement, double x, double y, double z) {
        Vec3 oriented = AssemblyEntityOrientation.localToWorld(this, new Vec3(x, y, z));
        if (oriented == null) {
            return movement.add(x, y, z);
        }
        return movement.add(oriented);
    }

    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;Z)Z", at = @At("RETURN"))
    private void kinetic_assembly$lookLocalWhenStartingAssemblyRide(
            Entity vehicle,
            boolean force,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!cir.getReturnValue() || !AssemblyEntityKicking.shouldKick(this)) {
            return;
        }

        Entity currentVehicle = getVehicle();
        if (currentVehicle == null) {
            return;
        }
        AssemblyPlotProjection projection = AssemblyVehicleRiding.vehicleProjection(currentVehicle);
        if (projection == null) {
            return;
        }

        Vec3 lookDirection = kinetic_assembly$calculateViewVector(getXRot(), getYRot());
        Vec3 localLookDirection = projection.worldDirectionToPlot(lookDirection);
        if (!AssemblyVectors.finite(localLookDirection)) {
            return;
        }

        currentVehicle.positionRider(this);
        AssemblyEntityPositioning.setOldPosNoMovement(this);
        lookAt(EntityAnchorArgument.Anchor.FEET, position().add(localLookDirection));
        AssemblyVehicleRiding.beginPassengerRide(this, (AssemblyEntityCollisionAccess) this, currentVehicle);
        kinetic_assembly$rideDebugTicks = 20;
        kinetic_assembly$debugAssemblyRideState("client-start-riding", currentVehicle);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void kinetic_assembly$syncAssemblyPassengerVehicleTracking(CallbackInfo ci) {
        AssemblyVehicleRiding.syncPassengerTracking(this, (AssemblyEntityCollisionAccess) this);
        kinetic_assembly$restoreAuthoritativeAssemblyRide();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void kinetic_assembly$debugAssemblyRideTick(CallbackInfo ci) {
        AssemblyVehicleRiding.syncPassengerTracking(this, (AssemblyEntityCollisionAccess) this);
        if (kinetic_assembly$rideDebugTicks <= 0) {
            return;
        }
        kinetic_assembly$debugAssemblyRideState("client-tick", getVehicle());
        kinetic_assembly$rideDebugTicks--;
    }

    @Inject(method = "removeVehicle", at = @At("HEAD"))
    private void kinetic_assembly$lookWorldWhenStoppingAssemblyRide(CallbackInfo ci) {
        Entity vehicle = getVehicle();
        if (vehicle == null || !AssemblyEntityKicking.shouldKick(this)) {
            return;
        }
        if (kinetic_assembly$isAuthoritativeClientRide(vehicle)) {
            return;
        }
        AssemblyPlotProjection projection = AssemblyVehicleRiding.vehicleProjection(vehicle);
        if (projection == null) {
            return;
        }

        AssemblyVehicleRiding.markPassengerDismounted(this, (AssemblyEntityCollisionAccess) this, vehicle);
        kinetic_assembly$debugAssemblyRideState("client-remove-vehicle", vehicle);
        Vec3 lookDirection = kinetic_assembly$calculateViewVector(getXRot(), getYRot());
        Vec3 worldLookDirection = projection.plotDirectionToWorld(lookDirection);
        if (!AssemblyVectors.finite(worldLookDirection)) {
            return;
        }
        lookAt(EntityAnchorArgument.Anchor.FEET, position().add(worldLookDirection));
    }

    @Override
    public void stopRiding() {
        Entity vehicle = getVehicle();
        boolean authoritativeClientRide = kinetic_assembly$isAuthoritativeClientRide(vehicle);
        kinetic_assembly$debugAssemblyRideState("client-stop-riding-before", vehicle);
        if (vehicle != null && !authoritativeClientRide) {
            AssemblyVehicleRiding.markPassengerDismounted(this, (AssemblyEntityCollisionAccess) this, vehicle);
        }
        super.stopRiding();
        kinetic_assembly$debugAssemblyRideState("client-stop-riding-after", vehicle);

        if (level().isClientSide
                && vehicle != null
                && vehicle != getVehicle()
                && !authoritativeClientRide
                && AssemblyEntityKicking.shouldKick(this)
                && AssemblyVehicleRiding.vehicleProjection(vehicle) != null) {
            kinetic_assembly$dismountAssemblyVehicle(vehicle);
        }
    }

    @Unique
    private void kinetic_assembly$restoreAuthoritativeAssemblyRide() {
        if (!level().isClientSide || getVehicle() != null) {
            return;
        }

        Entity vehicle = AssemblyVehicleRiding.authoritativeClientVehicle(this);
        if (vehicle == null) {
            return;
        }

        startRiding(vehicle, true);
    }

    @Unique
    private boolean kinetic_assembly$isAuthoritativeClientRide(Entity vehicle) {
        return level().isClientSide
                && vehicle != null
                && AssemblyVehicleRiding.authoritativeClientVehicle(this) == vehicle;
    }

    @Unique
    private void kinetic_assembly$dismountAssemblyVehicle(Entity vehicle) {
        Vec3 dismountPosition;
        if (isRemoved()) {
            dismountPosition = position();
        } else if (!vehicle.isRemoved() && !level().getBlockState(vehicle.blockPosition()).is(BlockTags.PORTALS)) {
            dismountPosition = vehicle.getDismountLocationForPassenger(this);
        } else {
            Vec3 vehiclePosition = AssemblyEntityPositioning.projectOutOfAssembly(level(), vehicle.position());
            double y = Math.max(getY(), vehiclePosition.y);
            dismountPosition = new Vec3(getX(), y, getZ());
        }

        Vec3 projected = AssemblyEntityPositioning.projectOutOfAssembly(level(), dismountPosition);
        if (AssemblyVectors.finite(projected)) {
            setPos(projected);
        }
    }

    @Unique
    private static Vec3 kinetic_assembly$calculateViewVector(float xRot, float yRot) {
        float xRotRadians = xRot * (float) (Math.PI / 180.0D);
        float negativeYRotRadians = -yRot * (float) (Math.PI / 180.0D);
        float yCos = Mth.cos(negativeYRotRadians);
        float ySin = Mth.sin(negativeYRotRadians);
        float xCos = Mth.cos(xRotRadians);
        float xSin = Mth.sin(xRotRadians);
        return new Vec3(ySin * xCos, -xSin, yCos * xCos);
    }

    @Unique
    private void kinetic_assembly$debugAssemblyRideState(String phase, Entity vehicle) {
        if (vehicle == null && kinetic_assembly$rideDebugTicks <= 0) {
            return;
        }

        AssemblyEntityCollisionAccess access = (AssemblyEntityCollisionAccess) this;
        AssemblyPlotProjection projection = vehicle == null ? null : AssemblyVehicleRiding.vehicleProjection(vehicle);
        KineticAssembly.LOGGER.info(
                "[kinetic_assembly-ride] {} ticksLeft={} player={} vehicle={} type={} passengers={} playerVehicle={} vehiclePos={} vehicleRemoved={} vehicleRemovalReason={} projection={} playerPos={} tracking={} state={} plot={}",
                phase,
                kinetic_assembly$rideDebugTicks,
                getId(),
                vehicle == null ? null : vehicle.getId(),
                vehicle == null ? null : EntityType.getKey(vehicle.getType()),
                vehicle == null ? null : vehicle.getPassengers().stream().map(Entity::getId).toList(),
                getVehicle() == null ? null : getVehicle().getId(),
                vehicle == null ? null : vehicle.position(),
                vehicle == null ? null : vehicle.isRemoved(),
                vehicle == null ? null : vehicle.getRemovalReason(),
                projection == null ? null : projection.id(),
                position(),
                access.kinetic_assembly$trackingAssemblyId(),
                access.kinetic_assembly$assemblyState(),
                access.kinetic_assembly$plotPosition()
        );
    }
}
