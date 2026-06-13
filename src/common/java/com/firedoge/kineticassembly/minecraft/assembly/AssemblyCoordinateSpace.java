package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.Objects;

import com.firedoge.kineticassembly.api.PhysicsVector;

import net.minecraft.world.phys.Vec3;

public final class AssemblyCoordinateSpace {
    private AssemblyCoordinateSpace() {
    }

    public static PhysicsVector bodyToPlotOrigin(PhysicsAssembly assembly) {
        Objects.requireNonNull(assembly, "assembly");
        return assembly.blocks().stream()
                .findFirst()
                .map(block -> new PhysicsVector(
                        block.visualLocalOrigin().x() - block.localPos().getX(),
                        block.visualLocalOrigin().y() - block.localPos().getY(),
                        block.visualLocalOrigin().z() - block.localPos().getZ()
                ))
                .orElse(PhysicsVector.ZERO);
    }

    public static PhysicsVector plotToBodyLocal(PhysicsAssembly assembly, Vec3 plotPosition) {
        Objects.requireNonNull(assembly, "assembly");
        return plotToBodyLocal(assembly.plot(), bodyToPlotOrigin(assembly), plotPosition);
    }

    public static PhysicsVector plotToBodyLocal(AssemblyClientMetadata metadata, Vec3 plotPosition) {
        Objects.requireNonNull(metadata, "metadata");
        return plotToBodyLocal(metadata.plot(), metadata.bodyToPlotOrigin(), plotPosition);
    }

    public static PhysicsVector plotToBodyLocal(
            AssemblyPlot plot,
            PhysicsVector bodyToPlotOrigin,
            Vec3 plotPosition
    ) {
        Objects.requireNonNull(plot, "plot");
        Objects.requireNonNull(bodyToPlotOrigin, "bodyToPlotOrigin");
        Objects.requireNonNull(plotPosition, "plotPosition");
        return new PhysicsVector(
                bodyToPlotOrigin.x() + plotPosition.x() - plot.minPlotX(),
                bodyToPlotOrigin.y() + plotPosition.y() - plot.minPlotY(),
                bodyToPlotOrigin.z() + plotPosition.z() - plot.minPlotZ()
        );
    }

    public static PhysicsVector bodyLocalToPlot(AssemblyClientMetadata metadata, PhysicsVector bodyLocalPosition) {
        Objects.requireNonNull(metadata, "metadata");
        return bodyLocalToPlot(metadata.plot(), metadata.bodyToPlotOrigin(), bodyLocalPosition);
    }

    public static PhysicsVector bodyLocalToPlot(
            AssemblyPlot plot,
            PhysicsVector bodyToPlotOrigin,
            PhysicsVector bodyLocalPosition
    ) {
        Objects.requireNonNull(plot, "plot");
        Objects.requireNonNull(bodyToPlotOrigin, "bodyToPlotOrigin");
        Objects.requireNonNull(bodyLocalPosition, "bodyLocalPosition");
        return new PhysicsVector(
                bodyLocalPosition.x() - bodyToPlotOrigin.x() + plot.minPlotX(),
                bodyLocalPosition.y() - bodyToPlotOrigin.y() + plot.minPlotY(),
                bodyLocalPosition.z() - bodyToPlotOrigin.z() + plot.minPlotZ()
        );
    }

    public static Vec3 toVec3(PhysicsVector vector) {
        Objects.requireNonNull(vector, "vector");
        return new Vec3(vector.x(), vector.y(), vector.z());
    }

    public static PhysicsVector toPhysicsVector(Vec3 vector) {
        Objects.requireNonNull(vector, "vector");
        return new PhysicsVector(vector.x, vector.y, vector.z);
    }
}
