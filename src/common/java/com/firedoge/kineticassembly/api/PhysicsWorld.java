package com.firedoge.kineticassembly.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public interface PhysicsWorld extends AutoCloseable {
    String backendId();

    PhysicsShape createBoxShape(float halfExtentX, float halfExtentY, float halfExtentZ);

    PhysicsBody createStaticPlane(PhysicsVector normal, double distance);

    PhysicsBody createBody(RigidBodyDefinition definition);

    default PhysicsBody createDynamicCompoundBoxBody(PhysicsPose pose, List<PhysicsBoxCollider> boxes, float mass) {
        throw new UnsupportedOperationException(backendId() + " does not support dynamic compound box bodies");
    }

    default PhysicsBody createDynamicCompoundBoxBody(PhysicsPose pose, List<PhysicsBoxCollider> boxes, PhysicsMassProperties massProperties) {
        Objects.requireNonNull(massProperties, "massProperties");
        return createDynamicCompoundBoxBody(pose, boxes, massProperties.mass());
    }

    default PhysicsDeformableVolume createDeformableVolumeBox(DeformableVolumeDefinition definition) {
        throw new UnsupportedOperationException(backendId() + " does not support deformable volumes");
    }

    default PhysicsJoint createFixedJoint(PhysicsBody firstBody, PhysicsBody secondBody) {
        return createFixedJoint(firstBody, secondBody, false, Float.MAX_VALUE, Float.MAX_VALUE);
    }

    default PhysicsJoint createFixedJointAtWorldFrame(PhysicsBody firstBody, PhysicsBody secondBody, PhysicsPose worldFrame) {
        return createFixedJointAtWorldFrame(firstBody, secondBody, worldFrame, false, Float.MAX_VALUE, Float.MAX_VALUE);
    }

    default PhysicsJoint createFixedJointAtWorldFrame(
            PhysicsBody firstBody,
            PhysicsBody secondBody,
            PhysicsPose worldFrame,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    ) {
        Objects.requireNonNull(worldFrame, "worldFrame");
        throw new UnsupportedOperationException(backendId() + " does not support fixed joint world frames");
    }

    default PhysicsJoint createFixedJointWithLocalFrames(
            PhysicsBody firstBody,
            PhysicsPose firstLocalFrame,
            PhysicsBody secondBody,
            PhysicsPose secondLocalFrame
    ) {
        return createFixedJointWithLocalFrames(
                firstBody,
                firstLocalFrame,
                secondBody,
                secondLocalFrame,
                false,
                Float.MAX_VALUE,
                Float.MAX_VALUE
        );
    }

    default PhysicsJoint createFixedJointWithLocalFrames(
            PhysicsBody firstBody,
            PhysicsPose firstLocalFrame,
            PhysicsBody secondBody,
            PhysicsPose secondLocalFrame,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    ) {
        Objects.requireNonNull(firstLocalFrame, "firstLocalFrame");
        Objects.requireNonNull(secondLocalFrame, "secondLocalFrame");
        throw new UnsupportedOperationException(backendId() + " does not support fixed joint local frames");
    }

    default PhysicsJoint createFixedJoint(
            PhysicsBody firstBody,
            PhysicsBody secondBody,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    ) {
        throw new UnsupportedOperationException(backendId() + " does not support fixed joints");
    }

    default PhysicsJoint createDistanceJoint(
            PhysicsBody firstBody,
            PhysicsBody secondBody,
            float minDistance,
            float maxDistance
    ) {
        return createDistanceJoint(firstBody, secondBody, minDistance, maxDistance, 0.0F, 0.0F);
    }

    default PhysicsJoint createDistanceJoint(
            PhysicsBody firstBody,
            PhysicsBody secondBody,
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping
    ) {
        return createDistanceJoint(
                firstBody,
                secondBody,
                minDistance,
                maxDistance,
                stiffness,
                damping,
                false,
                Float.MAX_VALUE,
                Float.MAX_VALUE
        );
    }

    default PhysicsJoint createDistanceJoint(
            PhysicsBody firstBody,
            PhysicsBody secondBody,
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    ) {
        throw new UnsupportedOperationException(backendId() + " does not support distance joints");
    }

    default PhysicsJoint createDistanceJointAtWorldAnchors(
            PhysicsBody firstBody,
            PhysicsVector firstWorldAnchor,
            PhysicsBody secondBody,
            PhysicsVector secondWorldAnchor,
            float minDistance,
            float maxDistance
    ) {
        return createDistanceJointAtWorldAnchors(
                firstBody,
                firstWorldAnchor,
                secondBody,
                secondWorldAnchor,
                minDistance,
                maxDistance,
                0.0F,
                0.0F
        );
    }

    default PhysicsJoint createDistanceJointAtWorldAnchors(
            PhysicsBody firstBody,
            PhysicsVector firstWorldAnchor,
            PhysicsBody secondBody,
            PhysicsVector secondWorldAnchor,
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping
    ) {
        return createDistanceJointAtWorldAnchors(
                firstBody,
                firstWorldAnchor,
                secondBody,
                secondWorldAnchor,
                minDistance,
                maxDistance,
                stiffness,
                damping,
                false,
                Float.MAX_VALUE,
                Float.MAX_VALUE
        );
    }

    default PhysicsJoint createDistanceJointAtWorldAnchors(
            PhysicsBody firstBody,
            PhysicsVector firstWorldAnchor,
            PhysicsBody secondBody,
            PhysicsVector secondWorldAnchor,
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    ) {
        Objects.requireNonNull(firstWorldAnchor, "firstWorldAnchor");
        Objects.requireNonNull(secondWorldAnchor, "secondWorldAnchor");
        throw new UnsupportedOperationException(backendId() + " does not support distance joint world anchors");
    }

    default PhysicsJoint createDistanceJointWithLocalAnchors(
            PhysicsBody firstBody,
            PhysicsVector firstLocalAnchor,
            PhysicsBody secondBody,
            PhysicsVector secondLocalAnchor,
            float minDistance,
            float maxDistance
    ) {
        return createDistanceJointWithLocalAnchors(
                firstBody,
                firstLocalAnchor,
                secondBody,
                secondLocalAnchor,
                minDistance,
                maxDistance,
                0.0F,
                0.0F
        );
    }

    default PhysicsJoint createDistanceJointWithLocalAnchors(
            PhysicsBody firstBody,
            PhysicsVector firstLocalAnchor,
            PhysicsBody secondBody,
            PhysicsVector secondLocalAnchor,
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping
    ) {
        return createDistanceJointWithLocalAnchors(
                firstBody,
                firstLocalAnchor,
                secondBody,
                secondLocalAnchor,
                minDistance,
                maxDistance,
                stiffness,
                damping,
                false,
                Float.MAX_VALUE,
                Float.MAX_VALUE
        );
    }

    default PhysicsJoint createDistanceJointWithLocalAnchors(
            PhysicsBody firstBody,
            PhysicsVector firstLocalAnchor,
            PhysicsBody secondBody,
            PhysicsVector secondLocalAnchor,
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    ) {
        Objects.requireNonNull(firstLocalAnchor, "firstLocalAnchor");
        Objects.requireNonNull(secondLocalAnchor, "secondLocalAnchor");
        throw new UnsupportedOperationException(backendId() + " does not support distance joint local anchors");
    }

    default PhysicsJoint createRevoluteJointAtWorldFrame(PhysicsBody firstBody, PhysicsBody secondBody, PhysicsPose worldFrame) {
        return createRevoluteJointAtWorldFrame(firstBody, secondBody, worldFrame, false, Float.MAX_VALUE, Float.MAX_VALUE);
    }

    default PhysicsJoint createRevoluteJointAtWorldFrame(
            PhysicsBody firstBody,
            PhysicsBody secondBody,
            PhysicsPose worldFrame,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    ) {
        Objects.requireNonNull(worldFrame, "worldFrame");
        throw new UnsupportedOperationException(backendId() + " does not support revolute joint world frames");
    }

    default PhysicsJoint createRevoluteJointWithLocalFrames(
            PhysicsBody firstBody,
            PhysicsPose firstLocalFrame,
            PhysicsBody secondBody,
            PhysicsPose secondLocalFrame
    ) {
        return createRevoluteJointWithLocalFrames(
                firstBody,
                firstLocalFrame,
                secondBody,
                secondLocalFrame,
                false,
                Float.MAX_VALUE,
                Float.MAX_VALUE
        );
    }

    default PhysicsJoint createRevoluteJointWithLocalFrames(
            PhysicsBody firstBody,
            PhysicsPose firstLocalFrame,
            PhysicsBody secondBody,
            PhysicsPose secondLocalFrame,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    ) {
        Objects.requireNonNull(firstLocalFrame, "firstLocalFrame");
        Objects.requireNonNull(secondLocalFrame, "secondLocalFrame");
        throw new UnsupportedOperationException(backendId() + " does not support revolute joint local frames");
    }

    default PhysicsJoint createPrismaticJointAtWorldFrame(PhysicsBody firstBody, PhysicsBody secondBody, PhysicsPose worldFrame) {
        return createPrismaticJointAtWorldFrame(firstBody, secondBody, worldFrame, false, Float.MAX_VALUE, Float.MAX_VALUE);
    }

    default PhysicsJoint createPrismaticJointAtWorldFrame(
            PhysicsBody firstBody,
            PhysicsBody secondBody,
            PhysicsPose worldFrame,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    ) {
        Objects.requireNonNull(worldFrame, "worldFrame");
        throw new UnsupportedOperationException(backendId() + " does not support prismatic joint world frames");
    }

    default PhysicsJoint createPrismaticJointWithLocalFrames(
            PhysicsBody firstBody,
            PhysicsPose firstLocalFrame,
            PhysicsBody secondBody,
            PhysicsPose secondLocalFrame
    ) {
        return createPrismaticJointWithLocalFrames(
                firstBody,
                firstLocalFrame,
                secondBody,
                secondLocalFrame,
                false,
                Float.MAX_VALUE,
                Float.MAX_VALUE
        );
    }

    default PhysicsJoint createPrismaticJointWithLocalFrames(
            PhysicsBody firstBody,
            PhysicsPose firstLocalFrame,
            PhysicsBody secondBody,
            PhysicsPose secondLocalFrame,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    ) {
        Objects.requireNonNull(firstLocalFrame, "firstLocalFrame");
        Objects.requireNonNull(secondLocalFrame, "secondLocalFrame");
        throw new UnsupportedOperationException(backendId() + " does not support prismatic joint local frames");
    }

    void destroyBody(PhysicsBody body);

    default void destroyJoint(PhysicsJoint joint) {
        Objects.requireNonNull(joint, "joint");
        joint.close();
    }

    void step(float deltaSeconds);

    default List<PhysicsBodyState> readBodyStates(List<? extends PhysicsBody> bodies) {
        Objects.requireNonNull(bodies, "bodies");
        List<PhysicsBodyState> states = new ArrayList<>();
        for (PhysicsBody body : bodies) {
            Objects.requireNonNull(body, "body");
            if (body.isClosed()) {
                continue;
            }
            states.add(new PhysicsBodyState(
                    body,
                    body.pose(),
                    body.linearVelocity(),
                    body.angularVelocity()
            ));
        }
        return List.copyOf(states);
    }

    default boolean gpuDynamicsEnabled() {
        return false;
    }

    default String gpuDynamicsStatus() {
        return "not_requested";
    }

    boolean isClosed();

    @Override
    void close();
}
