package com.firedoge.kineticassembly.backend.physx;

import java.util.Objects;

import com.firedoge.kineticassembly.api.PhysicsBody;
import com.firedoge.kineticassembly.api.PhysicsJoint;
import com.firedoge.kineticassembly.api.PhysicsJointType;

final class PhysXJoint implements PhysicsJoint {
    private final PhysXWorld world;
    private final PhysicsJointType type;
    private final long nativeHandle;
    private final PhysXBody firstBody;
    private final PhysXBody secondBody;
    private boolean closed;

    PhysXJoint(PhysXWorld world, PhysicsJointType type, long nativeHandle, PhysXBody firstBody, PhysXBody secondBody) {
        this.world = Objects.requireNonNull(world, "world");
        this.type = Objects.requireNonNull(type, "type");
        this.nativeHandle = nativeHandle;
        this.firstBody = Objects.requireNonNull(firstBody, "firstBody");
        this.secondBody = Objects.requireNonNull(secondBody, "secondBody");
    }

    @Override
    public long nativeHandle() {
        return nativeHandle;
    }

    @Override
    public PhysicsJointType type() {
        return type;
    }

    @Override
    public PhysicsBody firstBody() {
        return firstBody;
    }

    @Override
    public PhysicsBody secondBody() {
        return secondBody;
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
        if (nativeHandle != 0L && PhysXNative.isLoaded()) {
            PhysXNative.destroyJoint(nativeHandle);
        }
        world.forgetJoint(this);
    }

    boolean belongsTo(PhysXWorld world) {
        return this.world == world;
    }

    boolean references(PhysXBody body) {
        return firstBody == body || secondBody == body;
    }
}
