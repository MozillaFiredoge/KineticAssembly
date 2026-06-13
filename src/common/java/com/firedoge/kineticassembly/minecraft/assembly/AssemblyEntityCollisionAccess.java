package com.firedoge.kineticassembly.minecraft.assembly;

import javax.annotation.Nullable;

import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

public interface AssemblyEntityCollisionAccess {
    @Nullable
    EntityAssemblyState kinetic_assembly$assemblyState();

    void kinetic_assembly$setAssemblyState(@Nullable EntityAssemblyState state);

    @Nullable
    AssemblyId kinetic_assembly$trackingAssemblyId();

    void kinetic_assembly$setTrackingAssemblyId(@Nullable AssemblyId id);

    @Nullable
    Vec3 kinetic_assembly$plotPosition();

    void kinetic_assembly$setPlotPosition(@Nullable Vec3 position);

    default void kinetic_assembly$plotLerpTo(Vec3 position, int lerpSteps) {
        kinetic_assembly$setPlotPosition(position);
    }

    void kinetic_assembly$setPosField(Vec3 position);

    default Vec3 kinetic_assembly$vanillaCollide(Vec3 movement) {
        return movement;
    }

    void kinetic_assembly$syncAssemblyOldPosition();

    @Nullable
    default AssemblyEntityCollision.CollisionResult kinetic_assembly$recentAssemblyCollision() {
        return null;
    }

    @Nullable
    default AssemblyEntityCollision.CollisionResult kinetic_assembly$lastAssemblyCollision() {
        return null;
    }

    default void kinetic_assembly$setLastAssemblyCollision(@Nullable AssemblyEntityCollision.CollisionResult collision) {
    }

    default void kinetic_assembly$setRecentAssemblyCollision(@Nullable AssemblyEntityCollision.CollisionResult collision) {
    }

    @Nullable
    default Vec3 kinetic_assembly$preAssemblyCollisionDeltaMovement() {
        return null;
    }

    default void kinetic_assembly$setPreAssemblyCollisionDeltaMovement(@Nullable Vec3 movement) {
    }

    default Vec3 kinetic_assembly$assemblyInheritedVelocity() {
        return Vec3.ZERO;
    }

    default void kinetic_assembly$setAssemblyInheritedVelocity(Vec3 motion) {
    }

    @Nullable
    default Vec3 kinetic_assembly$recentAssemblyInheritedMotion() {
        return null;
    }

    default void kinetic_assembly$setRecentAssemblyInheritedMotion(@Nullable Vec3 motion) {
    }

    default void kinetic_assembly$clearRecentAssemblyCollision() {
    }

    @Nullable
    default MoverType kinetic_assembly$currentMoveType() {
        return null;
    }

    default void kinetic_assembly$setCurrentMoveType(@Nullable MoverType type) {
    }

    @Nullable
    default AssemblyId kinetic_assembly$supportBreakAssemblyId() {
        return null;
    }

    default int kinetic_assembly$supportBreakCooldownTicks() {
        return 0;
    }

    default void kinetic_assembly$setSupportBreakCooldown(@Nullable AssemblyId id, int ticks) {
    }
}
