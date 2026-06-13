package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.lang.ref.WeakReference;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Keeps vanilla ride relationships coherent when the vehicle is retained in plot
 * coordinates but the passenger remains in world coordinates.
 */
public final class AssemblyVehicleRiding {
    private static final int DISMOUNT_SYNC_BLOCK_TICKS = 10;
    private static final Map<DismountKey, Long> RECENT_DISMOUNTS = new LinkedHashMap<>();
    private static final Map<PassengerKey, WeakReference<Entity>> CLIENT_AUTHORITATIVE_VEHICLES =
            new LinkedHashMap<>();

    private AssemblyVehicleRiding() {
    }

    public static boolean isRetainedAssemblyVehicle(Entity vehicle) {
        Objects.requireNonNull(vehicle, "vehicle");
        return !AssemblyEntityKicking.shouldKick(vehicle) && vehicleProjection(vehicle) != null;
    }

    @Nullable
    public static AssemblyPlotProjection vehicleProjection(Entity vehicle) {
        Objects.requireNonNull(vehicle, "vehicle");
        AssemblyPlotProjection retainedProjection = AssemblyEntityBridge
                .plotProjectionForRetainedEntity(vehicle.level(), vehicle)
                .orElse(null);
        if (retainedProjection != null) {
            return retainedProjection;
        }

        AssemblyId plotId = AssemblyEntityCollision.assemblyAtPlotPosition(vehicle.level(), vehicle.position());
        if (plotId != null) {
            return AssemblyContainers.container(vehicle.level())
                    .flatMap(container -> container.plotProjection(plotId))
                    .orElse(null);
        }

        if (vehicle instanceof AssemblyEntityCollisionAccess access) {
            AssemblyId trackingId = access.kinetic_assembly$trackingAssemblyId();
            if (trackingId != null) {
                return AssemblyContainers.container(vehicle.level())
                        .flatMap(container -> container.plotProjection(trackingId))
                        .orElse(null);
            }
        }
        return null;
    }

    public static boolean syncPassengerTracking(Entity passenger, AssemblyEntityCollisionAccess access) {
        Objects.requireNonNull(passenger, "passenger");
        Objects.requireNonNull(access, "access");
        Entity vehicle = passenger.getVehicle();
        return vehicle != null && syncPassengerTracking(passenger, access, vehicle);
    }

    public static boolean syncPassengerTracking(
            Entity passenger,
            AssemblyEntityCollisionAccess access,
            Entity vehicle
    ) {
        Objects.requireNonNull(passenger, "passenger");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(vehicle, "vehicle");
        if (recentlyDismounted(passenger, vehicle)) {
            return false;
        }

        AssemblyPlotProjection projection = vehicleProjection(vehicle);
        if (projection == null) {
            return false;
        }

        clearPassengerAssemblyPose(access);
        access.kinetic_assembly$setTrackingAssemblyId(projection.id());
        return true;
    }

    public static boolean beginPassengerRide(
            Entity passenger,
            AssemblyEntityCollisionAccess access,
            Entity vehicle
    ) {
        clearRecentDismount(passenger, vehicle);
        return syncPassengerTracking(passenger, access, vehicle);
    }

    public static boolean markPassengerDismounted(
            Entity passenger,
            AssemblyEntityCollisionAccess access,
            @Nullable Entity vehicle
    ) {
        Objects.requireNonNull(passenger, "passenger");
        Objects.requireNonNull(access, "access");
        boolean assemblyVehicle = vehicle != null && vehicleProjection(vehicle) != null;
        if (!assemblyVehicle && activeTrackingId(access) == null) {
            return false;
        }

        clearPassengerAssemblyPose(access);
        access.kinetic_assembly$setTrackingAssemblyId(null);
        if (vehicle != null) {
            rememberRecentDismount(passenger, vehicle);
        }
        return true;
    }

    public static void rememberAuthoritativeClientVehicle(Entity passenger, Entity vehicle) {
        Objects.requireNonNull(passenger, "passenger");
        Objects.requireNonNull(vehicle, "vehicle");
        if (!passenger.level().isClientSide()) {
            return;
        }

        CLIENT_AUTHORITATIVE_VEHICLES.put(passengerKey(passenger), new WeakReference<>(vehicle));
    }

    @Nullable
    public static Entity authoritativeClientVehicle(Entity passenger) {
        Objects.requireNonNull(passenger, "passenger");
        if (!passenger.level().isClientSide()) {
            return null;
        }

        PassengerKey key = passengerKey(passenger);
        WeakReference<Entity> reference = CLIENT_AUTHORITATIVE_VEHICLES.get(key);
        Entity vehicle = reference == null ? null : reference.get();
        if (vehicle == null
                || vehicle.isRemoved()
                || vehicle.level() != passenger.level()
                || vehicleProjection(vehicle) == null) {
            CLIENT_AUTHORITATIVE_VEHICLES.remove(key);
            return null;
        }
        return vehicle;
    }

