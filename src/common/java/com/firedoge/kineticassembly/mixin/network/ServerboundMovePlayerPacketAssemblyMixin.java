package com.firedoge.kineticassembly.mixin.network;

import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.minecraft.assembly.EntityAssemblyState;
import com.firedoge.kineticassembly.minecraft.assembly.PhysicsAssembly;
import com.firedoge.kineticassembly.minecraft.assembly.ServerAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyCoordinateSpace;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityCollisionAccess;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyMovePlayerPacketAccess;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPoseFrame;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyTransform;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyVerticalTrace;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyVectors;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerboundMovePlayerPacket.class)
public abstract class ServerboundMovePlayerPacketAssemblyMixin implements AssemblyMovePlayerPacketAccess {
    @Mutable
    @Shadow
    @Final
    protected double x;

    @Mutable
    @Shadow
    @Final
    protected double y;

    @Mutable
    @Shadow
    @Final
    protected double z;

    @Shadow
    @Final
    protected boolean hasPos;

    @Override
    public void kinetic_assembly$applyAssemblyMove(ServerPlayer player) {
        if (!hasPos) {
            return;
        }
        Vec3 packetPosition = new Vec3(x, y, z);

        ServerAssemblyContainer container = AssemblyContainers.container(player.level())
                .filter(ServerAssemblyContainer.class::isInstance)
                .map(ServerAssemblyContainer.class::cast)
                .orElse(null);
        if (container == null) {
            if (kinetic_assembly$hasPlotMoveState(player)) {
                kinetic_assembly$rejectAssemblyMove(player, packetPosition, "no-container-with-plot-state");
                return;
            }
            kinetic_assembly$trace(player, "clear", packetPosition, null, "no-container");
            kinetic_assembly$clearPlotMoveState(player);
            return;
        }

        BlockPos plotBlockPos = BlockPos.containing(x, y, z);
        if (container.isEmpty()) {
            if (container.inPlotBounds(new ChunkPos(plotBlockPos)) || kinetic_assembly$hasPlotMoveState(player)) {
                kinetic_assembly$rejectAssemblyMove(player, packetPosition, "empty-container-plot-packet");
                return;
            }
            kinetic_assembly$trace(player, "clear", packetPosition, null, "empty-container-world-packet");
            kinetic_assembly$clearPlotMoveState(player);
            return;
        }

        PhysicsAssembly assembly = kinetic_assembly$assemblyAtOrAdjacentPlotBlock(container, plotBlockPos);
        if (assembly == null) {
            if (container.inPlotBounds(new ChunkPos(plotBlockPos))) {
                kinetic_assembly$rejectAssemblyMove(player, packetPosition, "packet-inside-empty-plot");
                return;
            }
            kinetic_assembly$trace(player, "clear", packetPosition, null, "world-packet-outside-plot");
            kinetic_assembly$clearPlotMoveState(player);
            return;
        }

        if (player instanceof AssemblyEntityCollisionAccess access) {
            access.kinetic_assembly$setTrackingAssemblyId(assembly.id());
            access.kinetic_assembly$setPlotPosition(packetPosition);
        }

        AssemblyPoseFrame frame = container.trackingSystem().poseFrame(assembly).orElse(null);
        if (frame == null) {
            kinetic_assembly$rejectAssemblyMove(player, packetPosition, "missing-pose-frame");
            return;
        }

        PhysicsVector bodyLocalPosition = AssemblyCoordinateSpace.plotToBodyLocal(assembly, packetPosition);
        PhysicsVector worldPosition = AssemblyTransform.from(frame.currentPose()).localToWorld(bodyLocalPosition);
        if (!AssemblyVectors.finite(worldPosition)) {
            kinetic_assembly$rejectAssemblyMove(player, packetPosition, "non-finite-world-position");
            return;
        }

        x = worldPosition.x();
        y = worldPosition.y();
        z = worldPosition.z();
        Vec3 convertedWorld = new Vec3(worldPosition.x(), worldPosition.y(), worldPosition.z());
        if (player instanceof AssemblyEntityCollisionAccess access) {
            PhysicsVector localCenterPosition = bodyLocalPosition;
            if (player.getBbHeight() > 0.0F) {
                PhysicsVector localHalfHeight = AssemblyTransform.from(frame.currentPose()).worldDirectionToLocal(
                        new PhysicsVector(0.0D, player.getBbHeight() * 0.5D, 0.0D)
                );
                localCenterPosition = new PhysicsVector(
                        bodyLocalPosition.x() + localHalfHeight.x(),
                        bodyLocalPosition.y() + localHalfHeight.y(),
                        bodyLocalPosition.z() + localHalfHeight.z()
                );
            }
            access.kinetic_assembly$setAssemblyState(EntityAssemblyState.localPacket(frame, localCenterPosition, bodyLocalPosition, 2));
        }
        kinetic_assembly$trace(player, "convert", packetPosition, convertedWorld, "plot-to-world assembly=" + assembly.id().value());
    }

