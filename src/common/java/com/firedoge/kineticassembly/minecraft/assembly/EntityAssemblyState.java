package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.Objects;

import javax.annotation.Nullable;

import com.firedoge.kineticassembly.api.PhysicsVector;

public record EntityAssemblyState(
        @Nullable AssemblyId id,
        long epoch,
        PhysicsVector localPosition,
        PhysicsVector localVelocity,
        PhysicsVector localAnchor,
        TrackingMode trackingMode,
        int graceTicks
) {
    public static final EntityAssemblyState NONE = new EntityAssemblyState(
            null,
            -1L,
            PhysicsVector.ZERO,
            PhysicsVector.ZERO,
            PhysicsVector.ZERO,
            TrackingMode.NONE,
            0
    );

    public EntityAssemblyState {
        Objects.requireNonNull(localPosition, "localPosition");
        Objects.requireNonNull(localVelocity, "localVelocity");
        Objects.requireNonNull(localAnchor, "localAnchor");
        Objects.requireNonNull(trackingMode, "trackingMode");
        graceTicks = Math.max(0, graceTicks);
        if (id == null || trackingMode == TrackingMode.NONE) {
            id = null;
            epoch = -1L;
            localPosition = PhysicsVector.ZERO;
            localVelocity = PhysicsVector.ZERO;
            localAnchor = PhysicsVector.ZERO;
            trackingMode = TrackingMode.NONE;
            graceTicks = 0;
        } else if (epoch < 0L) {
            throw new IllegalArgumentException("epoch must be non-negative for active assembly state");
        }
    }

    public static EntityAssemblyState supported(
            AssemblyPoseFrame frame,
            PhysicsVector localAnchor,
            int graceTicks
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(localAnchor, "localAnchor");
        return new EntityAssemblyState(
                frame.id(),
                frame.epoch(),
                localAnchor,
                PhysicsVector.ZERO,
                localAnchor,
                TrackingMode.SUPPORTED,
                graceTicks
        );
    }

    public static EntityAssemblyState localPacket(
            AssemblyPoseFrame frame,
            PhysicsVector localPosition,
            int graceTicks
    ) {
        return localPacket(frame, localPosition, localPosition, graceTicks);
    }

    public static EntityAssemblyState localPacket(
            AssemblyPoseFrame frame,
            PhysicsVector localPosition,
            PhysicsVector localAnchor,
            int graceTicks
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(localPosition, "localPosition");
        Objects.requireNonNull(localAnchor, "localAnchor");
        return new EntityAssemblyState(
                frame.id(),
                frame.epoch(),
                localPosition,
                PhysicsVector.ZERO,
                localAnchor,
                TrackingMode.PACKET_LOCAL,
                graceTicks
        );
    }

    public boolean active() {
        return id != null && trackingMode != TrackingMode.NONE;
    }

    public EntityAssemblyState withFrame(AssemblyPoseFrame frame) {
        Objects.requireNonNull(frame, "frame");
        if (!active() || !frame.id().equals(id)) {
            return NONE;
        }
        return new EntityAssemblyState(
                id,
                frame.epoch(),
                localPosition,
                localVelocity,
                localAnchor,
                trackingMode,
                graceTicks
        );
    }

    public EntityAssemblyState withLocalPosition(PhysicsVector localPosition) {
        Objects.requireNonNull(localPosition, "localPosition");
        if (!active()) {
            return NONE;
        }
        return new EntityAssemblyState(
                id,
                epoch,
                localPosition,
                localVelocity,
                localAnchor,
                trackingMode,
                graceTicks
        );
    }

    public EntityAssemblyState withLocalAnchor(PhysicsVector localAnchor) {
        Objects.requireNonNull(localAnchor, "localAnchor");
        if (!active()) {
            return NONE;
        }
        return new EntityAssemblyState(
                id,
                epoch,
                localPosition,
                localVelocity,
                localAnchor,
                trackingMode,
                graceTicks
        );
    }

    @Nullable
    public EntityAssemblyState decayGrace() {
        if (!active()) {
            return null;
        }
        int remaining = graceTicks - 1;
        return remaining < 0
                ? null
                : new EntityAssemblyState(id, epoch, localPosition, localVelocity, localAnchor, trackingMode, remaining);
    }

    public enum TrackingMode {
        NONE,
        SUPPORTED,
        PACKET_LOCAL
    }
}
