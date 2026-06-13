package com.firedoge.kineticassembly.mixin.render;

import com.firedoge.kineticassembly.api.PhysicsQuaternion;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPlotProjection;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyVectors;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherAssemblyRotationMixin {
    @Unique
    private boolean kinetic_assembly$assemblyRotated;

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V",
                    shift = At.Shift.AFTER,
                    ordinal = 0
            )
    )
    private <E extends Entity> void kinetic_assembly$rotateTrackedAssemblyEntity(
            E entity,
            double x,
            double y,
            double z,
            float yaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            CallbackInfo ci
    ) {
        AssemblyPlotProjection projection = kinetic_assembly$trackingProjection(entity);
        if (projection == null) {
            return;
        }

        Vec3 renderEyePosition = kinetic_assembly$trackedRenderEyePosition(entity, projection, partialTick);
        Vec3 eyeOffsetFromVanilla = renderEyePosition.subtract(entity.getEyePosition(partialTick));
        if (!AssemblyVectors.finite(eyeOffsetFromVanilla)) {
            return;
        }

        Vec3 entityEyeOffset = entity.getEyePosition().subtract(entity.position());
        poseStack.pushPose();
        poseStack.translate(eyeOffsetFromVanilla.x, eyeOffsetFromVanilla.y, eyeOffsetFromVanilla.z);
        poseStack.translate(entityEyeOffset.x, entityEyeOffset.y, entityEyeOffset.z);
        poseStack.mulPose(kinetic_assembly$toJomlQuaternion(projection.poseFrame().interpolatedPose(partialTick).rotation()));
        poseStack.translate(-entityEyeOffset.x, -entityEyeOffset.y, -entityEyeOffset.z);
        kinetic_assembly$assemblyRotated = true;
    }

    @Inject(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;isInvisible()Z")
    )
    private <E extends Entity> void kinetic_assembly$popAssemblyEntityRotationBeforeShadow(
            E entity,
            double x,
            double y,
            double z,
            float yaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            CallbackInfo ci
    ) {
        kinetic_assembly$popAssemblyEntityRotation(poseStack);
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V",
                    shift = At.Shift.BEFORE
            )
    )
    private <E extends Entity> void kinetic_assembly$popAssemblyEntityRotationAtEnd(
            E entity,
            double x,
            double y,
            double z,
            float yaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            CallbackInfo ci
    ) {
        kinetic_assembly$popAssemblyEntityRotation(poseStack);
    }

    @Unique
    private void kinetic_assembly$popAssemblyEntityRotation(PoseStack poseStack) {
        if (kinetic_assembly$assemblyRotated) {
            poseStack.popPose();
            kinetic_assembly$assemblyRotated = false;
        }
    }

    @Unique
    private static AssemblyPlotProjection kinetic_assembly$trackingProjection(Entity entity) {
        // Tracking a moving assembly should affect render position, not automatically rotate
        // the entity model. Full orientation is reserved for an explicit custom-orientation
        // source such as a future pilot/vehicle controller.
        return null;
    }

    @Unique
    private static Vec3 kinetic_assembly$trackedRenderEyePosition(
            Entity entity,
            AssemblyPlotProjection projection,
            float partialTick
    ) {
        Vec3 previousEyePosition = new Vec3(entity.xo, entity.yo + entity.getEyeHeight(), entity.zo);
        Vec3 currentEyePosition = new Vec3(entity.getX(), entity.getY() + entity.getEyeHeight(), entity.getZ());
        return projection.trackedRenderPosition(previousEyePosition, currentEyePosition, partialTick);
    }

    @Unique
    private static Quaternionf kinetic_assembly$toJomlQuaternion(PhysicsQuaternion rotation) {
        return new Quaternionf(
                (float) rotation.x(),
                (float) rotation.y(),
                (float) rotation.z(),
                (float) rotation.w()
        ).normalize();
    }
}
