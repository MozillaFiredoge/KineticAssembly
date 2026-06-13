package com.firedoge.kineticassembly.mixin.chunk;

import java.util.Set;

import com.firedoge.kineticassembly.minecraft.assembly.ServerAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityBridge;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityKicking;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class ChunkMapTrackedEntityMixin {
    @Shadow
    @Final
    private ServerEntity serverEntity;

    @Shadow
    @Final
    private Entity entity;

    @Shadow
    @Final
    private Set<ServerPlayerConnection> seenBy;

    @Shadow
    public abstract void removePlayer(ServerPlayer player);

    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$updatePlayerTrackingRetainedPlotEntity(ServerPlayer player, CallbackInfo ci) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }

        ServerAssemblyContainer container = AssemblyContainers.server(level).orElse(null);
        BlockPos trackingPlotBlock = kinetic_assembly$trackingPlotBlock(level, container);
        if (trackingPlotBlock == null) {
            return;
        }

        boolean shouldTrack = container != null
                && entity.broadcastToPlayer(player)
                && container.trackingSystem().playersTracking(new ChunkPos(trackingPlotBlock)).contains(player);

        if (shouldTrack) {
            if (seenBy.add(player.connection)) {
                AssemblyEntityBridge.debugAttachedEntity(
                        "chunkmap-track",
                        level,
                        entity,
                        "action=add player=" + player.getScoreboardName() + " trackingPlotBlock=" + trackingPlotBlock
                );
                serverEntity.addPairing(player);
            }
        } else {
            if (seenBy.contains(player.connection)) {
                AssemblyEntityBridge.debugAttachedEntity(
                        "chunkmap-track",
                        level,
                        entity,
                        "action=remove player=" + player.getScoreboardName() + " trackingPlotBlock=" + trackingPlotBlock
                );
            }
            removePlayer(player);
        }
        ci.cancel();
    }

    private BlockPos kinetic_assembly$trackingPlotBlock(ServerLevel level, ServerAssemblyContainer container) {
        if (container == null) {
            return null;
        }
        BlockPos attachedTrackingPlotBlock = AssemblyEntityBridge.attachedTrackingPlotBlock(level, entity).orElse(null);
        if (attachedTrackingPlotBlock != null) {
            return attachedTrackingPlotBlock;
        }
        if (AssemblyEntityKicking.shouldKick(entity)) {
            return null;
        }

        BlockPos plotPos = BlockPos.containing(entity.position());
        if (container.assemblyAtPlotBlock(plotPos).isPresent()) {
            return plotPos;
        }
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = plotPos.relative(direction);
            if (container.assemblyAtPlotBlock(adjacent).isPresent()) {
                return adjacent;
            }
        }
        return null;
    }
}
