package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.platform.PlatformServices;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class AssemblyVerticalTrace {
    private static final int MAX_TRACE_TICKS = 1200;
    private static final Map<UUID, TraceSession> SESSIONS = new ConcurrentHashMap<>();

    private AssemblyVerticalTrace() {
    }

    public static int enable(Entity entity, int ticks) {
        Objects.requireNonNull(entity, "entity");
        int clampedTicks = Mth.clamp(ticks, 1, MAX_TRACE_TICKS);
        SESSIONS.put(entity.getUUID(), new TraceSession(entity.tickCount + clampedTicks));
        KineticAssembly.LOGGER.info(
                "[assembly-vertical] enabled player={} uuid={} side={} tick={} ticks={} endTick={}",
                entity.getName().getString(),
                shortUuid(entity.getUUID()),
                side(entity),
                entity.tickCount,
                clampedTicks,
                entity.tickCount + clampedTicks
        );
        return clampedTicks;
    }

    public static void moveStart(
            Entity entity,
            MoverType type,
            Vec3 requestedMotion,
            Vec3 inheritedVelocity,
            EntityAssemblyState previousState,
            AssemblyId previousTrackingId
    ) {
        if (!shouldTrace(entity)) {
            return;
        }
        KineticAssembly.LOGGER.info(
                "[assembly-vertical] move-start side={} tick={} entity={} type={} pos={} delta={} onGround={} verticalCollision={} verticalBelow={} requested={} inheritedVelocity={} prevState={} prevTracking={}",
                side(entity),
                entity.tickCount,
                entityName(entity),
                type,
                vec(entity.position()),
                vec(entity.getDeltaMovement()),
                entity.onGround(),
                entity.verticalCollision,
                entity.verticalCollisionBelow,
                vec(requestedMotion),
                vec(inheritedVelocity),
                state(previousState),
                id(previousTrackingId)
        );
    }

    public static void assemblyResult(Entity entity, AssemblyEntityCollision.CollisionResult result) {
        if (!shouldTrace(entity)) {
            return;
        }
        KineticAssembly.LOGGER.info(
                "[assembly-vertical] assembly-result side={} tick={} entity={} state={} onGround={} horizontal={} motion={} inheritedMotion={} collisions={}",
                side(entity),
                entity.tickCount,
                entityName(entity),
                state(result.state()),
                result.onGround(),
                result.horizontalCollision(),
                vec(result.motion()),
                vec(result.inheritedMotion()),
                result.firstCollisions().size()
        );
    }

    public static void solverQuery(
            Entity entity,
            Vec3 requestedMotion,
            AABB queryBounds,
            int targetCount,
            int substeps,
            AssemblyId forcedId,
            AssemblyId trackedId,
            AssemblyId carryId,
            EntityAssemblyState sustainedState
    ) {
        if (!shouldTrace(entity)) {
            return;
        }
        KineticAssembly.LOGGER.info(
                "[assembly-vertical] solver-query side={} tick={} entity={} requested={} query={} targets={} substeps={} forced={} trackedTarget={} carryTarget={} sustained={}",
                side(entity),
                entity.tickCount,
                entityName(entity),
                vec(requestedMotion),
                box(queryBounds),
                targetCount,
                substeps,
                id(forcedId),
                id(trackedId),
                id(carryId),
                state(sustainedState)
        );
    }

    public static void solverEarlyExit(
            Entity entity,
            String reason,
            Vec3 requestedMotion,
            EntityAssemblyState previousState
    ) {
        if (!shouldTrace(entity)) {
            return;
        }
        KineticAssembly.LOGGER.info(
                "[assembly-vertical] solver-exit side={} tick={} entity={} reason={} requested={} prevState={}",
                side(entity),
                entity.tickCount,
                entityName(entity),
                reason,
                vec(requestedMotion),
                state(previousState)
        );
    }

    public static void solverPenetration(
            Entity entity,
            int stepIndex,
            int substeps,
            int iteration,
            double alpha,
            AssemblyId targetId,
            BlockPos blockPos,
            Vec3 requestedStep,
            Vec3 resolvedBefore,
            Vec3 worldMtv,
            Vec3 normal,
            double upDot,
            boolean vertical,
            String resolution
    ) {
        if (!shouldTrace(entity)) {
            return;
        }
        KineticAssembly.LOGGER.info(
                "[assembly-vertical] solver-penetration side={} tick={} entity={} step={}/{} iteration={} alpha={} target={} block={} requestedStep={} resolvedBefore={} mtv={} normal={} upDot={} vertical={} resolution={}",
                side(entity),
                entity.tickCount,
                entityName(entity),
                stepIndex + 1,
                substeps,
                iteration,
                fixed(alpha, 3),
                id(targetId),
                blockPos == null ? "none" : blockPos.toShortString(),
                vec(requestedStep),
                vec(resolvedBefore),
                vec(worldMtv),
                vec(normal),
                fixed(upDot, 5),
                vertical,
                resolution
        );
    }

    public static void solverStepResult(
            Entity entity,
            int stepIndex,
            int substeps,
            double alpha,
            Vec3 requestedStep,
            Vec3 resolvedStep,
            EntityAssemblyState state,
            boolean horizontalCollision,
            AssemblyEntityCollision.FirstCollisionInfo first
    ) {
        if (!shouldTrace(entity)) {
            return;
        }
        KineticAssembly.LOGGER.info(
                "[assembly-vertical] solver-step side={} tick={} entity={} step={}/{} alpha={} requestedStep={} resolvedStep={} state={} horizontal={} firstTarget={} firstBlock={} firstDir={}",
                side(entity),
                entity.tickCount,
                entityName(entity),
                stepIndex + 1,
                substeps,
                fixed(alpha, 3),
                vec(requestedStep),
                vec(resolvedStep),
                state(state),
                horizontalCollision,
                first == null ? "none" : id(first.id()),
                first == null ? "none" : first.block().localPos().toShortString(),
                first == null ? "none" : vec(first.worldDirection())
        );
    }

    public static void vanillaResult(
            Entity entity,
            Vec3 vanillaMotion,
            AssemblyEntityCollision.CollisionResult mergedResult
    ) {
        if (!shouldTrace(entity)) {
            return;
        }
        KineticAssembly.LOGGER.info(
                "[assembly-vertical] vanilla-result side={} tick={} entity={} vanillaMotion={} mergedState={} mergedOnGround={} mergedHorizontal={} mergedMotion={} mergedInherited={}",
                side(entity),
                entity.tickCount,
                entityName(entity),
                vec(vanillaMotion),
                state(mergedResult.state()),
                mergedResult.onGround(),
                mergedResult.horizontalCollision(),
                vec(mergedResult.motion()),
                vec(mergedResult.inheritedMotion())
        );
    }

    public static void groundWrite(
            Entity entity,
            boolean vanillaOnGround,
            boolean passedOnGround,
            boolean forced,
            Vec3 movement,
            boolean verticalCollision,
            boolean verticalCollisionBelow
    ) {
        if (!shouldTrace(entity)) {
            return;
        }
        KineticAssembly.LOGGER.info(
                "[assembly-vertical] ground-write side={} tick={} entity={} vanillaArg={} passedArg={} forced={} movement={} finalOnGround={} verticalCollision={} verticalBelow={} delta={}",
                side(entity),
                entity.tickCount,
                entityName(entity),
                vanillaOnGround,
                passedOnGround,
                forced,
                vec(movement),
                entity.onGround(),
                verticalCollision,
                verticalCollisionBelow,
                vec(entity.getDeltaMovement())
        );
    }

    public static void moveEnd(
            Entity entity,
            MoverType type,
            Vec3 requestedMotion,
            AssemblyEntityCollision.CollisionResult lastResult,
            boolean horizontalCollision,
            boolean verticalCollision,
            boolean verticalCollisionBelow
    ) {
        if (!shouldTrace(entity)) {
            return;
        }
        KineticAssembly.LOGGER.info(
                "[assembly-vertical] move-end side={} tick={} entity={} type={} requested={} pos={} delta={} onGround={} horizontalCollision={} verticalCollision={} verticalBelow={} resultState={} resultOnGround={} resultInherited={}",
                side(entity),
                entity.tickCount,
                entityName(entity),
                type,
                vec(requestedMotion),
                vec(entity.position()),
                vec(entity.getDeltaMovement()),
                entity.onGround(),
                horizontalCollision,
                verticalCollision,
                verticalCollisionBelow,
                lastResult == null ? "none" : state(lastResult.state()),
                lastResult != null && lastResult.onGround(),
                lastResult == null ? "none" : vec(lastResult.inheritedMotion())
        );
    }

    public static void serverCarry(
            Entity entity,
            AssemblyId trackingId,
            AssemblyEntityCollision.CarryResult carry,
            boolean applied
    ) {
        if (!shouldTrace(entity)) {
            return;
        }
        KineticAssembly.LOGGER.info(
                "[assembly-vertical] server-carry side={} tick={} entity={} tracking={} applied={} motion={} state={} pos={} delta={} onGround={}",
                side(entity),
                entity.tickCount,
                entityName(entity),
                id(trackingId),
                applied,
                vec(carry.motion()),
                state(carry.state()),
                vec(entity.position()),
                vec(entity.getDeltaMovement()),
                entity.onGround()
        );
    }

    public static void carryJitter(Entity entity, String source, Vec3 rawMotion, Vec3 snappedMotion) {
        if (!shouldTrace(entity)) {
            return;
        }
        KineticAssembly.LOGGER.info(
                "[assembly-vertical] carry-jitter side={} tick={} entity={} source={} raw={} snapped={} pos={} delta={} onGround={}",
                side(entity),
                entity.tickCount,
                entityName(entity),
                source,
                vec(rawMotion),
                vec(snappedMotion),
                vec(entity.position()),
                vec(entity.getDeltaMovement()),
                entity.onGround()
        );
    }

    public static void supportCooldown(
            Entity entity,
            String action,
            AssemblyId id,
            int ticks,
            Vec3 requestedMotion,
            EntityAssemblyState state
    ) {
        if (!shouldTrace(entity)) {
            return;
        }
        KineticAssembly.LOGGER.info(
                "[assembly-vertical] support-cooldown side={} tick={} entity={} action={} target={} ticks={} requested={} state={} pos={} delta={} onGround={}",
                side(entity),
                entity.tickCount,
                entityName(entity),
                action,
                id(id),
                ticks,
                vec(requestedMotion),
                state(state),
                vec(entity.position()),
                vec(entity.getDeltaMovement()),
                entity.onGround()
        );
    }

    public static void supportSwitch(
            Entity entity,
            String action,
            AssemblyId previousId,
            AssemblyId nextId,
            Vec3 requestedMotion,
            EntityAssemblyState keptState,
            EntityAssemblyState rejectedState
    ) {
        if (!shouldTrace(entity)) {
            return;
        }
        KineticAssembly.LOGGER.info(
                "[assembly-vertical] support-switch side={} tick={} entity={} action={} previous={} next={} requested={} kept={} rejected={} pos={} delta={} onGround={}",
                side(entity),
                entity.tickCount,
                entityName(entity),
                action,
                id(previousId),
                id(nextId),
                vec(requestedMotion),
                state(keptState),
                state(rejectedState),
                vec(entity.position()),
                vec(entity.getDeltaMovement()),
                entity.onGround()
        );
    }

    public static void supportAnchor(
            Entity entity,
            AssemblyId id,
            Vec3 requestedMotion,
            com.firedoge.kineticassembly.api.PhysicsVector previousAnchor,
            com.firedoge.kineticassembly.api.PhysicsVector candidateAnchor
    ) {
        if (!shouldTrace(entity)) {
            return;
        }
        KineticAssembly.LOGGER.info(
                "[assembly-vertical] support-anchor side={} tick={} entity={} target={} requested={} previous={} candidate={} pos={} delta={} onGround={}",
                side(entity),
                entity.tickCount,
                entityName(entity),
                id(id),
                vec(requestedMotion),
                vector(previousAnchor),
                vector(candidateAnchor),
                vec(entity.position()),
                vec(entity.getDeltaMovement()),
                entity.onGround()
        );
    }

    public static void clientPacket(
            Entity entity,
            String action,
            EntityAssemblyState state,
            AssemblyId trackingId,
            Vec3 worldPosition,
            Vec3 plotPosition,
            String detail
    ) {
        if (!shouldTrace(entity)) {
            return;
        }
        KineticAssembly.LOGGER.info(
                "[assembly-vertical] client-packet side={} tick={} entity={} action={} state={} tracking={} world={} plot={} detail={}",
                side(entity),
                entity.tickCount,
                entityName(entity),
                action,
                state(state),
                id(trackingId),
                vec(worldPosition),
                vec(plotPosition),
                detail
        );
    }

    public static void serverPacket(
            Entity entity,
            String action,
            Vec3 packetPosition,
            Vec3 worldPosition,
            EntityAssemblyState state,
            AssemblyId trackingId,
            Vec3 plotPosition,
            String detail
    ) {
        if (!shouldTrace(entity)) {
            return;
        }
        KineticAssembly.LOGGER.info(
                "[assembly-vertical] server-packet side={} tick={} entity={} action={} packet={} world={} state={} tracking={} plot={} detail={}",
                side(entity),
                entity.tickCount,
                entityName(entity),
                action,
                vec(packetPosition),
                vec(worldPosition),
                state(state),
                id(trackingId),
                vec(plotPosition),
                detail
        );
    }

    public static void serverMoveCheck(
            Entity entity,
            boolean skipped,
            EntityAssemblyState state,
            AssemblyId trackingId,
            Vec3 plotPosition
    ) {
        if (!shouldTrace(entity)) {
            return;
        }
        KineticAssembly.LOGGER.info(
                "[assembly-vertical] server-move-check side={} tick={} entity={} skipMovedWrongly={} state={} tracking={} plot={} pos={} delta={} onGround={}",
                side(entity),
                entity.tickCount,
                entityName(entity),
                skipped,
                state(state),
                id(trackingId),
                vec(plotPosition),
                vec(entity.position()),
                vec(entity.getDeltaMovement()),
                entity.onGround()
        );
    }

    public static boolean shouldTrace(Entity entity) {
        if (PlatformServices.services().config().debugLogging()) {
            return true;
        }
        TraceSession session = SESSIONS.get(entity.getUUID());
        if (session == null) {
            return false;
        }
        if (entity.tickCount <= session.endTick()) {
            return true;
        }
        SESSIONS.remove(entity.getUUID(), session);
        return false;
    }

    private static String entityName(Entity entity) {
        return entity.getName().getString() + "/" + shortUuid(entity.getUUID());
    }

    private static String side(Entity entity) {
        return entity.level().isClientSide ? "client" : "server";
    }

    private static String state(EntityAssemblyState state) {
        if (state == null || !state.active()) {
            return "none";
        }
        return id(state.id())
                + "/" + state.trackingMode()
                + "/epoch=" + state.epoch()
                + "/grace=" + state.graceTicks()
                + "/anchor=" + vector(state.localAnchor());
    }

    private static String id(AssemblyId id) {
        return id == null ? "none" : shortUuid(id.value());
    }

    private static String vec(Vec3 vector) {
        if (vector == null) {
            return "none";
        }
        return String.format("(%.8f,%.8f,%.8f)", vector.x, vector.y, vector.z);
    }

    private static String fixed(double value, int decimals) {
        return String.format("%." + decimals + "f", value);
    }

    private static String box(AABB box) {
        if (box == null) {
            return "none";
        }
        return String.format(
                "[(%.8f,%.8f,%.8f)->(%.8f,%.8f,%.8f)]",
                box.minX,
                box.minY,
                box.minZ,
                box.maxX,
                box.maxY,
                box.maxZ
        );
    }

    private static String vector(com.firedoge.kineticassembly.api.PhysicsVector vector) {
        if (vector == null) {
            return "none";
        }
        return String.format("(%.8f,%.8f,%.8f)", vector.x(), vector.y(), vector.z());
    }

    private static String shortUuid(UUID uuid) {
        return uuid.toString().substring(0, 8);
    }

    private record TraceSession(int endTick) {
    }
}
