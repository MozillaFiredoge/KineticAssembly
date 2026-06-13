package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class AssemblyPathing {
    private AssemblyPathing() {
    }

    @Nullable
    public static AssemblyPlotProjection trackingProjection(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (entity instanceof AssemblyEntityCollisionAccess access) {
            AssemblyId trackingId = access.kinetic_assembly$trackingAssemblyId();
            if (trackingId != null) {
                AssemblyPlotProjection projection = projection(entity.level(), trackingId);
                if (projection != null) {
                    return projection;
                }
            }
        }
        return null;
    }

    @Nullable
    public static AssemblyPlotProjection projection(Level level, AssemblyId id) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        return AssemblyContainers.container(level)
                .flatMap(container -> container.plotProjection(id))
                .orElse(null);
    }

    @Nullable
    public static AssemblyPlotProjection projection(Level level, BlockPos plotPos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        return AssemblyContainers.container(level)
                .flatMap(container -> container.plotProjection(plotPos))
                .orElse(null);
    }

    @Nullable
    public static AssemblyPlotProjection pathingProjection(Entity mob, Set<BlockPos> targets) {
        AssemblyPlotProjection projection = trackingProjection(mob);
        if (projection != null) {
            return projection;
        }

        Level level = mob.level();
        for (BlockPos target : targets) {
            projection = projection(level, target);
            if (projection != null) {
                return projection;
            }
        }
        return null;
    }

    @Nullable
    public static AssemblyPlotProjection entityProjection(Entity entity) {
        AssemblyPlotProjection projection = trackingProjection(entity);
        if (projection != null) {
            return projection;
        }
        return projection(entity.level(), entity.blockPosition());
    }

    public static Vec3 entityToPlotPosition(Entity entity, AssemblyPlotProjection projection) {
        if (isTrackedBy(entity, projection.id())) {
            return projection.worldToPlot(entity.position());
        }
        if (projection.containsPlotBlock(entity.blockPosition())) {
            return entity.position();
        }
        return projection.worldToPlot(entity.position());
    }

    public static Vec3 pathfindingMobPosition(Entity mob) {
        Objects.requireNonNull(mob, "mob");
        AssemblyPlotProjection projection = trackingProjection(mob);
        if (projection == null) {
            return mob.position();
        }
        Vec3 localPosition = entityToPlotPosition(mob, projection);
        return finite(localPosition) ? localPosition : mob.position();
    }

    public static Vec3 worldOrPlotToPlot(AssemblyPlotProjection projection, Vec3 position) {
        if (projection.containsPlotBlock(BlockPos.containing(position))) {
            return position;
        }
        return projection.worldToPlot(position);
    }

    public static Vec3 plotPositionToWorld(Level level, Vec3 position) {
        AssemblyPlotProjection projection = projection(level, BlockPos.containing(position));
        return projection == null ? position : projection.plotToWorld(position);
    }

    @Nullable
    public static BlockPos plotBlockToWorldBlock(Level level, BlockPos position) {
        AssemblyPlotProjection projection = projection(level, position);
        if (projection == null) {
            return null;
        }

        Vec3 projected = projection.plotToWorld(position.getCenter());
        return finite(projected) ? BlockPos.containing(projected) : null;
    }

    public static boolean finite(Vec3 vector) {
        return AssemblyVectors.finite(vector);
    }

    private static boolean isTrackedBy(Entity entity, AssemblyId id) {
        return entity instanceof AssemblyEntityCollisionAccess access
                && id.equals(access.kinetic_assembly$trackingAssemblyId());
    }
}
