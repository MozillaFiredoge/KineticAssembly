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
import java.util.UUID;

import com.mojang.serialization.Codec;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsQuaternion;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.mechanics.MechanicsBodyId;
import com.firedoge.kineticassembly.mechanics.MechanicsBodySnapshot;
import com.firedoge.kineticassembly.mechanics.MechanicsOwner;
import com.firedoge.kineticassembly.mechanics.MechanicsWorld;
import com.firedoge.kineticassembly.minecraft.scene.ServerPhysicsRuntime;
import com.firedoge.kineticassembly.platform.PlatformServices;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.ticks.SavedTick;

public final class AssemblyPersistence {
    private static final int DATA_VERSION = 1;
    private static final int CAPTURE_INTERVAL_TICKS = 100;
    private static final double RESTORE_Y_MARGIN = 512.0D;
    private static final double MAX_RESTORED_LINEAR_SPEED = 256.0D;
    private static final double MAX_RESTORED_ANGULAR_SPEED = 256.0D;
    private static final String DATA_NAME = KineticAssembly.MODID + "_assemblies";
    private static final Codec<Data> CODEC = CompoundTag.CODEC.xmap(
            tag -> Data.load(tag, null),
            data -> data.save(new CompoundTag(), null)
    );
    private static final SavedDataType<Data> TYPE = new SavedDataType<>(DATA_NAME, Data::new, CODEC);
    private static final ThreadLocal<Boolean> RESTORING = ThreadLocal.withInitial(() -> false);

    private static int captureTick;
    private static boolean serverStopping;

    private AssemblyPersistence() {
    }

    public static void startServer() {
        serverStopping = false;
        captureTick = 0;
    }

    public static void restore(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        for (ServerLevel level : server.getAllLevels()) {
            restore(level);
        }
    }

    public static void capturePeriodically(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (serverStopping) {
            return;
        }
        captureTick++;
        if (captureTick < CAPTURE_INTERVAL_TICKS) {
            return;
        }
        captureTick = 0;
        long sectionStarted = AssemblyProfiler.start();
        try {
            capture(server, false, false);
        } finally {
            AssemblyProfiler.record("persistence.capture.collect", sectionStarted);
        }
    }

    public static void captureBeforeLevelSave(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        if (serverStopping) {
            return;
        }
        if (capture(level, true)) {
            level.getDataStorage().saveAndJoin();
            PlatformServices.services().waitForIoCompletion();
        }
    }

    public static void flush(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        capture(server, true, true);
        serverStopping = true;
        for (ServerLevel level : server.getAllLevels()) {
            level.getDataStorage().saveAndJoin();
        }
        PlatformServices.services().waitForIoCompletion();
    }

    public static Optional<AssemblyPlotProjection> savedProjection(ServerLevel level, AssemblyId id) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        Data data = data(level);
        if (data.isEmpty()) {
            return Optional.empty();
        }

        for (CompoundTag assemblyTag : data.assemblies()) {
            if (!assemblyTag.read("id", net.minecraft.core.UUIDUtil.CODEC).isPresent() || !id.value().equals(assemblyTag.read("id", net.minecraft.core.UUIDUtil.CODEC).orElseThrow())) {
                continue;
            }
            try {
                StoredAssembly stored = readAssembly(level, assemblyTag);
                return Optional.of(new AssemblyPlotProjection(
                        stored.id(),
                        stored.plot(),
                        bodyToPlotOrigin(stored.blocks()),
                        AssemblyPoseFrame.initial(stored.id(), stored.pose())
                ));
            } catch (RuntimeException exception) {
                KineticAssembly.LOGGER.warn(
                        "Failed to read saved projection for assembly {} in {}",
                        id,
                        level.dimension().identifier(),
                        exception
                );
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public static Optional<AssemblyPlotProjection> projectionFromRecord(ServerLevel level, CompoundTag tag) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(tag, "tag");
        try {
            StoredAssembly stored = readAssembly(level, tag);
            return Optional.of(new AssemblyPlotProjection(
                    stored.id(),
                    stored.plot(),
                    bodyToPlotOrigin(stored.blocks()),
                    AssemblyPoseFrame.initial(stored.id(), stored.pose())
            ));
        } catch (RuntimeException exception) {
            KineticAssembly.LOGGER.warn(
                    "Failed to read held projection in {}",
                    level.dimension().identifier(),
                    exception
            );
            return Optional.empty();
        }
    }

    public static Optional<CompoundTag> snapshotAssembly(
            ServerLevel level,
            ServerAssemblyContainer container,
            PhysicsAssembly assembly
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(assembly, "assembly");
        MechanicsWorld world = KineticAssembly.api().existingWorld(level).orElse(null);
        if (world == null) {
            return Optional.empty();
        }
        return world.snapshot(assembly.bodyId())
                .flatMap(body -> snapshotAssembly(level, container, assembly, body));
    }

    public static Optional<CompoundTag> snapshotAssembly(
            ServerLevel level,
            ServerAssemblyContainer container,
            PhysicsAssembly assembly,
            MechanicsBodySnapshot body
    ) {
        return snapshotAssembly(level, container, assembly, body, List.of());
    }

    public static Optional<CompoundTag> snapshotAssembly(
            ServerLevel level,
            ServerAssemblyContainer container,
            PhysicsAssembly assembly,
            MechanicsBodySnapshot body,
            List<AssemblyId> dependencies
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(assembly, "assembly");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(dependencies, "dependencies");
        container.refreshBlockEntityTags(assembly);
        if (!isReasonablePose(level, body.pose())
                || !isReasonableLinearVelocity(body.linearVelocity())
                || !isReasonableAngularVelocity(body.angularVelocity())) {
            KineticAssembly.LOGGER.warn(
                    "Skipping snapshot for assembly {} because body pose/velocity is invalid: position={}, velocity={}, angularVelocity={}",
                    assembly.id(),
                    describe(body.pose().position()),
                    describe(body.linearVelocity()),
                    describe(body.angularVelocity())
            );
            return Optional.empty();
        }
        return Optional.of(writeAssembly(
                level,
                container,
                assembly,
                body,
                persistedWorldBounds(assembly, body.pose()),
                dependencies
        ));
    }

    public static Optional<AssemblyId> recordId(CompoundTag tag) {
        return idFromTag(tag);
    }

    public static List<AssemblyId> recordDependencies(CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");
        return readDependencies(tag);
    }

    public static boolean canRestoreAssemblyRecord(ServerLevel level, CompoundTag tag) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(tag, "tag");
        try {
            StoredAssembly stored = readAssembly(level, tag);
            ServerAssemblyContainer container = AssemblyContainers.requireServer(level);
            return container.assembly(stored.id()).isEmpty()
                    && isRestoreTerrainLoadedAround(level, stored);
        } catch (RuntimeException exception) {
            KineticAssembly.LOGGER.warn("Failed to read held assembly in {}", level.dimension().identifier(), exception);
            return false;
        }
    }

