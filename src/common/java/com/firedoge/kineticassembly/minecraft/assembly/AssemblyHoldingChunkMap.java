package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.mechanics.MechanicsBodySnapshot;
import com.firedoge.kineticassembly.mechanics.MechanicsWorld;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

public final class AssemblyHoldingChunkMap {
    private static final double MAX_PREDICTION_DISTANCE = 20.0D;
    private static final double PREDICTION_MOVEMENT_THRESHOLD = 0.05D;
    private static final double PHYSICS_TICK_SECONDS = 1.0D / 20.0D;
    private static final long FORCED_TICKET_REFRESH_INTERVAL_TICKS = 5L;
    private static final long ACTIVE_UNLOAD_SCAN_INTERVAL_TICKS = 10L;
    private static final int MAX_CHUNK_UNLOADS_PER_TICK = 8;
    private static final int MAX_CHUNK_LOADS_PER_TICK = 8;
    private static final TicketType<AssemblyId> ASSEMBLY_LOADED_TICKET_TYPE = TicketType.create(
            "kinetic_assembly_assembly_loaded",
            (first, second) -> first.value().compareTo(second.value())
    );
    private static final int ASSEMBLY_LOADED_TICKET_DISTANCE =
            ChunkLevel.byStatus(FullChunkStatus.FULL) - ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING);
    private final ServerLevel level;
    private final ServerAssemblyContainer container;
    private AssemblyStorage storage;
    private final Map<ChunkPos, HoldingChunk> loadedHoldingChunks = new LinkedHashMap<>();
    private final Map<AssemblyId, HeldAssembly> heldAssemblies = new LinkedHashMap<>();
    private final Map<Long, Map<AssemblyId, Long>> forcedInhabitedChunks = new LinkedHashMap<>();
    private final Set<Long> chunksToUnload = new LinkedHashSet<>();
    private final Set<Long> chunksToLoad = new LinkedHashSet<>();
    private final Set<Long> dirtyHoldingChunks = new LinkedHashSet<>();
    private final Set<GlobalSavedAssemblyPointer> queuedDeletions = new LinkedHashSet<>();
    private long nextForcedTicketRefreshGameTime;
    private long nextActiveUnloadScanGameTime;

    AssemblyHoldingChunkMap(ServerLevel level, ServerAssemblyContainer container) {
        this.level = Objects.requireNonNull(level, "level");
        this.container = Objects.requireNonNull(container, "container");
    }

    public synchronized void updateChunkStatus(ChunkPos chunkPos, boolean loaded) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        if (container.inPlotBounds(chunkPos)) {
            return;
        }
        long key = chunkPos.toLong();
        if (loaded) {
            chunksToUnload.remove(key);
            chunksToLoad.add(key);
        } else {
            chunksToUnload.add(key);
            chunksToLoad.remove(key);
        }
    }

    public void processChanges() {
        long sectionStarted = AssemblyProfiler.start();
        try {
            processChunkStatusChanges();
        } finally {
            AssemblyProfiler.record("holding.processChunkStatus", sectionStarted);
        }

        sectionStarted = AssemblyProfiler.start();
        try {
            updateForcedInhabitedChunkTickets();
        } finally {
            AssemblyProfiler.record("holding.updateForcedTickets", sectionStarted);
        }

        sectionStarted = AssemblyProfiler.start();
        try {
            moveActiveAssembliesInUnloadedChunks();
        } finally {
            AssemblyProfiler.record("holding.moveActiveToUnloaded", sectionStarted);
        }

        sectionStarted = AssemblyProfiler.start();
        try {
            restoreReadyAssemblies();
        } finally {
            AssemblyProfiler.record("holding.restoreReady", sectionStarted);
        }

        sectionStarted = AssemblyProfiler.start();
        try {
            saveDirtyHoldingChunks();
        } finally {
            AssemblyProfiler.record("holding.saveDirtyChunks", sectionStarted);
        }
    }

    public void saveAll() {
        saveAll(true);
    }

    public void saveAll(boolean flushStorage) {
        saveForceLoadedActiveAssemblies();
        processQueuedDeletions();
        saveDirtyHoldingChunks();
        if (flushStorage) {
            storage().ifPresent(AssemblyStorage::flush);
        }
    }

    public synchronized boolean isEmpty() {
        return heldAssemblies.isEmpty();
    }

    public synchronized List<CompoundTag> heldAssemblies() {
        return heldAssemblies.values().stream()
                .map(HeldAssembly::tag)
                .map(CompoundTag::copy)
                .toList();
    }

    public synchronized Optional<AssemblyPlotProjection> projection(AssemblyId id) {
        Objects.requireNonNull(id, "id");
        HeldAssembly heldAssembly = heldAssemblies.get(id);
        return heldAssembly == null
                ? Optional.empty()
                : AssemblyPersistence.projectionFromRecord(level, heldAssembly.tag());
    }

    public synchronized Optional<AssemblyPlotProjection> projection(BlockPos plotPos) {
        Objects.requireNonNull(plotPos, "plotPos");
        for (HeldAssembly heldAssembly : heldAssemblies.values()) {
            AssemblyPlotProjection projection = AssemblyPersistence.projectionFromRecord(level, heldAssembly.tag())
                    .orElse(null);
            if (projection != null && projection.plot().containsPlotBlockPos(plotPos)) {
                return Optional.of(projection);
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<AssemblyPlotProjection> projection(ChunkPos plotChunk) {
        Objects.requireNonNull(plotChunk, "plotChunk");
        for (HeldAssembly heldAssembly : heldAssemblies.values()) {
            AssemblyPlotProjection projection = AssemblyPersistence.projectionFromRecord(level, heldAssembly.tag())
                    .orElse(null);
            if (projection != null && projection.plot().containsChunk(plotChunk)) {
                return Optional.of(projection);
            }
        }
        return Optional.empty();
    }

    synchronized Optional<GlobalSavedAssemblyPointer> pointer(AssemblyId id) {
        Objects.requireNonNull(id, "id");
        HeldAssembly heldAssembly = heldAssemblies.get(id);
        if (heldAssembly != null) {
            return heldAssembly.pointer();
        }
        return container.assembly(id).flatMap(PhysicsAssembly::lastSerializationPointer);
    }

    Optional<AssemblyPlotProjection> projection(GlobalSavedAssemblyPointer pointer) {
        Objects.requireNonNull(pointer, "pointer");
        synchronized (this) {
            for (HeldAssembly heldAssembly : heldAssemblies.values()) {
                if (pointer.equals(heldAssembly.pointer().orElse(null))) {
                    return AssemblyPersistence.projectionFromRecord(level, heldAssembly.tag());
                }
            }
        }
        return storage()
                .flatMap(storage -> storage.attemptLoadAssembly(pointer.chunkPos(), pointer.local()))
                .flatMap(tag -> AssemblyPersistence.projectionFromRecord(level, tag));
    }

    public void snatchAndLoad(GlobalSavedAssemblyPointer pointer, AssemblyId expectedId) {
        Objects.requireNonNull(pointer, "pointer");
        Objects.requireNonNull(expectedId, "expectedId");
        if (container.assembly(expectedId).isPresent()) {
            return;
        }

        Optional<AssemblyStorage> maybeStorage = storage();
        if (maybeStorage.isEmpty()) {
            return;
        }
        getOrLoadHoldingChunk(pointer.chunkPos(), false);
        CompoundTag tag = maybeStorage.get().attemptLoadAssembly(pointer.chunkPos(), pointer.local()).orElse(null);
        if (tag == null) {
            KineticAssembly.LOGGER.warn("Failed to force-load assembly {} from missing pointer {}", expectedId, pointer);
            return;
        }

        AssemblyId id = AssemblyPersistence.recordId(tag).orElse(null);
        if (id == null) {
            KineticAssembly.LOGGER.warn("Failed to force-load assembly {} from pointer {} because the record has no id", expectedId, pointer);
            return;
        }
        if (!expectedId.equals(id)) {
            KineticAssembly.LOGGER.warn("Force-load pointer {} resolved to assembly {}, not expected {}", pointer, id, expectedId);
        }
        acceptLoadedHeldAssembly(pointer.chunkPos(), id, tag, pointer);
        restoreReadyAssemblies();
    }

    public synchronized void queueDeletion(PhysicsAssembly assembly) {
        Objects.requireNonNull(assembly, "assembly");
        assembly.lastSerializationPointer().ifPresent(pointer -> queueDeletion(assembly.id(), pointer));
        assembly.setLastSerializationPointer(null);
    }

    private void processChunkStatusChanges() {
        List<ChunkPos> unloads;
        List<ChunkPos> loads;
        synchronized (this) {
            if (chunksToUnload.isEmpty() && chunksToLoad.isEmpty()) {
                return;
            }
            unloads = drainChunkPositions(chunksToUnload, MAX_CHUNK_UNLOADS_PER_TICK);
            loads = drainChunkPositions(chunksToLoad, MAX_CHUNK_LOADS_PER_TICK);
        }

        long sectionStarted = AssemblyProfiler.start();
        try {
            unloadAssembliesIntersecting(unloads);
        } finally {
            AssemblyProfiler.record("holding.chunkStatus.unloads", sectionStarted);
        }

        sectionStarted = AssemblyProfiler.start();
        try {
            for (ChunkPos chunkPos : loads) {
                processLoad(chunkPos);
            }
        } finally {
            AssemblyProfiler.record("holding.chunkStatus.loads", sectionStarted);
        }
    }

    private static List<ChunkPos> drainChunkPositions(Set<Long> chunks, int limit) {
        if (chunks.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<ChunkPos> result = new ArrayList<>(Math.min(chunks.size(), limit));
        var iterator = chunks.iterator();
        while (iterator.hasNext() && result.size() < limit) {
            long chunk = iterator.next();
            iterator.remove();
            result.add(new ChunkPos(chunk));
        }
        return List.copyOf(result);
    }

    private void processLoad(ChunkPos chunkPos) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        getOrLoadHoldingChunk(chunkPos, false);
    }

    private synchronized Optional<HoldingChunk> getOrLoadHoldingChunk(ChunkPos chunkPos, boolean create) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        HoldingChunk existing = loadedHoldingChunks.get(chunkPos);
        if (existing != null) {
            return Optional.of(existing);
        }

        Optional<AssemblyStorage> maybeStorage = storage();
        if (maybeStorage.isPresent()) {
            AssemblyStorage storage = maybeStorage.get();
            Optional<AssemblyStorage.HoldingChunkData> data = storage.attemptLoadHoldingChunk(chunkPos);
            if (data.isPresent()) {
                HoldingChunk holdingChunk = new HoldingChunk(chunkPos);
                for (SavedAssemblyPointer pointer : data.get().pointers()) {
                    holdingChunk.acceptPointer(pointer);
                }
                loadedHoldingChunks.put(chunkPos, holdingChunk);
                for (SavedAssemblyPointer pointer : data.get().pointers()) {
                    storage.attemptLoadAssembly(chunkPos, pointer).ifPresent(tag -> AssemblyPersistence.recordId(tag).ifPresent(id -> {
                        if (container.assembly(id).isPresent()) {
                            return;
                        }
                        acceptLoadedHeldAssembly(
                                chunkPos,
                                id,
                                tag,
                                new GlobalSavedAssemblyPointer(chunkPos, pointer.storageIndex(), pointer.assemblyIndex())
                        );
                    }));
                }
                return Optional.of(holdingChunk);
            }
        }

        if (!create) {
            return Optional.empty();
        }
        HoldingChunk holdingChunk = new HoldingChunk(chunkPos);
        loadedHoldingChunks.put(chunkPos, holdingChunk);
        return Optional.of(holdingChunk);
    }

    private void moveActiveAssembliesInUnloadedChunks() {
        long gameTime = level.getGameTime();
        if (gameTime < nextActiveUnloadScanGameTime) {
            return;
        }
        nextActiveUnloadScanGameTime = gameTime + ACTIVE_UNLOAD_SCAN_INTERVAL_TICKS;

        MechanicsWorld world = KineticAssembly.api().existingWorld(level).orElse(null);
        if (world == null) {
            return;
        }

        List<ActiveAssemblyState> states = activeAssemblyStates(world);
        if (states.isEmpty()) {
            return;
        }

        Set<AssemblyId> forceLoadedRoots = container.collectForceLoadedAssemblyIds();
        Map<AssemblyId, List<AssemblyId>> dependenciesById = null;
        Set<AssemblyId> forceLoaded = forceLoadedRoots;
        if (!forceLoadedRoots.isEmpty()) {
            dependenciesById = dependenciesById(states);
            forceLoaded = dependencyClosure(forceLoadedRoots, dependenciesById);
        }

        Map<AssemblyId, ChunkPos> unloadTargets = new LinkedHashMap<>();
        for (ActiveAssemblyState state : states) {
            AssemblyId id = state.assembly().id();
            if (forceLoaded.contains(id)) {
                continue;
            }
            firstUnloadedChunk(state.loadingBounds()).ifPresent(unloaded -> unloadTargets.put(id, unloaded));
        }
        if (unloadTargets.isEmpty()) {
            return;
        }

        Map<AssemblyId, ActiveAssemblyState> statesById = statesById(states);
        if (dependenciesById == null) {
            dependenciesById = dependenciesById(states);
        }

        Set<AssemblyId> moved = new LinkedHashSet<>();
        for (Map.Entry<AssemblyId, ChunkPos> entry : unloadTargets.entrySet()) {
            AssemblyId id = entry.getKey();
            if (moved.contains(id)) {
                continue;
            }
            List<ActiveAssemblyState> chain = dependencyChain(id, statesById, dependenciesById);
            if (moveToUnloaded(world, chain, entry.getValue())) {
                for (ActiveAssemblyState movedState : chain) {
                    moved.add(movedState.assembly().id());
                }
            }
        }
    }

    private void updateForcedInhabitedChunkTickets() {
        long gameTime = level.getGameTime();
        expireForcedInhabitedChunkTickets(gameTime);

        Set<AssemblyId> forceLoadedRoots = container.collectForceLoadedAssemblyIds();
        if (forceLoadedRoots.isEmpty()) {
            return;
        }
        if (gameTime < nextForcedTicketRefreshGameTime) {
            return;
        }
        nextForcedTicketRefreshGameTime = gameTime + FORCED_TICKET_REFRESH_INTERVAL_TICKS;

        MechanicsWorld world = KineticAssembly.api().existingWorld(level).orElse(null);
        if (world == null) {
            return;
        }

        List<ActiveAssemblyState> states = activeAssemblyStates(world);
        Map<AssemblyId, List<AssemblyId>> dependenciesById = dependenciesById(states);
        Set<AssemblyId> forceLoaded = dependencyClosure(forceLoadedRoots, dependenciesById);
        for (ActiveAssemblyState state : states) {
            AssemblyId id = state.assembly().id();
            if (forceLoaded.contains(id)) {
                refreshForcedInhabitedChunks(state.loadingBounds(), id, gameTime);
            }
        }
    }

    private void refreshForcedInhabitedChunks(AABB bounds, AssemblyId id, long gameTime) {
        long profileStarted = AssemblyProfiler.start();
        try {
            int minChunkX = Mth.floor(bounds.minX) >> 4;
            int maxChunkX = Mth.floor(bounds.maxX) >> 4;
            int minChunkZ = Mth.floor(bounds.minZ) >> 4;
            int maxChunkZ = Mth.floor(bounds.maxZ) >> 4;
            for (int x = minChunkX; x <= maxChunkX; x++) {
                for (int z = minChunkZ; z <= maxChunkZ; z++) {
                    if (container.inPlotBounds(x, z)) {
                        continue;
                    }
                    refreshForcedInhabitedChunk(ChunkPos.asLong(x, z), x, z, id, gameTime);
                }
            }
        } finally {
            AssemblyProfiler.record("holding.refreshForcedChunks", profileStarted);
        }
    }

    private void refreshForcedInhabitedChunk(long chunkLong, int chunkX, int chunkZ, AssemblyId id, long gameTime) {
        Map<AssemblyId, Long> assemblies = forcedInhabitedChunks.computeIfAbsent(chunkLong, ignored -> new LinkedHashMap<>());
        Long previous = assemblies.put(id, gameTime);
        if (previous != null) {
            return;
        }

        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        level.getChunkSource().addRegionTicket(
                ASSEMBLY_LOADED_TICKET_TYPE,
                chunkPos,
                ASSEMBLY_LOADED_TICKET_DISTANCE,
                id,
                true
        );
        level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, true);
    }

    private void expireForcedInhabitedChunkTickets(long gameTime) {
        var chunkIterator = forcedInhabitedChunks.entrySet().iterator();
        while (chunkIterator.hasNext()) {
            Map.Entry<Long, Map<AssemblyId, Long>> chunkEntry = chunkIterator.next();
            ChunkPos chunkPos = new ChunkPos(chunkEntry.getKey());
            var assemblyIterator = chunkEntry.getValue().entrySet().iterator();
            while (assemblyIterator.hasNext()) {
                Map.Entry<AssemblyId, Long> assemblyEntry = assemblyIterator.next();
                if (assemblyEntry.getValue() >= gameTime - 20L) {
                    continue;
                }
                level.getChunkSource().removeRegionTicket(
                        ASSEMBLY_LOADED_TICKET_TYPE,
                        chunkPos,
                        ASSEMBLY_LOADED_TICKET_DISTANCE,
                        assemblyEntry.getKey(),
                        true
                );
                assemblyIterator.remove();
            }
            if (chunkEntry.getValue().isEmpty()) {
                chunkIterator.remove();
            }
        }
    }

    private void unloadAssembliesIntersecting(List<ChunkPos> chunkPositions) {
        Objects.requireNonNull(chunkPositions, "chunkPositions");
        if (chunkPositions.isEmpty()) {
            return;
        }

        MechanicsWorld world = KineticAssembly.api().existingWorld(level).orElse(null);
        if (world == null) {
            return;
        }

        List<ActiveAssemblyState> states = activeAssemblyStates(world);
        Map<AssemblyId, ActiveAssemblyState> statesById = statesById(states);
        Map<AssemblyId, List<AssemblyId>> dependenciesById = dependenciesById(states);
        Set<AssemblyId> forceLoaded = dependencyClosure(container.collectForceLoadedAssemblyIds(), dependenciesById);
        Set<AssemblyId> moved = new LinkedHashSet<>();
        for (ChunkPos chunkPos : chunkPositions) {
            AABB chunkBounds = chunkBounds(chunkPos);
            for (ActiveAssemblyState state : states) {
                AssemblyId id = state.assembly().id();
                if (moved.contains(id) || forceLoaded.contains(id)) {
                    continue;
                }
                if (state.worldBounds().intersects(chunkBounds)) {
                    List<ActiveAssemblyState> chain = dependencyChain(id, statesById, dependenciesById);
                    if (moveToUnloaded(world, chain, chunkPos)) {
                        for (ActiveAssemblyState movedState : chain) {
                            moved.add(movedState.assembly().id());
                        }
                    }
                }
            }
        }
    }

    private AABB chunkBounds(ChunkPos chunkPos) {
        return new AABB(
                chunkPos.getMinBlockX(),
                level.getMinBuildHeight() - 1024.0D,
                chunkPos.getMinBlockZ(),
                chunkPos.getMaxBlockX() + 1.0D,
                level.getMaxBuildHeight() + 1024.0D,
                chunkPos.getMaxBlockZ() + 1.0D
        );
    }

    private boolean moveToUnloaded(MechanicsWorld world, List<ActiveAssemblyState> chain, ChunkPos holdingChunkPos) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(holdingChunkPos, "holdingChunkPos");
        long profileStarted = AssemblyProfiler.start();
        try {
            if (chain.isEmpty()) {
                return false;
            }

            List<AssemblyId> dependencies = chain.stream()
                    .map(ActiveAssemblyState::assembly)
                    .map(PhysicsAssembly::id)
                    .distinct()
                    .toList();
            List<HeldSnapshot> snapshots = new ArrayList<>(chain.size());
            for (ActiveAssemblyState state : chain) {
                PhysicsAssembly assembly = state.assembly();
                if (container.assembly(assembly.id()).isEmpty()) {
                    return false;
                }
                CompoundTag tag = AssemblyPersistence
                        .snapshotAssembly(level, container, assembly, state.body(), dependencies)
                        .orElse(null);
                if (tag == null) {
                    return false;
                }
                snapshots.add(new HeldSnapshot(state, tag));
            }

            for (HeldSnapshot snapshot : snapshots) {
                ActiveAssemblyState state = snapshot.state();
                PhysicsAssembly assembly = state.assembly();
                acceptHeldAssembly(holdingChunkPos, assembly, snapshot.tag());
                AssemblyDebugVisuals.discard(level, state.body(), assembly);
                assembly.setDebugVisualsEnabled(false);
                assembly.markRemoving();
                assembly.clearBlockEntities();
                world.removeBody(assembly.bodyId());
                container.remove(assembly.id(), AssemblyRemovalReason.UNLOADED);
            }
            KineticAssembly.LOGGER.debug(
                    "Moved assembly dependency chain {} to holding chunk {} in {}",
                    dependencies,
                    holdingChunkPos,
                    level.dimension().location()
            );
            return true;
        } finally {
            AssemblyProfiler.record("holding.moveToUnloaded", profileStarted);
        }
    }

    private void saveForceLoadedActiveAssemblies() {
        Set<AssemblyId> forceLoaded = container.collectForceLoadedAssemblyIds();
        if (forceLoaded.isEmpty()) {
            return;
        }

        MechanicsWorld world = KineticAssembly.api().existingWorld(level).orElse(null);
        if (world == null) {
            return;
        }

        List<ActiveAssemblyState> states = activeAssemblyStates(world);
        Map<AssemblyId, ActiveAssemblyState> statesById = statesById(states);
        Map<AssemblyId, List<AssemblyId>> dependenciesById = dependenciesById(states);
        Set<AssemblyId> saved = new LinkedHashSet<>();
        for (AssemblyId id : forceLoaded) {
            if (saved.contains(id)) {
                continue;
            }
            List<ActiveAssemblyState> chain = dependencyChain(id, statesById, dependenciesById);
            if (chain.isEmpty()) {
                continue;
            }
            List<AssemblyId> dependencies = chain.stream()
                    .map(ActiveAssemblyState::assembly)
                    .map(PhysicsAssembly::id)
                    .distinct()
                    .toList();
            for (ActiveAssemblyState state : chain) {
                saveActiveAssemblyPointer(state, dependencies);
                saved.add(state.assembly().id());
            }
        }
    }

    private void saveActiveAssemblyPointer(ActiveAssemblyState state, List<AssemblyId> dependencies) {
        PhysicsAssembly assembly = state.assembly();
        CompoundTag tag = AssemblyPersistence.snapshotAssembly(level, container, assembly, state.body(), dependencies)
                .orElse(null);
        if (tag == null) {
            return;
        }

        PhysicsVector position = state.body().pose().position();
        ChunkPos chunkPos = new ChunkPos(BlockPos.containing(position.x(), position.y(), position.z()));
        saveAssemblyPointer(chunkPos, assembly.id(), tag, assembly.lastSerializationPointer().orElse(null))
                .ifPresent(pointer -> {
                    assembly.setLastSerializationPointer(pointer);
                    container.updateSavedAssemblyPointer(assembly.id(), pointer);
                });
    }

    private void restoreReadyAssemblies() {
        List<HeldAssembly> held;
        synchronized (this) {
            if (heldAssemblies.isEmpty()) {
                return;
            }
            held = List.copyOf(heldAssemblies.values());
        }

        Map<AssemblyId, HeldAssembly> heldById = new LinkedHashMap<>();
        Map<AssemblyId, List<AssemblyId>> dependenciesById = new LinkedHashMap<>();
        for (HeldAssembly heldAssembly : held) {
            heldById.put(heldAssembly.id(), heldAssembly);
            dependenciesById.put(heldAssembly.id(), dependenciesFor(heldAssembly));
        }

        Set<AssemblyId> visited = new LinkedHashSet<>();
        for (HeldAssembly heldAssembly : held) {
            AssemblyId id = heldAssembly.id();
            Optional<PhysicsAssembly> active = container.assembly(id);
            if (active.isPresent()) {
                heldAssembly.pointer().ifPresent(active.get()::setLastSerializationPointer);
                releaseHeldAssembly(id, false);
                visited.add(id);
                continue;
            }
            if (visited.contains(id)) {
                continue;
            }
            RestoreGroup group = restoreGroup(id, heldById, dependenciesById);
            visited.addAll(group.ids());
            if (group.missingDependencies()) {
                continue;
            }

            boolean ready = true;
            for (HeldAssembly member : group.members()) {
                if (container.assembly(member.id()).isPresent()) {
                    continue;
                }
                if (!AssemblyPersistence.canRestoreAssemblyRecord(level, member.tag())) {
                    ready = false;
                    break;
                }
            }
            if (!ready) {
                continue;
            }

            for (HeldAssembly member : group.members()) {
                Optional<PhysicsAssembly> activeMember = container.assembly(member.id());
                if (activeMember.isPresent()) {
                    member.pointer().ifPresent(activeMember.get()::setLastSerializationPointer);
                    releaseHeldAssembly(member.id(), false);
                    continue;
                }
                if (!AssemblyPersistence.restoreAssemblyRecord(level, member.tag())) {
                    continue;
                }
                member.pointer().ifPresent(pointer -> container.assembly(member.id())
                        .ifPresent(assembly -> assembly.setLastSerializationPointer(pointer)));
                releaseHeldAssembly(member.id(), false);
                KineticAssembly.LOGGER.debug(
                        "Restored held assembly {} from holding chunk {} in {}",
                        member.id(),
                        member.chunkPos(),
                        level.dimension().location()
                );
            }
        }
    }

    private synchronized void acceptHeldAssembly(ChunkPos chunkPos, PhysicsAssembly assembly, CompoundTag tag) {
        removeHeldAssembly(assembly.id());
        GlobalSavedAssemblyPointer previousPointer = assembly.lastSerializationPointer().orElse(null);
        GlobalSavedAssemblyPointer pointer = saveAssemblyPointer(chunkPos, assembly.id(), tag, previousPointer)
                .orElse(null);
        assembly.setLastSerializationPointer(pointer);
        acceptHeldAssembly(chunkPos, assembly.id(), tag, pointer);
        setDirty(chunkPos);
    }

    private synchronized void acceptLoadedHeldAssembly(
            ChunkPos chunkPos,
            AssemblyId id,
            CompoundTag tag,
            GlobalSavedAssemblyPointer pointer
    ) {
        if (heldAssemblies.containsKey(id)) {
            return;
        }
        acceptHeldAssembly(chunkPos, id, tag, pointer);
    }

    private synchronized Optional<GlobalSavedAssemblyPointer> saveAssemblyPointer(
            ChunkPos chunkPos,
            AssemblyId id,
            CompoundTag tag,
            GlobalSavedAssemblyPointer previousPointer
    ) {
        Optional<AssemblyStorage> maybeStorage = storage();
        if (maybeStorage.isEmpty()) {
            return Optional.empty();
        }
        AssemblyStorage storage = maybeStorage.get();
        GlobalSavedAssemblyPointer pointer;
        if (previousPointer != null && previousPointer.chunkPos().equals(chunkPos)) {
            storage.attemptSaveAssembly(previousPointer, tag);
            pointer = previousPointer;
        } else {
            Optional<GlobalSavedAssemblyPointer> maybePointer = storage.attemptSaveAssembly(chunkPos, tag);
            if (maybePointer.isEmpty()) {
                return Optional.empty();
            }
            pointer = maybePointer.get();
            if (previousPointer != null) {
                removePointer(previousPointer, true);
            }
        }

        getOrLoadHoldingChunk(chunkPos, true).ifPresent(holdingChunk -> holdingChunk.acceptPointer(pointer.local()));
        setDirty(chunkPos);
        AssemblyTrackingPointSavedData.getOrLoad(level).updateSavedAssemblyPointer(id, pointer);
        return Optional.of(pointer);
    }

    private synchronized void removePointer(GlobalSavedAssemblyPointer pointer, boolean deleteData) {
        Objects.requireNonNull(pointer, "pointer");
        if (deleteData) {
            storage().ifPresent(storage -> storage.attemptSaveAssembly(pointer, null));
        }
        getOrLoadHoldingChunk(pointer.chunkPos(), false).ifPresent(holdingChunk -> {
            holdingChunk.removePointer(pointer.local());
            if (holdingChunk.isEmpty()) {
                loadedHoldingChunks.remove(pointer.chunkPos());
            }
            setDirty(pointer.chunkPos());
        });
    }

    private synchronized void queueDeletion(AssemblyId id, GlobalSavedAssemblyPointer pointer) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(pointer, "pointer");
        getOrLoadHoldingChunk(pointer.chunkPos(), false).ifPresent(holdingChunk -> {
            releaseHeldAssembly(id, false);
            holdingChunk.removePointer(pointer.local());
            if (holdingChunk.isEmpty()) {
                loadedHoldingChunks.remove(pointer.chunkPos());
            }
            setDirty(pointer.chunkPos());
        });
        queuedDeletions.add(pointer);
    }

    private void acceptHeldAssembly(
            ChunkPos chunkPos,
            AssemblyId id,
            CompoundTag tag,
            GlobalSavedAssemblyPointer pointer
    ) {
        HoldingChunk holdingChunk = loadedHoldingChunks.computeIfAbsent(chunkPos, HoldingChunk::new);
        HeldAssembly heldAssembly = new HeldAssembly(id, chunkPos, tag, Optional.ofNullable(pointer));
        holdingChunk.accept(heldAssembly);
        heldAssemblies.put(id, heldAssembly);
        if (pointer != null) {
            AssemblyTrackingPointSavedData.getOrLoad(level).updateSavedAssemblyPointer(id, pointer);
        }
    }

    private synchronized void removeHeldAssembly(AssemblyId id) {
        releaseHeldAssembly(id, true);
    }

    private synchronized void releaseHeldAssembly(AssemblyId id, boolean deleteData) {
        HeldAssembly removed = heldAssemblies.remove(id);
        if (removed == null) {
            return;
        }
        if (deleteData) {
            removed.pointer().ifPresent(pointer -> storage().ifPresent(storage -> storage.attemptSaveAssembly(pointer, null)));
        }
        HoldingChunk holdingChunk = loadedHoldingChunks.get(removed.chunkPos());
        if (holdingChunk != null) {
            if (deleteData) {
                holdingChunk.remove(removed);
            } else {
                holdingChunk.removeHeldOnly(removed);
            }
            if (holdingChunk.isEmpty()) {
                loadedHoldingChunks.remove(removed.chunkPos());
            }
        }
        setDirty(removed.chunkPos());
    }

    private void saveDirtyHoldingChunks() {
        List<ChunkPos> dirty;
        synchronized (this) {
            if (dirtyHoldingChunks.isEmpty()) {
                return;
            }
            dirty = dirtyHoldingChunks.stream()
                    .map(ChunkPos::new)
                    .toList();
            dirtyHoldingChunks.clear();
        }
        Optional<AssemblyStorage> maybeStorage = storage();
        if (maybeStorage.isEmpty()) {
            synchronized (this) {
                for (ChunkPos chunkPos : dirty) {
                    dirtyHoldingChunks.add(chunkPos.toLong());
                }
            }
            return;
        }
        AssemblyStorage storage = maybeStorage.get();
        for (ChunkPos chunkPos : dirty) {
            HoldingChunk holdingChunk;
            synchronized (this) {
                holdingChunk = loadedHoldingChunks.get(chunkPos);
            }
            AssemblyStorage.HoldingChunkData data = holdingChunk == null
                    ? new AssemblyStorage.HoldingChunkData(List.of())
                    : holdingChunk.data();
            storage.attemptSaveHoldingChunk(chunkPos, data);
        }
    }

    private void processQueuedDeletions() {
        List<GlobalSavedAssemblyPointer> deletions;
        synchronized (this) {
            deletions = List.copyOf(queuedDeletions);
            queuedDeletions.clear();
        }
        if (deletions.isEmpty()) {
            return;
        }

        Optional<AssemblyStorage> maybeStorage = storage();
        if (maybeStorage.isEmpty()) {
            synchronized (this) {
                queuedDeletions.addAll(deletions);
            }
            return;
        }

        AssemblyStorage storage = maybeStorage.get();
        for (GlobalSavedAssemblyPointer pointer : deletions) {
            storage.attemptSaveAssembly(pointer, null);
        }
    }

    private synchronized void setDirty(ChunkPos chunkPos) {
        dirtyHoldingChunks.add(chunkPos.toLong());
    }

    private synchronized Optional<AssemblyStorage> storage() {
        if (storage != null) {
            return Optional.of(storage);
        }
        if (level.getServer() == null) {
            return Optional.empty();
        }
        storage = AssemblyStorage.forLevel(level);
        return Optional.of(storage);
    }

    private Optional<ChunkPos> firstUnloadedChunk(AABB bounds) {
        long profileStarted = AssemblyProfiler.start();
        try {
            int minChunkX = Mth.floor(bounds.minX) >> 4;
            int maxChunkX = Mth.floor(bounds.maxX) >> 4;
            int minChunkZ = Mth.floor(bounds.minZ) >> 4;
            int maxChunkZ = Mth.floor(bounds.maxZ) >> 4;
            for (int x = minChunkX; x <= maxChunkX; x++) {
                for (int z = minChunkZ; z <= maxChunkZ; z++) {
                    if (container.inPlotBounds(x, z)) {
                        continue;
                    }
                    if (!AssemblyChunkLoading.isChunkLoadedEnough(level, x, z)) {
                        return Optional.of(new ChunkPos(x, z));
                    }
                }
            }
            return Optional.empty();
        } finally {
            AssemblyProfiler.record("holding.firstUnloadedChunk", profileStarted);
        }
    }

    private List<ActiveAssemblyState> activeAssemblyStates(MechanicsWorld world) {
        long profileStarted = AssemblyProfiler.start();
        try {
            List<ActiveAssemblyState> states = new ArrayList<>();
            for (PhysicsAssembly assembly : container.assemblies()) {
                if (assembly.state() == AssemblyLifecycleState.REMOVING) {
                    continue;
                }
                MechanicsBodySnapshot body = world.snapshot(assembly.bodyId()).orElse(null);
                if (body == null || body.closed()) {
                    continue;
                }
                AABB bounds = worldBounds(assembly, body).orElse(null);
                if (bounds == null) {
                    continue;
                }
                states.add(new ActiveAssemblyState(assembly, body, bounds, predictedLoadingBounds(bounds, body)));
            }
            return states;
        } finally {
            AssemblyProfiler.record("holding.activeStates", profileStarted);
        }
    }

    private static Map<AssemblyId, ActiveAssemblyState> statesById(List<ActiveAssemblyState> states) {
        Map<AssemblyId, ActiveAssemblyState> byId = new LinkedHashMap<>();
        for (ActiveAssemblyState state : states) {
            byId.put(state.assembly().id(), state);
        }
        return byId;
    }

    private static Map<AssemblyId, List<AssemblyId>> dependenciesById(List<ActiveAssemblyState> states) {
        long profileStarted = AssemblyProfiler.start();
        try {
            List<PhysicsAssembly> assemblies = states.stream()
                    .map(ActiveAssemblyState::assembly)
                    .toList();
            Map<AssemblyId, AABB> worldBoundsById = new LinkedHashMap<>();
            for (ActiveAssemblyState state : states) {
                worldBoundsById.put(state.assembly().id(), state.worldBounds());
            }
            return AssemblyLoadingDependencies.idsByAssembly(assemblies, worldBoundsById);
        } finally {
            AssemblyProfiler.record("holding.dependenciesById", profileStarted);
        }
    }

    private static List<ActiveAssemblyState> dependencyChain(
            AssemblyId root,
            Map<AssemblyId, ActiveAssemblyState> statesById,
            Map<AssemblyId, List<AssemblyId>> dependenciesById
    ) {
        List<ActiveAssemblyState> chain = new ArrayList<>();
        for (AssemblyId dependency : dependenciesById.getOrDefault(root, List.of(root))) {
            ActiveAssemblyState state = statesById.get(dependency);
            if (state != null) {
                chain.add(state);
            }
        }
        if (chain.isEmpty()) {
            ActiveAssemblyState rootState = statesById.get(root);
            if (rootState != null) {
                chain.add(rootState);
            }
        }
        return chain;
    }

    private static Set<AssemblyId> dependencyClosure(
            Set<AssemblyId> roots,
            Map<AssemblyId, List<AssemblyId>> dependenciesById
    ) {
        LinkedHashSet<AssemblyId> ids = new LinkedHashSet<>();
        ArrayDeque<AssemblyId> frontier = new ArrayDeque<>(roots);
        while (!frontier.isEmpty()) {
            AssemblyId current = frontier.removeFirst();
            if (!ids.add(current)) {
                continue;
            }
            for (AssemblyId dependency : dependenciesById.getOrDefault(current, List.of(current))) {
                if (!ids.contains(dependency)) {
                    frontier.addLast(dependency);
                }
            }
        }
        return ids;
    }

    private RestoreGroup restoreGroup(
            AssemblyId root,
            Map<AssemblyId, HeldAssembly> heldById,
            Map<AssemblyId, List<AssemblyId>> dependenciesById
    ) {
        LinkedHashSet<AssemblyId> ids = new LinkedHashSet<>();
        ArrayDeque<AssemblyId> frontier = new ArrayDeque<>();
        boolean missingDependencies = false;
        frontier.add(root);

        while (!frontier.isEmpty()) {
            AssemblyId current = frontier.removeFirst();
            if (!ids.add(current)) {
                continue;
            }

            HeldAssembly held = heldById.get(current);
            if (held == null) {
                if (container.assembly(current).isEmpty()) {
                    missingDependencies = true;
                }
                continue;
            }

            for (AssemblyId dependency : dependenciesById.getOrDefault(current, List.of(current))) {
                if (!ids.contains(dependency)) {
                    frontier.addLast(dependency);
                }
            }
            for (Map.Entry<AssemblyId, List<AssemblyId>> entry : dependenciesById.entrySet()) {
                if (!entry.getKey().equals(current) && entry.getValue().contains(current) && !ids.contains(entry.getKey())) {
                    frontier.addLast(entry.getKey());
                }
            }
        }

        List<HeldAssembly> members = new ArrayList<>();
        for (HeldAssembly heldAssembly : heldById.values()) {
            if (ids.contains(heldAssembly.id())) {
                members.add(heldAssembly);
            }
        }
        return new RestoreGroup(List.copyOf(members), List.copyOf(ids), missingDependencies);
    }

    private static List<AssemblyId> dependenciesFor(HeldAssembly heldAssembly) {
        List<AssemblyId> dependencies = AssemblyPersistence.recordDependencies(heldAssembly.tag());
        return dependencies.isEmpty() ? List.of(heldAssembly.id()) : dependencies;
    }

    private Optional<AABB> worldBounds(PhysicsAssembly assembly, MechanicsBodySnapshot body) {
        container.trackingSystem().poseFrame(assembly, body.pose());
        return container.runtimeState(assembly)
                .worldBounds()
                .map(bounds -> bounds.inflate(1.0D));
    }

    private static AABB predictedLoadingBounds(AABB worldBounds, MechanicsBodySnapshot body) {
        PhysicsVector velocity = body.linearVelocity();
        double predictedX = velocity.x() * PHYSICS_TICK_SECONDS;
        double predictedY = velocity.y() * PHYSICS_TICK_SECONDS;
        double predictedZ = velocity.z() * PHYSICS_TICK_SECONDS;
        double predictedDistanceSqr = predictedX * predictedX + predictedY * predictedY + predictedZ * predictedZ;
        if (predictedDistanceSqr <= PREDICTION_MOVEMENT_THRESHOLD * PREDICTION_MOVEMENT_THRESHOLD) {
            return worldBounds;
        }

        double clampedY = Mth.clamp(predictedY, -MAX_PREDICTION_DISTANCE, MAX_PREDICTION_DISTANCE);
        if (Math.abs(clampedY) <= 1.0E-7D) {
            return worldBounds;
        }
        return union(worldBounds, worldBounds.move(0.0D, clampedY, 0.0D));
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

    private static final class HoldingChunk {
        private final Map<AssemblyId, HeldAssembly> assemblies = new LinkedHashMap<>();
        private final Set<SavedAssemblyPointer> pointers = new LinkedHashSet<>();

        private HoldingChunk(ChunkPos pos) {
            Objects.requireNonNull(pos, "pos");
        }

        private void accept(HeldAssembly assembly) {
            assemblies.put(assembly.id(), assembly);
            assembly.pointer().map(GlobalSavedAssemblyPointer::local).ifPresent(pointers::add);
        }

        private void acceptPointer(SavedAssemblyPointer pointer) {
            pointers.add(Objects.requireNonNull(pointer, "pointer"));
        }

        private void remove(HeldAssembly assembly) {
            assemblies.remove(assembly.id());
            assembly.pointer().map(GlobalSavedAssemblyPointer::local).ifPresent(pointers::remove);
        }

        private void removeHeldOnly(HeldAssembly assembly) {
            assemblies.remove(assembly.id());
        }

        private void removePointer(SavedAssemblyPointer pointer) {
            pointers.remove(pointer);
        }

        private boolean isEmpty() {
            return assemblies.isEmpty() && pointers.isEmpty();
        }

        private AssemblyStorage.HoldingChunkData data() {
            return new AssemblyStorage.HoldingChunkData(List.copyOf(pointers));
        }
    }

    private record HeldAssembly(
            AssemblyId id,
            ChunkPos chunkPos,
            CompoundTag tag,
            Optional<GlobalSavedAssemblyPointer> pointer
    ) {
        private HeldAssembly {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(chunkPos, "chunkPos");
            Objects.requireNonNull(pointer, "pointer");
            tag = tag.copy();
        }
    }

    private record ActiveAssemblyState(
            PhysicsAssembly assembly,
            MechanicsBodySnapshot body,
            AABB worldBounds,
            AABB loadingBounds
    ) {
        private ActiveAssemblyState {
            Objects.requireNonNull(assembly, "assembly");
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(worldBounds, "worldBounds");
            Objects.requireNonNull(loadingBounds, "loadingBounds");
        }
    }

    private record HeldSnapshot(ActiveAssemblyState state, CompoundTag tag) {
        private HeldSnapshot {
            Objects.requireNonNull(state, "state");
            tag = tag.copy();
        }
    }

    private record RestoreGroup(List<HeldAssembly> members, List<AssemblyId> ids, boolean missingDependencies) {
        private RestoreGroup {
            members = List.copyOf(members);
            ids = List.copyOf(ids);
        }
    }
}
