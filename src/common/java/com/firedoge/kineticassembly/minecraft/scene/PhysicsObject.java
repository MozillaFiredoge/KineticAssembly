package com.firedoge.kineticassembly.minecraft.scene;

import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsVector;

public interface PhysicsObject extends AutoCloseable {
    PhysicsObjectId id();

    PhysicsObjectType type();

    PhysicsPose pose();

    void setPose(PhysicsPose pose);

    PhysicsVector linearVelocity();

    void setLinearVelocity(PhysicsVector velocity);

    PhysicsVector angularVelocity();

    void setAngularVelocity(PhysicsVector velocity);

    boolean applyLinearImpulse(PhysicsVector impulse);

    boolean applyAngularImpulse(PhysicsVector impulse);

    boolean applyImpulseAtPoint(PhysicsVector impulse, PhysicsVector point);

    PhysicsObjectSnapshot snapshot();

    boolean isClosed();

    @Override
    void close();
}
