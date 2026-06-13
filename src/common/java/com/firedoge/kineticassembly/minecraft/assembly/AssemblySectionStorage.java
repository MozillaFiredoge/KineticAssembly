package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.firedoge.kineticassembly.api.PhysicsVector;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

public final class AssemblySectionStorage {
    public static final int SECTION_SIZE = 16;

    private final BlockPos sourceOrigin;
    private final Map<BlockPos, AssemblyBlock> blocksByLocalPos = new LinkedHashMap<>();
    private final Set<BlockPos> dirtyLocalPositions = new LinkedHashSet<>();

    private AssemblySectionStorage(BlockPos sourceOrigin) {
        this.sourceOrigin = Objects.requireNonNull(sourceOrigin, "sourceOrigin").immutable();
    }

    public static AssemblySectionStorage empty(BlockPos sourceOrigin) {
        return new AssemblySectionStorage(sourceOrigin);
    }

    public static AssemblySectionStorage fromBlocks(BlockPos sourceOrigin, List<AssemblyBlock> blocks) {
        Objects.requireNonNull(blocks, "blocks");
        AssemblySectionStorage storage = new AssemblySectionStorage(sourceOrigin);
        for (AssemblyBlock block : blocks) {
            storage.loadInitial(block);
        }
        return storage;
    }

    public BlockPos sourceOrigin() {
        return sourceOrigin;
    }

    public int blockCount() {
        return blocksByLocalPos.size();
    }

    public boolean isEmpty() {
        return blocksByLocalPos.isEmpty();
    }

    public List<AssemblyBlock> blocks() {
        return List.copyOf(blocksByLocalPos.values());
    }

    public Optional<AssemblyBlock> block(BlockPos localPos) {
        requireLocal(localPos);
        return Optional.ofNullable(blocksByLocalPos.get(localPos));
    }

    public BlockState blockState(BlockPos localPos) {
        requireLocal(localPos);
        AssemblyBlock block = blocksByLocalPos.get(localPos);
        return block == null ? Blocks.AIR.defaultBlockState() : block.blockState();
    }

    public Optional<AABB> collisionBounds(BlockPos localPos) {
        return block(localPos).map(AssemblyBlock::localCollisionBounds);
    }

    public boolean hasBlock(BlockPos localPos) {
        requireLocal(localPos);
        return blocksByLocalPos.containsKey(localPos);
    }

    public BlockPos toSourcePos(BlockPos localPos) {
        requireLocal(localPos);
        return sourceOrigin.offset(localPos.getX(), localPos.getY(), localPos.getZ());
    }

    public void putBlock(AssemblyBlock block) {
        Objects.requireNonNull(block, "block");
        requireLocal(block.localPos());
        BlockPos localPos = block.localPos().immutable();
        blocksByLocalPos.put(localPos, block);
        dirtyLocalPositions.add(localPos);
    }

    public void replaceBlocks(List<AssemblyBlock> blocks) {
        Objects.requireNonNull(blocks, "blocks");
        Map<BlockPos, AssemblyBlock> replacement = new LinkedHashMap<>();
        for (AssemblyBlock block : blocks) {
            Objects.requireNonNull(block, "block");
            requireLocal(block.localPos());
            BlockPos localPos = block.localPos().immutable();
            if (replacement.put(localPos, block) != null) {
                throw new IllegalArgumentException("Duplicate assembly block at local position " + describe(block.localPos()));
            }
        }
        blocksByLocalPos.clear();
        blocksByLocalPos.putAll(replacement);
    }

    public boolean updateBlockEntityTag(BlockPos localPos, @Nullable CompoundTag blockEntityTag) {
        requireLocal(localPos);
        BlockPos immutable = localPos.immutable();
        AssemblyBlock block = blocksByLocalPos.get(immutable);
        if (block == null) {
            return false;
        }
        blocksByLocalPos.put(immutable, block.withBlockEntityTag(blockEntityTag));
        return true;
    }

    public void setBlockState(BlockPos localPos, BlockState blockState, AABB localCollisionBounds) {
        setBlockState(localPos, blockState, localCollisionBounds, PhysicsVector.ZERO);
    }

    public void setBlockState(BlockPos localPos, BlockState blockState, AABB localCollisionBounds, PhysicsVector visualLocalOrigin) {
        Objects.requireNonNull(blockState, "blockState");
        if (blockState.isAir()) {
            removeBlock(localPos);
            return;
        }
        Objects.requireNonNull(localCollisionBounds, "localCollisionBounds");
        Objects.requireNonNull(visualLocalOrigin, "visualLocalOrigin");
        putBlock(new AssemblyBlock(
                toSourcePos(localPos),
                localPos.immutable(),
                blockState,
                localCollisionBounds,
                visualLocalOrigin
        ));
    }

    public Optional<AssemblyBlock> removeBlock(BlockPos localPos) {
        requireLocal(localPos);
        BlockPos immutable = localPos.immutable();
        AssemblyBlock previous = blocksByLocalPos.remove(immutable);
        dirtyLocalPositions.add(immutable);
        return Optional.ofNullable(previous);
    }

    public void markDirty(BlockPos localPos) {
        requireLocal(localPos);
        dirtyLocalPositions.add(localPos.immutable());
    }

    public boolean hasDirtyBlocks() {
        return !dirtyLocalPositions.isEmpty();
    }

    public int dirtyBlockCount() {
        return dirtyLocalPositions.size();
    }

    public List<BlockPos> dirtyLocalPositions() {
        return dirtyLocalPositions(Integer.MAX_VALUE, false);
    }

    public List<BlockPos> drainDirtyLocalPositions(int maxCount) {
        if (maxCount <= 0) {
            return List.of();
        }
        return dirtyLocalPositions(maxCount, true);
    }

    public void clearDirty() {
        dirtyLocalPositions.clear();
    }

    private void loadInitial(AssemblyBlock block) {
        Objects.requireNonNull(block, "block");
        requireLocal(block.localPos());
        BlockPos localPos = block.localPos().immutable();
        if (blocksByLocalPos.containsKey(localPos)) {
            throw new IllegalArgumentException("Duplicate assembly block at local position " + describe(block.localPos()));
        }
        blocksByLocalPos.put(localPos, block);
    }

    private List<BlockPos> dirtyLocalPositions(int maxCount, boolean clear) {
        List<BlockPos> result = new ArrayList<>(Math.min(maxCount, dirtyLocalPositions.size()));
        for (BlockPos dirty : dirtyLocalPositions) {
            if (result.size() >= maxCount) {
                break;
            }
            result.add(dirty);
        }
        if (clear) {
            for (BlockPos dirty : result) {
                dirtyLocalPositions.remove(dirty);
            }
        }
        return List.copyOf(result);
    }

    private static void requireLocal(BlockPos localPos) {
        Objects.requireNonNull(localPos, "localPos");
        if (!isValidLocal(localPos)) {
            throw new IllegalArgumentException("Assembly local position must be non-negative: " + describe(localPos));
        }
    }

    public static boolean isValidLocal(BlockPos localPos) {
        Objects.requireNonNull(localPos, "localPos");
        return localPos.getX() >= 0 && localPos.getY() >= 0 && localPos.getZ() >= 0;
    }

    private static String describe(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
