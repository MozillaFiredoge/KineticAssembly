package com.firedoge.kineticassembly.backend.physx;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import com.firedoge.kineticassembly.api.PhysicsBody;
import com.firedoge.kineticassembly.api.PhysicsBodyState;
import com.firedoge.kineticassembly.api.PhysicsBoxCollider;
import com.firedoge.kineticassembly.api.DeformableVolumeDefinition;
import com.firedoge.kineticassembly.api.PhysicsDeformableVolume;
import com.firedoge.kineticassembly.api.PhysicsJoint;
import com.firedoge.kineticassembly.api.PhysicsJointType;
import com.firedoge.kineticassembly.api.PhysicsMassProperties;
import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsQuaternion;
import com.firedoge.kineticassembly.api.PhysicsShape;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.api.PhysicsWorld;
import com.firedoge.kineticassembly.api.PhysicsWorldConfig;
import com.firedoge.kineticassembly.api.RigidBodyDefinition;

public final class PhysXWorld implements PhysicsWorld {
    private static final int BODY_STATE_STRIDE = 13;

    private final long nativeHandle;
    private final PhysicsWorldConfig config;
    private final List<PhysXBody> bodies = new CopyOnWriteArrayList<>();
    private final List<PhysXShape> shapes = new CopyOnWriteArrayList<>();
    private final List<PhysXDeformableVolume> deformableVolumes = new CopyOnWriteArrayList<>();
    private final List<PhysXJoint> joints = new CopyOnWriteArrayList<>();
    private boolean closed;

    PhysXWorld(long nativeHandle, PhysicsWorldConfig config) {
        if (nativeHandle == 0L) {
            throw new IllegalStateException("PhysX returned a null world handle");
        }
        this.nativeHandle = nativeHandle;
        this.config = Objects.requireNonNull(config, "config");
    }

    public long nativeHandle() {
        return nativeHandle;
    }

    public PhysicsWorldConfig config() {
        return config;
    }

    @Override
    public String backendId() {
        return PhysXBackend.ID;
    }

    @Override
    public PhysicsShape createBoxShape(float halfExtentX, float halfExtentY, float halfExtentZ) {
        ensureOpen();
        if (halfExtentX <= 0.0F || halfExtentY <= 0.0F || halfExtentZ <= 0.0F) {
            throw new IllegalArgumentException("Box half extents must be positive");
        }
        long handle = PhysXNative.nativeCreateBoxShape(nativeHandle, halfExtentX, halfExtentY, halfExtentZ);
        if (handle == 0L) {
            throw new IllegalStateException("PhysX failed to create a box shape");
        }
        PhysXShape shape = new PhysXShape(this, PhysicsShape.ShapeType.BOX, handle);
        shapes.add(shape);
        return shape;
    }

    @Override
    public PhysicsBody createStaticPlane(PhysicsVector normal, double distance) {
        ensureOpen();
        Objects.requireNonNull(normal, "normal");
        long handle = PhysXNative.nativeCreateStaticPlane(nativeHandle, normal.x(), normal.y(), normal.z(), distance);
        if (handle == 0L) {
            throw new IllegalStateException("PhysX failed to create a static plane");
        }
        PhysXBody body = new PhysXBody(this, handle, PhysicsPose.IDENTITY);
        bodies.add(body);
        return body;
    }

    @Override
    public PhysicsBody createBody(RigidBodyDefinition definition) {
        ensureOpen();
        Objects.requireNonNull(definition, "definition");
        if (!(definition.shape() instanceof PhysXShape shape)) {
            throw new IllegalArgumentException("PhysX worlds can only create bodies from PhysX shapes");
        }
        if (!shape.belongsTo(this)) {
            throw new IllegalArgumentException("PhysX shapes cannot be shared across worlds");
        }
        shape.ensureOpen();

        PhysicsPose pose = definition.initialPose();
        long handle = switch (definition.type()) {
            case STATIC -> PhysXNative.nativeCreateStaticBody(
                    nativeHandle,
                    shape.nativeHandle(),
                    pose.position().x(),
                    pose.position().y(),
                    pose.position().z(),
                    pose.rotation().x(),
                    pose.rotation().y(),
                    pose.rotation().z(),
                    pose.rotation().w()
            );
            case DYNAMIC -> PhysXNative.nativeCreateDynamicBody(
                    nativeHandle,
                    shape.nativeHandle(),
                    pose.position().x(),
                    pose.position().y(),
                    pose.position().z(),
                    pose.rotation().x(),
                    pose.rotation().y(),
                    pose.rotation().z(),
                    pose.rotation().w(),
                    definition.mass()
            );
            case KINEMATIC -> throw new UnsupportedOperationException("Kinematic PhysX bodies are not implemented yet");
        };
        if (handle == 0L) {
            throw new IllegalStateException("PhysX failed to create a " + definition.type() + " body");
        }
        PhysXBody body = new PhysXBody(this, handle, pose);
        bodies.add(body);
        return body;
    }

