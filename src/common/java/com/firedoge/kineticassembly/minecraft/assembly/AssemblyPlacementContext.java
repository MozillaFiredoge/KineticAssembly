package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

import javax.annotation.Nullable;

import com.firedoge.kineticassembly.api.PhysicsVector;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class AssemblyPlacementContext {
    private static final double LOOK_EPSILON = 1.0E-12D;
    private static final double HIT_EPSILON = 1.0E-6D;
    private static final double FACE_EPSILON = 1.0E-4D;
    private static final double WORLD_HIT_QUERY_INFLATE = 1.0D;

    private AssemblyPlacementContext() {
    }

    @Nullable
    public static Direction horizontalDirection(Level level, BlockPos clickedPos, @Nullable Player player) {
        Vec3 localLook = localLook(level, clickedPos, player);
        if (localLook == null || horizontalLengthSqr(localLook) <= LOOK_EPSILON) {
            return null;
        }
        return Direction.getNearest(localLook.x, 0.0D, localLook.z);
    }

    @Nullable
    public static Float rotation(Level level, BlockPos clickedPos, @Nullable Player player) {
        Vec3 localLook = localLook(level, clickedPos, player);
        if (localLook == null || horizontalLengthSqr(localLook) <= LOOK_EPSILON) {
            return null;
        }
        return (float) (Math.atan2(-localLook.x, localLook.z) * 180.0D / Math.PI);
    }

    @Nullable
    public static Direction facingAxis(Level level, BlockPos clickedPos, Entity entity, Direction.Axis axis) {
        return facingAxis(level, clickedPos, null, entity, axis);
    }

    @Nullable
    public static Direction facingAxis(
            Level level,
            BlockPos clickedPos,
            @Nullable BlockPos fallbackPos,
            Entity entity,
            Direction.Axis axis
    ) {
        Vec3 localLook = localLook(level, clickedPos, fallbackPos, entity);
        if (localLook == null) {
            return null;
        }
        double value = switch (axis) {
            case X -> localLook.x;
            case Y -> localLook.y;
            case Z -> localLook.z;
        };
        if (Math.abs(value) <= LOOK_EPSILON) {
            return null;
        }
        return switch (axis) {
            case X -> value > 0.0D ? Direction.EAST : Direction.WEST;
            case Y -> value > 0.0D ? Direction.UP : Direction.DOWN;
            case Z -> value > 0.0D ? Direction.SOUTH : Direction.NORTH;
        };
    }

    @Nullable
    public static Direction[] orderedByNearest(Level level, BlockPos clickedPos, Entity entity) {
        return orderedByNearest(level, clickedPos, null, entity);
    }

    @Nullable
    public static Direction[] orderedByNearest(Level level, BlockPos clickedPos, @Nullable BlockPos fallbackPos, Entity entity) {
        Vec3 localLook = localLook(level, clickedPos, fallbackPos, entity);
        if (localLook == null || localLook.lengthSqr() <= LOOK_EPSILON) {
            return null;
        }
        Direction[] directions = Direction.values().clone();
        Arrays.sort(directions, Comparator.comparingDouble(direction -> -score(localLook, direction)));
        return directions;
    }

    public static Optional<HangingEntityPlacement> hangingEntityPlacement(
            Level level,
            BlockPos clickedPos,
            Direction clickedFace,
            Vec3 clickLocation
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(clickedPos, "clickedPos");
        Objects.requireNonNull(clickedFace, "clickedFace");
        Objects.requireNonNull(clickLocation, "clickLocation");

        AssemblyContainer container = AssemblyContainers.container(level).orElse(null);
        if (container == null || container.isEmpty()) {
            return Optional.empty();
        }

        if (container.plotProjection(clickedPos).isPresent()) {
            return Optional.of(new HangingEntityPlacement(clickedPos, clickedFace));
        }

        return worldHangingEntityPlacement(container, clickedFace, clickLocation);
    }

    @Nullable
    private static Vec3 localLook(Level level, BlockPos clickedPos, @Nullable Entity entity) {
        return localLook(level, clickedPos, null, entity);
    }

    @Nullable
    private static Vec3 localLook(Level level, BlockPos clickedPos, @Nullable BlockPos fallbackPos, @Nullable Entity entity) {
        if (entity == null) {
            return null;
        }
        AssemblyContainer container = AssemblyContainers.container(level).orElse(null);
        if (container == null) {
            return null;
        }
        AssemblyPlotProjection projection = container.plotProjection(clickedPos).orElse(null);
        if (projection == null && fallbackPos != null) {
            projection = container.plotProjection(fallbackPos).orElse(null);
        }
        if (projection == null) {
            return null;
        }
        Vec3 localLook = projection.worldDirectionToPlot(entity.getLookAngle());
        return finite(localLook) ? localLook : null;
    }

    private static Optional<HangingEntityPlacement> worldHangingEntityPlacement(
            AssemblyContainer container,
            Direction worldFace,
            Vec3 worldHit
    ) {
        if (!finite(worldHit)) {
            return Optional.empty();
        }

        AABB query = AABB.ofSize(worldHit, 0.0D, 0.0D, 0.0D).inflate(WORLD_HIT_QUERY_INFLATE);
        for (AssemblyCollisionTarget target : container.collisionTargets(query, null)) {
            AssemblyPlotProjection projection = new AssemblyPlotProjection(
                    target.id(),
                    target.plot(),
                    target.bodyToPlotOrigin(),
                    target.poseFrame()
            );
            Vec3 plotHit = projection.worldToPlot(worldHit);
            Vec3 plotFaceVector = projection.worldDirectionToPlot(directionVector(worldFace));
            if (!finite(plotHit) || !finite(plotFaceVector) || plotFaceVector.lengthSqr() <= LOOK_EPSILON) {
                continue;
            }

            Direction plotFace = Direction.getNearest(plotFaceVector.x, plotFaceVector.y, plotFaceVector.z);
            PhysicsVector bodyHit = target.transform().worldToLocal(AssemblyCoordinateSpace.toPhysicsVector(worldHit));
            if (!finite(bodyHit) || !touchesCollisionFace(target, bodyHit, plotFace)) {
                continue;
            }

            BlockPos plotClickedPos = BlockPos.containing(plotHit.subtract(directionVector(plotFace).scale(HIT_EPSILON)));
            if (!projection.containsPlotBlock(plotClickedPos)) {
                BlockPos adjacent = plotClickedPos.relative(plotFace.getOpposite());
                if (!projection.containsPlotBlock(adjacent)) {
                    continue;
                }
                plotClickedPos = adjacent;
            }
            return Optional.of(new HangingEntityPlacement(plotClickedPos, plotFace));
        }
        return Optional.empty();
    }

    private static boolean touchesCollisionFace(
            AssemblyCollisionTarget target,
            PhysicsVector bodyHit,
            Direction face
    ) {
        for (AssemblyCollisionBlock block : target.collisionBlocks()) {
            for (AABB box : block.bodyLocalBoxes()) {
                if (touchesCollisionFace(box, bodyHit, face)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean touchesCollisionFace(AABB box, PhysicsVector bodyHit, Direction face) {
        return switch (face) {
            case EAST -> near(bodyHit.x(), box.maxX) && within(bodyHit.y(), box.minY, box.maxY) && within(bodyHit.z(), box.minZ, box.maxZ);
            case WEST -> near(bodyHit.x(), box.minX) && within(bodyHit.y(), box.minY, box.maxY) && within(bodyHit.z(), box.minZ, box.maxZ);
            case UP -> near(bodyHit.y(), box.maxY) && within(bodyHit.x(), box.minX, box.maxX) && within(bodyHit.z(), box.minZ, box.maxZ);
            case DOWN -> near(bodyHit.y(), box.minY) && within(bodyHit.x(), box.minX, box.maxX) && within(bodyHit.z(), box.minZ, box.maxZ);
            case SOUTH -> near(bodyHit.z(), box.maxZ) && within(bodyHit.x(), box.minX, box.maxX) && within(bodyHit.y(), box.minY, box.maxY);
            case NORTH -> near(bodyHit.z(), box.minZ) && within(bodyHit.x(), box.minX, box.maxX) && within(bodyHit.y(), box.minY, box.maxY);
        };
    }

    private static double horizontalLengthSqr(Vec3 vector) {
        return vector.x * vector.x + vector.z * vector.z;
    }

    private static double score(Vec3 localLook, Direction direction) {
        return localLook.x * direction.getStepX()
                + localLook.y * direction.getStepY()
                + localLook.z * direction.getStepZ();
    }

    private static boolean finite(Vec3 vector) {
        return Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }

    private static boolean finite(PhysicsVector vector) {
        return Double.isFinite(vector.x())
                && Double.isFinite(vector.y())
                && Double.isFinite(vector.z());
    }

    private static boolean near(double value, double expected) {
        return Math.abs(value - expected) <= FACE_EPSILON;
    }

    private static boolean within(double value, double min, double max) {
        return value >= min - FACE_EPSILON && value <= max + FACE_EPSILON;
    }

    private static Vec3 directionVector(Direction direction) {
        return Vec3.atLowerCornerOf(direction.getNormal());
    }

    public record HangingEntityPlacement(BlockPos clickedPos, Direction clickedFace) {
        public HangingEntityPlacement {
            Objects.requireNonNull(clickedPos, "clickedPos");
            Objects.requireNonNull(clickedFace, "clickedFace");
            clickedPos = clickedPos.immutable();
        }

        public BlockPos attachmentPos() {
            return clickedPos.relative(clickedFace);
        }
    }
}
