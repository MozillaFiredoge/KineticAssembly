package com.firedoge.kineticassembly.mixin.collision;

import java.util.Optional;

import javax.annotation.Nullable;

import com.firedoge.kineticassembly.minecraft.assembly.EntityAssemblyState;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityBridge;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityCollision;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityCollisionAccess;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityMotor;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyId;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPlotProjection;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyVerticalTrace;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Entity.class, priority = 1100)
public abstract class EntityAssemblyCollisionMixin implements AssemblyEntityCollisionAccess {
    @Shadow
    public boolean horizontalCollision;
    @Shadow
    public boolean verticalCollision;
    @Shadow
    public boolean verticalCollisionBelow;
    @Shadow
    public double xo;
    @Shadow
    public double yo;
    @Shadow
    public double zo;
    @Shadow
    public double xOld;
    @Shadow
    public double yOld;
    @Shadow
    public double zOld;
    @Shadow
    private Vec3 position;
    @Shadow
    private BlockPos blockPosition;
    @Shadow
    @Nullable
    private BlockState inBlockState;
    @Shadow
    public Optional<BlockPos> mainSupportingBlockPos;

    @Shadow
    public abstract void setPos(Vec3 pos);

    @Shadow
    public abstract void moveTo(double x, double y, double z);

    @Shadow
    public abstract void setOnGround(boolean onGround);

    @Shadow
    public abstract Vec3 getDeltaMovement();

    @Shadow
    public abstract void setDeltaMovement(Vec3 movement);

    @Shadow
    protected abstract Vec3 collide(Vec3 movement);

    @Shadow
    @Nullable
    public abstract Entity getVehicle();

    @Shadow
    public abstract Level level();

    @Unique
    @Nullable
    private EntityAssemblyState kinetic_assembly$assemblyState;
    @Unique
    @Nullable
    private AssemblyId kinetic_assembly$trackingAssemblyId;
    @Unique
    @Nullable
    private Vec3 kinetic_assembly$plotPosition;
    @Unique
    @Nullable
    private AssemblyEntityCollision.CollisionResult kinetic_assembly$lastAssemblyCollision;
    @Unique
    @Nullable
    private AssemblyEntityCollision.CollisionResult kinetic_assembly$recentAssemblyCollision;
    @Unique
    @Nullable
    private Vec3 kinetic_assembly$preAssemblyCollisionDeltaMovement;
    @Unique
    private Vec3 kinetic_assembly$assemblyInheritedVelocity = Vec3.ZERO;
    @Unique
    @Nullable
    private Vec3 kinetic_assembly$recentAssemblyInheritedMotion;
    @Unique
    @Nullable
    private MoverType kinetic_assembly$currentMoveType;
    @Unique
    @Nullable
    private AssemblyId kinetic_assembly$supportBreakAssemblyId;
    @Unique
    private int kinetic_assembly$supportBreakCooldownTicks;

    @Override
    @Nullable
    public EntityAssemblyState kinetic_assembly$assemblyState() {
        return kinetic_assembly$assemblyState;
    }

    @Override
    public void kinetic_assembly$setAssemblyState(@Nullable EntityAssemblyState state) {
        kinetic_assembly$assemblyState = state;
        if (state != null && state.active() && state.id() != null) {
            kinetic_assembly$trackingAssemblyId = state.id();
            return;
        }
        kinetic_assembly$trackingAssemblyId = null;
        kinetic_assembly$plotPosition = null;
    }

    @Override
    @Nullable
    public AssemblyId kinetic_assembly$trackingAssemblyId() {
        return kinetic_assembly$trackingAssemblyId;
    }

    @Override
    public void kinetic_assembly$setTrackingAssemblyId(@Nullable AssemblyId id) {
        kinetic_assembly$trackingAssemblyId = id;
        if (id == null) {
            kinetic_assembly$assemblyState = null;
        }
    }

    @Override
    @Nullable
    public Vec3 kinetic_assembly$plotPosition() {
        return kinetic_assembly$plotPosition;
    }

    @Override
    public void kinetic_assembly$setPlotPosition(@Nullable Vec3 position) {
        kinetic_assembly$plotPosition = position;
    }

    @Override
    public void kinetic_assembly$setPosField(Vec3 position) {
        this.position = position;
    }

    @Override
    public Vec3 kinetic_assembly$vanillaCollide(Vec3 movement) {
        return collide(movement);
    }

