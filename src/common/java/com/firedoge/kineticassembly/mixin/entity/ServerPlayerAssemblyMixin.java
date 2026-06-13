package com.firedoge.kineticassembly.mixin.entity;

import java.util.UUID;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityCollisionAccess;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityMotor;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityPositioning;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPlotProjection;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyTrackingPointSavedData;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyVehicleRiding;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyVectors;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerAssemblyMixin extends Entity {
    @Unique
    private int kinetic_assembly$rideDebugTicks;

    @Unique
    private int kinetic_assembly$ridePassengerSyncTicks;

    @Unique
    private Entity kinetic_assembly$stoppingAssemblyVehicle;

    @Shadow
    public abstract ServerLevel serverLevel();

    public ServerPlayerAssemblyMixin(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    @WrapOperation(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;absMoveTo(DDDFF)V"
            )
    )
    private void kinetic_assembly$projectRetainedMinecartCameraPosition(
            ServerPlayer player,
            double x,
            double y,
            double z,
            float yRot,
            float xRot,
            Operation<Void> original
    ) {
        Entity camera = player.getCamera();
        if (camera instanceof AbstractMinecart) {
            AssemblyPlotProjection projection = AssemblyVehicleRiding.vehicleProjection(camera);
            if (projection != null) {
                Vec3 worldPosition = projection.plotToWorld(new Vec3(x, y, z));
                if (AssemblyVectors.finite(worldPosition)) {
                    original.call(player, worldPosition.x, worldPosition.y, worldPosition.z, yRot, xRot);
                    return;
                }
            }
        }

        original.call(player, x, y, z, yRot, xRot);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void kinetic_assembly$carryWithTrackedAssemblyCenter(CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        AssemblyEntityMotor.carryServerPlayer(self, (AssemblyEntityCollisionAccess) self);
        kinetic_assembly$syncRetainedVehiclePassengers(self);
        if (kinetic_assembly$rideDebugTicks > 0) {
            kinetic_assembly$debugAssemblyRideState("server-tick", self.getVehicle());
            kinetic_assembly$rideDebugTicks--;
        }
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void kinetic_assembly$saveAssemblyLoginPoint(CompoundTag tag, CallbackInfo ci) {
        UUID loginPoint = AssemblyTrackingPointSavedData.getOrLoad(serverLevel())
                .generateTrackingPoint((ServerPlayer) (Object) this);
        if (loginPoint != null) {
            tag.putUUID(AssemblyTrackingPointSavedData.LOGIN_POINT_TAG, loginPoint);
        }
    }

    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;Z)Z", at = @At("RETURN"))
    private void kinetic_assembly$debugAssemblyStartRiding(Entity vehicle, boolean force, CallbackInfoReturnable<Boolean> cir) {
        AssemblyPlotProjection projection = AssemblyVehicleRiding.vehicleProjection(vehicle);
        if (projection == null) {
            return;
        }

        ServerPlayer self = (ServerPlayer) (Object) this;
        if (cir.getReturnValue()) {
            AssemblyEntityCollisionAccess access = (AssemblyEntityCollisionAccess) self;
            AssemblyVehicleRiding.beginPassengerRide(self, access, vehicle);
            kinetic_assembly$rideDebugTicks = 20;
            kinetic_assembly$ridePassengerSyncTicks = 40;
            self.connection.send(new ClientboundSetPassengersPacket(vehicle));
        }
        KineticAssembly.LOGGER.info(
                "[kinetic_assembly-ride] server-start-riding result={} force={} player={} vehicle={} type={} passengers={} playerVehicle={} vehiclePos={} projection={} playerPos={}",
                cir.getReturnValue(),
                force,
                self.getId(),
                vehicle.getId(),
                EntityType.getKey(vehicle.getType()),
                vehicle.getPassengers().stream().map(Entity::getId).toList(),
                self.getVehicle() == null ? null : self.getVehicle().getId(),
                vehicle.position(),
                projection.id(),
                self.position()
        );
    }

    @Inject(method = "stopRiding", at = @At("HEAD"))
    private void kinetic_assembly$debugAssemblyStopRidingBefore(CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        Entity vehicle = self.getVehicle();
        kinetic_assembly$stoppingAssemblyVehicle = vehicle != null && AssemblyVehicleRiding.vehicleProjection(vehicle) != null
                ? vehicle
                : null;
        if (kinetic_assembly$stoppingAssemblyVehicle != null) {
            KineticAssembly.LOGGER.info(
                    "[kinetic_assembly-ride] server-stop-riding-caller player={} vehicle={} shift={} passenger={} tick={} stack={}",
                    self.getId(),
                    vehicle.getId(),
                    self.isShiftKeyDown(),
                    self.isPassenger(),
                    self.tickCount,
                    kinetic_assembly$compactStackTrace()
            );
        }
        kinetic_assembly$debugAssemblyStopRiding("server-stop-riding-before");
    }

    @Inject(method = "stopRiding", at = @At("RETURN"))
    private void kinetic_assembly$debugAssemblyStopRidingAfter(CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (kinetic_assembly$stoppingAssemblyVehicle != null && self.getVehicle() != kinetic_assembly$stoppingAssemblyVehicle) {
            AssemblyVehicleRiding.markPassengerDismounted(
                    self,
                    (AssemblyEntityCollisionAccess) self,
                    kinetic_assembly$stoppingAssemblyVehicle
            );
            self.connection.send(new ClientboundSetPassengersPacket(kinetic_assembly$stoppingAssemblyVehicle));
            kinetic_assembly$rideDebugTicks = 0;
            kinetic_assembly$ridePassengerSyncTicks = 0;
            kinetic_assembly$debugAssemblyRideState("server-stop-riding-after", kinetic_assembly$stoppingAssemblyVehicle);
        } else {
            kinetic_assembly$debugAssemblyStopRiding("server-stop-riding-after");
        }
        kinetic_assembly$stoppingAssemblyVehicle = null;
    }

    @Unique
    private void kinetic_assembly$syncRetainedVehiclePassengers(ServerPlayer player) {
        if (kinetic_assembly$ridePassengerSyncTicks <= 0) {
            return;
        }

        Entity vehicle = player.getVehicle();
        if (vehicle == null || AssemblyVehicleRiding.vehicleProjection(vehicle) == null) {
            kinetic_assembly$ridePassengerSyncTicks = 0;
            return;
        }

        if ((kinetic_assembly$ridePassengerSyncTicks & 1) == 0) {
            player.connection.send(new ClientboundSetPassengersPacket(vehicle));
        }
        kinetic_assembly$ridePassengerSyncTicks--;
    }

    private void kinetic_assembly$debugAssemblyStopRiding(String phase) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        Entity vehicle = self.getVehicle();
        if (vehicle == null) {
            return;
        }
        AssemblyPlotProjection projection = AssemblyVehicleRiding.vehicleProjection(vehicle);
        if (projection == null) {
            return;
        }

        KineticAssembly.LOGGER.info(
                "[kinetic_assembly-ride] {} player={} vehicle={} type={} passengers={} vehiclePos={} projection={} playerPos={}",
                phase,
                self.getId(),
                vehicle.getId(),
                EntityType.getKey(vehicle.getType()),
                vehicle.getPassengers().stream().map(Entity::getId).toList(),
                vehicle.position(),
                projection.id(),
                self.position()
        );
    }

    @Unique
    private void kinetic_assembly$debugAssemblyRideState(String phase, Entity vehicle) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        AssemblyEntityCollisionAccess access = (AssemblyEntityCollisionAccess) self;
        AssemblyPlotProjection projection = vehicle == null ? null : AssemblyVehicleRiding.vehicleProjection(vehicle);
        KineticAssembly.LOGGER.info(
                "[kinetic_assembly-ride] {} ticksLeft={} player={} vehicle={} type={} passengers={} playerVehicle={} vehiclePos={} vehicleRemoved={} vehicleRemovalReason={} projection={} playerPos={} tracking={} state={} plot={}",
                phase,
                kinetic_assembly$rideDebugTicks,
                self.getId(),
                vehicle == null ? null : vehicle.getId(),
                vehicle == null ? null : EntityType.getKey(vehicle.getType()),
                vehicle == null ? null : vehicle.getPassengers().stream().map(Entity::getId).toList(),
                self.getVehicle() == null ? null : self.getVehicle().getId(),
                vehicle == null ? null : vehicle.position(),
                vehicle == null ? null : vehicle.isRemoved(),
                vehicle == null ? null : vehicle.getRemovalReason(),
                projection == null ? null : projection.id(),
                self.position(),
                access.kinetic_assembly$trackingAssemblyId(),
                access.kinetic_assembly$assemblyState(),
                access.kinetic_assembly$plotPosition()
        );
    }

    @Unique
    private static String kinetic_assembly$compactStackTrace() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StringBuilder builder = new StringBuilder();
        int included = 0;
        for (int i = 3; i < stack.length && included < 14; i++) {
            StackTraceElement element = stack[i];
            String className = element.getClassName();
            if (className.startsWith("java.lang.Thread")) {
                continue;
            }

            if (builder.length() > 0) {
                builder.append(" <- ");
            }
            int packageEnd = className.lastIndexOf('.');
            builder.append(packageEnd >= 0 ? className.substring(packageEnd + 1) : className)
                    .append('.')
                    .append(element.getMethodName())
                    .append(':')
                    .append(element.getLineNumber());
            included++;
        }
        return builder.toString();
    }
}
