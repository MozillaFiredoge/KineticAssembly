package com.firedoge.kineticassembly.api;

public interface PhysicsDeformableVolume extends AutoCloseable {
    long nativeHandle();

    int collisionVertexCount();

    int collisionTetrahedronCount();

    int simulationVertexCount();

    int simulationTetrahedronCount();

    double[] collisionVertexPositions();

    boolean isClosed();

    @Override
    void close();
}