    @Override
    @Nullable
    public AssemblyEntityCollision.CollisionResult kinetic_assembly$recentAssemblyCollision() {
        return kinetic_assembly$recentAssemblyCollision;
    }

    @Override
    @Nullable
    public Vec3 kinetic_assembly$preAssemblyCollisionDeltaMovement() {
        return kinetic_assembly$preAssemblyCollisionDeltaMovement;
    }

    @Override
    public Vec3 kinetic_assembly$assemblyInheritedVelocity() {
        return kinetic_assembly$assemblyInheritedVelocity;
    }

    @Override
    public void kinetic_assembly$setAssemblyInheritedVelocity(Vec3 motion) {
        kinetic_assembly$assemblyInheritedVelocity = AssemblyEntityMotor.isFinite(motion) ? motion : Vec3.ZERO;
    }

    @Override
    @Nullable
    public Vec3 kinetic_assembly$recentAssemblyInheritedMotion() {
        return kinetic_assembly$recentAssemblyInheritedMotion;
    }

    @Override
    public void kinetic_assembly$setRecentAssemblyInheritedMotion(@Nullable Vec3 motion) {
        kinetic_assembly$recentAssemblyInheritedMotion = AssemblyEntityMotor.isFinite(motion) ? motion : null;
    }

    @Override
    public void kinetic_assembly$clearRecentAssemblyCollision() {
        kinetic_assembly$recentAssemblyCollision = null;
        kinetic_assembly$preAssemblyCollisionDeltaMovement = null;
        kinetic_assembly$recentAssemblyInheritedMotion = null;
    }

    @Override
    @Nullable
    public AssemblyEntityCollision.CollisionResult kinetic_assembly$lastAssemblyCollision() {
        return kinetic_assembly$lastAssemblyCollision;
    }

    @Override
    public void kinetic_assembly$setLastAssemblyCollision(@Nullable AssemblyEntityCollision.CollisionResult collision) {
        kinetic_assembly$lastAssemblyCollision = collision;
    }

    @Override
    public void kinetic_assembly$setRecentAssemblyCollision(@Nullable AssemblyEntityCollision.CollisionResult collision) {
        kinetic_assembly$recentAssemblyCollision = collision;
    }

    @Override
    public void kinetic_assembly$setPreAssemblyCollisionDeltaMovement(@Nullable Vec3 movement) {
        kinetic_assembly$preAssemblyCollisionDeltaMovement = movement;
    }

    @Override
    @Nullable
    public MoverType kinetic_assembly$currentMoveType() {
        return kinetic_assembly$currentMoveType;
    }

    @Override
    public void kinetic_assembly$setCurrentMoveType(@Nullable MoverType type) {
        kinetic_assembly$currentMoveType = type;
    }

    @Override
    @Nullable
    public AssemblyId kinetic_assembly$supportBreakAssemblyId() {
        return kinetic_assembly$supportBreakAssemblyId;
    }

    @Override
    public int kinetic_assembly$supportBreakCooldownTicks() {
        return kinetic_assembly$supportBreakCooldownTicks;
    }

    @Override
    public void kinetic_assembly$setSupportBreakCooldown(@Nullable AssemblyId id, int ticks) {
        if (id == null || ticks <= 0) {
            kinetic_assembly$supportBreakAssemblyId = null;
            kinetic_assembly$supportBreakCooldownTicks = 0;
            return;
        }
        kinetic_assembly$supportBreakAssemblyId = id;
        kinetic_assembly$supportBreakCooldownTicks = ticks;
    }

