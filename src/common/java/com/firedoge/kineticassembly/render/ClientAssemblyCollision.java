package com.firedoge.kineticassembly.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.minecraft.assembly.ClientAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.ClientTrackedAssembly;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyTransform;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class ClientAssemblyCollision {
    private ClientAssemblyCollision() {
    }

    public static List<VoxelShape> blockCollisionShapes(ClientLevel level, AABB worldBounds) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(worldBounds, "worldBounds");
        ClientAssemblyContainer container = AssemblyContainers.container(level)
                .filter(ClientAssemblyContainer.class::isInstance)
                .map(ClientAssemblyContainer.class::cast)
                .orElse(null);
        if (container == null || container.isEmpty()) {
            return List.of();
        }

        AABB queryBounds = worldBounds.inflate(1.0E-7D);
        List<VoxelShape> shapes = new ArrayList<>();
        for (ClientTrackedAssembly assembly : container.trackedAssemblies()) {
            if (!assembly.finalized()) {
                continue;
            }
            appendCachedAssemblyCollisions(container, assembly, queryBounds, shapes);
        }
        return List.copyOf(shapes);
    }

    private static void appendCachedAssemblyCollisions(
            ClientAssemblyContainer container,
            ClientTrackedAssembly assembly,
            AABB queryBounds,
            List<VoxelShape> output
    ) {
        ClientTrackedAssembly.WorldCollisionCache cache = worldCollisionCache(container, assembly);
        for (AABB worldBox : cache.candidateBoxes(queryBounds)) {
            if (worldBox.intersects(queryBounds)) {
                output.add(Shapes.create(worldBox));
            }
        }
    }

    private static ClientTrackedAssembly.WorldCollisionCache worldCollisionCache(
            ClientAssemblyContainer container,
            ClientTrackedAssembly assembly
    ) {
        long geometryVersion = assembly.collisionGeometryVersion();
        PhysicsPose pose = assembly.pose();
        ClientTrackedAssembly.WorldCollisionCache cached = assembly.worldCollisionCache();
        if (cached != null && cached.geometryVersion() == geometryVersion && cached.pose().equals(pose)) {
            return cached;
        }

        List<AABB> bodyLocalBoxes = container.bodyLocalCollisionBoxes(assembly);
        AssemblyTransform transform = AssemblyTransform.from(pose);
        List<AABB> worldBoxes = new ArrayList<>(bodyLocalBoxes.size());
        for (AABB bodyLocalBox : bodyLocalBoxes) {
            worldBoxes.add(transform.localAabbToWorldBounds(bodyLocalBox));
        }
        assembly.cacheWorldCollisionBoxes(pose, worldBoxes);
        return assembly.worldCollisionCache();
    }
}
