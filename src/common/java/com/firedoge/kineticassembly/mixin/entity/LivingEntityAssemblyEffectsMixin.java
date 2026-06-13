package com.firedoge.kineticassembly.mixin.entity;

import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityCollision;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityCollisionAccess;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyBlockTags;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyVectors;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityAssemblyEffectsMixin extends Entity {
    @Unique
    private static final double KINETIC_ASSEMBLY_INHERITED_VELOCITY_THRESHOLD_SQR = 2.5E-7D;

    @Shadow
    public abstract LivingEntity.Fallsounds getFallSounds();

    protected LivingEntityAssemblyEffectsMixin(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    @WrapOperation(
            method = "checkFallDamage",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;blockPosition()Lnet/minecraft/core/BlockPos;"
            )
    )
    private BlockPos kinetic_assembly$fallDamageParticlesPosition(
            LivingEntity instance,
            Operation<BlockPos> original,
            @Local(argsOnly = true) BlockPos blockPos
    ) {
        if (AssemblyEntityCollision.isPlotBlock(instance.level(), blockPos)) {
            return blockPos;
        }
        return original.call(instance);
    }

    @Inject(method = "travel", at = @At("RETURN"))
    private void kinetic_assembly$applyAssemblyCollisionEffects(Vec3 travelVector, CallbackInfo ci) {
        if (isSpectator() || !((Object) this instanceof AssemblyEntityCollisionAccess access)) {
            return;
        }

        try {
            boolean serverPlayer = (Object) this instanceof ServerPlayer;
            Vec3 recentInheritedMotion = access.kinetic_assembly$recentAssemblyInheritedMotion();
            boolean hasRecentInheritedMotion = kinetic_assembly$hasMeaningfulInheritedVelocity(recentInheritedMotion);
            if (hasRecentInheritedMotion && !serverPlayer) {
                Vec3 inheritedCollisionMotion = access.kinetic_assembly$vanillaCollide(recentInheritedMotion);
                if (kinetic_assembly$hasMeaningfulInheritedVelocity(inheritedCollisionMotion)) {
                    setPos(position().add(inheritedCollisionMotion));
                }
                access.kinetic_assembly$setAssemblyInheritedVelocity(recentInheritedMotion);
            } else if (serverPlayer) {
                access.kinetic_assembly$setAssemblyInheritedVelocity(Vec3.ZERO);
            }

            Vec3 inheritedVelocity = access.kinetic_assembly$assemblyInheritedVelocity();
            if (!kinetic_assembly$hasMeaningfulInheritedVelocity(inheritedVelocity)) {
                inheritedVelocity = Vec3.ZERO;
                access.kinetic_assembly$setAssemblyInheritedVelocity(Vec3.ZERO);
            }

            AssemblyEntityCollision.CollisionResult collision = access.kinetic_assembly$recentAssemblyCollision();
            Vec3 preDeltaMovement = access.kinetic_assembly$preAssemblyCollisionDeltaMovement();
            if (preDeltaMovement == null) {
                preDeltaMovement = getDeltaMovement();
            }

            if (collision != null) {
                for (AssemblyEntityCollision.FirstCollisionInfo info : collision.firstCollisions().values()) {
                    if (collision.state() != null && info.id().equals(collision.state().id())) {
                        continue;
                    }
                    if (info.horizontal()) {
                        kinetic_assembly$applyHorizontalCollisionEffect(
                                info,
                                preDeltaMovement,
                                inheritedVelocity,
                                access.kinetic_assembly$trackingAssemblyId() != null
                        );
                    }
                }
            }

            if (!serverPlayer
                    && !hasRecentInheritedMotion
                    && access.kinetic_assembly$assemblyInheritedVelocity().lengthSqr() > KINETIC_ASSEMBLY_INHERITED_VELOCITY_THRESHOLD_SQR) {
                kinetic_assembly$applyInheritedVelocityDrag(access, collision);
            }
        } finally {
            access.kinetic_assembly$clearRecentAssemblyCollision();
        }
    }

    @Unique
    private void kinetic_assembly$applyHorizontalCollisionEffect(
            AssemblyEntityCollision.FirstCollisionInfo info,
            Vec3 preDeltaMovement,
            Vec3 inheritedVelocity,
            boolean trackingAssembly
    ) {
        Vec3 direction = info.worldDirection().normalize();
        if (direction.lengthSqr() <= 1.0E-12D) {
            return;
        }

        Vec3 playerVelocity = preDeltaMovement.add(inheritedVelocity);
        Vec3 relativeVelocityIntoEntity = info.pointVelocity().subtract(playerVelocity);
        double impactSpeed = direction.dot(relativeVelocityIntoEntity);
        if (impactSpeed <= 3.0D / 20.0D) {
            return;
        }

        if (info.blockState().is(AssemblyBlockTags.BOUNCY)) {
            Vec3 bounce = direction.scale(impactSpeed * 0.65D);
            if (!trackingAssembly) {
                bounce = bounce.add(info.pointVelocity());
            }
            setDeltaMovement(getDeltaMovement().add(bounce));
            playSound(info.blockState().getSoundType().getFallSound(), 0.75F, 1.0F);
            return;
        }

        float damageAmount = (float) (impactSpeed * 12.0D - 8.0D);
        if (damageAmount <= 0.0F) {
            return;
        }

        playSound(damageAmount > 4.0F ? getFallSounds().big() : getFallSounds().small(), 1.0F, 1.0F);
        hurt(damageSources().flyIntoWall(), damageAmount);
    }

    @Unique
    private void kinetic_assembly$applyInheritedVelocityDrag(
            AssemblyEntityCollisionAccess access,
            AssemblyEntityCollision.CollisionResult collision
    ) {
        Vec3 velocity = access.kinetic_assembly$assemblyInheritedVelocity();
        if (verticalCollision || onGround()) {
            velocity = new Vec3(velocity.x * 0.7D, 0.0D, velocity.z * 0.7D);
        }

        if (horizontalCollision || kinetic_assembly$hasHorizontalAssemblyCollision(collision)) {
            velocity = new Vec3(velocity.x * 0.8D, velocity.y * 0.6D, velocity.z * 0.8D);
        }

        if ((Object) this instanceof Player player && player.getAbilities().flying) {
            velocity = velocity.scale(0.9D);
        }

        if (isInWater()) {
            velocity = velocity.scale(0.9D);
        }

        velocity = velocity.scale(0.99D);
        if (Math.abs(velocity.y) < 0.01D) {
            velocity = new Vec3(velocity.x, 0.0D, velocity.z);
        }
        if (!kinetic_assembly$hasMeaningfulInheritedVelocity(velocity)) {
            velocity = Vec3.ZERO;
        }
        access.kinetic_assembly$setAssemblyInheritedVelocity(velocity);
    }

    @Unique
    private static boolean kinetic_assembly$hasHorizontalAssemblyCollision(AssemblyEntityCollision.CollisionResult collision) {
        if (collision == null) {
            return false;
        }
        for (AssemblyEntityCollision.FirstCollisionInfo info : collision.firstCollisions().values()) {
            if (info.horizontal()) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private static boolean kinetic_assembly$hasMeaningfulInheritedVelocity(Vec3 velocity) {
        return velocity != null
                && velocity.lengthSqr() > KINETIC_ASSEMBLY_INHERITED_VELOCITY_THRESHOLD_SQR
                && AssemblyVectors.finite(velocity);
    }
}
