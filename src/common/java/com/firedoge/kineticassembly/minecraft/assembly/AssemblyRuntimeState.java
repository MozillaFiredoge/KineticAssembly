package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.mechanics.MechanicsBodyId;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

public final class AssemblyRuntimeState {
    private static final int COLLISION_INDEX_CELL_SIZE = 4;
    private static final long CELL_KEY_MASK = 0x1F_FFFFL;

    private final AssemblyId id;
    private MechanicsBodyId bodyId;
    private long bodyHandle;
    private long observedShapeEpoch = -1L;
    private long shapeEpoch;
    private long poseEpoch;
    private long renderEpoch;
    private PhysicsVector bodyToPlotOrigin = PhysicsVector.ZERO;
    @Nullable
    private AssemblyPoseFrame poseFrame;
    @Nullable
    private AABB localAggregateBounds;
    @Nullable
    private AABB lastWorldBounds;
    @Nullable
    private AABB worldBounds;
    @Nullable
    private AABB sweptBounds;
    private List<AssemblyCollisionBlock> collisionBlocks = List.of();
    private Map<Long, List<AssemblyCollisionBlock>> collisionBlocksByCell = Map.of();

    public AssemblyRuntimeState(PhysicsAssembly assembly) {
        Objects.requireNonNull(assembly, "assembly");
        this.id = assembly.id();
        this.bodyId = assembly.bodyId();
        refreshShape(assembly);
    }

    public AssemblyId id() {
        return id;
    }

    public MechanicsBodyId bodyId() {
        return bodyId;
    }

    public long bodyHandle() {
        return bodyHandle;
    }

    public long shapeEpoch() {
        return shapeEpoch;
    }

    public long poseEpoch() {
        return poseEpoch;
    }

    public long renderEpoch() {
        return renderEpoch;
    }

    public PhysicsVector bodyToPlotOrigin() {
        return bodyToPlotOrigin;
    }

    public Optional<AssemblyPoseFrame> poseFrame() {
        return Optional.ofNullable(poseFrame);
    }

    public Optional<AABB> localAggregateBounds() {
        return Optional.ofNullable(localAggregateBounds).map(AssemblyRuntimeState::copy);
    }

    public Optional<AABB> lastWorldBounds() {
        return Optional.ofNullable(lastWorldBounds).map(AssemblyRuntimeState::copy);
    }

    public Optional<AABB> worldBounds() {
        return Optional.ofNullable(worldBounds).map(AssemblyRuntimeState::copy);
    }

    public Optional<AABB> sweptBounds() {
        return Optional.ofNullable(sweptBounds).map(AssemblyRuntimeState::copy);
    }

    public List<AssemblyCollisionBlock> collisionBlocks() {
        return collisionBlocks;
    }

    public List<AssemblyCollisionBlock> collisionBlocks(AABB bodyLocalQueryBounds) {
        Objects.requireNonNull(bodyLocalQueryBounds, "bodyLocalQueryBounds");
        if (collisionBlocks.isEmpty()) {
            return List.of();
        }
        if (collisionBlocksByCell.isEmpty()) {
            return collisionBlocks;
        }

        boolean profiling = AssemblyProfiler.enabled();
        int[] cellCount = profiling ? new int[1] : null;
        Set<AssemblyCollisionBlock> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
        forEachCell(bodyLocalQueryBounds, (x, y, z) -> {
            if (cellCount != null) {
                cellCount[0]++;
            }
            List<AssemblyCollisionBlock> blocks = collisionBlocksByCell.get(cellKey(x, y, z));
            if (blocks != null) {
                candidates.addAll(blocks);
            }
        });
        if (candidates.isEmpty()) {
            if (profiling) {
                AssemblyProfiler.recordLocalBlockQuery(cellCount[0], 0, 0);
            }
            return List.of();
        }

        List<AssemblyCollisionBlock> result = new ArrayList<>();
        for (AssemblyCollisionBlock block : collisionBlocks) {
            if (candidates.contains(block) && intersectsBroadPhase(block, bodyLocalQueryBounds)) {
                result.add(block);
            }
        }
        if (profiling) {
            AssemblyProfiler.recordLocalBlockQuery(cellCount[0], candidates.size(), result.size());
        }
        return List.copyOf(result);
    }

    public boolean hasCollision() {
        return !collisionBlocks.isEmpty();
    }

    public void refreshIfNeeded(PhysicsAssembly assembly) {
        Objects.requireNonNull(assembly, "assembly");
        requireId(assembly);
        if (!bodyId.equals(assembly.bodyId()) || observedShapeEpoch != assembly.shapeEpoch()) {
            refreshShape(assembly);
        }
    }

    public void refreshShape(PhysicsAssembly assembly) {
        Objects.requireNonNull(assembly, "assembly");
        requireId(assembly);
        bodyId = assembly.bodyId();
        bodyHandle = 0L;
        observedShapeEpoch = assembly.shapeEpoch();
        shapeEpoch++;
        bodyToPlotOrigin = AssemblyCoordinateSpace.bodyToPlotOrigin(assembly);
        localAggregateBounds = computeLocalAggregateBounds(assembly);
        collisionBlocks = computeCollisionBlocks(assembly);
        collisionBlocksByCell = buildCollisionBlockIndex(collisionBlocks);
        if (poseFrame != null) {
            updateWorldBounds(assembly, poseFrame);
        }
    }