    @Override
    public PhysicsBody createDynamicCompoundBoxBody(PhysicsPose pose, List<PhysicsBoxCollider> boxes, float mass) {
        ensureOpen();
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(boxes, "boxes");
        if (boxes.isEmpty()) {
            throw new IllegalArgumentException("Compound boxes must not be empty");
        }
        if (mass <= 0.0F) {
            throw new IllegalArgumentException("Mass must be positive");
        }

        double[] packedBoxes = packBoxes(boxes);

        long handle = PhysXNative.nativeCreateDynamicCompoundBoxBody(
                nativeHandle,
                packedBoxes,
                boxes.size(),
                pose.position().x(),
                pose.position().y(),
                pose.position().z(),
                pose.rotation().x(),
                pose.rotation().y(),
                pose.rotation().z(),
                pose.rotation().w(),
                mass
        );
        if (handle == 0L) {
            throw new IllegalStateException("PhysX failed to create a dynamic compound box body");
        }
        PhysXBody body = new PhysXBody(this, handle, pose);
        bodies.add(body);
        return body;
    }

    @Override
    public PhysicsBody createDynamicCompoundBoxBody(PhysicsPose pose, List<PhysicsBoxCollider> boxes, PhysicsMassProperties massProperties) {
        ensureOpen();
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(boxes, "boxes");
        Objects.requireNonNull(massProperties, "massProperties");
        if (boxes.isEmpty()) {
            throw new IllegalArgumentException("Compound boxes must not be empty");
        }

        double[] packedBoxes = packBoxes(boxes);
        PhysicsVector centerOfMass = massProperties.centerOfMass();
        PhysicsVector inertiaTensor = massProperties.inertiaTensor();

        long handle = PhysXNative.nativeCreateDynamicCompoundBoxBodyWithMassProperties(
                nativeHandle,
                packedBoxes,
                boxes.size(),
                pose.position().x(),
                pose.position().y(),
                pose.position().z(),
                pose.rotation().x(),
                pose.rotation().y(),
                pose.rotation().z(),
                pose.rotation().w(),
                massProperties.mass(),
                centerOfMass.x(),
                centerOfMass.y(),
                centerOfMass.z(),
                inertiaTensor.x(),
                inertiaTensor.y(),
                inertiaTensor.z()
        );
        if (handle == 0L) {
            throw new IllegalStateException("PhysX failed to create a dynamic compound box body with explicit mass properties");
        }
        PhysXBody body = new PhysXBody(this, handle, pose);
        bodies.add(body);
        return body;
    }

    private static double[] packBoxes(List<PhysicsBoxCollider> boxes) {
        double[] packedBoxes = new double[boxes.size() * 6];
        for (int i = 0; i < boxes.size(); i++) {
            PhysicsBoxCollider box = Objects.requireNonNull(boxes.get(i), "box");
            int offset = i * 6;
            packedBoxes[offset] = box.center().x();
            packedBoxes[offset + 1] = box.center().y();
            packedBoxes[offset + 2] = box.center().z();
            packedBoxes[offset + 3] = box.halfExtents().x();
            packedBoxes[offset + 4] = box.halfExtents().y();
            packedBoxes[offset + 5] = box.halfExtents().z();
        }
        return packedBoxes;
    }

