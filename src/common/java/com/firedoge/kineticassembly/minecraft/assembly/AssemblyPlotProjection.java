package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.Objects;

import com.firedoge.kineticassembly.api.PhysicsVector;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public record AssemblyPlotProjection(
        AssemblyId id,
        AssemblyPlot plot,
        PhysicsVector bodyToPlotOrigin,
        AssemblyPoseFrame poseFrame
) {
    public AssemblyPlotProjection {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(plot, "plot");
        Objects.requireNonNull(bodyToPlotOrigin, "bodyToPlotOrigin");
        Objects.requireNonNull(poseFrame, "poseFrame");
        if (!poseFrame.id().equals(id)) {
            throw new IllegalArgumentException("pose frame id does not match projection id");
        }
    }

    public boolean containsPlotBlock(BlockPos plotPos) {
        Objects.requireNonNull(plotPos, "plotPos");
        return plot.containsPlotBlockPos(plotPos);
    }

    public Vec3 plotToWorld(Vec3 plotPosition) {
        Objects.requireNonNull(plotPosition, "plotPosition");
        PhysicsVector bodyLocal = AssemblyCoordinateSpace.plotToBodyLocal(plot, bodyToPlotOrigin, plotPosition);
        return AssemblyCoordinateSpace.toVec3(poseFrame.currentTransform().localToWorld(bodyLocal));
    }

    public Vec3 plotToWorld(Vec3 plotPosition, double partialTick) {
        Objects.requireNonNull(plotPosition, "plotPosition");
        PhysicsVector bodyLocal = AssemblyCoordinateSpace.plotToBodyLocal(plot, bodyToPlotOrigin, plotPosition);
        return AssemblyCoordinateSpace.toVec3(poseFrame.interpolatedTransform(partialTick).localToWorld(bodyLocal));
    }

    public Vec3 trackedRenderPosition(Vec3 previousWorldPosition, Vec3 currentWorldPosition, double partialTick) {
        Objects.requireNonNull(previousWorldPosition, "previousWorldPosition");
        Objects.requireNonNull(currentWorldPosition, "currentWorldPosition");
        double alpha = clamp(partialTick, 0.0D, 1.0D);
        PhysicsVector previousLocal = poseFrame.previousTransform()
                .worldToLocal(AssemblyCoordinateSpace.toPhysicsVector(previousWorldPosition));
        PhysicsVector currentLocal = poseFrame.currentTransform()
                .worldToLocal(AssemblyCoordinateSpace.toPhysicsVector(currentWorldPosition));
        PhysicsVector local = new PhysicsVector(
                previousLocal.x() + (currentLocal.x() - previousLocal.x()) * alpha,
                previousLocal.y() + (currentLocal.y() - previousLocal.y()) * alpha,
                previousLocal.z() + (currentLocal.z() - previousLocal.z()) * alpha
        );
        return AssemblyCoordinateSpace.toVec3(poseFrame.interpolatedTransform(alpha).localToWorld(local));
    }

    public Vec3 plotDirectionToWorld(Vec3 plotDirection) {
        Objects.requireNonNull(plotDirection, "plotDirection");
        return AssemblyCoordinateSpace.toVec3(
                poseFrame.currentTransform().localDirectionToWorld(AssemblyCoordinateSpace.toPhysicsVector(plotDirection))
        );
    }

    public Vec3 worldToPlot(Vec3 worldPosition) {
        Objects.requireNonNull(worldPosition, "worldPosition");
        PhysicsVector bodyLocal = poseFrame.currentTransform().worldToLocal(AssemblyCoordinateSpace.toPhysicsVector(worldPosition));
        return AssemblyCoordinateSpace.toVec3(
                AssemblyCoordinateSpace.bodyLocalToPlot(plot, bodyToPlotOrigin, bodyLocal)
        );
    }

    public Vec3 pointVelocity(Vec3 plotPosition) {
        Objects.requireNonNull(plotPosition, "plotPosition");
        PhysicsVector bodyLocal = AssemblyCoordinateSpace.plotToBodyLocal(plot, bodyToPlotOrigin, plotPosition);
        PhysicsVector previous = poseFrame.previousTransform().localToWorld(bodyLocal);
        PhysicsVector current = poseFrame.currentTransform().localToWorld(bodyLocal);
        if (!finite(previous) || !finite(current)) {
            return Vec3.ZERO;
        }
        return new Vec3(
                current.x() - previous.x(),
                current.y() - previous.y(),
                current.z() - previous.z()
        );
    }

    public Vec3 worldDirectionToPlot(Vec3 worldDirection) {
        Objects.requireNonNull(worldDirection, "worldDirection");
        return AssemblyCoordinateSpace.toVec3(
                poseFrame.currentTransform().worldDirectionToLocal(AssemblyCoordinateSpace.toPhysicsVector(worldDirection))
        );
    }

    public Vec3 previousWorldToPlot(Vec3 worldPosition) {
        Objects.requireNonNull(worldPosition, "worldPosition");
        PhysicsVector bodyLocal = poseFrame.previousTransform().worldToLocal(AssemblyCoordinateSpace.toPhysicsVector(worldPosition));
        return AssemblyCoordinateSpace.toVec3(
                AssemblyCoordinateSpace.bodyLocalToPlot(plot, bodyToPlotOrigin, bodyLocal)
        );
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean finite(PhysicsVector vector) {
        return Double.isFinite(vector.x())
                && Double.isFinite(vector.y())
                && Double.isFinite(vector.z());
    }
}
