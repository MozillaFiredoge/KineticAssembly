package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.Objects;

import javax.annotation.Nullable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class AssemblyEntityOrientation {
    private static final Vec3 LOCAL_UP = new Vec3(0.0D, 1.0D, 0.0D);

    private AssemblyEntityOrientation() {
    }

    @Nullable
    public static Vec3 localToWorld(Entity entity, Vec3 localDirection) {
        AssemblyPlotProjection projection = projection(entity);
        if (projection == null) {
            return null;
        }
        Vec3 direction = projection.plotDirectionToWorld(localDirection);
        return finite(direction) ? direction : null;
    }

    @Nullable
    public static Vec3 worldToLocal(Entity entity, Vec3 worldDirection) {
        AssemblyPlotProjection projection = projection(entity);
        if (projection == null) {
            return null;
        }
        Vec3 direction = projection.worldDirectionToPlot(worldDirection);
        return finite(direction) ? direction : null;
    }

    @Nullable
    public static Vec3 up(Entity entity) {
        Vec3 up = localToWorld(entity, LOCAL_UP);
        if (up == null) {
            return null;
        }
        double lengthSqr = up.lengthSqr();
        if (lengthSqr <= 1.0E-12D || !Double.isFinite(lengthSqr)) {
            return null;
        }
        return up.scale(1.0D / Math.sqrt(lengthSqr));
    }

    @Nullable
    public static AssemblyPlotProjection projection(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        // Ordinary assembly tracking is contact/carry state, not an entity-local orientation.
        // Keep this null until an explicit pilot/vehicle/custom-orientation source exists.
        return null;
    }

    private static boolean finite(Vec3 position) {
        return Double.isFinite(position.x)
                && Double.isFinite(position.y)
                && Double.isFinite(position.z);
    }
}
