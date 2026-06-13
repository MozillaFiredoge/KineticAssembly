package com.firedoge.kineticassembly.render;

import java.util.Objects;
import java.util.Optional;

import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.minecraft.assembly.ClientAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.ClientTrackedAssembly;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyClientMetadata;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyTransform;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

public final class ClientAssemblyEffectProjection {
    private ClientAssemblyEffectProjection() {
    }

    public static Optional<Projection> projection(ClientLevel level, BlockPos plotPos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        return AssemblyContainers.container(level)
                .filter(ClientAssemblyContainer.class::isInstance)
                .map(ClientAssemblyContainer.class::cast)
                .flatMap(container -> {
                    ChunkPos chunkPos = new ChunkPos(plotPos);
                    Optional<Projection> active = container.trackedAssemblyForChunk(chunkPos)
                            .filter(ClientTrackedAssembly::finalized)
                            .filter(assembly -> assembly.plot().containsPlotBlockPos(plotPos))
                            .map(Projection::active);
                    if (active.isPresent()) {
                        return active;
                    }
                    return container.removedProjectionForChunk(chunkPos)
                            .map(ClientAssemblyContainer.RemovedClientAssemblyProjection::metadata)
                            .filter(metadata -> metadata.plot().containsPlotBlockPos(plotPos))
                            .map(Projection::removed);
                });
    }

    public static Optional<Vec3> plotToWorld(ClientLevel level, Vec3 plotPosition) {
        Objects.requireNonNull(plotPosition, "plotPosition");
        return projection(level, BlockPos.containing(plotPosition))
                .map(projection -> projection.toWorld(plotPosition));
    }

    public record Projection(ClientTrackedAssembly assembly, AssemblyClientMetadata metadata) {
        public Projection {
            if (assembly == null && metadata == null) {
                throw new IllegalArgumentException("Projection needs either an active assembly or removed metadata");
            }
        }

        static Projection active(ClientTrackedAssembly assembly) {
            return new Projection(Objects.requireNonNull(assembly, "assembly"), null);
        }

        static Projection removed(AssemblyClientMetadata metadata) {
            return new Projection(null, Objects.requireNonNull(metadata, "metadata"));
        }

        public Vec3 toWorld(Vec3 plotPosition) {
            return assembly != null
                    ? ClientAssemblySelection.plotToWorld(plotPosition, assembly)
                    : ClientAssemblySelection.plotToWorld(plotPosition, metadata);
        }

        public Vec3 directionToWorld(Vec3 plotDirection) {
            return assembly != null
                    ? ClientAssemblySelection.plotDirectionToWorld(plotDirection, assembly)
                    : ClientAssemblySelection.plotDirectionToWorld(plotDirection, metadata);
        }

        public Vec3 worldToPlot(Vec3 worldPosition) {
            Objects.requireNonNull(worldPosition, "worldPosition");
            AssemblyClientMetadata currentMetadata = assembly != null ? assembly.metadata() : metadata;
            PhysicsVector local = AssemblyTransform.from(assembly != null ? assembly.pose() : metadata.pose())
                    .worldToLocal(new PhysicsVector(worldPosition.x, worldPosition.y, worldPosition.z));
            PhysicsVector bodyToPlotOrigin = currentMetadata.bodyToPlotOrigin();
            return new Vec3(
                    local.x() - bodyToPlotOrigin.x() + currentMetadata.plot().minPlotX(),
                    local.y() - bodyToPlotOrigin.y() + currentMetadata.plot().minPlotY(),
                    local.z() - bodyToPlotOrigin.z() + currentMetadata.plot().minPlotZ()
            );
        }
    }
}