    @Override
    public void kinetic_assembly$syncAssemblyOldPosition() {
        Vec3 previousPosition = AssemblyEntityCollision.previousFramePosition(kinetic_assembly$self(), kinetic_assembly$assemblyState);
        if (previousPosition == null) {
            return;
        }
        xo = previousPosition.x;
        xOld = previousPosition.x;
        yo = previousPosition.y;
        yOld = previousPosition.y;
        zo = previousPosition.z;
        zOld = previousPosition.z;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void kinetic_assembly$carryWithTrackedAssembly(CallbackInfo ci) {
        AssemblyEntityMotor.tickTracking(kinetic_assembly$self(), this);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void kinetic_assembly$updateAssemblyPlotPosition(CallbackInfo ci) {
        AssemblyEntityMotor.tickPlotPosition(kinetic_assembly$self(), this);
    }

    @Inject(method = "recreateFromPacket", at = @At("TAIL"))
    private void kinetic_assembly$recreateFromAssemblyAddPacket(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        AssemblyEntityMotor.recreateFromPacket(kinetic_assembly$self(), packet, this);
        Vec3 packetPosition = new Vec3(packet.getX(), packet.getY(), packet.getZ());
        AssemblyEntityBridge.debugAttachedEntity(
                "client-recreate",
                kinetic_assembly$self().level(),
                kinetic_assembly$self(),
                "packetPos=" + packetPosition
                        + " directProjection=" + AssemblyContainers.container(kinetic_assembly$self().level())
                        .flatMap(container -> container.plotProjection(BlockPos.containing(packetPosition)))
                        .isPresent()
                        + " adjacentProjection=" + AssemblyEntityBridge
                        .plotProjectionAtOrAdjacent(kinetic_assembly$self().level(), BlockPos.containing(packetPosition))
                        .isPresent()
                        + " trackingId=" + kinetic_assembly$trackingAssemblyId
                        + " plotPosition=" + kinetic_assembly$plotPosition
        );
    }

    /**
     * @author firedoge
     * @reason Include tracked/intersecting assembly blocks in the cached in-block state.
     */
    @Overwrite
    public BlockState getInBlockState() {
        AssemblyId trackingId = AssemblyEntityMotor.activeTrackingAssemblyId(this);
        if (inBlockState == null || trackingId != null) {
            inBlockState = level().getBlockState(blockPosition);
            if (inBlockState.isAir()) {
                BlockState assemblyBlockState = AssemblyEntityCollision.inBlockState(kinetic_assembly$self(), trackingId);
                if (assemblyBlockState != null) {
                    inBlockState = assemblyBlockState;
                }
            }
        }
        return inBlockState;
    }

    @Inject(method = "getOnPos(F)Lnet/minecraft/core/BlockPos;", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$getAssemblyOnPos(float distance, CallbackInfoReturnable<BlockPos> cir) {
        AssemblyId trackingId = AssemblyEntityMotor.activeTrackingAssemblyId(this);
        if (trackingId == null && mainSupportingBlockPos.isPresent()) {
            return;
        }

        BlockPos plotOnPos = AssemblyEntityCollision.onPos(kinetic_assembly$self(), trackingId, distance);
        if (plotOnPos != null) {
            cir.setReturnValue(plotOnPos);
        }
    }

    @Inject(
            method = "spawnSprintParticle",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
                    shift = At.Shift.AFTER
            )
    )
    private void kinetic_assembly$prepareAssemblySprintParticle(
            CallbackInfo ci,
            @Local(ordinal = 0) BlockPos blockPos,
            @Share("kinetic_assembly$assemblySprintParticlePosition") LocalRef<Vec3> localPosition
    ) {
        Vec3 feetPosition = kinetic_assembly$self().position();
        AssemblyPlotProjection projection = kinetic_assembly$plotProjection(blockPos);
        localPosition.set(projection == null ? feetPosition : projection.worldToPlot(feetPosition));
    }

    @Redirect(
            method = "spawnSprintParticle",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getX()D")
    )
    private double kinetic_assembly$sprintParticleX(
            Entity entity,
            @Share("kinetic_assembly$assemblySprintParticlePosition") LocalRef<Vec3> localPosition
    ) {
        return kinetic_assembly$sprintParticlePosition(localPosition).x;
    }

    @Redirect(
            method = "spawnSprintParticle",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getY()D")
    )
    private double kinetic_assembly$sprintParticleY(
            Entity entity,
            @Share("kinetic_assembly$assemblySprintParticlePosition") LocalRef<Vec3> localPosition
    ) {
        return kinetic_assembly$sprintParticlePosition(localPosition).y;
    }

    @Redirect(
            method = "spawnSprintParticle",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getZ()D")
    )
    private double kinetic_assembly$sprintParticleZ(
            Entity entity,
            @Share("kinetic_assembly$assemblySprintParticlePosition") LocalRef<Vec3> localPosition
    ) {
        return kinetic_assembly$sprintParticlePosition(localPosition).z;
    }

    @Redirect(
            method = "spawnSprintParticle",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"
            )
    )
    private void kinetic_assembly$addAssemblySprintParticle(
            Level instance,
            ParticleOptions particleOptions,
            double x,
            double y,
            double z,
            double xSpeed,
            double ySpeed,
            double zSpeed,
            @Local(ordinal = 0) BlockPos blockPos
    ) {
        AssemblyPlotProjection projection = kinetic_assembly$plotProjection(blockPos);
        if (projection == null) {
            instance.addParticle(particleOptions, x, y, z, xSpeed, ySpeed, zSpeed);
            return;
        }

        Vec3 plotPosition = new Vec3(x, y - 0.1D, z)
                .add(projection.worldDirectionToPlot(new Vec3(0.0D, 0.1D, 0.0D)));
        Vec3 plotSpeed = projection.worldDirectionToPlot(new Vec3(xSpeed, ySpeed, zSpeed));
        instance.addParticle(
                particleOptions,
                plotPosition.x,
                plotPosition.y,
                plotPosition.z,
                plotSpeed.x,
                plotSpeed.y,
                plotSpeed.z
        );
    }

    @Redirect(
            method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"
            )
    )
    private Vec3 kinetic_assembly$collideWithAssemblySupport(Entity instance, Vec3 requestedMotion) {
        return AssemblyEntityMotor.collide(kinetic_assembly$self(), this, requestedMotion, this::collide);
    }