    public void updatePoseFrame(PhysicsAssembly assembly, AssemblyPoseFrame frame) {
        Objects.requireNonNull(assembly, "assembly");
        Objects.requireNonNull(frame, "frame");
        requireId(assembly);
        if (!frame.id().equals(id)) {
            throw new IllegalArgumentException("pose frame id does not match runtime state id");
        }
        refreshIfNeeded(assembly);
        poseFrame = frame;
        poseEpoch = frame.epoch();
        updateWorldBounds(assembly, frame);
    }

    private void updateWorldBounds(PhysicsAssembly assembly, AssemblyPoseFrame frame) {
        AABB previous = transformLocalAggregate(frame.previousTransform());
        AABB current = transformLocalAggregate(frame.currentTransform());
        if (current == null) {
            lastWorldBounds = null;
            worldBounds = null;
            sweptBounds = null;
            assembly.applyRuntimeWorldBounds(null, null, null);
            renderEpoch++;
            return;
        }

        lastWorldBounds = previous == null ? copy(current) : previous;
        worldBounds = current;
        sweptBounds = previous == null ? copy(current) : union(previous, current);
        assembly.applyRuntimeWorldBounds(lastWorldBounds, worldBounds, sweptBounds);
        renderEpoch++;
    }

    @Nullable
    private AABB transformLocalAggregate(AssemblyTransform transform) {
        if (localAggregateBounds == null) {
            return null;
        }
        return transform.localAabbToWorldBounds(localAggregateBounds);
    }

    private void requireId(PhysicsAssembly assembly) {
        if (!assembly.id().equals(id)) {
            throw new IllegalArgumentException("Assembly id does not match runtime state: " + assembly.id());
        }
    }

    @Nullable
    private static AABB computeLocalAggregateBounds(PhysicsAssembly assembly) {
        AABB aggregate = null;
        for (AssemblyBlock block : assembly.blocks()) {
            AABB blockBounds = block.bodyLocalBounds();
            aggregate = aggregate == null ? blockBounds : union(aggregate, blockBounds);
        }
        return aggregate;
    }

    private static List<AssemblyCollisionBlock> computeCollisionBlocks(PhysicsAssembly assembly) {
        List<AssemblyCollisionBlock> blocks = new ArrayList<>();
        for (AssemblyBlock block : assembly.blocks()) {
            AssemblyCollisionBlock collisionBlock = AssemblyCollisionBlock.from(block);
            if (collisionBlock.hasCollision()) {
                blocks.add(collisionBlock);
            }
        }
        return List.copyOf(blocks);
    }

    private static Map<Long, List<AssemblyCollisionBlock>> buildCollisionBlockIndex(List<AssemblyCollisionBlock> blocks) {
        if (blocks.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<AssemblyCollisionBlock>> index = new LinkedHashMap<>();
        for (AssemblyCollisionBlock block : blocks) {
            Set<Long> blockCells = new LinkedHashSet<>();
            for (AABB localBox : block.broadPhaseLocalBoxes()) {
                forEachCell(localBox, (x, y, z) -> blockCells.add(cellKey(x, y, z)));
            }
            for (long cell : blockCells) {
                index.computeIfAbsent(cell, ignored -> new ArrayList<>()).add(block);
            }
        }
        if (index.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<AssemblyCollisionBlock>> immutable = new LinkedHashMap<>();
        for (Map.Entry<Long, List<AssemblyCollisionBlock>> entry : index.entrySet()) {
            immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(immutable);
    }

    private static boolean intersectsBroadPhase(AssemblyCollisionBlock block, AABB bodyLocalQueryBounds) {
        for (AABB localBox : block.broadPhaseLocalBoxes()) {
            if (localBox.intersects(bodyLocalQueryBounds)) {
                return true;
            }
        }
        return false;
    }

    private static void forEachCell(AABB bounds, CellConsumer consumer) {
        int minX = cellCoordinate(bounds.minX);
        int minY = cellCoordinate(bounds.minY);
        int minZ = cellCoordinate(bounds.minZ);
        int maxX = cellCoordinate(bounds.maxX);
        int maxY = cellCoordinate(bounds.maxY);
        int maxZ = cellCoordinate(bounds.maxZ);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    consumer.accept(x, y, z);
                }
            }
        }
    }

    private static int cellCoordinate(double coordinate) {
        return Mth.floor(coordinate / COLLISION_INDEX_CELL_SIZE);
    }

    private static long cellKey(int x, int y, int z) {
        return ((long) x & CELL_KEY_MASK) << 42
                | ((long) y & CELL_KEY_MASK) << 21
                | ((long) z & CELL_KEY_MASK);
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

    private static AABB copy(AABB bounds) {
        return new AABB(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
    }

    @FunctionalInterface
    private interface CellConsumer {
        void accept(int x, int y, int z);
    }
}
