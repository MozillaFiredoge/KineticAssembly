package com.firedoge.kineticassembly.mixin.chunk;

import java.util.List;

import com.firedoge.kineticassembly.minecraft.assembly.PlotChunkHolder;
import com.firedoge.kineticassembly.minecraft.assembly.ServerAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.Visibility;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Shadow
    @Final
    private ServerLevel level;

    @Inject(method = "getPlayers", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$getPlayersTrackingPlot(ChunkPos chunkPos, boolean boundaryOnly, CallbackInfoReturnable<List<ServerPlayer>> cir) {
        ServerAssemblyContainer container = kinetic_assembly$plotContainer();
        if (container != null && container.plotChunkHolder(chunkPos).isPresent()) {
            cir.setReturnValue(container.trackingSystem().playersTracking(chunkPos));
        }
    }

    @Inject(method = "saveChunkIfNeeded", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$skipSavingPlotChunks(ChunkHolder chunkHolder, CallbackInfoReturnable<Boolean> cir) {
        if (chunkHolder instanceof PlotChunkHolder) {
            cir.setReturnValue(false);
        }
    }

    @Redirect(
            method = "hasWork",
            at = @At(
                    value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap;isEmpty()Z",
                    ordinal = 1,
                    remap = false
            )
    )
    private boolean kinetic_assembly$hasNonPlotChunkWork(Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap) {
        return updatingChunkMap.values().stream().noneMatch(holder -> !(holder instanceof PlotChunkHolder));
    }

    @Inject(method = "isChunkTracked", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$isPlotChunkTracked(ServerPlayer player, int x, int z, CallbackInfoReturnable<Boolean> cir) {
        ChunkPos chunkPos = new ChunkPos(x, z);
        ServerAssemblyContainer container = kinetic_assembly$plotContainer();
        if (container != null && container.plotChunkHolder(chunkPos).isPresent()) {
            cir.setReturnValue(container.trackingSystem().playersTracking(chunkPos).contains(player));
        }
    }

    @Inject(method = "anyPlayerCloseEnoughForSpawning", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$anyPlotPlayerCloseEnoughForSpawning(ChunkPos chunkPos, CallbackInfoReturnable<Boolean> cir) {
        ServerAssemblyContainer container = kinetic_assembly$plotContainer();
        if (container != null && container.plotChunkHolder(chunkPos).isPresent()) {
            cir.setReturnValue(!container.trackingSystem().playersTracking(chunkPos).isEmpty());
        }
    }

    @Inject(method = "onFullChunkStatusChange", at = @At("TAIL"))
    private void kinetic_assembly$updateAssemblyHoldingChunkStatus(
            ChunkPos chunkPos,
            FullChunkStatus fullChunkStatus,
            CallbackInfo ci
    ) {
        ServerAssemblyContainer container = kinetic_assembly$plotContainer();
        if (container == null || container.inPlotBounds(chunkPos)) {
            return;
        }
        container.holdingChunkMap().updateChunkStatus(
                chunkPos,
                Visibility.fromFullChunkStatus(fullChunkStatus) != Visibility.HIDDEN
        );
    }

    private ServerAssemblyContainer kinetic_assembly$plotContainer() {
        return AssemblyContainers.server(level).orElse(null);
    }
}
