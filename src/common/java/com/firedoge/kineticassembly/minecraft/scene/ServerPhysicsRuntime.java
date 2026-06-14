package com.firedoge.kineticassembly.minecraft.scene;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.api.PhysicsBackend;
import com.firedoge.kineticassembly.api.PhysicsBoxCollider;
import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsQuaternion;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.api.PhysicsWorldConfig;
import com.firedoge.kineticassembly.config.PhysicsRuntimeConfig;
import com.firedoge.kineticassembly.mechanics.MechanicsBodyId;
import com.firedoge.kineticassembly.mechanics.MechanicsBodyRole;
import com.firedoge.kineticassembly.mechanics.MechanicsBodySnapshot;
import com.firedoge.kineticassembly.mechanics.MechanicsBodyType;
import com.firedoge.kineticassembly.mechanics.MechanicsBoxDefinition;
import com.firedoge.kineticassembly.mechanics.MechanicsCapabilities;
import com.firedoge.kineticassembly.mechanics.MechanicsCompoundBoxDefinition;
import com.firedoge.kineticassembly.mechanics.MechanicsDebugProxy;
import com.firedoge.kineticassembly.mechanics.MechanicsJointId;
import com.firedoge.kineticassembly.mechanics.MechanicsJointSnapshot;
import com.firedoge.kineticassembly.mechanics.MechanicsJointType;
import com.firedoge.kineticassembly.mechanics.MechanicsOwner;
import com.firedoge.kineticassembly.mechanics.MechanicsResult;
import com.firedoge.kineticassembly.mechanics.MechanicsResultCode;
import com.firedoge.kineticassembly.mechanics.MechanicsTickContext;
import com.firedoge.kineticassembly.mechanics.MechanicsTickListener;
import com.firedoge.kineticassembly.mechanics.MechanicsTickPhase;
import com.firedoge.kineticassembly.physics.PhysicsManager;
import com.firedoge.kineticassembly.platform.PlatformServices;
import com.mojang.math.Transformation;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class ServerPhysicsRuntime implements AutoCloseable {
    public static final ServerPhysicsRuntime INSTANCE = new ServerPhysicsRuntime();
    private static final int ACTIVE_OBJECT_TERRAIN_CHUNK_RADIUS = 1;
    private static final int SPAWN_TERRAIN_CHUNK_RADIUS = 1;
    private static final int MAX_TERRAIN_CHUNK_BUILDS_PER_TICK = 1;
    private static final double MAX_ACTIVE_TERRAIN_PREDICTION_DISTANCE = 20.0D;
    private static final double ACTIVE_TERRAIN_PREDICTION_MOVEMENT_THRESHOLD = 0.05D;
    private static final double PHYSICS_TICK_SECONDS = 1.0D / 20.0D;
    private static final int MAX_STRESS_GRID_OBJECTS = 20_000;
    private static final BlockState DEBUG_PROXY_BLOCK = Blocks.LIME_STAINED_GLASS.defaultBlockState();
    private static final MethodHandle DISPLAY_SET_TRANSFORMATION = findDisplaySetTransformation();

    private final PhysicsSceneManager scenes = new PhysicsSceneManager();
    private final Map<String, SceneState> states = new LinkedHashMap<>();
    private final Map<PhysicsObjectId, EntityBinding> entityBindings = new LinkedHashMap<>();
    private final Map<PhysicsObjectId, MechanicsBodyMetadata> mechanicsBodies = new LinkedHashMap<>();
    private final Map<PhysicsJointId, MechanicsJointMetadata> mechanicsJoints = new LinkedHashMap<>();
    private final Set<PhysicsJointId> removedMechanicsJointIds = new LinkedHashSet<>();
    private final Map<PhysicsObjectId, PhysicsPose> mechanicsPoseCache = new LinkedHashMap<>();
    private final Map<PhysicsObjectId, MechanicsBodySnapshot> mechanicsSnapshotCache = new LinkedHashMap<>();
    private final Map<MechanicsTickPhase, List<MechanicsTickListener>> mechanicsTickListeners = new EnumMap<>(MechanicsTickPhase.class);
    private final Map<String, Map<Long, TerrainCollider>> terrainColliders = new LinkedHashMap<>();
    private final Map<String, Map<Long, Set<Long>>> terrainChunks = new LinkedHashMap<>();
    private final Map<String, LinkedHashSet<Long>> terrainBuildQueues = new LinkedHashMap<>();
    private final Map<String, Map<Long, TerrainChunkBuildState>> terrainChunkBuildStates = new LinkedHashMap<>();
    private final Map<String, Integer> activeTerrainScanCursors = new LinkedHashMap<>();
    private long terrainColliderSequence = 1L;
    private int lastTerrainChunkBuildCount;
    private int lastTerrainColliderBuildCount;
    private int lastTerrainPartialColliderBuildCount;
    private long lastTerrainBuildNanos;
    private int debugProxyRecreateCount;
    private int lastDebugProxyRecreateCount;
    private long lastRuntimeTickNanos;
    private long lastQueueActiveNanos;
    private long lastTerrainProcessNanos;
    private long lastStepPhaseNanos;
    private long lastSyncEntitiesNanos;
    private long lastSyncObjectLookupNanos;
    private long lastSyncEntityLookupNanos;
    private long lastSyncRecreateNanos;
    private long lastSyncPoseReadNanos;
    private long lastSyncApplyNanos;
    private int lastActiveSnapshotCount;
    private int lastActiveDynamicCount;
    private int lastActiveTerrainQueuedCount;
    private int lastActiveTerrainSkippedHeightCount;
    private int lastSyncedEntityCount;
    private int lastEntityPoseSyncCount;
    private int lastSyncRemovedBindingCount;
    private int lastSyncMissingEntityCount;
    private int debugProxySyncCursor;

    private ServerPhysicsRuntime() {
    }

    public synchronized MechanicsCapabilities mechanicsCapabilities() {
        PhysicsRuntimeConfig runtimeConfig = runtimeConfig();
        boolean nativeLinked = PhysicsManager.INSTANCE.backend(runtimeConfig.defaultBackend())
                .map(PhysicsBackend::isAvailable)
                .orElse(false);
        return new MechanicsCapabilities(
                nativeLinked,
                nativeLinked,
                nativeLinked,
                nativeLinked,
                nativeLinked,
                nativeLinked,
                true
        );
    }

    public synchronized AutoCloseable addMechanicsTickListener(MechanicsTickPhase phase, MechanicsTickListener listener) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(listener, "listener");
        mechanicsTickListeners.computeIfAbsent(phase, ignored -> new ArrayList<>()).add(listener);
        return () -> {
            synchronized (ServerPhysicsRuntime.this) {
                List<MechanicsTickListener> listeners = mechanicsTickListeners.get(phase);
                if (listeners == null) {
                    return;
                }
                listeners.remove(listener);
                if (listeners.isEmpty()) {
                    mechanicsTickListeners.remove(phase);
                }
            }
        };
    }

    public synchronized ServerPhysicsScene sceneFor(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        String sceneKey = sceneKey(level);
        SceneState state = states.get(sceneKey);
        if (state != null && !state.scene().isClosed()) {
            return state.scene();
        }

        PhysicsRuntimeConfig runtimeConfig = runtimeConfig();
        String backendId = runtimeConfig.defaultBackend();
        PhysicsBackend backend = PhysicsManager.INSTANCE.backend(backendId)
                .orElseThrow(() -> new IllegalStateException("Unknown physics backend: " + backendId));
        PhysicsWorldConfig config = new PhysicsWorldConfig(
                com.firedoge.kineticassembly.api.PhysicsVector.MC_GRAVITY,
                (float) runtimeConfig.fixedTimeStep(),
                runtimeConfig.maxSubSteps(),
                runtimeConfig.enableGpuDynamics()
        );
        ServerPhysicsScene scene = scenes.createScene(sceneKey, backend, config);
        states.put(sceneKey, new SceneState(scene, config.fixedTimeStep(), config.maxSubSteps()));
        return scene;
    }

    public synchronized void tick(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        long tickStart = System.nanoTime();
        dispatchMechanicsTick(server, MechanicsTickPhase.BEFORE_STEP);
        if (states.isEmpty()) {
            clearLastTickProfiling();
            dispatchMechanicsTick(server, MechanicsTickPhase.AFTER_STEP);
            return;
        }

        long queueStart = tickStart;
        ActiveObjectTerrainQueueResult activeQueueResult = queueTerrainAroundActiveObjects(server);
        lastQueueActiveNanos = System.nanoTime() - queueStart;
        lastActiveSnapshotCount = activeQueueResult.snapshotCount();
        lastActiveDynamicCount = activeQueueResult.dynamicCount();
        lastActiveTerrainQueuedCount = activeQueueResult.queuedTerrainChunks();
        lastActiveTerrainSkippedHeightCount = activeQueueResult.skippedByHeight();

        long terrainStart = System.nanoTime();
        processTerrainBuildQueue(server);
        lastTerrainProcessNanos = System.nanoTime() - terrainStart;

        long stepStart = System.nanoTime();
        for (SceneState state : List.copyOf(states.values())) {
            state.advance(1.0F / 20.0F);
        }
        refreshMechanicsStateCache();
        lastStepPhaseNanos = System.nanoTime() - stepStart;
        dispatchMechanicsTick(server, MechanicsTickPhase.AFTER_STEP);

        long syncStart = System.nanoTime();
        EntitySyncResult entitySyncResult = syncBoundEntities(server);
        lastSyncEntitiesNanos = System.nanoTime() - syncStart;
        lastSyncedEntityCount = entitySyncResult.processedBindings();
        lastEntityPoseSyncCount = entitySyncResult.poseSyncs();
        lastSyncObjectLookupNanos = entitySyncResult.objectLookupNanos();
        lastSyncEntityLookupNanos = entitySyncResult.entityLookupNanos();
        lastSyncRecreateNanos = entitySyncResult.recreateNanos();
        lastSyncPoseReadNanos = entitySyncResult.poseReadNanos();
        lastSyncApplyNanos = entitySyncResult.applyNanos();
        lastSyncRemovedBindingCount = entitySyncResult.removedBindings();
        lastSyncMissingEntityCount = entitySyncResult.missingEntities();
        lastRuntimeTickNanos = System.nanoTime() - tickStart;
    }

    public synchronized RuntimeStatus status() {
        int objectCount = 0;
        int dynamicBoxCount = 0;
        int gpuDynamicsSceneCount = 0;
        LinkedHashSet<String> gpuDynamicsStatuses = new LinkedHashSet<>();
        long lastStepNanos = 0L;
        for (SceneState state : states.values()) {
            objectCount += state.scene().objectCount();
            if (state.scene().gpuDynamicsEnabled()) {
                gpuDynamicsSceneCount++;
            }
            gpuDynamicsStatuses.add(state.scene().gpuDynamicsStatus());
            for (PhysicsObjectSnapshot snapshot : state.scene().snapshots()) {
                if (snapshot.type() == PhysicsObjectType.DYNAMIC_BOX && !snapshot.closed()) {
                    dynamicBoxCount++;
                }
            }
            lastStepNanos = Math.max(lastStepNanos, state.lastStepNanos());
        }
        PhysicsRuntimeConfig runtimeConfig = runtimeConfig();
        boolean nativeLinked = PhysicsManager.INSTANCE.backend(runtimeConfig.defaultBackend())
                .map(PhysicsBackend::isAvailable)
                .orElse(false);
        return new RuntimeStatus(
                states.size(),
                objectCount,
                dynamicBoxCount,
                terrainColliderCount(),
                terrainChunkCount(),
                terrainQueuedChunkCount(),
                terrainChunkStateCount(TerrainChunkBuildStatus.BUILT),
                terrainChunkStateCount(TerrainChunkBuildStatus.DIRTY),
                entityBindings.size(),
                nativeLinked,
                runtimeConfig.enableGpuDynamics(),
                gpuDynamicsSceneCount,
                describeGpuDynamicsStatuses(gpuDynamicsStatuses),
                lastStepNanos,
                lastTerrainChunkBuildCount,
                lastTerrainColliderBuildCount,
                lastTerrainPartialColliderBuildCount,
                lastTerrainBuildNanos,
                debugProxyRecreateCount,
                lastDebugProxyRecreateCount,
                lastRuntimeTickNanos,
                lastQueueActiveNanos,
                lastTerrainProcessNanos,
                lastStepPhaseNanos,
                lastSyncEntitiesNanos,
                lastSyncObjectLookupNanos,
                lastSyncEntityLookupNanos,
                lastSyncRecreateNanos,
                lastSyncPoseReadNanos,
                lastSyncApplyNanos,
                lastActiveSnapshotCount,
                lastActiveDynamicCount,
                lastActiveTerrainQueuedCount,
                lastActiveTerrainSkippedHeightCount,
                lastSyncedEntityCount,
                lastEntityPoseSyncCount,
                lastSyncRemovedBindingCount,
                lastSyncMissingEntityCount,
                debugProxySyncTransform(),
                debugProxySyncLimit(),
                activeTerrainScanLimit()
        );
    }

    public synchronized List<PhysicsObjectSnapshot> snapshotsFor(ServerLevel level) {
        SceneState state = states.get(sceneKey(level));
        if (state == null) {
            return List.of();
        }
        return List.copyOf(state.scene().snapshots());
    }

    public synchronized int clearAll() {
        int removed = 0;
        removedMechanicsJointIds.addAll(mechanicsJoints.keySet());
        entityBindings.clear();
        mechanicsBodies.clear();
        mechanicsJoints.clear();
        mechanicsPoseCache.clear();
        mechanicsSnapshotCache.clear();
        terrainColliders.clear();
        terrainChunks.clear();
        terrainBuildQueues.clear();
        terrainChunkBuildStates.clear();
        activeTerrainScanCursors.clear();
        terrainColliderSequence = 1L;
        lastTerrainChunkBuildCount = 0;
        lastTerrainColliderBuildCount = 0;
        lastTerrainPartialColliderBuildCount = 0;
        lastTerrainBuildNanos = 0L;
        debugProxyRecreateCount = 0;
        lastDebugProxyRecreateCount = 0;
        debugProxySyncCursor = 0;
        clearLastTickProfiling();
        for (SceneState state : states.values()) {
            removed += state.scene().objectCount();
            state.scene().clearObjects();
        }
        return removed;
    }

    public synchronized int clearLevel(ServerLevel level) {
        String sceneKey = sceneKey(level);
        removeBindingsForScene(level, sceneKey);
        markMechanicsJointsRemovedForScene(sceneKey);
        removeMechanicsJointsForScene(sceneKey);
        removeMechanicsBodiesForScene(sceneKey);
        terrainColliders.remove(sceneKey);
        terrainChunks.remove(sceneKey);
        terrainBuildQueues.remove(sceneKey);
        terrainChunkBuildStates.remove(sceneKey);
        activeTerrainScanCursors.remove(sceneKey);
        lastTerrainChunkBuildCount = 0;
        lastTerrainColliderBuildCount = 0;
        lastTerrainPartialColliderBuildCount = 0;
        lastTerrainBuildNanos = 0L;
        debugProxyRecreateCount = 0;
        lastDebugProxyRecreateCount = 0;
        debugProxySyncCursor = 0;
        clearLastTickProfiling();
        SceneState state = states.get(sceneKey);
        if (state == null) {
            return 0;
        }
        int removed = state.scene().objectCount();
        state.scene().clearObjects();
        return removed;
    }

    public synchronized Optional<ServerPhysicsScene> existingScene(ServerLevel level) {
        SceneState state = states.get(sceneKey(level));
        return state == null ? Optional.empty() : Optional.of(state.scene());
    }

    public synchronized MechanicsBodySnapshot createMechanicsDynamicBox(ServerLevel level, MechanicsBoxDefinition definition) {
        return createMechanicsDynamicBox(level, MechanicsOwner.UNSPECIFIED, definition);
    }

    public synchronized MechanicsBodySnapshot createMechanicsDynamicBox(
            ServerLevel level,
            MechanicsOwner owner,
            MechanicsBoxDefinition definition
    ) {
        return createMechanicsDynamicBox(level, owner, mechanicsBodyId(PhysicsObjectId.random()), definition);
    }

    public synchronized MechanicsBodySnapshot createMechanicsDynamicBox(
            ServerLevel level,
            MechanicsBodyId id,
            MechanicsBoxDefinition definition
    ) {
        return createMechanicsDynamicBox(level, MechanicsOwner.UNSPECIFIED, id, definition);
    }

    public synchronized MechanicsBodySnapshot createMechanicsDynamicBox(
            ServerLevel level,
            MechanicsOwner owner,
            MechanicsBodyId id,
            MechanicsBoxDefinition definition
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(definition, "definition");
        PhysicsVector halfExtents = definition.halfExtents();
        ServerPhysicsScene scene = sceneFor(level);
        PhysicsObject object = scene.createDynamicBox(
                positiveFloat(halfExtents.x(), "halfExtentX"),
                positiveFloat(halfExtents.y(), "halfExtentY"),
                positiveFloat(halfExtents.z(), "halfExtentZ"),
                definition.pose(),
                definition.mass(),
                physicsObjectId(id)
        );
        MechanicsBodyMetadata metadata = new MechanicsBodyMetadata(
                sceneKey(level),
                level.dimension(),
                MechanicsBodyType.DYNAMIC_BOX,
                definition.role(),
                owner,
                halfExtents,
                definition.mass()
        );
        mechanicsBodies.put(object.id(), metadata);
        return cacheMechanicsSnapshot(object, metadata);
    }

    public synchronized MechanicsBodySnapshot createMechanicsDynamicCompoundBox(ServerLevel level, MechanicsCompoundBoxDefinition definition) {
        return createMechanicsDynamicCompoundBox(level, MechanicsOwner.UNSPECIFIED, definition);
    }

    public synchronized MechanicsBodySnapshot createMechanicsDynamicCompoundBox(
            ServerLevel level,
            MechanicsOwner owner,
            MechanicsCompoundBoxDefinition definition
    ) {
        return createMechanicsDynamicCompoundBox(level, owner, mechanicsBodyId(PhysicsObjectId.random()), definition);
    }

    public synchronized MechanicsBodySnapshot createMechanicsDynamicCompoundBox(
            ServerLevel level,
            MechanicsBodyId id,
            MechanicsCompoundBoxDefinition definition
    ) {
        return createMechanicsDynamicCompoundBox(level, MechanicsOwner.UNSPECIFIED, id, definition);
    }

    public synchronized MechanicsBodySnapshot createMechanicsDynamicCompoundBox(
            ServerLevel level,
            MechanicsOwner owner,
            MechanicsBodyId id,
            MechanicsCompoundBoxDefinition definition
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(definition, "definition");
        List<PhysicsBoxCollider> boxes = definition.boxes();
        ServerPhysicsScene scene = sceneFor(level);
        PhysicsObject object = definition.massProperties() == null
                ? scene.createDynamicCompoundBox(boxes, definition.pose(), definition.mass(), physicsObjectId(id))
                : scene.createDynamicCompoundBox(boxes, definition.pose(), definition.massProperties(), physicsObjectId(id));
        MechanicsBodyMetadata metadata = new MechanicsBodyMetadata(
                sceneKey(level),
                level.dimension(),
                MechanicsBodyType.DYNAMIC_COMPOUND_BOX,
                definition.role(),
                owner,
                definition.halfExtents(),
                definition.mass()
        );
        mechanicsBodies.put(object.id(), metadata);
        return cacheMechanicsSnapshot(object, metadata);
    }

    public synchronized MechanicsJointSnapshot createMechanicsFixedJoint(ServerLevel level, MechanicsBodyId firstBodyId, MechanicsBodyId secondBodyId) {
        return createMechanicsFixedJoint(level, firstBodyId, secondBodyId, false);
    }

    public synchronized MechanicsJointSnapshot createMechanicsFixedJoint(
            ServerLevel level,
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            boolean collideConnected
    ) {
        ResolvedMechanicsJointBodies bodies = requireMechanicsJointBodies(level, firstBodyId, secondBodyId, "Fixed");
        PhysicsPose firstPose = requirePhysicsObjectPose(bodies.state().scene(), bodies.firstObjectId(), "firstBodyId");
        PhysicsPose secondPose = requirePhysicsObjectPose(bodies.state().scene(), bodies.secondObjectId(), "secondBodyId");
        MechanicsJointDefinition definition = MechanicsJointDefinition.frames(
                PhysicsPose.IDENTITY,
                bodyLocalFrame(secondPose, firstPose),
                collideConnected
        );
        PhysicsJointSnapshot snapshot = bodies.state().scene().createFixedJointWithLocalFrames(
                bodies.firstObjectId(),
                definition.firstLocalFrame(),
                bodies.secondObjectId(),
                definition.secondLocalFrame(),
                definition.collideConnected()
        );
        MechanicsJointMetadata metadata = new MechanicsJointMetadata(
                bodies.sceneKey(),
                level.dimension(),
                MechanicsJointType.FIXED,
                bodies.firstObjectId(),
                bodies.secondObjectId(),
                definition
        );
        mechanicsJoints.put(snapshot.id(), metadata);
        return mechanicsJointSnapshot(snapshot, metadata);
    }

    public synchronized MechanicsJointSnapshot createMechanicsFixedJointAtWorldFrame(
            ServerLevel level,
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            PhysicsPose worldFrame
    ) {
        return createMechanicsFixedJointAtWorldFrame(level, firstBodyId, secondBodyId, worldFrame, false);
    }

    public synchronized MechanicsJointSnapshot createMechanicsFixedJointAtWorldFrame(
            ServerLevel level,
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            PhysicsPose worldFrame,
            boolean collideConnected
    ) {
        Objects.requireNonNull(worldFrame, "worldFrame");
        ResolvedMechanicsJointBodies bodies = requireMechanicsJointBodies(level, firstBodyId, secondBodyId, "Fixed");
        MechanicsJointDefinition definition = frameDefinitionFromWorldFrame(
                bodies.state().scene(),
                bodies.firstObjectId(),
                bodies.secondObjectId(),
                worldFrame,
                collideConnected
        );
        PhysicsJointSnapshot snapshot = bodies.state().scene().createFixedJointWithLocalFrames(
                bodies.firstObjectId(),
                definition.firstLocalFrame(),
                bodies.secondObjectId(),
                definition.secondLocalFrame(),
                definition.collideConnected()
        );
        MechanicsJointMetadata metadata = new MechanicsJointMetadata(
                bodies.sceneKey(),
                level.dimension(),
                MechanicsJointType.FIXED,
                bodies.firstObjectId(),
                bodies.secondObjectId(),
                definition
        );
        mechanicsJoints.put(snapshot.id(), metadata);
        return mechanicsJointSnapshot(snapshot, metadata);
    }

    public synchronized MechanicsJointSnapshot createMechanicsDistanceJoint(
            ServerLevel level,
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping
    ) {
        return createMechanicsDistanceJoint(
                level,
                firstBodyId,
                secondBodyId,
                minDistance,
                maxDistance,
                stiffness,
                damping,
                false
        );
    }

    public synchronized MechanicsJointSnapshot createMechanicsDistanceJoint(
            ServerLevel level,
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping,
            boolean collideConnected
    ) {
        ResolvedMechanicsJointBodies bodies = requireMechanicsJointBodies(level, firstBodyId, secondBodyId, "Distance");
        MechanicsJointDefinition definition = MechanicsJointDefinition.distance(
                PhysicsVector.ZERO,
                PhysicsVector.ZERO,
                minDistance,
                maxDistance,
                stiffness,
                damping,
                collideConnected
        );
        PhysicsJointSnapshot snapshot = bodies.state().scene().createDistanceJointWithLocalAnchors(
                bodies.firstObjectId(),
                definition.firstLocalAnchor(),
                bodies.secondObjectId(),
                definition.secondLocalAnchor(),
                definition.minDistance(),
                definition.maxDistance(),
                definition.stiffness(),
                definition.damping(),
                definition.collideConnected()
        );
        MechanicsJointMetadata metadata = new MechanicsJointMetadata(
                bodies.sceneKey(),
                level.dimension(),
                MechanicsJointType.DISTANCE,
                bodies.firstObjectId(),
                bodies.secondObjectId(),
                definition
        );
        mechanicsJoints.put(snapshot.id(), metadata);
        return mechanicsJointSnapshot(snapshot, metadata);
    }

    public synchronized MechanicsJointSnapshot createMechanicsDistanceJointAtWorldAnchors(
            ServerLevel level,
            MechanicsBodyId firstBodyId,
            PhysicsVector firstWorldAnchor,
            MechanicsBodyId secondBodyId,
            PhysicsVector secondWorldAnchor,
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping
    ) {
        return createMechanicsDistanceJointAtWorldAnchors(
                level,
                firstBodyId,
                firstWorldAnchor,
                secondBodyId,
                secondWorldAnchor,
                minDistance,
                maxDistance,
                stiffness,
                damping,
                false
        );
    }

    public synchronized MechanicsJointSnapshot createMechanicsDistanceJointAtWorldAnchors(
            ServerLevel level,
            MechanicsBodyId firstBodyId,
            PhysicsVector firstWorldAnchor,
            MechanicsBodyId secondBodyId,
            PhysicsVector secondWorldAnchor,
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping,
            boolean collideConnected
    ) {
        Objects.requireNonNull(firstWorldAnchor, "firstWorldAnchor");
        Objects.requireNonNull(secondWorldAnchor, "secondWorldAnchor");
        ResolvedMechanicsJointBodies bodies = requireMechanicsJointBodies(level, firstBodyId, secondBodyId, "Distance");
        PhysicsPose firstPose = requirePhysicsObjectPose(bodies.state().scene(), bodies.firstObjectId(), "firstBodyId");
        PhysicsPose secondPose = requirePhysicsObjectPose(bodies.state().scene(), bodies.secondObjectId(), "secondBodyId");
        MechanicsJointDefinition definition = MechanicsJointDefinition.distance(
                bodyLocalPoint(firstPose, firstWorldAnchor),
                bodyLocalPoint(secondPose, secondWorldAnchor),
                minDistance,
                maxDistance,
                stiffness,
                damping,
                collideConnected
        );
        PhysicsJointSnapshot snapshot = bodies.state().scene().createDistanceJointWithLocalAnchors(
                bodies.firstObjectId(),
                definition.firstLocalAnchor(),
                bodies.secondObjectId(),
                definition.secondLocalAnchor(),
                definition.minDistance(),
                definition.maxDistance(),
                definition.stiffness(),
                definition.damping(),
                definition.collideConnected()
        );
        MechanicsJointMetadata metadata = new MechanicsJointMetadata(
                bodies.sceneKey(),
                level.dimension(),
                MechanicsJointType.DISTANCE,
                bodies.firstObjectId(),
                bodies.secondObjectId(),
                definition
        );
        mechanicsJoints.put(snapshot.id(), metadata);
        return mechanicsJointSnapshot(snapshot, metadata);
    }

    public synchronized MechanicsJointSnapshot createMechanicsRevoluteJointAtWorldFrame(
            ServerLevel level,
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            PhysicsPose worldFrame
    ) {
        return createMechanicsRevoluteJointAtWorldFrame(level, firstBodyId, secondBodyId, worldFrame, false);
    }

    public synchronized MechanicsJointSnapshot createMechanicsRevoluteJointAtWorldFrame(
            ServerLevel level,
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            PhysicsPose worldFrame,
            boolean collideConnected
    ) {
        Objects.requireNonNull(worldFrame, "worldFrame");
        ResolvedMechanicsJointBodies bodies = requireMechanicsJointBodies(level, firstBodyId, secondBodyId, "Revolute");
        MechanicsJointDefinition definition = frameDefinitionFromWorldFrame(
                bodies.state().scene(),
                bodies.firstObjectId(),
                bodies.secondObjectId(),
                worldFrame,
                collideConnected
        );
        PhysicsJointSnapshot snapshot = bodies.state().scene().createRevoluteJointWithLocalFrames(
                bodies.firstObjectId(),
                definition.firstLocalFrame(),
                bodies.secondObjectId(),
                definition.secondLocalFrame(),
                definition.collideConnected()
        );
        MechanicsJointMetadata metadata = new MechanicsJointMetadata(
                bodies.sceneKey(),
                level.dimension(),
                MechanicsJointType.REVOLUTE,
                bodies.firstObjectId(),
                bodies.secondObjectId(),
                definition
        );
        mechanicsJoints.put(snapshot.id(), metadata);
        return mechanicsJointSnapshot(snapshot, metadata);
    }

    public synchronized MechanicsJointSnapshot createMechanicsPrismaticJointAtWorldFrame(
            ServerLevel level,
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            PhysicsPose worldFrame
    ) {
        return createMechanicsPrismaticJointAtWorldFrame(level, firstBodyId, secondBodyId, worldFrame, false);
    }

    public synchronized MechanicsJointSnapshot createMechanicsPrismaticJointAtWorldFrame(
            ServerLevel level,
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            PhysicsPose worldFrame,
            boolean collideConnected
    ) {
        Objects.requireNonNull(worldFrame, "worldFrame");
        ResolvedMechanicsJointBodies bodies = requireMechanicsJointBodies(level, firstBodyId, secondBodyId, "Prismatic");
        MechanicsJointDefinition definition = frameDefinitionFromWorldFrame(
                bodies.state().scene(),
                bodies.firstObjectId(),
                bodies.secondObjectId(),
                worldFrame,
                collideConnected
        );
        PhysicsJointSnapshot snapshot = bodies.state().scene().createPrismaticJointWithLocalFrames(
                bodies.firstObjectId(),
                definition.firstLocalFrame(),
                bodies.secondObjectId(),
                definition.secondLocalFrame(),
                definition.collideConnected()
        );
        MechanicsJointMetadata metadata = new MechanicsJointMetadata(
                bodies.sceneKey(),
                level.dimension(),
                MechanicsJointType.PRISMATIC,
                bodies.firstObjectId(),
                bodies.secondObjectId(),
                definition
        );
        mechanicsJoints.put(snapshot.id(), metadata);
        return mechanicsJointSnapshot(snapshot, metadata);
    }

    public synchronized Optional<MechanicsBodySnapshot> mechanicsSnapshot(ServerLevel level, MechanicsBodyId id) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        PhysicsObjectId objectId = physicsObjectId(id);
        MechanicsBodyMetadata metadata = mechanicsBodies.get(objectId);
        if (metadata == null || !metadata.levelKey().equals(level.dimension())) {
            return Optional.empty();
        }
        SceneState state = states.get(metadata.sceneKey());
        if (state == null || state.scene().isClosed()) {
            forgetMechanicsBody(objectId);
            return Optional.empty();
        }
        MechanicsBodySnapshot cachedSnapshot = mechanicsSnapshotCache.get(objectId);
        if (cachedSnapshot != null) {
            return Optional.of(cachedSnapshot);
        }
        Optional<PhysicsObject> object = state.scene().object(objectId);
        if (object.isEmpty()) {
            forgetMechanicsBody(objectId);
            return Optional.empty();
        }
        PhysicsObject physicsObject = object.get();
        if (physicsObject.isClosed()) {
            forgetMechanicsBody(objectId);
            return Optional.empty();
        }
        return Optional.of(cacheMechanicsSnapshot(physicsObject, metadata));
    }

    public synchronized Optional<PhysicsPose> mechanicsPose(ServerLevel level, MechanicsBodyId id) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        PhysicsObjectId objectId = physicsObjectId(id);
        MechanicsBodyMetadata metadata = mechanicsBodies.get(objectId);
        if (metadata == null || !metadata.levelKey().equals(level.dimension())) {
            return Optional.empty();
        }
        SceneState state = states.get(metadata.sceneKey());
        if (state == null || state.scene().isClosed()) {
            forgetMechanicsBody(objectId);
            return Optional.empty();
        }
        PhysicsPose cachedPose = mechanicsPoseCache.get(objectId);
        if (cachedPose != null) {
            return Optional.of(cachedPose);
        }
        MechanicsBodySnapshot cachedSnapshot = mechanicsSnapshotCache.get(objectId);
        if (cachedSnapshot != null) {
            mechanicsPoseCache.put(objectId, cachedSnapshot.pose());
            return Optional.of(cachedSnapshot.pose());
        }
        Optional<PhysicsObject> object = state.scene().object(objectId);
        if (object.isEmpty()) {
            forgetMechanicsBody(objectId);
            return Optional.empty();
        }
        PhysicsObject physicsObject = object.get();
        if (physicsObject.isClosed()) {
            forgetMechanicsBody(objectId);
            return Optional.empty();
        }
        return Optional.of(cacheMechanicsPose(physicsObject));
    }

    public synchronized List<MechanicsBodySnapshot> mechanicsSnapshots(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        String sceneKey = sceneKey(level);
        SceneState state = states.get(sceneKey);
        if (state == null || state.scene().isClosed()) {
            return List.of();
        }
        List<MechanicsBodySnapshot> snapshots = new ArrayList<>();
        for (Map.Entry<PhysicsObjectId, MechanicsBodyMetadata> entry : List.copyOf(mechanicsBodies.entrySet())) {
            MechanicsBodyMetadata metadata = entry.getValue();
            if (!metadata.sceneKey().equals(sceneKey)) {
                continue;
            }
            MechanicsBodySnapshot cachedSnapshot = mechanicsSnapshotCache.get(entry.getKey());
            if (cachedSnapshot != null) {
                snapshots.add(cachedSnapshot);
                continue;
            }
            Optional<PhysicsObject> object = state.scene().object(entry.getKey());
            if (object.isEmpty()) {
                forgetMechanicsBody(entry.getKey());
                continue;
            }
            PhysicsObject physicsObject = object.get();
            if (physicsObject.isClosed()) {
                forgetMechanicsBody(entry.getKey());
                continue;
            }
            snapshots.add(cacheMechanicsSnapshot(physicsObject, metadata));
        }
        return List.copyOf(snapshots);
    }

    public synchronized Optional<MechanicsJointSnapshot> mechanicsJointSnapshot(ServerLevel level, MechanicsJointId id) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        PhysicsJointId jointId = physicsJointId(id);
        MechanicsJointMetadata metadata = mechanicsJoints.get(jointId);
        if (metadata == null || !metadata.levelKey().equals(level.dimension())) {
            return Optional.empty();
        }
        SceneState state = states.get(metadata.sceneKey());
        if (state == null || state.scene().isClosed()) {
            forgetMechanicsJoint(jointId);
            return Optional.empty();
        }
        Optional<PhysicsJointSnapshot> snapshot = state.scene().joint(jointId);
        if (snapshot.isEmpty() || snapshot.get().closed()) {
            forgetMechanicsJoint(jointId);
            return Optional.empty();
        }
        return Optional.of(mechanicsJointSnapshot(snapshot.get(), metadata));
    }

    public synchronized List<MechanicsJointSnapshot> mechanicsJointSnapshots(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        String sceneKey = sceneKey(level);
        SceneState state = states.get(sceneKey);
        if (state == null || state.scene().isClosed()) {
            return List.of();
        }

        List<MechanicsJointSnapshot> snapshots = new ArrayList<>();
        for (Map.Entry<PhysicsJointId, MechanicsJointMetadata> entry : List.copyOf(mechanicsJoints.entrySet())) {
            MechanicsJointMetadata metadata = entry.getValue();
            if (!metadata.sceneKey().equals(sceneKey)) {
                continue;
            }
            Optional<PhysicsJointSnapshot> snapshot = state.scene().joint(entry.getKey());
            if (snapshot.isEmpty() || snapshot.get().closed()) {
                forgetMechanicsJoint(entry.getKey());
                continue;
            }
            snapshots.add(mechanicsJointSnapshot(snapshot.get(), metadata));
        }
        return List.copyOf(snapshots);
    }

    synchronized List<PersistedMechanicsJoint> persistedMechanicsJoints(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        String sceneKey = sceneKey(level);
        SceneState state = states.get(sceneKey);
        if (state == null || state.scene().isClosed()) {
            return List.of();
        }

        List<PersistedMechanicsJoint> joints = new ArrayList<>();
        for (Map.Entry<PhysicsJointId, MechanicsJointMetadata> entry : List.copyOf(mechanicsJoints.entrySet())) {
            MechanicsJointMetadata metadata = entry.getValue();
            if (!metadata.sceneKey().equals(sceneKey)) {
                continue;
            }
            Optional<PhysicsJointSnapshot> snapshot = state.scene().joint(entry.getKey());
            if (snapshot.isEmpty() || snapshot.get().closed()) {
                forgetMechanicsJoint(entry.getKey());
                continue;
            }
            joints.add(new PersistedMechanicsJoint(
                    entry.getKey(),
                    metadata.levelKey(),
                    metadata.type(),
                    metadata.firstBodyId(),
                    metadata.secondBodyId(),
                    metadata.definition()
            ));
        }
        return List.copyOf(joints);
    }

    synchronized MechanicsJointRestoreResult restorePersistedMechanicsJoint(ServerLevel level, PersistedMechanicsJoint record) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(record, "record");
        if (!record.levelKey().equals(level.dimension())) {
            return MechanicsJointRestoreResult.FAILED;
        }
        if (removedMechanicsJointIds.contains(record.id())) {
            return MechanicsJointRestoreResult.FAILED;
        }

        MechanicsJointMetadata existingMetadata = mechanicsJoints.get(record.id());
        if (existingMetadata != null) {
            SceneState existingState = states.get(existingMetadata.sceneKey());
            Optional<PhysicsJointSnapshot> existing = existingState == null || existingState.scene().isClosed()
                    ? Optional.empty()
                    : existingState.scene().joint(record.id());
            if (existing.isPresent() && !existing.get().closed()) {
                return MechanicsJointRestoreResult.ALREADY_ACTIVE;
            }
            forgetMechanicsJoint(record.id());
        }

        Optional<ResolvedMechanicsJointBodies> maybeBodies = resolveMechanicsJointBodies(
                level,
                record.firstBodyId(),
                record.secondBodyId()
        );
        if (maybeBodies.isEmpty()) {
            return MechanicsJointRestoreResult.DEFERRED;
        }

        ResolvedMechanicsJointBodies bodies = maybeBodies.get();
        PhysicsJointSnapshot snapshot = createMechanicsJointFromDefinition(
                bodies.state().scene(),
                record.id(),
                record.type(),
                record.firstBodyId(),
                record.secondBodyId(),
                record.definition()
        );
        MechanicsJointMetadata metadata = new MechanicsJointMetadata(
                bodies.sceneKey(),
                level.dimension(),
                record.type(),
                record.firstBodyId(),
                record.secondBodyId(),
                record.definition()
        );
        mechanicsJoints.put(snapshot.id(), metadata);
        return MechanicsJointRestoreResult.RESTORED;
    }

    public synchronized List<MechanicsJointSnapshot> replaceMechanicsBodyPreservingJoints(
            ServerLevel level,
            MechanicsBodyId previousBodyId,
            MechanicsBodyId replacementBodyId
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(previousBodyId, "previousBodyId");
        Objects.requireNonNull(replacementBodyId, "replacementBodyId");
        if (previousBodyId.equals(replacementBodyId)) {
            return List.of();
        }

        PhysicsObjectId previousObjectId = physicsObjectId(previousBodyId);
        PhysicsObjectId replacementObjectId = physicsObjectId(replacementBodyId);
        MechanicsBodyMetadata previousMetadata = mechanicsBodies.get(previousObjectId);
        MechanicsBodyMetadata replacementMetadata = mechanicsBodies.get(replacementObjectId);
        if (previousMetadata == null || replacementMetadata == null) {
            throw new IllegalArgumentException("Both mechanics bodies must exist for replacement");
        }
        if (!previousMetadata.levelKey().equals(level.dimension()) || !replacementMetadata.levelKey().equals(level.dimension())) {
            throw new IllegalArgumentException("Both mechanics bodies must be in this level");
        }
        if (!previousMetadata.sceneKey().equals(replacementMetadata.sceneKey())) {
            throw new IllegalArgumentException("Replacement mechanics bodies must be in the same physics scene");
        }

        SceneState state = states.get(previousMetadata.sceneKey());
        if (state == null || state.scene().isClosed()) {
            throw new IllegalStateException("Physics scene is not available for mechanics body replacement");
        }

        List<RemappedMechanicsJoint> attachedJoints = remappedMechanicsJoints(state.scene(), previousObjectId, replacementObjectId);
        forgetMechanicsBody(previousObjectId);
        if (!state.scene().removeObject(previousObjectId)) {
            throw new IllegalStateException("Previous mechanics body " + previousBodyId + " no longer exists in the physics scene");
        }

        List<MechanicsJointSnapshot> restored = new ArrayList<>(attachedJoints.size());
        for (RemappedMechanicsJoint remapped : attachedJoints) {
            try {
                PhysicsJointSnapshot snapshot = createMechanicsJointFromDefinition(
                        state.scene(),
                        remapped.id(),
                        remapped.type(),
                        remapped.firstBodyId(),
                        remapped.secondBodyId(),
                        remapped.definition()
                );
                MechanicsJointMetadata metadata = new MechanicsJointMetadata(
                        previousMetadata.sceneKey(),
                        level.dimension(),
                        remapped.type(),
                        remapped.firstBodyId(),
                        remapped.secondBodyId(),
                        remapped.definition()
                );
                mechanicsJoints.put(snapshot.id(), metadata);
                restored.add(mechanicsJointSnapshot(snapshot, metadata));
            } catch (RuntimeException exception) {
                KineticAssembly.LOGGER.warn(
                        "Failed to restore mechanics joint {} while replacing body {} with {}",
                        remapped.id(),
                        previousBodyId,
                        replacementBodyId,
                        exception
                );
            }
        }
        return List.copyOf(restored);
    }

    synchronized boolean mechanicsBodiesAvailable(ServerLevel level, PhysicsObjectId firstBodyId, PhysicsObjectId secondBodyId) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(firstBodyId, "firstBodyId");
        Objects.requireNonNull(secondBodyId, "secondBodyId");
        return resolveMechanicsJointBodies(level, firstBodyId, secondBodyId).isPresent();
    }

    synchronized boolean wasMechanicsJointExplicitlyRemoved(PhysicsJointId jointId) {
        Objects.requireNonNull(jointId, "jointId");
        return removedMechanicsJointIds.contains(jointId);
    }

    public synchronized boolean setMechanicsLinearVelocity(ServerLevel level, MechanicsBodyId id, PhysicsVector velocity) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(velocity, "velocity");
        Optional<PhysicsObject> object = mechanicsObject(level, id);
        if (object.isEmpty()) {
            return false;
        }
        object.get().setLinearVelocity(velocity);
        mechanicsSnapshotCache.remove(physicsObjectId(id));
        return true;
    }

    public synchronized boolean setMechanicsAngularVelocity(ServerLevel level, MechanicsBodyId id, PhysicsVector velocity) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(velocity, "velocity");
        Optional<PhysicsObject> object = mechanicsObject(level, id);
        if (object.isEmpty()) {
            return false;
        }
        object.get().setAngularVelocity(velocity);
        mechanicsSnapshotCache.remove(physicsObjectId(id));
        return true;
    }

    public synchronized boolean setMechanicsPose(ServerLevel level, MechanicsBodyId id, PhysicsPose pose) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(pose, "pose");
        Optional<PhysicsObject> object = mechanicsObject(level, id);
        if (object.isEmpty()) {
            return false;
        }
        object.get().setPose(pose);
        mechanicsPoseCache.put(physicsObjectId(id), pose);
        mechanicsSnapshotCache.remove(physicsObjectId(id));
        return true;
    }

    public synchronized boolean applyMechanicsLinearImpulse(ServerLevel level, MechanicsBodyId id, PhysicsVector impulse) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(impulse, "impulse");
        PhysicsObjectId objectId = physicsObjectId(id);
        MechanicsBodyMetadata metadata = mechanicsBodies.get(objectId);
        if (metadata == null || metadata.mass() <= 0.0F) {
            return false;
        }
        Optional<PhysicsObject> object = mechanicsObject(level, id);
        if (object.isEmpty()) {
            return false;
        }
        boolean applied = object.get().applyLinearImpulse(impulse);
        if (applied) {
            mechanicsSnapshotCache.remove(objectId);
        }
        return applied;
    }

    public synchronized boolean applyMechanicsAngularImpulse(ServerLevel level, MechanicsBodyId id, PhysicsVector impulse) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(impulse, "impulse");
        PhysicsObjectId objectId = physicsObjectId(id);
        MechanicsBodyMetadata metadata = mechanicsBodies.get(objectId);
        if (metadata == null || metadata.mass() <= 0.0F) {
            return false;
        }
        Optional<PhysicsObject> object = mechanicsObject(level, id);
        if (object.isEmpty()) {
            return false;
        }
        boolean applied = object.get().applyAngularImpulse(impulse);
        if (applied) {
            mechanicsSnapshotCache.remove(objectId);
        }
        return applied;
    }

    public synchronized boolean applyMechanicsImpulseAtPoint(
            ServerLevel level,
            MechanicsBodyId id,
            PhysicsVector impulse,
            PhysicsVector point
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(impulse, "impulse");
        Objects.requireNonNull(point, "point");
        PhysicsObjectId objectId = physicsObjectId(id);
        MechanicsBodyMetadata metadata = mechanicsBodies.get(objectId);
        if (metadata == null || metadata.mass() <= 0.0F) {
            return false;
        }
        Optional<PhysicsObject> object = mechanicsObject(level, id);
        if (object.isEmpty()) {
            return false;
        }
        boolean applied = object.get().applyImpulseAtPoint(impulse, point);
        if (applied) {
            mechanicsSnapshotCache.remove(objectId);
        }
        return applied;
    }

    public synchronized MechanicsResult<Void> applyMechanicsForce(ServerLevel level, MechanicsBodyId id, PhysicsVector force) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(force, "force");
        MechanicsResult<Void> validation = validateMechanicsVector(force, "force");
        if (!validation.success()) {
            return validation;
        }
        PhysicsObjectId objectId = physicsObjectId(id);
        MechanicsResult<PhysicsObject> object = mechanicsDynamicObject(level, id);
        if (!object.success()) {
            return MechanicsResult.failure(object.code(), object.message());
        }
        boolean applied = object.value().orElseThrow().applyForce(force);
        if (!applied) {
            return MechanicsResult.failure(MechanicsResultCode.NATIVE_UNAVAILABLE, "Native backend did not accept the force");
        }
        mechanicsSnapshotCache.remove(objectId);
        return MechanicsResult.ok();
    }

    public synchronized MechanicsResult<Void> applyMechanicsTorque(ServerLevel level, MechanicsBodyId id, PhysicsVector torque) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(torque, "torque");
        MechanicsResult<Void> validation = validateMechanicsVector(torque, "torque");
        if (!validation.success()) {
            return validation;
        }
        PhysicsObjectId objectId = physicsObjectId(id);
        MechanicsResult<PhysicsObject> object = mechanicsDynamicObject(level, id);
        if (!object.success()) {
            return MechanicsResult.failure(object.code(), object.message());
        }
        boolean applied = object.value().orElseThrow().applyTorque(torque);
        if (!applied) {
            return MechanicsResult.failure(MechanicsResultCode.NATIVE_UNAVAILABLE, "Native backend did not accept the torque");
        }
        mechanicsSnapshotCache.remove(objectId);
        return MechanicsResult.ok();
    }

    public synchronized MechanicsResult<Void> applyMechanicsForceAtPoint(
            ServerLevel level,
            MechanicsBodyId id,
            PhysicsVector force,
            PhysicsVector point
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(force, "force");
        Objects.requireNonNull(point, "point");
        MechanicsResult<Void> forceValidation = validateMechanicsVector(force, "force");
        if (!forceValidation.success()) {
            return forceValidation;
        }
        MechanicsResult<Void> pointValidation = validateMechanicsVector(point, "point");
        if (!pointValidation.success()) {
            return pointValidation;
        }
        PhysicsObjectId objectId = physicsObjectId(id);
        MechanicsResult<PhysicsObject> object = mechanicsDynamicObject(level, id);
        if (!object.success()) {
            return MechanicsResult.failure(object.code(), object.message());
        }
        boolean applied = object.value().orElseThrow().applyForceAtPoint(force, point);
        if (!applied) {
            return MechanicsResult.failure(MechanicsResultCode.NATIVE_UNAVAILABLE, "Native backend did not accept the point force");
        }
        mechanicsSnapshotCache.remove(objectId);
        return MechanicsResult.ok();
    }

    public synchronized boolean removeMechanicsBody(ServerLevel level, MechanicsBodyId id) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        PhysicsObjectId objectId = physicsObjectId(id);
        MechanicsBodyMetadata metadata = mechanicsBodies.get(objectId);
        if (metadata == null || !metadata.levelKey().equals(level.dimension())) {
            return false;
        }
        SceneState state = states.get(metadata.sceneKey());
        if (state == null || state.scene().isClosed()) {
            forgetMechanicsBody(objectId);
            return false;
        }
        discardBoundEntity(level, objectId);
        markMechanicsJointsRemovedForBody(objectId);
        forgetMechanicsBody(objectId);
        return state.scene().removeObject(objectId);
    }

    public synchronized boolean removeMechanicsJoint(ServerLevel level, MechanicsJointId id) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        PhysicsJointId jointId = physicsJointId(id);
        MechanicsJointMetadata metadata = mechanicsJoints.get(jointId);
        if (metadata == null || !metadata.levelKey().equals(level.dimension())) {
            return false;
        }
        SceneState state = states.get(metadata.sceneKey());
        removedMechanicsJointIds.add(jointId);
        forgetMechanicsJoint(jointId);
        return state != null && !state.scene().isClosed() && state.scene().removeJoint(jointId);
    }

    public synchronized Optional<MechanicsDebugProxy> showMechanicsDebugProxy(ServerLevel level, MechanicsBodyId id) {
        return showMechanicsDebugProxy(level, id, DEBUG_PROXY_BLOCK);
    }

    public synchronized Optional<MechanicsDebugProxy> showMechanicsDebugProxy(ServerLevel level, MechanicsBodyId id, BlockState displayState) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayState, "displayState");
        PhysicsObjectId objectId = physicsObjectId(id);
        Optional<PhysicsObject> maybeObject = mechanicsObject(level, id);
        if (maybeObject.isEmpty()) {
            return Optional.empty();
        }
        MechanicsBodyMetadata metadata = mechanicsBodies.get(objectId);
        if (metadata == null) {
            return Optional.empty();
        }

        EntityBinding existing = entityBindings.get(objectId);
        if (existing != null && existing.sceneKey().equals(metadata.sceneKey())) {
            Entity existingEntity = level.getEntity(existing.entityId());
            if (existingEntity != null && !existingEntity.isRemoved()) {
                return Optional.of(new MechanicsDebugProxy(id, existing.entityId(), false));
            }
            entityBindings.remove(objectId);
        }

        Display.BlockDisplay entity = createDebugEntity(level, maybeObject.get(), metadata.halfExtents(), displayState);
        if (!level.addFreshEntity(entity)) {
            return Optional.empty();
        }
        entityBindings.put(objectId, new EntityBinding(metadata.sceneKey(), metadata.levelKey(), objectId, entity.getUUID(), metadata.halfExtents(), displayState));
        return Optional.of(new MechanicsDebugProxy(id, entity.getUUID(), true));
    }

    public synchronized boolean hideMechanicsDebugProxy(ServerLevel level, MechanicsBodyId id) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        return discardBoundEntity(level, physicsObjectId(id)) != null;
    }

    public synchronized SpawnedDebugBox spawnDebugBox(ServerLevel level, Vec3 position) {
        return spawnDebugBox(level, position, 1.0F, 1.0F);
    }

    public synchronized SpawnedDebugBox spawnDebugBox(ServerLevel level, Vec3 position, float size, float mass) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(position, "position");
        if (size <= 0.0F) {
            throw new IllegalArgumentException("Box size must be positive");
        }
        if (mass <= 0.0F) {
            throw new IllegalArgumentException("Box mass must be positive");
        }
        ServerPhysicsScene scene = sceneFor(level);
        int queuedTerrainChunks = queueTerrainAround(level, BlockPos.containing(position), SPAWN_TERRAIN_CHUNK_RADIUS);
        float halfExtent = size * 0.5F;
        PhysicsVector halfExtents = new PhysicsVector(halfExtent, halfExtent, halfExtent);
        PhysicsObject box;
        try {
            box = scene.createDynamicBox(
                    halfExtent,
                    halfExtent,
                    halfExtent,
                    new PhysicsPose(new PhysicsVector(position.x(), position.y() + halfExtent + 1.5D, position.z()), PhysicsQuaternion.IDENTITY),
                    mass
            );
        } catch (RuntimeException exception) {
            throw exception;
        }

        Display.BlockDisplay entity = createDebugEntity(level, box, halfExtents);
        if (!level.addFreshEntity(entity)) {
            box.close();
            throw new IllegalStateException("Failed to spawn debug entity for physics object " + box.id());
        }

        EntityBinding binding = new EntityBinding(sceneKey(level), level.dimension(), box.id(), entity.getUUID(), halfExtents, DEBUG_PROXY_BLOCK);
        entityBindings.put(box.id(), binding);
        return new SpawnedDebugBox(box, entity.getUUID(), queuedTerrainChunks, halfExtents, mass);
    }

    public synchronized StressGridResult spawnStressGrid(
            ServerLevel level,
            Vec3 position,
            int countX,
            int countY,
            int countZ,
            float spacing,
            float size,
            float mass
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(position, "position");
        if (countX <= 0 || countY <= 0 || countZ <= 0) {
            throw new IllegalArgumentException("Stress grid dimensions must be positive");
        }
        int requested = Math.toIntExact((long) countX * countY * countZ);
        if (requested > MAX_STRESS_GRID_OBJECTS) {
            throw new IllegalArgumentException("Stress grid is limited to " + MAX_STRESS_GRID_OBJECTS + " objects");
        }
        if (spacing <= 0.0F) {
            throw new IllegalArgumentException("Stress grid spacing must be positive");
        }
        if (size <= 0.0F) {
            throw new IllegalArgumentException("Box size must be positive");
        }
        if (mass <= 0.0F) {
            throw new IllegalArgumentException("Box mass must be positive");
        }

        ServerPhysicsScene scene = sceneFor(level);
        int queuedTerrainChunks = queueTerrainAround(level, BlockPos.containing(position), SPAWN_TERRAIN_CHUNK_RADIUS);
        float halfExtent = size * 0.5F;
        PhysicsVector halfExtents = new PhysicsVector(halfExtent, halfExtent, halfExtent);
        List<PhysicsObject> createdObjects = new ArrayList<>(requested);

        double baseX = position.x() - (countX - 1) * spacing * 0.5D;
        double baseY = position.y() + halfExtent + 1.5D;
        double baseZ = position.z() - (countZ - 1) * spacing * 0.5D;
        try {
            for (int y = 0; y < countY; y++) {
                for (int z = 0; z < countZ; z++) {
                    for (int x = 0; x < countX; x++) {
                        PhysicsObject box = scene.createDynamicBox(
                                halfExtent,
                                halfExtent,
                                halfExtent,
                                new PhysicsPose(
                                        new PhysicsVector(
                                                baseX + x * spacing,
                                                baseY + y * spacing,
                                                baseZ + z * spacing
                                        ),
                                        PhysicsQuaternion.IDENTITY
                                ),
                                mass
                        );
                        createdObjects.add(box);
                    }
                }
            }
        } catch (RuntimeException exception) {
            for (PhysicsObject object : createdObjects) {
                object.close();
            }
            throw exception;
        }

        return new StressGridResult(createdObjects.size(), requested, queuedTerrainChunks, halfExtents, spacing, mass);
    }

    public synchronized Optional<VelocityControlResult> setNearestDynamicBoxVelocity(ServerLevel level, Vec3 origin, PhysicsVector velocity, double maxDistance) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(velocity, "velocity");
        if (maxDistance <= 0.0D) {
            throw new IllegalArgumentException("Max distance must be positive");
        }

        SceneState state = states.get(sceneKey(level));
        if (state == null || state.scene().isClosed()) {
            return Optional.empty();
        }

        PhysicsObject nearest = null;
        double nearestDistanceSqr = maxDistance * maxDistance;
        for (PhysicsObjectSnapshot snapshot : state.scene().snapshots()) {
            if (snapshot.type() != PhysicsObjectType.DYNAMIC_BOX || snapshot.closed()) {
                continue;
            }
            PhysicsVector position = snapshot.pose().position();
            double dx = position.x() - origin.x();
            double dy = position.y() - origin.y();
            double dz = position.z() - origin.z();
            double distanceSqr = dx * dx + dy * dy + dz * dz;
            if (distanceSqr <= nearestDistanceSqr) {
                nearestDistanceSqr = distanceSqr;
                nearest = state.scene().object(snapshot.id()).orElse(null);
            }
        }

        if (nearest == null) {
            return Optional.empty();
        }

        PhysicsVector previousVelocity = nearest.linearVelocity();
        nearest.setLinearVelocity(velocity);
        return Optional.of(new VelocityControlResult(
                nearest.id(),
                previousVelocity,
                velocity,
                Math.sqrt(nearestDistanceSqr)
        ));
    }

    public synchronized Optional<RemovedDebugBox> removeDynamicBox(ServerLevel level, PhysicsObjectId objectId) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(objectId, "objectId");

        SceneState state = states.get(sceneKey(level));
        if (state == null || state.scene().isClosed()) {
            return Optional.empty();
        }

        Optional<PhysicsObject> maybeObject = state.scene().object(objectId);
        if (maybeObject.isEmpty() || maybeObject.get().type() != PhysicsObjectType.DYNAMIC_BOX) {
            return Optional.empty();
        }

        PhysicsObject object = maybeObject.get();
        PhysicsObjectSnapshot snapshot = object.snapshot();
        UUID entityId = discardBoundEntity(level, objectId);
        markMechanicsJointsRemovedForBody(objectId);
        forgetMechanicsBody(objectId);
        if (!state.scene().removeObject(objectId)) {
            return Optional.empty();
        }

        return Optional.of(new RemovedDebugBox(
                snapshot.id(),
                entityId,
                snapshot.pose().position(),
                snapshot.linearVelocity()
        ));
    }

    @Override
    public synchronized void close() {
        entityBindings.clear();
        mechanicsBodies.clear();
        mechanicsJoints.clear();
        removedMechanicsJointIds.clear();
        mechanicsPoseCache.clear();
        mechanicsSnapshotCache.clear();
        terrainColliders.clear();
        terrainChunks.clear();
        terrainBuildQueues.clear();
        terrainChunkBuildStates.clear();
        activeTerrainScanCursors.clear();
        terrainColliderSequence = 1L;
        lastTerrainChunkBuildCount = 0;
        lastTerrainColliderBuildCount = 0;
        lastTerrainPartialColliderBuildCount = 0;
        lastTerrainBuildNanos = 0L;
        debugProxyRecreateCount = 0;
        lastDebugProxyRecreateCount = 0;
        debugProxySyncCursor = 0;
        clearLastTickProfiling();
        scenes.close();
        states.clear();
    }

    public synchronized void close(MinecraftServer server) {
        discardBoundEntities(server);
        close();
    }

    private static String sceneKey(ServerLevel level) {
        ResourceKey<Level> dimension = level.dimension();
        return dimension.location().toString();
    }

    private Optional<PhysicsObject> mechanicsObject(ServerLevel level, MechanicsBodyId id) {
        PhysicsObjectId objectId = physicsObjectId(id);
        MechanicsBodyMetadata metadata = mechanicsBodies.get(objectId);
        if (metadata == null || !metadata.levelKey().equals(level.dimension())) {
            return Optional.empty();
        }
        SceneState state = states.get(metadata.sceneKey());
        if (state == null || state.scene().isClosed()) {
            forgetMechanicsBody(objectId);
            return Optional.empty();
        }
        Optional<PhysicsObject> object = state.scene().object(objectId);
        if (object.isEmpty()) {
            forgetMechanicsBody(objectId);
        }
        return object;
    }

    private MechanicsResult<PhysicsObject> mechanicsDynamicObject(ServerLevel level, MechanicsBodyId id) {
        PhysicsObjectId objectId = physicsObjectId(id);
        MechanicsBodyMetadata metadata = mechanicsBodies.get(objectId);
        if (metadata == null) {
            return MechanicsResult.failure(MechanicsResultCode.NOT_FOUND, "Mechanics body does not exist");
        }
        if (!metadata.levelKey().equals(level.dimension())) {
            return MechanicsResult.failure(MechanicsResultCode.WRONG_LEVEL, "Mechanics body belongs to a different level");
        }
        if (metadata.mass() <= 0.0F) {
            return MechanicsResult.failure(MechanicsResultCode.STATIC_BODY, "Mechanics body is not dynamic");
        }
        SceneState state = states.get(metadata.sceneKey());
        if (state == null || state.scene().isClosed()) {
            forgetMechanicsBody(objectId);
            return MechanicsResult.failure(MechanicsResultCode.CLOSED, "Physics scene is closed");
        }
        Optional<PhysicsObject> object = state.scene().object(objectId);
        if (object.isEmpty()) {
            forgetMechanicsBody(objectId);
            return MechanicsResult.failure(MechanicsResultCode.NOT_FOUND, "Mechanics body is missing from the physics scene");
        }
        PhysicsObject physicsObject = object.get();
        if (physicsObject.isClosed()) {
            forgetMechanicsBody(objectId);
            return MechanicsResult.failure(MechanicsResultCode.CLOSED, "Mechanics body is closed");
        }
        return MechanicsResult.ok(physicsObject);
    }

    private void refreshMechanicsStateCache() {
        Map<String, List<PhysicsObjectId>> idsByScene = new LinkedHashMap<>();
        for (Map.Entry<PhysicsObjectId, MechanicsBodyMetadata> entry : List.copyOf(mechanicsBodies.entrySet())) {
            PhysicsObjectId objectId = entry.getKey();
            MechanicsBodyMetadata metadata = entry.getValue();
            idsByScene.computeIfAbsent(metadata.sceneKey(), ignored -> new ArrayList<>()).add(objectId);
        }

        for (Map.Entry<String, List<PhysicsObjectId>> sceneEntry : idsByScene.entrySet()) {
            String sceneKey = sceneEntry.getKey();
            List<PhysicsObjectId> objectIds = sceneEntry.getValue();
            SceneState state = states.get(sceneKey);
            if (state == null || state.scene().isClosed()) {
                for (PhysicsObjectId objectId : objectIds) {
                    forgetMechanicsBody(objectId);
                }
                continue;
            }

            Map<PhysicsObjectId, PhysicsObjectSnapshot> snapshots = state.scene().snapshots(objectIds);
            for (PhysicsObjectId objectId : objectIds) {
                MechanicsBodyMetadata metadata = mechanicsBodies.get(objectId);
                if (metadata == null) {
                    continue;
                }
                PhysicsObjectSnapshot snapshot = snapshots.get(objectId);
                if (snapshot == null || snapshot.closed()) {
                    forgetMechanicsBody(objectId);
                    continue;
                }
                cacheMechanicsSnapshot(snapshot, metadata);
            }
        }
    }

    private MechanicsBodySnapshot cacheMechanicsSnapshot(PhysicsObject object, MechanicsBodyMetadata metadata) {
        return cacheMechanicsSnapshot(object.snapshot(), metadata);
    }

    private MechanicsBodySnapshot cacheMechanicsSnapshot(PhysicsObjectSnapshot snapshot, MechanicsBodyMetadata metadata) {
        MechanicsBodySnapshot mechanicsSnapshot = mechanicsSnapshot(snapshot, metadata);
        mechanicsSnapshotCache.put(snapshot.id(), mechanicsSnapshot);
        mechanicsPoseCache.put(snapshot.id(), mechanicsSnapshot.pose());
        return mechanicsSnapshot;
    }

    private PhysicsPose cacheMechanicsPose(PhysicsObject object) {
        PhysicsPose pose = object.pose();
        mechanicsPoseCache.put(object.id(), pose);
        return pose;
    }

    private void forgetMechanicsBody(PhysicsObjectId objectId) {
        mechanicsBodies.remove(objectId);
        mechanicsPoseCache.remove(objectId);
        mechanicsSnapshotCache.remove(objectId);
        forgetMechanicsJointsForBody(objectId);
    }

    private void forgetMechanicsJoint(PhysicsJointId jointId) {
        mechanicsJoints.remove(jointId);
    }

    private void markMechanicsJointsRemovedForBody(PhysicsObjectId objectId) {
        for (Map.Entry<PhysicsJointId, MechanicsJointMetadata> entry : mechanicsJoints.entrySet()) {
            if (entry.getValue().references(objectId)) {
                removedMechanicsJointIds.add(entry.getKey());
            }
        }
    }

    private void markMechanicsJointsRemovedForScene(String sceneKey) {
        for (Map.Entry<PhysicsJointId, MechanicsJointMetadata> entry : mechanicsJoints.entrySet()) {
            if (entry.getValue().sceneKey().equals(sceneKey)) {
                removedMechanicsJointIds.add(entry.getKey());
            }
        }
    }

    private void forgetMechanicsJointsForBody(PhysicsObjectId objectId) {
        for (Map.Entry<PhysicsJointId, MechanicsJointMetadata> entry : List.copyOf(mechanicsJoints.entrySet())) {
            if (entry.getValue().references(objectId)) {
                forgetMechanicsJoint(entry.getKey());
            }
        }
    }

    private List<RemappedMechanicsJoint> remappedMechanicsJoints(
            ServerPhysicsScene scene,
            PhysicsObjectId previousObjectId,
            PhysicsObjectId replacementObjectId
    ) {
        PhysicsPose previousPose = requirePhysicsObjectPose(scene, previousObjectId, "previousBodyId");
        PhysicsPose replacementPose = requirePhysicsObjectPose(scene, replacementObjectId, "replacementBodyId");
        List<RemappedMechanicsJoint> remapped = new ArrayList<>();
        for (Map.Entry<PhysicsJointId, MechanicsJointMetadata> entry : List.copyOf(mechanicsJoints.entrySet())) {
            MechanicsJointMetadata metadata = entry.getValue();
            if (!metadata.references(previousObjectId)) {
                continue;
            }
            Optional<PhysicsJointSnapshot> existing = scene.joint(entry.getKey());
            if (existing.isEmpty() || existing.get().closed()) {
                forgetMechanicsJoint(entry.getKey());
                continue;
            }

            PhysicsObjectId firstBodyId = metadata.firstBodyId().equals(previousObjectId)
                    ? replacementObjectId
                    : metadata.firstBodyId();
            PhysicsObjectId secondBodyId = metadata.secondBodyId().equals(previousObjectId)
                    ? replacementObjectId
                    : metadata.secondBodyId();
            if (firstBodyId.equals(secondBodyId)) {
                forgetMechanicsJoint(entry.getKey());
                continue;
            }

            remapped.add(new RemappedMechanicsJoint(
                    entry.getKey(),
                    metadata.type(),
                    firstBodyId,
                    secondBodyId,
                    remapJointDefinition(
                            metadata.type(),
                            metadata.definition(),
                            metadata.firstBodyId().equals(previousObjectId),
                            metadata.secondBodyId().equals(previousObjectId),
                            previousPose,
                            replacementPose
                    )
            ));
        }
        return List.copyOf(remapped);
    }

    private static MechanicsJointDefinition remapJointDefinition(
            MechanicsJointType type,
            MechanicsJointDefinition definition,
            boolean replaceFirst,
            boolean replaceSecond,
            PhysicsPose previousPose,
            PhysicsPose replacementPose
    ) {
        return switch (type) {
            case DISTANCE -> MechanicsJointDefinition.distance(
                    replaceFirst ? remapLocalAnchor(previousPose, replacementPose, definition.firstLocalAnchor()) : definition.firstLocalAnchor(),
                    replaceSecond ? remapLocalAnchor(previousPose, replacementPose, definition.secondLocalAnchor()) : definition.secondLocalAnchor(),
                    definition.minDistance(),
                    definition.maxDistance(),
                    definition.stiffness(),
                    definition.damping(),
                    definition.collideConnected()
            );
            case FIXED, REVOLUTE, PRISMATIC -> MechanicsJointDefinition.frames(
                    replaceFirst ? remapLocalFrame(previousPose, replacementPose, definition.firstLocalFrame()) : definition.firstLocalFrame(),
                    replaceSecond ? remapLocalFrame(previousPose, replacementPose, definition.secondLocalFrame()) : definition.secondLocalFrame(),
                    definition.collideConnected()
            );
        };
    }

    private static PhysicsJointSnapshot createMechanicsJointFromDefinition(
            ServerPhysicsScene scene,
            PhysicsJointId id,
            MechanicsJointType type,
            PhysicsObjectId firstBodyId,
            PhysicsObjectId secondBodyId,
            MechanicsJointDefinition definition
    ) {
        return switch (type) {
            case FIXED -> scene.createFixedJointWithLocalFrames(
                    firstBodyId,
                    definition.firstLocalFrame(),
                    secondBodyId,
                    definition.secondLocalFrame(),
                    definition.collideConnected(),
                    id
            );
            case DISTANCE -> scene.createDistanceJointWithLocalAnchors(
                    firstBodyId,
                    definition.firstLocalAnchor(),
                    secondBodyId,
                    definition.secondLocalAnchor(),
                    definition.minDistance(),
                    definition.maxDistance(),
                    definition.stiffness(),
                    definition.damping(),
                    definition.collideConnected(),
                    id
            );
            case REVOLUTE -> scene.createRevoluteJointWithLocalFrames(
                    firstBodyId,
                    definition.firstLocalFrame(),
                    secondBodyId,
                    definition.secondLocalFrame(),
                    definition.collideConnected(),
                    id
            );
            case PRISMATIC -> scene.createPrismaticJointWithLocalFrames(
                    firstBodyId,
                    definition.firstLocalFrame(),
                    secondBodyId,
                    definition.secondLocalFrame(),
                    definition.collideConnected(),
                    id
            );
        };
    }

    private Optional<ResolvedMechanicsJointBodies> resolveMechanicsJointBodies(
            ServerLevel level,
            PhysicsObjectId firstObjectId,
            PhysicsObjectId secondObjectId
    ) {
        if (firstObjectId.equals(secondObjectId)) {
            throw new IllegalArgumentException("Mechanics joints require two different bodies");
        }

        MechanicsBodyMetadata firstMetadata = mechanicsBodies.get(firstObjectId);
        MechanicsBodyMetadata secondMetadata = mechanicsBodies.get(secondObjectId);
        if (firstMetadata == null || secondMetadata == null) {
            return Optional.empty();
        }
        if (!firstMetadata.levelKey().equals(level.dimension()) || !secondMetadata.levelKey().equals(level.dimension())) {
            return Optional.empty();
        }
        if (!firstMetadata.sceneKey().equals(secondMetadata.sceneKey())) {
            throw new IllegalArgumentException("Mechanics joints require bodies in the same physics scene");
        }

        SceneState state = states.get(firstMetadata.sceneKey());
        if (state == null || state.scene().isClosed()) {
            return Optional.empty();
        }
        if (state.scene().object(firstObjectId).isEmpty() || state.scene().object(secondObjectId).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedMechanicsJointBodies(firstMetadata.sceneKey(), firstObjectId, secondObjectId, state));
    }

    private ResolvedMechanicsJointBodies requireMechanicsJointBodies(
            ServerLevel level,
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            String jointTypeName
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(firstBodyId, "firstBodyId");
        Objects.requireNonNull(secondBodyId, "secondBodyId");
        Objects.requireNonNull(jointTypeName, "jointTypeName");

        PhysicsObjectId firstObjectId = physicsObjectId(firstBodyId);
        PhysicsObjectId secondObjectId = physicsObjectId(secondBodyId);
        if (firstObjectId.equals(secondObjectId)) {
            throw new IllegalArgumentException(jointTypeName + " joints require two different mechanics bodies");
        }

        MechanicsBodyMetadata firstMetadata = mechanicsBodies.get(firstObjectId);
        MechanicsBodyMetadata secondMetadata = mechanicsBodies.get(secondObjectId);
        if (firstMetadata == null || secondMetadata == null) {
            throw new IllegalArgumentException("Both mechanics bodies must exist");
        }
        if (!firstMetadata.levelKey().equals(level.dimension()) || !secondMetadata.levelKey().equals(level.dimension())) {
            throw new IllegalArgumentException("Both mechanics bodies must be in this level");
        }
        if (!firstMetadata.sceneKey().equals(secondMetadata.sceneKey())) {
            throw new IllegalArgumentException(jointTypeName + " joints require bodies in the same physics scene");
        }

        SceneState state = states.get(firstMetadata.sceneKey());
        if (state == null || state.scene().isClosed()) {
            throw new IllegalStateException("Physics scene is not available for " + jointTypeName + " joint creation");
        }
        return new ResolvedMechanicsJointBodies(firstMetadata.sceneKey(), firstObjectId, secondObjectId, state);
    }

    private static MechanicsJointDefinition frameDefinitionFromWorldFrame(
            ServerPhysicsScene scene,
            PhysicsObjectId firstObjectId,
            PhysicsObjectId secondObjectId,
            PhysicsPose worldFrame,
            boolean collideConnected
    ) {
        PhysicsPose firstPose = requirePhysicsObjectPose(scene, firstObjectId, "firstBodyId");
        PhysicsPose secondPose = requirePhysicsObjectPose(scene, secondObjectId, "secondBodyId");
        return MechanicsJointDefinition.frames(
                bodyLocalFrame(firstPose, worldFrame),
                bodyLocalFrame(secondPose, worldFrame),
                collideConnected
        );
    }

    private static PhysicsPose requirePhysicsObjectPose(ServerPhysicsScene scene, PhysicsObjectId objectId, String name) {
        return scene.object(objectId)
                .orElseThrow(() -> new IllegalArgumentException(name + " does not exist in this physics scene"))
                .pose();
    }

    private static PhysicsPose remapLocalFrame(PhysicsPose previousBodyPose, PhysicsPose replacementBodyPose, PhysicsPose previousLocalFrame) {
        PhysicsPose worldFrame = localFrameToWorld(previousBodyPose, previousLocalFrame);
        return bodyLocalFrame(replacementBodyPose, worldFrame);
    }

    private static PhysicsVector remapLocalAnchor(PhysicsPose previousBodyPose, PhysicsPose replacementBodyPose, PhysicsVector previousLocalAnchor) {
        return bodyLocalPoint(replacementBodyPose, localPointToWorld(previousBodyPose, previousLocalAnchor));
    }

    private static PhysicsPose localFrameToWorld(PhysicsPose bodyPose, PhysicsPose localFrame) {
        PhysicsQuaternion bodyRotation = normalize(bodyPose.rotation());
        PhysicsQuaternion localRotation = normalize(localFrame.rotation());
        return new PhysicsPose(
                localPointToWorld(bodyPose, localFrame.position()),
                normalize(multiply(bodyRotation, localRotation))
        );
    }

    private static PhysicsVector localPointToWorld(PhysicsPose bodyPose, PhysicsVector localPoint) {
        PhysicsQuaternion bodyRotation = normalize(bodyPose.rotation());
        PhysicsVector rotated = rotate(bodyRotation, localPoint);
        PhysicsVector bodyPosition = bodyPose.position();
        return new PhysicsVector(
                bodyPosition.x() + rotated.x(),
                bodyPosition.y() + rotated.y(),
                bodyPosition.z() + rotated.z()
        );
    }

    private static MechanicsBodySnapshot mechanicsSnapshot(PhysicsObject object, MechanicsBodyMetadata metadata) {
        return mechanicsSnapshot(object.snapshot(), metadata);
    }

    private static MechanicsBodySnapshot mechanicsSnapshot(PhysicsObjectSnapshot snapshot, MechanicsBodyMetadata metadata) {
        return new MechanicsBodySnapshot(
                mechanicsBodyId(snapshot.id()),
                metadata.levelKey(),
                metadata.type(),
                metadata.role(),
                metadata.owner(),
                snapshot.pose(),
                snapshot.linearVelocity(),
                snapshot.angularVelocity(),
                metadata.halfExtents(),
                metadata.mass(),
                snapshot.closed()
        );
    }

    private static MechanicsJointSnapshot mechanicsJointSnapshot(PhysicsJointSnapshot snapshot, MechanicsJointMetadata metadata) {
        return new MechanicsJointSnapshot(
                mechanicsJointId(snapshot.id()),
                metadata.levelKey(),
                metadata.type(),
                mechanicsBodyId(snapshot.firstBodyId()),
                mechanicsBodyId(snapshot.secondBodyId()),
                snapshot.closed()
        );
    }

    private static MechanicsBodyId mechanicsBodyId(PhysicsObjectId id) {
        return new MechanicsBodyId(id.value());
    }

    private static PhysicsObjectId physicsObjectId(MechanicsBodyId id) {
        return new PhysicsObjectId(id.value());
    }

    private static MechanicsJointId mechanicsJointId(PhysicsJointId id) {
        return new MechanicsJointId(id.value());
    }

    private static PhysicsJointId physicsJointId(MechanicsJointId id) {
        return new PhysicsJointId(id.value());
    }

    private static PhysicsPose bodyLocalFrame(PhysicsPose bodyPose, PhysicsPose worldFrame) {
        PhysicsQuaternion bodyRotation = normalize(bodyPose.rotation());
        PhysicsVector bodyPosition = bodyPose.position();
        PhysicsVector worldPosition = worldFrame.position();
        PhysicsVector localPosition = inverseRotate(bodyRotation, new PhysicsVector(
                worldPosition.x() - bodyPosition.x(),
                worldPosition.y() - bodyPosition.y(),
                worldPosition.z() - bodyPosition.z()
        ));
        PhysicsQuaternion localRotation = normalize(multiply(conjugate(bodyRotation), normalize(worldFrame.rotation())));
        return new PhysicsPose(localPosition, localRotation);
    }

    private static PhysicsVector bodyLocalPoint(PhysicsPose bodyPose, PhysicsVector worldPoint) {
        PhysicsQuaternion bodyRotation = normalize(bodyPose.rotation());
        PhysicsVector bodyPosition = bodyPose.position();
        return inverseRotate(bodyRotation, new PhysicsVector(
                worldPoint.x() - bodyPosition.x(),
                worldPoint.y() - bodyPosition.y(),
                worldPoint.z() - bodyPosition.z()
        ));
    }

    private static PhysicsVector inverseRotate(PhysicsQuaternion rotation, PhysicsVector vector) {
        return rotate(conjugate(normalize(rotation)), vector);
    }

    private static PhysicsVector rotate(PhysicsQuaternion rotation, PhysicsVector vector) {
        PhysicsQuaternion q = normalize(rotation);
        double ux = q.x();
        double uy = q.y();
        double uz = q.z();
        double s = q.w();
        double vx = vector.x();
        double vy = vector.y();
        double vz = vector.z();

        double dotUv = ux * vx + uy * vy + uz * vz;
        double dotUu = ux * ux + uy * uy + uz * uz;
        double crossX = uy * vz - uz * vy;
        double crossY = uz * vx - ux * vz;
        double crossZ = ux * vy - uy * vx;
        double scale = s * s - dotUu;

        return new PhysicsVector(
                2.0D * dotUv * ux + scale * vx + 2.0D * s * crossX,
                2.0D * dotUv * uy + scale * vy + 2.0D * s * crossY,
                2.0D * dotUv * uz + scale * vz + 2.0D * s * crossZ
        );
    }

    private static PhysicsQuaternion multiply(PhysicsQuaternion first, PhysicsQuaternion second) {
        return new PhysicsQuaternion(
                first.w() * second.x() + first.x() * second.w() + first.y() * second.z() - first.z() * second.y(),
                first.w() * second.y() - first.x() * second.z() + first.y() * second.w() + first.z() * second.x(),
                first.w() * second.z() + first.x() * second.y() - first.y() * second.x() + first.z() * second.w(),
                first.w() * second.w() - first.x() * second.x() - first.y() * second.y() - first.z() * second.z()
        );
    }

    private static PhysicsQuaternion conjugate(PhysicsQuaternion quaternion) {
        PhysicsQuaternion normalized = normalize(quaternion);
        return new PhysicsQuaternion(-normalized.x(), -normalized.y(), -normalized.z(), normalized.w());
    }

    private static PhysicsQuaternion normalize(PhysicsQuaternion rotation) {
        Objects.requireNonNull(rotation, "rotation");
        double length = Math.sqrt(
                rotation.x() * rotation.x()
                        + rotation.y() * rotation.y()
                        + rotation.z() * rotation.z()
                        + rotation.w() * rotation.w()
        );
        if (length <= 1.0E-12D || Double.isNaN(length)) {
            return PhysicsQuaternion.IDENTITY;
        }
        return new PhysicsQuaternion(
                rotation.x() / length,
                rotation.y() / length,
                rotation.z() / length,
                rotation.w() / length
        );
    }

    private static float positiveFloat(double value, String name) {
        if (value <= 0.0D || value > Float.MAX_VALUE || Double.isNaN(value)) {
            throw new IllegalArgumentException(name + " must be a finite positive float");
        }
        return (float) value;
    }

    private static int debugProxySyncLimit() {
        return runtimeConfig().debugProxyMaxSyncsPerTick();
    }

    private static boolean debugProxySyncTransform() {
        return runtimeConfig().debugProxySyncTransform();
    }

    private static int activeTerrainScanLimit() {
        return runtimeConfig().activeTerrainMaxScansPerTick();
    }

    private static int activeTerrainVerticalMargin() {
        return runtimeConfig().activeTerrainVerticalMargin();
    }

    private static PhysicsRuntimeConfig runtimeConfig() {
        return PlatformServices.services().config();
    }

    private static MechanicsResult<Void> validateMechanicsVector(PhysicsVector vector, String name) {
        Objects.requireNonNull(vector, name);
        if (!Double.isFinite(vector.x()) || !Double.isFinite(vector.y()) || !Double.isFinite(vector.z())) {
            return MechanicsResult.failure(MechanicsResultCode.INVALID_ARGUMENT, name + " must be finite");
        }
        return MechanicsResult.ok();
    }

    private void dispatchMechanicsTick(MinecraftServer server, MechanicsTickPhase phase) {
        List<MechanicsTickListener> listeners = mechanicsTickListeners.get(phase);
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        MechanicsTickContext context = new MechanicsTickContext(server, phase, (float) PHYSICS_TICK_SECONDS);
        for (MechanicsTickListener listener : List.copyOf(listeners)) {
            try {
                listener.onMechanicsTick(context);
            } catch (RuntimeException exception) {
                KineticAssembly.LOGGER.error("Mechanics tick listener failed during {}", phase, exception);
            }
        }
    }

    private void clearLastTickProfiling() {
        lastRuntimeTickNanos = 0L;
        lastQueueActiveNanos = 0L;
        lastTerrainProcessNanos = 0L;
        lastStepPhaseNanos = 0L;
        lastSyncEntitiesNanos = 0L;
        lastSyncObjectLookupNanos = 0L;
        lastSyncEntityLookupNanos = 0L;
        lastSyncRecreateNanos = 0L;
        lastSyncPoseReadNanos = 0L;
        lastSyncApplyNanos = 0L;
        lastActiveSnapshotCount = 0;
        lastActiveDynamicCount = 0;
        lastActiveTerrainQueuedCount = 0;
        lastActiveTerrainSkippedHeightCount = 0;
        lastSyncedEntityCount = 0;
        lastEntityPoseSyncCount = 0;
        lastSyncRemovedBindingCount = 0;
        lastSyncMissingEntityCount = 0;
    }

    private int terrainColliderCount() {
        int count = 0;
        for (Map<Long, TerrainCollider> colliders : terrainColliders.values()) {
            count += colliders.size();
        }
        return count;
    }

    private static String describeGpuDynamicsStatuses(Set<String> statuses) {
        if (statuses.isEmpty()) {
            return "no_scenes";
        }
        return String.join("|", statuses);
    }

    private int terrainQueuedChunkCount() {
        int count = 0;
        for (Set<Long> queue : terrainBuildQueues.values()) {
            count += queue.size();
        }
        return count;
    }

    private int terrainChunkStateCount(TerrainChunkBuildStatus status) {
        int count = 0;
        for (Map<Long, TerrainChunkBuildState> states : terrainChunkBuildStates.values()) {
            for (TerrainChunkBuildState state : states.values()) {
                if (state.status() == status) {
                    count++;
                }
            }
        }
        return count;
    }

    private int terrainChunkCount() {
        int count = 0;
        for (Map<Long, Set<Long>> chunks : terrainChunks.values()) {
            count += chunks.size();
        }
        return count;
    }

    private ActiveObjectTerrainQueueResult queueTerrainAroundActiveObjects(MinecraftServer server) {
        int snapshotCount = 0;
        int dynamicCount = 0;
        int skippedByHeight = 0;
        int queuedTerrainChunks = 0;
        int remainingScans = activeTerrainScanLimit();
        for (ServerLevel level : server.getAllLevels()) {
            SceneState state = states.get(sceneKey(level));
            if (state == null || state.scene().isClosed()) {
                continue;
            }
            String sceneKey = state.scene().sceneKey();
            List<PhysicsObject> objects = state.scene().objectsOfType(PhysicsObjectType.DYNAMIC_BOX);
            snapshotCount += objects.size();
            if (objects.isEmpty()) {
                activeTerrainScanCursors.remove(sceneKey);
                continue;
            }
            if (remainingScans <= 0) {
                continue;
            }

            int cursor = activeTerrainScanCursors.getOrDefault(sceneKey, 0);
            if (cursor >= objects.size()) {
                cursor = 0;
            }
            int scanCount = Math.min(remainingScans, objects.size());
            for (int i = 0; i < scanCount; i++) {
                PhysicsObject object = objects.get((cursor + i) % objects.size());
                if (object.isClosed()) {
                    continue;
                }
                dynamicCount++;

                TerrainBounds bounds = activeTerrainBounds(object);
                if (bounds == null) {
                    PhysicsVector position = object.pose().position();
                    if (!isWithinActiveTerrainHeight(level, position)) {
                        skippedByHeight++;
                        continue;
                    }
                    queuedTerrainChunks += queueTerrainAround(level, BlockPos.containing(position.x(), position.y(), position.z()), ACTIVE_OBJECT_TERRAIN_CHUNK_RADIUS);
                    continue;
                }

                if (!isWithinActiveTerrainHeight(level, bounds)) {
                    skippedByHeight++;
                    continue;
                }
                queuedTerrainChunks += queueTerrainForActiveBounds(level, bounds);
            }
            activeTerrainScanCursors.put(sceneKey, (cursor + scanCount) % objects.size());
            remainingScans -= scanCount;
            if (remainingScans <= 0) {
                break;
            }
        }
        return new ActiveObjectTerrainQueueResult(snapshotCount, dynamicCount, skippedByHeight, queuedTerrainChunks);
    }

    private int queueTerrainAround(ServerLevel level, BlockPos center, int chunkRadius) {
        int centerChunkX = Math.floorDiv(center.getX(), 16);
        int centerChunkZ = Math.floorDiv(center.getZ(), 16);
        int queued = queueLoadedTerrainChunk(level, ChunkPos.asLong(centerChunkX, centerChunkZ), false);

        for (int radius = 1; radius <= chunkRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    queued += queueLoadedTerrainChunk(level, ChunkPos.asLong(centerChunkX + dx, centerChunkZ + dz), false);
                }
            }
        }
        return queued;
    }

    private int queueLoadedTerrainChunk(ServerLevel level, long chunkKey, boolean dirty) {
        if (!isChunkSafeToInspect(level, chunkKey)) {
            return 0;
        }
        return queueTerrainChunk(level, chunkKey, dirty);
    }

    private static boolean isWithinActiveTerrainHeight(ServerLevel level, PhysicsVector position) {
        int margin = activeTerrainVerticalMargin();
        return position.y() >= level.getMinBuildHeight() - margin
                && position.y() <= level.getMaxBuildHeight() + margin;
    }

    private static boolean isWithinActiveTerrainHeight(ServerLevel level, TerrainBounds bounds) {
        int margin = activeTerrainVerticalMargin();
        return bounds.maxY() >= level.getMinBuildHeight() - margin
                && bounds.minY() <= level.getMaxBuildHeight() + margin;
    }

    private int queueTerrainForActiveBounds(ServerLevel level, TerrainBounds bounds) {
        int minChunkX = Mth.floor(bounds.minX()) >> 4;
        int maxChunkX = Mth.floor(bounds.maxX()) >> 4;
        int minChunkZ = Mth.floor(bounds.minZ()) >> 4;
        int maxChunkZ = Mth.floor(bounds.maxZ()) >> 4;
        int queued = 0;
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                queued += queueLoadedTerrainChunk(level, ChunkPos.asLong(x, z), false);
            }
        }
        return queued;
    }

    private TerrainBounds activeTerrainBounds(PhysicsObject object) {
        PhysicsVector halfExtents = activeTerrainHalfExtents(object);
        if (halfExtents == null) {
            return null;
        }

        TerrainBounds bounds = orientedBounds(object.pose(), halfExtents).inflate(1.0D);
        if (!predictActiveTerrainBounds(object)) {
            return bounds;
        }

        PhysicsVector velocity = object.linearVelocity();
        double predictedX = velocity.x() * PHYSICS_TICK_SECONDS;
        double predictedY = velocity.y() * PHYSICS_TICK_SECONDS;
        double predictedZ = velocity.z() * PHYSICS_TICK_SECONDS;
        double predictedDistanceSqr = predictedX * predictedX + predictedY * predictedY + predictedZ * predictedZ;
        if (predictedDistanceSqr <= ACTIVE_TERRAIN_PREDICTION_MOVEMENT_THRESHOLD * ACTIVE_TERRAIN_PREDICTION_MOVEMENT_THRESHOLD) {
            return bounds;
        }

        double clampedY = Mth.clamp(predictedY, -MAX_ACTIVE_TERRAIN_PREDICTION_DISTANCE, MAX_ACTIVE_TERRAIN_PREDICTION_DISTANCE);
        if (Math.abs(clampedY) <= 1.0E-7D) {
            return bounds;
        }
        return bounds.union(bounds.moveY(clampedY));
    }

    private PhysicsVector activeTerrainHalfExtents(PhysicsObject object) {
        MechanicsBodyMetadata metadata = mechanicsBodies.get(object.id());
        if (metadata != null) {
            return metadata.halfExtents();
        }
        EntityBinding binding = entityBindings.get(object.id());
        return binding == null ? null : binding.halfExtents();
    }

    private boolean predictActiveTerrainBounds(PhysicsObject object) {
        MechanicsBodyMetadata metadata = mechanicsBodies.get(object.id());
        return metadata != null && metadata.type() == MechanicsBodyType.DYNAMIC_COMPOUND_BOX;
    }

    private static TerrainBounds orientedBounds(PhysicsPose pose, PhysicsVector halfExtents) {
        PhysicsVector center = pose.position();
        PhysicsQuaternion rotation = normalized(pose.rotation());
        double x = rotation.x();
        double y = rotation.y();
        double z = rotation.z();
        double w = rotation.w();

        double m00 = 1.0D - 2.0D * (y * y + z * z);
        double m01 = 2.0D * (x * y - z * w);
        double m02 = 2.0D * (x * z + y * w);
        double m10 = 2.0D * (x * y + z * w);
        double m11 = 1.0D - 2.0D * (x * x + z * z);
        double m12 = 2.0D * (y * z - x * w);
        double m20 = 2.0D * (x * z - y * w);
        double m21 = 2.0D * (y * z + x * w);
        double m22 = 1.0D - 2.0D * (x * x + y * y);

        double extentX = Math.abs(m00) * halfExtents.x()
                + Math.abs(m01) * halfExtents.y()
                + Math.abs(m02) * halfExtents.z();
        double extentY = Math.abs(m10) * halfExtents.x()
                + Math.abs(m11) * halfExtents.y()
                + Math.abs(m12) * halfExtents.z();
        double extentZ = Math.abs(m20) * halfExtents.x()
                + Math.abs(m21) * halfExtents.y()
                + Math.abs(m22) * halfExtents.z();

        return new TerrainBounds(
                center.x() - extentX,
                center.y() - extentY,
                center.z() - extentZ,
                center.x() + extentX,
                center.y() + extentY,
                center.z() + extentZ
        );
    }

    private static PhysicsQuaternion normalized(PhysicsQuaternion quaternion) {
        double lengthSqr = quaternion.x() * quaternion.x()
                + quaternion.y() * quaternion.y()
                + quaternion.z() * quaternion.z()
                + quaternion.w() * quaternion.w();
        if (lengthSqr <= 1.0E-12D) {
            return PhysicsQuaternion.IDENTITY;
        }
        double inverseLength = 1.0D / Math.sqrt(lengthSqr);
        return new PhysicsQuaternion(
                quaternion.x() * inverseLength,
                quaternion.y() * inverseLength,
                quaternion.z() * inverseLength,
                quaternion.w() * inverseLength
        );
    }

    private int queueTerrainChunk(ServerLevel level, long chunkKey, boolean dirty) {
        String sceneKey = sceneKey(level);
        TerrainChunkBuildState state = terrainChunkBuildStates
                .computeIfAbsent(sceneKey, ignored -> new LinkedHashMap<>())
                .computeIfAbsent(chunkKey, ignored -> new TerrainChunkBuildState());

        if (!dirty && state.status() != TerrainChunkBuildStatus.UNSEEN) {
            return 0;
        }
        if (dirty && state.status() == TerrainChunkBuildStatus.PENDING) {
            return 0;
        }

        state.mark(dirty ? TerrainChunkBuildStatus.DIRTY : TerrainChunkBuildStatus.PENDING);
        boolean added = terrainBuildQueues
                .computeIfAbsent(sceneKey, ignored -> new LinkedHashSet<>())
                .add(chunkKey);
        return added ? 1 : 0;
    }

    private void processTerrainBuildQueue(MinecraftServer server) {
        int chunksBuilt = 0;
        int collidersBuilt = 0;
        int partialCollidersBuilt = 0;
        long buildNanos = 0L;

        for (ServerLevel level : server.getAllLevels()) {
            String sceneKey = sceneKey(level);
            LinkedHashSet<Long> queue = terrainBuildQueues.get(sceneKey);
            SceneState sceneState = states.get(sceneKey);
            if (queue == null || queue.isEmpty() || sceneState == null || sceneState.scene().isClosed()) {
                continue;
            }

            while (chunksBuilt < MAX_TERRAIN_CHUNK_BUILDS_PER_TICK && !queue.isEmpty()) {
                long chunkKey = pollFirst(queue);
                if (!isChunkSafeToInspect(level, chunkKey)) {
                    removeTerrainChunkTracking(sceneKey, chunkKey);
                    continue;
                }

                TerrainChunkBuildResult result = buildTerrainChunk(level, sceneState.scene(), chunkKey);
                TerrainChunkBuildState buildState = terrainChunkBuildStates
                        .computeIfAbsent(sceneKey, ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(chunkKey, ignored -> new TerrainChunkBuildState());
                buildState.markBuilt(result.created(), result.buildNanos());
                chunksBuilt++;
                collidersBuilt += result.created();
                partialCollidersBuilt += result.partialCreated();
                buildNanos += result.buildNanos();
            }

            if (queue.isEmpty()) {
                terrainBuildQueues.remove(sceneKey);
            }
            if (chunksBuilt >= MAX_TERRAIN_CHUNK_BUILDS_PER_TICK) {
                break;
            }
        }

        if (chunksBuilt > 0) {
            lastTerrainChunkBuildCount = chunksBuilt;
            lastTerrainColliderBuildCount = collidersBuilt;
            lastTerrainPartialColliderBuildCount = partialCollidersBuilt;
            lastTerrainBuildNanos = buildNanos;
        }
    }

    private TerrainChunkBuildResult buildTerrainChunk(ServerLevel level, ServerPhysicsScene scene, long chunkKey) {
        long start = System.nanoTime();
        int removed = removeTerrainCollisionForChunk(scene.sceneKey(), chunkKey);
        int created = 0;

        int chunkX = chunkX(chunkKey);
        int chunkZ = chunkZ(chunkKey);
        int minX = chunkX << 4;
        int minY = level.getMinBuildHeight();
        int height = level.getMaxBuildHeight() - minY;
        int minZ = chunkZ << 4;
        boolean[] solid = new boolean[16 * height * 16];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int partialCreated = 0;

        for (int y = 0; y < height; y++) {
            int worldY = minY + y;
            for (int z = 0; z < 16; z++) {
                int worldZ = minZ + z;
                for (int x = 0; x < 16; x++) {
                    int worldX = minX + x;
                    pos.set(worldX, worldY, worldZ);
                    BlockState state = level.getBlockState(pos);
                    if (state.isCollisionShapeFullBlock(level, pos)) {
                        solid[terrainIndex(x, y, z)] = true;
                    } else {
                        partialCreated += createPartialTerrainColliders(level, scene, chunkKey, pos.immutable(), state);
                    }
                }
            }
        }

        created = partialCreated + createBatchedTerrainColliders(scene, chunkKey, minX, minY, minZ, height, solid);
        return new TerrainChunkBuildResult(created, removed, partialCreated, System.nanoTime() - start);
    }

    private int createPartialTerrainColliders(ServerLevel level, ServerPhysicsScene scene, long chunkKey, BlockPos pos, BlockState state) {
        VoxelShape collisionShape = state.getCollisionShape(level, pos);
        if (collisionShape.isEmpty()) {
            return 0;
        }

        int[] created = new int[1];
        collisionShape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
            double width = maxX - minX;
            double height = maxY - minY;
            double depth = maxZ - minZ;
            if (width <= 0.0D || height <= 0.0D || depth <= 0.0D) {
                return;
            }

            created[0] += createTerrainCollider(
                    scene,
                    chunkKey,
                    pos.getX() + minX + width * 0.5D,
                    pos.getY() + minY + height * 0.5D,
                    pos.getZ() + minZ + depth * 0.5D,
                    (float) (width * 0.5D),
                    (float) (height * 0.5D),
                    (float) (depth * 0.5D)
            );
        });
        return created[0];
    }

    private int createBatchedTerrainColliders(ServerPhysicsScene scene, long chunkKey, int minX, int minY, int minZ, int height, boolean[] solid) {
        boolean[] consumed = new boolean[solid.length];
        int created = 0;

        for (int y = 0; y < height; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (!isUnconsumedSolid(solid, consumed, x, y, z)) {
                        continue;
                    }

                    int width = terrainBatchWidth(solid, consumed, x, y, z);
                    int depth = terrainBatchDepth(solid, consumed, x, y, z, width);
                    int boxHeight = terrainBatchHeight(solid, consumed, x, y, z, width, depth, height);
                    markTerrainBatchConsumed(consumed, x, y, z, width, depth, boxHeight);

                    created += createTerrainCollider(
                            scene,
                            chunkKey,
                            minX + x + width * 0.5D,
                            minY + y + boxHeight * 0.5D,
                            minZ + z + depth * 0.5D,
                            width * 0.5F,
                            boxHeight * 0.5F,
                            depth * 0.5F
                    );
                }
            }
        }
        return created;
    }

    private static int terrainBatchWidth(boolean[] solid, boolean[] consumed, int startX, int y, int z) {
        int width = 0;
        while (startX + width < 16 && isUnconsumedSolid(solid, consumed, startX + width, y, z)) {
            width++;
        }
        return width;
    }

    private static int terrainBatchDepth(boolean[] solid, boolean[] consumed, int startX, int y, int startZ, int width) {
        int depth = 1;
        while (startZ + depth < 16) {
            for (int x = startX; x < startX + width; x++) {
                if (!isUnconsumedSolid(solid, consumed, x, y, startZ + depth)) {
                    return depth;
                }
            }
            depth++;
        }
        return depth;
    }

    private static int terrainBatchHeight(boolean[] solid, boolean[] consumed, int startX, int startY, int startZ, int width, int depth, int maxHeight) {
        int height = 1;
        while (startY + height < maxHeight) {
            for (int z = startZ; z < startZ + depth; z++) {
                for (int x = startX; x < startX + width; x++) {
                    if (!isUnconsumedSolid(solid, consumed, x, startY + height, z)) {
                        return height;
                    }
                }
            }
            height++;
        }
        return height;
    }

    private static void markTerrainBatchConsumed(boolean[] consumed, int startX, int startY, int startZ, int width, int depth, int height) {
        for (int y = startY; y < startY + height; y++) {
            for (int z = startZ; z < startZ + depth; z++) {
                for (int x = startX; x < startX + width; x++) {
                    consumed[terrainIndex(x, y, z)] = true;
                }
            }
        }
    }

    private static boolean isUnconsumedSolid(boolean[] solid, boolean[] consumed, int x, int y, int z) {
        int index = terrainIndex(x, y, z);
        return solid[index] && !consumed[index];
    }

    private static int terrainIndex(int x, int y, int z) {
        return ((y * 16) + z) * 16 + x;
    }

    public synchronized int unloadChunkCollision(ServerLevel level, long chunkKey) {
        String sceneKey = sceneKey(level);
        removeTerrainChunkTracking(sceneKey, chunkKey);
        return removeTerrainCollisionForChunk(sceneKey, chunkKey);
    }

    public synchronized void updateTerrainCollisionAt(ServerLevel level, BlockPos pos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        String sceneKey = sceneKey(level);
        long chunkKey = ChunkPos.asLong(pos);
        if (!isKnownTerrainChunk(sceneKey, chunkKey)) {
            return;
        }
        queueTerrainChunk(level, chunkKey, true);
    }

    public synchronized void removeTerrainCollisionAt(ServerLevel level, BlockPos pos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        String sceneKey = sceneKey(level);
        long chunkKey = ChunkPos.asLong(pos);
        if (isKnownTerrainChunk(sceneKey, chunkKey)) {
            queueTerrainChunk(level, chunkKey, true);
        }
    }

    public synchronized int refreshTerrainCollisionAt(ServerLevel level, BlockPos pos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        String sceneKey = sceneKey(level);
        long chunkKey = ChunkPos.asLong(pos);
        int removed = removeTerrainCollisionForChunk(sceneKey, chunkKey);
        queueLoadedTerrainChunk(level, chunkKey, true);
        return removed;
    }

    public synchronized int buildTerrainCollisionAround(ServerLevel level, BlockPos center, int chunkRadius) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(center, "center");
        if (chunkRadius < 0) {
            throw new IllegalArgumentException("chunkRadius must be non-negative");
        }

        ServerPhysicsScene scene = sceneFor(level);
        int centerChunkX = Math.floorDiv(center.getX(), 16);
        int centerChunkZ = Math.floorDiv(center.getZ(), 16);
        TerrainChunkBuildAccumulator accumulator = new TerrainChunkBuildAccumulator();

        accumulator.add(buildLoadedTerrainChunkIfNeeded(level, scene, ChunkPos.asLong(centerChunkX, centerChunkZ)));
        for (int radius = 1; radius <= chunkRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    accumulator.add(buildLoadedTerrainChunkIfNeeded(level, scene, ChunkPos.asLong(centerChunkX + dx, centerChunkZ + dz)));
                }
            }
        }

        if (accumulator.chunksBuilt() > 0) {
            lastTerrainChunkBuildCount = accumulator.chunksBuilt();
            lastTerrainColliderBuildCount = accumulator.collidersBuilt();
            lastTerrainPartialColliderBuildCount = accumulator.partialCollidersBuilt();
            lastTerrainBuildNanos = accumulator.buildNanos();
        }
        return accumulator.chunksBuilt();
    }

    private TerrainChunkBuildResult buildLoadedTerrainChunkIfNeeded(ServerLevel level, ServerPhysicsScene scene, long chunkKey) {
        if (!isChunkSafeToInspect(level, chunkKey)) {
            return null;
        }

        String sceneKey = scene.sceneKey();
        TerrainChunkBuildState existingState = terrainChunkBuildStates
                .computeIfAbsent(sceneKey, ignored -> new LinkedHashMap<>())
                .computeIfAbsent(chunkKey, ignored -> new TerrainChunkBuildState());
        if (existingState.status() == TerrainChunkBuildStatus.BUILT) {
            removeTerrainChunkBuildQueueEntry(sceneKey, chunkKey);
            return null;
        }

        TerrainChunkBuildResult result = buildTerrainChunk(level, scene, chunkKey);
        existingState.markBuilt(result.created(), result.buildNanos());
        removeTerrainChunkBuildQueueEntry(sceneKey, chunkKey);
        return result;
    }

    private int createTerrainCollider(
            ServerPhysicsScene scene,
            long chunkKey,
            double centerX,
            double centerY,
            double centerZ,
            float halfExtentX,
            float halfExtentY,
            float halfExtentZ
    ) {
        String sceneKey = scene.sceneKey();
        long colliderKey = terrainColliderSequence++;
        Map<Long, TerrainCollider> colliders = terrainColliders.computeIfAbsent(sceneKey, ignored -> new LinkedHashMap<>());

        PhysicsObject collider = scene.createStaticBox(
                halfExtentX,
                halfExtentY,
                halfExtentZ,
                new PhysicsPose(
                        new PhysicsVector(centerX, centerY, centerZ),
                        PhysicsQuaternion.IDENTITY
                )
        );
        colliders.put(colliderKey, new TerrainCollider(collider, chunkKey));
        terrainChunks
                .computeIfAbsent(sceneKey, ignored -> new LinkedHashMap<>())
                .computeIfAbsent(chunkKey, ignored -> new LinkedHashSet<>())
                .add(colliderKey);
        return 1;
    }

    private boolean removeTerrainCollider(String sceneKey, long colliderKey) {
        Map<Long, TerrainCollider> colliders = terrainColliders.get(sceneKey);
        if (colliders == null) {
            return false;
        }

        TerrainCollider collider = colliders.remove(colliderKey);
        if (collider == null) {
            return false;
        }

        collider.object().close();
        Map<Long, Set<Long>> chunks = terrainChunks.get(sceneKey);
        if (chunks != null) {
            Set<Long> colliderKeys = chunks.get(collider.chunkKey());
            if (colliderKeys != null) {
                colliderKeys.remove(colliderKey);
                if (colliderKeys.isEmpty()) {
                    chunks.remove(collider.chunkKey());
                }
            }
            if (chunks.isEmpty()) {
                terrainChunks.remove(sceneKey);
            }
        }
        if (colliders.isEmpty()) {
            terrainColliders.remove(sceneKey);
        }
        return true;
    }

    private int removeTerrainCollisionForChunk(String sceneKey, long chunkKey) {
        Map<Long, Set<Long>> chunks = terrainChunks.get(sceneKey);
        if (chunks == null) {
            return 0;
        }
        Set<Long> colliderKeys = chunks.get(chunkKey);
        if (colliderKeys == null || colliderKeys.isEmpty()) {
            return 0;
        }

        int removed = 0;
        for (long colliderKey : List.copyOf(colliderKeys)) {
            if (removeTerrainCollider(sceneKey, colliderKey)) {
                removed++;
            }
        }
        return removed;
    }

    private void removeTerrainChunkTracking(String sceneKey, long chunkKey) {
        LinkedHashSet<Long> queue = terrainBuildQueues.get(sceneKey);
        if (queue != null) {
            queue.remove(chunkKey);
            if (queue.isEmpty()) {
                terrainBuildQueues.remove(sceneKey);
            }
        }

        Map<Long, TerrainChunkBuildState> states = terrainChunkBuildStates.get(sceneKey);
        if (states != null) {
            states.remove(chunkKey);
            if (states.isEmpty()) {
                terrainChunkBuildStates.remove(sceneKey);
            }
        }
    }

    private void removeTerrainChunkBuildQueueEntry(String sceneKey, long chunkKey) {
        LinkedHashSet<Long> queue = terrainBuildQueues.get(sceneKey);
        if (queue == null) {
            return;
        }
        queue.remove(chunkKey);
        if (queue.isEmpty()) {
            terrainBuildQueues.remove(sceneKey);
        }
    }

    private boolean isKnownTerrainChunk(String sceneKey, long chunkKey) {
        Map<Long, TerrainChunkBuildState> states = terrainChunkBuildStates.get(sceneKey);
        return states != null && states.containsKey(chunkKey);
    }

    private boolean isChunkSafeToInspect(ServerLevel level, long chunkKey) {
        int chunkX = chunkX(chunkKey);
        int chunkZ = chunkZ(chunkKey);
        return level.hasChunk(chunkX, chunkZ)
                && level.getChunkSource()
                        .chunkMap
                        .getDistanceManager()
                        .inBlockTickingRange(ChunkPos.asLong(chunkX, chunkZ));
    }

    private static long pollFirst(LinkedHashSet<Long> queue) {
        Iterator<Long> iterator = queue.iterator();
        long value = iterator.next();
        iterator.remove();
        return value;
    }

    private static int chunkX(long chunkKey) {
        return (int) chunkKey;
    }

    private static int chunkZ(long chunkKey) {
        return (int) (chunkKey >> 32);
    }

    private EntitySyncResult syncBoundEntities(MinecraftServer server) {
        int recreated = 0;
        int processedBindings = 0;
        int poseSyncs = 0;
        int removedBindings = 0;
        int missingEntities = 0;
        long objectLookupNanos = 0L;
        long entityLookupNanos = 0L;
        long recreateNanos = 0L;
        long poseReadNanos = 0L;
        long applyNanos = 0L;
        List<EntityBinding> bindings = List.copyOf(entityBindings.values());
        if (bindings.isEmpty()) {
            debugProxySyncCursor = 0;
            lastDebugProxyRecreateCount = 0;
            return EntitySyncResult.EMPTY;
        }

        if (debugProxySyncCursor >= bindings.size()) {
            debugProxySyncCursor = 0;
        }
        int syncLimit = debugProxySyncLimit();
        if (syncLimit <= 0) {
            lastDebugProxyRecreateCount = 0;
            return EntitySyncResult.EMPTY;
        }
        int syncCount = Math.min(syncLimit, bindings.size());
        for (int i = 0; i < syncCount; i++) {
            EntityBinding binding = bindings.get((debugProxySyncCursor + i) % bindings.size());
            processedBindings++;
            SceneState state = states.get(binding.sceneKey());
            ServerLevel level = server.getLevel(binding.levelKey());
            if (state == null || level == null) {
                entityBindings.remove(binding.objectId());
                removedBindings++;
                continue;
            }

            long objectLookupStart = System.nanoTime();
            Optional<PhysicsObject> maybeObject = state.scene().object(binding.objectId());
            objectLookupNanos += System.nanoTime() - objectLookupStart;
            long entityLookupStart = System.nanoTime();
            Entity entity = level.getEntity(binding.entityId());
            entityLookupNanos += System.nanoTime() - entityLookupStart;
            if (maybeObject.isEmpty()) {
                if (entity != null && !entity.isRemoved()) {
                    entity.discard();
                }
                entityBindings.remove(binding.objectId());
                removedBindings++;
                continue;
            }
            if (entity != null && !(entity instanceof Display.BlockDisplay)) {
                entity.discard();
                entity = null;
            }
            if (entity == null || entity.isRemoved()) {
                missingEntities++;
                PhysicsObject object = maybeObject.get();
                long recreateStart = System.nanoTime();
                Display.BlockDisplay replacement = createDebugEntity(level, object, binding.halfExtents(), binding.displayState());
                if (!level.addFreshEntity(replacement)) {
                    recreateNanos += System.nanoTime() - recreateStart;
                    continue;
                }
                recreateNanos += System.nanoTime() - recreateStart;
                entityBindings.put(object.id(), new EntityBinding(binding.sceneKey(), binding.levelKey(), object.id(), replacement.getUUID(), binding.halfExtents(), binding.displayState()));
                entity = replacement;
                recreated++;
            }

            long poseReadStart = System.nanoTime();
            PhysicsPose pose = maybeObject.get().pose();
            poseReadNanos += System.nanoTime() - poseReadStart;
            long applyStart = System.nanoTime();
            boolean poseApplied = syncDebugEntity((Display.BlockDisplay) entity, pose, binding.halfExtents());
            applyNanos += System.nanoTime() - applyStart;
            if (poseApplied) {
                poseSyncs++;
            }
        }
        if (entityBindings.isEmpty()) {
            debugProxySyncCursor = 0;
        } else {
            debugProxySyncCursor = (debugProxySyncCursor + syncCount) % entityBindings.size();
        }
        lastDebugProxyRecreateCount = recreated;
        debugProxyRecreateCount += recreated;
        return new EntitySyncResult(
                processedBindings,
                poseSyncs,
                recreated,
                removedBindings,
                missingEntities,
                objectLookupNanos,
                entityLookupNanos,
                recreateNanos,
                poseReadNanos,
                applyNanos
        );
    }

    private Display.BlockDisplay createDebugEntity(ServerLevel level, PhysicsObject object, PhysicsVector halfExtents) {
        return createDebugEntity(level, object, halfExtents, DEBUG_PROXY_BLOCK);
    }

    private Display.BlockDisplay createDebugEntity(ServerLevel level, PhysicsObject object, PhysicsVector halfExtents, BlockState displayState) {
        Display.BlockDisplay entity = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
        entity.setNoGravity(true);
        entity.setInvulnerable(true);
        entity.setCustomName(Component.literal("PhysX " + object.id().toString().substring(0, 8)));
        entity.setCustomNameVisible(false);
        applyInitialDebugDisplayState(entity, object.pose(), halfExtents, displayState);
        return entity;
    }

    private boolean syncDebugEntity(Display.BlockDisplay entity, PhysicsPose pose, PhysicsVector halfExtents) {
        PhysicsVector position = pose.position();
        boolean syncTransform = debugProxySyncTransform();
        if (!syncTransform && isAtPosition(entity, position)) {
            return false;
        }
        entity.setPos(position.x(), position.y(), position.z());
        if (syncTransform) {
            setDebugDisplayTransformation(entity, debugDisplayTransformation(pose, halfExtents));
        }
        return true;
    }

    private static boolean isAtPosition(Entity entity, PhysicsVector position) {
        double dx = entity.getX() - position.x();
        double dy = entity.getY() - position.y();
        double dz = entity.getZ() - position.z();
        return dx * dx + dy * dy + dz * dz <= 1.0E-6D;
    }

    private void applyInitialDebugDisplayState(Display.BlockDisplay entity, PhysicsPose pose, PhysicsVector halfExtents, BlockState displayState) {
        CompoundTag tag = new CompoundTag();
        PhysicsVector position = pose.position();
        tag.put("Pos", doubleList(position.x(), position.y(), position.z()));
        tag.put("Motion", doubleList(0.0D, 0.0D, 0.0D));
        tag.put("Rotation", floatList(0.0F, 0.0F));
        tag.putBoolean("NoGravity", true);
        tag.putBoolean("Invulnerable", true);
        tag.put("block_state", NbtUtils.writeBlockState(displayState));
        tag.putFloat("width", (float) (halfExtents.x() * 2.0D));
        tag.putFloat("height", (float) (halfExtents.y() * 2.0D));
        tag.putFloat("view_range", 64.0F);
        tag.putFloat("shadow_radius", 0.2F);
        tag.putFloat("shadow_strength", 0.4F);
        tag.putInt("interpolation_duration", 0);
        tag.putInt("teleport_duration", 1);
        encodeDisplayTransformation(pose, halfExtents).ifPresent(transformation -> tag.put("transformation", transformation));
        entity.load(tag);
    }

    private static void setDebugDisplayTransformation(Display.BlockDisplay entity, Transformation transformation) {
        if (DISPLAY_SET_TRANSFORMATION == null) {
            return;
        }
        try {
            DISPLAY_SET_TRANSFORMATION.invoke(entity, transformation);
        } catch (Throwable ignored) {
            // If private display setters are unavailable, position sync still keeps the proxy useful.
        }
    }

    private Optional<net.minecraft.nbt.Tag> encodeDisplayTransformation(PhysicsPose pose, PhysicsVector halfExtents) {
        Transformation transformation = debugDisplayTransformation(pose, halfExtents);
        return Transformation.EXTENDED_CODEC
                .encodeStart(NbtOps.INSTANCE, transformation)
                .resultOrPartial(message -> com.firedoge.kineticassembly.KineticAssembly.LOGGER.warn("Failed to encode debug display transformation: {}", message));
    }

    private static Transformation debugDisplayTransformation(PhysicsPose pose, PhysicsVector halfExtents) {
        Quaternionf rotation = toJomlQuaternion(pose.rotation());
        Vector3f scale = new Vector3f(
                (float) (halfExtents.x() * 2.0D),
                (float) (halfExtents.y() * 2.0D),
                (float) (halfExtents.z() * 2.0D)
        );
        Vector3f localCenter = new Vector3f(
                (float) halfExtents.x(),
                (float) halfExtents.y(),
                (float) halfExtents.z()
        ).rotate(new Quaternionf(rotation));
        Vector3f translation = localCenter.negate();
        return new Transformation(
                translation,
                rotation,
                scale,
                new Quaternionf()
        );
    }

    private static MethodHandle findDisplaySetTransformation() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Display.class, MethodHandles.lookup());
            return lookup.findVirtual(Display.class, "setTransformation", MethodType.methodType(void.class, Transformation.class));
        } catch (ReflectiveOperationException exception) {
            com.firedoge.kineticassembly.KineticAssembly.LOGGER.warn("Display#setTransformation is unavailable; debug proxies will only sync position", exception);
            return null;
        }
    }

    private static Quaternionf toJomlQuaternion(PhysicsQuaternion rotation) {
        return new Quaternionf(
                (float) rotation.x(),
                (float) rotation.y(),
                (float) rotation.z(),
                (float) rotation.w()
        ).normalize();
    }

    private static ListTag doubleList(double first, double second, double third) {
        ListTag list = new ListTag();
        list.addTag(0, DoubleTag.valueOf(first));
        list.addTag(1, DoubleTag.valueOf(second));
        list.addTag(2, DoubleTag.valueOf(third));
        return list;
    }

    private static ListTag floatList(float first, float second) {
        ListTag list = new ListTag();
        list.addTag(0, FloatTag.valueOf(first));
        list.addTag(1, FloatTag.valueOf(second));
        return list;
    }

    private void removeBindingsForScene(ServerLevel level, String sceneKey) {
        for (EntityBinding binding : List.copyOf(entityBindings.values())) {
            if (!binding.sceneKey().equals(sceneKey)) {
                continue;
            }
            Entity entity = level.getEntity(binding.entityId());
            if (entity != null && !entity.isRemoved()) {
                entity.discard();
            }
            entityBindings.remove(binding.objectId());
        }
    }

    private void removeMechanicsBodiesForScene(String sceneKey) {
        for (Map.Entry<PhysicsObjectId, MechanicsBodyMetadata> entry : List.copyOf(mechanicsBodies.entrySet())) {
            if (entry.getValue().sceneKey().equals(sceneKey)) {
                forgetMechanicsBody(entry.getKey());
            }
        }
    }

    private void removeMechanicsJointsForScene(String sceneKey) {
        for (Map.Entry<PhysicsJointId, MechanicsJointMetadata> entry : List.copyOf(mechanicsJoints.entrySet())) {
            if (entry.getValue().sceneKey().equals(sceneKey)) {
                forgetMechanicsJoint(entry.getKey());
            }
        }
    }

    private UUID discardBoundEntity(ServerLevel level, PhysicsObjectId objectId) {
        EntityBinding binding = entityBindings.remove(objectId);
        if (binding == null) {
            return null;
        }
        Entity entity = level.getEntity(binding.entityId());
        if (entity != null && !entity.isRemoved()) {
            entity.discard();
        }
        return binding.entityId();
    }

    private void discardBoundEntities(MinecraftServer server) {
        for (EntityBinding binding : List.copyOf(entityBindings.values())) {
            ServerLevel level = server.getLevel(binding.levelKey());
            if (level == null) {
                continue;
            }
            Entity entity = level.getEntity(binding.entityId());
            if (entity != null && !entity.isRemoved()) {
                entity.discard();
            }
        }
    }

    public record RuntimeStatus(
            int sceneCount,
            int objectCount,
            int dynamicBoxCount,
            int terrainColliderCount,
            int terrainChunkCount,
            int terrainQueuedChunkCount,
            int terrainBuiltChunkCount,
            int terrainDirtyChunkCount,
            int boundEntityCount,
            boolean nativeLinked,
            boolean gpuDynamicsRequested,
            int gpuDynamicsSceneCount,
            String gpuDynamicsStatus,
            long lastStepNanos,
            int lastTerrainChunkBuildCount,
            int lastTerrainColliderBuildCount,
            int lastTerrainPartialColliderBuildCount,
            long lastTerrainBuildNanos,
            int debugProxyRecreateCount,
            int lastDebugProxyRecreateCount,
            long lastRuntimeTickNanos,
            long lastQueueActiveNanos,
            long lastTerrainProcessNanos,
            long lastStepPhaseNanos,
            long lastSyncEntitiesNanos,
            long lastSyncObjectLookupNanos,
            long lastSyncEntityLookupNanos,
            long lastSyncRecreateNanos,
            long lastSyncPoseReadNanos,
            long lastSyncApplyNanos,
            int lastActiveSnapshotCount,
            int lastActiveDynamicCount,
            int lastActiveTerrainQueuedCount,
            int lastActiveTerrainSkippedHeightCount,
            int lastSyncedEntityCount,
            int lastEntityPoseSyncCount,
            int lastSyncRemovedBindingCount,
            int lastSyncMissingEntityCount,
            boolean debugProxySyncTransform,
            int maxEntityPoseSyncsPerTick,
            int activeTerrainMaxScansPerTick
    ) {
        public double lastStepMillis() {
            return lastStepNanos / 1_000_000.0D;
        }

        public double lastTerrainBuildMillis() {
            return lastTerrainBuildNanos / 1_000_000.0D;
        }

        public double lastRuntimeTickMillis() {
            return lastRuntimeTickNanos / 1_000_000.0D;
        }

        public double lastQueueActiveMillis() {
            return lastQueueActiveNanos / 1_000_000.0D;
        }

        public double lastTerrainProcessMillis() {
            return lastTerrainProcessNanos / 1_000_000.0D;
        }

        public double lastStepPhaseMillis() {
            return lastStepPhaseNanos / 1_000_000.0D;
        }

        public double lastSyncEntitiesMillis() {
            return lastSyncEntitiesNanos / 1_000_000.0D;
        }

        public double lastSyncObjectLookupMillis() {
            return lastSyncObjectLookupNanos / 1_000_000.0D;
        }

        public double lastSyncEntityLookupMillis() {
            return lastSyncEntityLookupNanos / 1_000_000.0D;
        }

        public double lastSyncRecreateMillis() {
            return lastSyncRecreateNanos / 1_000_000.0D;
        }

        public double lastSyncPoseReadMillis() {
            return lastSyncPoseReadNanos / 1_000_000.0D;
        }

        public double lastSyncApplyMillis() {
            return lastSyncApplyNanos / 1_000_000.0D;
        }
    }

    public record SpawnedDebugBox(PhysicsObject object, UUID entityId, int terrainChunkQueueCount, PhysicsVector halfExtents, float mass) {
    }

    public record StressGridResult(int created, int requested, int terrainChunkQueueCount, PhysicsVector halfExtents, float spacing, float mass) {
    }

    public record VelocityControlResult(PhysicsObjectId objectId, PhysicsVector previousVelocity, PhysicsVector newVelocity, double distance) {
    }

    public record RemovedDebugBox(PhysicsObjectId objectId, UUID entityId, PhysicsVector lastPosition, PhysicsVector lastVelocity) {
    }

    private record EntityBinding(String sceneKey, ResourceKey<Level> levelKey, PhysicsObjectId objectId, UUID entityId, PhysicsVector halfExtents, BlockState displayState) {
    }

    private record MechanicsBodyMetadata(
            String sceneKey,
            ResourceKey<Level> levelKey,
            MechanicsBodyType type,
            MechanicsBodyRole role,
            MechanicsOwner owner,
            PhysicsVector halfExtents,
            float mass
    ) {
    }

    private record MechanicsJointMetadata(
            String sceneKey,
            ResourceKey<Level> levelKey,
            MechanicsJointType type,
            PhysicsObjectId firstBodyId,
            PhysicsObjectId secondBodyId,
            MechanicsJointDefinition definition
    ) {
        private MechanicsJointMetadata {
            Objects.requireNonNull(sceneKey, "sceneKey");
            Objects.requireNonNull(levelKey, "levelKey");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(firstBodyId, "firstBodyId");
            Objects.requireNonNull(secondBodyId, "secondBodyId");
            Objects.requireNonNull(definition, "definition");
        }

        private boolean references(PhysicsObjectId objectId) {
            return firstBodyId.equals(objectId) || secondBodyId.equals(objectId);
        }
    }

    private record RemappedMechanicsJoint(
            PhysicsJointId id,
            MechanicsJointType type,
            PhysicsObjectId firstBodyId,
            PhysicsObjectId secondBodyId,
            MechanicsJointDefinition definition
    ) {
        private RemappedMechanicsJoint {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(firstBodyId, "firstBodyId");
            Objects.requireNonNull(secondBodyId, "secondBodyId");
            Objects.requireNonNull(definition, "definition");
        }
    }

    private record ResolvedMechanicsJointBodies(
            String sceneKey,
            PhysicsObjectId firstObjectId,
            PhysicsObjectId secondObjectId,
            SceneState state
    ) {
        private ResolvedMechanicsJointBodies {
            Objects.requireNonNull(sceneKey, "sceneKey");
            Objects.requireNonNull(firstObjectId, "firstObjectId");
            Objects.requireNonNull(secondObjectId, "secondObjectId");
            Objects.requireNonNull(state, "state");
        }
    }

    private record TerrainCollider(PhysicsObject object, long chunkKey) {
    }

    private record TerrainChunkBuildResult(int created, int removed, int partialCreated, long buildNanos) {
    }

    private static final class TerrainChunkBuildAccumulator {
        private int chunksBuilt;
        private int collidersBuilt;
        private int partialCollidersBuilt;
        private long buildNanos;

        private void add(TerrainChunkBuildResult result) {
            if (result == null) {
                return;
            }
            chunksBuilt++;
            collidersBuilt += result.created();
            partialCollidersBuilt += result.partialCreated();
            buildNanos += result.buildNanos();
        }

        private int chunksBuilt() {
            return chunksBuilt;
        }

        private int collidersBuilt() {
            return collidersBuilt;
        }

        private int partialCollidersBuilt() {
            return partialCollidersBuilt;
        }

        private long buildNanos() {
            return buildNanos;
        }
    }

    private record ActiveObjectTerrainQueueResult(int snapshotCount, int dynamicCount, int skippedByHeight, int queuedTerrainChunks) {
    }

    private record TerrainBounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        private TerrainBounds inflate(double amount) {
            return new TerrainBounds(
                    minX - amount,
                    minY - amount,
                    minZ - amount,
                    maxX + amount,
                    maxY + amount,
                    maxZ + amount
            );
        }

        private TerrainBounds moveY(double offset) {
            return new TerrainBounds(minX, minY + offset, minZ, maxX, maxY + offset, maxZ);
        }

        private TerrainBounds union(TerrainBounds other) {
            return new TerrainBounds(
                    Math.min(minX, other.minX),
                    Math.min(minY, other.minY),
                    Math.min(minZ, other.minZ),
                    Math.max(maxX, other.maxX),
                    Math.max(maxY, other.maxY),
                    Math.max(maxZ, other.maxZ)
            );
        }
    }

    private record EntitySyncResult(
            int processedBindings,
            int poseSyncs,
            int recreated,
            int removedBindings,
            int missingEntities,
            long objectLookupNanos,
            long entityLookupNanos,
            long recreateNanos,
            long poseReadNanos,
            long applyNanos
    ) {
        private static final EntitySyncResult EMPTY = new EntitySyncResult(0, 0, 0, 0, 0, 0L, 0L, 0L, 0L, 0L);
    }

    private enum TerrainChunkBuildStatus {
        UNSEEN,
        PENDING,
        BUILT,
        DIRTY
    }

    private static final class TerrainChunkBuildState {
        private TerrainChunkBuildStatus status = TerrainChunkBuildStatus.UNSEEN;
        private int colliderCount;
        private long lastBuildNanos;

        private TerrainChunkBuildStatus status() {
            return status;
        }

        private void mark(TerrainChunkBuildStatus status) {
            this.status = status;
        }

        private void markBuilt(int colliderCount, long lastBuildNanos) {
            this.status = TerrainChunkBuildStatus.BUILT;
            this.colliderCount = colliderCount;
            this.lastBuildNanos = lastBuildNanos;
        }
    }

    private static final class SceneState {
        private final ServerPhysicsScene scene;
        private final float fixedTimeStep;
        private final int maxSubSteps;
        private float accumulator;
        private long lastStepNanos;

        private SceneState(ServerPhysicsScene scene, float fixedTimeStep, int maxSubSteps) {
            this.scene = scene;
            this.fixedTimeStep = fixedTimeStep;
            this.maxSubSteps = maxSubSteps;
        }

        private ServerPhysicsScene scene() {
            return scene;
        }

        private long lastStepNanos() {
            return lastStepNanos;
        }

        private void advance(float deltaSeconds) {
            if (scene.isClosed()) {
                return;
            }
            accumulator += Math.max(0.0F, deltaSeconds);
            int steps = 0;
            long start = System.nanoTime();
            while (accumulator >= fixedTimeStep && steps < maxSubSteps) {
                scene.step(fixedTimeStep);
                accumulator -= fixedTimeStep;
                steps++;
            }
            if (steps == maxSubSteps) {
                accumulator = 0.0F;
            }
            lastStepNanos = System.nanoTime() - start;
        }
    }
}
