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
import com.firedoge.kineticassembly.render.ClientAssemblyBreakingProgress;
import com.firedoge.kineticassembly.render.ClientAssemblyEffectProjection;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow
    private ClientLevel level;
    private static final Set<Integer> kinetic_assembly$debuggedAttachedRenders = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> kinetic_assembly$debuggedAttachedSectionChecks = ConcurrentHashMap.newKeySet();
    private boolean kinetic_assembly$projectingAssemblyParticle;

    @WrapOperation(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;blockPosition()Lnet/minecraft/core/BlockPos;"
            )
    )
    private BlockPos kinetic_assembly$assemblyAttachedEntityRenderBlockPosition(
            Entity entity,
            Operation<BlockPos> original
    ) {
        BlockPos vanilla = original.call(entity);
        if (level == null) {
            return vanilla;
        }

        float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
        AABB worldBounds = AssemblyEntityBridge.plotAttachedEntityWorldBounds(
                level,
                entity,
                partialTick
        ).orElse(null);
        Vec3 projectedCenter = worldBounds == null
                ? kinetic_assembly$projectedEntityRenderPosition(entity, partialTick)
                : worldBounds.getCenter();
        if (!AssemblyVectors.finite(projectedCenter)) {
            return vanilla;
        }

        BlockPos projectedPos = BlockPos.containing(projectedCenter);
        boolean projectedCompiled = level.isOutsideBuildHeight(projectedPos.getY())
                || ((LevelRenderer) (Object) this).isSectionCompiled(projectedPos);
        kinetic_assembly$debugAttachedSectionCheck(entity, vanilla, projectedPos, projectedCompiled);
        return projectedPos;
    }

    @WrapOperation(
            method = "renderEntity",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"
            )
    )
    private void kinetic_assembly$renderAssemblyEntityWithRotation(
            EntityRenderDispatcher dispatcher,
            Entity entity,
            double x,
            double y,
            double z,
            float yaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            Operation<Void> original
    ) {
        RenderProjection projection = kinetic_assembly$renderProjection(entity, x, y, z, partialTick);
        if (projection == null) {
            original.call(dispatcher, entity, x, y, z, yaw, partialTick, poseStack, bufferSource, packedLight);
            return;
        }

        Vec3 position = projection.cameraRelativePosition();
        PhysicsQuaternion rotation = projection.rotation();
        if (rotation == null) {
            original.call(dispatcher, entity, position.x, position.y, position.z, yaw, partialTick, poseStack, bufferSource, packedLight);
            return;
        }

        poseStack.pushPose();
        poseStack.rotateAround(kinetic_assembly$toJomlQuaternion(rotation), (float) position.x, (float) position.y, (float) position.z);
        original.call(dispatcher, entity, position.x, position.y, position.z, yaw, partialTick, poseStack, bufferSource, packedLight);
        poseStack.popPose();
    }

    @Inject(method = "destroyBlockProgress", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$storePlotDestroyProgress(int breakerId, BlockPos pos, int progress, CallbackInfo ci) {
        ClientLevel clientLevel = level != null ? level : Minecraft.getInstance().level;
        if (clientLevel != null && ClientAssemblyBreakingProgress.update(clientLevel, breakerId, pos, progress)) {
            ci.cancel();
        } else if (progress < 0 && ClientAssemblyBreakingProgress.removeIfTracked(breakerId)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDDDD)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void kinetic_assembly$addPlotParticle(
            ParticleOptions options,
            boolean force,
            boolean decreased,
            double x,
            double y,
            double z,
            double xSpeed,
            double ySpeed,
            double zSpeed,
            CallbackInfo ci
    ) {
        if (kinetic_assembly$projectingAssemblyParticle || level == null) {
            return;
        }

        ClientAssemblyEffectProjection.Projection projection = ClientAssemblyEffectProjection
                .projection(level, BlockPos.containing(x, y, z))
                .orElse(null);
        if (projection == null) {
            return;
        }

        Vec3 world = projection.toWorld(new Vec3(x, y, z));
        Vec3 worldSpeed = projection.directionToWorld(new Vec3(xSpeed, ySpeed, zSpeed));
        kinetic_assembly$projectingAssemblyParticle = true;
        try {
            ((LevelRenderer) (Object) this).addParticle(
                    options,
                    force,
                    decreased,
                    world.x,
                    world.y,
                    world.z,
                    worldSpeed.x,
                    worldSpeed.y,
                    worldSpeed.z
            );
        } finally {
            kinetic_assembly$projectingAssemblyParticle = false;
        }
        ci.cancel();
    }

    private AssemblyPlotProjection kinetic_assembly$projectionForPlotPosition(Vec3 position) {
        if (level == null) {
            return null;
        }
        return AssemblyEntityBridge.plotProjectionAtOrAdjacent(level, BlockPos.containing(position))
                .orElse(null);
    }

    private AssemblyPlotProjection kinetic_assembly$projectionForTracking(AssemblyId id) {
        if (level == null) {
            return null;
        }
        return AssemblyContainers.container(level)
                .flatMap(container -> container.plotProjection(id))
                .orElse(null);
    }

    private static void kinetic_assembly$debugAttachedRender(
            Entity entity,
            Vec3 renderPosition,
            AssemblyPlotProjection projection
    ) {
        if (!AssemblyEntityBridge.isDebugAttachedEntity(entity)
                || !kinetic_assembly$debuggedAttachedRenders.add(entity.getId())) {
            return;
        }
        AssemblyEntityBridge.debugAttachedEntity(
                "client-render-project",
                entity.level(),
                entity,
                "renderPosition=" + renderPosition + " projection=" + projection.id()
        );
    }

    private static void kinetic_assembly$debugAttachedSectionCheck(
            Entity entity,
            BlockPos plotPos,
            BlockPos projectedPos,
            boolean projectedCompiled
    ) {
        if (!AssemblyEntityBridge.isDebugAttachedEntity(entity)
                || !kinetic_assembly$debuggedAttachedSectionChecks.add(entity.getId())) {
            return;
        }
        AssemblyEntityBridge.debugAttachedEntity(
                "client-section-compiled",
                entity.level(),
                entity,
                "plotPos=" + plotPos + " projectedPos=" + projectedPos + " projectedCompiled=" + projectedCompiled
        );
    }

    private Vec3 kinetic_assembly$projectedEntityRenderPosition(Entity entity, float partialTick) {
        AssemblyPlotProjection projection = kinetic_assembly$projectionForPlotPosition(entity.position());
        if (projection == null) {
            return null;
        }
        return projection.plotToWorld(entity.position(), partialTick);
    }

    private RenderProjection kinetic_assembly$renderProjection(
            Entity entity,
            double x,
            double y,
            double z,
            float partialTick
    ) {
        Vec3 plotPosition = kinetic_assembly$interpolatedPosition(entity, partialTick);
        Vec3 cameraPosition = plotPosition.subtract(x, y, z);
        AssemblyPlotProjection containingProjection = kinetic_assembly$projectionForPlotPosition(entity.position());
        if (containingProjection != null) {
            Vec3 renderPosition = containingProjection.plotToWorld(plotPosition, partialTick);
            if (!AssemblyVectors.finite(renderPosition)) {
                return null;
            }
            kinetic_assembly$debugAttachedRender(entity, renderPosition, containingProjection);
            return new RenderProjection(
                    renderPosition.subtract(cameraPosition),
                    containingProjection.poseFrame().interpolatedPose(partialTick).rotation()
            );
        }

        if (entity.isPassenger() || !(entity instanceof AssemblyEntityCollisionAccess access)) {
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

        Vec3 renderPosition = trackingProjection.trackedRenderPosition(
                new Vec3(entity.xOld, entity.yOld, entity.zOld),
                entity.position(),
                partialTick
        );
        if (!AssemblyVectors.finite(renderPosition)) {
            return null;
        }
        return new RenderProjection(renderPosition.subtract(cameraPosition), null);
    }

    private static Vec3 kinetic_assembly$interpolatedPosition(Entity entity, float partialTick) {
        return new Vec3(
                Mth.lerp((double) partialTick, entity.xOld, entity.getX()),
                Mth.lerp((double) partialTick, entity.yOld, entity.getY()),
                Mth.lerp((double) partialTick, entity.zOld, entity.getZ())
        );
    }

    private static Quaternionf kinetic_assembly$toJomlQuaternion(PhysicsQuaternion rotation) {
        return new Quaternionf(
                (float) rotation.x(),
                (float) rotation.y(),
                (float) rotation.z(),
                (float) rotation.w()
        ).normalize();
    }

    private record RenderProjection(Vec3 cameraRelativePosition, PhysicsQuaternion rotation) {
    }
}