    public static void clearAuthoritativeClientVehicle(Entity passenger, @Nullable Entity vehicle) {
        Objects.requireNonNull(passenger, "passenger");
        if (!passenger.level().isClientSide()) {
            return;
        }

        PassengerKey key = passengerKey(passenger);
        if (vehicle == null) {
            CLIENT_AUTHORITATIVE_VEHICLES.remove(key);
            return;
        }

        WeakReference<Entity> reference = CLIENT_AUTHORITATIVE_VEHICLES.get(key);
        if (reference == null || reference.get() == vehicle) {
            CLIENT_AUTHORITATIVE_VEHICLES.remove(key);
        }
    }

    public static boolean positionPassengerInWorld(Entity vehicle, Entity passenger) {
        Objects.requireNonNull(vehicle, "vehicle");
        Objects.requireNonNull(passenger, "passenger");
        if (!AssemblyEntityKicking.shouldKick(passenger)) {
            return false;
        }
        if (recentlyDismounted(passenger, vehicle)) {
            return false;
        }

        AssemblyPlotProjection projection = vehicleProjection(vehicle);
        if (projection == null) {
            return false;
        }

        Vec3 projected = AssemblyEntityPositioning.kickRidingEntity(passenger, projection);
        if (!AssemblyVectors.finite(projected)) {
            return false;
        }
        passenger.setPos(projected);
        if (passenger instanceof AssemblyEntityCollisionAccess access) {
            syncPassengerTracking(passenger, access, vehicle);
        }
        return true;
    }

    public static boolean positionPassengerInWorldIfPlotLocal(Entity vehicle, Entity passenger) {
        Objects.requireNonNull(vehicle, "vehicle");
        Objects.requireNonNull(passenger, "passenger");
        AssemblyPlotProjection vehicleProjection = vehicleProjection(vehicle);
        if (vehicleProjection == null) {
            return false;
        }

        AssemblyPlotProjection passengerProjection = AssemblyContainers.container(passenger.level())
                .flatMap(container -> container.plotProjection(BlockPos.containing(passenger.position())))
                .orElse(null);
        if (passengerProjection == null || !passengerProjection.id().equals(vehicleProjection.id())) {
            return false;
        }
        return positionPassengerInWorld(vehicle, passenger);
    }

    @Nullable
    private static AssemblyId activeTrackingId(AssemblyEntityCollisionAccess access) {
        AssemblyId trackingId = access.kinetic_assembly$trackingAssemblyId();
        if (trackingId != null) {
            return trackingId;
        }
        EntityAssemblyState state = access.kinetic_assembly$assemblyState();
        return state != null && state.active() ? state.id() : null;
    }

    private static boolean recentlyDismounted(Entity passenger, Entity vehicle) {
        long gameTime = passenger.level().getGameTime();
        cleanupRecentDismounts(gameTime);
        Long blockedUntil = RECENT_DISMOUNTS.get(dismountKey(passenger, vehicle));
        return blockedUntil != null && blockedUntil >= gameTime;
    }

    private static void rememberRecentDismount(Entity passenger, Entity vehicle) {
        long gameTime = passenger.level().getGameTime();
        cleanupRecentDismounts(gameTime);
        RECENT_DISMOUNTS.put(dismountKey(passenger, vehicle), gameTime + DISMOUNT_SYNC_BLOCK_TICKS);
    }

    private static void clearRecentDismount(Entity passenger, Entity vehicle) {
        cleanupRecentDismounts(passenger.level().getGameTime());
        RECENT_DISMOUNTS.remove(dismountKey(passenger, vehicle));
    }

    private static void cleanupRecentDismounts(long gameTime) {
        RECENT_DISMOUNTS.entrySet().removeIf(entry -> entry.getValue() < gameTime);
    }

    private static DismountKey dismountKey(Entity passenger, Entity vehicle) {
        return new DismountKey(
                passenger.level().dimension(),
                passenger.level().isClientSide(),
                passenger.getUUID(),
                vehicle.getUUID()
        );
    }

    private static PassengerKey passengerKey(Entity passenger) {
        return new PassengerKey(
                passenger.level().dimension(),
                passenger.level().isClientSide(),
                passenger.getUUID()
        );
    }

    private static void clearPassengerAssemblyPose(AssemblyEntityCollisionAccess access) {
        access.kinetic_assembly$setAssemblyState(null);
        access.kinetic_assembly$setPlotPosition(null);
        access.kinetic_assembly$setRecentAssemblyInheritedMotion(null);
        access.kinetic_assembly$setAssemblyInheritedVelocity(Vec3.ZERO);
    }

    private record DismountKey(
            ResourceKey<Level> dimension,
            boolean clientSide,
            UUID passenger,
            UUID vehicle
    ) {
    }

    private record PassengerKey(
            ResourceKey<Level> dimension,
            boolean clientSide,
            UUID passenger
    ) {
    }
}