    @Override
    public PhysicsDeformableVolume createDeformableVolumeBox(DeformableVolumeDefinition definition) {
        ensureOpen();
        Objects.requireNonNull(definition, "definition");
        if (!gpuDynamicsEnabled()) {
            throw new IllegalStateException("PhysX deformable volumes require a GPU dynamics scene; enable enableGpuDynamics and ensure CUDA is available. status=" + gpuDynamicsStatus());
        }

        PhysicsVector center = definition.center();
        PhysicsVector dimensions = definition.dimensions();
        long handle = PhysXNative.nativeCreateDeformableVolumeBox(
                nativeHandle,
                center.x(),
                center.y(),
                center.z(),
                (float) dimensions.x(),
                (float) dimensions.y(),
                (float) dimensions.z(),
                definition.density(),
                definition.youngs(),
                definition.poissons(),
                definition.dynamicFriction(),
                definition.damping(),
                definition.maxEdgeLength(),
                definition.voxels()
        );
        if (handle == 0L) {
            throw new IllegalStateException("PhysX failed to create a deformable volume box");
        }
        int[] info = new int[4];
        if (!PhysXNative.nativeGetDeformableVolumeInfo(handle, info)) {
            PhysXNative.nativeDestroyDeformableVolume(handle);
            throw new IllegalStateException("PhysX failed to query deformable volume mesh info");
        }
        PhysXDeformableVolume deformableVolume = new PhysXDeformableVolume(this, handle, info);
        deformableVolumes.add(deformableVolume);
        return deformableVolume;
    }

    @Override
    public PhysicsJoint createFixedJoint(
            PhysicsBody firstBody,
            PhysicsBody secondBody,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    ) {
        ensureOpen();
        PhysXBody first = requireBody(firstBody, "firstBody");
        PhysXBody second = requireBody(secondBody, "secondBody");
        if (first == second) {
            throw new IllegalArgumentException("Fixed joints require two different bodies");
        }
        if (!Float.isFinite(breakForce) || breakForce <= 0.0F) {
            throw new IllegalArgumentException("Break force must be a finite positive float");
        }
        if (!Float.isFinite(breakTorque) || breakTorque <= 0.0F) {
            throw new IllegalArgumentException("Break torque must be a finite positive float");
        }

        long handle = PhysXNative.createFixedJoint(
                nativeHandle,
                first.nativeHandle(),
                second.nativeHandle(),
                collideConnected,
                breakForce,
                breakTorque
        );
        if (handle == 0L) {
            if (!PhysXNative.fixedJointNativeAvailable()) {
                throw new IllegalStateException("Loaded PhysX native bridge does not expose fixed joints; run ./gradlew buildNativeLinux and restart the client/server");
            }
            throw new IllegalStateException("PhysX failed to create a fixed joint");
        }
        PhysXJoint joint = new PhysXJoint(this, PhysicsJointType.FIXED, handle, first, second);
        joints.add(joint);
        return joint;
    }

    @Override
    public PhysicsJoint createFixedJointAtWorldFrame(
            PhysicsBody firstBody,
            PhysicsBody secondBody,
            PhysicsPose worldFrame,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    ) {
        ensureOpen();
        Objects.requireNonNull(worldFrame, "worldFrame");
        validatePose(worldFrame, "worldFrame");
        PhysXBody first = requireBody(firstBody, "firstBody");
        PhysXBody second = requireBody(secondBody, "secondBody");
        PhysicsPose firstLocalFrame = bodyLocalFrame(first.pose(), worldFrame);
        PhysicsPose secondLocalFrame = bodyLocalFrame(second.pose(), worldFrame);
        return createFixedJointWithLocalFrames(
                first,
                firstLocalFrame,
                second,
                secondLocalFrame,
                collideConnected,
                breakForce,
                breakTorque
        );
    }

    @Override
    public PhysicsJoint createFixedJointWithLocalFrames(
            PhysicsBody firstBody,
            PhysicsPose firstLocalFrame,
            PhysicsBody secondBody,
            PhysicsPose secondLocalFrame,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    ) {
        ensureOpen();
        PhysXBody first = requireBody(firstBody, "firstBody");
        PhysXBody second = requireBody(secondBody, "secondBody");
        if (first == second) {
            throw new IllegalArgumentException("Fixed joints require two different bodies");
        }
        validatePose(firstLocalFrame, "firstLocalFrame");
        validatePose(secondLocalFrame, "secondLocalFrame");
        if (!Float.isFinite(breakForce) || breakForce <= 0.0F) {
            throw new IllegalArgumentException("Break force must be a finite positive float");
        }
        if (!Float.isFinite(breakTorque) || breakTorque <= 0.0F) {
            throw new IllegalArgumentException("Break torque must be a finite positive float");
        }

        long handle = PhysXNative.createFixedJointWithLocalFrames(
                nativeHandle,
                first.nativeHandle(),
                second.nativeHandle(),
                packLocalFrames(firstLocalFrame, secondLocalFrame),
                collideConnected,
                breakForce,
                breakTorque
        );
        if (handle == 0L) {
            if (!PhysXNative.fixedJointNativeAvailable()) {
                throw new IllegalStateException("Loaded PhysX native bridge does not expose fixed joint local frames; run ./gradlew buildNativeLinux and restart the client/server");
            }
            throw new IllegalStateException("PhysX failed to create a fixed joint with local frames");
        }
        PhysXJoint joint = new PhysXJoint(this, PhysicsJointType.FIXED, handle, first, second);
        joints.add(joint);
        return joint;
    }

