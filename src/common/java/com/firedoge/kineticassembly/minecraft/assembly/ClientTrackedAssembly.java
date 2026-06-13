package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.render.ClientAssemblyBlockView;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

public final class ClientTrackedAssembly {
    private final AssemblyClientMetadata metadata;
    private final Set<ChunkPos> loadedChunks = new LinkedHashSet<>();
    private ClientAssemblyBlockView levelView;
    private AssemblyPoseFrame poseFrame;
    private AssemblyPoseFrame renderPoseFrame;
    private long renderFrameStartTick;
    private float renderFrameStartPartialTick;
    private boolean finalized;
    private long collisionGeometryVersion;
    private BodyLocalCollisionCache bodyLocalCollisionCache;
    private WorldCollisionCache worldCollisionCache;
    @Nullable
    private AABB lastWorldBounds;
    @Nullable
    private AABB worldBounds;
    @Nullable
    private AABB sweptWorldBounds;
    private final Map<RenderType, RenderMeshCache> renderMeshCaches = new HashMap<>();
    private static final double COLLISION_INDEX_CELL_SIZE = 4.0D;

    ClientTrackedAssembly(AssemblyClientMetadata metadata) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.poseFrame = metadata.poseFrame();
        this.renderPoseFrame = metadata.poseFrame();
        RenderClock clock = renderClock();
        this.renderFrameStartTick = clock.gameTime();
        this.renderFrameStartPartialTick = clock.partialTick();
    }

    public AssemblyClientMetadata metadata() {
        return metadata;
    }

    public AssemblyId id() {
        return metadata.id();
    }

    public AssemblyPlot plot() {
        return metadata.plot();
    }

    public PhysicsPose pose() {
        return poseFrame.currentPose();
    }

    public AssemblyPoseFrame poseFrame() {
        return poseFrame;
    }

    public PhysicsPose renderPose(float partialTick) {
        return renderPoseAt(clientGameTime(), partialTick);
    }

    public Optional<AABB> renderWorldBounds(float partialTick) {
        BodyLocalCollisionCache cache = bodyLocalCollisionCache;
        if (cache == null || cache.localAggregateBounds() == null) {
            return worldBounds();
        }

        AABB bounds = AssemblyTransform.from(renderPose(partialTick))
                .localAabbToWorldBounds(cache.localAggregateBounds());
        return Optional.of(copy(bounds));
    }

    public ClientAssemblyBlockView levelView() {
        if (levelView == null) {
            throw new IllegalStateException("Client assembly block view has not been attached");
        }
        return levelView;
    }

    void attachLevelView(ClientAssemblyBlockView levelView) {
        this.levelView = Objects.requireNonNull(levelView, "levelView");
    }

    void updatePoseFrame(AssemblyPoseFrame poseFrame) {
        Objects.requireNonNull(poseFrame, "poseFrame");
        if (!poseFrame.id().equals(id())) {
            throw new IllegalArgumentException("pose frame id does not match tracked assembly id");
        }
        if (poseFrame.epoch() <= this.poseFrame.epoch()) {
            return;
        }
        RenderClock clock = renderClock();
        PhysicsPose renderStart = renderPoseAt(clock.gameTime(), clock.partialTick());
        this.poseFrame = poseFrame;
        this.renderPoseFrame = new AssemblyPoseFrame(
                poseFrame.id(),
                poseFrame.epoch(),
                renderStart,
                poseFrame.currentPose()
        );
        this.renderFrameStartTick = clock.gameTime();
        this.renderFrameStartPartialTick = clock.partialTick();
        refreshWorldBounds();
        // Pose changes are rendered by transforming the cached plot mesh. Geometry and light
        // updates are the only client events that should rebuild render buffers.
    }

    public boolean finalized() {
        return finalized;
    }

    void finalizeTracking() {
        finalized = true;
    }

    void markChunkLoaded(ChunkPos chunkPos) {
        loadedChunks.add(Objects.requireNonNull(chunkPos, "chunkPos"));
        invalidateCollisionGeometry();
    }

    void markChunkDropped(ChunkPos chunkPos) {
        if (loadedChunks.remove(chunkPos)) {
            invalidateCollisionGeometry();
        }
    }

    public Set<ChunkPos> loadedChunks() {
        return Set.copyOf(loadedChunks);
    }

    public long collisionGeometryVersion() {
        return collisionGeometryVersion;
    }

    public void invalidateCollisionGeometry() {
        collisionGeometryVersion++;
        bodyLocalCollisionCache = null;
        worldCollisionCache = null;
        clearWorldBounds();
        closeRenderMeshCaches();
    }

    public void invalidateRenderMeshes() {
        closeRenderMeshCaches();
    }

    public BodyLocalCollisionCache bodyLocalCollisionCache() {
        return bodyLocalCollisionCache;
    }

    public void cacheBodyLocalCollisionBlocks(List<AssemblyCollisionBlock> blocks) {
        bodyLocalCollisionCache = new BodyLocalCollisionCache(collisionGeometryVersion, blocks);
        worldCollisionCache = null;
        refreshWorldBounds();
    }

    public WorldCollisionCache worldCollisionCache() {
        return worldCollisionCache;
    }

    public void cacheWorldCollisionBoxes(PhysicsPose pose, List<AABB> boxes) {
        worldCollisionCache = new WorldCollisionCache(collisionGeometryVersion, pose, boxes);
    }

    public Optional<AABB> worldBounds() {
        return Optional.ofNullable(worldBounds).map(ClientTrackedAssembly::copy);
    }

    public Optional<AABB> sweptWorldBounds() {
        return Optional.ofNullable(sweptWorldBounds).map(ClientTrackedAssembly::copy);
    }

    public RenderMeshCache renderMeshCache(RenderType renderType) {
        RenderMeshCache cache = renderMeshCaches.get(renderType);
        if (cache != null && cache.geometryVersion() == collisionGeometryVersion && !cache.invalid()) {
            return cache;
        }
        if (cache != null) {
            renderMeshCaches.remove(renderType);
            closeRenderMeshCache(cache);
        }
        return null;
    }

    public void cacheRenderMesh(RenderType renderType, VertexBuffer vertexBuffer, int chunks, int blocks) {
        Objects.requireNonNull(renderType, "renderType");
        RenderMeshCache previous = renderMeshCaches.put(
                renderType,
                new RenderMeshCache(collisionGeometryVersion, vertexBuffer, chunks, blocks)
        );
        if (previous != null) {
            closeRenderMeshCache(previous);
        }
    }

    public void close() {
        closeRenderMeshCaches();
        bodyLocalCollisionCache = null;
        worldCollisionCache = null;
        clearWorldBounds();
    }

    private void refreshWorldBounds() {
        BodyLocalCollisionCache cache = bodyLocalCollisionCache;
        if (cache == null || cache.localAggregateBounds() == null) {
            clearWorldBounds();
            return;
        }

        AABB previous = poseFrame.previousTransform().localAabbToWorldBounds(cache.localAggregateBounds());
        AABB current = poseFrame.currentTransform().localAabbToWorldBounds(cache.localAggregateBounds());
        lastWorldBounds = previous == null ? copy(current) : previous;
        worldBounds = current;
        sweptWorldBounds = previous == null ? copy(current) : union(previous, current);
    }

    private void clearWorldBounds() {
        lastWorldBounds = null;
        worldBounds = null;
        sweptWorldBounds = null;
    }

    private PhysicsPose renderPoseAt(long gameTime, float partialTick) {
        return renderPoseFrame.interpolatedPose(renderAlpha(gameTime, partialTick));
    }

    private double renderAlpha(long gameTime, float partialTick) {
        double elapsedTicks = gameTime - renderFrameStartTick + partialTick - renderFrameStartPartialTick;
        return Math.max(0.0D, Math.min(1.0D, elapsedTicks));
    }

    private static RenderClock renderClock() {
        Minecraft minecraft = Minecraft.getInstance();
        return new RenderClock(clientGameTime(minecraft), minecraft.getTimer().getGameTimeDeltaPartialTick(false));
    }

    private static long clientGameTime() {
        return clientGameTime(Minecraft.getInstance());
    }

    private static long clientGameTime(Minecraft minecraft) {
        return minecraft.level == null ? 0L : minecraft.level.getGameTime();
    }

    private void closeRenderMeshCaches() {
        if (renderMeshCaches.isEmpty()) {
            return;
        }
        for (RenderMeshCache cache : renderMeshCaches.values()) {
            closeRenderMeshCache(cache);
        }
        renderMeshCaches.clear();
    }

    private static void closeRenderMeshCache(RenderMeshCache cache) {
        VertexBuffer buffer = cache.vertexBuffer();
        if (buffer == null || buffer.isInvalid()) {
            return;
        }
        if (RenderSystem.isOnRenderThreadOrInit()) {
            buffer.close();
        } else {
            RenderSystem.recordRenderCall(buffer::close);
        }
    }

    public record BodyLocalCollisionCache(
            long geometryVersion,
            List<AssemblyCollisionBlock> blocks,
            List<AABB> boxes,
            List<AABB> broadPhaseBoxes,
            @Nullable AABB localAggregateBounds
    ) {
        public BodyLocalCollisionCache(long geometryVersion, List<AssemblyCollisionBlock> blocks) {
            this(geometryVersion, blocks, collisionBoxes(blocks), broadPhaseBoxes(blocks));
        }

        private BodyLocalCollisionCache(
                long geometryVersion,
                List<AssemblyCollisionBlock> blocks,
                List<AABB> boxes,
                List<AABB> broadPhaseBoxes
        ) {
            this(geometryVersion, blocks, boxes, broadPhaseBoxes, unionAll(broadPhaseBoxes));
        }

        public BodyLocalCollisionCache {
            blocks = List.copyOf(blocks);
            boxes = List.copyOf(boxes);
            broadPhaseBoxes = List.copyOf(broadPhaseBoxes);
            localAggregateBounds = localAggregateBounds == null ? null : copy(localAggregateBounds);
        }

        private static List<AABB> collisionBoxes(List<AssemblyCollisionBlock> blocks) {
            return blocks.stream()
                    .flatMap(block -> block.bodyLocalBoxes().stream())
                    .toList();
        }

        private static List<AABB> broadPhaseBoxes(List<AssemblyCollisionBlock> blocks) {
            return blocks.stream()
                    .flatMap(block -> block.broadPhaseLocalBoxes().stream())
                    .toList();
        }
    }

    public record RenderMeshCache(long geometryVersion, VertexBuffer vertexBuffer, int chunks, int blocks) {
        private boolean invalid() {
            return vertexBuffer != null && vertexBuffer.isInvalid();
        }
    }

    public record WorldCollisionCache(
            long geometryVersion,
            PhysicsPose pose,
            List<AABB> boxes,
            AABB bounds,
            Map<CellKey, List<Integer>> boxIndexesByCell
    ) {
        public WorldCollisionCache(long geometryVersion, PhysicsPose pose, List<AABB> boxes) {
            this(geometryVersion, pose, boxes, union(boxes), index(boxes));
        }

        public WorldCollisionCache {
            Objects.requireNonNull(pose, "pose");
            boxes = List.copyOf(boxes);
            boxIndexesByCell = copyIndex(boxIndexesByCell);
        }

        public List<AABB> candidateBoxes(AABB queryBounds) {
            if (bounds == null || !bounds.intersects(queryBounds)) {
                return List.of();
            }

            int minCellX = cell(queryBounds.minX);
            int minCellY = cell(queryBounds.minY);
            int minCellZ = cell(queryBounds.minZ);
            int maxCellX = cell(queryBounds.maxX);
            int maxCellY = cell(queryBounds.maxY);
            int maxCellZ = cell(queryBounds.maxZ);
            if (minCellX == maxCellX && minCellY == maxCellY && minCellZ == maxCellZ) {
                return boxesForIndexes(boxIndexesByCell.get(new CellKey(minCellX, minCellY, minCellZ)));
            }

            Set<Integer> indexes = new LinkedHashSet<>();
            for (int x = minCellX; x <= maxCellX; x++) {
                for (int y = minCellY; y <= maxCellY; y++) {
                    for (int z = minCellZ; z <= maxCellZ; z++) {
                        List<Integer> cellIndexes = boxIndexesByCell.get(new CellKey(x, y, z));
                        if (cellIndexes != null) {
                            indexes.addAll(cellIndexes);
                        }
                    }
                }
            }
            return boxesForIndexes(indexes.stream().toList());
        }

        private List<AABB> boxesForIndexes(List<Integer> indexes) {
            if (indexes == null || indexes.isEmpty()) {
                return List.of();
            }

            List<AABB> candidates = new ArrayList<>(indexes.size());
            for (int index : indexes) {
                candidates.add(boxes.get(index));
            }
            return candidates;
        }

        private static AABB union(List<AABB> boxes) {
            if (boxes.isEmpty()) {
                return null;
            }

            AABB first = boxes.getFirst();
            double minX = first.minX;
            double minY = first.minY;
            double minZ = first.minZ;
            double maxX = first.maxX;
            double maxY = first.maxY;
            double maxZ = first.maxZ;
            for (int i = 1; i < boxes.size(); i++) {
                AABB box = boxes.get(i);
                minX = Math.min(minX, box.minX);
                minY = Math.min(minY, box.minY);
                minZ = Math.min(minZ, box.minZ);
                maxX = Math.max(maxX, box.maxX);
                maxY = Math.max(maxY, box.maxY);
                maxZ = Math.max(maxZ, box.maxZ);
            }
            return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        }

        private static Map<CellKey, List<Integer>> index(List<AABB> boxes) {
            Map<CellKey, List<Integer>> index = new LinkedHashMap<>();
            for (int boxIndex = 0; boxIndex < boxes.size(); boxIndex++) {
                AABB box = boxes.get(boxIndex);
                int minCellX = cell(box.minX);
                int minCellY = cell(box.minY);
                int minCellZ = cell(box.minZ);
                int maxCellX = cell(box.maxX);
                int maxCellY = cell(box.maxY);
                int maxCellZ = cell(box.maxZ);
                for (int x = minCellX; x <= maxCellX; x++) {
                    for (int y = minCellY; y <= maxCellY; y++) {
                        for (int z = minCellZ; z <= maxCellZ; z++) {
                            index.computeIfAbsent(new CellKey(x, y, z), ignored -> new ArrayList<>()).add(boxIndex);
                        }
                    }
                }
            }
            return copyIndex(index);
        }

        private static Map<CellKey, List<Integer>> copyIndex(Map<CellKey, List<Integer>> index) {
            Map<CellKey, List<Integer>> copy = new LinkedHashMap<>();
            for (Map.Entry<CellKey, List<Integer>> entry : index.entrySet()) {
                copy.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            return Map.copyOf(copy);
        }

        private static int cell(double coordinate) {
            return (int) Math.floor(coordinate / COLLISION_INDEX_CELL_SIZE);
        }

        private record CellKey(int x, int y, int z) {
        }
    }

    private static AABB union(AABB first, AABB second) {
        return new AABB(
                Math.min(first.minX, second.minX),
                Math.min(first.minY, second.minY),
                Math.min(first.minZ, second.minZ),
                Math.max(first.maxX, second.maxX),
                Math.max(first.maxY, second.maxY),
                Math.max(first.maxZ, second.maxZ)
        );
    }

    @Nullable
    private static AABB unionAll(List<AABB> boxes) {
        if (boxes.isEmpty()) {
            return null;
        }
        AABB bounds = boxes.getFirst();
        for (int i = 1; i < boxes.size(); i++) {
            bounds = union(bounds, boxes.get(i));
        }
        return bounds;
    }

    private static AABB copy(AABB bounds) {
        return new AABB(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
    }

    private record RenderClock(long gameTime, float partialTick) {
    }
}
