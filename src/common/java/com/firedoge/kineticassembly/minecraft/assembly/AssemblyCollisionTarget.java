package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.List;
import java.util.Objects;

import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsVector;

import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;

public record AssemblyCollisionTarget(
        AssemblyPoseFrame poseFrame,
        List<AssemblyCollisionBlock> collisionBlocks,
        BlockGetter collisionLevel,
        AssemblyPlot plot,
        PhysicsVector bodyToPlotOrigin,
        List<AABB> bodyLocalBoxes
) {
    public AssemblyCollisionTarget(
            AssemblyPoseFrame poseFrame,
            List<AssemblyCollisionBlock> collisionBlocks,
            BlockGetter collisionLevel,
            AssemblyPlot plot,
            PhysicsVector bodyToPlotOrigin
    ) {
        this(poseFrame, collisionBlocks, collisionLevel, plot, bodyToPlotOrigin, flatten(collisionBlocks));
    }

    public AssemblyCollisionTarget {
        Objects.requireNonNull(poseFrame, "poseFrame");
        Objects.requireNonNull(collisionLevel, "collisionLevel");
        Objects.requireNonNull(plot, "plot");
        Objects.requireNonNull(bodyToPlotOrigin, "bodyToPlotOrigin");
        collisionBlocks = List.copyOf(collisionBlocks);
        bodyLocalBoxes = List.copyOf(bodyLocalBoxes);
    }

    public AssemblyId id() {
        return poseFrame.id();
    }

    public long epoch() {
        return poseFrame.epoch();
    }

    public PhysicsPose pose() {
        return poseFrame.currentPose();
    }

    public AssemblyTransform transform() {
        return poseFrame.currentTransform();
    }

    private static List<AABB> flatten(List<AssemblyCollisionBlock> collisionBlocks) {
        Objects.requireNonNull(collisionBlocks, "collisionBlocks");
        return collisionBlocks.stream()
                .flatMap(block -> block.broadPhaseLocalBoxes().stream())
                .toList();
    }
}
