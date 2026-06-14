package com.firedoge.kineticassembly.mechanics;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.minecraft.scene.ServerPhysicsRuntime;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

public final class ServerMechanicsApi implements MechanicsApi {
    public static final ServerMechanicsApi INSTANCE = new ServerMechanicsApi();

    private ServerMechanicsApi() {
    }

    @Override
    public MechanicsCapabilities capabilities() {
        return ServerPhysicsRuntime.INSTANCE.mechanicsCapabilities();
    }

    @Override
    public MechanicsWorld world(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        ServerPhysicsRuntime.INSTANCE.sceneFor(level);
        return new RuntimeMechanicsWorld(level);
    }

    @Override
    public Optional<MechanicsWorld> existingWorld(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        return ServerPhysicsRuntime.INSTANCE.existingScene(level)
                .map(ignored -> new RuntimeMechanicsWorld(level));
    }

    @Override
    public AutoCloseable addTickListener(MechanicsTickPhase phase, MechanicsTickListener listener) {
        return ServerPhysicsRuntime.INSTANCE.addMechanicsTickListener(phase, listener);
    }

    private record RuntimeMechanicsWorld(ServerLevel level) implements MechanicsWorld {
        private RuntimeMechanicsWorld {
            Objects.requireNonNull(level, "level");
        }

        @Override
        public ResourceKey<Level> levelKey() {
            return level.dimension();
        }

        @Override
        public MechanicsBodySnapshot createDynamicBox(MechanicsBoxDefinition definition) {
            return ServerPhysicsRuntime.INSTANCE.createMechanicsDynamicBox(level, definition);
        }

        @Override
        public MechanicsBodySnapshot createDynamicBox(MechanicsBodyId id, MechanicsBoxDefinition definition) {
            return ServerPhysicsRuntime.INSTANCE.createMechanicsDynamicBox(level, id, definition);
        }

        @Override
        public MechanicsBodySnapshot createDynamicBox(MechanicsOwner owner, MechanicsBoxDefinition definition) {
            return ServerPhysicsRuntime.INSTANCE.createMechanicsDynamicBox(level, owner, definition);
        }

        @Override
        public MechanicsBodySnapshot createDynamicBox(MechanicsOwner owner, MechanicsBodyId id, MechanicsBoxDefinition definition) {
            return ServerPhysicsRuntime.INSTANCE.createMechanicsDynamicBox(level, owner, id, definition);
        }

        @Override
        public MechanicsBodySnapshot createDynamicCompoundBox(MechanicsCompoundBoxDefinition definition) {
            return ServerPhysicsRuntime.INSTANCE.createMechanicsDynamicCompoundBox(level, definition);
        }

        @Override
        public MechanicsBodySnapshot createDynamicCompoundBox(MechanicsBodyId id, MechanicsCompoundBoxDefinition definition) {
            return ServerPhysicsRuntime.INSTANCE.createMechanicsDynamicCompoundBox(level, id, definition);
        }

        @Override
        public MechanicsBodySnapshot createDynamicCompoundBox(MechanicsOwner owner, MechanicsCompoundBoxDefinition definition) {
            return ServerPhysicsRuntime.INSTANCE.createMechanicsDynamicCompoundBox(level, owner, definition);
        }

        @Override
        public MechanicsBodySnapshot createDynamicCompoundBox(
                MechanicsOwner owner,
                MechanicsBodyId id,
                MechanicsCompoundBoxDefinition definition
        ) {
            return ServerPhysicsRuntime.INSTANCE.createMechanicsDynamicCompoundBox(level, owner, id, definition);
        }

        @Override
        public MechanicsJointSnapshot createFixedJoint(MechanicsBodyId firstBodyId, MechanicsBodyId secondBodyId, boolean collideConnected) {
            return ServerPhysicsRuntime.INSTANCE.createMechanicsFixedJoint(level, firstBodyId, secondBodyId, collideConnected);
        }

        @Override
        public MechanicsJointSnapshot createFixedJointAtWorldFrame(
                MechanicsBodyId firstBodyId,
                MechanicsBodyId secondBodyId,
                PhysicsPose worldFrame,
                boolean collideConnected
        ) {
            return ServerPhysicsRuntime.INSTANCE.createMechanicsFixedJointAtWorldFrame(
                    level,
                    firstBodyId,
                    secondBodyId,
                    worldFrame,
                    collideConnected
            );
        }

        @Override
        public MechanicsJointSnapshot createRevoluteJointAtWorldFrame(
                MechanicsBodyId firstBodyId,
                MechanicsBodyId secondBodyId,
                PhysicsPose worldFrame,
                boolean collideConnected
        ) {
            return ServerPhysicsRuntime.INSTANCE.createMechanicsRevoluteJointAtWorldFrame(
                    level,
                    firstBodyId,
                    secondBodyId,
                    worldFrame,
                    collideConnected
            );
        }

