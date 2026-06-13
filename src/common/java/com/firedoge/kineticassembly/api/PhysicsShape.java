package com.firedoge.kineticassembly.api;

public interface PhysicsShape extends AutoCloseable {
    ShapeType type();

    long nativeHandle();

    boolean isClosed();

    @Override
    void close();

    enum ShapeType {
        PLANE,
        BOX,
        SPHERE,
        CAPSULE,
        CONVEX_MESH,
        TRIANGLE_MESH,
        HEIGHT_FIELD
    }
}