    @Override
    public PhysicsJoint createDistanceJoint(
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
        ensureOpen();
        PhysXBody first = requireBody(firstBody, "firstBody");
        PhysXBody second = requireBody(secondBody, "secondBody");
        if (first == second) {
            throw new IllegalArgumentException("Distance joints require two different bodies");
        }
        validateDistanceJointSettings(minDistance, maxDistance, stiffness, damping, breakForce, breakTorque);

        long handle = PhysXNative.createDistanceJoint(
                nativeHandle,
                first.nativeHandle(),
                second.nativeHandle(),
                minDistance,
                maxDistance,
                stiffness,
                damping,
                collideConnected,
                breakForce,
                breakTorque
        );
        if (handle == 0L) {
            if (!PhysXNative.distanceJointNativeAvailable()) {
                throw new IllegalStateException("Loaded PhysX native bridge does not expose distance joints; run ./gradlew buildNativeLinux and restart the client/server");
            }
            throw new IllegalStateException("PhysX failed to create a distance joint");
        }
        PhysXJoint joint = new PhysXJoint(this, PhysicsJointType.DISTANCE, handle, first, second);
        joints.add(joint);
        return joint;
    }

    @Override
    public PhysicsJoint createDistanceJointAtWorldAnchors(
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
        ensureOpen();
        validateVector(firstWorldAnchor, "firstWorldAnchor");
        validateVector(secondWorldAnchor, "secondWorldAnchor");
        PhysXBody first = requireBody(firstBody, "firstBody");
        PhysXBody second = requireBody(secondBody, "secondBody");
        PhysicsVector firstLocalAnchor = bodyLocalPoint(first.pose(), firstWorldAnchor);
        PhysicsVector secondLocalAnchor = bodyLocalPoint(second.pose(), secondWorldAnchor);
        return createDistanceJointWithLocalAnchors(
                first,
                firstLocalAnchor,
                second,
                secondLocalAnchor,
                minDistance,
                maxDistance,
                stiffness,
                damping,
                collideConnected,
                breakForce,
                breakTorque
        );
    }

    @Override
    public PhysicsJoint createDistanceJointWithLocalAnchors(
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
        ensureOpen();
        PhysXBody first = requireBody(firstBody, "firstBody");
        PhysXBody second = requireBody(secondBody, "secondBody");
        if (first == second) {
            throw new IllegalArgumentException("Distance joints require two different bodies");
        }
        validateVector(firstLocalAnchor, "firstLocalAnchor");
        validateVector(secondLocalAnchor, "secondLocalAnchor");
        validateDistanceJointSettings(minDistance, maxDistance, stiffness, damping, breakForce, breakTorque);

        long handle = PhysXNative.createDistanceJointWithLocalAnchors(
                nativeHandle,
                first.nativeHandle(),
                second.nativeHandle(),
                packLocalAnchors(firstLocalAnchor, secondLocalAnchor),
                minDistance,
                maxDistance,
                stiffness,
                damping,
                collideConnected,
                breakForce,
                breakTorque
        );
        if (handle == 0L) {
            if (!PhysXNative.distanceJointNativeAvailable()) {
                throw new IllegalStateException("Loaded PhysX native bridge does not expose distance joint local anchors; run ./gradlew buildNativeLinux and restart the client/server");
            }
            throw new IllegalStateException("PhysX failed to create a distance joint with local anchors");
        }
        PhysXJoint joint = new PhysXJoint(this, PhysicsJointType.DISTANCE, handle, first, second);
        joints.add(joint);
        return joint;
    }

