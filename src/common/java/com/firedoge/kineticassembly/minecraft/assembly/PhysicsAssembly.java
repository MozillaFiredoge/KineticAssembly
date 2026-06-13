package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.firedoge.kineticassembly.mechanics.MechanicsBodyId;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

public final class PhysicsAssembly {
    private final AssemblyId id;
    private final ResourceKey<Level> levelKey;
    private final AssemblyPlot plot;
    private MechanicsBodyId bodyId;
    private AssemblyBounds bounds;
    private final AssemblySectionStorage section;
    private final List<VisualBinding> visuals = new ArrayList<>();
    private final Map<BlockPos, BlockEntity> blockEntitiesByLocalPos = new LinkedHashMap<>();
    private boolean debugVisualsEnabled;
    private AssemblyLifecycleState state = AssemblyLifecycleState.CAPTURED;
    @Nullable
    private AABB lastWorldBounds;
    @Nullable
    private AABB worldBounds;
    @Nullable
    private AABB sweptWorldBounds;
    @Nullable
    private GlobalSavedAssemblyPointer lastSerializationPointer;
    private long shapeEpoch;

    public PhysicsAssembly(
            AssemblyId id,
            ResourceKey<Level> levelKey,
            AssemblyPlot plot,
            MechanicsBodyId bodyId,
            AssemblyBounds bounds,
            List<AssemblyBlock> blocks
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.levelKey = Objects.requireNonNull(levelKey, "levelKey");
        this.plot = Objects.requireNonNull(plot, "plot");
        this.bodyId = Objects.requireNonNull(bodyId, "bodyId");
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.section = AssemblySectionStorage.fromBlocks(bounds.sourceOrigin(), blocks);
        if (this.section.isEmpty()) {
            throw new IllegalArgumentException("blocks must not be empty");
        }
    }

    public AssemblyId id() {
        return id;
    }

    public ResourceKey<Level> levelKey() {
        return levelKey;
    }

    public AssemblyPlot plot() {
        return plot;
    }

    public MechanicsBodyId bodyId() {
        return bodyId;
    }

    public void replaceBody(MechanicsBodyId bodyId) {
        this.bodyId = Objects.requireNonNull(bodyId, "bodyId");
    }

    public long shapeEpoch() {
        return shapeEpoch;
    }

    public void bumpShapeEpoch() {
        shapeEpoch++;
    }

    public AssemblyBounds bounds() {
        return bounds;
    }

    public void refreshBoundsFromBlocks() {
        bumpShapeEpoch();
        if (section.isEmpty()) {
            return;
        }
        List<AssemblyBlock> blocks = section.blocks();
        BlockPos first = blocks.getFirst().sourcePos();
        int minX = first.getX();
        int minY = first.getY();
        int minZ = first.getZ();
        int maxX = minX;
        int maxY = minY;
        int maxZ = minZ;
        for (AssemblyBlock block : blocks) {
            BlockPos sourcePos = block.sourcePos();
            minX = Math.min(minX, sourcePos.getX());
            minY = Math.min(minY, sourcePos.getY());
            minZ = Math.min(minZ, sourcePos.getZ());
            maxX = Math.max(maxX, sourcePos.getX());
            maxY = Math.max(maxY, sourcePos.getY());
            maxZ = Math.max(maxZ, sourcePos.getZ());
        }
        bounds = new AssemblyBounds(
                bounds.sourceOrigin(),
                new BlockPos(minX, minY, minZ),
                new BlockPos(maxX, maxY, maxZ)
        );
    }

    public AssemblyLifecycleState state() {
        return state;
    }

    public void activate() {
        if (state != AssemblyLifecycleState.REMOVING) {
            state = AssemblyLifecycleState.ACTIVE;
        }
    }

    public void markDirty() {
        if (state != AssemblyLifecycleState.REMOVING) {
            state = AssemblyLifecycleState.DIRTY;
        }
    }

    public void markRemoving() {
        state = AssemblyLifecycleState.REMOVING;
    }

    public Optional<GlobalSavedAssemblyPointer> lastSerializationPointer() {
        return Optional.ofNullable(lastSerializationPointer);
    }

    public void setLastSerializationPointer(@Nullable GlobalSavedAssemblyPointer lastSerializationPointer) {
        this.lastSerializationPointer = lastSerializationPointer;
    }

    public AssemblySectionStorage section() {
        return section;
    }

    public List<AssemblyBlock> blocks() {
        return section.blocks();
    }

