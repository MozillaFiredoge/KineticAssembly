package com.firedoge.kineticassembly.mixin.network;

import javax.annotation.Nullable;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityBridge;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityCollisionAccess;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityKicking;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityPacketAccess;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityPositioning;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPlotProjection;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyVehicleRiding;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyVectors;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerAssemblyEntityMixin {
    @Shadow
    private ClientLevel level;

    @Unique
    @Nullable
    private Entity kinetic_assembly$localPlayerAssemblyPassengerRemovalVehicle;

    @Inject(method = "handleSetEntityPassengersPacket", at = @At("HEAD"))
    private void kinetic_assembly$debugAssemblyPassengersBefore(ClientboundSetPassengersPacket packet, CallbackInfo ci) {
        kinetic_assembly$captureLocalPlayerAssemblyPassengerRemoval(packet);
        kinetic_assembly$debugAssemblyPassengers("client-passengers-before", packet);
    }

    @Inject(method = "handleSetEntityPassengersPacket", at = @At("TAIL"))
    private void kinetic_assembly$debugAssemblyPassengersAfter(ClientboundSetPassengersPacket packet, CallbackInfo ci) {
        kinetic_assembly$syncLocalPlayerAssemblyPassenger(packet);
        kinetic_assembly$syncLocalPlayerAssemblyPassengerRemoval();
        kinetic_assembly$debugAssemblyPassengers("client-passengers-after", packet);
    }

    @Inject(method = "handleRemoveEntities", at = @At("HEAD"))
    private void kinetic_assembly$clearLocalPlayerAssemblyPassengerOnVehicleRemove(
            ClientboundRemoveEntitiesPacket packet,
            CallbackInfo ci
    ) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        Entity vehicle = AssemblyVehicleRiding.authoritativeClientVehicle(player);
        if (vehicle == null) {
            return;
        }

        for (int removedId : packet.getEntityIds()) {
            if (removedId == vehicle.getId()) {
                AssemblyVehicleRiding.clearAuthoritativeClientVehicle(player, vehicle);
                return;
            }
        }
    }

    @Inject(method = "handleAddEntity", at = @At("HEAD"))
    private void kinetic_assembly$debugAttachedAddEntityPacket(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        if (!AssemblyEntityBridge.isDebugAttachedEntityType(packet.getType())) {
            return;
        }

        Vec3 packetPosition = new Vec3(packet.getX(), packet.getY(), packet.getZ());
        BlockPos packetBlock = BlockPos.containing(packetPosition);
        boolean directProjection = kinetic_assembly$projectionForPlotPosition(packetPosition) != null;
        boolean adjacentProjection = AssemblyEntityBridge.plotProjectionAtOrAdjacent(level, packetBlock).isPresent();
        KineticAssembly.LOGGER.info(
                "[kinetic_assembly-attached] client-add-packet type={} id={} uuid={} packetPos={} packetBlock={} directProjection={} adjacentProjection={} data={}",
                EntityType.getKey(packet.getType()),
                packet.getId(),
                packet.getUUID(),
                packetPosition,
                packetBlock,
                directProjection,
                adjacentProjection,
                packet.getData()
        );
    }

    @Inject(method = "handleSetEntityData", at = @At("TAIL"))
    private void kinetic_assembly$debugAttachedEntityDataPacket(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
        Entity entity = level.getEntity(packet.id());
        if (entity == null || !AssemblyEntityBridge.isDebugAttachedEntity(entity)) {
            return;
        }

        AssemblyEntityBridge.debugAttachedEntity(
                "client-data-sync",
                level,
                entity,
                "values=" + packet.packedItems().size()
        );
    }

    @WrapOperation(
            method = "handleTeleportEntity",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;lerpTo(DDDFFI)V"
            )
    )
    private void kinetic_assembly$handleTeleportEntity(
            Entity entity,
            double x,
            double y,
            double z,
            float yRot,
            float xRot,
            int lerpSteps,
            Operation<Void> original,
            @Local(argsOnly = true) ClientboundTeleportEntityPacket packet
    ) {
        kinetic_assembly$lerpAssemblyEntity(
                entity,
                new Vec3(x, y, z),
                yRot,
                xRot,
                lerpSteps,
                packet instanceof AssemblyEntityPacketAccess access && access.kinetic_assembly$actuallyInAssembly(),
                original
        );
    }

    @WrapOperation(
            method = "handleMoveEntity",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;lerpTo(DDDFFI)V",
                    ordinal = 0
            )
    )
    private void kinetic_assembly$handleMoveEntity(
            Entity entity,
            double x,
            double y,
            double z,
            float yRot,
            float xRot,
            int lerpSteps,
            Operation<Void> original,
            @Local(argsOnly = true) ClientboundMoveEntityPacket packet
    ) {
        kinetic_assembly$lerpAssemblyEntity(
                entity,
                new Vec3(x, y, z),
                yRot,
                xRot,
                lerpSteps,
                packet instanceof AssemblyEntityPacketAccess access && access.kinetic_assembly$actuallyInAssembly(),
                original
        );
    }

    @Unique
    private void kinetic_assembly$lerpAssemblyEntity(
            Entity entity,
            Vec3 packetPosition,
            float yRot,
            float xRot,
            int lerpSteps,
            boolean actuallyInAssembly,
            Operation<Void> original
    ) {
        AssemblyEntityCollisionAccess access = (AssemblyEntityCollisionAccess) entity;
        AssemblyPlotProjection packetProjection = kinetic_assembly$projectionForPlotPosition(packetPosition);

        if (!actuallyInAssembly && packetProjection == null && kinetic_assembly$inPlotBounds(packetPosition)) {
            return;
        }

        if (!AssemblyEntityKicking.shouldKick(entity)) {
            if (packetProjection != null) {
                original.call(
                        entity,
                        packetPosition.x,
                        packetPosition.y,
                        packetPosition.z,
                        yRot,
                        xRot,
                        lerpSteps
                );
                access.kinetic_assembly$setPlotPosition(null);
                access.kinetic_assembly$setTrackingAssemblyId(null);
                return;
            }
            if (kinetic_assembly$projectionForPlotPosition(entity.position()) != null) {
                access.kinetic_assembly$setPlotPosition(null);
                access.kinetic_assembly$setTrackingAssemblyId(null);
                return;
            }
        }

        if (packetProjection != null && !actuallyInAssembly) {
            if (!(entity instanceof LivingEntity)) {
                Vec3 worldPosition = packetProjection.plotToWorld(packetPosition);
                access.kinetic_assembly$setTrackingAssemblyId(packetProjection.id());
                original.call(
                        entity,
                        worldPosition.x,
                        worldPosition.y,
                        worldPosition.z,
                        yRot,
                        xRot,
                        lerpSteps
                );
                return;
            }

            Vec3 plotPosition = access.kinetic_assembly$plotPosition();
            if (plotPosition == null) {
                access.kinetic_assembly$setPlotPosition(packetProjection.worldToPlot(entity.position()));
            } else {
                AssemblyPlotProjection existingProjection = kinetic_assembly$projectionForPlotPosition(plotPosition);
                if (existingProjection != null && !existingProjection.id().equals(packetProjection.id())) {
                    Vec3 worldPlotPosition = existingProjection.plotToWorld(plotPosition);
                    access.kinetic_assembly$setPlotPosition(packetProjection.worldToPlot(worldPlotPosition));
                }
            }

            access.kinetic_assembly$setTrackingAssemblyId(packetProjection.id());
            original.call(
                    entity,
                    packetPosition.x,
                    packetPosition.y,
                    packetPosition.z,
                    yRot,
                    xRot,
                    lerpSteps
            );
            access.kinetic_assembly$plotLerpTo(packetPosition, lerpSteps);
            return;
        }

        AssemblyPlotProjection existingProjection = kinetic_assembly$projectionForPlotPosition(entity.position());
        if (packetProjection != null && actuallyInAssembly && !kinetic_assembly$sameProjection(existingProjection, packetProjection)) {
            entity.setPos(packetProjection.worldToPlot(entity.position()));
        } else if (existingProjection != null && packetProjection == null) {
            entity.setPos(existingProjection.plotToWorld(entity.position()));
        }

        original.call(
                entity,
                packetPosition.x,
                packetPosition.y,
                packetPosition.z,
                yRot,
                xRot,
                lerpSteps
        );
        access.kinetic_assembly$setPlotPosition(null);
        access.kinetic_assembly$setTrackingAssemblyId(null);
    }

    @Inject(
            method = "handleMovePlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;)V",
                    ordinal = 0,
                    shift = At.Shift.BEFORE
            )
    )
    private void kinetic_assembly$projectAssemblyPlayerCorrection(
            ClientboundPlayerPositionPacket packet,
            CallbackInfo ci
    ) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        AssemblyPlotProjection projection = AssemblyEntityPositioning.containingProjection(player);
        if (projection == null) {
            return;
        }

        Vec3 plotPosition = player.position();
        Vec3 worldPosition = projection.plotToWorld(plotPosition);
        if (!AssemblyVectors.finite(worldPosition)) {
            return;
        }

        AssemblyEntityCollisionAccess access = (AssemblyEntityCollisionAccess) player;
        access.kinetic_assembly$setTrackingAssemblyId(projection.id());
        access.kinetic_assembly$setPlotPosition(plotPosition);
        player.setPos(worldPosition);
        AssemblyEntityPositioning.setOldPosNoMovement(player);
    }

    @Unique
    @Nullable
    private AssemblyPlotProjection kinetic_assembly$projectionForPlotPosition(Vec3 position) {
        return AssemblyContainers.container(level)
                .flatMap(container -> container.plotProjection(BlockPos.containing(position)))
                .orElse(null);
    }

    @Unique
    private boolean kinetic_assembly$inPlotBounds(Vec3 position) {
        return AssemblyContainers.container(level)
                .map(container -> container.inPlotBounds(new ChunkPos(BlockPos.containing(position))))
                .orElse(false);
    }

    @Unique
    private void kinetic_assembly$debugAssemblyPassengers(String phase, ClientboundSetPassengersPacket packet) {
        LocalPlayer player = Minecraft.getInstance().player;
        boolean containsLocalPlayer = player != null
                && java.util.Arrays.stream(packet.getPassengers()).anyMatch(id -> id == player.getId());
        Entity vehicle = level.getEntity(packet.getVehicle());
        if (vehicle == null) {
            if (containsLocalPlayer) {
                KineticAssembly.LOGGER.info(
                        "[kinetic_assembly-ride] {} vehicle-missing vehicle={} passengers={} player={} playerVehicle={} playerPos={}",
                        phase,
                        packet.getVehicle(),
                        java.util.Arrays.toString(packet.getPassengers()),
                        player.getId(),
                        player.getVehicle() == null ? null : player.getVehicle().getId(),
                        player.position()
                );
            }
            return;
        }
        AssemblyPlotProjection projection = AssemblyEntityPositioning.containingProjection(vehicle);
        if (projection == null && !containsLocalPlayer) {
            return;
        }

        Entity playerVehicle = player == null ? null : player.getVehicle();
        KineticAssembly.LOGGER.info(
                "[kinetic_assembly-ride] {} vehicle={} type={} passengers={} player={} playerVehicle={} vehiclePos={} projection={} playerPos={}",
                phase,
                packet.getVehicle(),
                EntityType.getKey(vehicle.getType()),
                java.util.Arrays.toString(packet.getPassengers()),
                player == null ? null : player.getId(),
                playerVehicle == null ? null : playerVehicle.getId(),
                vehicle.position(),
                projection == null ? null : projection.id(),
                player == null ? null : player.position()
        );
    }

    @Unique
    private void kinetic_assembly$captureLocalPlayerAssemblyPassengerRemoval(ClientboundSetPassengersPacket packet) {
        kinetic_assembly$localPlayerAssemblyPassengerRemovalVehicle = null;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null
                || java.util.Arrays.stream(packet.getPassengers()).anyMatch(id -> id == player.getId())) {
            return;
        }

        Entity vehicle = level.getEntity(packet.getVehicle());
        if (vehicle == null || !vehicle.hasIndirectPassenger(player)) {
            return;
        }
        if (AssemblyVehicleRiding.vehicleProjection(vehicle) == null) {
            return;
        }

        AssemblyVehicleRiding.clearAuthoritativeClientVehicle(player, vehicle);
        kinetic_assembly$localPlayerAssemblyPassengerRemovalVehicle = vehicle;
    }

    @Unique
    private void kinetic_assembly$syncLocalPlayerAssemblyPassenger(ClientboundSetPassengersPacket packet) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null
                || java.util.Arrays.stream(packet.getPassengers()).noneMatch(id -> id == player.getId())) {
            return;
        }

        Entity vehicle = level.getEntity(packet.getVehicle());
        if (vehicle == null) {
            return;
        }

        AssemblyPlotProjection projection = AssemblyVehicleRiding.vehicleProjection(vehicle);
        if (projection == null) {
            return;
        }

        AssemblyEntityCollisionAccess access = (AssemblyEntityCollisionAccess) player;
        AssemblyVehicleRiding.beginPassengerRide(player, access, vehicle);
        AssemblyVehicleRiding.rememberAuthoritativeClientVehicle(player, vehicle);
        AssemblyEntityPositioning.setOldPosNoMovement(player);
    }

    @Unique
    private void kinetic_assembly$syncLocalPlayerAssemblyPassengerRemoval() {
        Entity vehicle = kinetic_assembly$localPlayerAssemblyPassengerRemovalVehicle;
        kinetic_assembly$localPlayerAssemblyPassengerRemovalVehicle = null;
        if (vehicle == null) {
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || vehicle.hasIndirectPassenger(player)) {
            return;
        }

        AssemblyVehicleRiding.markPassengerDismounted(player, (AssemblyEntityCollisionAccess) player, vehicle);
        AssemblyVehicleRiding.clearAuthoritativeClientVehicle(player, vehicle);
        AssemblyEntityPositioning.setOldPosNoMovement(player);
    }

    @Unique
    private static boolean kinetic_assembly$sameProjection(
            @Nullable AssemblyPlotProjection first,
            @Nullable AssemblyPlotProjection second
    ) {
        return first != null && second != null && first.id().equals(second.id());
    }
}