    @Override
    public PhysicsJoint createRevoluteJointAtWorldFrame(
            PhysicsBody firstBody,
            PhysicsBody secondBody,
            PhysicsPose worldFrame,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    ) {
        ensureOpen();
        Objects.requireNonNull(worldFrame, "worldFrame");
        validatePose(worldFrame, "worldFrame");
        PhysXBody first = requireBody(firstBody, "firstBody");
        PhysXBody second = requireBody(secondBody, "secondBody");
        PhysicsPose firstLocalFrame = bodyLocalFrame(first.pose(), worldFrame);
        PhysicsPose secondLocalFrame = bodyLocalFrame(second.pose(), worldFrame);
        return createRevoluteJointWithLocalFrames(
                first,
                firstLocalFrame,
                second,
                secondLocalFrame,
                collideConnected,
                breakForce,
                breakTorque
        );
    }

    @Override
    public PhysicsJoint createRevoluteJointWithLocalFrames(
            PhysicsBody firstBody,
            PhysicsPose firstLocalFrame,
            PhysicsBody secondBody,
            PhysicsPose secondLocalFrame,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    ) {
        ensureOpen();
        PhysXBody first = requireBody(firstBody, "firstBody");
        PhysXBody second = requireBody(secondBody, "secondBody");
        if (first == second) {
            throw new IllegalArgumentException("Revolute joints require two different bodies");
        }
        validatePose(firstLocalFrame, "firstLocalFrame");
        validatePose(secondLocalFrame, "secondLocalFrame");
        if (!Float.isFinite(breakForce) || breakForce <= 0.0F) {
            throw new IllegalArgumentException("Break force must be a finite positive float");
        }
        if (!Float.isFinite(breakTorque) || breakTorque <= 0.0F) {
            throw new IllegalArgumentException("Break torque must be a finite positive float");
        }

        long handle = PhysXNative.createRevoluteJointWithLocalFrames(
                nativeHandle,
                first.nativeHandle(),
                second.nativeHandle(),
                packLocalFrames(firstLocalFrame, secondLocalFrame),
                collideConnected,
                breakForce,
                breakTorque
        );
        if (handle == 0L) {
            if (!PhysXNative.revoluteJointNativeAvailable()) {
                throw new IllegalStateException("Loaded PhysX native bridge does not expose revolute joint local frames; run ./gradlew buildNativeLinux and restart the client/server");
            }
            throw new IllegalStateException("PhysX failed to create a revolute joint with local frames");
        }
        PhysXJoint joint = new PhysXJoint(this, PhysicsJointType.REVOLUTE, handle, first, second);
        joints.add(joint);
        return joint;
    }

    @Override
    public PhysicsJoint createPrismaticJointAtWorldFrame(
            PhysicsBody firstBody,
            PhysicsBody secondBody,
            PhysicsPose worldFrame,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    ) {
        ensureOpen();
        Objects.requireNonNull(worldFrame, "worldFrame");
        validatePose(worldFrame, "worldFrame");
        PhysXBody first = requireBody(firstBody, "firstBody");
        PhysXBody second = requireBody(secondBody, "secondBody");
        PhysicsPose firstLocalFrame = bodyLocalFrame(first.pose(), worldFrame);
        PhysicsPose secondLocalFrame = bodyLocalFrame(second.pose(), worldFrame);
        return createPrismaticJointWithLocalFrames(
                first,
                firstLocalFrame,
                second,
                secondLocalFrame,
                collideConnected,
                breakForce,
                breakTorque
        );
    }

