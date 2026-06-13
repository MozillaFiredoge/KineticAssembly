package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.mechanics.MechanicsWorld;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.ticks.LevelChunkTicks;

public final class ServerAssemblyContainer implements AssemblyContainer {
    private final ServerLevel level;
    private final Map<AssemblyId, PhysicsAssembly> assemblies = new LinkedHashMap<>();
    private final Map<AssemblyId, AssemblyRuntimeState> runtimeStates = new LinkedHashMap<>();
    private final Map<Long, Set<AssemblyId>> runtimeSpatialIndex = new LinkedHashMap<>();
    private final Map<AssemblyId, Set<Long>> runtimeSpatialCellsById = new LinkedHashMap<>();
    private final Map<ChunkPos, AssemblyId> assembliesByChunk = new LinkedHashMap<>();
    private final Map<ChunkPos, PlotChunkHolder> plotChunkHolders = new LinkedHashMap<>();
    private final Map<AssemblyId, Integer> pendingBlockUpdatePrimes = new LinkedHashMap<>();
    private final Map<AssemblyId, Set<AssemblyLoadingTicket<?>>> activeTickets = new LinkedHashMap<>();
    private final Map<AssemblyId, AssemblyTicketInfo> allTickets = new LinkedHashMap<>();
    private final AssemblyPlotAllocator plotAllocator = new AssemblyPlotAllocator();
    private final AssemblyHoldingChunkMap holdingChunkMap;
    private final AssemblyTrackingSystem trackingSystem = new AssemblyTrackingSystem(this);
    private final AssemblyTicketLoadingSystem ticketLoadingSystem = new AssemblyTicketLoadingSystem(this);
    private final ThreadLocal<Boolean> rebuildingPlotChunks = ThreadLocal.withInitial(() -> false);
    private boolean forceLoadTicketsInitialized;
    private static final int DEFAULT_BLOCK_UPDATE_PRIME_TICKS = 1;

    public ServerAssemblyContainer(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
        this.holdingChunkMap = new AssemblyHoldingChunkMap(level, this);
    }

    @Override
    public ServerLevel level() {
        return level;
    }

    public synchronized AssemblyPlot allocatePlot(AssemblyBounds bounds) {
        return plotAllocator.allocate(bounds, level.getMinSection(), level.getSectionsCount());
    }

    public void add(PhysicsAssembly assembly) {
        add(assembly, true);
    }

    public void add(PhysicsAssembly assembly, boolean updateTrackingImmediately) {
        Objects.requireNonNull(assembly, "assembly");
        synchronized (this) {
            if (!assembly.levelKey().equals(level.dimension())) {
                throw new IllegalArgumentException("Assembly belongs to " + assembly.levelKey().location()
                        + ", not " + level.dimension().location());
            }
            for (ChunkPos chunkPos : assembly.plot().chunkPositions()) {
                AssemblyId occupant = assembliesByChunk.get(chunkPos);
                if (occupant != null && !occupant.equals(assembly.id())) {
                    throw new IllegalArgumentException("Duplicate assembly plot chunk " + chunkPos);
                }
            }
            PhysicsAssembly previous = assemblies.putIfAbsent(assembly.id(), assembly);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate assembly id " + assembly.id());
            }
            runtimeStates.put(assembly.id(), new AssemblyRuntimeState(assembly));
            plotAllocator.observe(assembly.plot().id());
        }

