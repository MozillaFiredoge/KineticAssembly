package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.List;
import java.util.Objects;

import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsVector;

import net.minecraft.world.level.ChunkPos;

public record AssemblyClientMetadata(
        AssemblyId id,
        AssemblyPlot plot,
        AssemblyPoseFrame poseFrame,
        PhysicsVector bodyToPlotOrigin,
        List<ChunkPos> chunkPositions
) {
    public AssemblyClientMetadata {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(plot, "plot");
        Objects.requireNonNull(poseFrame, "poseFrame");
        if (!poseFrame.id().equals(id)) {
            throw new IllegalArgumentException("pose frame id does not match metadata id");
        }
        Objects.requireNonNull(bodyToPlotOrigin, "bodyToPlotOrigin");
        chunkPositions = List.copyOf(chunkPositions);
    }

    public PhysicsPose pose() {
        return poseFrame.currentPose();
    }

    public long epoch() {
        return poseFrame.epoch();
    }

    public static AssemblyClientMetadata from(PhysicsAssembly assembly, AssemblyPoseFrame poseFrame) {
        Objects.requireNonNull(assembly, "assembly");
        return new AssemblyClientMetadata(
                assembly.id(),
                assembly.plot(),
                poseFrame,
                AssemblyCoordinateSpace.bodyToPlotOrigin(assembly),
                assembly.plot().chunkPositions()
        );
    }

    public static AssemblyClientMetadata from(PhysicsAssembly assembly, PhysicsPose pose) {
        Objects.requireNonNull(assembly, "assembly");
        return from(assembly, AssemblyPoseFrame.initial(assembly.id(), pose));
    }
}
