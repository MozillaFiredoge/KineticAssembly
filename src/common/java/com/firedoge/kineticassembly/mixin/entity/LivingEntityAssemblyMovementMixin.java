package com.firedoge.kineticassembly.mixin.entity;

import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityOrientation;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityPositioning;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPlotProjection;
import com.firedoge.kineticassembly.platform.PlatformServices;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityAssemblyMovementMixin extends Entity {
    @Shadow
    protected abstract float getJumpPower();

    protected LivingEntityAssemblyMovementMixin(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    @WrapOperation(
            method = "dismountVehicle",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;dismountTo(DDD)V"
            )
    )
    private void kinetic_assembly$projectAssemblyDismountPosition(
            LivingEntity instance,
            double x,
            double y,
            double z,
            Operation<Void> original
    ) {
        Vec3 projected = AssemblyEntityPositioning.projectOutOfAssembly(instance.level(), new Vec3(x, y, z));
        original.call(instance, projected.x, projected.y, projected.z);
    }

    @Redirect(
            method = "dismountVehicle",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/Math;max(DD)D"
            )
    )
    private double kinetic_assembly$projectAssemblyVehicleAltitude(double first, double second, @Local(argsOnly = true) Entity vehicle) {
        AssemblyPlotProjection projection = AssemblyEntityPositioning.containingProjection(vehicle);
        if (projection == null) {
            return Math.max(first, second);
        }

        Vec3 projectedVehiclePosition = projection.plotToWorld(vehicle.position());
        if (!Double.isFinite(projectedVehiclePosition.y)) {
            return Math.max(first, second);
        }
        return Math.max(getY(), projectedVehiclePosition.y);
    }

    @Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$jumpFromAssemblyGround(CallbackInfo ci) {
        Vec3 up = AssemblyEntityOrientation.up(this);
        if (up == null) {
            return;
        }

        float power = getJumpPower();
        if (!(power <= 1.0E-5F)) {
            Vec3 movement = getDeltaMovement();
            double upVelocity = movement.x * up.x + movement.y * up.y + movement.z * up.z;
            setDeltaMovement(movement.subtract(up.scale(upVelocity)).add(up.scale(power)));

            if (isSprinting()) {
                float yawRadians = getYRot() * (float) (Math.PI / 180.0D);
                Vec3 localSprintImpulse = new Vec3(
                        -Mth.sin(yawRadians) * 0.2D,
                        0.0D,
                        Mth.cos(yawRadians) * 0.2D
                );
                Vec3 sprintImpulse = AssemblyEntityOrientation.localToWorld(this, localSprintImpulse);
                if (sprintImpulse != null) {
                    addDeltaMovement(sprintImpulse);
                }
            }

            hasImpulse = true;
            PlatformServices.services().onLivingJump((LivingEntity) (Object) this);
        }

        ci.cancel();
    }
}
