package com.firedoge.kineticassembly.backend.physx;

import java.util.Objects;

import com.firedoge.kineticassembly.api.PhysicsDeformableVolume;

public final class PhysXDeformableVolume implements PhysicsDeformableVolume {
    private final PhysXWorld world;
    private final long nativeHandle;
    private final int collisionVertexCount;
    private final int collisionTetrahedronCount;
    private final int simulationVertexCount;
    private final int simulationTetrahedronCount;
    private boolean closed;

    PhysXDeformableVolume(PhysXWorld world, long nativeHandle, int[] info) {
        this.world = Objects.requireNonNull(world, "world");
        this.nativeHandle = nativeHandle;
        this.collisionVertexCount = info[0];
        this.collisionTetrahedronCount = info[1];
        this.simulationVertexCount = info[2];
        this.simulationTetrahedronCount = info[3];
    }

    @Override
    public long nativeHandle() {
        return nativeHandle;
    }

    @Override
    public int collisionVertexCount() {
        return collisionVertexCount;
    }

    @Override
    public int collisionTetrahedronCount() {
        return collisionTetrahedronCount;
    }

    @Override
    public int simulationVertexCount() {
        return simulationVertexCount;
    }

    @Override
    public int simulationTetrahedronCount() {
        return simulationTetrahedronCount;
    }

    @Override
    public double[] collisionVertexPositions() {
        ensureOpen();
        double[] positions = new double[collisionVertexCount * 3];
        if (nativeHandle == 0L || !PhysXNative.isLoaded()) {
            return positions;
        }
        int copied = PhysXNative.nativeGetDeformableVolumeVertices(nativeHandle, positions, collisionVertexCount);
        if (copied == collisionVertexCount) {
            return positions;
        }
        double[] partial = new double[Math.max(0, copied) * 3];
        System.arraycopy(positions, 0, partial, 0, partial.length);
        return partial;
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
            PhysXNative.nativeDestroyDeformableVolume(nativeHandle);
        }
        world.forgetDeformableVolume(this);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Deformable volume is closed");
        }
    }
}
