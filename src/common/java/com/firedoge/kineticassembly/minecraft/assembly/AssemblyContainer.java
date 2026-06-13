package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

public interface AssemblyContainer {
    Level level();

    List<PhysicsAssembly> assemblies();

    Optional<PhysicsAssembly> assembly(AssemblyId id);

    Optional<PhysicsAssembly> assemblyAtPlotBlock(BlockPos plotPos);

    int size();

    default boolean inPlotBounds(ChunkPos chunkPos) {
        return false;
    }

    default Optional<AssemblyPlotProjection> plotProjection(AssemblyId id) {
        return Optional.empty();
    }

    default Optional<AssemblyPlotProjection> plotProjection(BlockPos plotPos) {
        return Optional.empty();
    }

    default Optional<AssemblyPlotProjection> plotProjection(ChunkPos plotChunk) {
        return Optional.empty();
    }

    default boolean isEmpty() {
        return size() == 0;
    }

    default void clientStartTracking(AssemblyClientMetadata metadata) {
    }

    default void clientFinalizeTracking(AssemblyId id) {
    }

    default void clientUpdateTransform(AssemblyPoseFrame frame) {
    }

    default void clientStopTracking(AssemblyId id) {
    }

    default List<AssemblyCollisionTarget> collisionTargets(AABB worldBounds, @Nullable AssemblyId forcedId) {
        return List.of();
    }
}
