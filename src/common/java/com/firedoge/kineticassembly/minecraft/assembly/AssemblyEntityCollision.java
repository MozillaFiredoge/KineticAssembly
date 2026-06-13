package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.annotation.Nullable;

import com.firedoge.kineticassembly.api.PhysicsVector;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class AssemblyEntityCollision {
    private static final double EPSILON = 1.0E-7D;
    private static final double MIN_MTV_LENGTH_SQR = 1.0E-8D;
    private static final double SUPPORT_PROBE_DOWN = 0.16D;
    private static final double SUPPORT_PROBE_UP = 0.08D;
    private static final double SUPPORT_QUERY_INFLATE = 0.35D;
    private static final double HORIZONTAL_INSET = 0.02D;
    private static final double CARRY_JITTER_COMPONENT = 5.0E-4D;
    private static final double CARRY_JITTER_LENGTH_SQR = CARRY_JITTER_COMPONENT * CARRY_JITTER_COMPONENT;
    private static final double MAX_CARRY_DISTANCE_SQR = 16.0D;
    private static final int SUPPORT_GRACE_TICKS = 2;
    private static final int MAX_LOCAL_COLLISION_SUBSTEPS = 10;
    private static final int LOCAL_COLLISION_ITERATIONS = 4;
    private static final double LOCAL_COLLISION_SUBSTEP_DISTANCE = 0.25D / 16.0D;
    private static final double LOCAL_STEP_INCREMENT = 1.0D / 16.0D;
    private static final double SCAFFOLDING_SKEW = 0.05D;
    private static final VoxelShape SCAFFOLDING_TOP_SHAPE = Shapes.create(
            new AABB(0.0D, 15.0D / 16.0D, 0.0D, 1.0D, 1.0D, 1.0D)
    );
    private static final Vec3 WORLD_RIGHT = new Vec3(1.0D, 0.0D, 0.0D);
    private static final Vec3 WORLD_UP = new Vec3(0.0D, 1.0D, 0.0D);
    private static final Vec3 WORLD_FORWARD = new Vec3(0.0D, 0.0D, 1.0D);

    private AssemblyEntityCollision() {
    }

    public static boolean hasAssemblyCollision(Entity entity, AABB worldBox) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(worldBox, "worldBox");
        long profileStarted = AssemblyProfiler.start();
        try {
            AssemblyContainer container = AssemblyContainers.container(entity.level()).orElse(null);
            if (container == null || container.isEmpty()) {
                return false;
            }

            List<AssemblyCollisionTarget> targets = container.collisionTargets(worldBox.inflate(1.05D), null);
            if (targets.isEmpty()) {
                return false;
            }

            return deepestLocalPenetration(entity, shrinkHorizontal(worldBox, 0.05D), targets, 0.0D, true) != null;
        } finally {
            AssemblyProfiler.record("entity.query.hasAssemblyCollision", profileStarted);
        }
    }

    public static boolean hasAssemblyFallSupport(
            Entity entity,
            AssemblyEntityCollisionAccess access,
            AABB fallProbe
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(fallProbe, "fallProbe");
        long profileStarted = AssemblyProfiler.start();
        try {
            AssemblyContainer container = AssemblyContainers.container(entity.level()).orElse(null);
            if (container == null || container.isEmpty()) {
                return false;
            }

            List<AssemblyCollisionTarget> targets = container.collisionTargets(fallProbe.inflate(1.05D), null);
            if (targets.isEmpty()) {
                return false;
            }

            List<AssemblyCollisionTarget> scopedTargets = scopedFallSupportTargets(
                    entity.level(),
                    access,
                    targets
            );
            return deepestLocalPenetration(entity, shrinkHorizontal(fallProbe, 0.05D), scopedTargets, 1.0D, true) != null;
        } finally {
            AssemblyProfiler.record("entity.query.assemblyFallSupport", profileStarted);
        }
    }

    public static boolean hasAssemblyGroundSupportAfterMove(
            Entity entity,
            AssemblyEntityCollisionAccess access,
            Vec3 movement
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(movement, "movement");
        long profileStarted = AssemblyProfiler.start();
        try {
            AssemblyContainer container = AssemblyContainers.container(entity.level()).orElse(null);
            if (container == null || container.isEmpty()) {
                return false;
            }

            AABB movedBox = entity.getBoundingBox().move(movement.x, 0.0D, movement.z);
            AABB query = supportProbe(movedBox, Vec3.ZERO).inflate(SUPPORT_QUERY_INFLATE + 1.0D);
            List<AssemblyCollisionTarget> targets = container.collisionTargets(query, null);
            if (targets.isEmpty()) {
                return false;
            }

            for (AssemblyCollisionTarget target : scopedFallSupportTargets(entity.level(), access, targets)) {
                if (hasActualFeetSupport(target, movedBox, Vec3.ZERO, target.poseFrame().currentTransform())
                        || hasActualFeetSupport(target, movedBox, Vec3.ZERO, target.poseFrame().previousTransform())) {
                    return true;
                }
            }
            return false;
        } finally {
            AssemblyProfiler.record("entity.query.assemblyGroundSupportAfterMove", profileStarted);
        }
    }

    public static CarryResult inheritedMotion(Entity entity, @Nullable EntityAssemblyState state) {
        Objects.requireNonNull(entity, "entity");
        if (state == null || !state.active() || state.id() == null || !shouldTrack(entity)) {
            return CarryResult.NONE;
        }

        if (!canMaintainSupport(entity, Vec3.ZERO)) {
            return new CarryResult(Vec3.ZERO, state.decayGrace());
        }

        AssemblyCollisionTarget target = forcedTarget(entity.level(), entity.getBoundingBox(), state.id()).orElse(null);
        if (target == null) {
            return new CarryResult(Vec3.ZERO, state.decayGrace());
        }
        AssemblyPoseFrame frame = target.poseFrame();
        if (!hasActualFeetSupport(target, entity.getBoundingBox(), Vec3.ZERO, frame.previousTransform())) {
            return new CarryResult(Vec3.ZERO, state.decayGrace());
        }

        if (state.epoch() == frame.epoch()) {
            return new CarryResult(Vec3.ZERO, state.withFrame(frame));
        }
        if (state.epoch() > frame.epoch() || frame.epoch() - state.epoch() > SUPPORT_GRACE_TICKS + 1L) {
            return new CarryResult(Vec3.ZERO, state.decayGrace());
        }

        PhysicsVector previousAnchor = frame.previousTransform().localToWorld(state.localAnchor());
        PhysicsVector currentAnchor = frame.currentTransform().localToWorld(state.localAnchor());
        Vec3 motion = new Vec3(
                currentAnchor.x() - previousAnchor.x(),
                currentAnchor.y() - previousAnchor.y(),
                currentAnchor.z() - previousAnchor.z()
        );
        EntityAssemblyState updated = state.withFrame(frame);
        if (!finite(motion) || motion.lengthSqr() > MAX_CARRY_DISTANCE_SQR) {
            return CarryResult.NONE;
        }
        return new CarryResult(snapCarryJitter(entity, "anchor", motion), updated);
    }

    public static CarryResult inheritedCenterMotion(Entity entity, @Nullable EntityAssemblyState state) {
        Objects.requireNonNull(entity, "entity");
        if (state == null || !state.active() || state.id() == null || !shouldTrack(entity)) {
            return CarryResult.NONE;
        }

        if (!canMaintainSupport(entity, Vec3.ZERO)) {
            return new CarryResult(Vec3.ZERO, state.decayGrace());
        }

        AssemblyCollisionTarget target = forcedTarget(entity.level(), entity.getBoundingBox(), state.id()).orElse(null);
        if (target == null) {
            return new CarryResult(Vec3.ZERO, state.decayGrace());
        }
        AssemblyPoseFrame frame = target.poseFrame();
        if (!hasActualFeetSupport(target, entity.getBoundingBox(), Vec3.ZERO, frame.previousTransform())) {
            return new CarryResult(Vec3.ZERO, state.decayGrace());
        }

        if (state.epoch() == frame.epoch()) {
            return new CarryResult(Vec3.ZERO, state.withFrame(frame));
        }
        if (state.epoch() > frame.epoch() || frame.epoch() - state.epoch() > SUPPORT_GRACE_TICKS + 1L) {
            return new CarryResult(Vec3.ZERO, state.decayGrace());
        }

        PhysicsVector previousCenter = frame.previousTransform().localToWorld(state.localPosition());
        PhysicsVector currentCenter = frame.currentTransform().localToWorld(state.localPosition());
        Vec3 motion = new Vec3(
                currentCenter.x() - previousCenter.x(),
                currentCenter.y() - previousCenter.y(),
                currentCenter.z() - previousCenter.z()
        );
        EntityAssemblyState updated = state.withFrame(frame);
        if (!finite(motion) || motion.lengthSqr() > MAX_CARRY_DISTANCE_SQR) {
            return CarryResult.NONE;
        }
        return new CarryResult(snapCarryJitter(entity, "center-state", motion), updated);
    }

    public static CarryResult trackedCenterMotion(Entity entity, @Nullable EntityAssemblyState state) {
        Objects.requireNonNull(entity, "entity");
        if (state == null || !state.active() || state.id() == null || !shouldTrack(entity)) {
            return CarryResult.NONE;
        }

        AssemblyCollisionTarget target = forcedTarget(entity.level(), state.id()).orElse(null);
        if (target == null) {
            return new CarryResult(Vec3.ZERO, state.decayGrace());
        }
        AssemblyPoseFrame frame = target.poseFrame();
        if (state.epoch() == frame.epoch()) {
            return new CarryResult(Vec3.ZERO, state.withFrame(frame));
        }
        if (state.epoch() > frame.epoch() || frame.epoch() - state.epoch() > SUPPORT_GRACE_TICKS + 1L) {
            return new CarryResult(Vec3.ZERO, state.decayGrace());
        }

        PhysicsVector previousCenter = frame.previousTransform().localToWorld(state.localPosition());
        PhysicsVector currentCenter = frame.currentTransform().localToWorld(state.localPosition());
        Vec3 motion = new Vec3(
                currentCenter.x() - previousCenter.x(),
                currentCenter.y() - previousCenter.y(),
                currentCenter.z() - previousCenter.z()
        );
        EntityAssemblyState updated = state.withFrame(frame);
        if (!finite(motion) || motion.lengthSqr() > MAX_CARRY_DISTANCE_SQR) {
            return CarryResult.NONE;
        }
        return new CarryResult(snapCarryJitter(entity, "tracked-center", motion), updated);
    }

    public static CarryResult inheritedCenterMotion(Entity entity, @Nullable AssemblyId trackingId) {
        Objects.requireNonNull(entity, "entity");
        if (trackingId == null || !shouldTrack(entity)) {
            return CarryResult.NONE;
        }

        if (!canMaintainSupport(entity, Vec3.ZERO)) {
            return CarryResult.NONE;
        }

        AssemblyCollisionTarget target = forcedTarget(entity.level(), entity.getBoundingBox(), trackingId).orElse(null);
        if (target == null) {
            return CarryResult.NONE;
        }
        AssemblyPoseFrame frame = target.poseFrame();
        if (!hasActualFeetSupport(target, entity.getBoundingBox(), Vec3.ZERO, frame.previousTransform())) {
            return CarryResult.NONE;
        }

        PhysicsVector previousCenter = center(entity.getBoundingBox());
        PhysicsVector previousFeet = feetCenter(entity.getBoundingBox());
        PhysicsVector localCenter = frame.previousTransform().worldToLocal(previousCenter);
        PhysicsVector localFeet = frame.previousTransform().worldToLocal(previousFeet);
        PhysicsVector currentCenter = frame.currentTransform().localToWorld(localCenter);
        Vec3 motion = new Vec3(
                currentCenter.x() - previousCenter.x(),
                currentCenter.y() - previousCenter.y(),
                currentCenter.z() - previousCenter.z()
        );
        EntityAssemblyState updated = EntityAssemblyState.supported(frame, localFeet, SUPPORT_GRACE_TICKS)
                .withLocalPosition(localCenter);
        if (!finite(motion) || motion.lengthSqr() > MAX_CARRY_DISTANCE_SQR) {
            return CarryResult.NONE;
        }
        return new CarryResult(snapCarryJitter(entity, "center-tracking", motion), updated);
    }

    @Nullable
    public static Vec3 previousFramePosition(Entity entity, @Nullable EntityAssemblyState state) {
        Objects.requireNonNull(entity, "entity");
        if (state == null || !state.active() || state.id() == null) {
            return null;
        }
        AssemblyCollisionTarget target = forcedTarget(entity.level(), entity.getBoundingBox(), state.id()).orElse(null);
        if (target == null) {
            return null;
        }
        AssemblyPoseFrame frame = target.poseFrame();
        PhysicsVector currentPosition = new PhysicsVector(entity.getX(), entity.getY(), entity.getZ());
        PhysicsVector localPosition = frame.currentTransform().worldToLocal(currentPosition);
        PhysicsVector previousPosition = frame.previousTransform().localToWorld(localPosition);
        if (!finite(previousPosition)) {
            return null;
        }
        return new Vec3(previousPosition.x(), previousPosition.y(), previousPosition.z());
    }

    public static CollisionResult collideAssemblies(
            Entity entity,
            Vec3 requestedMotion,
            Vec3 inheritedVelocity,
            @Nullable EntityAssemblyState previousState
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(requestedMotion, "requestedMotion");
        Objects.requireNonNull(inheritedVelocity, "inheritedVelocity");

        long profileStarted = AssemblyProfiler.start();
        try {
            if (entity instanceof ServerPlayer serverPlayer) {
                return collideServerPlayer(serverPlayer, requestedMotion, previousState);
            }

            EntityAssemblyState trackingState = activeTrackingState(entity, requestedMotion, previousState);

            kickTrackedEntityOutOfPlot(entity, trackingState);

            AABB entityBox = entity.getBoundingBox();
            Vec3 collisionMotion = requestedMotion;
            Vec3 velocityMotion = trackingState == null && finite(inheritedVelocity) ? inheritedVelocity : Vec3.ZERO;
            velocityMotion = snapCarryJitter(entity, "stored-velocity", velocityMotion);
            collisionMotion = collisionMotion.add(velocityMotion);
            if (!shouldTrack(entity)) {
                return new CollisionResult(null, false, false, collisionMotion, Vec3.ZERO, Map.of());
            }

            LocalCollisionPass localCollision = collideWithAssemblies(entity, collisionMotion, entityBox, trackingState);
            return new CollisionResult(
                    localCollision.state(),
                    localCollision.onGround(),
                    localCollision.horizontalCollision(),
                    localCollision.motion(),
                    localCollision.inheritedMotion(),
                    localCollision.firstCollisions()
            );
        } finally {
            AssemblyProfiler.record("entity.collision.collideAssemblies", profileStarted);
        }
    }

    private static CollisionResult collideServerPlayer(
            ServerPlayer player,
            Vec3 requestedMotion,
            @Nullable EntityAssemblyState previousState
    ) {
        EntityAssemblyState state = null;
        if (canMaintainSupport(player, requestedMotion)) {
            AABB supportBox = player.getBoundingBox().move(requestedMotion);
            if (previousState != null && previousState.active()) {
                state = findTrackedSupport(
                        player.level(),
                        supportBox,
                        requestedMotion,
                        previousState
                );
            }
        }
        if (state == null && canMaintainSupport(player, requestedMotion) && player instanceof AssemblyEntityCollisionAccess access) {
            AssemblyId trackingId = access.kinetic_assembly$trackingAssemblyId();
            if (trackingId != null) {
                state = findTrackedSupport(
                        player.level(),
                        player.getBoundingBox().move(requestedMotion),
                        requestedMotion,
                        trackingId
                );
            }
        }

        boolean supported = state != null && canMaintainSupport(player, requestedMotion);
        if (!supported) {
            return new CollisionResult(null, false, false, requestedMotion, Vec3.ZERO, Map.of());
        }

        Vec3 delta = player.getDeltaMovement();
        if (delta.y < 0.0D && finite(delta)) {
            player.setDeltaMovement(delta.multiply(1.0D, 0.0D, 1.0D));
        }
        return new CollisionResult(state, true, false, requestedMotion, Vec3.ZERO, Map.of());
    }

    public static CollisionResult finishVanillaCollision(
            Entity entity,
            Vec3 requestedMotion,
            Vec3 vanillaMotion,
            @Nullable EntityAssemblyState previousState,
            CollisionResult assemblyResult
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(requestedMotion, "requestedMotion");
        Objects.requireNonNull(vanillaMotion, "vanillaMotion");
        Objects.requireNonNull(assemblyResult, "assemblyResult");

        Vec3 finalMotion = snapUnchangedComponents(requestedMotion, vanillaMotion);
        EntityAssemblyState state = assemblyResult.state();
        EntityAssemblyState assemblyStateBeforeVanilla = state;
        boolean onGround = assemblyResult.onGround();
        boolean horizontalCollision = assemblyResult.horizontalCollision()
                || !Mth.equal(finalMotion.x, assemblyResult.motion().x)
                || !Mth.equal(finalMotion.z, assemblyResult.motion().z);

        if (!Mth.equal(finalMotion.y, assemblyResult.motion().y)) {
            state = null;
            onGround = false;
        }
        if (state == null && canMaintainSupport(entity, requestedMotion)) {
            EntityAssemblyState supportCandidate = assemblyStateBeforeVanilla != null
                    ? assemblyStateBeforeVanilla
                    : previousState;
            state = findTrackedSupport(entity.level(), entity.getBoundingBox().move(finalMotion), requestedMotion, supportCandidate);
            onGround = state != null;
        }

        return new CollisionResult(
                state,
                onGround,
                horizontalCollision,
                finalMotion,
                assemblyResult.inheritedMotion(),
                assemblyResult.firstCollisions()
        );
    }

    @Nullable
    private static EntityAssemblyState activeTrackingState(
            Entity entity,
            Vec3 requestedMotion,
            @Nullable EntityAssemblyState previousState
    ) {
        if (!shouldTrack(entity)) {
            return null;
        }
        AssemblyId trackingId = null;
        if (previousState != null && previousState.active()) {
            trackingId = previousState.id();
        }
        if (trackingId == null && entity instanceof AssemblyEntityCollisionAccess access) {
            trackingId = access.kinetic_assembly$trackingAssemblyId();
        }
        if (trackingId == null) {
            return null;
        }
        if (!canMaintainSupport(entity, requestedMotion)) {
            return null;
        }

        EntityAssemblyState supportState = findTrackedSupport(
                entity.level(),
                entity.getBoundingBox().move(requestedMotion),
                requestedMotion,
                trackingId
        );
        if (supportState != null) {
            return supportState;
        }

        if (previousState == null) {
            return null;
        }
        return requestedMotion.y > 0.0D ? null : previousState.decayGrace();
    }

    private static void kickTrackedEntityOutOfPlot(Entity entity, @Nullable EntityAssemblyState previousState) {
        if (previousState == null
                || !previousState.active()
                || previousState.id() == null
                || !AssemblyEntityKicking.shouldKick(entity)) {
            return;
        }

        AssemblyPlotProjection projection = AssemblyEntityPositioning.containingProjection(entity);
        if (projection != null && projection.id().equals(previousState.id())) {
            AssemblyEntityKicking.kickEntity(entity, projection);
        }
    }

    private static LocalCollisionPass collideWithAssemblies(
            Entity entity,
            Vec3 requestedMotion,
            AABB entityBox,
            @Nullable EntityAssemblyState previousState
    ) {
        long profileStarted = AssemblyProfiler.start();
        try {
            boolean hasRequestedMotion = requestedMotion.lengthSqr() > 0.0D;
            boolean hasTrackedState = previousState != null
                    && previousState.active()
                    && previousState.id() != null;
            if (!hasRequestedMotion && !hasTrackedState) {
                AssemblyVerticalTrace.solverEarlyExit(entity, "no-motion-no-state", requestedMotion, previousState);
                return new LocalCollisionPass(requestedMotion, Vec3.ZERO, null, false, false, Map.of());
            }

            AssemblyContainer container = AssemblyContainers.container(entity.level()).orElse(null);
            if (container == null || container.isEmpty()) {
                AssemblyVerticalTrace.solverEarlyExit(entity, "no-container", requestedMotion, previousState);
                return new LocalCollisionPass(requestedMotion, Vec3.ZERO, null, false, false, Map.of());
            }

            AssemblyId forcedId = previousState == null ? null : previousState.id();
            AABB queryBounds = entityBox.expandTowards(requestedMotion).inflate(SUPPORT_QUERY_INFLATE + 1.0D);
            List<AssemblyCollisionTarget> targets = container.collisionTargets(queryBounds, forcedId);
            if (targets.isEmpty()) {
                AssemblyProfiler.recordCollisionPass(0, 0, 0);
                AssemblyVerticalTrace.solverEarlyExit(entity, "no-targets", requestedMotion, previousState);
                return new LocalCollisionPass(requestedMotion, Vec3.ZERO, null, false, false, Map.of());
            }

            AssemblyCollisionTarget trackedTarget = trackedTarget(previousState, targets);
            AssemblyCollisionTarget carryTarget = canMaintainSupport(entity, requestedMotion)
                    && needsPoseCarry(previousState, trackedTarget)
                    ? trackedTarget
                    : null;
            if (carryTarget != null) {
                if (!hasActualFeetSupport(carryTarget, entityBox, Vec3.ZERO, carryTarget.poseFrame().previousTransform())) {
                    carryTarget = null;
                }
            }
            EntityAssemblyState sustainedState = sustainTrackedSupport(entity, entityBox, requestedMotion, previousState, targets);
            if (!hasRequestedMotion && sustainedState == null && carryTarget == null) {
                AssemblyVerticalTrace.solverEarlyExit(entity, "no-motion-no-support", requestedMotion, previousState);
                return new LocalCollisionPass(requestedMotion, Vec3.ZERO, null, false, false, Map.of());
            }

            int substeps = Math.min(
                    MAX_LOCAL_COLLISION_SUBSTEPS,
                    Math.max(1, (int) Math.ceil(requestedMotion.length() / LOCAL_COLLISION_SUBSTEP_DISTANCE))
            );
            if (entity instanceof Player player && player.isLocalPlayer()) {
                substeps = Math.max(substeps, 8);
            }
            AssemblyProfiler.recordCollisionPass(targets.size(), profiledBlockCount(targets), substeps);
            AssemblyVerticalTrace.solverQuery(
                    entity,
                    requestedMotion,
                    queryBounds,
                targets.size(),
                substeps,
                forcedId,
                targetId(trackedTarget),
                targetId(carryTarget),
                sustainedState
        );

        Vec3 stepMotion = requestedMotion.scale(1.0D / substeps);
        Vec3 resolvedMotion = Vec3.ZERO;
        Vec3 inheritedMotion = Vec3.ZERO;
        AABB movingBox = entityBox;
        EntityAssemblyState state = sustainedState;
        boolean onGround = state != null;
        boolean horizontalCollision = false;
        Map<AssemblyId, FirstCollisionInfo> firstCollisions = new LinkedHashMap<>();

        for (int i = 0; i < substeps; i++) {
            double substepAlpha = (i + 1.0D) / substeps;
            if (carryTarget != null) {
                SubstepCarry carry = carryTrackedBox(entity, movingBox, carryTarget, (double) i / substeps, substepAlpha);
                movingBox = carry.box();
                inheritedMotion = inheritedMotion.add(carry.motion());
            }
            LocalStepResult step = resolveLocalAssemblyStep(
                    entity,
                    movingBox,
                    stepMotion,
                    targets,
                    substepAlpha,
                    i,
                    substeps
            );
            if (step.horizontalCollision()
                    || step.state() != null
                    || step.firstCollision() != null
                    || !Mth.equal(step.motion().x, stepMotion.x)
                    || !Mth.equal(step.motion().y, stepMotion.y)
                    || !Mth.equal(step.motion().z, stepMotion.z)) {
                AssemblyVerticalTrace.solverStepResult(
                        entity,
                        i,
                        substeps,
                        substepAlpha,
                        stepMotion,
                        step.motion(),
                        step.state(),
                        step.horizontalCollision(),
                        step.firstCollision()
                );
            }
            resolvedMotion = resolvedMotion.add(step.motion());
            movingBox = movingBox.move(step.motion());
            if (step.state() != null) {
                state = step.state();
                onGround = true;
            }
            horizontalCollision |= step.horizontalCollision();
            if (step.firstCollision() != null) {
                firstCollisions.putIfAbsent(step.firstCollision().id(), step.firstCollision());
            }
        }

            Vec3 finalMotion = snapUnchangedComponents(requestedMotion, resolvedMotion);
            return new LocalCollisionPass(
                    finalMotion,
                    snapCarryJitter(entity, "substep-total", inheritedMotion),
                    state,
                    onGround,
                    horizontalCollision,
                    firstCollisions
            );
        } finally {
            AssemblyProfiler.record("entity.collision.pass", profileStarted);
        }
    }

    @Nullable
    private static AssemblyId targetId(@Nullable AssemblyCollisionTarget target) {
        return target == null ? null : target.id();
    }

    private static int profiledBlockCount(List<AssemblyCollisionTarget> targets) {
        if (!AssemblyProfiler.enabled()) {
            return 0;
        }
        int blocks = 0;
        for (AssemblyCollisionTarget target : targets) {
            blocks += target.collisionBlocks().size();
        }
        return blocks;
    }

    private static Vec3 snapUnchangedComponents(Vec3 reference, Vec3 motion) {
        double x = Mth.equal(reference.x, motion.x) ? reference.x : motion.x;
        double y = Mth.equal(reference.y, motion.y) ? reference.y : motion.y;
        double z = Mth.equal(reference.z, motion.z) ? reference.z : motion.z;
        if (x == motion.x && y == motion.y && z == motion.z) {
            return motion;
        }
        return new Vec3(x, y, z);
    }

    private static Vec3 snapCarryJitter(Vec3 motion) {
        if (motion == Vec3.ZERO || !finite(motion)) {
            return Vec3.ZERO;
        }
        if (motion.lengthSqr() <= CARRY_JITTER_LENGTH_SQR) {
            return Vec3.ZERO;
        }

        double x = Math.abs(motion.x) <= CARRY_JITTER_COMPONENT ? 0.0D : motion.x;
        double y = Math.abs(motion.y) <= CARRY_JITTER_COMPONENT ? 0.0D : motion.y;
        double z = Math.abs(motion.z) <= CARRY_JITTER_COMPONENT ? 0.0D : motion.z;
        if (x == 0.0D && y == 0.0D && z == 0.0D) {
            return Vec3.ZERO;
        }
        Vec3 snapped = new Vec3(x, y, z);
        return snapped.lengthSqr() <= CARRY_JITTER_LENGTH_SQR ? Vec3.ZERO : snapped;
    }

    private static Vec3 snapCarryJitter(Entity entity, String source, Vec3 motion) {
        Vec3 snapped = snapCarryJitter(motion);
        if (!sameMotion(motion, snapped)) {
            AssemblyVerticalTrace.carryJitter(entity, source, motion, snapped);
        }
        return snapped;
    }

    private static boolean sameMotion(Vec3 first, Vec3 second) {
        return first.x == second.x && first.y == second.y && first.z == second.z;
    }

    private static boolean needsPoseCarry(
            @Nullable EntityAssemblyState previousState,
            @Nullable AssemblyCollisionTarget target
    ) {
        return previousState != null
                && previousState.active()
                && target != null
                && previousState.epoch() < target.poseFrame().epoch();
    }

    @Nullable
    private static AssemblyCollisionTarget trackedTarget(
            @Nullable EntityAssemblyState previousState,
            List<AssemblyCollisionTarget> targets
    ) {
        if (previousState == null || !previousState.active() || previousState.id() == null) {
            return null;
        }
        for (AssemblyCollisionTarget target : targets) {
            if (previousState.id().equals(target.id())) {
                return target;
            }
        }
        return null;
    }

    private static SubstepCarry carryTrackedBox(
            Entity entity,
            AABB box,
            AssemblyCollisionTarget target,
            double previousAlpha,
            double currentAlpha
    ) {
        AssemblyTransform previousTransform = interpolatedTransform(target.poseFrame(), previousAlpha);
        AssemblyTransform currentTransform = interpolatedTransform(target.poseFrame(), currentAlpha);
        PhysicsVector previousFeet = feetCenter(box);
        PhysicsVector localFeet = previousTransform.worldToLocal(previousFeet);
        PhysicsVector currentFeet = currentTransform.localToWorld(localFeet);
        Vec3 motion = new Vec3(
                currentFeet.x() - previousFeet.x(),
                currentFeet.y() - previousFeet.y(),
                currentFeet.z() - previousFeet.z()
        );
        if (!finite(motion) || motion.lengthSqr() > MAX_CARRY_DISTANCE_SQR) {
            return new SubstepCarry(box, Vec3.ZERO);
        }
        Vec3 snappedMotion = snapCarryJitter(entity, "substep", motion);
        return new SubstepCarry(box.move(snappedMotion), snappedMotion);
    }

    @Nullable
    private static EntityAssemblyState sustainTrackedSupport(
            Entity entity,
            AABB entityBox,
            Vec3 requestedMotion,
            @Nullable EntityAssemblyState previousState,
            List<AssemblyCollisionTarget> targets
    ) {
        if (previousState == null
                || !previousState.active()
                || previousState.id() == null
                || !canMaintainSupport(entity, requestedMotion)) {
            return null;
        }

        AssemblyCollisionTarget trackedTarget = null;
        for (AssemblyCollisionTarget target : targets) {
            if (previousState.id().equals(target.id())) {
                trackedTarget = target;
                break;
            }
        }
        if (trackedTarget == null) {
            return previousState.decayGrace();
        }

        AABB supportBox = entityBox.move(requestedMotion);
        if (!hasActualFeetSupport(trackedTarget, supportBox, Vec3.ZERO, trackedTarget.poseFrame().currentTransform())) {
            return previousState.decayGrace();
        }
        return supportState(supportBox, trackedTarget);
    }

    private static boolean hasNearbyLocalCollisionBlocks(AssemblyCollisionTarget target, AABB sweptEntityBox) {
        AABB worldQuery = sweptEntityBox.inflate(SUPPORT_QUERY_INFLATE);
        AABB previousLocal = worldAabbToLocalBounds(target.poseFrame().previousTransform(), worldQuery);
        AABB currentLocal = worldAabbToLocalBounds(target.poseFrame().currentTransform(), worldQuery);
        AABB localQuery = previousLocal.minmax(currentLocal);
        localQuery = new AABB(
                localQuery.minX,
                localQuery.minY - 1.0D,
                localQuery.minZ,
                localQuery.maxX,
                localQuery.maxY,
                localQuery.maxZ
        );

        for (AssemblyCollisionBlock block : target.collisionBlocks()) {
            for (AABB localBox : block.broadPhaseLocalBoxes()) {
                if (localBox.intersects(localQuery)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasActualFeetSupport(
            AssemblyCollisionTarget target,
            AABB entityBox,
            Vec3 requestedMotion,
            AssemblyTransform transform
    ) {
        return feetSupportCandidate(target, entityBox, requestedMotion, transform) != null;
    }

    @Nullable
    private static Candidate feetSupportCandidate(
            AssemblyCollisionTarget target,
            AABB entityBox,
            Vec3 requestedMotion,
            AssemblyTransform transform
    ) {
        AABB feet = supportProbe(entityBox, requestedMotion);
        Candidate best = null;
        for (AABB localBox : target.bodyLocalBoxes()) {
            AABB worldBox = transform.localAabbToWorldBounds(localBox);
            double xOverlap = Math.min(feet.maxX, worldBox.maxX) - Math.max(feet.minX, worldBox.minX);
            double zOverlap = Math.min(feet.maxZ, worldBox.maxZ) - Math.max(feet.minZ, worldBox.minZ);
            if (xOverlap <= EPSILON || zOverlap <= EPSILON) {
                continue;
            }
            if (!verticallySupportsFeet(entityBox, feet, worldBox)) {
                continue;
            }

            double score = xOverlap * zOverlap;
            if (best == null || score > best.score()) {
                best = new Candidate(target, worldBox, score);
            }
        }
        return best;
    }

    private static boolean verticallySupportsFeet(AABB entityBox, AABB feet, AABB worldBox) {
        if (worldBox.minY > entityBox.minY + EPSILON) {
            return false;
        }
        double yOverlap = Math.min(feet.maxY, worldBox.maxY) - Math.max(feet.minY, worldBox.minY);
        return yOverlap > EPSILON;
    }

    private static LocalStepResult resolveLocalAssemblyStep(
            Entity entity,
            AABB baseBox,
            Vec3 requestedStep,
            List<AssemblyCollisionTarget> targets,
            double substepAlpha,
            int stepIndex,
            int substeps
    ) {
        Vec3 resolved = requestedStep;
        AABB testBox = baseBox.move(resolved);
        EntityAssemblyState state = null;
        FirstCollisionInfo firstCollision = null;
        boolean horizontalCollision = false;

        for (int iteration = 0; iteration < LOCAL_COLLISION_ITERATIONS; iteration++) {
            LocalPenetration penetration = deepestLocalPenetration(entity, testBox, targets, substepAlpha, true);
            if (penetration == null) {
                break;
            }

            Vec3 worldMtv = penetration.worldMtv();
            Vec3 normal = normalizeOrNull(worldMtv);
            if (normal == null) {
                break;
            }
            double upDot = normal.dot(WORLD_UP);
            boolean vertical = Math.abs(upDot) > 0.6D;
            if (firstCollision == null) {
                firstCollision = firstCollisionInfo(testBox, penetration, normal, !vertical);
            }
            if (!vertical) {
                Vec3 stepUp = tryStepUpLocal(entity, baseBox, requestedStep, normal, targets, substepAlpha);
                if (stepUp != null) {
                    AssemblyVerticalTrace.solverPenetration(
                            entity,
                            stepIndex,
                            substeps,
                            iteration,
                            substepAlpha,
                            penetration.target().id(),
                            penetration.block().localPos(),
                            requestedStep,
                            resolved,
                            worldMtv,
                            normal,
                            upDot,
                            false,
                            "step-up"
                    );
                    resolved = stepUp;
                    testBox = baseBox.move(resolved);
                    state = supportState(testBox, penetration.target());
                    continue;
                }
                horizontalCollision = true;
                applyHorizontalFriction(entity, normal);
            } else if (upDot > 0.8D) {
                worldMtv = WORLD_UP.scale(worldMtv.dot(WORLD_UP));
            }

            AssemblyVerticalTrace.solverPenetration(
                    entity,
                    stepIndex,
                    substeps,
                    iteration,
                    substepAlpha,
                    penetration.target().id(),
                    penetration.block().localPos(),
                    requestedStep,
                    resolved,
                    worldMtv,
                    normal,
                    upDot,
                    vertical,
                    "mtv"
            );
            resolved = resolved.add(worldMtv);
            testBox = testBox.move(worldMtv);
            if (vertical && upDot > 0.0D) {
                state = supportState(testBox, penetration.target());
            }
        }

        return new LocalStepResult(resolved, state, horizontalCollision, firstCollision);
    }

    private static void applyHorizontalFriction(Entity entity, Vec3 normal) {
        Vec3 existingMovement = entity.getDeltaMovement();
        double existingLength = existingMovement.length();
        if (existingLength <= EPSILON || !finite(existingMovement)) {
            return;
        }

        Vec3 deltaMovementLoss = normal.scale(normal.dot(existingMovement));
        if (deltaMovementLoss.length() > existingLength * 0.1D) {
            entity.setSprinting(false);
        }

        Vec3 newMovement = existingMovement.subtract(deltaMovementLoss);
        double upVelocity = newMovement.dot(WORLD_UP);
        Vec3 horizontalMovement = newMovement.subtract(WORLD_UP.scale(upVelocity)).scale(0.995D);
        entity.setDeltaMovement(horizontalMovement.add(WORLD_UP.scale(upVelocity)));
    }

    @Nullable
    private static Vec3 tryStepUpLocal(
            Entity entity,
            AABB baseBox,
            Vec3 requestedStep,
            Vec3 normal,
            List<AssemblyCollisionTarget> targets,
            double substepAlpha
    ) {
        if (!entity.onGround() || entity.maxUpStep() <= 0.0F) {
            return null;
        }
        if (requestedStep.horizontalDistanceSqr() <= 0.0D) {
            return null;
        }
        if (requestedStep.dot(normal) > 0.0D) {
            return requestedStep;
        }

        AABB inflatedBaseBox = baseBox.inflate(0.05D);
        Vec3 checkBackoff = normal.scale(-2.0D / 16.0D);
        Vec3 resolvedBackoff = normal.scale(-1.0D / 16.0D);
        LocalPenetration lastStepPenetration = null;
        int collidingCount = 0;
        int freeCount = 0;
        for (double height = 0.0D; height <= entity.maxUpStep() + EPSILON; height += LOCAL_STEP_INCREMENT) {
            Vec3 candidate = new Vec3(requestedStep.x, height, requestedStep.z);
            LocalPenetration penetration = deepestLocalPenetration(
                    entity,
                    inflatedBaseBox.move(candidate).move(checkBackoff),
                    targets,
                    substepAlpha,
                    false
            );
            if (penetration == null) {
                freeCount++;
                break;
            }
            lastStepPenetration = penetration;
            collidingCount++;
        }
        if (freeCount <= 0 || collidingCount <= 0 || lastStepPenetration == null) {
            return null;
        }

        Vec3 lastStepNormal = normalizeOrNull(lastStepPenetration.worldMtv());
        if (lastStepNormal == null || lastStepNormal.dot(WORLD_UP) <= 0.8D) {
            return null;
        }
        double height = Math.min(entity.maxUpStep(), collidingCount * LOCAL_STEP_INCREMENT);
        return new Vec3(requestedStep.x, height, requestedStep.z).add(resolvedBackoff);
    }

    @Nullable
    private static LocalPenetration deepestLocalPenetration(
            Entity entity,
            AABB worldBox,
            List<AssemblyCollisionTarget> targets,
            double substepAlpha,
            boolean dynamicShapes
    ) {
        long profileStarted = AssemblyProfiler.start();
        try {
            OrientedBox entityObb = entityObb(worldBox);
            LocalPenetration deepest = null;
            double deepestLength = 0.0D;

            for (AssemblyCollisionTarget target : targets) {
                AssemblyTransform transform = interpolatedTransform(target.poseFrame(), substepAlpha);
                for (AssemblyCollisionBlock block : target.collisionBlocks()) {
                    for (AABB localBox : collisionBoxes(entity, worldBox, target, block, transform, dynamicShapes)) {
                        Vec3 worldMtv = sat(entityObb, bodyLocalObb(localBox, transform));
                        if (worldMtv == null) {
                            continue;
                        }
                        double length = worldMtv.lengthSqr();
                        if (length <= MIN_MTV_LENGTH_SQR) {
                            continue;
                        }
                        if (isInteriorCollision(entity, worldBox, target, block, localBox, transform, worldMtv, dynamicShapes)) {
                            continue;
                        }
                        if (length > deepestLength) {
                            deepestLength = length;
                            deepest = new LocalPenetration(target, block, worldMtv);
                        }
                    }
                }
            }

            return deepest;
        } finally {
            AssemblyProfiler.record("entity.collision.deepestPenetration", profileStarted);
        }
    }

    private static boolean isInteriorCollision(
            Entity entity,
            AABB worldBox,
            AssemblyCollisionTarget target,
            AssemblyCollisionBlock block,
            AABB localBox,
            AssemblyTransform transform,
            Vec3 worldMtv,
            boolean dynamicShapes
    ) {
        long profileStarted = AssemblyProfiler.start();
        try {
            Vec3 localNormal = worldDirectionToLocal(transform, worldMtv);
            AxisOffset offset = dominantAxisOffset(localNormal);
            if (offset == null) {
                return false;
            }

            BlockPos neighborPos = block.localPos().offset(offset.x(), offset.y(), offset.z());
            AssemblyCollisionBlock neighbor = null;
            for (AssemblyCollisionBlock candidate : target.collisionBlocks()) {
                if (candidate.localPos().equals(neighborPos)) {
                    neighbor = candidate;
                    break;
                }
            }
            if (neighbor == null) {
                return false;
            }

            for (AABB neighborBox : collisionBoxes(entity, worldBox, target, neighbor, transform, dynamicShapes)) {
                if (coversInteriorFace(localBox, expand(neighborBox, 0.001D), offset)) {
                    return true;
                }
            }
            return false;
        } finally {
            AssemblyProfiler.record("entity.collision.interiorCheck", profileStarted);
        }
    }

    private static Vec3 worldDirectionToLocal(AssemblyTransform transform, Vec3 worldDirection) {
        PhysicsVector local = transform.worldDirectionToLocal(new PhysicsVector(
                worldDirection.x,
                worldDirection.y,
                worldDirection.z
        ));
        return normalizeOrDefault(new Vec3(local.x(), local.y(), local.z()), Vec3.ZERO);
    }

    @Nullable
    private static AxisOffset dominantAxisOffset(Vec3 localNormal) {
        double absX = Math.abs(localNormal.x);
        double absY = Math.abs(localNormal.y);
        double absZ = Math.abs(localNormal.z);
        double max = Math.max(absX, Math.max(absY, absZ));
        if (max < 0.5D) {
            return null;
        }
        if (absX >= absY && absX >= absZ) {
            return new AxisOffset(localNormal.x >= 0.0D ? 1 : -1, 0, 0);
        }
        if (absY >= absX && absY >= absZ) {
            return new AxisOffset(0, localNormal.y >= 0.0D ? 1 : -1, 0);
        }
        return new AxisOffset(0, 0, localNormal.z >= 0.0D ? 1 : -1);
    }

    private static boolean coversInteriorFace(AABB box, AABB neighbor, AxisOffset offset) {
        double faceArea;
        double coveredArea;
        if (offset.x() != 0) {
            double faceX = offset.x() > 0 ? box.maxX : box.minX;
            if (!crossesFace(neighbor.minX, neighbor.maxX, faceX)) {
                return false;
            }
            faceArea = intervalSize(box.minY, box.maxY) * intervalSize(box.minZ, box.maxZ);
            coveredArea = overlap(box.minY, box.maxY, neighbor.minY, neighbor.maxY)
                    * overlap(box.minZ, box.maxZ, neighbor.minZ, neighbor.maxZ);
        } else if (offset.y() != 0) {
            double faceY = offset.y() > 0 ? box.maxY : box.minY;
            if (!crossesFace(neighbor.minY, neighbor.maxY, faceY)) {
                return false;
            }
            faceArea = intervalSize(box.minX, box.maxX) * intervalSize(box.minZ, box.maxZ);
            coveredArea = overlap(box.minX, box.maxX, neighbor.minX, neighbor.maxX)
                    * overlap(box.minZ, box.maxZ, neighbor.minZ, neighbor.maxZ);
        } else {
            double faceZ = offset.z() > 0 ? box.maxZ : box.minZ;
            if (!crossesFace(neighbor.minZ, neighbor.maxZ, faceZ)) {
                return false;
            }
            faceArea = intervalSize(box.minX, box.maxX) * intervalSize(box.minY, box.maxY);
            coveredArea = overlap(box.minX, box.maxX, neighbor.minX, neighbor.maxX)
                    * overlap(box.minY, box.maxY, neighbor.minY, neighbor.maxY);
        }
        return faceArea > EPSILON && coveredArea + 0.01D >= faceArea;
    }

    private static boolean crossesFace(double min, double max, double face) {
        return min <= face + 0.001D && max >= face - 0.001D;
    }

    private static double intervalSize(double min, double max) {
        return Math.max(0.0D, max - min);
    }

    private static double overlap(double firstMin, double firstMax, double secondMin, double secondMax) {
        return Math.max(0.0D, Math.min(firstMax, secondMax) - Math.max(firstMin, secondMin));
    }

    private static AABB expand(AABB box, double amount) {
        return new AABB(
                box.minX - amount,
                box.minY - amount,
                box.minZ - amount,
                box.maxX + amount,
                box.maxY + amount,
                box.maxZ + amount
        );
    }

    private static List<AABB> collisionBoxes(
            Entity entity,
            AABB worldBox,
            AssemblyCollisionTarget target,
            AssemblyCollisionBlock block,
            AssemblyTransform transform,
            boolean dynamicShapes
    ) {
        if (!dynamicShapes || !block.dynamicCollisionShape()) {
            return block.bodyLocalBoxes();
        }

        BlockPos plotPos = target.plot().toPlotBlockPos(block.localPos());
        VoxelShape shape = assemblyEntityCollisionShape(entity, worldBox, target, block, transform, plotPos);
        return bodyLocalCollisionBoxes(target, plotPos, shape);
    }

    private static VoxelShape assemblyEntityCollisionShape(
            Entity entity,
            AABB worldBox,
            AssemblyCollisionTarget target,
            AssemblyCollisionBlock block,
            AssemblyTransform transform,
            BlockPos plotPos
    ) {
        BlockState state = block.blockState();
        BlockGetter level = target.collisionLevel();
        if (state.getBlock() instanceof ScaffoldingBlock) {
            VoxelShape originalShape = state.getCollisionShape(
                    level,
                    plotPos,
                    new AssemblyScaffoldingCollisionContext(entity)
            );
            if (entity.isShiftKeyDown()) {
                return originalShape;
            }
            if (isAboveScaffoldingTop(worldBox, target, transform, plotPos)) {
                return SCAFFOLDING_TOP_SHAPE;
            }
            return originalShape;
        }
        return state.getCollisionShape(level, plotPos);
    }

    private static boolean isAboveScaffoldingTop(
            AABB worldBox,
            AssemblyCollisionTarget target,
            AssemblyTransform transform,
            BlockPos plotPos
    ) {
        PhysicsVector worldCenter = center(worldBox);
        PhysicsVector worldFeet = new PhysicsVector(
                worldCenter.x(),
                worldCenter.y() - (worldBox.getYsize() * 0.5D - SCAFFOLDING_SKEW),
                worldCenter.z()
        );
        PhysicsVector bodyLocalFeet = transform.worldToLocal(worldFeet);
        PhysicsVector plotFeet = AssemblyCoordinateSpace.bodyLocalToPlot(
                target.plot(),
                target.bodyToPlotOrigin(),
                bodyLocalFeet
        );
        return plotFeet.y() > plotPos.getY() + 1.0D + SCAFFOLDING_SKEW;
    }

    private static List<AABB> bodyLocalCollisionBoxes(
            AssemblyCollisionTarget target,
            BlockPos plotPos,
            VoxelShape shape
    ) {
        if (shape.isEmpty()) {
            return List.of();
        }

        List<AABB> boxes = new ArrayList<>();
        for (AABB box : shape.toAabbs()) {
            AABB plotBox = new AABB(
                    plotPos.getX() + box.minX,
                    plotPos.getY() + box.minY,
                    plotPos.getZ() + box.minZ,
                    plotPos.getX() + box.maxX,
                    plotPos.getY() + box.maxY,
                    plotPos.getZ() + box.maxZ
            );
            boxes.add(plotBoxToBodyLocalBounds(target, plotBox));
        }
        return List.copyOf(boxes);
    }

    private static AABB plotBoxToBodyLocalBounds(AssemblyCollisionTarget target, AABB plotBox) {
        PhysicsVector bodyToPlotOrigin = target.bodyToPlotOrigin();
        double xOffset = bodyToPlotOrigin.x() - target.plot().minPlotX();
        double yOffset = bodyToPlotOrigin.y() - target.plot().minPlotY();
        double zOffset = bodyToPlotOrigin.z() - target.plot().minPlotZ();
        return new AABB(
                plotBox.minX + xOffset,
                plotBox.minY + yOffset,
                plotBox.minZ + zOffset,
                plotBox.maxX + xOffset,
                plotBox.maxY + yOffset,
                plotBox.maxZ + zOffset
        );
    }

    private static AssemblyTransform interpolatedTransform(AssemblyPoseFrame frame, double alpha) {
        return frame.interpolatedTransform(alpha);
    }

    private static OrientedBox entityObb(AABB box) {
        return new OrientedBox(
                new Vec3(
                        (box.minX + box.maxX) * 0.5D,
                        (box.minY + box.maxY) * 0.5D,
                        (box.minZ + box.maxZ) * 0.5D
                ),
                new Vec3(
                        (box.maxX - box.minX) * 0.5D,
                        (box.maxY - box.minY) * 0.5D,
                        (box.maxZ - box.minZ) * 0.5D
                ),
                WORLD_RIGHT,
                WORLD_UP,
                WORLD_FORWARD
        );
    }

    private static OrientedBox bodyLocalObb(AABB localBox, AssemblyTransform transform) {
        PhysicsVector localCenter = new PhysicsVector(
                (localBox.minX + localBox.maxX) * 0.5D,
                (localBox.minY + localBox.maxY) * 0.5D,
                (localBox.minZ + localBox.maxZ) * 0.5D
        );
        PhysicsVector worldCenter = transform.localToWorld(localCenter);
        return new OrientedBox(
                new Vec3(worldCenter.x(), worldCenter.y(), worldCenter.z()),
                new Vec3(
                        (localBox.maxX - localBox.minX) * 0.5D,
                        (localBox.maxY - localBox.minY) * 0.5D,
                        (localBox.maxZ - localBox.minZ) * 0.5D
                ),
                localAxisToWorld(transform, WORLD_RIGHT),
                localAxisToWorld(transform, WORLD_UP),
                localAxisToWorld(transform, WORLD_FORWARD)
        );
    }

    private static Vec3 localAxisToWorld(AssemblyTransform transform, Vec3 axis) {
        PhysicsVector world = transform.localDirectionToWorld(new PhysicsVector(axis.x, axis.y, axis.z));
        return normalizeOrDefault(new Vec3(world.x(), world.y(), world.z()), axis);
    }

    @Nullable
    private static Vec3 sat(OrientedBox entityBox, OrientedBox blockBox) {
        Vec3[] axes = new Vec3[]{
                entityBox.axisX(),
                entityBox.axisY(),
                entityBox.axisZ(),
                blockBox.axisX(),
                blockBox.axisY(),
                blockBox.axisZ(),
                cross(entityBox.axisX(), blockBox.axisX()),
                cross(entityBox.axisX(), blockBox.axisY()),
                cross(entityBox.axisX(), blockBox.axisZ()),
                cross(entityBox.axisY(), blockBox.axisX()),
                cross(entityBox.axisY(), blockBox.axisY()),
                cross(entityBox.axisY(), blockBox.axisZ()),
                cross(entityBox.axisZ(), blockBox.axisX()),
                cross(entityBox.axisZ(), blockBox.axisY()),
                cross(entityBox.axisZ(), blockBox.axisZ())
        };

        double minOverlap = Double.POSITIVE_INFINITY;
        Vec3 minAxis = null;
        Vec3 centerDelta = subtract(entityBox.center(), blockBox.center());
        for (Vec3 candidate : axes) {
            Vec3 axis = normalizeOrNull(candidate);
            if (axis == null) {
                continue;
            }

            Projection entityProjection = project(entityBox, axis);
            Projection blockProjection = project(blockBox, axis);
            double overlap = Math.min(entityProjection.max(), blockProjection.max())
                    - Math.max(entityProjection.min(), blockProjection.min());
            if (overlap <= EPSILON) {
                return null;
            }
            if (overlap < minOverlap) {
                minOverlap = overlap;
                minAxis = dot(centerDelta, axis) < 0.0D ? axis.scale(-1.0D) : axis;
            }
        }

        return minAxis == null ? null : minAxis.scale(minOverlap);
    }

    private static Projection project(OrientedBox box, Vec3 axis) {
        double center = dot(box.center(), axis);
        double radius = box.halfExtents().x * Math.abs(dot(box.axisX(), axis))
                + box.halfExtents().y * Math.abs(dot(box.axisY(), axis))
                + box.halfExtents().z * Math.abs(dot(box.axisZ(), axis));
        return new Projection(center - radius, center + radius);
    }

    private static Vec3 cross(Vec3 first, Vec3 second) {
        return new Vec3(
                first.y * second.z - first.z * second.y,
                first.z * second.x - first.x * second.z,
                first.x * second.y - first.y * second.x
        );
    }

    private static double dot(Vec3 first, Vec3 second) {
        return first.x * second.x + first.y * second.y + first.z * second.z;
    }

    private static Vec3 subtract(Vec3 first, Vec3 second) {
        return new Vec3(first.x - second.x, first.y - second.y, first.z - second.z);
    }

    @Nullable
    private static Vec3 normalizeOrNull(Vec3 vector) {
        double lengthSqr = vector.lengthSqr();
        if (lengthSqr <= 1.0E-12D || !Double.isFinite(lengthSqr)) {
            return null;
        }
        return vector.scale(1.0D / Math.sqrt(lengthSqr));
    }

    private static Vec3 normalizeOrDefault(Vec3 vector, Vec3 fallback) {
        Vec3 normalized = normalizeOrNull(vector);
        return normalized == null ? fallback : normalized;
    }

    private static AABB shrinkHorizontal(AABB box, double amount) {
        double shrinkX = Math.min(Math.max(box.getXsize() * 0.45D, 0.0D), amount);
        double shrinkZ = Math.min(Math.max(box.getZsize() * 0.45D, 0.0D), amount);
        return new AABB(
                box.minX + shrinkX,
                box.minY,
                box.minZ + shrinkZ,
                box.maxX - shrinkX,
                box.maxY,
                box.maxZ - shrinkZ
        );
    }

    private static FirstCollisionInfo firstCollisionInfo(
            AABB entityBox,
            LocalPenetration penetration,
            Vec3 worldDirection,
            boolean horizontal
    ) {
        PhysicsVector localLocation = penetration.target().transform().worldToLocal(center(entityBox));
        return new FirstCollisionInfo(
                penetration.target().id(),
                localLocation,
                worldDirection,
                horizontal,
                pointVelocity(penetration.target().poseFrame(), localLocation),
                penetration.block()
        );
    }

    private static Vec3 pointVelocity(AssemblyPoseFrame frame, PhysicsVector localLocation) {
        PhysicsVector previous = frame.previousTransform().localToWorld(localLocation);
        PhysicsVector current = frame.currentTransform().localToWorld(localLocation);
        if (!finite(previous) || !finite(current)) {
            return Vec3.ZERO;
        }
        return snapCarryJitter(new Vec3(
                current.x() - previous.x(),
                current.y() - previous.y(),
                current.z() - previous.z()
        ));
    }

    private static AABB worldAabbToLocalBounds(AssemblyTransform transform, AABB worldBox) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int xIndex = 0; xIndex < 2; xIndex++) {
            double x = xIndex == 0 ? worldBox.minX : worldBox.maxX;
            for (int yIndex = 0; yIndex < 2; yIndex++) {
                double y = yIndex == 0 ? worldBox.minY : worldBox.maxY;
                for (int zIndex = 0; zIndex < 2; zIndex++) {
                    double z = zIndex == 0 ? worldBox.minZ : worldBox.maxZ;
                    PhysicsVector local = transform.worldToLocal(new PhysicsVector(x, y, z));
                    minX = Math.min(minX, local.x());
                    minY = Math.min(minY, local.y());
                    minZ = Math.min(minZ, local.z());
                    maxX = Math.max(maxX, local.x());
                    maxY = Math.max(maxY, local.y());
                    maxZ = Math.max(maxZ, local.z());
                }
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static EntityAssemblyState supportState(AABB entityBox, AssemblyCollisionTarget target) {
        PhysicsVector center = target.transform().worldToLocal(new PhysicsVector(
                (entityBox.minX + entityBox.maxX) * 0.5D,
                (entityBox.minY + entityBox.maxY) * 0.5D,
                (entityBox.minZ + entityBox.maxZ) * 0.5D
        ));
        PhysicsVector anchor = target.transform().worldToLocal(new PhysicsVector(
                (entityBox.minX + entityBox.maxX) * 0.5D,
                entityBox.minY,
                (entityBox.minZ + entityBox.maxZ) * 0.5D
        ));
        return EntityAssemblyState.supported(target.poseFrame(), anchor, SUPPORT_GRACE_TICKS)
                .withLocalPosition(center);
    }

    private static @Nullable EntityAssemblyState findTrackedSupport(
            Level level,
            AABB entityBox,
            Vec3 requestedMotion,
            @Nullable EntityAssemblyState previousState
    ) {
        if (previousState == null || !previousState.active() || previousState.id() == null || requestedMotion.y > 0.0D) {
            return null;
        }

        AssemblyCollisionTarget target = forcedTarget(level, entityBox, previousState.id()).orElse(null);
        if (target == null) {
            return null;
        }

        if (!hasActualFeetSupport(target, entityBox, Vec3.ZERO, target.poseFrame().currentTransform())) {
            return null;
        }
        return supportState(entityBox, target);
    }

    private static @Nullable EntityAssemblyState findTrackedSupport(
            Level level,
            AABB entityBox,
            Vec3 requestedMotion,
            AssemblyId trackingId
    ) {
        if (requestedMotion.y > 0.0D) {
            return null;
        }

        AssemblyCollisionTarget target = forcedTarget(level, entityBox, trackingId).orElse(null);
        if (target == null) {
            return null;
        }

        return hasActualFeetSupport(target, entityBox, Vec3.ZERO, target.poseFrame().currentTransform())
                ? supportState(entityBox, target)
                : null;
    }

    private static AABB supportProbe(AABB entityBox, Vec3 requestedMotion) {
        double down = SUPPORT_PROBE_DOWN + Math.max(0.0D, -requestedMotion.y);
        double minX = entityBox.minX + HORIZONTAL_INSET;
        double minZ = entityBox.minZ + HORIZONTAL_INSET;
        double maxX = entityBox.maxX - HORIZONTAL_INSET;
        double maxZ = entityBox.maxZ - HORIZONTAL_INSET;
        if (minX >= maxX) {
            minX = entityBox.minX;
            maxX = entityBox.maxX;
        }
        if (minZ >= maxZ) {
            minZ = entityBox.minZ;
            maxZ = entityBox.maxZ;
        }
        return new AABB(
                minX,
                entityBox.minY - down,
                minZ,
                maxX,
                entityBox.minY + SUPPORT_PROBE_UP,
                maxZ
        );
    }

    public static Optional<AssemblyCollisionTarget> forcedTarget(Level level, AABB entityBox, AssemblyId id) {
        long profileStarted = AssemblyProfiler.start();
        try {
            AssemblyContainer container = AssemblyContainers.container(level).orElse(null);
            if (container == null || container.isEmpty()) {
                return Optional.empty();
            }
            AABB query = entityBox.inflate(SUPPORT_QUERY_INFLATE + 1.0D);
            return container.collisionTargets(query, id).stream()
                    .filter(target -> target.id().equals(id))
                    .findFirst();
        } finally {
            AssemblyProfiler.record("entity.query.forcedTarget", profileStarted);
        }
    }

    public static Optional<AssemblyCollisionTarget> forcedTarget(Level level, AssemblyId id) {
        return forcedTarget(level, new AABB(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D), id);
    }

    public static boolean trackingTargetExists(Level level, AssemblyId id) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        long profileStarted = AssemblyProfiler.start();
        try {
            return forcedTarget(level, id).isPresent();
        } finally {
            AssemblyProfiler.record("entity.query.trackingTargetExists", profileStarted);
        }
    }

    public static boolean hasNearbyTrackingTarget(Entity entity, AssemblyId id) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(id, "id");
        long profileStarted = AssemblyProfiler.start();
        try {
            if (!shouldTrack(entity)) {
                return false;
            }
            AssemblyCollisionTarget target = forcedTarget(entity.level(), entity.getBoundingBox(), id).orElse(null);
            return target != null
                    && (hasActualFeetSupport(target, entity.getBoundingBox(), Vec3.ZERO, target.poseFrame().previousTransform())
                    || hasActualFeetSupport(target, entity.getBoundingBox(), Vec3.ZERO, target.poseFrame().currentTransform()));
        } finally {
            AssemblyProfiler.record("entity.query.hasNearbyTrackingTarget", profileStarted);
        }
    }

    public static boolean isPlotBlock(Level level, BlockPos plotBlockPos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotBlockPos, "plotBlockPos");
        AssemblyContainer container = AssemblyContainers.container(level).orElse(null);
        if (container == null || container.isEmpty()) {
            return false;
        }
        return container.plotProjection(plotBlockPos).isPresent()
                || container.assemblyAtPlotBlock(plotBlockPos).isPresent();
    }

    @Nullable
    public static AssemblyPlotProjection spawnPacketProjection(Level level, Vec3 packetPosition) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(packetPosition, "packetPosition");
        if (!finite(packetPosition)) {
            return null;
        }
        AssemblyContainer container = AssemblyContainers.container(level).orElse(null);
        if (container == null || container.isEmpty()) {
            return null;
        }

        BlockPos plotBlockPos = BlockPos.containing(packetPosition);
        AssemblyPlotProjection projection = container.plotProjection(plotBlockPos).orElse(null);
        if (projection != null) {
            return projection;
        }
        return container.plotProjection(new ChunkPos(plotBlockPos)).orElse(null);
    }

    @Nullable
    public static AssemblyId assemblyAtPlotPosition(Level level, Vec3 plotPosition) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPosition, "plotPosition");
        AssemblyContainer container = AssemblyContainers.container(level).orElse(null);
        if (container == null || container.isEmpty()) {
            return null;
        }
        BlockPos plotBlockPos = BlockPos.containing(plotPosition);
        return container.assemblyAtPlotBlock(plotBlockPos)
                .map(PhysicsAssembly::id)
                .orElse(null);
    }

    @Nullable
    public static BlockState inBlockState(Entity entity, @Nullable AssemblyId trackingId) {
        Objects.requireNonNull(entity, "entity");
        long profileStarted = AssemblyProfiler.start();
        try {
            AssemblyContainer container = AssemblyContainers.container(entity.level()).orElse(null);
            if (container == null || container.isEmpty()) {
                return null;
            }

            AABB queryBounds = entity.getBoundingBox().inflate(SUPPORT_QUERY_INFLATE + 1.0D);
            List<AssemblyCollisionTarget> targets = container.collisionTargets(queryBounds, trackingId);
            if (targets.isEmpty()) {
                return null;
            }

            PhysicsVector sample = new PhysicsVector(entity.getX(), entity.getY() + 0.001D, entity.getZ());
            for (AssemblyCollisionTarget target : targets) {
                PhysicsVector localSample = target.transform().worldToLocal(sample);
                for (AssemblyCollisionBlock block : target.collisionBlocks()) {
                    for (AABB localBox : block.broadPhaseLocalBoxes()) {
                        if (contains(localBox, localSample)) {
                            return block.blockState();
                        }
                    }
                }
            }
            return null;
        } finally {
            AssemblyProfiler.record("entity.query.inBlockState", profileStarted);
        }
    }

    @Nullable
    public static BlockPos onPos(Entity entity, @Nullable AssemblyId trackingId, float distance) {
        Objects.requireNonNull(entity, "entity");
        long profileStarted = AssemblyProfiler.start();
        try {
            AssemblyContainer container = AssemblyContainers.container(entity.level()).orElse(null);
            if (container == null || container.isEmpty()) {
                return null;
            }

            double down = Math.max(0.1D, distance);
            Vec3 feetPosition = entity.position().add(0.0D, -down, 0.0D);
            if (trackingId != null) {
                AssemblyPlotProjection projection = container.plotProjection(trackingId).orElse(null);
                BlockPos trackingOnPos = projectedOnPos(entity.level(), projection, feetPosition, false);
                if (trackingOnPos != null) {
                    return trackingOnPos;
                }
            }

            AABB queryBounds = supportProbe(entity.getBoundingBox(), new Vec3(0.0D, -down, 0.0D))
                    .inflate(SUPPORT_QUERY_INFLATE);
            for (AssemblyCollisionTarget target : container.collisionTargets(queryBounds, null)) {
                AssemblyPlotProjection projection = container.plotProjection(target.id()).orElse(null);
                BlockPos fallbackOnPos = projectedOnPos(entity.level(), projection, feetPosition, true);
                if (fallbackOnPos != null) {
                    return fallbackOnPos;
                }
            }
            return null;
        } finally {
            AssemblyProfiler.record("entity.query.onPos", profileStarted);
        }
    }

    @Nullable
    private static BlockPos projectedOnPos(
            Level level,
            @Nullable AssemblyPlotProjection projection,
            Vec3 feetPosition,
            boolean requireBlock
    ) {
        if (projection == null) {
            return null;
        }
        Vec3 plotPosition = projection.worldToPlot(feetPosition);
        if (!finite(plotPosition)) {
            return null;
        }

        BlockPos plotBlockPos = BlockPos.containing(plotPosition);
        if (!projection.containsPlotBlock(plotBlockPos)) {
            return null;
        }
        return !requireBlock || !level.getBlockState(plotBlockPos).isAir() ? plotBlockPos : null;
    }

    private static boolean contains(AABB box, PhysicsVector point) {
        return point.x() >= box.minX - EPSILON
                && point.x() <= box.maxX + EPSILON
                && point.y() >= box.minY - EPSILON
                && point.y() <= box.maxY + EPSILON
                && point.z() >= box.minZ - EPSILON
                && point.z() <= box.maxZ + EPSILON;
    }

    private static PhysicsVector center(AABB box) {
        return new PhysicsVector(
                (box.minX + box.maxX) * 0.5D,
                (box.minY + box.maxY) * 0.5D,
                (box.minZ + box.maxZ) * 0.5D
        );
    }

    private static PhysicsVector feetCenter(AABB box) {
        return new PhysicsVector(
                (box.minX + box.maxX) * 0.5D,
                box.minY,
                (box.minZ + box.maxZ) * 0.5D
        );
    }

    private static boolean shouldTrack(Entity entity) {
        return !entity.noPhysics
                && !entity.isSpectator()
                && !entity.isPassenger()
                && !(entity instanceof BlockAttachedEntity);
    }

    private static boolean canMaintainSupport(Entity entity, Vec3 requestedMotion) {
        return entity.onGround()
                && requestedMotion.y <= EPSILON
                && !hasUpwardMotion(entity);
    }

    private static List<AssemblyCollisionTarget> scopedFallSupportTargets(
            Level level,
            AssemblyEntityCollisionAccess access,
            List<AssemblyCollisionTarget> targets
    ) {
        AssemblyId trackingId = activeContextAssemblyId(level, access);
        if (trackingId == null) {
            return targets;
        }

        List<AssemblyCollisionTarget> scoped = new ArrayList<>();
        for (AssemblyCollisionTarget target : targets) {
            if (trackingId.equals(target.id())) {
                scoped.add(target);
            }
        }
        return scoped.isEmpty() ? targets : scoped;
    }

    @Nullable
    private static AssemblyId activeContextAssemblyId(Level level, AssemblyEntityCollisionAccess access) {
        AssemblyId trackingId = access.kinetic_assembly$trackingAssemblyId();
        if (trackingId != null) {
            return trackingId;
        }
        EntityAssemblyState state = access.kinetic_assembly$assemblyState();
        if (state != null && state.active()) {
            return state.id();
        }
        Vec3 plotPosition = access.kinetic_assembly$plotPosition();
        return plotPosition == null ? null : assemblyAtPlotPosition(level, plotPosition);
    }

    private static boolean hasUpwardMotion(Entity entity) {
        return entity.getDeltaMovement().y > EPSILON;
    }

    private static boolean finite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static boolean finite(PhysicsVector vector) {
        return Double.isFinite(vector.x()) && Double.isFinite(vector.y()) && Double.isFinite(vector.z());
    }

    private static final class AssemblyScaffoldingCollisionContext extends EntityCollisionContext {
        private AssemblyScaffoldingCollisionContext(Entity entity) {
            super(entity);
        }

        @Override
        public boolean isAbove(VoxelShape shape, BlockPos pos, boolean defaultValue) {
            return false;
        }
    }

    public record CarryResult(Vec3 motion, @Nullable EntityAssemblyState state) {
        static final CarryResult NONE = new CarryResult(Vec3.ZERO, null);

        public CarryResult {
            Objects.requireNonNull(motion, "motion");
        }
    }

    public record CollisionResult(
            @Nullable EntityAssemblyState state,
            boolean onGround,
            boolean horizontalCollision,
            Vec3 motion,
            Vec3 inheritedMotion,
            Map<AssemblyId, FirstCollisionInfo> firstCollisions
    ) {
        public CollisionResult {
            Objects.requireNonNull(motion, "motion");
            Objects.requireNonNull(inheritedMotion, "inheritedMotion");
            firstCollisions = Map.copyOf(firstCollisions);
        }
    }

    public record FirstCollisionInfo(
            AssemblyId id,
            PhysicsVector localLocation,
            Vec3 worldDirection,
            boolean horizontal,
            Vec3 pointVelocity,
            AssemblyCollisionBlock block
    ) {
        public FirstCollisionInfo {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(localLocation, "localLocation");
            Objects.requireNonNull(worldDirection, "worldDirection");
            Objects.requireNonNull(pointVelocity, "pointVelocity");
            Objects.requireNonNull(block, "block");
        }

        public BlockState blockState() {
            return block.blockState();
        }
    }

    private record LocalCollisionPass(
            Vec3 motion,
            Vec3 inheritedMotion,
            @Nullable EntityAssemblyState state,
            boolean onGround,
            boolean horizontalCollision,
            Map<AssemblyId, FirstCollisionInfo> firstCollisions
    ) {
        private LocalCollisionPass {
            Objects.requireNonNull(motion, "motion");
            Objects.requireNonNull(inheritedMotion, "inheritedMotion");
            firstCollisions = Map.copyOf(firstCollisions);
        }
    }

    private record SubstepCarry(AABB box, Vec3 motion) {
        private SubstepCarry {
            Objects.requireNonNull(box, "box");
            Objects.requireNonNull(motion, "motion");
        }
    }

    private record LocalStepResult(
            Vec3 motion,
            @Nullable EntityAssemblyState state,
            boolean horizontalCollision,
            @Nullable FirstCollisionInfo firstCollision
    ) {
        private LocalStepResult {
            Objects.requireNonNull(motion, "motion");
        }
    }

    private record LocalPenetration(AssemblyCollisionTarget target, AssemblyCollisionBlock block, Vec3 worldMtv) {
        private LocalPenetration {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(worldMtv, "worldMtv");
        }
    }

    private record AxisOffset(int x, int y, int z) {
    }

    private record OrientedBox(Vec3 center, Vec3 halfExtents, Vec3 axisX, Vec3 axisY, Vec3 axisZ) {
        private OrientedBox {
            Objects.requireNonNull(center, "center");
            Objects.requireNonNull(halfExtents, "halfExtents");
            Objects.requireNonNull(axisX, "axisX");
            Objects.requireNonNull(axisY, "axisY");
            Objects.requireNonNull(axisZ, "axisZ");
        }
    }

    private record Projection(double min, double max) {
    }

    private record Candidate(AssemblyCollisionTarget target, AABB worldBox, double score) {
        private Candidate {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(worldBox, "worldBox");
        }
    }
}
