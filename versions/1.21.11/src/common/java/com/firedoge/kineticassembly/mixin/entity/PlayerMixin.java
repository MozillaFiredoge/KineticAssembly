package com.firedoge.kineticassembly.mixin.entity;

import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.minecraft.assembly.EntityAssemblyState;
import com.firedoge.kineticassembly.minecraft.assembly.ServerAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityCollision;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityCollisionAccess;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityMotor;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityOrientation;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityPositioning;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyId;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyManager;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPlotProjection;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyTransform;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin {
    @Shadow
    protected abstract boolean isStayingOnGroundSurface();

    @Shadow
    protected abstract boolean isAboveGround(float maxUpStep);

    @Shadow
    private boolean canFallAtLeast(double x, double z, double maxUpStep) {
        throw new AssertionError();
    }

    @Inject(
            method = "travel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;getDeltaMovement()Lnet/minecraft/world/phys/Vec3;",
                    ordinal = 1
            )
    )
    private void kinetic_assembly$storeAssemblyUpDeltaMovement(
            Vec3 travelVector,
            CallbackInfo ci,
            @Share("kinetic_assembly$assemblyUpDirection") LocalRef<Vec3> upDirection,
            @Share("kinetic_assembly$assemblyUpDeltaMovement") LocalRef<Vec3> upDeltaMovement
    ) {
        Player player = (Player) (Object) this;
        Vec3 up = AssemblyEntityOrientation.up(player);
        if (up == null) {
            return;
        }

        Vec3 deltaMovement = player.getDeltaMovement();
        double upVelocity = deltaMovement.x * up.x + deltaMovement.y * up.y + deltaMovement.z * up.z;
        upDirection.set(up);
        upDeltaMovement.set(up.scale(upVelocity));
    }

    @Redirect(
            method = "travel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V",
                    ordinal = 1
            )
    )
    private void kinetic_assembly$modifyTravelSetDeltaMovement(
            Player instance,
            Vec3 movement,
            @Share("kinetic_assembly$assemblyUpDirection") LocalRef<Vec3> upDirection,
            @Share("kinetic_assembly$assemblyUpDeltaMovement") LocalRef<Vec3> upDeltaMovement
    ) {
        Vec3 up = upDirection.get();
        Vec3 preservedUpMovement = upDeltaMovement.get();
        if (up == null || preservedUpMovement == null) {
            instance.setDeltaMovement(movement);
            return;
        }

        Vec3 deltaMovement = instance.getDeltaMovement();
        double currentUpVelocity = deltaMovement.x * up.x + deltaMovement.y * up.y + deltaMovement.z * up.z;
        instance.setDeltaMovement(deltaMovement.subtract(up.scale(currentUpVelocity)).add(preservedUpMovement.scale(0.6D)));
    }

    @Redirect(
            method = "aiStep",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/AABB;minmax(Lnet/minecraft/world/phys/AABB;)Lnet/minecraft/world/phys/AABB;"
            )
    )
    private AABB kinetic_assembly$fixAssemblyRidingBoundingBox(AABB playerBoundingBox, AABB vehicleBoundingBox) {
        Player player = (Player) (Object) this;
        Entity vehicle = player.getVehicle();
        if (vehicle == null) {
            return playerBoundingBox.minmax(vehicleBoundingBox);
        }

        AssemblyPlotProjection projection = AssemblyEntityPositioning.containingProjection(vehicle);
        if (projection == null) {
            return playerBoundingBox.minmax(vehicleBoundingBox);
        }

        AABB projectedVehicleBoundingBox =
                AssemblyEntityPositioning.plotAabbToWorldBounds(projection, vehicleBoundingBox);
        if (projectedVehicleBoundingBox == null) {
            return playerBoundingBox.minmax(vehicleBoundingBox);
        }
        return playerBoundingBox.minmax(projectedVehicleBoundingBox);
    }

    @Inject(method = "isWithinBlockInteractionRange", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$canInteractWithPlotBlock(BlockPos pos, double distance, CallbackInfoReturnable<Boolean> cir) {
        Player player = (Player) (Object) this;
        Level level = player.level();
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        ServerAssemblyContainer container = AssemblyContainers.server(serverLevel).orElse(null);
        if (container == null
                || !container.inPlotBounds(new ChunkPos(pos))
                || container.assemblyAtPlotBlock(pos).isEmpty()) {
            return;
        }

        cir.setReturnValue(AssemblyManager.INSTANCE.playerCanReachPlotBlock(serverLevel, serverPlayer, pos, distance));
    }

    @Inject(method = "maybeBackOffFromEdge", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$maybeBackOffFromAssemblyEdge(
            Vec3 movement,
            MoverType moverType,
            CallbackInfoReturnable<Vec3> cir
    ) {
        Player player = (Player) (Object) this;
        if (!(player instanceof AssemblyEntityCollisionAccess access)) {
            return;
        }

        AssemblyPlotProjection projection = kinetic_assembly$trackingProjection(player.level(), access);
        if (projection == null) {
            return;
        }

        float maxUpStep = player.maxUpStep();
        if (player.getAbilities().flying
                || movement.y > 0.0D
                || moverType != MoverType.SELF && moverType != MoverType.PLAYER
                || !isStayingOnGroundSurface()
                || !isAboveGround(maxUpStep)) {
            return;
        }

        HorizontalFrame frame = HorizontalFrame.from(projection.poseFrame().previousTransform());
        double xMovement = frame.worldToLocalX(movement);
        double zMovement = frame.worldToLocalZ(movement);
        double step = 0.05D;
        double xStep = Math.signum(xMovement) * step;
        double zStep = Math.signum(zMovement) * step;

        while (xMovement != 0.0D && kinetic_assembly$wouldSlideOff(xMovement, 0.0D, maxUpStep, frame)) {
            if (Math.abs(xMovement) <= step) {
                xMovement = 0.0D;
                break;
            }
            xMovement -= xStep;
        }

        while (zMovement != 0.0D && kinetic_assembly$wouldSlideOff(0.0D, zMovement, maxUpStep, frame)) {
            if (Math.abs(zMovement) <= step) {
                zMovement = 0.0D;
                break;
            }
            zMovement -= zStep;
        }

        while (xMovement != 0.0D
                && zMovement != 0.0D
                && kinetic_assembly$wouldSlideOff(xMovement, zMovement, maxUpStep, frame)) {
            if (Math.abs(xMovement) <= step) {
                xMovement = 0.0D;
            } else {
                xMovement -= xStep;
            }

            if (Math.abs(zMovement) <= step) {
                zMovement = 0.0D;
            } else {
                zMovement -= zStep;
            }
        }

        Vec3 worldMovement = frame.localToWorld(xMovement, zMovement);
        cir.setReturnValue(new Vec3(worldMovement.x, movement.y, worldMovement.z));
    }

    @Redirect(
            method = "canFallAtLeast",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;noCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z"
            )
    )
    private boolean kinetic_assembly$includeAssemblyFallSupport(Level level, Entity entity, AABB fallProbe) {
        boolean vanillaNoCollision = level.noCollision(entity, fallProbe);
        if (!vanillaNoCollision
                || !(entity instanceof Player)
                || !(entity instanceof AssemblyEntityCollisionAccess access)
                || !kinetic_assembly$hasAssemblyMovementContext(access)) {
            return vanillaNoCollision;
        }

        return !AssemblyEntityCollision.hasAssemblyFallSupport(entity, access, fallProbe);
    }

    private static boolean kinetic_assembly$hasAssemblyMovementContext(AssemblyEntityCollisionAccess access) {
        EntityAssemblyState state = access.kinetic_assembly$assemblyState();
        return state != null && state.active()
                || access.kinetic_assembly$trackingAssemblyId() != null
                || access.kinetic_assembly$plotPosition() != null;
    }

    @Unique
    private boolean kinetic_assembly$wouldSlideOff(
            double localXMovement,
            double localZMovement,
            float maxUpStep,
            HorizontalFrame frame
    ) {
        Vec3 worldMovement = frame.localToWorld(localXMovement, localZMovement);
        if (!canFallAtLeast(worldMovement.x, worldMovement.z, maxUpStep)) {
            return false;
        }

        Player player = (Player) (Object) this;
        if (!(player instanceof AssemblyEntityCollisionAccess access)) {
            return true;
        }
        return !AssemblyEntityCollision.hasAssemblyGroundSupportAfterMove(player, access, worldMovement);
    }

    @Unique
    private static AssemblyPlotProjection kinetic_assembly$trackingProjection(Level level, AssemblyEntityCollisionAccess access) {
        AssemblyId trackingId = AssemblyEntityMotor.activeTrackingAssemblyId(access);
        if (trackingId == null) {
            return null;
        }
        return AssemblyContainers.container(level)
                .flatMap(container -> container.plotProjection(trackingId))
                .orElse(null);
    }

    @Unique
    private record HorizontalFrame(Vec3 xAxis, Vec3 zAxis) {
        private static HorizontalFrame from(AssemblyTransform transform) {
            Vec3 zAxis = horizontalAxis(transform.localDirectionToWorld(new PhysicsVector(0.0D, 0.0D, 1.0D)));
            if (zAxis.lengthSqr() <= 1.0E-12D) {
                Vec3 xAxis = horizontalAxis(transform.localDirectionToWorld(new PhysicsVector(1.0D, 0.0D, 0.0D)));
                if (xAxis.lengthSqr() <= 1.0E-12D) {
                    return new HorizontalFrame(new Vec3(1.0D, 0.0D, 0.0D), new Vec3(0.0D, 0.0D, 1.0D));
                }
                xAxis = xAxis.normalize();
                return new HorizontalFrame(xAxis, new Vec3(-xAxis.z, 0.0D, xAxis.x));
            }

            zAxis = zAxis.normalize();
            return new HorizontalFrame(new Vec3(zAxis.z, 0.0D, -zAxis.x), zAxis);
        }

        private double worldToLocalX(Vec3 movement) {
            return movement.x * xAxis.x + movement.z * xAxis.z;
        }

        private double worldToLocalZ(Vec3 movement) {
            return movement.x * zAxis.x + movement.z * zAxis.z;
        }

        private Vec3 localToWorld(double x, double z) {
            return new Vec3(
                    xAxis.x * x + zAxis.x * z,
                    0.0D,
                    xAxis.z * x + zAxis.z * z
            );
        }

        private static Vec3 horizontalAxis(PhysicsVector vector) {
            return new Vec3(vector.x(), 0.0D, vector.z());
        }
    }
}
