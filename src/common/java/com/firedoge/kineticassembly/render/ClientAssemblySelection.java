package com.firedoge.kineticassembly.render;

import java.util.Objects;
import java.util.Optional;

import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.minecraft.assembly.ClientAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.ClientTrackedAssembly;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyClientMetadata;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyId;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyTransform;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class ClientAssemblySelection {
    private static Optional<Result> lastResult = Optional.empty();

    private ClientAssemblySelection() {
    }

    public static Optional<Result> lastResult() {
        return lastResult;
    }

    public static Optional<Result> update(Minecraft minecraft, ClientAssemblyContainer container) {
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(container, "container");
        if (minecraft.player == null) {
            lastResult = Optional.empty();
            return lastResult;
        }

        Vec3 origin = minecraft.player.getEyePosition();
        Vec3 look = minecraft.player.getLookAngle();
        double maxDistance = Math.max(1.0D, minecraft.player.blockInteractionRange());
        Vec3 end = origin.add(look.scale(maxDistance));
        Result best = null;

        for (ClientTrackedAssembly assembly : container.trackedAssemblies()) {
            if (!assembly.finalized()) {
                continue;
            }
            if (!rayMayHit(assembly, origin, end)) {
                continue;
            }
            Result result = pickAssembly(assembly, origin, end, maxDistance).orElse(null);
            if (result == null) {
                continue;
            }
            if (best == null || result.distance() < best.distance()) {
                best = result;
            }
        }

        lastResult = Optional.ofNullable(best);
        return lastResult;
    }

    private static Optional<Result> pickAssembly(
            ClientTrackedAssembly assembly,
            Vec3 worldStart,
            Vec3 worldEnd,
            double maxDistance
    ) {
        AssemblyTransform transform = AssemblyTransform.from(assembly.pose());
        PhysicsVector localStartVector = transform.worldToLocal(toPhysics(worldStart));
        PhysicsVector localEndVector = transform.worldToLocal(toPhysics(worldEnd));
        AssemblyClientMetadata metadata = assembly.metadata();
        Vec3 plotStart = bodyLocalToPlotSpace(localStartVector, metadata);
        Vec3 plotEnd = bodyLocalToPlotSpace(localEndVector, metadata);
        if (plotStart.distanceToSqr(plotEnd) <= 1.0E-12D) {
            return Optional.empty();
        }

        return pickAlongRay(assembly, plotStart, plotEnd, maxDistance);
    }

    private static Optional<Result> pickAlongRay(
            ClientTrackedAssembly assembly,
            Vec3 plotStart,
            Vec3 plotEnd,
            double maxDistance
    ) {
        Result best = null;
        Vec3 delta = plotEnd.subtract(plotStart);
        int x = floor(plotStart.x);
        int y = floor(plotStart.y);
        int z = floor(plotStart.z);
        int endX = floor(plotEnd.x);
        int endY = floor(plotEnd.y);
        int endZ = floor(plotEnd.z);
        int stepX = sign(delta.x);
        int stepY = sign(delta.y);
        int stepZ = sign(delta.z);
        double tMaxX = firstBoundaryT(plotStart.x, delta.x, stepX);
        double tMaxY = firstBoundaryT(plotStart.y, delta.y, stepY);
        double tMaxZ = firstBoundaryT(plotStart.z, delta.z, stepZ);
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / delta.x);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / delta.y);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / delta.z);
        int maxSteps = Math.max(1, (int) Math.ceil(plotStart.distanceTo(plotEnd) * 3.0D) + 6);
        BlockPos.MutableBlockPos plotPos = new BlockPos.MutableBlockPos();

        for (int step = 0; step <= maxSteps; step++) {
            plotPos.set(x, y, z);
            if (assembly.plot().containsPlotBlockPos(plotPos)) {
                Result result = pickBlock(assembly, plotPos.immutable(), plotStart, plotEnd, maxDistance).orElse(null);
                if (result != null && (best == null || result.distance() < best.distance())) {
                    best = result;
                }
            }

            if (x == endX && y == endY && z == endZ) {
                break;
            }
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
        }
        return Optional.ofNullable(best);
    }

    private static Optional<Result> pickBlock(
            ClientTrackedAssembly assembly,
            BlockPos plotPos,
            Vec3 plotStart,
            Vec3 plotEnd,
            double maxDistance
    ) {
        BlockState state = assembly.levelView().getBlockState(plotPos);
        if (state.isAir()) {
            return Optional.empty();
        }
        VoxelShape shape = state.getShape(assembly.levelView(), plotPos);
        if (shape.isEmpty()) {
            return Optional.empty();
        }
        BlockHitResult hit = shape.clip(plotStart, plotEnd, plotPos);
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            return Optional.empty();
        }
        double distance = hit.getLocation().distanceTo(plotStart);
        if (distance > maxDistance + 1.0E-6D) {
            return Optional.empty();
        }
        return Optional.of(new Result(assembly.id(), plotPos, state, shape, hit, distance));
    }

    private static boolean rayMayHit(ClientTrackedAssembly assembly, Vec3 start, Vec3 end) {
        AABB bounds = assembly.worldBounds().orElse(null);
        return bounds == null || segmentIntersects(bounds.inflate(1.0E-6D), start, end);
    }

    private static boolean segmentIntersects(AABB bounds, Vec3 start, Vec3 end) {
        Vec3 delta = end.subtract(start);
        double minT = 0.0D;
        double maxT = 1.0D;
        if (Math.abs(delta.x) <= 1.0E-12D) {
            if (start.x < bounds.minX || start.x > bounds.maxX) {
                return false;
            }
        } else {
            double first = (bounds.minX - start.x) / delta.x;
            double second = (bounds.maxX - start.x) / delta.x;
            if (first > second) {
                double swap = first;
                first = second;
                second = swap;
            }
            minT = Math.max(minT, first);
            maxT = Math.min(maxT, second);
            if (minT > maxT) {
                return false;
            }
        }

        if (Math.abs(delta.y) <= 1.0E-12D) {
            if (start.y < bounds.minY || start.y > bounds.maxY) {
                return false;
            }
        } else {
            double first = (bounds.minY - start.y) / delta.y;
            double second = (bounds.maxY - start.y) / delta.y;
            if (first > second) {
                double swap = first;
                first = second;
                second = swap;
            }
            minT = Math.max(minT, first);
            maxT = Math.min(maxT, second);
            if (minT > maxT) {
                return false;
            }
        }

        if (Math.abs(delta.z) <= 1.0E-12D) {
            return start.z >= bounds.minZ && start.z <= bounds.maxZ;
        }
        double first = (bounds.minZ - start.z) / delta.z;
        double second = (bounds.maxZ - start.z) / delta.z;
        if (first > second) {
            double swap = first;
            first = second;
            second = swap;
        }
        minT = Math.max(minT, first);
        maxT = Math.min(maxT, second);
        return minT <= maxT;
    }

    private static int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    private static int sign(double value) {
        if (value > 0.0D) {
            return 1;
        }
        if (value < 0.0D) {
            return -1;
        }
        return 0;
    }

    private static double firstBoundaryT(double start, double delta, int step) {
        if (step == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double boundary = step > 0 ? Math.floor(start) + 1.0D : Math.floor(start);
        return (boundary - start) / delta;
    }

    private static Vec3 bodyLocalToPlotSpace(PhysicsVector bodyLocal, AssemblyClientMetadata metadata) {
        PhysicsVector bodyToPlotOrigin = metadata.bodyToPlotOrigin();
        return new Vec3(
                bodyLocal.x() - bodyToPlotOrigin.x() + metadata.plot().minPlotX(),
                bodyLocal.y() - bodyToPlotOrigin.y() + metadata.plot().minPlotY(),
                bodyLocal.z() - bodyToPlotOrigin.z() + metadata.plot().minPlotZ()
        );
    }

    public static Vec3 plotCenterToBodyLocal(BlockPos plotPos, AssemblyClientMetadata metadata) {
        Objects.requireNonNull(plotPos, "plotPos");
        Objects.requireNonNull(metadata, "metadata");
        PhysicsVector bodyToPlotOrigin = metadata.bodyToPlotOrigin();
        return new Vec3(
                bodyToPlotOrigin.x() + (plotPos.getX() - metadata.plot().minPlotX()) + 0.5D,
                bodyToPlotOrigin.y() + (plotPos.getY() - metadata.plot().minPlotY()) + 0.5D,
                bodyToPlotOrigin.z() + (plotPos.getZ() - metadata.plot().minPlotZ()) + 0.5D
        );
    }

    public static Vec3 plotCenterToWorld(BlockPos plotPos, ClientTrackedAssembly assembly) {
        Objects.requireNonNull(assembly, "assembly");
        Vec3 bodyLocal = plotToBodyLocal(Vec3.atCenterOf(plotPos), assembly.metadata());
        PhysicsVector world = AssemblyTransform.from(assembly.pose()).localToWorld(toPhysics(bodyLocal));
        return new Vec3(world.x(), world.y(), world.z());
    }

    public static Vec3 plotToWorld(Vec3 plotPosition, ClientTrackedAssembly assembly) {
        Objects.requireNonNull(plotPosition, "plotPosition");
        Objects.requireNonNull(assembly, "assembly");
        Vec3 bodyLocal = plotToBodyLocal(plotPosition, assembly.metadata());
        PhysicsVector world = AssemblyTransform.from(assembly.pose()).localToWorld(toPhysics(bodyLocal));
        return new Vec3(world.x(), world.y(), world.z());
    }

    public static Vec3 plotToWorld(Vec3 plotPosition, AssemblyClientMetadata metadata) {
        Objects.requireNonNull(plotPosition, "plotPosition");
        Objects.requireNonNull(metadata, "metadata");
        Vec3 bodyLocal = plotToBodyLocal(plotPosition, metadata);
        PhysicsVector world = AssemblyTransform.from(metadata.pose()).localToWorld(toPhysics(bodyLocal));
        return new Vec3(world.x(), world.y(), world.z());
    }

    public static Vec3 plotDirectionToWorld(Vec3 plotDirection, ClientTrackedAssembly assembly) {
        Objects.requireNonNull(plotDirection, "plotDirection");
        Objects.requireNonNull(assembly, "assembly");
        PhysicsVector world = AssemblyTransform.from(assembly.pose()).localDirectionToWorld(toPhysics(plotDirection));
        return new Vec3(world.x(), world.y(), world.z());
    }

    public static Vec3 plotDirectionToWorld(Vec3 plotDirection, AssemblyClientMetadata metadata) {
        Objects.requireNonNull(plotDirection, "plotDirection");
        Objects.requireNonNull(metadata, "metadata");
        PhysicsVector world = AssemblyTransform.from(metadata.pose()).localDirectionToWorld(toPhysics(plotDirection));
        return new Vec3(world.x(), world.y(), world.z());
    }

    private static Vec3 plotToBodyLocal(Vec3 plotPosition, AssemblyClientMetadata metadata) {
        PhysicsVector bodyToPlotOrigin = metadata.bodyToPlotOrigin();
        return new Vec3(
                bodyToPlotOrigin.x() + plotPosition.x() - metadata.plot().minPlotX(),
                bodyToPlotOrigin.y() + plotPosition.y() - metadata.plot().minPlotY(),
                bodyToPlotOrigin.z() + plotPosition.z() - metadata.plot().minPlotZ()
        );
    }

    private static PhysicsVector toPhysics(Vec3 vec) {
        return new PhysicsVector(vec.x, vec.y, vec.z);
    }

    public record Result(
            AssemblyId id,
            BlockPos plotPos,
            BlockState blockState,
            VoxelShape shape,
            BlockHitResult hit,
            double distance
    ) {
        public Result {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(plotPos, "plotPos");
            Objects.requireNonNull(blockState, "blockState");
            Objects.requireNonNull(shape, "shape");
            Objects.requireNonNull(hit, "hit");
            if (distance < 0.0D || Double.isNaN(distance)) {
                throw new IllegalArgumentException("distance must not be negative or NaN");
            }
        }
    }
}
