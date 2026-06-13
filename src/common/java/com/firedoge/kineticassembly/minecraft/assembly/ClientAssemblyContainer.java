package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.annotation.Nullable;

import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.render.ClientAssemblyBlockView;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class ClientAssemblyContainer implements AssemblyContainer {
    private final ClientLevel level;
    private final Map<ChunkPos, LevelChunk> plotChunks = new LinkedHashMap<>();
    private final Map<AssemblyId, ClientTrackedAssembly> trackedAssemblies = new LinkedHashMap<>();
    private final Map<ChunkPos, AssemblyId> trackedAssembliesByChunk = new LinkedHashMap<>();
    private final Map<ChunkPos, RemovedClientAssemblyProjection> removedProjectionsByChunk = new LinkedHashMap<>();
    private final AssemblyPlotAllocator plotAllocator = new AssemblyPlotAllocator();
    private static final long REMOVED_PROJECTION_TTL_MILLIS = 2500L;

    public ClientAssemblyContainer(ClientLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public ClientLevel level() {
        return level;
    }

    @Override
    public List<PhysicsAssembly> assemblies() {
        return List.of();
    }

    @Override
    public Optional<PhysicsAssembly> assembly(AssemblyId id) {
        Objects.requireNonNull(id, "id");
        return Optional.empty();
    }

    @Override
    public Optional<PhysicsAssembly> assemblyAtPlotBlock(BlockPos plotPos) {
        Objects.requireNonNull(plotPos, "plotPos");
        return Optional.empty();
    }

    @Override
    public synchronized int size() {
        return trackedAssemblies.size();
    }

    @Override
    public synchronized boolean inPlotBounds(ChunkPos chunkPos) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        return plotAllocator.inBounds(chunkPos);
    }

    public synchronized boolean inPlotBounds(int chunkX, int chunkZ) {
        return plotAllocator.inBounds(chunkX, chunkZ);
    }

    public synchronized Optional<LevelChunk> plotChunk(ChunkPos chunkPos) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        return Optional.ofNullable(plotChunks.get(chunkPos));
    }

    public synchronized LevelChunk putPlotChunk(ChunkPos chunkPos, LevelChunk chunk) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        Objects.requireNonNull(chunk, "chunk");
        cleanupRemovedProjections();
        if (!chunk.getPos().equals(chunkPos)) {
            throw new IllegalArgumentException("Chunk " + chunk.getPos() + " does not match plot position " + chunkPos);
        }
        if (!inPlotBounds(chunkPos)) {
            throw new IllegalArgumentException("Chunk is outside assembly plot bounds: " + chunkPos);
        }
        LevelChunk previous = plotChunks.put(chunkPos, chunk);
        ClientTrackedAssembly tracked = trackedAssemblyForChunk(chunkPos).orElse(null);
        if (tracked != null) {
            tracked.markChunkLoaded(chunkPos);
        }
        return previous;
    }

    public synchronized Optional<LevelChunk> removePlotChunk(ChunkPos chunkPos) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        cleanupRemovedProjections();
        trackedAssemblyForChunk(chunkPos).ifPresent(tracked -> tracked.markChunkDropped(chunkPos));
        return Optional.ofNullable(plotChunks.remove(chunkPos));
    }

    public synchronized int loadedPlotChunkCount() {
        return plotChunks.size();
    }

    @Override
    public synchronized void clientStartTracking(AssemblyClientMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        cleanupRemovedProjections();
        clientStopTracking(metadata.id());
        ClientTrackedAssembly tracked = new ClientTrackedAssembly(metadata);
        tracked.attachLevelView(new ClientAssemblyBlockView(this, tracked));
        trackedAssemblies.put(metadata.id(), tracked);
        for (ChunkPos chunkPos : metadata.chunkPositions()) {
            if (!inPlotBounds(chunkPos)) {
                throw new IllegalArgumentException("Assembly metadata references chunk outside plotyard: " + chunkPos);
            }
            trackedAssembliesByChunk.put(chunkPos, metadata.id());
            if (plotChunks.containsKey(chunkPos)) {
                tracked.markChunkLoaded(chunkPos);
            }
        }
    }

    @Override
    public synchronized void clientFinalizeTracking(AssemblyId id) {
        Objects.requireNonNull(id, "id");
        ClientTrackedAssembly tracked = trackedAssemblies.get(id);
        if (tracked != null) {
            tracked.finalizeTracking();
            projectPendingPlotyardEntities(tracked);
        }
    }

    @Override
    public synchronized void clientUpdateTransform(AssemblyPoseFrame frame) {
        Objects.requireNonNull(frame, "frame");
        ClientTrackedAssembly tracked = trackedAssemblies.get(frame.id());
        if (tracked != null) {
            tracked.updatePoseFrame(frame);
        }
    }

    @Override
    public synchronized void clientStopTracking(AssemblyId id) {
        Objects.requireNonNull(id, "id");
        cleanupRemovedProjections();
        ClientTrackedAssembly removed = trackedAssemblies.remove(id);
        if (removed == null) {
            return;
        }
        removed.close();
        RemovedClientAssemblyProjection projection = new RemovedClientAssemblyProjection(
                removed.metadata(),
                System.currentTimeMillis() + REMOVED_PROJECTION_TTL_MILLIS
        );
        for (ChunkPos chunkPos : removed.metadata().chunkPositions()) {
            trackedAssembliesByChunk.remove(chunkPos, id);
            removedProjectionsByChunk.put(chunkPos, projection);
            LevelChunk chunk = plotChunks.remove(chunkPos);
            if (chunk != null) {
                chunk.setLoaded(false);
                level.unload(chunk);
                level.getLightEngine().setLightEnabled(chunkPos, false);
            }
        }
    }

    public synchronized List<ClientTrackedAssembly> trackedAssemblies() {
        return List.copyOf(trackedAssemblies.values());
    }

    public synchronized Optional<ClientTrackedAssembly> trackedAssembly(AssemblyId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(trackedAssemblies.get(id));
    }

    @Override
    public synchronized Optional<AssemblyPlotProjection> plotProjection(AssemblyId id) {
        Objects.requireNonNull(id, "id");
        return trackedAssembly(id)
                .filter(ClientTrackedAssembly::finalized)
                .map(ClientAssemblyContainer::projection);
    }

    public synchronized Optional<ClientTrackedAssembly> trackedAssemblyForChunk(ChunkPos chunkPos) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        AssemblyId id = trackedAssembliesByChunk.get(chunkPos);
        return id == null ? Optional.empty() : Optional.ofNullable(trackedAssemblies.get(id));
    }

    @Override
    public synchronized Optional<AssemblyPlotProjection> plotProjection(BlockPos plotPos) {
        Objects.requireNonNull(plotPos, "plotPos");
        return trackedAssemblyForChunk(new ChunkPos(plotPos))
                .filter(ClientTrackedAssembly::finalized)
                .filter(tracked -> tracked.plot().containsPlotBlockPos(plotPos))
                .map(ClientAssemblyContainer::projection);
    }

    @Override
    public synchronized Optional<AssemblyPlotProjection> plotProjection(ChunkPos plotChunk) {
        Objects.requireNonNull(plotChunk, "plotChunk");
        return trackedAssemblyForChunk(plotChunk)
                .filter(ClientTrackedAssembly::finalized)
                .map(ClientAssemblyContainer::projection);
    }

    @Override
    public synchronized List<AssemblyCollisionTarget> collisionTargets(AABB worldBounds, @Nullable AssemblyId forcedId) {
        Objects.requireNonNull(worldBounds, "worldBounds");
        cleanupRemovedProjections();
        if (trackedAssemblies.isEmpty()) {
            return List.of();
        }

        AABB queryBounds = worldBounds.inflate(1.0E-7D);
        List<AssemblyCollisionTarget> targets = new ArrayList<>();
        for (ClientTrackedAssembly assembly : trackedAssemblies.values()) {
            if (!assembly.finalized()) {
                continue;
            }
            List<AssemblyCollisionBlock> collisionBlocks = bodyLocalCollisionBlocks(assembly);
            if (collisionBlocks.isEmpty()) {
                continue;
            }
            AssemblyCollisionTarget target = new AssemblyCollisionTarget(
                    assembly.poseFrame(),
                    collisionBlocks,
                    assembly.levelView(),
                    assembly.plot(),
                    assembly.metadata().bodyToPlotOrigin()
            );
            if (forcedId != null && forcedId.equals(assembly.id())) {
                targets.add(target);
                continue;
            }

            AABB sweptBounds = assembly.sweptWorldBounds().orElseGet(() -> assembly.worldBounds().orElse(null));
            if (sweptBounds != null && sweptBounds.intersects(queryBounds)) {
                targets.add(target);
            }
        }
        return List.copyOf(targets);
    }

    public synchronized void markPlotBlockChanged(BlockPos plotPos) {
        Objects.requireNonNull(plotPos, "plotPos");
        trackedAssemblyForChunk(new ChunkPos(plotPos))
                .filter(assembly -> assembly.plot().containsPlotBlockPos(plotPos))
                .ifPresent(ClientTrackedAssembly::invalidateCollisionGeometry);
    }

    public synchronized void markPlotLightChanged(int sectionX, int sectionZ) {
        trackedAssemblyForChunk(new ChunkPos(sectionX, sectionZ))
                .ifPresent(ClientTrackedAssembly::invalidateRenderMeshes);
    }

    public synchronized Optional<RemovedClientAssemblyProjection> removedProjectionForChunk(ChunkPos chunkPos) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        cleanupRemovedProjections();
        return Optional.ofNullable(removedProjectionsByChunk.get(chunkPos));
    }

    private static AssemblyPlotProjection projection(ClientTrackedAssembly tracked) {
        return new AssemblyPlotProjection(
                tracked.id(),
                tracked.plot(),
                tracked.metadata().bodyToPlotOrigin(),
                tracked.poseFrame()
        );
    }

    private void projectPendingPlotyardEntities(ClientTrackedAssembly tracked) {
        AssemblyPlotProjection projection = projection(tracked);
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isRemoved()
                    || entity instanceof Player player && player.isLocalPlayer()
                    || entity instanceof BlockAttachedEntity
                    || !AssemblyEntityKicking.shouldKick(entity)) {
                continue;
            }

            Vec3 plotPosition = entity.position();
            BlockPos plotBlockPos = BlockPos.containing(plotPosition);
            if (!tracked.plot().containsPlotBlockPos(plotBlockPos)) {
                continue;
            }

            Vec3 worldPosition = projection.plotToWorld(plotPosition);
            if (!finite(worldPosition)) {
                continue;
            }

            entity.moveTo(worldPosition.x, worldPosition.y, worldPosition.z);
            if (entity instanceof AssemblyEntityCollisionAccess access) {
                access.kinetic_assembly$setTrackingAssemblyId(tracked.id());
                if (entity instanceof LivingEntity) {
                    access.kinetic_assembly$setPlotPosition(plotPosition);
                }
            }
        }
    }

    private void cleanupRemovedProjections() {
        long now = System.currentTimeMillis();
        removedProjectionsByChunk.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }

    public synchronized List<AABB> bodyLocalCollisionBoxes(ClientTrackedAssembly assembly) {
        Objects.requireNonNull(assembly, "assembly");
        return bodyLocalCollisionBlocks(assembly).stream()
                .flatMap(block -> block.bodyLocalBoxes().stream())
                .toList();
    }

    public synchronized List<AssemblyCollisionBlock> bodyLocalCollisionBlocks(ClientTrackedAssembly assembly) {
        Objects.requireNonNull(assembly, "assembly");
        long geometryVersion = assembly.collisionGeometryVersion();
        ClientTrackedAssembly.BodyLocalCollisionCache cached = assembly.bodyLocalCollisionCache();
        if (cached != null && cached.geometryVersion() == geometryVersion) {
            return cached.blocks();
        }

        List<AssemblyCollisionBlock> blocks = new ArrayList<>();
        appendBodyLocalCollisions(assembly, blocks);
        assembly.cacheBodyLocalCollisionBlocks(blocks);
        return assembly.bodyLocalCollisionCache().blocks();
    }

    private void appendBodyLocalCollisions(ClientTrackedAssembly assembly, List<AssemblyCollisionBlock> output) {
        for (ChunkPos chunkPos : assembly.loadedChunks()) {
            LevelChunk chunk = plotChunks.get(chunkPos);
            if (chunk == null) {
                continue;
            }
            LevelChunkSection[] sections = chunk.getSections();
            for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                LevelChunkSection section = sections[sectionIndex];
                if (section.hasOnlyAir()) {
                    continue;
                }
                int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
                BlockPos sectionOrigin = SectionPos.of(chunkPos, sectionY).origin();
                BlockPos.MutableBlockPos plotPos = new BlockPos.MutableBlockPos();
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState state = section.getBlockState(x, y, z);
                            if (state.isAir()) {
                                continue;
                            }
                            plotPos.setWithOffset(sectionOrigin, x, y, z);
                            BlockPos immutablePlotPos = plotPos.immutable();
                            if (!assembly.plot().containsPlotBlockPos(immutablePlotPos)) {
                                continue;
                            }
                            VoxelShape collisionShape = state.getCollisionShape(assembly.levelView(), immutablePlotPos);
                            List<AABB> boxes = bodyLocalCollisionBoxes(assembly, immutablePlotPos, collisionShape);
                            List<AABB> broadPhaseBoxes = dynamicCollisionShape(state)
                                    ? List.of(bodyLocalBlockBounds(assembly, immutablePlotPos))
                                    : boxes;
                            if (!boxes.isEmpty() || !broadPhaseBoxes.isEmpty()) {
                                output.add(new AssemblyCollisionBlock(
                                        assembly.plot().toSectionLocalPos(immutablePlotPos),
                                        state,
                                        boxes,
                                        broadPhaseBoxes
                                ));
                            }
                        }
                    }
                }
            }
        }
    }

    private static List<AABB> bodyLocalCollisionBoxes(
            ClientTrackedAssembly assembly,
            BlockPos plotPos,
            VoxelShape collisionShape
    ) {
        List<AABB> boxes = new ArrayList<>();
        for (AABB box : collisionShape.toAabbs()) {
            AABB plotBox = new AABB(
                    plotPos.getX() + box.minX,
                    plotPos.getY() + box.minY,
                    plotPos.getZ() + box.minZ,
                    plotPos.getX() + box.maxX,
                    plotPos.getY() + box.maxY,
                    plotPos.getZ() + box.maxZ
            );
            boxes.add(plotBoxToBodyLocalBounds(assembly, plotBox));
        }
        return List.copyOf(boxes);
    }

    private static boolean dynamicCollisionShape(BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.ScaffoldingBlock;
    }

    private static AABB bodyLocalBlockBounds(ClientTrackedAssembly assembly, BlockPos plotPos) {
        return plotBoxToBodyLocalBounds(assembly, new AABB(
                plotPos.getX(),
                plotPos.getY(),
                plotPos.getZ(),
                plotPos.getX() + 1.0D,
                plotPos.getY() + 1.0D,
                plotPos.getZ() + 1.0D
        ));
    }

    private static AABB plotBoxToBodyLocalBounds(ClientTrackedAssembly assembly, AABB plotBox) {
        PhysicsVector bodyToPlotOrigin = assembly.metadata().bodyToPlotOrigin();
        double xOffset = bodyToPlotOrigin.x() - assembly.plot().minPlotX();
        double yOffset = bodyToPlotOrigin.y() - assembly.plot().minPlotY();
        double zOffset = bodyToPlotOrigin.z() - assembly.plot().minPlotZ();
        return new AABB(
                plotBox.minX + xOffset,
                plotBox.minY + yOffset,
                plotBox.minZ + zOffset,
                plotBox.maxX + xOffset,
                plotBox.maxY + yOffset,
                plotBox.maxZ + zOffset
        );
    }

    private static boolean finite(Vec3 position) {
        return Double.isFinite(position.x)
                && Double.isFinite(position.y)
                && Double.isFinite(position.z);
    }

    public record RemovedClientAssemblyProjection(AssemblyClientMetadata metadata, long expiresAtMillis) {
        public RemovedClientAssemblyProjection {
            Objects.requireNonNull(metadata, "metadata");
        }

        boolean isExpired(long now) {
            return now >= expiresAtMillis;
        }
    }
}
