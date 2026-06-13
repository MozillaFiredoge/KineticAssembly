package com.firedoge.kineticassembly.mixin.render;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.firedoge.kineticassembly.api.PhysicsQuaternion;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityCollisionAccess;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityBridge;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyId;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPlotProjection;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyVectors;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    private static final Set<Integer> kinetic_assembly$debuggedAttachedCulling = ConcurrentHashMap.newKeySet();

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <T extends Entity> void kinetic_assembly$shouldRenderAssemblyEntity(
            T entity,
            Frustum frustum,
            double cameraX,
            double cameraY,
            double cameraZ,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (entity.noCulling) {
            cir.setReturnValue(true);
            return;
        }

        float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
        AssemblyPlotProjection containingProjection = kinetic_assembly$projectionForPlotPosition(entity.position());
        if (containingProjection != null) {
            if (entity instanceof BlockAttachedEntity) {
                AABB worldBounds = AssemblyEntityBridge
                        .plotAttachedEntityWorldBounds(entity.level(), entity, partialTick)
                        .orElse(null);
                if (worldBounds != null) {
                    boolean visible = frustum.isVisible(worldBounds.inflate(0.5D));
                    kinetic_assembly$debugAttachedCulling(entity, worldBounds.getCenter(), containingProjection, visible);
                    cir.setReturnValue(visible);
                    return;
                }
            }

            Vec3 renderPosition = containingProjection.plotToWorld(entity.position(), partialTick);
            boolean visible = kinetic_assembly$visibleAt(entity, frustum, renderPosition);
            kinetic_assembly$debugAttachedCulling(entity, renderPosition, containingProjection, visible);
            cir.setReturnValue(visible);
            return;
        }

        if (!(entity instanceof AssemblyEntityCollisionAccess access)) {
            return;
        }

        AssemblyId trackingId = access.kinetic_assembly$trackingAssemblyId();
        if (trackingId == null) {
            return;
        }

        AssemblyPlotProjection trackingProjection = kinetic_assembly$projectionForTracking(trackingId);
        if (trackingProjection == null) {
            return;
        }

        Vec3 renderPosition = trackingProjection.trackedRenderPosition(
                new Vec3(entity.xOld, entity.yOld, entity.zOld),
                entity.position(),
                partialTick
        );
        cir.setReturnValue(kinetic_assembly$visibleAt(entity, frustum, renderPosition));
    }

    @Inject(method = "getPackedLightCoords", at = @At("RETURN"), cancellable = true)
    private <T extends Entity> void kinetic_assembly$getPackedLightCoords(
            T entity,
            float partialTick,
            CallbackInfoReturnable<Integer> cir
    ) {
        Vec3 probePosition = kinetic_assembly$renderLightProbePosition(entity, partialTick);
        if (probePosition == null || !AssemblyVectors.finite(probePosition)) {
            return;
        }

        BlockPos probeBlock = BlockPos.containing(probePosition);
        int original = cir.getReturnValue();
        int blockLight = Math.max(
                LightTexture.block(original),
                entity.level().getBrightness(LightLayer.BLOCK, probeBlock)
        );
        int skyLight = entity.level().getBrightness(LightLayer.SKY, probeBlock);
        cir.setReturnValue(LightTexture.pack(blockLight, skyLight));
    }

    @Inject(method = "getSkyLightLevel", at = @At("RETURN"), cancellable = true)
    private <T extends Entity> void kinetic_assembly$getSkyLightLevel(
            T entity,
            BlockPos pos,
            CallbackInfoReturnable<Integer> cir
    ) {
        Vec3 probePosition = kinetic_assembly$renderLightProbePosition(entity, kinetic_assembly$partialTick());
        if (AssemblyVectors.finite(probePosition)) {
            cir.setReturnValue(entity.level().getBrightness(LightLayer.SKY, BlockPos.containing(probePosition)));
        }
    }

    @Inject(method = "getBlockLightLevel", at = @At("RETURN"), cancellable = true)
    private <T extends Entity> void kinetic_assembly$getBlockLightLevel(
            T entity,
            BlockPos pos,
            CallbackInfoReturnable<Integer> cir
    ) {
        Vec3 probePosition = kinetic_assembly$renderLightProbePosition(entity, kinetic_assembly$partialTick());
        if (AssemblyVectors.finite(probePosition)) {
            int blockLight = entity.level().getBrightness(LightLayer.BLOCK, BlockPos.containing(probePosition));
            cir.setReturnValue(Math.max(cir.getReturnValue(), blockLight));
        }
    }

    @Redirect(
            method = "renderNameTag",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;cameraOrientation()Lorg/joml/Quaternionf;"
            )
    )
    private Quaternionf kinetic_assembly$assemblyNameTagCameraOrientation(
            EntityRenderDispatcher dispatcher,
            @Local(argsOnly = true) Entity entity
    ) {
        PhysicsQuaternion orientation = kinetic_assembly$renderOrientation(entity, kinetic_assembly$partialTick());
        if (orientation == null) {
            return dispatcher.cameraOrientation();
        }
        return kinetic_assembly$toJomlQuaternion(orientation).conjugate().mul(dispatcher.cameraOrientation());
    }

    private static boolean kinetic_assembly$visibleAt(Entity entity, Frustum frustum, Vec3 renderPosition) {
        if (!AssemblyVectors.finite(renderPosition)) {
            return false;
        }

        AABB cullingBox = kinetic_assembly$cullingBox(entity).move(renderPosition.subtract(entity.position()));
        if (frustum.isVisible(cullingBox)) {
            return true;
        }

        if (entity instanceof Leashable leashable) {
            Entity leashHolder = leashable.getLeashHolder();
            if (leashHolder != null) {
                return frustum.isVisible(kinetic_assembly$cullingBox(leashHolder));
            }
        }
        return false;
    }

    private static AABB kinetic_assembly$cullingBox(Entity entity) {
        AABB cullingBox = entity.getBoundingBoxForCulling().inflate(0.5D);
        if (cullingBox.hasNaN() || cullingBox.getSize() == 0.0D) {
            return new AABB(
                    entity.getX() - 2.0D,
                    entity.getY() - 2.0D,
                    entity.getZ() - 2.0D,
                    entity.getX() + 2.0D,
                    entity.getY() + 2.0D,
                    entity.getZ() + 2.0D
            );
        }
        return cullingBox;
    }

    private static void kinetic_assembly$debugAttachedCulling(
            Entity entity,
            Vec3 renderPosition,
            AssemblyPlotProjection projection,
            boolean visible
    ) {
        if (!AssemblyEntityBridge.isDebugAttachedEntity(entity)
                || !kinetic_assembly$debuggedAttachedCulling.add(entity.getId())) {
            return;
        }
        AssemblyEntityBridge.debugAttachedEntity(
                "client-should-render",
                entity.level(),
                entity,
                "renderPosition=" + renderPosition + " projection=" + projection.id() + " visible=" + visible
        );
    }

    private static Vec3 kinetic_assembly$renderLightProbePosition(Entity entity, float partialTick) {
        AssemblyPlotProjection containingProjection = kinetic_assembly$projectionForPlotPosition(entity.position());
        if (containingProjection != null) {
            return containingProjection.plotToWorld(entity.getLightProbePosition(partialTick), partialTick);
        }

        if (!(entity instanceof AssemblyEntityCollisionAccess access)) {
            return null;
        }

        AssemblyId trackingId = access.kinetic_assembly$trackingAssemblyId();
        if (trackingId == null) {
            return null;
        }

        AssemblyPlotProjection trackingProjection = kinetic_assembly$projectionForTracking(trackingId);
        if (trackingProjection == null) {
            return null;
        }

        Vec3 lightProbeOffset = entity.getLightProbePosition(partialTick).subtract(entity.getEyePosition(partialTick));
        Vec3 previousEyePosition = new Vec3(entity.xo, entity.yo + entity.getEyeHeight(), entity.zo);
        Vec3 currentEyePosition = new Vec3(entity.getX(), entity.getY() + entity.getEyeHeight(), entity.getZ());
        return trackingProjection.trackedRenderPosition(previousEyePosition, currentEyePosition, partialTick)
                .add(lightProbeOffset);
    }

    private static PhysicsQuaternion kinetic_assembly$renderOrientation(Entity entity, float partialTick) {
        AssemblyPlotProjection containingProjection = kinetic_assembly$projectionForPlotPosition(entity.position());
        if (containingProjection != null) {
            return containingProjection.poseFrame().interpolatedPose(partialTick).rotation();
        }

        return null;
    }

    private static float kinetic_assembly$partialTick() {
        return Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
    }

    private static AssemblyPlotProjection kinetic_assembly$projectionForPlotPosition(Vec3 position) {
        if (Minecraft.getInstance().level == null) {
            return null;
        }
        return AssemblyEntityBridge.plotProjectionAtOrAdjacent(Minecraft.getInstance().level, BlockPos.containing(position))
                .orElse(null);
    }

    private static AssemblyPlotProjection kinetic_assembly$projectionForTracking(AssemblyId id) {
        if (Minecraft.getInstance().level == null) {
            return null;
        }
        return AssemblyContainers.container(Minecraft.getInstance().level)
                .flatMap(container -> container.plotProjection(id))
                .orElse(null);
    }
    private static Quaternionf kinetic_assembly$toJomlQuaternion(PhysicsQuaternion rotation) {
        return new Quaternionf(
                (float) rotation.x(),
                (float) rotation.y(),
                (float) rotation.z(),
                (float) rotation.w()
        ).normalize();
    }
}