    public static boolean restoreAssemblyRecord(ServerLevel level, CompoundTag tag) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(tag, "tag");
        StoredAssembly stored;
        try {
            stored = readAssembly(level, tag);
        } catch (RuntimeException exception) {
            KineticAssembly.LOGGER.warn("Failed to read held assembly in {}", level.dimension().identifier(), exception);
            return false;
        }

        ServerAssemblyContainer container = AssemblyContainers.requireServer(level);
        if (container.assembly(stored.id()).isPresent()) {
            return true;
        }
        if (!isRestoreTerrainLoadedAround(level, stored)) {
            return false;
        }

        RESTORING.set(true);
        try {
            return restoreAssembly(level, container, stored);
        } catch (RuntimeException exception) {
            KineticAssembly.LOGGER.warn("Failed to restore held assembly {} in {}", stored.id(), level.dimension().identifier(), exception);
            return false;
        } finally {
            RESTORING.set(false);
        }
    }

    private static void restore(ServerLevel level) {
        Data data = data(level);
        if (data.restored()) {
            return;
        }
        if (!data.restoreLoadLogged()) {
            KineticAssembly.LOGGER.info(
                    "Loaded {} persisted assembly records for {}",
                    data.size(),
                    level.dimension().identifier()
            );
            data.markRestoreLoadLogged();
        }
        if (data.isEmpty()) {
            data.markRestored(false);
            return;
        }

        ServerAssemblyContainer container = AssemblyContainers.requireServer(level);
        Map<AssemblyId, RestoreCandidate> candidatesById = new LinkedHashMap<>();
        List<CompoundTag> remainingAssemblies = new ArrayList<>();
        int failed = 0;
        int alreadyActive = 0;
        for (CompoundTag assemblyTag : data.assemblies()) {
            try {
                StoredAssembly stored = readAssembly(level, assemblyTag);
                if (container.assembly(stored.id()).isPresent()) {
                    alreadyActive++;
                    continue;
                }
                if (candidatesById.containsKey(stored.id())) {
                    failed++;
                    remainingAssemblies.add(assemblyTag.copy());
                    KineticAssembly.LOGGER.warn(
                            "Ignoring duplicate persisted assembly record {} in {}",
                            stored.id(),
                            level.dimension().identifier()
                    );
                    continue;
                }
                candidatesById.put(stored.id(), new RestoreCandidate(stored, assemblyTag));
            } catch (RuntimeException exception) {
                failed++;
                remainingAssemblies.add(assemblyTag.copy());
                KineticAssembly.LOGGER.warn("Failed to read persisted assembly in {}", level.dimension().identifier(), exception);
            }
        }
        RestorePlan plan = collectReadyRestoreCandidates(level, container, candidatesById);
        List<RestoreCandidate> readyAssemblies = plan.ready();
        for (RestoreCandidate deferredCandidate : plan.deferred()) {
            remainingAssemblies.add(deferredCandidate.tag().copy());
        }
        int deferred = plan.deferred().size();
        if (readyAssemblies.isEmpty() && failed == 0 && alreadyActive == 0) {
            if (deferred > 0) {
                KineticAssembly.LOGGER.debug(
                        "Deferred restore of {} persisted assemblies for {} until nearby terrain chunks are loaded",
                        deferred,
                        level.dimension().identifier()
                );
            }
            return;
        }
        if (deferred > 0) {
            KineticAssembly.LOGGER.debug(
                    "Keeping {} persisted assemblies for {} in deferred restore storage until nearby terrain chunks are loaded",
                    deferred,
                    level.dimension().identifier()
            );
        }

        int restored = 0;
        RESTORING.set(true);
        try {
            for (RestoreCandidate candidate : readyAssemblies) {
                try {
                    if (restoreAssembly(level, container, candidate.stored())) {
                        restored++;
                    } else {
                        failed++;
                        remainingAssemblies.add(candidate.tag().copy());
                    }
                } catch (RuntimeException exception) {
                    failed++;
                    remainingAssemblies.add(candidate.tag().copy());
                    KineticAssembly.LOGGER.warn("Failed to restore persisted assembly in {}", level.dimension().identifier(), exception);
                }
            }
        } finally {
            RESTORING.set(false);
        }

        if (restored > 0 || failed > 0 || alreadyActive > 0) {
            data.replaceAssemblies(remainingAssemblies);
        }
        if (remainingAssemblies.isEmpty() || (failed > 0 && deferred == 0)) {
            data.markRestored(failed > 0);
        }
        if (restored > 0 || failed > 0) {
            KineticAssembly.LOGGER.info(
                    "Restored {} persisted assemblies for {}{}{}",
                    restored,
                    level.dimension().identifier(),
                    failed == 0 ? "" : " (" + failed + " failed)",
                    deferred == 0 ? "" : " (" + deferred + " deferred)"
            );
        }
    }

    private static RestorePlan collectReadyRestoreCandidates(
            ServerLevel level,
            ServerAssemblyContainer container,
            Map<AssemblyId, RestoreCandidate> candidatesById
    ) {
        List<RestoreCandidate> ready = new ArrayList<>();
        List<RestoreCandidate> deferred = new ArrayList<>();
        Set<AssemblyId> visited = new LinkedHashSet<>();

        for (RestoreCandidate candidate : candidatesById.values()) {
            AssemblyId id = candidate.stored().id();
            if (visited.contains(id)) {
                continue;
            }

            RestoreDependencyGroup group = restoreDependencyGroup(candidate, container, candidatesById);
            visited.addAll(group.ids());
            boolean canRestore = !group.missingDependencies();
            if (canRestore) {
                for (RestoreCandidate member : group.members()) {
                    if (container.assembly(member.stored().id()).isPresent()) {
                        continue;
                    }
                    if (!isRestoreTerrainLoadedAround(level, member.stored())) {
                        canRestore = false;
                        break;
                    }
                }
            }

            if (canRestore) {
                ready.addAll(group.members());
            } else {
                deferred.addAll(group.members());
            }
        }

        return new RestorePlan(ready, deferred);
    }

    private static RestoreDependencyGroup restoreDependencyGroup(
            RestoreCandidate root,
            ServerAssemblyContainer container,
            Map<AssemblyId, RestoreCandidate> candidatesById
    ) {
        LinkedHashSet<AssemblyId> ids = new LinkedHashSet<>();
        ArrayDeque<AssemblyId> frontier = new ArrayDeque<>();
        boolean missingDependencies = false;
        frontier.add(root.stored().id());

        while (!frontier.isEmpty()) {
            AssemblyId current = frontier.removeFirst();
            if (!ids.add(current)) {
                continue;
            }

            RestoreCandidate candidate = candidatesById.get(current);
            if (candidate == null) {
                if (container.assembly(current).isEmpty()) {
                    missingDependencies = true;
                }
                continue;
            }

            for (AssemblyId dependency : dependencyIds(candidate.stored())) {
                if (!ids.contains(dependency)) {
                    frontier.addLast(dependency);
                }
            }
            for (RestoreCandidate other : candidatesById.values()) {
                AssemblyId otherId = other.stored().id();
                if (!otherId.equals(current) && dependencyIds(other.stored()).contains(current) && !ids.contains(otherId)) {
                    frontier.addLast(otherId);
                }
            }
        }

        List<RestoreCandidate> members = new ArrayList<>();
        for (RestoreCandidate candidate : candidatesById.values()) {
            if (ids.contains(candidate.stored().id())) {
                members.add(candidate);
            }
        }
        return new RestoreDependencyGroup(List.copyOf(members), List.copyOf(ids), missingDependencies);
    }

    private static List<AssemblyId> dependencyIds(StoredAssembly stored) {
        return stored.dependencies().isEmpty() ? List.of(stored.id()) : stored.dependencies();
    }

    private static boolean restoreAssembly(ServerLevel level, ServerAssemblyContainer container, StoredAssembly stored) {
        buildRestoreTerrainCollision(level, stored);
        MechanicsWorld world = KineticAssembly.api().world(level);
        MechanicsBodySnapshot body = world.createDynamicCompoundBox(stored.owner(), stored.bodyId(), AssemblyAssembler.compoundDefinition(
                stored.pose(),
                stored.blocks(),
                stored.mass()
        ));
        world.setLinearVelocity(body.id(), stored.linearVelocity());
        world.setAngularVelocity(body.id(), stored.angularVelocity());

        PhysicsAssembly assembly = new PhysicsAssembly(
                stored.id(),
                level.dimension(),
                stored.owner(),
                stored.plot(),
                body.id(),
                stored.bounds(),
                stored.blocks()
        );
        try {
            container.add(assembly, false);
            restoreScheduledTicks(level, stored.plot(), stored.scheduledTicks());
            container.requestPlotBlockUpdatePrime(assembly);
            assembly.activate();
            return true;
        } catch (RuntimeException exception) {
            container.remove(assembly.id());
            world.removeBody(body.id());
            throw exception;
        }
    }

    private static boolean isRestoreTerrainLoadedAround(ServerLevel level, StoredAssembly stored) {
        ChunkBounds chunkBounds = restoreChunkBounds(stored);
        for (int x = chunkBounds.minX(); x <= chunkBounds.maxX(); x++) {
            for (int z = chunkBounds.minZ(); z <= chunkBounds.maxZ(); z++) {
                if (!AssemblyChunkLoading.isChunkLoadedEnough(level, x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void buildRestoreTerrainCollision(ServerLevel level, StoredAssembly stored) {
        ChunkBounds chunkBounds = restoreChunkBounds(stored);
        PhysicsVector position = stored.pose().position();
        int y = Mth.floor(position.y());
        for (int x = chunkBounds.minX(); x <= chunkBounds.maxX(); x++) {
            for (int z = chunkBounds.minZ(); z <= chunkBounds.maxZ(); z++) {
                ServerPhysicsRuntime.INSTANCE.buildTerrainCollisionAround(
                        level,
                        new BlockPos(x << 4, y, z << 4),
                        0
                );
            }
        }
    }

    private static ChunkBounds restoreChunkBounds(StoredAssembly stored) {
        AABB bounds = stored.worldBounds().inflate(1.0D);
        return new ChunkBounds(
                Mth.floor(bounds.minX) >> 4,
                Mth.floor(bounds.maxX) >> 4,
                Mth.floor(bounds.minZ) >> 4,
                Mth.floor(bounds.maxZ) >> 4
        );
    }

    private static AABB persistedWorldBounds(PhysicsAssembly assembly, PhysicsPose pose) {
        return assembly.worldBounds().orElseGet(() -> worldBounds(pose, assembly.blocks()));
    }

    private static AABB worldBounds(PhysicsPose pose, List<AssemblyBlock> blocks) {
        AssemblyTransform transform = AssemblyTransform.from(pose);
        AABB bounds = null;
        for (AssemblyBlock block : blocks) {
            AABB worldBounds = transform.localAabbToWorldBounds(block.bodyLocalBounds());
            bounds = bounds == null ? worldBounds : union(bounds, worldBounds);
        }
        if (bounds != null) {
            return bounds;
        }
        PhysicsVector position = pose.position();
        return new AABB(
                position.x(),
                position.y(),
                position.z(),
                position.x(),
                position.y(),
                position.z()
        );
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

    private static boolean capture(MinecraftServer server, boolean force, boolean flushHoldingStorage) {
        if (RESTORING.get()) {
            return false;
        }
        boolean changed = false;
        for (ServerLevel level : server.getAllLevels()) {
            if (force || shouldCapture(level)) {
                changed |= capture(level, flushHoldingStorage);
            }
        }
        return changed;
    }

    private static boolean shouldCapture(ServerLevel level) {
        ServerAssemblyContainer container = AssemblyContainers.server(level).orElse(null);
        if (container != null && (!container.isEmpty() || !container.holdingChunkMap().isEmpty())) {
            return true;
        }

        Data existing = existingData(level);
        return existing != null && existing.restored() && !existing.restoreHadFailures() && !existing.isEmpty();
    }

    private static boolean capture(ServerLevel level, boolean flushHoldingStorage) {
        ServerAssemblyContainer container = AssemblyContainers.server(level).orElse(null);
        Data existing = existingData(level);
        if (container != null) {
            long sectionStarted = AssemblyProfiler.start();
            try {
                container.holdingChunkMap().saveAll(flushHoldingStorage);
            } finally {
                AssemblyProfiler.record("persistence.capture.holdingSaveAll", sectionStarted);
            }
        }
        List<CompoundTag> heldAssemblies = container == null
                ? List.of()
                : container.holdingChunkMap().heldAssemblies();
        if ((container == null || (container.isEmpty() && heldAssemblies.isEmpty()))
                && (existing == null || existing.isEmpty())) {
            return false;
        }
        if (existing != null && existing.restoreHadFailures()) {
            return false;
        }

        List<PhysicsAssembly> assemblies = container == null ? List.of() : container.assemblies();
        if (assemblies.isEmpty() && heldAssemblies.isEmpty()) {
            if (existing != null && !existing.restored()) {
                return false;
            }
            return replaceSavedAssemblies(level, List.of());
        }

        MechanicsWorld world = null;
        List<PhysicsAssembly> persistableAssemblies = List.of();
        Map<AssemblyId, MechanicsBodySnapshot> bodySnapshotsById = new LinkedHashMap<>();
        Map<AssemblyId, AABB> worldBoundsById = new LinkedHashMap<>();
        Map<AssemblyId, AABB> persistedWorldBoundsById = new LinkedHashMap<>();
        if (!assemblies.isEmpty()) {
            world = KineticAssembly.api().existingWorld(level).orElse(null);
            if (world == null) {
                return false;
            }
            List<PhysicsAssembly> persistable = new ArrayList<>();
            long sectionStarted = AssemblyProfiler.start();
            try {
                for (PhysicsAssembly assembly : assemblies) {
                    if (assembly.state() == AssemblyLifecycleState.REMOVING) {
                        continue;
                    }
                    container.refreshBlockEntityTags(assembly);
                    Optional<MechanicsBodySnapshot> maybeBody = world.snapshot(assembly.bodyId());
                    if (maybeBody.isEmpty()) {
                        return false;
                    }
                    MechanicsBodySnapshot body = maybeBody.get();
                    if (!isReasonablePose(level, body.pose())
                            || !isReasonableLinearVelocity(body.linearVelocity())
                            || !isReasonableAngularVelocity(body.angularVelocity())) {
                        KineticAssembly.LOGGER.warn(
                                "Skipping persistence update for assembly {} because body pose/velocity is invalid: position={}, velocity={}, angularVelocity={}",
                                assembly.id(),
                                describe(body.pose().position()),
                                describe(body.linearVelocity()),
                                describe(body.angularVelocity())
                        );
                        return false;
                    }
                    persistable.add(assembly);
                    bodySnapshotsById.put(assembly.id(), body);
                    AABB persistedWorldBounds = persistedWorldBounds(assembly, body.pose());
                    worldBoundsById.put(assembly.id(), persistedWorldBounds);
                    persistedWorldBoundsById.put(assembly.id(), persistedWorldBounds);
                }
            } finally {
                AssemblyProfiler.record("persistence.capture.activeSnapshots", sectionStarted);
            }
            persistableAssemblies = List.copyOf(persistable);
        }
        long sectionStarted = AssemblyProfiler.start();
        Map<AssemblyId, List<AssemblyId>> dependenciesById;
        try {
            dependenciesById = AssemblyLoadingDependencies.idsByAssembly(
                    persistableAssemblies,
                    worldBoundsById
            );
        } finally {
            AssemblyProfiler.record("persistence.capture.dependencies", sectionStarted);
        }

        List<CompoundTag> saved = new ArrayList<>();
        sectionStarted = AssemblyProfiler.start();
        try {
            Set<AssemblyId> activeIds = new LinkedHashSet<>();
            for (PhysicsAssembly assembly : assemblies) {
                activeIds.add(assembly.id());
            }
            for (CompoundTag heldAssembly : heldAssemblies) {
                idFromTag(heldAssembly).ifPresent(activeIds::add);
            }
            if (existing != null && !existing.restored()) {
                for (CompoundTag assemblyTag : existing.assemblies()) {
                    Optional<AssemblyId> id = idFromTag(assemblyTag);
                    if (id.isPresent() && activeIds.contains(id.get())) {
                        continue;
                    }
                    saved.add(assemblyTag.copy());
                }
            }
            for (CompoundTag heldAssembly : heldAssemblies) {
                saved.add(heldAssembly.copy());
            }
            for (PhysicsAssembly assembly : persistableAssemblies) {
                MechanicsBodySnapshot body = bodySnapshotsById.get(assembly.id());
                saved.add(writeAssembly(
                        level,
                        container,
                        assembly,
                        body,
                        persistedWorldBoundsById.get(assembly.id()),
                        dependenciesById.getOrDefault(assembly.id(), List.of(assembly.id()))
                ));
            }
        } finally {
            AssemblyProfiler.record("persistence.capture.writeTags", sectionStarted);
        }
        return replaceSavedAssemblies(level, saved);
    }

    private static boolean replaceSavedAssemblies(ServerLevel level, List<CompoundTag> saved) {
        long sectionStarted = AssemblyProfiler.start();
        try {
            return data(level).replaceAssemblies(saved);
        } finally {
            AssemblyProfiler.record("persistence.capture.replaceData", sectionStarted);
        }
    }

    private static Optional<AssemblyId> idFromTag(CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");
        return tag.read("id", net.minecraft.core.UUIDUtil.CODEC).isPresent()
                ? Optional.of(new AssemblyId(tag.read("id", net.minecraft.core.UUIDUtil.CODEC).orElseThrow()))
                : Optional.empty();
    }

    private static CompoundTag writeAssembly(
            ServerLevel level,
            ServerAssemblyContainer container,
            PhysicsAssembly assembly,
            MechanicsBodySnapshot body,
            AABB worldBounds,
            List<AssemblyId> dependencies
    ) {
        Objects.requireNonNull(worldBounds, "worldBounds");
        CompoundTag tag = new CompoundTag();
        tag.store("id", net.minecraft.core.UUIDUtil.CODEC, assembly.id().value());
        tag.store("body_id", net.minecraft.core.UUIDUtil.CODEC, body.id().value());
        tag.putString("owner", assembly.owner().id().toString());
        tag.put("bounds", writeBounds(assembly.bounds()));
        tag.put("plot", writePlot(assembly.plot()));
        tag.put("pose", writePose(body.pose()));
        tag.put("world_bounds", writeAabb(worldBounds));
        tag.put("linear_velocity", writeVector(body.linearVelocity()));
        tag.put("angular_velocity", writeVector(body.angularVelocity()));
        tag.putFloat("mass", body.mass());
        tag.put("scheduled_ticks", writeScheduledTicks(level, container, assembly));
        writeDependencies(tag, dependencies);

        ListTag blocks = new ListTag();
        for (AssemblyBlock block : assembly.blocks()) {
            blocks.add(writeBlock(block));
        }
        tag.put("blocks", blocks);
        return tag;
    }

    private static CompoundTag writeBlock(AssemblyBlock block) {
        CompoundTag tag = new CompoundTag();
        tag.store("source_pos", BlockPos.CODEC, block.sourcePos());
        tag.store("local_pos", BlockPos.CODEC, block.localPos());
        tag.put("state", NbtUtils.writeBlockState(block.blockState()));
        tag.put("local_collision_bounds", writeAabb(block.localCollisionBounds()));
        tag.put("visual_local_origin", writeVector(block.visualLocalOrigin()));

        ListTag collisionBoxes = new ListTag();
        for (AABB box : block.localCollisionBoxes()) {
            collisionBoxes.add(writeAabb(box));
        }
        tag.put("local_collision_boxes", collisionBoxes);

        CompoundTag blockEntityTag = block.blockEntityTag();
        if (blockEntityTag != null) {
            tag.put("block_entity", blockEntityTag.copy());
        }
        return tag;
    }

    private static void writeDependencies(CompoundTag tag, List<AssemblyId> dependencies) {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(dependencies, "dependencies");
        if (dependencies.isEmpty()) {
            return;
        }

        ListTag dependencyTags = new ListTag();
        for (AssemblyId dependency : dependencies) {
            UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE, dependency.value())
                    .resultOrPartial(error -> KineticAssembly.LOGGER.warn(
                            "Failed to serialize assembly dependency {}: {}",
                            dependency,
                            error
                    ))
                    .ifPresent(dependencyTags::add);
        }
        tag.put("loading_dependencies", dependencyTags);
    }

    private static StoredAssembly readAssembly(ServerLevel level, CompoundTag tag) {
        AssemblyId id = new AssemblyId(tag.read("id", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
        MechanicsBodyId bodyId = new MechanicsBodyId(tag.read("body_id", net.minecraft.core.UUIDUtil.CODEC).isPresent() ? tag.read("body_id", net.minecraft.core.UUIDUtil.CODEC).orElseThrow() : UUID.randomUUID());
        MechanicsOwner owner = readOwner(tag);
        AssemblyBounds bounds = readBounds(tag.getCompoundOrEmpty("bounds"));
        AssemblyPlot plot = readPlot(tag.getCompoundOrEmpty("plot"));
        PhysicsPose pose = readPose(tag.getCompoundOrEmpty("pose"));
        PhysicsVector linearVelocity = readVector(tag.getCompoundOrEmpty("linear_velocity"));
        PhysicsVector angularVelocity = tag.contains("angular_velocity")
                ? readVector(tag.getCompoundOrEmpty("angular_velocity"))
                : PhysicsVector.ZERO;
        float mass = tag.getFloatOr("mass", 0.0F);

        HolderLookup.RegistryLookup<Block> blockLookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        ListTag blockTags = tag.getListOrEmpty("blocks");
        List<AssemblyBlock> blocks = new ArrayList<>(blockTags.size());
        for (int i = 0; i < blockTags.size(); i++) {
            blocks.add(readBlock(blockTags.getCompoundOrEmpty(i), blockLookup));
        }
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("Persisted assembly " + id + " has no blocks");
        }
        if (mass <= 0.0F) {
            throw new IllegalArgumentException("Persisted assembly " + id + " has invalid mass " + mass);
        }
        pose = sanitizePose(level, id, pose, blocks);
        linearVelocity = sanitizeLinearVelocity(id, linearVelocity);
        angularVelocity = sanitizeAngularVelocity(id, angularVelocity);
        AABB worldBounds = readWorldBounds(tag, pose, blocks);
        List<AssemblyId> dependencies = readDependencies(tag);
        StoredScheduledTicks scheduledTicks = readScheduledTicks(tag.getCompoundOrEmpty("scheduled_ticks"));
        return new StoredAssembly(
                id,
                bodyId,
                owner,
                bounds,
                plot,
                pose,
                worldBounds,
                linearVelocity,
                angularVelocity,
                mass,
                List.copyOf(blocks),
                dependencies,
                scheduledTicks
        );
    }

    private static MechanicsOwner readOwner(CompoundTag tag) {
        if (!tag.contains("owner")) {
            return MechanicsOwner.KINETIC_ASSEMBLY;
        }
        Identifier id = Identifier.tryParse(tag.getStringOr("owner", ""));
        return id == null ? MechanicsOwner.KINETIC_ASSEMBLY : new MechanicsOwner(id);
    }

    private static AssemblyBlock readBlock(CompoundTag tag, HolderLookup.RegistryLookup<Block> blockLookup) {
        BlockPos sourcePos = readBlockPos(tag, "source_pos");
        BlockPos localPos = readBlockPos(tag, "local_pos");
        BlockState blockState = NbtUtils.readBlockState(blockLookup, tag.getCompoundOrEmpty("state"));
        AABB localCollisionBounds = readAabb(tag.getCompoundOrEmpty("local_collision_bounds"));
        PhysicsVector visualLocalOrigin = readVector(tag.getCompoundOrEmpty("visual_local_origin"));
        ListTag collisionBoxTags = tag.getListOrEmpty("local_collision_boxes");
        List<AABB> collisionBoxes = new ArrayList<>(collisionBoxTags.size());
        for (int i = 0; i < collisionBoxTags.size(); i++) {
            collisionBoxes.add(readAabb(collisionBoxTags.getCompoundOrEmpty(i)));
        }
        CompoundTag blockEntityTag = tag.contains("block_entity")
                ? tag.getCompoundOrEmpty("block_entity").copy()
                : null;
        return new AssemblyBlock(
                sourcePos,
                localPos,
                blockState,
                localCollisionBounds,
                List.copyOf(collisionBoxes),
                visualLocalOrigin,
                blockEntityTag
        );
    }

    private static AABB readWorldBounds(CompoundTag tag, PhysicsPose pose, List<AssemblyBlock> blocks) {
        if (tag.contains("world_bounds")) {
            AABB saved = readAabb(tag.getCompoundOrEmpty("world_bounds"));
            if (finite(saved.minX)
                    && finite(saved.minY)
                    && finite(saved.minZ)
                    && finite(saved.maxX)
                    && finite(saved.maxY)
                    && finite(saved.maxZ)
                    && saved.minX <= saved.maxX
                    && saved.minY <= saved.maxY
                    && saved.minZ <= saved.maxZ) {
                return saved;
            }
        }
        return worldBounds(pose, blocks);
    }

    private static List<AssemblyId> readDependencies(CompoundTag tag) {
        if (!tag.contains("loading_dependencies")) {
            return List.of();
        }

        ListTag dependencyTags = tag.getListOrEmpty("loading_dependencies");
        List<AssemblyId> dependencies = new ArrayList<>(dependencyTags.size());
        for (int index = 0; index < dependencyTags.size(); index++) {
            final int dependencyIndex = index;
            try {
                UUIDUtil.CODEC.parse(NbtOps.INSTANCE, dependencyTags.get(index))
                        .resultOrPartial(error -> KineticAssembly.LOGGER.warn(
                                "Ignoring invalid assembly dependency tag at index {}: {}",
                                dependencyIndex,
                                error
                        ))
                        .map(AssemblyId::new)
                        .ifPresent(dependencies::add);
            } catch (RuntimeException exception) {
                KineticAssembly.LOGGER.warn("Ignoring invalid assembly dependency tag at index {}", dependencyIndex, exception);
            }
        }
        return List.copyOf(dependencies);
    }

    private static CompoundTag writeBounds(AssemblyBounds bounds) {
        CompoundTag tag = new CompoundTag();
        tag.store("source_origin", BlockPos.CODEC, bounds.sourceOrigin());
        tag.store("min_source_pos", BlockPos.CODEC, bounds.minSourcePos());
        tag.store("max_source_pos", BlockPos.CODEC, bounds.maxSourcePos());
        return tag;
    }

    private static AssemblyBounds readBounds(CompoundTag tag) {
        return new AssemblyBounds(
                readBlockPos(tag, "source_origin"),
                readBlockPos(tag, "min_source_pos"),
                readBlockPos(tag, "max_source_pos")
        );
    }

    private static CompoundTag writePlot(AssemblyPlot plot) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("id", plot.id().value());
        tag.putInt("origin_chunk_x", plot.originChunk().x);
        tag.putInt("origin_chunk_z", plot.originChunk().z);
        tag.putInt("section_y", plot.sectionY());
        tag.putInt("chunk_span", plot.chunkSpan());
        tag.putInt("section_span", plot.sectionSpan());
        return tag;
    }

    private static AssemblyPlot readPlot(CompoundTag tag) {
        return AssemblyPlot.sections(
                new AssemblyPlotId(tag.getLongOr("id", 0L)),
                new ChunkPos(tag.getIntOr("origin_chunk_x", 0), tag.getIntOr("origin_chunk_z", 0)),
                tag.getIntOr("section_y", 0),
                tag.getIntOr("chunk_span", 0),
                tag.getIntOr("section_span", 0)
        );
    }

    private static CompoundTag writeScheduledTicks(
            ServerLevel level,
            ServerAssemblyContainer container,
            PhysicsAssembly assembly
    ) {
        long gameTime = level.getLevelData().getGameTime();
        ListTag chunks = new ListTag();
        for (ChunkPos chunkPos : assembly.plot().chunkPositions()) {
            container.plotChunk(chunkPos).ifPresent(chunk -> {
                var packedTicks = chunk.getTicksForSerialization(gameTime);
                CompoundTag chunkTag = new CompoundTag();
                chunkTag.putInt("x", chunkPos.x);
                chunkTag.putInt("z", chunkPos.z);
                chunkTag.put(
                        "block_ticks",
                        encodeSavedTicks(packedTicks.blocks(), SavedTick.codec(BuiltInRegistries.BLOCK.byNameCodec()), "block")
                );
                chunkTag.put(
                        "fluid_ticks",
                        encodeSavedTicks(packedTicks.fluids(), SavedTick.codec(BuiltInRegistries.FLUID.byNameCodec()), "fluid")
                );
                chunks.add(chunkTag);
            });
        }

        CompoundTag tag = new CompoundTag();
        tag.put("chunks", chunks);
        return tag;
    }

    private static StoredScheduledTicks readScheduledTicks(CompoundTag tag) {
        ListTag chunkTags = tag.getListOrEmpty("chunks");
        List<StoredChunkTicks> chunks = new ArrayList<>(chunkTags.size());
        for (int i = 0; i < chunkTags.size(); i++) {
            CompoundTag chunkTag = chunkTags.getCompoundOrEmpty(i);
            chunks.add(new StoredChunkTicks(
                    new ChunkPos(chunkTag.getIntOr("x", 0), chunkTag.getIntOr("z", 0)),
                    chunkTag.getListOrEmpty("block_ticks").copy(),
                    chunkTag.getListOrEmpty("fluid_ticks").copy()
            ));
        }
        return new StoredScheduledTicks(chunks);
    }

    private static void restoreScheduledTicks(ServerLevel level, AssemblyPlot plot, StoredScheduledTicks scheduledTicks) {
        long gameTime = level.getLevelData().getGameTime();
        long[] subTickOrder = new long[] {0L};
        for (StoredChunkTicks chunk : scheduledTicks.chunks()) {
            if (!plot.containsChunk(chunk.chunkPos())) {
                continue;
            }
            decodeSavedTicks(
                    chunk.blockTicks(),
                    SavedTick.codec(BuiltInRegistries.BLOCK.byNameCodec()),
                    "block",
                    savedTick -> {
                        if (plot.containsPlotBlockPos(savedTick.pos())) {
                            level.getBlockTicks().schedule(savedTick.unpack(gameTime, subTickOrder[0]++));
                        }
                    }
            );
            decodeSavedTicks(
                    chunk.fluidTicks(),
                    SavedTick.codec(BuiltInRegistries.FLUID.byNameCodec()),
                    "fluid",
                    savedTick -> {
                        if (plot.containsPlotBlockPos(savedTick.pos())) {
                            level.getFluidTicks().schedule(savedTick.unpack(gameTime, subTickOrder[0]++));
                        }
                    }
            );
        }
    }

    private static <T> ListTag encodeSavedTicks(List<SavedTick<T>> ticks, Codec<SavedTick<T>> codec, String typeName) {
        ListTag tags = new ListTag();
        for (SavedTick<T> tick : ticks) {
            codec.encodeStart(NbtOps.INSTANCE, tick)
                    .resultOrPartial(error -> KineticAssembly.LOGGER.warn(
                            "Failed to serialize {} scheduled tick at {}: {}",
                            typeName,
                            tick.pos(),
                            error
                    ))
                    .ifPresent(tags::add);
        }
        return tags;
    }

    private static <T> void decodeSavedTicks(
            ListTag tags,
            Codec<SavedTick<T>> codec,
            String typeName,
            java.util.function.Consumer<SavedTick<T>> consumer
    ) {
        for (Tag tag : tags) {
            codec.parse(NbtOps.INSTANCE, tag)
                    .resultOrPartial(error -> KineticAssembly.LOGGER.warn(
                            "Ignoring invalid {} scheduled tick: {}",
                            typeName,
                            error
                    ))
                    .ifPresent(consumer);
        }
    }

    private static CompoundTag writePose(PhysicsPose pose) {
        CompoundTag tag = new CompoundTag();
        tag.put("position", writeVector(pose.position()));
        tag.put("rotation", writeQuaternion(pose.rotation()));
        return tag;
    }

    private static PhysicsPose readPose(CompoundTag tag) {
        return new PhysicsPose(
                readVector(tag.getCompoundOrEmpty("position")),
                readQuaternion(tag.getCompoundOrEmpty("rotation"))
        );
    }

    private static CompoundTag writeVector(PhysicsVector vector) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("x", vector.x());
        tag.putDouble("y", vector.y());
        tag.putDouble("z", vector.z());
        return tag;
    }

    private static PhysicsVector readVector(CompoundTag tag) {
        return new PhysicsVector(tag.getDoubleOr("x", 0.0D), tag.getDoubleOr("y", 0.0D), tag.getDoubleOr("z", 0.0D));
    }

    private static CompoundTag writeQuaternion(PhysicsQuaternion quaternion) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("x", quaternion.x());
        tag.putDouble("y", quaternion.y());
        tag.putDouble("z", quaternion.z());
        tag.putDouble("w", quaternion.w());
        return tag;
    }

    private static PhysicsQuaternion readQuaternion(CompoundTag tag) {
        return new PhysicsQuaternion(
                tag.getDoubleOr("x", 0.0D),
                tag.getDoubleOr("y", 0.0D),
                tag.getDoubleOr("z", 0.0D),
                tag.getDoubleOr("w", 0.0D)
        );
    }

    private static CompoundTag writeAabb(AABB box) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("min_x", box.minX);
        tag.putDouble("min_y", box.minY);
        tag.putDouble("min_z", box.minZ);
        tag.putDouble("max_x", box.maxX);
        tag.putDouble("max_y", box.maxY);
        tag.putDouble("max_z", box.maxZ);
        return tag;
    }

    private static AABB readAabb(CompoundTag tag) {
        return new AABB(
                tag.getDoubleOr("min_x", 0.0D),
                tag.getDoubleOr("min_y", 0.0D),
                tag.getDoubleOr("min_z", 0.0D),
                tag.getDoubleOr("max_x", 0.0D),
                tag.getDoubleOr("max_y", 0.0D),
                tag.getDoubleOr("max_z", 0.0D)
        );
    }

    private static BlockPos readBlockPos(CompoundTag tag, String key) {
        return tag.read(key, BlockPos.CODEC)
                .orElseThrow(() -> new IllegalArgumentException("Missing block position tag: " + key));
    }

    private static PhysicsPose sanitizePose(
            ServerLevel level,
            AssemblyId id,
            PhysicsPose pose,
            List<AssemblyBlock> blocks
    ) {
        if (isReasonablePose(level, pose)) {
            return pose;
        }

        PhysicsPose fallback = new PhysicsPose(originalBodyCenter(blocks), PhysicsQuaternion.IDENTITY);
        KineticAssembly.LOGGER.warn(
                "Persisted assembly {} had an invalid pose at {}; restoring near original body center {}",
                id,
                describe(pose.position()),
                describe(fallback.position())
        );
        return fallback;
    }

    private static boolean isReasonablePose(ServerLevel level, PhysicsPose pose) {
        PhysicsVector position = pose.position();
        PhysicsQuaternion rotation = pose.rotation();
        return finite(position.x())
                && finite(position.y())
                && finite(position.z())
                && finite(rotation.x())
                && finite(rotation.y())
                && finite(rotation.z())
                && finite(rotation.w())
                && position.y() >= level.getMinY() - RESTORE_Y_MARGIN
                && position.y() <= level.getMaxY() + RESTORE_Y_MARGIN;
    }

    private static PhysicsVector sanitizeLinearVelocity(AssemblyId id, PhysicsVector velocity) {
        if (isReasonableLinearVelocity(velocity)) {
            return velocity;
        }
        KineticAssembly.LOGGER.warn(
                "Persisted assembly {} had an invalid linear velocity {}; restoring with zero velocity",
                id,
                describe(velocity)
        );
        return PhysicsVector.ZERO;
    }

    private static boolean isReasonableLinearVelocity(PhysicsVector velocity) {
        if (!finite(velocity.x()) || !finite(velocity.y()) || !finite(velocity.z())) {
            return false;
        }
        double speedSqr = velocity.x() * velocity.x() + velocity.y() * velocity.y() + velocity.z() * velocity.z();
        return speedSqr <= MAX_RESTORED_LINEAR_SPEED * MAX_RESTORED_LINEAR_SPEED;
    }

    private static PhysicsVector sanitizeAngularVelocity(AssemblyId id, PhysicsVector velocity) {
        if (isReasonableAngularVelocity(velocity)) {
            return velocity;
        }
        KineticAssembly.LOGGER.warn(
                "Persisted assembly {} had an invalid angular velocity {}; restoring with zero angular velocity",
                id,
                describe(velocity)
        );
        return PhysicsVector.ZERO;
    }

    private static boolean isReasonableAngularVelocity(PhysicsVector velocity) {
        if (!finite(velocity.x()) || !finite(velocity.y()) || !finite(velocity.z())) {
            return false;
        }
        double speedSqr = velocity.x() * velocity.x() + velocity.y() * velocity.y() + velocity.z() * velocity.z();
        return speedSqr <= MAX_RESTORED_ANGULAR_SPEED * MAX_RESTORED_ANGULAR_SPEED;
    }

    private static PhysicsVector originalBodyCenter(List<AssemblyBlock> blocks) {
        AssemblyBlock first = blocks.getFirst();
        return new PhysicsVector(
                first.sourcePos().getX() - first.visualLocalOrigin().x(),
                first.sourcePos().getY() - first.visualLocalOrigin().y(),
                first.sourcePos().getZ() - first.visualLocalOrigin().z()
        );
    }

    private static PhysicsVector bodyToPlotOrigin(List<AssemblyBlock> blocks) {
        AssemblyBlock first = blocks.getFirst();
        return new PhysicsVector(
                first.visualLocalOrigin().x() - first.localPos().getX(),
                first.visualLocalOrigin().y() - first.localPos().getY(),
                first.visualLocalOrigin().z() - first.localPos().getZ()
        );
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }

    private static String describe(PhysicsVector vector) {
        return "(" + vector.x() + ", " + vector.y() + ", " + vector.z() + ")";
    }

    private static Data data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    private static Data existingData(ServerLevel level) {
        return level.getDataStorage().get(TYPE);
    }

    private record StoredAssembly(
            AssemblyId id,
            MechanicsBodyId bodyId,
            MechanicsOwner owner,
            AssemblyBounds bounds,
            AssemblyPlot plot,
            PhysicsPose pose,
            AABB worldBounds,
            PhysicsVector linearVelocity,
            PhysicsVector angularVelocity,
            float mass,
            List<AssemblyBlock> blocks,
            List<AssemblyId> dependencies,
            StoredScheduledTicks scheduledTicks
    ) {
        private StoredAssembly {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(bodyId, "bodyId");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(plot, "plot");
            Objects.requireNonNull(pose, "pose");
            Objects.requireNonNull(worldBounds, "worldBounds");
            Objects.requireNonNull(linearVelocity, "linearVelocity");
            Objects.requireNonNull(angularVelocity, "angularVelocity");
            Objects.requireNonNull(dependencies, "dependencies");
            Objects.requireNonNull(scheduledTicks, "scheduledTicks");
            blocks = List.copyOf(blocks);
            dependencies = List.copyOf(dependencies);
        }
    }

    private record RestoreCandidate(StoredAssembly stored, CompoundTag tag) {
        private RestoreCandidate {
            Objects.requireNonNull(stored, "stored");
            tag = tag.copy();
        }
    }

    private record RestorePlan(List<RestoreCandidate> ready, List<RestoreCandidate> deferred) {
        private RestorePlan {
            ready = List.copyOf(ready);
            deferred = List.copyOf(deferred);
        }
    }

    private record RestoreDependencyGroup(
            List<RestoreCandidate> members,
            List<AssemblyId> ids,
            boolean missingDependencies
    ) {
        private RestoreDependencyGroup {
            members = List.copyOf(members);
            ids = List.copyOf(ids);
        }
    }

    private record ChunkBounds(int minX, int maxX, int minZ, int maxZ) {
    }

    private record StoredScheduledTicks(List<StoredChunkTicks> chunks) {
        private StoredScheduledTicks {
            chunks = List.copyOf(chunks);
        }
    }

    private record StoredChunkTicks(ChunkPos chunkPos, ListTag blockTicks, ListTag fluidTicks) {
        private StoredChunkTicks {
            Objects.requireNonNull(chunkPos, "chunkPos");
            Objects.requireNonNull(blockTicks, "blockTicks");
            Objects.requireNonNull(fluidTicks, "fluidTicks");
            blockTicks = blockTicks.copy();
            fluidTicks = fluidTicks.copy();
        }
    }

    private static final class Data extends SavedData {
        private List<CompoundTag> assemblies = List.of();
        private boolean restored;
        private boolean restoreHadFailures;
        private boolean restoreLoadLogged;

        private static Data load(CompoundTag tag, HolderLookup.Provider registries) {
            Data data = new Data();
            ListTag assemblies = tag.getListOrEmpty("assemblies");
            List<CompoundTag> loaded = new ArrayList<>(assemblies.size());
            for (int i = 0; i < assemblies.size(); i++) {
                loaded.add(assemblies.getCompoundOrEmpty(i).copy());
            }
            data.assemblies = List.copyOf(loaded);
            return data;
        }

        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putInt("version", DATA_VERSION);
            ListTag assemblyTags = new ListTag();
            for (CompoundTag assembly : assemblies) {
                assemblyTags.add(assembly.copy());
            }
            tag.put("assemblies", assemblyTags);
            return tag;
        }

        private boolean isEmpty() {
            return assemblies.isEmpty();
        }

        private int size() {
            return assemblies.size();
        }

        private List<CompoundTag> assemblies() {
            return assemblies.stream()
                    .map(CompoundTag::copy)
                    .toList();
        }

        private boolean restored() {
            return restored;
        }

        private boolean restoreHadFailures() {
            return restoreHadFailures;
        }

        private boolean restoreLoadLogged() {
            return restoreLoadLogged;
        }

        private void markRestoreLoadLogged() {
            restoreLoadLogged = true;
        }

        private void markRestored(boolean restoreHadFailures) {
            this.restored = true;
            this.restoreHadFailures = restoreHadFailures;
        }

        private boolean replaceAssemblies(List<CompoundTag> assemblies) {
            List<CompoundTag> copy = assemblies.stream()
                    .map(CompoundTag::copy)
                    .toList();
            if (this.assemblies.equals(copy)) {
                return false;
            }
            this.assemblies = copy;
            setDirty();
            return true;
        }
    }
}