        @Override
        public MechanicsJointSnapshot createPrismaticJointAtWorldFrame(
                MechanicsBodyId firstBodyId,
                MechanicsBodyId secondBodyId,
                PhysicsPose worldFrame,
                boolean collideConnected
        ) {
            return ServerPhysicsRuntime.INSTANCE.createMechanicsPrismaticJointAtWorldFrame(
                    level,
                    firstBodyId,
                    secondBodyId,
                    worldFrame,
                    collideConnected
            );
        }

        @Override
        public MechanicsJointSnapshot createDistanceJoint(
                MechanicsBodyId firstBodyId,
                MechanicsBodyId secondBodyId,
                float minDistance,
                float maxDistance,
                float stiffness,
                float damping,
                boolean collideConnected
        ) {
            return ServerPhysicsRuntime.INSTANCE.createMechanicsDistanceJoint(
                    level,
                    firstBodyId,
                    secondBodyId,
                    minDistance,
                    maxDistance,
                    stiffness,
                    damping,
                    collideConnected
            );
        }

        @Override
        public MechanicsJointSnapshot createDistanceJointAtWorldAnchors(
                MechanicsBodyId firstBodyId,
                PhysicsVector firstWorldAnchor,
                MechanicsBodyId secondBodyId,
                PhysicsVector secondWorldAnchor,
                float minDistance,
                float maxDistance,
                float stiffness,
                float damping,
                boolean collideConnected
        ) {
            return ServerPhysicsRuntime.INSTANCE.createMechanicsDistanceJointAtWorldAnchors(
                    level,
                    firstBodyId,
                    firstWorldAnchor,
                    secondBodyId,
                    secondWorldAnchor,
                    minDistance,
                    maxDistance,
                    stiffness,
                    damping,
                    collideConnected
            );
        }

        @Override
        public Optional<MechanicsBodySnapshot> snapshot(MechanicsBodyId id) {
            return ServerPhysicsRuntime.INSTANCE.mechanicsSnapshot(level, id);
        }

        @Override
        public Optional<MechanicsJointSnapshot> jointSnapshot(MechanicsJointId id) {
            return ServerPhysicsRuntime.INSTANCE.mechanicsJointSnapshot(level, id);
        }

        @Override
        public Optional<PhysicsPose> pose(MechanicsBodyId id) {
            return ServerPhysicsRuntime.INSTANCE.mechanicsPose(level, id);
        }

        @Override
        public List<MechanicsBodySnapshot> snapshots() {
            return ServerPhysicsRuntime.INSTANCE.mechanicsSnapshots(level);
        }

        @Override
        public List<MechanicsJointSnapshot> jointSnapshots() {
            return ServerPhysicsRuntime.INSTANCE.mechanicsJointSnapshots(level);
        }

        @Override
        public boolean setPose(MechanicsBodyId id, PhysicsPose pose) {
            return ServerPhysicsRuntime.INSTANCE.setMechanicsPose(level, id, pose);
        }

        @Override
        public boolean setLinearVelocity(MechanicsBodyId id, PhysicsVector velocity) {
            return ServerPhysicsRuntime.INSTANCE.setMechanicsLinearVelocity(level, id, velocity);
        }

        @Override
        public boolean setAngularVelocity(MechanicsBodyId id, PhysicsVector velocity) {
            return ServerPhysicsRuntime.INSTANCE.setMechanicsAngularVelocity(level, id, velocity);
        }

        @Override
        public boolean applyLinearImpulse(MechanicsBodyId id, PhysicsVector impulse) {
            return ServerPhysicsRuntime.INSTANCE.applyMechanicsLinearImpulse(level, id, impulse);
        }

        @Override
        public boolean applyAngularImpulse(MechanicsBodyId id, PhysicsVector impulse) {
            return ServerPhysicsRuntime.INSTANCE.applyMechanicsAngularImpulse(level, id, impulse);
        }

        @Override
        public boolean applyImpulseAtPoint(MechanicsBodyId id, PhysicsVector impulse, PhysicsVector point) {
            return ServerPhysicsRuntime.INSTANCE.applyMechanicsImpulseAtPoint(level, id, impulse, point);
        }

        @Override
        public MechanicsResult<Void> applyForce(MechanicsBodyId id, PhysicsVector force) {
            return ServerPhysicsRuntime.INSTANCE.applyMechanicsForce(level, id, force);
        }

        @Override
        public MechanicsResult<Void> applyTorque(MechanicsBodyId id, PhysicsVector torque) {
            return ServerPhysicsRuntime.INSTANCE.applyMechanicsTorque(level, id, torque);
        }

        @Override
        public MechanicsResult<Void> applyForceAtPoint(MechanicsBodyId id, PhysicsVector force, PhysicsVector point) {
            return ServerPhysicsRuntime.INSTANCE.applyMechanicsForceAtPoint(level, id, force, point);
        }

        @Override
        public boolean removeBody(MechanicsBodyId id) {
            return ServerPhysicsRuntime.INSTANCE.removeMechanicsBody(level, id);
        }

        @Override
        public boolean removeJoint(MechanicsJointId id) {
            return ServerPhysicsRuntime.INSTANCE.removeMechanicsJoint(level, id);
        }
    }
}
