package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public final class PlotChunkHolder extends ChunkHolder {
    private final LevelChunk chunk;
    private final CompletableFuture<ChunkResult<LevelChunk>> readyChunk;
    private final CompletableFuture<Void> ready = CompletableFuture.completedFuture(null);
    private boolean needsBroadcast = true;

    private PlotChunkHolder(ServerLevel level, LevelChunk chunk) {
        super(
                chunk.getPos(),
                ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING),
                level,
                level.getChunkSource().getLightEngine(),
                (pos, levelSupplier, levelValue, levelSetter) -> {
                },
                (pos, boundaryOnly) -> AssemblyContainers.server(level)
                        .map(container -> container.trackingSystem().playersTracking(pos))
                        .orElseGet(java.util.List::of)
        );
        this.chunk = Objects.requireNonNull(chunk, "chunk");
        this.readyChunk = CompletableFuture.completedFuture(ChunkResult.of(chunk));
    }

    public static PlotChunkHolder create(ServerLevel level, LevelChunk chunk) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(chunk, "chunk");
        return new PlotChunkHolder(level, chunk);
    }

    public LevelChunk chunk() {
        return chunk;
    }

    public void broadcastPlotChanges() {
        if (!needsBroadcast) {
            return;
        }
        needsBroadcast = false;
        broadcastChanges(chunk);
    }

    @Override
    public void blockChanged(BlockPos pos) {
        super.blockChanged(pos);
        needsBroadcast = true;
    }

    @Override
    public void sectionLightChanged(LightLayer lightLayer, int sectionY) {
        super.sectionLightChanged(lightLayer, sectionY);
        needsBroadcast = true;
    }

    @Override
    public CompletableFuture<ChunkResult<ChunkAccess>> scheduleChunkGenerationTask(
            ChunkStatus status,
            ChunkMap chunkMap
    ) {
        return CompletableFuture.completedFuture(ChunkResult.of(chunk));
    }

    @Override
    public CompletableFuture<ChunkResult<LevelChunk>> getTickingChunkFuture() {
        return readyChunk;
    }

    @Override
    public CompletableFuture<ChunkResult<LevelChunk>> getEntityTickingChunkFuture() {
        return readyChunk;
    }

    @Override
    public CompletableFuture<ChunkResult<LevelChunk>> getFullChunkFuture() {
        return readyChunk;
    }

    @Override
    public LevelChunk getTickingChunk() {
        return chunk;
    }

    @Override
    public LevelChunk getChunkToSend() {
        return chunk;
    }

    @Override
    public CompletableFuture<?> getSendSyncFuture() {
        return ready;
    }

    @Override
    public void addSendDependency(CompletableFuture<?> dependency) {
    }

    @Override
    public CompletableFuture<?> getSaveSyncFuture() {
        return ready;
    }

    @Override
    public boolean isReadyForSaving() {
        return false;
    }

    @Override
    protected void updateFutures(ChunkMap chunkMap, Executor executor) {
    }

    @Override
    public boolean wasAccessibleSinceLastSave() {
        return false;
    }

    @Override
    public void refreshAccessibility() {
    }
}
