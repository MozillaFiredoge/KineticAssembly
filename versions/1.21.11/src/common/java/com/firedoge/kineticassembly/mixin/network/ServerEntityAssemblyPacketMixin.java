package com.firedoge.kineticassembly.mixin.network;

import javax.annotation.Nullable;

import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityBridge;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityCollisionAccess;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityPacketAccess;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyId;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPlotProjection;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyVectors;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerEntity.class)
public abstract class ServerEntityAssemblyPacketMixin {
    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    @Final
    private Entity entity;

    @Unique
    @Nullable
    private Vec3 kinetic_assembly$oldWorldPosition;

    @Unique
    private boolean kinetic_assembly$actuallyInAssembly;

    @Inject(
            method = "sendChanges",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;trackingPosition()Lnet/minecraft/world/phys/Vec3;",
                    ordinal = 1,
                    shift = At.Shift.BEFORE
            )
    )
    private void kinetic_assembly$preAssemblyPacketPosition(CallbackInfo ci) {
        kinetic_assembly$oldWorldPosition = null;
        Vec3 worldPosition = entity.position();
        kinetic_assembly$actuallyInAssembly = AssemblyEntityBridge
                .plotProjectionAtOrAdjacent(level, BlockPos.containing(worldPosition))
                .isPresent();

        if (!(entity instanceof AssemblyEntityCollisionAccess access)) {
            return;
        }

        Vec3 attachedPlotPosition = AssemblyEntityBridge.attachedPlotPosition(level, entity).orElse(null);
        if (attachedPlotPosition != null && AssemblyVectors.finite(attachedPlotPosition)) {
            AssemblyEntityBridge.debugAttachedEntity(
                    "server-packet",
                    level,
                    entity,
                    "mode=attached-plot-position packetPos=" + attachedPlotPosition + " worldPosition=" + worldPosition
            );
            kinetic_assembly$oldWorldPosition = worldPosition;
            kinetic_assembly$actuallyInAssembly = true;
            access.kinetic_assembly$setPosField(attachedPlotPosition);
            return;
        }

        AssemblyId trackingId = access.kinetic_assembly$trackingAssemblyId();
        if (trackingId == null) {
            return;
        }

        AssemblyPlotProjection projection = AssemblyContainers.server(level)
                .flatMap(container -> container.plotProjection(trackingId))
                .orElse(null);
        if (projection == null) {
            return;
        }

        Vec3 plotPosition = projection.previousWorldToPlot(worldPosition);
        if (!AssemblyVectors.finite(plotPosition)) {
            return;
        }

        kinetic_assembly$oldWorldPosition = worldPosition;
        access.kinetic_assembly$setPosField(plotPosition);
    }

    @Inject(method = "sendChanges", at = @At("RETURN"))
    private void kinetic_assembly$postAssemblyPacketPosition(CallbackInfo ci) {
        if (kinetic_assembly$oldWorldPosition != null && entity instanceof AssemblyEntityCollisionAccess access) {
            access.kinetic_assembly$setPosField(kinetic_assembly$oldWorldPosition);
            kinetic_assembly$oldWorldPosition = null;
        }
        kinetic_assembly$actuallyInAssembly = false;
    }

    @WrapOperation(
            method = "sendChanges",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerEntity$Synchronizer;sendToTrackingPlayers(Lnet/minecraft/network/protocol/Packet;)V"
            )
    )
    private void kinetic_assembly$markAssemblyEntityPacket(
            ServerEntity.Synchronizer synchronizer,
            Packet<? super ClientGamePacketListener> packet,
            Operation<Void> original
    ) {
        if (kinetic_assembly$actuallyInAssembly && packet instanceof AssemblyEntityPacketAccess access) {
            access.kinetic_assembly$setActuallyInAssembly(true);
        }
        original.call(synchronizer, packet);
    }
}
