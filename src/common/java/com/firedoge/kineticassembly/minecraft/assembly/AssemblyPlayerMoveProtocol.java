package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.network.ServerboundAssemblyMovePayload;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class AssemblyPlayerMoveProtocol {
    private static final long MAX_PENDING_AGE_TICKS = 3L;
    private static final int SUPPORT_GRACE_TICKS = 2;
    private static final Map<UUID, PendingMove> PENDING_MOVES = new ConcurrentHashMap<>();

    private AssemblyPlayerMoveProtocol() {
    }

    public static void receive(ServerPlayer player, ServerboundAssemblyMovePayload payload) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(payload, "payload");
        if (payload.id() == null) {
            PENDING_MOVES.remove(player.getUUID());
            if (player instanceof AssemblyEntityCollisionAccess access) {
                access.kinetic_assembly$setTrackingAssemblyId(null);
                access.kinetic_assembly$setPlotPosition(null);
            }
            return;
        }

        PENDING_MOVES.put(
                player.getUUID(),
                new PendingMove(payload.id(), payload.localPosition(), player.level().getGameTime())
        );
    }

    public static boolean hasPending(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        return PENDING_MOVES.containsKey(player.getUUID());
    }

    @Nullable
    public static ResolvedMove resolve(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        PendingMove pending = PENDING_MOVES.get(player.getUUID());
        if (pending == null) {
            return null;
        }
        if (player.level().getGameTime() - pending.gameTime() > MAX_PENDING_AGE_TICKS) {
            PENDING_MOVES.remove(player.getUUID(), pending);
            return null;
        }

        AssemblyCollisionTarget target = forcedTarget(player, pending.id()).orElse(null);
        if (target == null) {
            return null;
        }

        PhysicsVector world = target.transform().localToWorld(pending.localPosition());
        if (!Double.isFinite(world.x()) || !Double.isFinite(world.y()) || !Double.isFinite(world.z())) {
            return null;
        }
        return new ResolvedMove(
                target.id(),
                target.poseFrame(),
                pending.localPosition(),
                new Vec3(world.x(), world.y(), world.z())
        );
    }

    public static void applySupport(ServerPlayer player, ResolvedMove move) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(move, "move");
        if (player instanceof AssemblyEntityCollisionAccess access) {
            PhysicsVector localFeet = move.localPosition();
            PhysicsVector localCenter = localFeet;
            if (player.getBbHeight() > 0.0F) {
                PhysicsVector localHalfHeight = AssemblyTransform.from(move.frame().currentPose()).worldDirectionToLocal(
                        new PhysicsVector(0.0D, player.getBbHeight() * 0.5D, 0.0D)
                );
                localCenter = new PhysicsVector(
                        localFeet.x() + localHalfHeight.x(),
                        localFeet.y() + localHalfHeight.y(),
                        localFeet.z() + localHalfHeight.z()
                );
            }
            access.kinetic_assembly$setTrackingAssemblyId(move.id());
            access.kinetic_assembly$setAssemblyState(EntityAssemblyState.localPacket(
                    move.frame(),
                    localCenter,
                    localFeet,
                    SUPPORT_GRACE_TICKS
            ));
        }
    }

    private static java.util.Optional<AssemblyCollisionTarget> forcedTarget(ServerPlayer player, AssemblyId id) {
        AssemblyContainer container = AssemblyContainers.container(player.level()).orElse(null);
        if (container == null || container.isEmpty()) {
            return java.util.Optional.empty();
        }
        return container.collisionTargets(new AABB(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D), id).stream()
                .filter(target -> target.id().equals(id))
                .findFirst();
    }

    public record ResolvedMove(
            AssemblyId id,
            AssemblyPoseFrame frame,
            PhysicsVector localPosition,
            Vec3 worldPosition
    ) {
        public ResolvedMove {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(localPosition, "localPosition");
            Objects.requireNonNull(worldPosition, "worldPosition");
        }
    }

    private record PendingMove(AssemblyId id, PhysicsVector localPosition, long gameTime) {
        private PendingMove {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(localPosition, "localPosition");
        }
    }
}
