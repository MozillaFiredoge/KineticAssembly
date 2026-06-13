package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.List;
import java.util.Objects;

import com.firedoge.kineticassembly.api.PhysicsVector;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public record AssemblyCollisionBlock(
        BlockPos localPos,
        BlockState blockState,
        List<AABB> bodyLocalBoxes,
        List<AABB> broadPhaseLocalBoxes,
        boolean dynamicCollisionShape
) {
    public AssemblyCollisionBlock {
        Objects.requireNonNull(localPos, "localPos");
        Objects.requireNonNull(blockState, "blockState");
        bodyLocalBoxes = bodyLocalBoxes.stream()
                .map(AssemblyCollisionBlock::copy)
                .toList();
        broadPhaseLocalBoxes = broadPhaseLocalBoxes.stream()
                .map(AssemblyCollisionBlock::copy)
                .toList();
    }

    public AssemblyCollisionBlock(BlockPos localPos, BlockState blockState, List<AABB> bodyLocalBoxes) {
        this(
                localPos,
                blockState,
                bodyLocalBoxes,
                bodyLocalBoxes,
                requiresDynamicCollisionShape(blockState)
        );
    }

    public AssemblyCollisionBlock(
            BlockPos localPos,
            BlockState blockState,
            List<AABB> bodyLocalBoxes,
            List<AABB> broadPhaseLocalBoxes
    ) {
        this(
                localPos,
                blockState,
                bodyLocalBoxes,
                broadPhaseLocalBoxes,
                requiresDynamicCollisionShape(blockState)
        );
    }

    public static AssemblyCollisionBlock from(AssemblyBlock block) {
        Objects.requireNonNull(block, "block");
        List<AABB> bodyLocalBoxes = block.bodyLocalCollisionBoxes();
        List<AABB> broadPhaseLocalBoxes = bodyLocalBoxes;
        if (requiresDynamicCollisionShape(block.blockState())) {
            broadPhaseLocalBoxes = List.of(bodyLocalBlockBounds(block));
        }
        return new AssemblyCollisionBlock(
                block.localPos(),
                block.blockState(),
                bodyLocalBoxes,
                broadPhaseLocalBoxes
        );
    }

    public boolean hasCollision() {
        return !bodyLocalBoxes.isEmpty() || !broadPhaseLocalBoxes.isEmpty();
    }

    public boolean hasBroadPhaseCollision() {
        return !broadPhaseLocalBoxes.isEmpty();
    }

    private static boolean requiresDynamicCollisionShape(BlockState state) {
        return state.getBlock() instanceof ScaffoldingBlock;
    }

    private static AABB bodyLocalBlockBounds(AssemblyBlock block) {
        PhysicsVector origin = block.visualLocalOrigin();
        return new AABB(
                origin.x(),
                origin.y(),
                origin.z(),
                origin.x() + 1.0D,
                origin.y() + 1.0D,
                origin.z() + 1.0D
        );
    }

    private static AABB copy(AABB bounds) {
        Objects.requireNonNull(bounds, "bounds");
        return new AABB(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
    }
}
