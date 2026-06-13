package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.Objects;

import javax.annotation.Nullable;

import com.firedoge.kineticassembly.api.PhysicsVector;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class AssemblyEntityPositioning {
    private AssemblyEntityPositioning() {
    }

    @Nullable
    public static AssemblyPlotProjection containingProjection(Entity entity) {
        if (!AssemblyEntityKicking.shouldKick(entity)) {
            AssemblyPlotProjection retainedProjection = AssemblyEntityBridge
                    .plotProjectionForRetainedEntity(entity.level(), entity)
                    .orElse(null);
            if (retainedProjection != null) {
                return retainedProjection;
            }
        }
        return containingProjection(entity.level(), entity.position());
    }

    @Nullable
    public static AssemblyPlotProjection containingProjection(Level level, Vec3 plotPosition) {
        return AssemblyContainers.container(level)
                .flatMap(container -> container.plotProjection(BlockPos.containing(plotPosition)))
                .orElse(null);
    }

    public static Vec3 projectOutOfAssembly(Level level, Vec3 position) {
        AssemblyPlotProjection projection = containingProjection(level, position);
        if (projection == null) {
            return position;
        }
        Vec3 projected = projection.plotToWorld(position);
        return finite(projected) ? projected : position;
    }

    public static Vec3 kickRidingEntity(Entity entity, AssemblyPlotProjection projection) {
        if (!AssemblyEntityKicking.shouldKick(entity)) {
            return entity.position();
        }
        return kickRidingEntity(entity, entity.position(), projection);
    }

    public static Vec3 kickRidingEntity(Entity entity, Vec3 position, AssemblyPlotProjection projection) {
        Vec3 eyeOffset = entity.getEyePosition().subtract(entity.position());
        Vec3 projectedEye = projection.plotToWorld(position.add(eyeOffset));
        if (!finite(projectedEye)) {
            return position;
        }
        return projectedEye.subtract(eyeOffset);
    }

    @Nullable
    public static Vec3 previousTrackingFramePosition(Entity entity) {
        if (!(entity instanceof AssemblyEntityCollisionAccess access)) {
            return null;
        }
        AssemblyId trackingId = access.kinetic_assembly$trackingAssemblyId();
        if (trackingId == null) {
            return null;
        }
        AssemblyPlotProjection projection = AssemblyContainers.container(entity.level())
                .flatMap(container -> container.plotProjection(trackingId))
                .orElse(null);
        if (projection == null) {
            return null;
        }

        PhysicsVector currentPosition = AssemblyCoordinateSpace.toPhysicsVector(entity.position());
        PhysicsVector localPosition = projection.poseFrame().currentTransform().worldToLocal(currentPosition);
        PhysicsVector previousPosition = projection.poseFrame().previousTransform().localToWorld(localPosition);
        if (!finite(previousPosition)) {
            return null;
        }
        return AssemblyCoordinateSpace.toVec3(previousPosition);
    }

    public static void setOldPosNoMovement(Entity entity) {
        Vec3 oldPosition = previousTrackingFramePosition(entity);
        if (oldPosition == null) {
            oldPosition = entity.position();
        }
        entity.xOld = oldPosition.x;
        entity.xo = oldPosition.x;
        entity.yOld = oldPosition.y;
        entity.yo = oldPosition.y;
        entity.zOld = oldPosition.z;
        entity.zo = oldPosition.z;
    }

    @Nullable
    public static AABB plotAabbToWorldBounds(AssemblyPlotProjection projection, AABB plotBounds) {
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(plotBounds, "plotBounds");

        AabbBuilder builder = new AabbBuilder();
        includeProjectedCorner(builder, projection, plotBounds.minX, plotBounds.minY, plotBounds.minZ);
        includeProjectedCorner(builder, projection, plotBounds.minX, plotBounds.minY, plotBounds.maxZ);
        includeProjectedCorner(builder, projection, plotBounds.minX, plotBounds.maxY, plotBounds.minZ);
        includeProjectedCorner(builder, projection, plotBounds.minX, plotBounds.maxY, plotBounds.maxZ);
        includeProjectedCorner(builder, projection, plotBounds.maxX, plotBounds.minY, plotBounds.minZ);
        includeProjectedCorner(builder, projection, plotBounds.maxX, plotBounds.minY, plotBounds.maxZ);
        includeProjectedCorner(builder, projection, plotBounds.maxX, plotBounds.maxY, plotBounds.minZ);
        includeProjectedCorner(builder, projection, plotBounds.maxX, plotBounds.maxY, plotBounds.maxZ);
        return builder.build();
    }

    private static boolean finite(Vec3 position) {
        return Double.isFinite(position.x)
                && Double.isFinite(position.y)
                && Double.isFinite(position.z);
    }

    private static boolean finite(PhysicsVector position) {
        return Double.isFinite(position.x())
                && Double.isFinite(position.y())
                && Double.isFinite(position.z());
    }

    private static void includeProjectedCorner(
            AabbBuilder builder,
            AssemblyPlotProjection projection,
            double x,
            double y,
            double z
    ) {
        Vec3 projected = projection.plotToWorld(new Vec3(x, y, z));
        if (finite(projected)) {
            builder.include(projected);
        }
    }

    private static final class AabbBuilder {
        private double minX = Double.POSITIVE_INFINITY;
        private double minY = Double.POSITIVE_INFINITY;
        private double minZ = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY;
        private double maxY = Double.NEGATIVE_INFINITY;
        private double maxZ = Double.NEGATIVE_INFINITY;

        private void include(Vec3 point) {
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            minZ = Math.min(minZ, point.z);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
            maxZ = Math.max(maxZ, point.z);
        }

        @Nullable
        private AABB build() {
            if (!Double.isFinite(minX)
                    || !Double.isFinite(minY)
                    || !Double.isFinite(minZ)
                    || !Double.isFinite(maxX)
                    || !Double.isFinite(maxY)
                    || !Double.isFinite(maxZ)) {
                return null;
            }
            return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }
}
