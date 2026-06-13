package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.minecraft.world.phys.AABB;

final class AssemblyLoadingDependencies {
    private static final double INTERSECTION_EPSILON = 1.0E-7D;

    private AssemblyLoadingDependencies() {
    }

    static Map<AssemblyId, List<AssemblyId>> idsByAssembly(
            List<PhysicsAssembly> assemblies,
            Map<AssemblyId, AABB> worldBoundsById
    ) {
        Objects.requireNonNull(assemblies, "assemblies");
        Objects.requireNonNull(worldBoundsById, "worldBoundsById");

        Map<AssemblyId, Integer> indicesById = new LinkedHashMap<>();
        for (int index = 0; index < assemblies.size(); index++) {
            indicesById.put(assemblies.get(index).id(), index);
        }

        BitSet[] reachability = reachability(assemblies, indicesById, worldBoundsById);
        closeTransitively(reachability);

        Map<AssemblyId, List<AssemblyId>> dependencies = new LinkedHashMap<>();
        for (int index = 0; index < assemblies.size(); index++) {
            dependencies.put(assemblies.get(index).id(), dependencyIds(index, assemblies, reachability[index]));
        }
        return dependencies;
    }

    private static BitSet[] reachability(
            List<PhysicsAssembly> assemblies,
            Map<AssemblyId, Integer> indicesById,
            Map<AssemblyId, AABB> worldBoundsById
    ) {
        BitSet[] reachability = new BitSet[assemblies.size()];
        for (int index = 0; index < assemblies.size(); index++) {
            reachability[index] = new BitSet(assemblies.size());
            reachability[index].set(index);
        }

        for (int index = 0; index < assemblies.size(); index++) {
            addActorDependencies(reachability[index], indicesById, assemblies.get(index));
        }

        for (int firstIndex = 0; firstIndex < assemblies.size(); firstIndex++) {
            PhysicsAssembly first = assemblies.get(firstIndex);
            AABB firstBounds = worldBoundsById.get(first.id());
            if (firstBounds == null) {
                continue;
            }
            for (int secondIndex = firstIndex + 1; secondIndex < assemblies.size(); secondIndex++) {
                PhysicsAssembly second = assemblies.get(secondIndex);
                AABB secondBounds = worldBoundsById.get(second.id());
                if (secondBounds != null && intersects(firstBounds, secondBounds)) {
                    reachability[firstIndex].set(secondIndex);
                    reachability[secondIndex].set(firstIndex);
                }
            }
        }
        return reachability;
    }

    private static void closeTransitively(BitSet[] reachability) {
        for (int bridge = 0; bridge < reachability.length; bridge++) {
            for (int source = 0; source < reachability.length; source++) {
                if (reachability[source].get(bridge)) {
                    reachability[source].or(reachability[bridge]);
                }
            }
        }
    }

    private static List<AssemblyId> dependencyIds(int rootIndex, List<PhysicsAssembly> assemblies, BitSet dependencies) {
        List<AssemblyId> ids = new ArrayList<>(Math.max(1, dependencies.cardinality()));
        ids.add(assemblies.get(rootIndex).id());
        for (int index = dependencies.nextSetBit(0); index >= 0; index = dependencies.nextSetBit(index + 1)) {
            if (index != rootIndex) {
                ids.add(assemblies.get(index).id());
            }
        }
        return List.copyOf(ids);
    }

    private static void addActorDependencies(
            BitSet dependencies,
            Map<AssemblyId, Integer> indicesById,
            PhysicsAssembly current
    ) {
        for (BlockEntityAssemblyActor actor : current.blockEntityActors()) {
            Iterable<PhysicsAssembly> loadingDependencies = actor.kinetic_assembly$getLoadingDependencies();
            if (loadingDependencies == null) {
                continue;
            }
            for (PhysicsAssembly dependency : loadingDependencies) {
                if (dependency == null) {
                    continue;
                }
                AssemblyId dependencyId = dependency.id();
                Integer dependencyIndex = indicesById.get(dependencyId);
                if (dependencyIndex != null && !dependencyId.equals(current.id())) {
                    dependencies.set(dependencyIndex);
                }
            }
        }
    }

    private static boolean intersects(AABB first, AABB second) {
        return first.maxX + INTERSECTION_EPSILON > second.minX - INTERSECTION_EPSILON
                && first.minX - INTERSECTION_EPSILON < second.maxX + INTERSECTION_EPSILON
                && first.maxY + INTERSECTION_EPSILON > second.minY - INTERSECTION_EPSILON
                && first.minY - INTERSECTION_EPSILON < second.maxY + INTERSECTION_EPSILON
                && first.maxZ + INTERSECTION_EPSILON > second.minZ - INTERSECTION_EPSILON
                && first.minZ - INTERSECTION_EPSILON < second.maxZ + INTERSECTION_EPSILON;
    }
}
