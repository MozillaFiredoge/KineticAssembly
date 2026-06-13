package com.firedoge.kineticassembly.mixin.network;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.minecraft.assembly.EntityAssemblyState;
import com.firedoge.kineticassembly.minecraft.assembly.ServerAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityCollisionAccess;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityBridge;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyMovePlayerPacketAccess;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyManager;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyVehicleRiding;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyVerticalTrace;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerAssemblyMoveMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(
            method = "handleMovePlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void kinetic_assembly$applyAssemblyMove(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        ((AssemblyMovePlayerPacketAccess) packet).kinetic_assembly$applyAssemblyMove(player);
    }

    @Inject(
            method = "handleMoveVehicle",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                    shift = At.Shift.AFTER
            ),
            cancellable = true
    )
    private void kinetic_assembly$ignoreRetainedAssemblyMinecartMove(
            ServerboundMoveVehiclePacket packet,
            CallbackInfo ci
    ) {
        Entity rootVehicle = player.getRootVehicle();
        if (rootVehicle instanceof AbstractMinecart
                && AssemblyVehicleRiding.vehicleProjection(rootVehicle) != null) {
            ci.cancel();
        }
    }

    @WrapOperation(
            method = "handlePlayerInput",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;setPlayerInput(FFZZ)V"
            )
    )
    private void kinetic_assembly$ignoreRetainedAssemblyMinecartInputSneak(
            ServerPlayer instance,
            float strafe,
            float forward,
            boolean jumping,
            boolean sneaking,
            Operation<Void> original
    ) {
        Entity vehicle = instance.getVehicle();
        if (vehicle instanceof AbstractMinecart
                && AssemblyVehicleRiding.vehicleProjection(vehicle) != null) {
            if (sneaking) {
                KineticAssembly.LOGGER.info(
                        "[kinetic_assembly-ride] server-player-input-stop-retained-minecart player={} vehicle={} strafe={} forward={} jumping={} sneaking={} shiftBefore={} passengers={}",
                        instance.getId(),
                        vehicle.getId(),
                        strafe,
                        forward,
                        jumping,
                        sneaking,
                        instance.isShiftKeyDown(),
                        vehicle.getPassengers().stream().map(Entity::getId).toList()
                );
                instance.stopRiding();
                instance.setShiftKeyDown(false);
            }
            original.call(instance, strafe, forward, jumping, false);
            return;
        }

        original.call(instance, strafe, forward, jumping, sneaking);
    }

    @Inject(method = "handlePlayerCommand", at = @At("TAIL"))
    private void kinetic_assembly$stopRetainedAssemblyVehicleOnShift(
            ServerboundPlayerCommandPacket packet,
            CallbackInfo ci
    ) {
        if (packet.getAction() != ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY) {
            return;
        }

        kinetic_assembly$stopRetainedAssemblyMinecart();
    }

    @Unique
    private void kinetic_assembly$stopRetainedAssemblyMinecart() {
        Entity vehicle = player.getVehicle();
        if (!(vehicle instanceof AbstractMinecart)
                || AssemblyVehicleRiding.vehicleProjection(vehicle) == null) {
            return;
        }

        KineticAssembly.LOGGER.info(
                "[kinetic_assembly-ride] server-player-command-stop-retained-minecart player={} vehicle={} shiftBefore={} passengers={}",
                player.getId(),
                vehicle.getId(),
                player.isShiftKeyDown(),
                vehicle.getPassengers().stream().map(Entity::getId).toList()
        );
        player.stopRiding();
        player.setShiftKeyDown(false);
    }

    @Redirect(
            method = "handleInteract",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;blockPosition()Lnet/minecraft/core/BlockPos;"
            )
    )
    private BlockPos kinetic_assembly$getAssemblyEntityInteractBlockPosition(Entity entity) {
        AABB worldBounds = AssemblyEntityBridge.plotEntityWorldBounds(player.serverLevel(), entity)
                .orElse(null);
        return worldBounds != null ? BlockPos.containing(worldBounds.getCenter()) : entity.blockPosition();
    }

    @Redirect(
            method = "handleInteract",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"
            )
    )
    private AABB kinetic_assembly$getAssemblyEntityInteractBounds(Entity entity) {
        return AssemblyEntityBridge.plotEntityWorldBounds(player.serverLevel(), entity)
                .orElseGet(entity::getBoundingBox);
    }

    @WrapOperation(
            method = "handleMovePlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;isChangingDimension()Z"
            )
    )
    private boolean kinetic_assembly$skipMovedWronglyWhileTracking(
            ServerPlayer instance,
            Operation<Boolean> original
    ) {
        if (instance instanceof AssemblyEntityCollisionAccess access
                && kinetic_assembly$isPacketLocal(access)) {
            AssemblyVerticalTrace.serverMoveCheck(
                    instance,
                    true,
                    access.kinetic_assembly$assemblyState(),
                    access.kinetic_assembly$trackingAssemblyId(),
                    access.kinetic_assembly$plotPosition()
            );
            return true;
        }
        if (instance instanceof AssemblyEntityCollisionAccess access) {
            AssemblyVerticalTrace.serverMoveCheck(
                    instance,
                    false,
                    access.kinetic_assembly$assemblyState(),
                    access.kinetic_assembly$trackingAssemblyId(),
                    access.kinetic_assembly$plotPosition()
            );
        }
        return original.call(instance);
    }

    @WrapOperation(
            method = "handleUseItemOn",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;canInteractWithBlock(Lnet/minecraft/core/BlockPos;D)Z"
            )
    )
    private boolean kinetic_assembly$allowUseItemOnReachablePlotBlock(
            ServerPlayer instance,
            BlockPos pos,
            double distance,
            Operation<Boolean> original
    ) {
        boolean vanilla = original.call(instance, pos, distance);
        if (vanilla) {
            return true;
        }
        ServerLevel level = instance.serverLevel();
        ServerAssemblyContainer container = AssemblyContainers.server(level).orElse(null);
        if (container == null || !container.inPlotBounds(new ChunkPos(pos))) {
            return false;
        }

        BlockPos reachablePlotPos = kinetic_assembly$reachablePlotPos(container, pos);
        if (reachablePlotPos == null) {
            KineticAssembly.LOGGER.info(
                    "[kinetic_assembly-attached] server-use-item-on-range result=false reason=not-plot-block pos={} distance={}",
                    pos,
                    distance
            );
            return false;
        }

        boolean reachable = AssemblyManager.INSTANCE.playerCanReachPlotBlock(level, instance, reachablePlotPos, distance);
        KineticAssembly.LOGGER.info(
                "[kinetic_assembly-attached] server-use-item-on-range result={} pos={} reachablePlotPos={} distance={}",
                reachable,
                pos,
                reachablePlotPos,
                distance
        );
        return reachable;
    }

    @Unique
    private static boolean kinetic_assembly$isPacketLocal(AssemblyEntityCollisionAccess access) {
        EntityAssemblyState state = access.kinetic_assembly$assemblyState();
        return access.kinetic_assembly$plotPosition() != null
                || state != null && state.trackingMode() == EntityAssemblyState.TrackingMode.PACKET_LOCAL;
    }

    @Unique
    private static BlockPos kinetic_assembly$reachablePlotPos(ServerAssemblyContainer container, BlockPos pos) {
        if (container.assemblyAtPlotBlock(pos).isPresent()) {
            return pos;
        }
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = pos.relative(direction);
            if (container.assemblyAtPlotBlock(adjacent).isPresent()) {
                return adjacent;
            }
        }
        return null;
    }
}