        try {
            rebuildPlotChunks(assembly);
            if (updateTrackingImmediately) {
                trackingSystem.onAssemblyAdded(assembly);
            }
            ticketLoadingSystem.onAssemblyAdded(assembly);
        } catch (RuntimeException exception) {
            remove(assembly.id());
            throw exception;
        }
    }

    public Optional<PhysicsAssembly> remove(AssemblyId id) {
        return remove(id, AssemblyRemovalReason.REMOVED);
    }

    public Optional<PhysicsAssembly> remove(AssemblyId id, AssemblyRemovalReason reason) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reason, "reason");
        PhysicsAssembly removed;
        List<ChunkPos> removedChunks = List.of();
        synchronized (this) {
            removed = assemblies.remove(id);
            if (removed != null) {
                runtimeStates.remove(id);
                removeRuntimeSpatialIndex(id);
                pendingBlockUpdatePrimes.remove(id);
                if (reason == AssemblyRemovalReason.REMOVED) {
                    kickPlotEntities(removed);
                }
                removedChunks = removePlotChunks(removed, reason == AssemblyRemovalReason.REMOVED);
            }
        }
        if (removed != null) {
            ticketLoadingSystem.onAssemblyRemoved(removed, reason);
            trackingSystem.onAssemblyRemoved(removed, removedChunks, reason);
            if (reason == AssemblyRemovalReason.REMOVED) {
                holdingChunkMap.queueDeletion(removed);
            }
        }
        return Optional.ofNullable(removed);
    }

    public void clear() {
        clear(AssemblyRemovalReason.REMOVED);
    }

    public void clear(AssemblyRemovalReason reason) {
        Objects.requireNonNull(reason, "reason");
        List<RemovedAssembly> removed;
        List<PlotChunkHolder> removedHolders;
        synchronized (this) {
            removedHolders = List.copyOf(plotChunkHolders.values());
            removed = assemblies.values().stream()
                    .map(assembly -> new RemovedAssembly(
                            assembly,
                            assembly.plot().chunkPositions().stream()
                                    .filter(plotChunkHolders::containsKey)
                                    .toList()
                    ))
                    .toList();
            assemblies.clear();
            runtimeStates.clear();
            runtimeSpatialIndex.clear();
            runtimeSpatialCellsById.clear();
            assembliesByChunk.clear();
            plotChunkHolders.clear();
            pendingBlockUpdatePrimes.clear();
        }
        for (RemovedAssembly entry : removed) {
            if (reason == AssemblyRemovalReason.REMOVED) {
                clearPlotScheduledTicks(entry.assembly());
            }
        }
        for (PlotChunkHolder holder : removedHolders) {
            unregisterPlotChunk(holder);
        }
        for (RemovedAssembly entry : removed) {
            ticketLoadingSystem.onAssemblyRemoved(entry.assembly(), reason);
            trackingSystem.onAssemblyRemoved(entry.assembly(), entry.removedChunks(), reason);
        }
        activeTickets.clear();
        if (reason == AssemblyRemovalReason.REMOVED) {
            allTickets.clear();
        }
        trackingSystem.clear();
    }

    private void kickPlotEntities(PhysicsAssembly assembly) {
        AssemblyPlotProjection projection = plotProjection(assembly).orElse(null);
        if (projection == null) {
            return;
        }

        List<Entity> entities = AssemblyEntityBridge.rawEntities(
                level,
                null,
                plotBounds(assembly.plot()),
                entity -> !entity.isRemoved()
        );
        for (Entity entity : entities) {
            if (entity.getType().is(AssemblyEntityTags.DESTROY_WITH_ASSEMBLY)) {
                entity.remove(Entity.RemovalReason.KILLED);
                continue;
            }

            AssemblyEntityKicking.kickEntity(entity, projection);
            entity.levelCallback.onRemove(Entity.RemovalReason.CHANGED_DIMENSION);
            level.addDuringTeleport(entity);
        }
    }

    private static AABB plotBounds(AssemblyPlot plot) {
        return new AABB(
                plot.minPlotX(),
                plot.minPlotY(),
                plot.minPlotZ(),
                plot.maxPlotX() + 1.0D,
                plot.maxPlotY() + 1.0D,
                plot.maxPlotZ() + 1.0D
        );
    }

    private static Set<Long> runtimeSpatialCells(AABB bounds) {
        Objects.requireNonNull(bounds, "bounds");
        int minChunkX = Mth.floor(bounds.minX) >> 4;
        int maxChunkX = Mth.floor(bounds.maxX) >> 4;
        int minChunkZ = Mth.floor(bounds.minZ) >> 4;
        int maxChunkZ = Mth.floor(bounds.maxZ) >> 4;
        Set<Long> cells = new LinkedHashSet<>();
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                cells.add(ChunkPos.asLong(x, z));
            }
        }
        return Set.copyOf(cells);
    }

    @Override
    public synchronized List<PhysicsAssembly> assemblies() {
        return List.copyOf(assemblies.values());
    }

    @Override
    public synchronized Optional<PhysicsAssembly> assembly(AssemblyId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(assemblies.get(id));
    }

    public synchronized Optional<AssemblyRuntimeState> runtimeState(AssemblyId id) {
        Objects.requireNonNull(id, "id");
        PhysicsAssembly assembly = assemblies.get(id);
        AssemblyRuntimeState state = runtimeStates.get(id);
        if (assembly != null && state != null) {
            state.refreshIfNeeded(assembly);
        }
        return Optional.ofNullable(state);
    }

    public synchronized AssemblyRuntimeState runtimeState(PhysicsAssembly assembly) {
        Objects.requireNonNull(assembly, "assembly");
        requireOwned(assembly);
        return runtimeStates.computeIfAbsent(assembly.id(), ignored -> new AssemblyRuntimeState(assembly));
    }

    public synchronized void refreshRuntimeShape(PhysicsAssembly assembly) {
        AssemblyRuntimeState state = runtimeState(assembly);
        state.refreshShape(assembly);
        reindexRuntimeState(state);
    }

    public synchronized void updateRuntimePose(PhysicsAssembly assembly, AssemblyPoseFrame frame) {
        AssemblyRuntimeState state = runtimeState(assembly);
        state.updatePoseFrame(assembly, frame);
        reindexRuntimeState(state);
    }

    public void refreshRuntimeStates() {
        MechanicsWorld world = KineticAssembly.api().existingWorld(level).orElse(null);
        if (world == null) {
            return;
        }
        for (PhysicsAssembly assembly : assemblies()) {
            if (assembly.state() == AssemblyLifecycleState.REMOVING) {
                continue;
            }
            world.pose(assembly.bodyId())
                    .ifPresent(pose -> trackingSystem.poseFrame(assembly, pose));
        }
    }

    private void reindexRuntimeState(AssemblyRuntimeState state) {
        removeRuntimeSpatialIndex(state.id());
        AABB bounds = state.sweptBounds().orElseGet(() -> state.worldBounds().orElse(null));
        if (bounds == null) {
            return;
        }

        Set<Long> cells = runtimeSpatialCells(bounds);
        if (cells.isEmpty()) {
            return;
        }
        runtimeSpatialCellsById.put(state.id(), cells);
        for (long cell : cells) {
            runtimeSpatialIndex.computeIfAbsent(cell, ignored -> new LinkedHashSet<>()).add(state.id());
        }
    }

    private void removeRuntimeSpatialIndex(AssemblyId id) {
        Set<Long> cells = runtimeSpatialCellsById.remove(id);
        if (cells == null) {
            return;
        }
        for (long cell : cells) {
            Set<AssemblyId> ids = runtimeSpatialIndex.get(cell);
            if (ids == null) {
                continue;
            }
            ids.remove(id);
            if (ids.isEmpty()) {
                runtimeSpatialIndex.remove(cell);
            }
        }
    }

    @Override
    public synchronized Optional<PhysicsAssembly> assemblyAtPlotBlock(BlockPos plotPos) {
        Objects.requireNonNull(plotPos, "plotPos");
        Optional<PhysicsAssembly> maybeByChunk = assemblyAtChunk(new ChunkPos(plotPos));
        if (maybeByChunk.isPresent()) {
            PhysicsAssembly assembly = maybeByChunk.get();
            if (assembly.plot().containsPlotBlockPos(plotPos)) {
                return Optional.of(assembly);
            }
            return Optional.empty();
        }
        for (PhysicsAssembly assembly : assemblies.values()) {
            if (assembly.plot().containsPlotBlockPos(plotPos)) {
                return Optional.of(assembly);
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized int size() {
        return assemblies.size();
    }

    @Override
    public synchronized boolean inPlotBounds(ChunkPos chunkPos) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        return plotAllocator.inBounds(chunkPos);
    }

    public synchronized boolean inPlotBounds(int chunkX, int chunkZ) {
        return plotAllocator.inBounds(chunkX, chunkZ);
    }

    public synchronized Optional<PhysicsAssembly> assemblyAtChunk(ChunkPos chunkPos) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        AssemblyId id = assembliesByChunk.get(chunkPos);
        return id == null ? Optional.empty() : Optional.ofNullable(assemblies.get(id));
    }

    @Override
    public Optional<AssemblyPlotProjection> plotProjection(AssemblyId id) {
        Objects.requireNonNull(id, "id");
        Optional<AssemblyPlotProjection> active = assembly(id).flatMap(this::plotProjection);
        return active.isPresent() ? active : holdingChunkMap.projection(id);
    }

    @Override
    public Optional<AssemblyPlotProjection> plotProjection(BlockPos plotPos) {
        Objects.requireNonNull(plotPos, "plotPos");
        Optional<AssemblyPlotProjection> active = assemblyAtPlotBlock(plotPos).flatMap(this::plotProjection);
        return active.isPresent() ? active : holdingChunkMap.projection(plotPos);
    }

    @Override
    public Optional<AssemblyPlotProjection> plotProjection(ChunkPos plotChunk) {
        Objects.requireNonNull(plotChunk, "plotChunk");
        Optional<AssemblyPlotProjection> active = assemblyAtChunk(plotChunk).flatMap(this::plotProjection);
        return active.isPresent() ? active : holdingChunkMap.projection(plotChunk);
    }

    private Optional<AssemblyPlotProjection> plotProjection(PhysicsAssembly assembly) {
        return trackingSystem.poseFrame(assembly)
                .map(frame -> new AssemblyPlotProjection(
                        assembly.id(),
                        assembly.plot(),
                        AssemblyCoordinateSpace.bodyToPlotOrigin(assembly),
                        frame
                ));
    }

    public synchronized AssemblyTrackingSystem trackingSystem() {
        return trackingSystem;
    }

    public AssemblyHoldingChunkMap holdingChunkMap() {
        return holdingChunkMap;
    }

    public void initializeForceLoadTickets() {
        if (forceLoadTicketsInitialized) {
            return;
        }
        forceLoadTicketsInitialized = true;
        AssemblyTicketsSavedData.getOrLoad(level);
        loadForceLoadedAssemblies();
    }

    public <T> boolean addForceLoadTicket(PhysicsAssembly assembly, AssemblyLoadingTicketType<T> ticketType, T key) {
        Objects.requireNonNull(assembly, "assembly");
        Objects.requireNonNull(ticketType, "ticketType");
        Objects.requireNonNull(key, "key");
        requireOwned(assembly);

        AssemblyLoadingTicket<T> ticket = new AssemblyLoadingTicket<>(ticketType, assembly.id(), key);
        activeTickets.computeIfAbsent(assembly.id(), ignored -> new LinkedHashSet<>()).add(ticket);
        AssemblyTicketInfo info = allTickets.computeIfAbsent(assembly.id(), ignored -> new AssemblyTicketInfo());
        if (info.tickets().add(ticket)) {
            AssemblyTicketsSavedData.getOrLoad(level).setDirty();
            return true;
        }
        return false;
    }

    public <T> boolean removeForceLoadTicket(PhysicsAssembly assembly, AssemblyLoadingTicketType<T> ticketType, T key) {
        Objects.requireNonNull(assembly, "assembly");
        Objects.requireNonNull(ticketType, "ticketType");
        Objects.requireNonNull(key, "key");
        requireOwned(assembly);

        AssemblyLoadingTicket<T> ticket = new AssemblyLoadingTicket<>(ticketType, assembly.id(), key);
        Set<AssemblyLoadingTicket<?>> active = activeTickets.get(assembly.id());
        if (active != null) {
            active.remove(ticket);
            if (active.isEmpty()) {
                activeTickets.remove(assembly.id());
            }
        }

        AssemblyTicketInfo info = allTickets.get(assembly.id());
        if (info == null) {
            return false;
        }
        boolean removed = info.tickets().remove(ticket);
        if (info.tickets().isEmpty()) {
            allTickets.remove(assembly.id());
        }
        if (removed) {
            AssemblyTicketsSavedData.getOrLoad(level).setDirty();
        }
        return removed;
    }

    Set<AssemblyId> collectForceLoadedAssemblyIds() {
        return Set.copyOf(activeTickets.keySet());
    }

    void loadTickets(Map<AssemblyId, AssemblyTicketInfo> tickets) {
        allTickets.putAll(tickets);
        for (PhysicsAssembly assembly : assemblies()) {
            ticketLoadingSystem.onAssemblyAdded(assembly);
        }
    }

    Map<AssemblyId, AssemblyTicketInfo> allTickets() {
        return Collections.unmodifiableMap(allTickets);
    }

    AssemblyTicketInfo ticketInfo(AssemblyId id) {
        return allTickets.get(id);
    }

    void setActiveTickets(PhysicsAssembly assembly, Set<AssemblyLoadingTicket<?>> tickets) {
        activeTickets.put(assembly.id(), new LinkedHashSet<>(tickets));
    }

    void clearActiveTickets(PhysicsAssembly assembly) {
        activeTickets.remove(assembly.id());
    }

    void removeTicketInfo(AssemblyId id) {
        allTickets.remove(id);
    }

    void updateSavedAssemblyPointer(AssemblyId id, GlobalSavedAssemblyPointer pointer) {
        AssemblyTicketInfo info = allTickets.get(id);
        if (info == null) {
            return;
        }
        if (pointer.equals(info.pointer())) {
            return;
        }
        info.setPointer(pointer);
        AssemblyTicketsSavedData.getOrLoad(level).setDirty();
    }

    private void loadForceLoadedAssemblies() {
        for (Map.Entry<AssemblyId, AssemblyTicketInfo> entry : List.copyOf(allTickets.entrySet())) {
            AssemblyId id = entry.getKey();
            if (assembly(id).isPresent()) {
                continue;
            }
            GlobalSavedAssemblyPointer pointer = entry.getValue().pointer();
            if (pointer == null) {
                KineticAssembly.LOGGER.error("Cannot force-load assembly {} because its ticket info has no saved pointer", id);
                continue;
            }
            holdingChunkMap.snatchAndLoad(pointer, id);
        }
    }

    @Override
    public List<AssemblyCollisionTarget> collisionTargets(AABB worldBounds, @Nullable AssemblyId forcedId) {
        Objects.requireNonNull(worldBounds, "worldBounds");
        long profileStarted = AssemblyProfiler.start();
        try {
            MechanicsWorld world = KineticAssembly.api().existingWorld(level).orElse(null);
            if (world == null) {
                AssemblyProfiler.recordCollisionTargets(0, 0, 0, forcedId != null);
                return List.of();
            }

            AABB queryBounds = worldBounds.inflate(1.0E-7D);
            List<PhysicsAssembly> candidates = collisionCandidateAssemblies(queryBounds, forcedId);
            if (candidates.isEmpty()) {
                AssemblyProfiler.recordCollisionTargets(0, 0, 0, forcedId != null);
                return List.of();
            }

            int blockCount = 0;
            List<AssemblyCollisionTarget> targets = new ArrayList<>();
            for (PhysicsAssembly assembly : candidates) {
                if (assembly.state() == AssemblyLifecycleState.REMOVING) {
                    continue;
                }
                AssemblyRuntimeState runtimeState = runtimeState(assembly);
                runtimeState.refreshIfNeeded(assembly);
                if (!runtimeState.hasCollision()) {
                    continue;
                }
                AssemblyPoseFrame frame = runtimeState.poseFrame().orElse(null);
                if (frame == null) {
                    PhysicsPose pose = world.pose(assembly.bodyId()).orElse(null);
                    if (pose == null) {
                        continue;
                    }
                    frame = trackingSystem.poseFrame(assembly, pose);
                }
                AABB worldBoundsForFrame = runtimeState.worldBounds().orElse(null);
                boolean forced = forcedId != null && forcedId.equals(assembly.id());
                if (!forced) {
                    AABB sweptBounds = runtimeState.sweptBounds().orElse(worldBoundsForFrame);
                    if (sweptBounds == null || !sweptBounds.intersects(queryBounds)) {
                        continue;
                    }
                }

                List<AssemblyCollisionBlock> collisionBlocks = forced
                        ? runtimeState.collisionBlocks()
                        : runtimeState.collisionBlocks(localCollisionQuery(frame, queryBounds));
                if (collisionBlocks.isEmpty()) {
                    continue;
                }
                blockCount += collisionBlocks.size();
                AssemblyCollisionTarget target = new AssemblyCollisionTarget(
                        frame,
                        collisionBlocks,
                        level,
                        assembly.plot(),
                        runtimeState.bodyToPlotOrigin()
                );
                if (forced) {
                    targets.add(target);
                    continue;
                }
                targets.add(target);
            }
            AssemblyProfiler.recordCollisionTargets(candidates.size(), targets.size(), blockCount, forcedId != null);
            return List.copyOf(targets);
        } finally {
            AssemblyProfiler.record("assembly.collisionTargets", profileStarted);
        }
    }

    private static AABB localCollisionQuery(AssemblyPoseFrame frame, AABB queryBounds) {
        AABB previousLocal = frame.previousTransform().worldAabbToLocalBounds(queryBounds);
        AABB currentLocal = frame.currentTransform().worldAabbToLocalBounds(queryBounds);
        return previousLocal.minmax(currentLocal).inflate(1.0D);
    }

    private synchronized List<PhysicsAssembly> collisionCandidateAssemblies(AABB queryBounds, @Nullable AssemblyId forcedId) {
        if (forcedId != null) {
            return assemblies.get(forcedId) == null ? List.of() : List.of(assemblies.get(forcedId));
        }
        if (assemblies.isEmpty()) {
            return List.of();
        }
        refreshRuntimeSpatialIndex();
        if (runtimeSpatialIndex.isEmpty()) {
            return List.copyOf(assemblies.values());
        }

        Set<AssemblyId> candidateIds = new LinkedHashSet<>();
        for (long cell : runtimeSpatialCells(queryBounds)) {
            Set<AssemblyId> ids = runtimeSpatialIndex.get(cell);
            if (ids != null) {
                candidateIds.addAll(ids);
            }
        }
        if (candidateIds.isEmpty()) {
            return List.of();
        }

        List<PhysicsAssembly> candidates = new ArrayList<>();
        for (AssemblyId id : candidateIds) {
            PhysicsAssembly assembly = assemblies.get(id);
            if (assembly != null) {
                candidates.add(assembly);
            }
        }
        return List.copyOf(candidates);
    }

    private void refreshRuntimeSpatialIndex() {
        for (PhysicsAssembly assembly : assemblies.values()) {
            AssemblyRuntimeState state = runtimeStates.computeIfAbsent(assembly.id(), ignored -> new AssemblyRuntimeState(assembly));
            long previousShapeEpoch = state.shapeEpoch();
            state.refreshIfNeeded(assembly);
            if (state.shapeEpoch() != previousShapeEpoch) {
                reindexRuntimeState(state);
            }
        }
    }

    public synchronized Optional<LevelChunk> plotChunk(ChunkPos chunkPos) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        return plotChunkHolder(chunkPos).map(PlotChunkHolder::chunk);
    }

    public synchronized List<LevelChunk> plotChunks() {
        return plotChunkHolders.values().stream()
                .map(PlotChunkHolder::chunk)
                .toList();
    }

    public synchronized Optional<PlotChunkHolder> plotChunkHolder(ChunkPos chunkPos) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        return Optional.ofNullable(plotChunkHolders.get(chunkPos));
    }

    public synchronized List<PlotChunkHolder> plotChunkHolders() {
        return List.copyOf(plotChunkHolders.values());
    }

    public boolean isRebuildingPlotChunks() {
        return rebuildingPlotChunks.get();
    }

    public void rebuildPlotChunks(PhysicsAssembly assembly) {
        Objects.requireNonNull(assembly, "assembly");
        refreshBlockEntityTagsFromLiveChunks(assembly);
        RebuiltPlotChunks rebuilt = buildPlotChunkHolders(assembly);
        List<LevelChunk> rebuiltChunks = rebuilt.holders().values().stream()
                .map(PlotChunkHolder::chunk)
                .toList();
        installRebuiltPlotChunks(assembly, rebuilt.holders());
        assembly.replaceBlockEntities(rebuilt.blockEntitiesByLocalPos());
        trackingSystem.onAssemblyChunksRebuilt(assembly, rebuiltChunks);
    }

    public boolean setPlotChunkBlockStateInPlace(BlockPos plotPos, BlockState blockState, boolean isMoving) {
        Objects.requireNonNull(plotPos, "plotPos");
        Objects.requireNonNull(blockState, "blockState");
        LevelChunk chunk = plotChunk(new ChunkPos(plotPos)).orElse(null);
        if (chunk == null) {
            return false;
        }

        rebuildingPlotChunks.set(true);
        try {
            BlockState previous = chunk.setBlockState(plotPos, blockState, isMoving);
            return previous != null || chunk.getBlockState(plotPos).equals(blockState);
        } finally {
            rebuildingPlotChunks.set(false);
        }
    }

    public void removePlotChunkBlockEntityInPlace(BlockPos plotPos) {
        Objects.requireNonNull(plotPos, "plotPos");
        LevelChunk chunk = plotChunk(new ChunkPos(plotPos)).orElse(null);
        if (chunk == null) {
            return;
        }

        rebuildingPlotChunks.set(true);
        try {
            chunk.removeBlockEntity(plotPos);
        } finally {
            rebuildingPlotChunks.set(false);
        }
    }

    public void moveSourceScheduledTicksToPlot(PhysicsAssembly assembly) {
        Objects.requireNonNull(assembly, "assembly");
        requireOwned(assembly);

        AssemblyBounds bounds = assembly.bounds();
        BoundingBox sourceArea = BoundingBox.fromCorners(bounds.minSourcePos(), bounds.maxSourcePos());
        BlockPos plotMin = assembly.plot().toPlotBlockPos(bounds.toLocal(bounds.minSourcePos()));
        Vec3i offset = new Vec3i(
                plotMin.getX() - bounds.minSourcePos().getX(),
                plotMin.getY() - bounds.minSourcePos().getY(),
                plotMin.getZ() - bounds.minSourcePos().getZ()
        );

        level.getBlockTicks().copyArea(sourceArea, offset);
        level.getBlockTicks().clearArea(sourceArea);
        level.getFluidTicks().copyArea(sourceArea, offset);
        level.getFluidTicks().clearArea(sourceArea);
    }

    public void movePlotScheduledTicksToSource(PhysicsAssembly assembly) {
        Objects.requireNonNull(assembly, "assembly");
        requireOwned(assembly);

        AssemblyBounds bounds = assembly.bounds();
        BlockPos plotMin = assembly.plot().toPlotBlockPos(bounds.toLocal(bounds.minSourcePos()));
        BlockPos plotMax = assembly.plot().toPlotBlockPos(bounds.toLocal(bounds.maxSourcePos()));
        BoundingBox plotArea = BoundingBox.fromCorners(plotMin, plotMax);
        Vec3i offset = new Vec3i(
                bounds.minSourcePos().getX() - plotMin.getX(),
                bounds.minSourcePos().getY() - plotMin.getY(),
                bounds.minSourcePos().getZ() - plotMin.getZ()
        );

        level.getBlockTicks().copyArea(plotArea, offset);
        level.getBlockTicks().clearArea(plotArea);
        level.getFluidTicks().copyArea(plotArea, offset);
        level.getFluidTicks().clearArea(plotArea);
    }

    public void movePlotScheduledTicksToChild(PhysicsAssembly source, PhysicsAssembly child) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(child, "child");
        requireOwned(source);
        requireOwned(child);

        for (AssemblyBlock block : child.blocks()) {
            BlockPos oldPlotPos = source.plot().toPlotBlockPos(source.bounds().toLocal(block.sourcePos()));
            BlockPos newPlotPos = child.plot().toPlotBlockPos(block.localPos());
            moveScheduledTickAt(oldPlotPos, newPlotPos);
        }
    }

    public void primePlotBlockUpdates(PhysicsAssembly assembly) {
        Objects.requireNonNull(assembly, "assembly");
        requireOwned(assembly);

        for (AssemblyBlock block : List.copyOf(assembly.blocks())) {
            BlockPos plotPos = assembly.plot().toPlotBlockPos(block.localPos());
            Block currentBlock = level.getBlockState(plotPos).getBlock();
            if (currentBlock != block.blockState().getBlock()) {
                continue;
            }
            level.neighborChanged(plotPos, currentBlock, plotPos);
            for (Direction direction : Direction.values()) {
                BlockPos neighborPos = plotPos.relative(direction);
                level.neighborChanged(plotPos, level.getBlockState(neighborPos).getBlock(), neighborPos);
            }
            level.updateNeighborsAt(plotPos, currentBlock);
            for (Direction direction : Direction.values()) {
                level.updateNeighborsAt(plotPos.relative(direction), currentBlock);
            }
        }
    }

    public synchronized void requestPlotBlockUpdatePrime(PhysicsAssembly assembly) {
        Objects.requireNonNull(assembly, "assembly");
        requireOwned(assembly);
        pendingBlockUpdatePrimes.merge(
                assembly.id(),
                DEFAULT_BLOCK_UPDATE_PRIME_TICKS,
                Math::max
        );
    }

    public void tickPlotBlockUpdatePrimes() {
        List<PhysicsAssembly> toPrime = new ArrayList<>();
        synchronized (this) {
            pendingBlockUpdatePrimes.entrySet().removeIf(entry -> {
                PhysicsAssembly assembly = assemblies.get(entry.getKey());
                if (assembly == null) {
                    return true;
                }
                toPrime.add(assembly);
                int remaining = entry.getValue() - 1;
                if (remaining <= 0) {
                    return true;
                }
                entry.setValue(remaining);
                return false;
            });
        }
        for (PhysicsAssembly assembly : toPrime) {
            primePlotBlockUpdates(assembly);
        }
    }

    private void moveScheduledTickAt(BlockPos from, BlockPos to) {
        BoundingBox area = new BoundingBox(from);
        Vec3i offset = new Vec3i(
                to.getX() - from.getX(),
                to.getY() - from.getY(),
                to.getZ() - from.getZ()
        );
        level.getBlockTicks().copyArea(area, offset);
        level.getBlockTicks().clearArea(area);
        level.getFluidTicks().copyArea(area, offset);
        level.getFluidTicks().clearArea(area);
    }

    private void requireOwned(PhysicsAssembly assembly) {
        PhysicsAssembly current;
        synchronized (this) {
            current = assemblies.get(assembly.id());
        }
        if (current != assembly) {
            throw new IllegalArgumentException("Assembly is not owned by this container: " + assembly.id());
        }
    }

    private synchronized void installRebuiltPlotChunks(PhysicsAssembly assembly, Map<ChunkPos, PlotChunkHolder> rebuilt) {
        PhysicsAssembly current = assemblies.get(assembly.id());
        if (current != assembly) {
            throw new IllegalArgumentException("Assembly is not owned by this container: " + assembly.id());
        }

        removePlotChunks(assembly, false);
        for (Map.Entry<ChunkPos, PlotChunkHolder> entry : rebuilt.entrySet()) {
            ChunkPos chunkPos = entry.getKey();
            AssemblyId occupant = assembliesByChunk.get(chunkPos);
            if (occupant != null && !occupant.equals(assembly.id())) {
                throw new IllegalStateException("Plot chunk " + chunkPos + " is already owned by " + occupant);
            }
            assembliesByChunk.put(chunkPos, assembly.id());
            PlotChunkHolder holder = entry.getValue();
            plotChunkHolders.put(chunkPos, holder);
            registerPlotChunk(holder);
        }
    }

    private RebuiltPlotChunks buildPlotChunkHolders(PhysicsAssembly assembly) {
        Map<ChunkPos, PreservedTicks> preservedTicks = preservedPlotTicks(assembly);
        Map<ChunkPos, LevelChunk> chunks = new LinkedHashMap<>();
        Map<BlockPos, BlockEntity> blockEntitiesByLocalPos = new LinkedHashMap<>();
        for (ChunkPos chunkPos : assembly.plot().chunkPositions()) {
            chunks.put(chunkPos, newPlotChunk(chunkPos, preservedTicks.get(chunkPos)));
        }
        for (AssemblyBlock block : assembly.blocks()) {
            BlockPos plotPos = assembly.plot().toPlotBlockPos(block.localPos());
            ChunkPos chunkPos = new ChunkPos(plotPos);
            if (!assembly.plot().containsChunk(chunkPos)) {
                throw new IllegalStateException("Block " + plotPos + " is outside plot " + assembly.plot().describe());
            }
            LevelChunk chunk = chunks.computeIfAbsent(chunkPos, pos -> newPlotChunk(pos, preservedTicks.get(pos)));
            rebuildingPlotChunks.set(true);
            try {
                chunk.setBlockState(plotPos, block.blockState(), false);
                addBlockEntity(chunk, assembly, blockEntitiesByLocalPos, block, plotPos);
            } finally {
                rebuildingPlotChunks.set(false);
            }
        }

        Map<ChunkPos, PlotChunkHolder> holders = new LinkedHashMap<>();
        for (Map.Entry<ChunkPos, LevelChunk> entry : chunks.entrySet()) {
            holders.put(entry.getKey(), PlotChunkHolder.create(level, entry.getValue()));
        }
        return new RebuiltPlotChunks(holders, blockEntitiesByLocalPos);
    }

    private LevelChunk newPlotChunk(ChunkPos chunkPos, PreservedTicks preservedTicks) {
        LevelChunk chunk = preservedTicks == null
                ? new LevelChunk(level, chunkPos)
                : new LevelChunk(
                        level,
                        chunkPos,
                        UpgradeData.EMPTY,
                        preservedTicks.blockTicks(),
                        preservedTicks.fluidTicks(),
                        0L,
                        null,
                        null,
                        null
                );
        chunk.setFullStatus(() -> FullChunkStatus.ENTITY_TICKING);
        return chunk;
    }

    private List<ChunkPos> removePlotChunks(PhysicsAssembly assembly, boolean clearScheduledTicks) {
        if (clearScheduledTicks) {
            clearPlotScheduledTicks(assembly);
        }
        List<ChunkPos> removedChunks = new ArrayList<>();
        for (ChunkPos chunkPos : assembly.plot().chunkPositions()) {
            AssemblyId occupant = assembliesByChunk.get(chunkPos);
            if (assembly.id().equals(occupant)) {
                assembliesByChunk.remove(chunkPos);
                PlotChunkHolder holder = plotChunkHolders.remove(chunkPos);
                if (holder != null) {
                    unregisterPlotChunk(holder);
                    removedChunks.add(chunkPos);
                }
            }
        }
        return List.copyOf(removedChunks);
    }

    private synchronized Map<ChunkPos, PreservedTicks> preservedPlotTicks(PhysicsAssembly assembly) {
        Map<ChunkPos, PreservedTicks> preserved = new LinkedHashMap<>();
        for (ChunkPos chunkPos : assembly.plot().chunkPositions()) {
            PlotChunkHolder holder = plotChunkHolders.get(chunkPos);
            if (holder != null && assembly.id().equals(assembliesByChunk.get(chunkPos))) {
                preserved.put(chunkPos, copyTicks(holder.chunk()));
            }
        }
        return Map.copyOf(preserved);
    }

    @SuppressWarnings("unchecked")
    private static PreservedTicks copyTicks(LevelChunk chunk) {
        return new PreservedTicks(
                copyTicks((LevelChunkTicks<Block>) chunk.getTicksForSerialization().blocks()),
                copyTicks((LevelChunkTicks<Fluid>) chunk.getTicksForSerialization().fluids())
        );
    }

    private static <T> LevelChunkTicks<T> copyTicks(LevelChunkTicks<T> source) {
        LevelChunkTicks<T> copy = new LevelChunkTicks<>();
        source.getAll().forEach(copy::schedule);
        return copy;
    }

    private void registerPlotChunk(PlotChunkHolder holder) {
        LevelChunk chunk = holder.chunk();
        ChunkPos pos = chunk.getPos();
        ServerChunkCache chunkSource = level.getChunkSource();
        chunkSource.chunkMap.updatingChunkMap.put(pos.toLong(), holder);
        chunkSource.chunkMap.modified = true;

        chunk.setFullStatus(() -> FullChunkStatus.ENTITY_TICKING);
        chunk.runPostLoad();
        chunk.setLoaded(true);
        chunk.getBlockEntities().values().forEach(BlockEntity::clearRemoved);
        chunk.registerAllBlockEntitiesAfterLevelLoad();
        chunk.registerTickContainerInLevel(level);
        level.startTickingChunk(chunk);

        level.entityManager.updateChunkStatus(pos, FullChunkStatus.ENTITY_TICKING);
        chunkSource.chunkMap.onFullChunkStatusChange(pos, FullChunkStatus.ENTITY_TICKING);
    }

    private void unregisterPlotChunk(PlotChunkHolder holder) {
        LevelChunk chunk = holder.chunk();
        ChunkPos pos = chunk.getPos();
        ServerChunkCache chunkSource = level.getChunkSource();
        if (chunkSource.chunkMap.updatingChunkMap.get(pos.toLong()) == holder) {
            chunkSource.chunkMap.updatingChunkMap.remove(pos.toLong());
            chunkSource.chunkMap.modified = true;
        }

        chunk.setLoaded(false);
        level.unload(chunk);
        level.entityManager.updateChunkStatus(pos, FullChunkStatus.INACCESSIBLE);
    }

    private void clearPlotScheduledTicks(PhysicsAssembly assembly) {
        BoundingBox plotArea = new BoundingBox(
                assembly.plot().minPlotX(),
                assembly.plot().minPlotY(),
                assembly.plot().minPlotZ(),
                assembly.plot().maxPlotX(),
                assembly.plot().maxPlotY(),
                assembly.plot().maxPlotZ()
        );
        level.getBlockTicks().clearArea(plotArea);
        level.getFluidTicks().clearArea(plotArea);
    }

    private void addBlockEntity(
            LevelChunk chunk,
            PhysicsAssembly assembly,
            Map<BlockPos, BlockEntity> blockEntitiesByLocalPos,
            AssemblyBlock block,
            BlockPos plotPos
    ) {
        BlockEntity blockEntity = createBlockEntity(block, plotPos);
        if (blockEntity != null) {
            blockEntitiesByLocalPos.put(block.localPos().immutable(), blockEntity);
            chunk.setBlockEntity(blockEntity);
        }
    }

    private void refreshBlockEntityTagsFromLiveChunks(PhysicsAssembly assembly) {
        for (AssemblyBlock block : assembly.blocks()) {
            BlockPos plotPos = assembly.plot().toPlotBlockPos(block.localPos());
            BlockEntity blockEntity = plotChunk(new ChunkPos(plotPos))
                    .map(chunk -> chunk.getBlockEntity(plotPos))
                    .filter(entity -> !entity.isRemoved())
                    .orElse(null);
            if (blockEntity != null) {
                assembly.section().updateBlockEntityTag(
                        block.localPos(),
                        blockEntity.saveWithFullMetadata(level.registryAccess())
                );
            }
        }
    }

    public void refreshBlockEntityTags(PhysicsAssembly assembly) {
        Objects.requireNonNull(assembly, "assembly");
        requireOwned(assembly);
        refreshBlockEntityTagsFromLiveChunks(assembly);
    }

    private BlockEntity createBlockEntity(AssemblyBlock block, BlockPos plotPos) {
        BlockEntity blockEntity = null;
        CompoundTag tag = block.blockEntityTag();
        if (tag != null) {
            CompoundTag plotTag = tag.copy();
            plotTag.putInt("x", plotPos.getX());
            plotTag.putInt("y", plotPos.getY());
            plotTag.putInt("z", plotPos.getZ());
            blockEntity = BlockEntity.loadStatic(plotPos, block.blockState(), plotTag, level.registryAccess());
        }
        if (blockEntity == null && block.blockState().getBlock() instanceof EntityBlock entityBlock) {
            blockEntity = entityBlock.newBlockEntity(plotPos, block.blockState());
        }
        if (blockEntity != null) {
            blockEntity.setLevel(level);
            blockEntity.clearRemoved();
        }
        return blockEntity;
    }

    private record RemovedAssembly(PhysicsAssembly assembly, List<ChunkPos> removedChunks) {
        private RemovedAssembly {
            Objects.requireNonNull(assembly, "assembly");
            removedChunks = List.copyOf(removedChunks);
        }
    }

    private record PreservedTicks(LevelChunkTicks<Block> blockTicks, LevelChunkTicks<Fluid> fluidTicks) {
        private PreservedTicks {
            Objects.requireNonNull(blockTicks, "blockTicks");
            Objects.requireNonNull(fluidTicks, "fluidTicks");
        }
    }

    private record RebuiltPlotChunks(
            Map<ChunkPos, PlotChunkHolder> holders,
            Map<BlockPos, BlockEntity> blockEntitiesByLocalPos
    ) {
        private RebuiltPlotChunks {
            holders = Map.copyOf(holders);
            blockEntitiesByLocalPos = Map.copyOf(blockEntitiesByLocalPos);
        }
    }
}
