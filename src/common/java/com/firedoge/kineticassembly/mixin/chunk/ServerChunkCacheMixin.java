package com.firedoge.kineticassembly.mixin.chunk;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.firedoge.kineticassembly.minecraft.assembly.ServerAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {
    @Shadow
    @Final
    public ServerLevel level;

    @Unique
    private EmptyLevelChunk kinetic_assembly$emptyPlotChunk;

    @Inject(
            method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void kinetic_assembly$getPlotChunk(
            int x,
            int z,
            ChunkStatus status,
            boolean create,
            CallbackInfoReturnable<ChunkAccess> cir
    ) {
        ServerAssemblyContainer container = kinetic_assembly$plotContainer();
        if (container == null || !container.inPlotBounds(x, z)) {
            return;
        }

        LevelChunk chunk = container.plotChunk(new ChunkPos(x, z)).orElse(null);
        cir.setReturnValue(chunk != null ? chunk : create ? kinetic_assembly$emptyPlotChunk() : null);
    }

    @Inject(method = "getChunkNow", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$getPlotChunkNow(int x, int z, CallbackInfoReturnable<LevelChunk> cir) {
        ServerAssemblyContainer container = kinetic_assembly$plotContainer();
        if (container == null || !container.inPlotBounds(x, z)) {
            return;
        }

        cir.setReturnValue(container.plotChunk(new ChunkPos(x, z)).orElse(null));
    }

    @Inject(method = "getChunkFutureMainThread", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$getPlotChunkFutureMainThread(
            int x,
            int z,
            ChunkStatus status,
            boolean create,
            CallbackInfoReturnable<CompletableFuture<ChunkResult<ChunkAccess>>> cir
    ) {
        ServerAssemblyContainer container = kinetic_assembly$plotContainer();
        if (container == null || !container.inPlotBounds(x, z)) {
            return;
        }

        ChunkAccess chunk = container.plotChunk(new ChunkPos(x, z)).orElseGet(this::kinetic_assembly$emptyPlotChunk);
        cir.setReturnValue(CompletableFuture.completedFuture(ChunkResult.of(chunk)));
    }

    @Inject(method = "hasChunk", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$hasPlotChunk(int x, int z, CallbackInfoReturnable<Boolean> cir) {
        ServerAssemblyContainer container = kinetic_assembly$plotContainer();
        if (container == null || !container.inPlotBounds(x, z)) {
            return;
        }

        cir.setReturnValue(container.plotChunk(new ChunkPos(x, z)).isPresent());
    }

    @Inject(method = "getChunkForLighting", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$getPlotChunkForLighting(int x, int z, CallbackInfoReturnable<LightChunk> cir) {
        ServerAssemblyContainer container = kinetic_assembly$plotContainer();
        if (container == null || !container.inPlotBounds(x, z)) {
            return;
        }

        cir.setReturnValue(container.plotChunk(new ChunkPos(x, z)).orElse(null));
    }

    @Inject(method = "isPositionTicking", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$isPlotPositionTicking(long pos, CallbackInfoReturnable<Boolean> cir) {
        ServerAssemblyContainer container = kinetic_assembly$plotContainer();
        if (container == null || !container.inPlotBounds(ChunkPos.getX(pos), ChunkPos.getZ(pos))) {
            return;
        }

        cir.setReturnValue(container.plotChunk(new ChunkPos(pos)).isPresent());
    }

    @Inject(method = "getFullChunk", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$getFullPlotChunk(long pos, Consumer<LevelChunk> consumer, CallbackInfo ci) {
        ServerAssemblyContainer container = kinetic_assembly$plotContainer();
        if (container == null || !container.inPlotBounds(ChunkPos.getX(pos), ChunkPos.getZ(pos))) {
            return;
        }

        container.plotChunk(new ChunkPos(pos)).ifPresent(consumer);
        ci.cancel();
    }

    @Inject(method = "blockChanged", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$plotBlockChanged(BlockPos blockPos, CallbackInfo ci) {
        ServerAssemblyContainer container = kinetic_assembly$plotContainer();
        if (container == null || !container.inPlotBounds(new ChunkPos(blockPos))) {
            return;
        }

        container.plotChunkHolder(new ChunkPos(blockPos)).ifPresent(holder -> holder.blockChanged(blockPos));
        ci.cancel();
    }

    @Inject(method = "getVisibleChunkIfPresent", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$getVisiblePlotChunkIfPresent(long pos, CallbackInfoReturnable<ChunkHolder> cir) {
        ServerAssemblyContainer container = kinetic_assembly$plotContainer();
        if (container == null || !container.inPlotBounds(ChunkPos.getX(pos), ChunkPos.getZ(pos))) {
            return;
        }

        cir.setReturnValue(container.plotChunkHolder(new ChunkPos(pos)).orElse(null));
    }

    @Inject(
            method = "addRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private <T> void kinetic_assembly$addPlotRegionTicket(TicketType<T> type, ChunkPos pos, int distance, T value, CallbackInfo ci) {
        ServerAssemblyContainer container = kinetic_assembly$plotContainer();
        if (container != null && container.inPlotBounds(pos)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "addRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;Z)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private <T> void kinetic_assembly$addPlotRegionTicket(
            TicketType<T> type,
            ChunkPos pos,
            int distance,
            T value,
            boolean forceTicks,
            CallbackInfo ci
    ) {
        ServerAssemblyContainer container = kinetic_assembly$plotContainer();
        if (container != null && container.inPlotBounds(pos)) {
            ci.cancel();
        }
    }

    @Unique
    private ServerAssemblyContainer kinetic_assembly$plotContainer() {
        return AssemblyContainers.server(level).orElse(null);
    }

    @Unique
    private EmptyLevelChunk kinetic_assembly$emptyPlotChunk() {
        if (kinetic_assembly$emptyPlotChunk == null) {
            kinetic_assembly$emptyPlotChunk = new EmptyLevelChunk(
                    level,
                    new ChunkPos(0, 0),
                    level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS)
            );
        }
        return kinetic_assembly$emptyPlotChunk;
    }
}
