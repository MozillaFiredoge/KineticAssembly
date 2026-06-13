package com.firedoge.kineticassembly.backend.physx;

import java.util.Objects;

import com.firedoge.kineticassembly.api.PhysicsBody;
import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsQuaternion;
import com.firedoge.kineticassembly.api.PhysicsVector;

public final class PhysXBody implements PhysicsBody {
    private final PhysXWorld world;
    private final long nativeHandle;
    private PhysicsPose pose;
    private PhysicsVector linearVelocity = PhysicsVector.ZERO;
    private PhysicsVector angularVelocity = PhysicsVector.ZERO;
    private boolean closed;

    PhysXBody(PhysXWorld world, long nativeHandle, PhysicsPose pose) {
        this.world = Objects.requireNonNull(world, "world");
        this.nativeHandle = nativeHandle;
        this.pose = Objects.requireNonNull(pose, "pose");
    }

    @Override
    public long nativeHandle() {
        return nativeHandle;
    }

    @Override
    public PhysicsPose pose() {
        if (!closed && nativeHandle != 0L && PhysXNative.isLoaded()) {
            double[] poseData = new double[7];
            if (PhysXNative.nativeGetBodyPose(nativeHandle, poseData)) {
                pose = new PhysicsPose(
                        new PhysicsVector(poseData[0], poseData[1], poseData[2]),
                        new PhysicsQuaternion(poseData[3], poseData[4], poseData[5], poseData[6])
                );
            }
        }
        return pose;
    }

    @Override
    public void setPose(PhysicsPose pose) {
        ensureOpen();
        this.pose = Objects.requireNonNull(pose, "pose");
        if (nativeHandle != 0L && PhysXNative.isLoaded()) {
            PhysXNative.nativeSetBodyPose(
                    nativeHandle,
                    pose.position().x(),
                    pose.position().y(),
                    pose.position().z(),
                    pose.rotation().x(),
                    pose.rotation().y(),
                    pose.rotation().z(),
                    pose.rotation().w()
            );
        }
    }

    @Override
    public PhysicsVector linearVelocity() {
        if (!closed && nativeHandle != 0L && PhysXNative.isLoaded()) {
            double[] velocityData = new double[3];
            if (PhysXNative.nativeGetLinearVelocity(nativeHandle, velocityData)) {
                linearVelocity = new PhysicsVector(velocityData[0], velocityData[1], velocityData[2]);
            }
        }
        return linearVelocity;
    }

    @Override
    public void setLinearVelocity(PhysicsVector velocity) {
        ensureOpen();
        this.linearVelocity = Objects.requireNonNull(velocity, "velocity");
        if (nativeHandle != 0L && PhysXNative.isLoaded()) {
            PhysXNative.nativeSetLinearVelocity(nativeHandle, velocity.x(), velocity.y(), velocity.z());
        }
    }

    @Override
    public PhysicsVector angularVelocity() {
        if (!closed && nativeHandle != 0L && PhysXNative.isLoaded()) {
            double[] velocityData = new double[3];
            if (PhysXNative.getAngularVelocity(nativeHandle, velocityData)) {
                angularVelocity = new PhysicsVector(velocityData[0], velocityData[1], velocityData[2]);
            }
        }
        return angularVelocity;
    }

    @Override
    public void setAngularVelocity(PhysicsVector velocity) {
        ensureOpen();
        this.angularVelocity = Objects.requireNonNull(velocity, "velocity");
        if (nativeHandle != 0L && PhysXNative.isLoaded()) {
            PhysXNative.setAngularVelocity(nativeHandle, velocity.x(), velocity.y(), velocity.z());
        }
    }

    @Override
    public boolean applyLinearImpulse(PhysicsVector impulse) {
        ensureOpen();
        Objects.requireNonNull(impulse, "impulse");
        if (nativeHandle == 0L || !PhysXNative.isLoaded()) {
            return false;
        }
        boolean applied = PhysXNative.applyLinearImpulse(nativeHandle, impulse.x(), impulse.y(), impulse.z());
        if (applied) {
            linearVelocity();
        }
        return applied;
    }

    @Override
    public boolean applyAngularImpulse(PhysicsVector impulse) {
        ensureOpen();
        Objects.requireNonNull(impulse, "impulse");
        if (nativeHandle == 0L || !PhysXNative.isLoaded()) {
            return false;
        }
        boolean applied = PhysXNative.applyAngularImpulse(nativeHandle, impulse.x(), impulse.y(), impulse.z());
        if (applied) {
            angularVelocity();
        }
        return applied;
    }

    @Override
    public boolean applyImpulseAtPoint(PhysicsVector impulse, PhysicsVector point) {
        ensureOpen();
        Objects.requireNonNull(impulse, "impulse");
        Objects.requireNonNull(point, "point");
        if (nativeHandle == 0L || !PhysXNative.isLoaded()) {
            return false;
        }
        boolean applied = PhysXNative.applyImpulseAtPoint(
                nativeHandle,
                impulse.x(),
                impulse.y(),
                impulse.z(),
                point.x(),
                point.y(),
                point.z()
        );
        if (applied) {
            linearVelocity();
            angularVelocity();
        }
        return applied;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        world.destroyJointsForBody(this);
        if (nativeHandle != 0L && PhysXNative.isLoaded()) {
            PhysXNative.nativeDestroyBody(nativeHandle);
        }
        world.forgetBody(this);
    }

    boolean belongsTo(PhysXWorld world) {
        return this.world == world;
    }

    void updateCachedState(PhysicsPose pose, PhysicsVector linearVelocity, PhysicsVector angularVelocity) {
        this.pose = Objects.requireNonNull(pose, "pose");
        this.linearVelocity = Objects.requireNonNull(linearVelocity, "linearVelocity");
        this.angularVelocity = Objects.requireNonNull(angularVelocity, "angularVelocity");
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Physics body is closed");
        }
    }
}