    public Optional<AABB> lastWorldBounds() {
        return Optional.ofNullable(lastWorldBounds).map(PhysicsAssembly::copy);
    }

    public Optional<AABB> worldBounds() {
        return Optional.ofNullable(worldBounds).map(PhysicsAssembly::copy);
    }

    public Optional<AABB> sweptWorldBounds() {
        return Optional.ofNullable(sweptWorldBounds).map(PhysicsAssembly::copy);
    }

    public Optional<AABB> updateWorldBounds(AssemblyPoseFrame frame) {
        Objects.requireNonNull(frame, "frame");
        if (!frame.id().equals(id)) {
            throw new IllegalArgumentException("pose frame id does not match assembly id");
        }

        AABB previous = transformBounds(frame.previousTransform());
        AABB current = transformBounds(frame.currentTransform());
        if (current == null) {
            lastWorldBounds = null;
            worldBounds = null;
            sweptWorldBounds = null;
            return Optional.empty();
        }

        lastWorldBounds = previous == null ? copy(current) : previous;
        worldBounds = current;
        sweptWorldBounds = previous == null ? copy(current) : union(previous, current);
        return Optional.of(copy(worldBounds));
    }

    void applyRuntimeWorldBounds(@Nullable AABB lastWorldBounds, @Nullable AABB worldBounds, @Nullable AABB sweptWorldBounds) {
        this.lastWorldBounds = lastWorldBounds == null ? null : copy(lastWorldBounds);
        this.worldBounds = worldBounds == null ? null : copy(worldBounds);
        this.sweptWorldBounds = sweptWorldBounds == null ? null : copy(sweptWorldBounds);
    }

    @Nullable
    private AABB transformBounds(AssemblyTransform transform) {
        AABB bounds = null;
        for (AssemblyBlock block : blocks()) {
            AABB worldBox = transform.localAabbToWorldBounds(block.bodyLocalBounds());
            bounds = bounds == null ? worldBox : union(bounds, worldBox);
        }
        return bounds;
    }

    public List<VisualBinding> visuals() {
        return visuals;
    }

    public boolean debugVisualsEnabled() {
        return debugVisualsEnabled;
    }

    public void setDebugVisualsEnabled(boolean debugVisualsEnabled) {
        this.debugVisualsEnabled = debugVisualsEnabled;
    }

    public Optional<BlockEntity> blockEntity(BlockPos localPos) {
        return Optional.ofNullable(blockEntitiesByLocalPos.get(localPos));
    }

    public List<BlockEntityAssemblyActor> blockEntityActors() {
        List<BlockEntityAssemblyActor> actors = new ArrayList<>();
        for (BlockEntity blockEntity : blockEntitiesByLocalPos.values()) {
            if (!blockEntity.isRemoved() && blockEntity instanceof BlockEntityAssemblyActor actor) {
                actors.add(actor);
            }
        }
        return List.copyOf(actors);
    }

    public void putBlockEntity(BlockPos localPos, BlockEntity blockEntity) {
        blockEntitiesByLocalPos.put(localPos.immutable(), Objects.requireNonNull(blockEntity, "blockEntity"));
    }

    public void replaceBlockEntities(Map<BlockPos, BlockEntity> blockEntities) {
        Objects.requireNonNull(blockEntities, "blockEntities");
        for (Map.Entry<BlockPos, BlockEntity> entry : blockEntitiesByLocalPos.entrySet()) {
            BlockEntity replacement = blockEntities.get(entry.getKey());
            if (replacement != entry.getValue()) {
                entry.getValue().setRemoved();
            }
        }
        blockEntitiesByLocalPos.clear();
        for (Map.Entry<BlockPos, BlockEntity> entry : blockEntities.entrySet()) {
            blockEntitiesByLocalPos.put(entry.getKey().immutable(), Objects.requireNonNull(entry.getValue(), "blockEntity"));
        }
    }

    public void removeBlockEntity(BlockPos localPos) {
        BlockEntity blockEntity = blockEntitiesByLocalPos.remove(localPos);
        if (blockEntity != null) {
            blockEntity.setRemoved();
        }
    }

    public void clearBlockEntities() {
        for (BlockEntity blockEntity : blockEntitiesByLocalPos.values()) {
            blockEntity.setRemoved();
        }
        blockEntitiesByLocalPos.clear();
    }

    public record VisualBinding(AssemblyBlock block, UUID entityId) {
        public VisualBinding {
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(entityId, "entityId");
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

    private static AABB copy(AABB bounds) {
        return new AABB(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
    }
}