    @Override
    public PhysicsJoint createPrismaticJointWithLocalFrames(
            PhysicsBody firstBody,
            PhysicsPose firstLocalFrame,
            PhysicsBody secondBody,
            PhysicsPose secondLocalFrame,
            boolean collideConnected,
            float breakForce,
            float breakTorque
    ) {
        ensureOpen();
        PhysXBody first = requireBody(firstBody, "firstBody");
        PhysXBody second = requireBody(secondBody, "secondBody");
        if (first == second) {
            throw new IllegalArgumentException("Prismatic joints require two different bodies");
        }
        validatePose(firstLocalFrame, "firstLocalFrame");
        validatePose(secondLocalFrame, "secondLocalFrame");
        if (!Float.isFinite(breakForce) || breakForce <= 0.0F) {
            throw new IllegalArgumentException("Break force must be a finite positive float");
        }
        if (!Float.isFinite(breakTorque) || breakTorque <= 0.0F) {
            throw new IllegalArgumentException("Break torque must be a finite positive float");
        }

        long handle = PhysXNative.createPrismaticJointWithLocalFrames(
                nativeHandle,
                first.nativeHandle(),
                second.nativeHandle(),
                packLocalFrames(firstLocalFrame, secondLocalFrame),
                collideConnected,
                breakForce,
                breakTorque
        );
        if (handle == 0L) {
            if (!PhysXNative.prismaticJointNativeAvailable()) {
                throw new IllegalStateException("Loaded PhysX native bridge does not expose prismatic joint local frames; run ./gradlew buildNativeLinux and restart the client/server");
            }
            throw new IllegalStateException("PhysX failed to create a prismatic joint with local frames");
        }
        PhysXJoint joint = new PhysXJoint(this, PhysicsJointType.PRISMATIC, handle, first, second);
        joints.add(joint);
        return joint;
    }

    @Override
    public void destroyBody(PhysicsBody body) {
        Objects.requireNonNull(body, "body");
        if (!(body instanceof PhysXBody physXBody) || !physXBody.belongsTo(this)) {
            throw new IllegalArgumentException("Body does not belong to this PhysX world");
        }
        physXBody.close();
    }

    @Override
    public void destroyJoint(PhysicsJoint joint) {
        Objects.requireNonNull(joint, "joint");
        if (!(joint instanceof PhysXJoint physXJoint) || !physXJoint.belongsTo(this)) {
            throw new IllegalArgumentException("Joint does not belong to this PhysX world");
        }
        physXJoint.close();
    }

    @Override
    public void step(float deltaSeconds) {
        ensureOpen();
        if (deltaSeconds <= 0.0F) {
            return;
        }
        PhysXNative.nativeStepWorld(nativeHandle, deltaSeconds);
    }

    @Override
    public List<PhysicsBodyState> readBodyStates(List<? extends PhysicsBody> bodies) {
        ensureOpen();
        Objects.requireNonNull(bodies, "bodies");
        if (bodies.isEmpty()) {
            return List.of();
        }

        List<PhysXBody> physXBodies = new ArrayList<>();
        for (PhysicsBody body : bodies) {
            Objects.requireNonNull(body, "body");
            if (!(body instanceof PhysXBody physXBody) || !physXBody.belongsTo(this)) {
                throw new IllegalArgumentException("Body does not belong to this PhysX world");
            }
            if (!physXBody.isClosed()) {
                physXBodies.add(physXBody);
            }
        }
        if (physXBodies.isEmpty()) {
            return List.of();
        }

        long[] handles = new long[physXBodies.size()];
        for (int i = 0; i < physXBodies.size(); i++) {
            handles[i] = physXBodies.get(i).nativeHandle();
        }
        double[] output = new double[physXBodies.size() * BODY_STATE_STRIDE];
        if (!PhysXNative.readBodyStates(nativeHandle, handles, output)) {
            return PhysicsWorld.super.readBodyStates(physXBodies);
        }

        List<PhysicsBodyState> states = new ArrayList<>(physXBodies.size());
        for (int i = 0; i < physXBodies.size(); i++) {
            int offset = i * BODY_STATE_STRIDE;
            PhysicsPose pose = new PhysicsPose(
                    new PhysicsVector(output[offset], output[offset + 1], output[offset + 2]),
                    new PhysicsQuaternion(output[offset + 3], output[offset + 4], output[offset + 5], output[offset + 6])
            );
            PhysicsVector linearVelocity = new PhysicsVector(output[offset + 7], output[offset + 8], output[offset + 9]);
            PhysicsVector angularVelocity = new PhysicsVector(output[offset + 10], output[offset + 11], output[offset + 12]);
            PhysXBody body = physXBodies.get(i);
            body.updateCachedState(pose, linearVelocity, angularVelocity);
            states.add(new PhysicsBodyState(body, pose, linearVelocity, angularVelocity));
        }
        return List.copyOf(states);
    }

    @Override
    public boolean gpuDynamicsEnabled() {
        return !closed && PhysXNative.nativeIsWorldGpuDynamicsEnabled(nativeHandle);
    }

