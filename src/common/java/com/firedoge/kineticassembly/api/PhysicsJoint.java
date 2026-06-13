package com.firedoge.kineticassembly.api;

public interface PhysicsJoint extends AutoCloseable {
    long nativeHandle();

    PhysicsJointType type();

    PhysicsBody firstBody();

    PhysicsBody secondBody();

    boolean isClosed();

    @Override
    void close();
}
