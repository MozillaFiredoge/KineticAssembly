package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import com.firedoge.kineticassembly.api.PhysicsVector;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class AssemblyPlacementObstruction {
    private static final double QUERY_EPSILON = 1.0E-7D;
    private static final double ENTITY_AABB_SHRINK = 0.75D / 16.0D;

    private AssemblyPlacementObstruction() {
    }

    public static boolean isBlockPlacementUnobstructed(
            Level level,
            BlockPos plotPos,
            BlockState blockState,
            @Nullable Player player
    ) {
        return isBlockPlacementUnobstructed(level, plotPos, null, blockState, player);
    }

    public static boolean isBlockPlacementUnobstructed(
            Level level,
            BlockPos plotPos,
            @Nullable BlockPos fallbackPlotPos,
            BlockState blockState,
            @Nullable Player player
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        Objects.requireNonNull(blockState, "blockState");

        AssemblyContainer container = AssemblyContainers.container(level).orElse(null);
        if (container == null) {
            return true;
        }
        AssemblyPlotProjection projection = container.plotProjection(plotPos).orElse(null);
        if (projection == null && fallbackPlotPos != null) {
            projection = container.plotProjection(fallbackPlotPos).orElse(null);
        }
        if (projection == null) {
            return true;
        }

        VoxelShape shape = blockState.getCollisionShape(
                level,
                plotPos,
                player == null ? CollisionContext.empty() : CollisionContext.of(player)
        );
        return isPlotShapeUnobstructed(level, projection, plotPos, shape, null);
    }

    public static boolean areBodyLocalBoxesUnobstructed(
            Level level,
            AssemblyTransform transform,
            List<AABB> bodyLocalBoxes
    ) {
        return areBodyLocalBoxesUnobstructed(level, transform, bodyLocalBoxes, null);
    }

    public static boolean areBodyLocalBoxesUnobstructed(
            Level level,
            AssemblyTransform transform,
            List<AABB> bodyLocalBoxes,
            @Nullable Entity ignoredEntity
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(transform, "transform");
        Objects.requireNonNull(bodyLocalBoxes, "bodyLocalBoxes");
        if (bodyLocalBoxes.isEmpty()) {
            return true;
        }

        for (AABB bodyLocalBox : bodyLocalBoxes) {
            AABB worldBounds = transform.localAabbToWorldBounds(bodyLocalBox).inflate(QUERY_EPSILON);
            for (Entity entity : level.getEntities(ignoredEntity, worldBounds, candidate ->
                    canBlockPlacement(candidate, ignoredEntity))) {
                if (worldAabbIntersectsLocalBox(shrunkEntityBounds(entity), transform, bodyLocalBox)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isPlotShapeUnobstructed(
            Level level,
            AssemblyPlotProjection projection,
            BlockPos plotPos,
            VoxelShape shape,
            @Nullable Entity ignoredEntity
    ) {
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(shape, "shape");
        if (shape.isEmpty()) {
            return true;
        }

        List<AABB> bodyLocalBoxes = new ArrayList<>();
        for (AABB box : shape.toAabbs()) {
            AABB plotBox = new AABB(
                    plotPos.getX() + box.minX,
                    plotPos.getY() + box.minY,
                    plotPos.getZ() + box.minZ,
                    plotPos.getX() + box.maxX,
                    plotPos.getY() + box.maxY,
                    plotPos.getZ() + box.maxZ
            );
            bodyLocalBoxes.add(plotBoxToBodyLocalBounds(projection, plotBox));
        }
        return areBodyLocalBoxesUnobstructed(
                level,
                projection.poseFrame().currentTransform(),
                bodyLocalBoxes,
                ignoredEntity
        );
    }

    private static boolean canBlockPlacement(Entity candidate, @Nullable Entity ignoredEntity) {
        if (candidate.isRemoved() || !candidate.blocksBuilding) {
            return false;
        }
        return ignoredEntity == null
                || candidate != ignoredEntity && !candidate.isPassengerOfSameVehicle(ignoredEntity);
    }

    private static AABB shrunkEntityBounds(Entity entity) {
        AABB bounds = entity.getBoundingBox();
        double xShrink = shrinkForSize(bounds.getXsize());
        double yShrink = shrinkForSize(bounds.getYsize());
        double zShrink = shrinkForSize(bounds.getZsize());
        return bounds.deflate(xShrink, yShrink, zShrink);
    }

    private static double shrinkForSize(double size) {
        return Math.min(ENTITY_AABB_SHRINK, Math.max(0.0D, size * 0.5D - QUERY_EPSILON));
    }

    private static AABB plotBoxToBodyLocalBounds(AssemblyPlotProjection projection, AABB plotBox) {
        PhysicsVector bodyToPlotOrigin = projection.bodyToPlotOrigin();
        double xOffset = bodyToPlotOrigin.x() - projection.plot().minPlotX();
        double yOffset = bodyToPlotOrigin.y() - projection.plot().minPlotY();
        double zOffset = bodyToPlotOrigin.z() - projection.plot().minPlotZ();
        return new AABB(
                xOffset + plotBox.minX,
                yOffset + plotBox.minY,
                zOffset + plotBox.minZ,
                xOffset + plotBox.maxX,
                yOffset + plotBox.maxY,
                zOffset + plotBox.maxZ
        );
    }

    private static boolean worldAabbIntersectsLocalBox(
            AABB worldBox,
            AssemblyTransform transform,
            AABB localBox
    ) {
        PhysicsVector localCenter = new PhysicsVector(
                (localBox.minX + localBox.maxX) * 0.5D,
                (localBox.minY + localBox.maxY) * 0.5D,
                (localBox.minZ + localBox.maxZ) * 0.5D
        );
        PhysicsVector worldCenter = transform.localToWorld(localCenter);
        double[] aHalf = new double[] {
                worldBox.getXsize() * 0.5D,
                worldBox.getYsize() * 0.5D,
                worldBox.getZsize() * 0.5D
        };
        double[] bHalf = new double[] {
                localBox.getXsize() * 0.5D,
                localBox.getYsize() * 0.5D,
                localBox.getZsize() * 0.5D
        };
        double[] t = new double[] {
                worldCenter.x() - (worldBox.minX + worldBox.maxX) * 0.5D,
                worldCenter.y() - (worldBox.minY + worldBox.maxY) * 0.5D,
                worldCenter.z() - (worldBox.minZ + worldBox.maxZ) * 0.5D
        };
        PhysicsVector[] bAxes = new PhysicsVector[] {
                transform.localDirectionToWorld(new PhysicsVector(1.0D, 0.0D, 0.0D)),
                transform.localDirectionToWorld(new PhysicsVector(0.0D, 1.0D, 0.0D)),
                transform.localDirectionToWorld(new PhysicsVector(0.0D, 0.0D, 1.0D))
        };
        double[][] rotation = new double[3][3];
        double[][] absRotation = new double[3][3];
        for (int axis = 0; axis < 3; axis++) {
            rotation[0][axis] = bAxes[axis].x();
            rotation[1][axis] = bAxes[axis].y();
            rotation[2][axis] = bAxes[axis].z();
            for (int worldAxis = 0; worldAxis < 3; worldAxis++) {
                absRotation[worldAxis][axis] = Math.abs(rotation[worldAxis][axis]) + QUERY_EPSILON;
            }
        }

        for (int worldAxis = 0; worldAxis < 3; worldAxis++) {
            double bProjection = bHalf[0] * absRotation[worldAxis][0]
                    + bHalf[1] * absRotation[worldAxis][1]
                    + bHalf[2] * absRotation[worldAxis][2];
            if (separated(t[worldAxis], aHalf[worldAxis] + bProjection)) {
                return false;
            }
        }

        for (int localAxis = 0; localAxis < 3; localAxis++) {
            double aProjection = aHalf[0] * absRotation[0][localAxis]
                    + aHalf[1] * absRotation[1][localAxis]
                    + aHalf[2] * absRotation[2][localAxis];
            double distance = t[0] * rotation[0][localAxis]
                    + t[1] * rotation[1][localAxis]
                    + t[2] * rotation[2][localAxis];
            if (separated(distance, aProjection + bHalf[localAxis])) {
                return false;
            }
        }

        for (int worldAxis = 0; worldAxis < 3; worldAxis++) {
            int worldNext = (worldAxis + 1) % 3;
            int worldOther = (worldAxis + 2) % 3;
            for (int localAxis = 0; localAxis < 3; localAxis++) {
                int localNext = (localAxis + 1) % 3;
                int localOther = (localAxis + 2) % 3;
                double aProjection = aHalf[worldNext] * absRotation[worldOther][localAxis]
                        + aHalf[worldOther] * absRotation[worldNext][localAxis];
                double bProjection = bHalf[localNext] * absRotation[worldAxis][localOther]
                        + bHalf[localOther] * absRotation[worldAxis][localNext];
                double distance = t[worldOther] * rotation[worldNext][localAxis]
                        - t[worldNext] * rotation[worldOther][localAxis];
                if (separated(distance, aProjection + bProjection)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean separated(double distance, double radius) {
        return Math.abs(distance) >= radius - QUERY_EPSILON;
    }
}
