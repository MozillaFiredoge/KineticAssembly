package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.Objects;

import javax.annotation.Nullable;

import com.firedoge.kineticassembly.api.PhysicsVector;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class AssemblyEntityMotor {
    private static final double MEANINGFUL_MOTION_LENGTH_SQR = 2.5E-7D;
    private static final double SUPPORT_BREAK_UPWARD_EPSILON = 1.0E-5D;
    private static final double SUPPORT_ANCHOR_JITTER_SQR = 2.5E-7D;
    private static final int SUPPORT_REACQUIRE_COOLDOWN_TICKS = 4;

    private AssemblyEntityMotor() {
    }

    public static void tickTracking(Entity entity, AssemblyEntityCollisionAccess access) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(access, "access");
        long profileStarted = AssemblyProfiler.start();
        try {
            decaySupportBreakCooldown(access);
            if (AssemblyVehicleRiding.syncPassengerTracking(entity, access)) {
                return;
            }
            if (entity instanceof ServerPlayer) {
                return;
            }
            if (!shouldPersistAssemblySupport(entity)) {
                access.kinetic_assembly$setTrackingAssemblyId(null);
                return;
            }

            AssemblyId trackingId = activeTrackingAssemblyId(access);
            if (trackingId == null) {
                return;
            }

            if (!AssemblyEntityCollision.trackingTargetExists(entity.level(), trackingId)) {
                access.kinetic_assembly$setTrackingAssemblyId(null);
                access.kinetic_assembly$setRecentAssemblyInheritedMotion(null);
                return;
            }

            if (entity instanceof Player player && player.isLocalPlayer()) {
                carryTrackedPose(entity, access, trackingId);
            }

            if (!AssemblyEntityCollision.hasNearbyTrackingTarget(entity, trackingId)) {
                clearTrackingMotion(access);
                return;
            }
        } finally {
            AssemblyProfiler.record("entity.motor.tickTracking", profileStarted);
        }
    }

    public static void tickPlotPosition(Entity entity, AssemblyEntityCollisionAccess access) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(access, "access");
        long profileStarted = AssemblyProfiler.start();
        try {
            if (destroyWhenLeavingPlot(entity)) {
                return;
            }

            Vec3 plotPosition = access.kinetic_assembly$plotPosition();
            if (plotPosition != null) {
                AssemblyPlotProjection projection = AssemblyContainers.container(entity.level())
                        .flatMap(container -> container.plotProjection(BlockPos.containing(plotPosition)))
                        .orElse(null);
                if (projection != null) {
                    Vec3 worldPosition = projection.plotToWorld(plotPosition);
                    if (isFinite(worldPosition)) {
                        entity.setPos(worldPosition);
                        access.kinetic_assembly$setTrackingAssemblyId(projection.id());
                        return;
                    }
                }
                access.kinetic_assembly$setPlotPosition(null);
            } else if (entity.level().isClientSide
                    && !(entity instanceof Player player && player.isLocalPlayer())
                    && !(entity instanceof ItemEntity)) {
                access.kinetic_assembly$setTrackingAssemblyId(null);
            }
            syncTrackingContext(entity, access);
        } finally {
            AssemblyProfiler.record("entity.motor.tickPlotPosition", profileStarted);
        }
    }

    public static void carryServerPlayer(ServerPlayer player, AssemblyEntityCollisionAccess access) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(access, "access");
        long profileStarted = AssemblyProfiler.start();
        try {
            decaySupportBreakCooldown(access);
            if (AssemblyVehicleRiding.syncPassengerTracking(player, access)) {
                return;
            }
            EntityAssemblyState state = access.kinetic_assembly$assemblyState();
            AssemblyId trackingId = access.kinetic_assembly$trackingAssemblyId();
            if (trackingId == null && state != null && state.active()) {
                trackingId = state.id();
            }
            if (trackingId == null || supportReacquireBlocked(access, trackingId)) {
                return;
            }
            AssemblyEntityCollision.CarryResult carry = carryResult(player, state, trackingId);
            access.kinetic_assembly$setAssemblyState(carry.state());
            if (carry.state() == null) {
                AssemblyVerticalTrace.serverCarry(player, trackingId, carry, false);
                clearTrackingMotion(access);
                return;
            }

            boolean applied = false;
            if (carry.motion().lengthSqr() > 1.0E-12D) {
                player.setPos(player.position().add(carry.motion()));
                applied = true;
            }
            AssemblyVerticalTrace.serverCarry(player, trackingId, carry, applied);
            access.kinetic_assembly$setRecentAssemblyInheritedMotion(null);
            access.kinetic_assembly$syncAssemblyOldPosition();
        } finally {
            AssemblyProfiler.record("entity.motor.carryServerPlayer", profileStarted);
        }
    }

    private static void carryTrackedPose(Entity entity, AssemblyEntityCollisionAccess access, AssemblyId trackingId) {
        if (supportReacquireBlocked(access, trackingId)) {
            return;
        }
        EntityAssemblyState state = access.kinetic_assembly$assemblyState();
        AssemblyEntityCollision.CarryResult carry = carryResult(entity, state, trackingId);
        access.kinetic_assembly$setAssemblyState(carry.state());
        if (carry.state() == null) {
            clearTrackingMotion(access);
            return;
        }
        if (carry.motion().lengthSqr() > 1.0E-12D) {
            entity.setPos(entity.position().add(carry.motion()));
        }
        access.kinetic_assembly$setRecentAssemblyInheritedMotion(null);
        access.kinetic_assembly$syncAssemblyOldPosition();
    }

    private static AssemblyEntityCollision.CarryResult carryResult(
            Entity entity,
            @Nullable EntityAssemblyState state,
            AssemblyId trackingId
    ) {
        return state != null
                && state.active()
                && trackingId.equals(state.id())
                ? AssemblyEntityCollision.trackedCenterMotion(entity, state)
                : AssemblyEntityCollision.inheritedCenterMotion(entity, trackingId);
    }

    public static void recreateFromPacket(Entity entity, ClientboundAddEntityPacket packet, AssemblyEntityCollisionAccess access) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(access, "access");
        Vec3 packetPosition = new Vec3(packet.getX(), packet.getY(), packet.getZ());
        AssemblyPlotProjection projection = AssemblyEntityCollision.spawnPacketProjection(entity.level(), packetPosition);
        if (projection == null) {
            return;
        }
        if (!AssemblyEntityKicking.shouldKick(entity)) {
            return;
        }

        Vec3 worldPosition = projection.plotToWorld(packetPosition);
        if (!isFinite(worldPosition)) {
            return;
        }
        entity.moveTo(worldPosition.x, worldPosition.y, worldPosition.z);
        access.kinetic_assembly$setTrackingAssemblyId(projection.id());
    }

    public static Vec3 collide(
            Entity entity,
            AssemblyEntityCollisionAccess access,
            Vec3 requestedMotion,
            VanillaCollision vanillaCollision
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(requestedMotion, "requestedMotion");
        Objects.requireNonNull(vanillaCollision, "vanillaCollision");

        long profileStarted = AssemblyProfiler.start();
        try {
            boolean persistSupport = shouldPersistAssemblySupport(entity);
            AssemblyId previousTrackingId = activeTrackingAssemblyId(access);
            boolean breakingSupport = previousTrackingId != null && isSupportBreakMotion(entity, requestedMotion);
            if (breakingSupport) {
                startSupportBreakCooldown(access, previousTrackingId);
                AssemblyVerticalTrace.supportCooldown(
                        entity,
                        "break",
                        previousTrackingId,
                        access.kinetic_assembly$supportBreakCooldownTicks(),
                        requestedMotion,
                        access.kinetic_assembly$assemblyState()
                );
                access.kinetic_assembly$setAssemblyState(null);
                access.kinetic_assembly$setTrackingAssemblyId(null);
                access.kinetic_assembly$setPlotPosition(null);
            }

            EntityAssemblyState previousState = !breakingSupport && persistSupport ? access.kinetic_assembly$assemblyState() : null;
            if (previousState != null && supportReacquireBlocked(access, previousState.id())) {
                previousState = null;
            }
            AssemblyId accessTrackingId = access.kinetic_assembly$trackingAssemblyId();
            if (!breakingSupport && supportReacquireBlocked(access, accessTrackingId)) {
                access.kinetic_assembly$setTrackingAssemblyId(null);
            }
            access.kinetic_assembly$setPreAssemblyCollisionDeltaMovement(entity.getDeltaMovement());
            AssemblyVerticalTrace.moveStart(
                    entity,
                    access.kinetic_assembly$currentMoveType(),
                    requestedMotion,
                    access.kinetic_assembly$assemblyInheritedVelocity(),
                    previousState,
                    previousTrackingId
            );
            long sectionStarted = AssemblyProfiler.start();
            AssemblyEntityCollision.CollisionResult assemblyCollision;
            try {
                assemblyCollision = AssemblyEntityCollision.collideAssemblies(
                        entity,
                        requestedMotion,
                        access.kinetic_assembly$assemblyInheritedVelocity(),
                        previousState
                );
            } finally {
                AssemblyProfiler.record("entity.motor.assembly", sectionStarted);
            }
            AssemblyVerticalTrace.assemblyResult(entity, assemblyCollision);

            sectionStarted = AssemblyProfiler.start();
            Vec3 vanillaMotion;
            try {
                vanillaMotion = vanillaCollision.collide(assemblyCollision.motion());
            } finally {
                AssemblyProfiler.record("entity.motor.vanilla", sectionStarted);
            }

            sectionStarted = AssemblyProfiler.start();
            AssemblyEntityCollision.CollisionResult finalCollision;
            try {
                finalCollision = AssemblyEntityCollision.finishVanillaCollision(
                        entity,
                        requestedMotion,
                        vanillaMotion,
                        previousState,
                        assemblyCollision
                );
            } finally {
                AssemblyProfiler.record("entity.motor.finish", sectionStarted);
            }
            if (finalCollision.state() != null && supportReacquireBlocked(access, finalCollision.state().id())) {
                AssemblyVerticalTrace.supportCooldown(
                        entity,
                        "strip",
                        finalCollision.state().id(),
                        access.kinetic_assembly$supportBreakCooldownTicks(),
                        requestedMotion,
                        finalCollision.state()
                );
                finalCollision = stripSupport(finalCollision);
            }
            finalCollision = applySupportHysteresis(
                    entity,
                    requestedMotion,
                    previousState,
                    previousTrackingId,
                    finalCollision
            );
            finalCollision = stabilizeSupportAnchor(entity, requestedMotion, previousState, finalCollision);
            access.kinetic_assembly$setLastAssemblyCollision(finalCollision);
            AssemblyVerticalTrace.vanillaResult(entity, vanillaMotion, finalCollision);
            access.kinetic_assembly$setRecentAssemblyCollision(finalCollision);
            access.kinetic_assembly$setRecentAssemblyInheritedMotion(finalCollision.inheritedMotion());
            EntityAssemblyState newState = persistSupport ? finalCollision.state() : null;
            access.kinetic_assembly$setAssemblyState(newState);
            if (persistSupport
                    && previousTrackingId != null
                    && !AssemblyEntityCollision.hasNearbyTrackingTarget(entity, previousTrackingId)) {
                clearTrackingMotion(access);
            }
            return finalCollision.motion();
        } finally {
            AssemblyProfiler.record("entity.motor.total", profileStarted);
        }
    }

    @Nullable
    public static AssemblyId activeTrackingAssemblyId(AssemblyEntityCollisionAccess access) {
        Objects.requireNonNull(access, "access");
        AssemblyId trackingId = access.kinetic_assembly$trackingAssemblyId();
        if (trackingId != null) {
            return trackingId;
        }
        EntityAssemblyState state = access.kinetic_assembly$assemblyState();
        return state != null && state.active() ? state.id() : null;
    }

    public static boolean isFinite(Vec3 position) {
        return position != null
                && Double.isFinite(position.x)
                && Double.isFinite(position.y)
                && Double.isFinite(position.z);
    }

    public static boolean hasMeaningfulMotion(@Nullable Vec3 motion) {
        return motion != null
                && motion.lengthSqr() > MEANINGFUL_MOTION_LENGTH_SQR
                && isFinite(motion);
    }

    private static boolean shouldPersistAssemblySupport(Entity entity) {
        return true;
    }

    private static boolean destroyWhenLeavingPlot(Entity entity) {
        if (entity.level().isClientSide || !entity.getType().is(AssemblyEntityTags.DESTROY_WHEN_LEAVING_PLOT)) {
            return false;
        }

        AssemblyPlotProjection projection = AssemblyEntityBridge
                .plotProjectionAtOrAdjacent(entity.level(), BlockPos.containing(entity.position()))
                .orElse(null);
        if (projection == null) {
            return false;
        }

        if (entity.getBoundingBox().intersects(plotBounds(projection.plot()).inflate(1.0D))) {
            return false;
        }

        entity.kill();
        return true;
    }

    private static AABB plotBounds(AssemblyPlot plot) {
        return new AABB(
                plot.minPlotX(),
                plot.minPlotY(),
                plot.minPlotZ(),
                plot.maxPlotX() + 1.0D,
                plot.maxPlotY() + 1.0D,
                plot.maxPlotZ() + 1.0D
        );
    }

    private static boolean isSupportBreakMotion(Entity entity, Vec3 requestedMotion) {
        if (requestedMotion.y > SUPPORT_BREAK_UPWARD_EPSILON) {
            return true;
        }
        Vec3 delta = entity.getDeltaMovement();
        return isFinite(delta) && delta.y > SUPPORT_BREAK_UPWARD_EPSILON;
    }

    private static void startSupportBreakCooldown(AssemblyEntityCollisionAccess access, AssemblyId id) {
        access.kinetic_assembly$setSupportBreakCooldown(id, SUPPORT_REACQUIRE_COOLDOWN_TICKS);
    }

    private static boolean supportReacquireBlocked(AssemblyEntityCollisionAccess access, @Nullable AssemblyId id) {
        return id != null
                && access.kinetic_assembly$supportBreakCooldownTicks() > 0
                && id.equals(access.kinetic_assembly$supportBreakAssemblyId());
    }

    private static void decaySupportBreakCooldown(AssemblyEntityCollisionAccess access) {
        int ticks = access.kinetic_assembly$supportBreakCooldownTicks();
        if (ticks <= 0) {
            return;
        }
        AssemblyId id = access.kinetic_assembly$supportBreakAssemblyId();
        access.kinetic_assembly$setSupportBreakCooldown(id, ticks - 1);
    }

    private static AssemblyEntityCollision.CollisionResult stripSupport(AssemblyEntityCollision.CollisionResult collision) {
        return new AssemblyEntityCollision.CollisionResult(
                null,
                false,
                collision.horizontalCollision(),
                collision.motion(),
                collision.inheritedMotion(),
                collision.firstCollisions()
        );
    }

    private static AssemblyEntityCollision.CollisionResult applySupportHysteresis(
            Entity entity,
            Vec3 requestedMotion,
            @Nullable EntityAssemblyState previousState,
            @Nullable AssemblyId previousTrackingId,
            AssemblyEntityCollision.CollisionResult collision
    ) {
        EntityAssemblyState nextState = collision.state();
        if (previousState == null
                || !previousState.active()
                || previousTrackingId == null
                || nextState == null
                || !nextState.active()
                || previousTrackingId.equals(nextState.id())
                || requestedMotion.y > SUPPORT_BREAK_UPWARD_EPSILON) {
            return collision;
        }
        if (!previousTrackingId.equals(previousState.id())) {
            return collision;
        }
        if (!AssemblyEntityCollision.hasNearbyTrackingTarget(entity, previousTrackingId)) {
            return collision;
        }

        AssemblyVerticalTrace.supportSwitch(
                entity,
                "keep-previous",
                previousTrackingId,
                nextState.id(),
                requestedMotion,
                previousState,
                nextState
        );
        return replaceSupport(collision, previousState);
    }

    private static AssemblyEntityCollision.CollisionResult replaceSupport(
            AssemblyEntityCollision.CollisionResult collision,
            EntityAssemblyState state
    ) {
        return new AssemblyEntityCollision.CollisionResult(
                state,
                true,
                collision.horizontalCollision(),
                collision.motion(),
                collision.inheritedMotion(),
                collision.firstCollisions()
        );
    }

    private static AssemblyEntityCollision.CollisionResult stabilizeSupportAnchor(
            Entity entity,
            Vec3 requestedMotion,
            @Nullable EntityAssemblyState previousState,
            AssemblyEntityCollision.CollisionResult collision
    ) {
        EntityAssemblyState nextState = collision.state();
        if (previousState == null
                || !previousState.active()
                || nextState == null
                || !nextState.active()
                || !previousState.id().equals(nextState.id())
                || previousState.trackingMode() != EntityAssemblyState.TrackingMode.SUPPORTED
                || nextState.trackingMode() != EntityAssemblyState.TrackingMode.SUPPORTED) {
            return collision;
        }
        if (distanceSqr(previousState.localAnchor(), nextState.localAnchor()) > SUPPORT_ANCHOR_JITTER_SQR) {
            return collision;
        }

        AssemblyVerticalTrace.supportAnchor(
                entity,
                nextState.id(),
                requestedMotion,
                previousState.localAnchor(),
                nextState.localAnchor()
        );
        EntityAssemblyState stableState = new EntityAssemblyState(
                nextState.id(),
                nextState.epoch(),
                previousState.localPosition(),
                nextState.localVelocity(),
                previousState.localAnchor(),
                nextState.trackingMode(),
                nextState.graceTicks()
        );
        return replaceSupport(collision, stableState);
    }

    private static double distanceSqr(PhysicsVector first, PhysicsVector second) {
        double dx = first.x() - second.x();
        double dy = first.y() - second.y();
        double dz = first.z() - second.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static void syncTrackingContext(Entity entity, AssemblyEntityCollisionAccess access) {
        if (AssemblyEntityCollision.assemblyAtPlotPosition(entity.level(), entity.position()) != null) {
            access.kinetic_assembly$setTrackingAssemblyId(null);
            return;
        }

        if (AssemblyVehicleRiding.syncPassengerTracking(entity, access)) {
            return;
        }

        AssemblyId trackingId = access.kinetic_assembly$trackingAssemblyId();
        if (trackingId != null && !AssemblyEntityCollision.trackingTargetExists(entity.level(), trackingId)) {
            access.kinetic_assembly$setTrackingAssemblyId(null);
            return;
        }

        trackingId = access.kinetic_assembly$trackingAssemblyId();
        if (trackingId != null && !AssemblyEntityCollision.hasNearbyTrackingTarget(entity, trackingId)) {
            clearTrackingMotion(access);
        }
    }

    private static void clearTrackingMotion(AssemblyEntityCollisionAccess access) {
        access.kinetic_assembly$setTrackingAssemblyId(null);
        access.kinetic_assembly$setPlotPosition(null);
        access.kinetic_assembly$setRecentAssemblyInheritedMotion(null);
        access.kinetic_assembly$setAssemblyInheritedVelocity(Vec3.ZERO);
    }

    @FunctionalInterface
    public interface VanillaCollision {
        Vec3 collide(Vec3 movement);
    }
}