    @Override
    public String gpuDynamicsStatus() {
        return closed ? "closed" : PhysXNative.nativeGetWorldGpuDynamicsStatus(nativeHandle);
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
        for (PhysXJoint joint : joints) {
            joint.close();
        }
        for (PhysXBody body : bodies) {
            body.close();
        }
        for (PhysXShape shape : shapes) {
            shape.close();
        }
        for (PhysXDeformableVolume deformableVolume : deformableVolumes) {
            deformableVolume.close();
        }
        PhysXNative.nativeDestroyWorld(nativeHandle);
    }

    void forgetBody(PhysXBody body) {
        bodies.remove(body);
    }

    void forgetShape(PhysXShape shape) {
        shapes.remove(shape);
    }

    void forgetDeformableVolume(PhysXDeformableVolume deformableVolume) {
        deformableVolumes.remove(deformableVolume);
    }

    void forgetJoint(PhysXJoint joint) {
        joints.remove(joint);
    }

    void destroyJointsForBody(PhysXBody body) {
        for (PhysXJoint joint : List.copyOf(joints)) {
            if (joint.references(body)) {
                joint.close();
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Physics world is closed");
        }
    }

    private PhysXBody requireBody(PhysicsBody body, String name) {
        Objects.requireNonNull(body, name);
        if (!(body instanceof PhysXBody physXBody) || !physXBody.belongsTo(this)) {
            throw new IllegalArgumentException(name + " does not belong to this PhysX world");
        }
        if (physXBody.isClosed()) {
            throw new IllegalArgumentException(name + " is closed");
        }
        return physXBody;
    }

    private static PhysicsPose bodyLocalFrame(PhysicsPose bodyPose, PhysicsPose worldFrame) {
        PhysicsQuaternion bodyRotation = normalize(bodyPose.rotation());
        PhysicsVector bodyPosition = bodyPose.position();
        PhysicsVector worldPosition = worldFrame.position();
        PhysicsVector localPosition = inverseRotate(bodyRotation, new PhysicsVector(
                worldPosition.x() - bodyPosition.x(),
                worldPosition.y() - bodyPosition.y(),
                worldPosition.z() - bodyPosition.z()
        ));
        PhysicsQuaternion localRotation = normalize(multiply(conjugate(bodyRotation), normalize(worldFrame.rotation())));
        return new PhysicsPose(localPosition, localRotation);
    }

    private static PhysicsVector bodyLocalPoint(PhysicsPose bodyPose, PhysicsVector worldPoint) {
        PhysicsQuaternion bodyRotation = normalize(bodyPose.rotation());
        PhysicsVector bodyPosition = bodyPose.position();
        return inverseRotate(bodyRotation, new PhysicsVector(
                worldPoint.x() - bodyPosition.x(),
                worldPoint.y() - bodyPosition.y(),
                worldPoint.z() - bodyPosition.z()
        ));
    }

    private static double[] packLocalFrames(PhysicsPose firstLocalFrame, PhysicsPose secondLocalFrame) {
        double[] frames = new double[14];
        packPose(firstLocalFrame, frames, 0);
        packPose(secondLocalFrame, frames, 7);
        return frames;
    }

    private static double[] packLocalAnchors(PhysicsVector firstLocalAnchor, PhysicsVector secondLocalAnchor) {
        return new double[] {
                firstLocalAnchor.x(),
                firstLocalAnchor.y(),
                firstLocalAnchor.z(),
                secondLocalAnchor.x(),
                secondLocalAnchor.y(),
                secondLocalAnchor.z()
        };
    }

    private static void packPose(PhysicsPose pose, double[] frames, int offset) {
        frames[offset] = pose.position().x();
        frames[offset + 1] = pose.position().y();
        frames[offset + 2] = pose.position().z();
        frames[offset + 3] = pose.rotation().x();
        frames[offset + 4] = pose.rotation().y();
        frames[offset + 5] = pose.rotation().z();
        frames[offset + 6] = pose.rotation().w();
    }

    private static PhysicsVector inverseRotate(PhysicsQuaternion rotation, PhysicsVector vector) {
        return rotate(conjugate(normalize(rotation)), vector);
    }

    private static PhysicsVector rotate(PhysicsQuaternion rotation, PhysicsVector vector) {
        PhysicsQuaternion q = normalize(rotation);
        double ux = q.x();
        double uy = q.y();
        double uz = q.z();
        double s = q.w();
        double vx = vector.x();
        double vy = vector.y();
        double vz = vector.z();

        double dotUv = ux * vx + uy * vy + uz * vz;
        double dotUu = ux * ux + uy * uy + uz * uz;
        double crossX = uy * vz - uz * vy;
        double crossY = uz * vx - ux * vz;
        double crossZ = ux * vy - uy * vx;
        double scale = s * s - dotUu;

        return new PhysicsVector(
                2.0D * dotUv * ux + scale * vx + 2.0D * s * crossX,
                2.0D * dotUv * uy + scale * vy + 2.0D * s * crossY,
                2.0D * dotUv * uz + scale * vz + 2.0D * s * crossZ
        );
    }

    private static PhysicsQuaternion multiply(PhysicsQuaternion first, PhysicsQuaternion second) {
        return new PhysicsQuaternion(
                first.w() * second.x() + first.x() * second.w() + first.y() * second.z() - first.z() * second.y(),
                first.w() * second.y() - first.x() * second.z() + first.y() * second.w() + first.z() * second.x(),
                first.w() * second.z() + first.x() * second.y() - first.y() * second.x() + first.z() * second.w(),
                first.w() * second.w() - first.x() * second.x() - first.y() * second.y() - first.z() * second.z()
        );
    }

    private static PhysicsQuaternion conjugate(PhysicsQuaternion quaternion) {
        PhysicsQuaternion normalized = normalize(quaternion);
        return new PhysicsQuaternion(-normalized.x(), -normalized.y(), -normalized.z(), normalized.w());
    }

    private static PhysicsQuaternion normalize(PhysicsQuaternion quaternion) {
        Objects.requireNonNull(quaternion, "quaternion");
        double length = Math.sqrt(
                quaternion.x() * quaternion.x()
                        + quaternion.y() * quaternion.y()
                        + quaternion.z() * quaternion.z()
                        + quaternion.w() * quaternion.w()
        );
        if (length <= 1.0E-12D || Double.isNaN(length)) {
            return PhysicsQuaternion.IDENTITY;
        }
        return new PhysicsQuaternion(
                quaternion.x() / length,
                quaternion.y() / length,
                quaternion.z() / length,
                quaternion.w() / length
        );
    }

    private static void validatePose(PhysicsPose pose, String name) {
        Objects.requireNonNull(pose, name);
        validateVector(pose.position(), name + ".position");
        validateQuaternion(pose.rotation(), name + ".rotation");
    }

    private static void validateVector(PhysicsVector vector, String name) {
        Objects.requireNonNull(vector, name);
        if (!Double.isFinite(vector.x()) || !Double.isFinite(vector.y()) || !Double.isFinite(vector.z())) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void validateQuaternion(PhysicsQuaternion quaternion, String name) {
        Objects.requireNonNull(quaternion, name);
        if (!Double.isFinite(quaternion.x())
                || !Double.isFinite(quaternion.y())
                || !Double.isFinite(quaternion.z())
                || !Double.isFinite(quaternion.w())) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void validateDistanceJointSettings(
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping,
            float breakForce,
            float breakTorque
    ) {
        if (!Float.isFinite(minDistance) || minDistance < 0.0F) {
            throw new IllegalArgumentException("Minimum distance must be a finite non-negative float");
        }
        if (!Float.isFinite(maxDistance) || maxDistance <= 0.0F) {
            throw new IllegalArgumentException("Maximum distance must be a finite positive float");
        }
        if (minDistance > maxDistance) {
            throw new IllegalArgumentException("Minimum distance must be less than or equal to maximum distance");
        }
        if (!Float.isFinite(stiffness) || stiffness < 0.0F) {
            throw new IllegalArgumentException("Distance joint stiffness must be a finite non-negative float");
        }
        if (!Float.isFinite(damping) || damping < 0.0F) {
            throw new IllegalArgumentException("Distance joint damping must be a finite non-negative float");
        }
        if (!Float.isFinite(breakForce) || breakForce <= 0.0F) {
            throw new IllegalArgumentException("Break force must be a finite positive float");
        }
        if (!Float.isFinite(breakTorque) || breakTorque <= 0.0F) {
            throw new IllegalArgumentException("Break torque must be a finite positive float");
        }
    }
}
