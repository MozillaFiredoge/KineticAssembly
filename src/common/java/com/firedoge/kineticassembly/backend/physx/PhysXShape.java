package com.firedoge.kineticassembly.backend.physx;

import java.util.Objects;

import com.firedoge.kineticassembly.api.PhysicsShape;

public final class PhysXShape implements PhysicsShape {
    private final PhysXWorld world;
    private final ShapeType type;
    private final long nativeHandle;
    private boolean closed;

    PhysXShape(PhysXWorld world, ShapeType type, long nativeHandle) {
        this.world = Objects.requireNonNull(world, "world");
        this.type = Objects.requireNonNull(type, "type");
        this.nativeHandle = nativeHandle;
    }

    @Override
    public ShapeType type() {
        return type;
    }

    @Override
    public long nativeHandle() {
        return nativeHandle;
    }

    void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Physics shape is closed");
        }
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
            PhysXNative.nativeDestroyShape(nativeHandle);
        }
        world.forgetShape(this);
    }

    boolean belongsTo(PhysXWorld world) {
        return this.world == world;
    }
}
