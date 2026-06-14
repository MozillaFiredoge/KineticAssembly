package com.firedoge.kineticassembly.minecraft.scene;

import java.util.Objects;

import com.firedoge.kineticassembly.api.PhysicsBody;
import com.firedoge.kineticassembly.api.PhysicsBodyState;
import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsShape;
import com.firedoge.kineticassembly.api.PhysicsVector;

final class RigidPhysicsObject implements PhysicsObject {
    private final ServerPhysicsScene scene;
    private final PhysicsObjectId id;
    private final PhysicsObjectType type;
    private final PhysicsBody body;
    private final PhysicsShape ownedShape;
    private boolean closed;

    RigidPhysicsObject(ServerPhysicsScene scene, PhysicsObjectId id, PhysicsObjectType type, PhysicsBody body, PhysicsShape ownedShape) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.body = Objects.requireNonNull(body, "body");
        this.ownedShape = ownedShape;
    }

    @Override
    public PhysicsObjectId id() {
        return id;
    }

    @Override
    public PhysicsObjectType type() {
        return type;
    }

    @Override
    public PhysicsPose pose() {
        return body.pose();
    }

    @Override
    public void setPose(PhysicsPose pose) {
        body.setPose(pose);
    }

    @Override
    public PhysicsVector linearVelocity() {
        return body.linearVelocity();
    }

    @Override
    public void setLinearVelocity(PhysicsVector velocity) {
        body.setLinearVelocity(velocity);
    }

    @Override
    public PhysicsVector angularVelocity() {
        return body.angularVelocity();
    }

    @Override
    public void setAngularVelocity(PhysicsVector velocity) {
        body.setAngularVelocity(velocity);
    }

    @Override
    public boolean applyLinearImpulse(PhysicsVector impulse) {
        return body.applyLinearImpulse(impulse);
    }

    @Override
    public boolean applyAngularImpulse(PhysicsVector impulse) {
        return body.applyAngularImpulse(impulse);
    }

    @Override
    public boolean applyImpulseAtPoint(PhysicsVector impulse, PhysicsVector point) {
        return body.applyImpulseAtPoint(impulse, point);
    }

    @Override
    public boolean applyForce(PhysicsVector force) {
        return body.applyForce(force);
    }

    @Override
    public boolean applyTorque(PhysicsVector torque) {
        return body.applyTorque(torque);
    }

    @Override
    public boolean applyForceAtPoint(PhysicsVector force, PhysicsVector point) {
        return body.applyForceAtPoint(force, point);
    }

    @Override
    public PhysicsObjectSnapshot snapshot() {
        return new PhysicsObjectSnapshot(id, type, pose(), linearVelocity(), angularVelocity(), closed);
    }

    PhysicsObjectSnapshot snapshot(PhysicsBodyState state) {
        Objects.requireNonNull(state, "state");
        if (state.body() != body) {
            throw new IllegalArgumentException("Body state does not belong to this object");
        }
        return new PhysicsObjectSnapshot(id, type, state.pose(), state.linearVelocity(), state.angularVelocity(), closed);
    }

    PhysicsBody body() {
        return body;
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
        scene.removeJointsForObject(id);
        body.close();
        if (ownedShape != null) {
            ownedShape.close();
        }
        scene.forgetObject(id);
    }
}