    @Inject(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"))
    private void kinetic_assembly$captureMoveType(MoverType type, Vec3 movement, CallbackInfo ci) {
        kinetic_assembly$setCurrentMoveType(type);
    }

    @WrapOperation(
            method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;setOnGroundWithMovement(ZLnet/minecraft/world/phys/Vec3;)V"
            )
    )
    private void kinetic_assembly$preserveAssemblyGround(
            Entity instance,
            boolean onGround,
            Vec3 movement,
            Operation<Void> original
    ) {
        if (kinetic_assembly$lastAssemblyCollision != null && kinetic_assembly$lastAssemblyCollision.onGround()) {
            verticalCollision = true;
            verticalCollisionBelow = true;
            original.call(instance, true, movement);
            AssemblyVerticalTrace.groundWrite(
                    kinetic_assembly$self(),
                    onGround,
                    true,
                    true,
                    movement,
                    verticalCollision,
                    verticalCollisionBelow
            );
            return;
        }
        original.call(instance, onGround, movement);
        AssemblyVerticalTrace.groundWrite(
                kinetic_assembly$self(),
                onGround,
                onGround,
                false,
                movement,
                verticalCollision,
                verticalCollisionBelow
        );
    }

    @Inject(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V", at = @At("TAIL"))
    private void kinetic_assembly$clearAssemblyMoveState(MoverType type, Vec3 movement, CallbackInfo ci) {
        if (kinetic_assembly$lastAssemblyCollision != null && kinetic_assembly$lastAssemblyCollision.horizontalCollision()) {
            horizontalCollision = true;
        }
        if (!(kinetic_assembly$self() instanceof LivingEntity)
                && kinetic_assembly$lastAssemblyCollision != null
                && AssemblyEntityMotor.hasMeaningfulMotion(kinetic_assembly$lastAssemblyCollision.inheritedMotion())) {
            Vec3 inheritedMotion = collide(kinetic_assembly$lastAssemblyCollision.inheritedMotion());
            if (AssemblyEntityMotor.hasMeaningfulMotion(inheritedMotion)) {
                setPos(position.add(inheritedMotion));
            }
        }
        AssemblyVerticalTrace.moveEnd(
                kinetic_assembly$self(),
                type,
                movement,
                kinetic_assembly$lastAssemblyCollision,
                horizontalCollision,
                verticalCollision,
                verticalCollisionBelow
        );
        kinetic_assembly$setLastAssemblyCollision(null);
        kinetic_assembly$setCurrentMoveType(null);
    }

    @Unique
    private Entity kinetic_assembly$self() {
        return (Entity) (Object) this;
    }

    @Unique
    private Vec3 kinetic_assembly$sprintParticlePosition(LocalRef<Vec3> localPosition) {
        Vec3 position = localPosition.get();
        return position == null ? kinetic_assembly$self().position() : position;
    }

    @Unique
    @Nullable
    private AssemblyPlotProjection kinetic_assembly$plotProjection(BlockPos plotPos) {
        return AssemblyContainers.container(level())
                .flatMap(container -> container.plotProjection(plotPos))
                .orElse(null);
    }

}
