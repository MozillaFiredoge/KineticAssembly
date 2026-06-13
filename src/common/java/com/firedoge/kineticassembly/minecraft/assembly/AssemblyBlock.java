package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.List;
import java.util.Objects;

import com.firedoge.kineticassembly.api.PhysicsVector;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

public record AssemblyBlock(
        BlockPos sourcePos,
        BlockPos localPos,
        BlockState blockState,
        AABB localCollisionBounds,
        List<AABB> localCollisionBoxes,
        PhysicsVector visualLocalOrigin,
        @Nullable CompoundTag blockEntityTag
) {
    public AssemblyBlock {
        Objects.requireNonNull(sourcePos, "sourcePos");
        Objects.requireNonNull(localPos, "localPos");
        Objects.requireNonNull(blockState, "blockState");
        Objects.requireNonNull(localCollisionBounds, "localCollisionBounds");
        Objects.requireNonNull(localCollisionBoxes, "localCollisionBoxes");
        Objects.requireNonNull(visualLocalOrigin, "visualLocalOrigin");
        localCollisionBoxes = localCollisionBoxes.stream()
                .map(AssemblyBlock::copy)
                .toList();
        blockEntityTag = blockEntityTag == null ? null : blockEntityTag.copy();
    }

    public AssemblyBlock(
            BlockPos sourcePos,
            BlockPos localPos,
            BlockState blockState,
            AABB localCollisionBounds,
            List<AABB> localCollisionBoxes,
            PhysicsVector visualLocalOrigin
    ) {
        this(sourcePos, localPos, blockState, localCollisionBounds, localCollisionBoxes, visualLocalOrigin, null);
    }

    public AssemblyBlock(
            BlockPos sourcePos,
            BlockPos localPos,
            BlockState blockState,
            AABB localCollisionBounds,
            PhysicsVector visualLocalOrigin
    ) {
        this(sourcePos, localPos, blockState, localCollisionBounds, List.of(localCollisionBounds), visualLocalOrigin, null);
    }

    public AssemblyBlock withVisualLocalOrigin(PhysicsVector visualLocalOrigin) {
        return new AssemblyBlock(sourcePos, localPos, blockState, localCollisionBounds, localCollisionBoxes, visualLocalOrigin, blockEntityTag);
    }

    public AssemblyBlock withBlockEntityTag(@Nullable CompoundTag blockEntityTag) {
        return new AssemblyBlock(sourcePos, localPos, blockState, localCollisionBounds, localCollisionBoxes, visualLocalOrigin, blockEntityTag);
    }

    public boolean hasPhysicalCollision() {
        return !localCollisionBoxes.isEmpty();
    }

    public List<AABB> bodyLocalCollisionBoxes() {
        return localCollisionBoxes.stream()
                .map(bounds -> new AABB(
                        visualLocalOrigin.x() + bounds.minX,
                        visualLocalOrigin.y() + bounds.minY,
                        visualLocalOrigin.z() + bounds.minZ,
                        visualLocalOrigin.x() + bounds.maxX,
                        visualLocalOrigin.y() + bounds.maxY,
                        visualLocalOrigin.z() + bounds.maxZ
                ))
                .toList();
    }

    public AABB bodyLocalBounds() {
        return new AABB(
                visualLocalOrigin.x() + localCollisionBounds.minX,
                visualLocalOrigin.y() + localCollisionBounds.minY,
                visualLocalOrigin.z() + localCollisionBounds.minZ,
                visualLocalOrigin.x() + localCollisionBounds.maxX,
                visualLocalOrigin.y() + localCollisionBounds.maxY,
                visualLocalOrigin.z() + localCollisionBounds.maxZ
        );
    }

    private static AABB copy(AABB bounds) {
        Objects.requireNonNull(bounds, "bounds");
        return new AABB(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
    }
}
