package com.firedoge.kineticassembly.api;

public interface PhysicsBody extends AutoCloseable {
    long nativeHandle();

    PhysicsPose pose();

    void setPose(PhysicsPose pose);

    PhysicsVector linearVelocity();

    void setLinearVelocity(PhysicsVector velocity);

    PhysicsVector angularVelocity();

    void setAngularVelocity(PhysicsVector velocity);

    boolean applyLinearImpulse(PhysicsVector impulse);

    boolean applyAngularImpulse(PhysicsVector impulse);

    boolean applyImpulseAtPoint(PhysicsVector impulse, PhysicsVector point);

    boolean isClosed();

    @Override
    void close();
}