    private static void kinetic_assembly$clearAssemblyTracking(ServerPlayer player) {
        if (player instanceof AssemblyEntityCollisionAccess access) {
            access.kinetic_assembly$setTrackingAssemblyId(null);
            access.kinetic_assembly$setPlotPosition(null);
        }
    }

    private static void kinetic_assembly$clearPlotMoveState(ServerPlayer player) {
        if (player instanceof AssemblyEntityCollisionAccess access) {
            EntityAssemblyState state = access.kinetic_assembly$assemblyState();
            access.kinetic_assembly$setPlotPosition(null);
            if (state != null && state.trackingMode() == EntityAssemblyState.TrackingMode.PACKET_LOCAL) {
                access.kinetic_assembly$setAssemblyState(null);
                access.kinetic_assembly$setTrackingAssemblyId(null);
            }
        }
    }

    private static boolean kinetic_assembly$hasPlotMoveState(ServerPlayer player) {
        if (!(player instanceof AssemblyEntityCollisionAccess access)) {
            return false;
        }
        EntityAssemblyState state = access.kinetic_assembly$assemblyState();
        return access.kinetic_assembly$plotPosition() != null
                || state != null && state.trackingMode() == EntityAssemblyState.TrackingMode.PACKET_LOCAL;
    }

    private void kinetic_assembly$rejectAssemblyMove(ServerPlayer player, Vec3 packetPosition, String reason) {
        kinetic_assembly$trace(player, "reject", packetPosition, player.position(), reason);
        x = player.getX();
        y = player.getY();
        z = player.getZ();
        kinetic_assembly$clearAssemblyTracking(player);
    }

    private static void kinetic_assembly$trace(ServerPlayer player, String action, Vec3 packetPosition, Vec3 worldPosition, String detail) {
        if (!(player instanceof AssemblyEntityCollisionAccess access)) {
            AssemblyVerticalTrace.serverPacket(player, action, packetPosition, worldPosition, null, null, null, detail);
            return;
        }
        AssemblyVerticalTrace.serverPacket(
                player,
                action,
                packetPosition,
                worldPosition,
                access.kinetic_assembly$assemblyState(),
                access.kinetic_assembly$trackingAssemblyId(),
                access.kinetic_assembly$plotPosition(),
                detail
        );
    }

    private static PhysicsAssembly kinetic_assembly$assemblyAtOrAdjacentPlotBlock(ServerAssemblyContainer container, BlockPos plotBlockPos) {
        PhysicsAssembly direct = container.assemblyAtPlotBlock(plotBlockPos).orElse(null);
        if (direct != null) {
            return direct;
        }
        for (Direction direction : Direction.values()) {
            PhysicsAssembly adjacent = container.assemblyAtPlotBlock(plotBlockPos.relative(direction)).orElse(null);
            if (adjacent != null) {
                return adjacent;
            }
        }
        return null;
    }
}
