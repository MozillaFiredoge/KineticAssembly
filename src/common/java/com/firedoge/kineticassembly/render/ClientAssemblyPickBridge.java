package com.firedoge.kineticassembly.render;

import java.util.Objects;
import java.util.Optional;

import com.firedoge.kineticassembly.minecraft.assembly.ClientAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityBridge;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class ClientAssemblyPickBridge {
    private ClientAssemblyPickBridge() {
    }

    public static void updateCrosshairTarget(Minecraft minecraft) {
        Objects.requireNonNull(minecraft, "minecraft");
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }

        ClientAssemblyContainer container = AssemblyContainers.container(minecraft.level)
                .filter(ClientAssemblyContainer.class::isInstance)
                .map(ClientAssemblyContainer.class::cast)
                .orElse(null);
        if (container == null || container.isEmpty()) {
            return;
        }

        ClientAssemblySelection.Result assemblyHit = ClientAssemblySelection.update(minecraft, container).orElse(null);
        HitResult vanillaHit = minecraft.hitResult;
        Vec3 eyePosition = minecraft.player.getEyePosition();
        double vanillaDistance = hitDistance(vanillaHit, eyePosition);
        EntityHitResult plotEntityHit = pickPlotEntity(minecraft).orElse(null);
        double plotEntityDistance = hitDistance(plotEntityHit, eyePosition);

        if (plotEntityHit != null
                && plotEntityDistance <= vanillaDistance
                && (assemblyHit == null || plotEntityDistance <= assemblyHit.distance())) {
            minecraft.hitResult = plotEntityHit;
            minecraft.crosshairPickEntity = plotEntityHit.getEntity();
            return;
        }

        if (assemblyHit == null) {
            return;
        }

        if (vanillaDistance <= assemblyHit.distance()) {
            return;
        }

        minecraft.hitResult = new BlockHitResult(
                assemblyHit.hit().getLocation(),
                assemblyHit.hit().getDirection(),
                assemblyHit.plotPos(),
                assemblyHit.hit().isInside()
        );
        minecraft.crosshairPickEntity = null;
    }

    private static Optional<EntityHitResult> pickPlotEntity(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            return Optional.empty();
        }

        double maxDistance = minecraft.player.entityInteractionRange();
        Vec3 start = minecraft.player.getEyePosition();
        Vec3 end = start.add(minecraft.player.getLookAngle().scale(maxDistance));
        double bestDistanceSqr = maxDistance * maxDistance;
        float partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(true);
        EntityHitResult best = null;

        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity == minecraft.player
                    || entity.isRemoved()
                    || entity.isSpectator()
                    || !entity.isPickable()) {
                continue;
            }

            AABB worldBounds = AssemblyEntityBridge
                    .plotEntityWorldBounds(minecraft.level, entity, partialTick)
                    .orElse(null);
            if (worldBounds == null) {
                continue;
            }

            AABB pickBounds = worldBounds.inflate(entity.getPickRadius());
            Optional<Vec3> maybeHit = pickBounds.contains(start)
                    ? Optional.of(start)
                    : pickBounds.clip(start, end);
            if (maybeHit.isEmpty()) {
                continue;
            }

            Vec3 hit = maybeHit.get();
            double distanceSqr = hit.distanceToSqr(start);
            if (distanceSqr < bestDistanceSqr) {
                bestDistanceSqr = distanceSqr;
                best = new EntityHitResult(entity, hit);
            }
        }
        return Optional.ofNullable(best);
    }

    private static double hitDistance(HitResult hit, Vec3 origin) {
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            return Double.POSITIVE_INFINITY;
        }
        return hit.getLocation().distanceTo(origin);
    }
}
