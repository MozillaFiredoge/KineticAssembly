package com.firedoge.kineticassembly.minecraft.scene;

import java.util.Objects;

import com.firedoge.kineticassembly.api.PhysicsJoint;

final class RigidPhysicsJoint implements AutoCloseable {
    private final ServerPhysicsScene scene;
    private final PhysicsJointId id;
    private final PhysicsObjectId firstBodyId;
    private final PhysicsObjectId secondBodyId;
    private final PhysicsJoint joint;
    private boolean closed;

    RigidPhysicsJoint(
            ServerPhysicsScene scene,
            PhysicsJointId id,
            PhysicsObjectId firstBodyId,
            PhysicsObjectId secondBodyId,
            PhysicsJoint joint
    ) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.id = Objects.requireNonNull(id, "id");
        this.firstBodyId = Objects.requireNonNull(firstBodyId, "firstBodyId");
        this.secondBodyId = Objects.requireNonNull(secondBodyId, "secondBodyId");
        this.joint = Objects.requireNonNull(joint, "joint");
    }

    PhysicsJointId id() {
        return id;
    }

    PhysicsObjectId firstBodyId() {
        return firstBodyId;
    }

    PhysicsObjectId secondBodyId() {
        return secondBodyId;
    }

    boolean references(PhysicsObjectId objectId) {
        return firstBodyId.equals(objectId) || secondBodyId.equals(objectId);
    }

    PhysicsJointSnapshot snapshot() {
        return new PhysicsJointSnapshot(id, joint.type(), firstBodyId, secondBodyId, closed || joint.isClosed());
    }

    boolean isClosed() {
        return closed || joint.isClosed();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        joint.close();
        scene.forgetJoint(id);
    }
}
