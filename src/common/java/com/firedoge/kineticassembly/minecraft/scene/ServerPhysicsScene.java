package com.firedoge.kineticassembly.minecraft.scene;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.firedoge.kineticassembly.api.PhysicsBackend;
import com.firedoge.kineticassembly.api.PhysicsBody;
import com.firedoge.kineticassembly.api.PhysicsBodyState;
import com.firedoge.kineticassembly.api.PhysicsBoxCollider;
import com.firedoge.kineticassembly.api.DeformableVolumeDefinition;
import com.firedoge.kineticassembly.api.PhysicsDeformableVolume;
import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsJoint;
import com.firedoge.kineticassembly.api.PhysicsMassProperties;
import com.firedoge.kineticassembly.api.PhysicsShape;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.api.PhysicsWorld;
import com.firedoge.kineticassembly.api.PhysicsWorldConfig;
import com.firedoge.kineticassembly.api.RigidBodyDefinition;

public final class ServerPhysicsScene implements AutoCloseable {
    private final String sceneKey;
    private final PhysicsWorld world;
    private final Map<PhysicsObjectId, RigidPhysicsObject> objects = new LinkedHashMap<>();
    private final Map<PhysicsJointId, RigidPhysicsJoint> joints = new LinkedHashMap<>();
    private final Map<PhysicsObjectId, PhysicsDeformableVolume> deformableVolumes = new LinkedHashMap<>();
    private boolean closed;

    public ServerPhysicsScene(String sceneKey, PhysicsBackend backend, PhysicsWorldConfig config) {
        this.sceneKey = Objects.requireNonNull(sceneKey, "sceneKey");
        Objects.requireNonNull(backend, "backend");
        this.world = backend.createWorld(Objects.requireNonNull(config, "config"));
    }

    public String sceneKey() {
        return sceneKey;
    }

    public int objectCount() {
        return objects.size();
    }

    public int jointCount() {
        return joints.size();
    }

    public int deformableVolumeCount() {
        return deformableVolumes.size();
    }

    public boolean gpuDynamicsEnabled() {
        return !closed && world.gpuDynamicsEnabled();
    }

    public String gpuDynamicsStatus() {
        return closed ? "closed" : world.gpuDynamicsStatus();
    }

    public PhysicsObject createStaticPlane(PhysicsVector normal, double distance) {
        ensureOpen();
        PhysicsBody body = world.createStaticPlane(normal, distance);
        return addObject(PhysicsObjectType.STATIC_PLANE, body, null);
    }

    public PhysicsObject createStaticBox(float halfExtentX, float halfExtentY, float halfExtentZ, PhysicsPose pose) {
        ensureOpen();
        Objects.requireNonNull(pose, "pose");
        PhysicsShape shape = world.createBoxShape(halfExtentX, halfExtentY, halfExtentZ);
        try {
            PhysicsBody body = world.createBody(RigidBodyDefinition.staticBody(pose, shape));
            return addObject(PhysicsObjectType.STATIC_BLOCK, body, shape);
        } catch (RuntimeException exception) {
            shape.close();
            throw exception;
        }
    }

    public PhysicsObject createDynamicBox(float halfExtentX, float halfExtentY, float halfExtentZ, PhysicsPose pose, float mass) {
        return createDynamicBox(halfExtentX, halfExtentY, halfExtentZ, pose, mass, PhysicsObjectId.random());
    }

    public PhysicsObject createDynamicBox(
            float halfExtentX,
            float halfExtentY,
            float halfExtentZ,
            PhysicsPose pose,
            float mass,
            PhysicsObjectId id
    ) {
        ensureOpen();
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(id, "id");
        ensureObjectIdAvailable(id);
        PhysicsShape shape = world.createBoxShape(halfExtentX, halfExtentY, halfExtentZ);
        try {
            PhysicsBody body = world.createBody(RigidBodyDefinition.dynamic(pose, shape, mass));
            return addObject(id, PhysicsObjectType.DYNAMIC_BOX, body, shape);
        } catch (RuntimeException exception) {
            shape.close();
            throw exception;
        }
    }

    public PhysicsObject createDynamicCompoundBox(List<PhysicsBoxCollider> boxes, PhysicsPose pose, float mass) {
        return createDynamicCompoundBox(boxes, pose, mass, PhysicsObjectId.random());
    }

    public PhysicsObject createDynamicCompoundBox(List<PhysicsBoxCollider> boxes, PhysicsPose pose, float mass, PhysicsObjectId id) {
        ensureOpen();
        Objects.requireNonNull(boxes, "boxes");
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(id, "id");
        ensureObjectIdAvailable(id);
        PhysicsBody body = world.createDynamicCompoundBoxBody(pose, boxes, mass);
        return addObject(id, PhysicsObjectType.DYNAMIC_BOX, body, null);
    }

    public PhysicsObject createDynamicCompoundBox(
            List<PhysicsBoxCollider> boxes,
            PhysicsPose pose,
            PhysicsMassProperties massProperties,
            PhysicsObjectId id
    ) {
        ensureOpen();
        Objects.requireNonNull(boxes, "boxes");
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(massProperties, "massProperties");
        Objects.requireNonNull(id, "id");
        ensureObjectIdAvailable(id);
        PhysicsBody body = world.createDynamicCompoundBoxBody(pose, boxes, massProperties);
        return addObject(id, PhysicsObjectType.DYNAMIC_BOX, body, null);
    }

    public PhysicsObjectId createDeformableVolumeBox(DeformableVolumeDefinition definition) {
        ensureOpen();
        PhysicsDeformableVolume volume = world.createDeformableVolumeBox(definition);
        PhysicsObjectId id = PhysicsObjectId.random();
        deformableVolumes.put(id, volume);
        return id;
    }

    public PhysicsJointSnapshot createFixedJoint(PhysicsObjectId firstBodyId, PhysicsObjectId secondBodyId) {
        return createFixedJoint(firstBodyId, secondBodyId, false);
    }

    public PhysicsJointSnapshot createFixedJoint(PhysicsObjectId firstBodyId, PhysicsObjectId secondBodyId, boolean collideConnected) {
        ensureOpen();
        RigidPhysicsObject first = requireObject(firstBodyId, "firstBodyId");
        RigidPhysicsObject second = requireObject(secondBodyId, "secondBodyId");
        if (first == second) {
            throw new IllegalArgumentException("Fixed joints require two different physics objects");
        }

        PhysicsJoint joint = world.createFixedJoint(first.body(), second.body(), collideConnected, Float.MAX_VALUE, Float.MAX_VALUE);
        return addJoint(PhysicsJointId.random(), first, second, joint);
    }

    public PhysicsJointSnapshot createFixedJointAtWorldFrame(PhysicsObjectId firstBodyId, PhysicsObjectId secondBodyId, PhysicsPose worldFrame) {
        return createFixedJointAtWorldFrame(firstBodyId, secondBodyId, worldFrame, false);
    }

    public PhysicsJointSnapshot createFixedJointAtWorldFrame(
            PhysicsObjectId firstBodyId,
            PhysicsObjectId secondBodyId,
            PhysicsPose worldFrame,
            boolean collideConnected
    ) {
        ensureOpen();
        Objects.requireNonNull(worldFrame, "worldFrame");
        RigidPhysicsObject first = requireObject(firstBodyId, "firstBodyId");
        RigidPhysicsObject second = requireObject(secondBodyId, "secondBodyId");
        if (first == second) {
            throw new IllegalArgumentException("Fixed joints require two different physics objects");
        }

        PhysicsJoint joint = world.createFixedJointAtWorldFrame(
                first.body(),
                second.body(),
                worldFrame,
                collideConnected,
                Float.MAX_VALUE,
                Float.MAX_VALUE
        );
        return addJoint(PhysicsJointId.random(), first, second, joint);
    }

    public PhysicsJointSnapshot createFixedJointWithLocalFrames(
            PhysicsObjectId firstBodyId,
            PhysicsPose firstLocalFrame,
            PhysicsObjectId secondBodyId,
            PhysicsPose secondLocalFrame,
            boolean collideConnected
    ) {
        return createFixedJointWithLocalFrames(
                firstBodyId,
                firstLocalFrame,
                secondBodyId,
                secondLocalFrame,
                collideConnected,
                PhysicsJointId.random()
        );
    }

    public PhysicsJointSnapshot createFixedJointWithLocalFrames(
            PhysicsObjectId firstBodyId,
            PhysicsPose firstLocalFrame,
            PhysicsObjectId secondBodyId,
            PhysicsPose secondLocalFrame,
            boolean collideConnected,
            PhysicsJointId id
    ) {
        ensureOpen();
        Objects.requireNonNull(firstLocalFrame, "firstLocalFrame");
        Objects.requireNonNull(secondLocalFrame, "secondLocalFrame");
        Objects.requireNonNull(id, "id");
        ensureJointIdAvailable(id);
        RigidPhysicsObject first = requireObject(firstBodyId, "firstBodyId");
        RigidPhysicsObject second = requireObject(secondBodyId, "secondBodyId");
        if (first == second) {
            throw new IllegalArgumentException("Fixed joints require two different physics objects");
        }

        PhysicsJoint joint = world.createFixedJointWithLocalFrames(
                first.body(),
                firstLocalFrame,
                second.body(),
                secondLocalFrame,
                collideConnected,
                Float.MAX_VALUE,
                Float.MAX_VALUE
        );
        return addJoint(id, first, second, joint);
    }

    public PhysicsJointSnapshot createDistanceJoint(
            PhysicsObjectId firstBodyId,
            PhysicsObjectId secondBodyId,
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping
    ) {
        return createDistanceJoint(firstBodyId, secondBodyId, minDistance, maxDistance, stiffness, damping, false);
    }

    public PhysicsJointSnapshot createDistanceJoint(
            PhysicsObjectId firstBodyId,
            PhysicsObjectId secondBodyId,
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping,
            boolean collideConnected
    ) {
        ensureOpen();
        RigidPhysicsObject first = requireObject(firstBodyId, "firstBodyId");
        RigidPhysicsObject second = requireObject(secondBodyId, "secondBodyId");
        if (first == second) {
            throw new IllegalArgumentException("Distance joints require two different physics objects");
        }

        PhysicsJoint joint = world.createDistanceJoint(
                first.body(),
                second.body(),
                minDistance,
                maxDistance,
                stiffness,
                damping,
                collideConnected,
                Float.MAX_VALUE,
                Float.MAX_VALUE
        );
        return addJoint(PhysicsJointId.random(), first, second, joint);
    }

    public PhysicsJointSnapshot createDistanceJointAtWorldAnchors(
            PhysicsObjectId firstBodyId,
            PhysicsVector firstWorldAnchor,
            PhysicsObjectId secondBodyId,
            PhysicsVector secondWorldAnchor,
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping
    ) {
        return createDistanceJointAtWorldAnchors(
                firstBodyId,
                firstWorldAnchor,
                secondBodyId,
                secondWorldAnchor,
                minDistance,
                maxDistance,
                stiffness,
                damping,
                false
        );
    }

    public PhysicsJointSnapshot createDistanceJointAtWorldAnchors(
            PhysicsObjectId firstBodyId,
            PhysicsVector firstWorldAnchor,
            PhysicsObjectId secondBodyId,
            PhysicsVector secondWorldAnchor,
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping,
            boolean collideConnected
    ) {
        ensureOpen();
        Objects.requireNonNull(firstWorldAnchor, "firstWorldAnchor");
        Objects.requireNonNull(secondWorldAnchor, "secondWorldAnchor");
        RigidPhysicsObject first = requireObject(firstBodyId, "firstBodyId");
        RigidPhysicsObject second = requireObject(secondBodyId, "secondBodyId");
        if (first == second) {
            throw new IllegalArgumentException("Distance joints require two different physics objects");
        }

        PhysicsJoint joint = world.createDistanceJointAtWorldAnchors(
                first.body(),
                firstWorldAnchor,
                second.body(),
                secondWorldAnchor,
                minDistance,
                maxDistance,
                stiffness,
                damping,
                collideConnected,
                Float.MAX_VALUE,
                Float.MAX_VALUE
        );
        return addJoint(PhysicsJointId.random(), first, second, joint);
    }

    public PhysicsJointSnapshot createDistanceJointWithLocalAnchors(
            PhysicsObjectId firstBodyId,
            PhysicsVector firstLocalAnchor,
            PhysicsObjectId secondBodyId,
            PhysicsVector secondLocalAnchor,
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping,
            boolean collideConnected
    ) {
        return createDistanceJointWithLocalAnchors(
                firstBodyId,
                firstLocalAnchor,
                secondBodyId,
                secondLocalAnchor,
                minDistance,
                maxDistance,
                stiffness,
                damping,
                collideConnected,
                PhysicsJointId.random()
        );
    }

    public PhysicsJointSnapshot createDistanceJointWithLocalAnchors(
            PhysicsObjectId firstBodyId,
            PhysicsVector firstLocalAnchor,
            PhysicsObjectId secondBodyId,
            PhysicsVector secondLocalAnchor,
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping,
            boolean collideConnected,
            PhysicsJointId id
    ) {
        ensureOpen();
        Objects.requireNonNull(firstLocalAnchor, "firstLocalAnchor");
        Objects.requireNonNull(secondLocalAnchor, "secondLocalAnchor");
        Objects.requireNonNull(id, "id");
        ensureJointIdAvailable(id);
        RigidPhysicsObject first = requireObject(firstBodyId, "firstBodyId");
        RigidPhysicsObject second = requireObject(secondBodyId, "secondBodyId");
        if (first == second) {
            throw new IllegalArgumentException("Distance joints require two different physics objects");
        }

        PhysicsJoint joint = world.createDistanceJointWithLocalAnchors(
                first.body(),
                firstLocalAnchor,
                second.body(),
                secondLocalAnchor,
                minDistance,
                maxDistance,
                stiffness,
                damping,
                collideConnected,
                Float.MAX_VALUE,
                Float.MAX_VALUE
        );
        return addJoint(id, first, second, joint);
    }

    public PhysicsJointSnapshot createRevoluteJointAtWorldFrame(
            PhysicsObjectId firstBodyId,
            PhysicsObjectId secondBodyId,
            PhysicsPose worldFrame
    ) {
        return createRevoluteJointAtWorldFrame(firstBodyId, secondBodyId, worldFrame, false);
    }

    public PhysicsJointSnapshot createRevoluteJointAtWorldFrame(
            PhysicsObjectId firstBodyId,
            PhysicsObjectId secondBodyId,
            PhysicsPose worldFrame,
            boolean collideConnected
    ) {
        ensureOpen();
        Objects.requireNonNull(worldFrame, "worldFrame");
        RigidPhysicsObject first = requireObject(firstBodyId, "firstBodyId");
        RigidPhysicsObject second = requireObject(secondBodyId, "secondBodyId");
        if (first == second) {
            throw new IllegalArgumentException("Revolute joints require two different physics objects");
        }

        PhysicsJoint joint = world.createRevoluteJointAtWorldFrame(
                first.body(),
                second.body(),
                worldFrame,
                collideConnected,
                Float.MAX_VALUE,
                Float.MAX_VALUE
        );
        return addJoint(PhysicsJointId.random(), first, second, joint);
    }

    public PhysicsJointSnapshot createRevoluteJointWithLocalFrames(
            PhysicsObjectId firstBodyId,
            PhysicsPose firstLocalFrame,
            PhysicsObjectId secondBodyId,
            PhysicsPose secondLocalFrame,
            boolean collideConnected
    ) {
        return createRevoluteJointWithLocalFrames(
                firstBodyId,
                firstLocalFrame,
                secondBodyId,
                secondLocalFrame,
                collideConnected,
                PhysicsJointId.random()
        );
    }

    public PhysicsJointSnapshot createRevoluteJointWithLocalFrames(
            PhysicsObjectId firstBodyId,
            PhysicsPose firstLocalFrame,
            PhysicsObjectId secondBodyId,
            PhysicsPose secondLocalFrame,
            boolean collideConnected,
            PhysicsJointId id
    ) {
        ensureOpen();
        Objects.requireNonNull(firstLocalFrame, "firstLocalFrame");
        Objects.requireNonNull(secondLocalFrame, "secondLocalFrame");
        Objects.requireNonNull(id, "id");
        ensureJointIdAvailable(id);
        RigidPhysicsObject first = requireObject(firstBodyId, "firstBodyId");
        RigidPhysicsObject second = requireObject(secondBodyId, "secondBodyId");
        if (first == second) {
            throw new IllegalArgumentException("Revolute joints require two different physics objects");
        }

        PhysicsJoint joint = world.createRevoluteJointWithLocalFrames(
                first.body(),
                firstLocalFrame,
                second.body(),
                secondLocalFrame,
                collideConnected,
                Float.MAX_VALUE,
                Float.MAX_VALUE
        );
        return addJoint(id, first, second, joint);
    }

    public PhysicsJointSnapshot createPrismaticJointAtWorldFrame(
            PhysicsObjectId firstBodyId,
            PhysicsObjectId secondBodyId,
            PhysicsPose worldFrame
    ) {
        return createPrismaticJointAtWorldFrame(firstBodyId, secondBodyId, worldFrame, false);
    }

    public PhysicsJointSnapshot createPrismaticJointAtWorldFrame(
            PhysicsObjectId firstBodyId,
            PhysicsObjectId secondBodyId,
            PhysicsPose worldFrame,
            boolean collideConnected
    ) {
        ensureOpen();
        Objects.requireNonNull(worldFrame, "worldFrame");
        RigidPhysicsObject first = requireObject(firstBodyId, "firstBodyId");
        RigidPhysicsObject second = requireObject(secondBodyId, "secondBodyId");
        if (first == second) {
            throw new IllegalArgumentException("Prismatic joints require two different physics objects");
        }

        PhysicsJoint joint = world.createPrismaticJointAtWorldFrame(
                first.body(),
                second.body(),
                worldFrame,
                collideConnected,
                Float.MAX_VALUE,
                Float.MAX_VALUE
        );
        return addJoint(PhysicsJointId.random(), first, second, joint);
    }

    public PhysicsJointSnapshot createPrismaticJointWithLocalFrames(
            PhysicsObjectId firstBodyId,
            PhysicsPose firstLocalFrame,
            PhysicsObjectId secondBodyId,
            PhysicsPose secondLocalFrame,
            boolean collideConnected
    ) {
        return createPrismaticJointWithLocalFrames(
                firstBodyId,
                firstLocalFrame,
                secondBodyId,
                secondLocalFrame,
                collideConnected,
                PhysicsJointId.random()
        );
    }

    public PhysicsJointSnapshot createPrismaticJointWithLocalFrames(
            PhysicsObjectId firstBodyId,
            PhysicsPose firstLocalFrame,
            PhysicsObjectId secondBodyId,
            PhysicsPose secondLocalFrame,
            boolean collideConnected,
            PhysicsJointId id
    ) {
        ensureOpen();
        Objects.requireNonNull(firstLocalFrame, "firstLocalFrame");
        Objects.requireNonNull(secondLocalFrame, "secondLocalFrame");
        Objects.requireNonNull(id, "id");
        ensureJointIdAvailable(id);
        RigidPhysicsObject first = requireObject(firstBodyId, "firstBodyId");
        RigidPhysicsObject second = requireObject(secondBodyId, "secondBodyId");
        if (first == second) {
            throw new IllegalArgumentException("Prismatic joints require two different physics objects");
        }

        PhysicsJoint joint = world.createPrismaticJointWithLocalFrames(
                first.body(),
                firstLocalFrame,
                second.body(),
                secondLocalFrame,
                collideConnected,
                Float.MAX_VALUE,
                Float.MAX_VALUE
        );
        return addJoint(id, first, second, joint);
    }

    public Optional<PhysicsDeformableVolume> deformableVolume(PhysicsObjectId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(deformableVolumes.get(id));
    }

    public Optional<PhysicsObject> object(PhysicsObjectId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(objects.get(id));
    }

    public Optional<PhysicsJointSnapshot> joint(PhysicsJointId id) {
        Objects.requireNonNull(id, "id");
        RigidPhysicsJoint joint = joints.get(id);
        return joint == null ? Optional.empty() : Optional.of(joint.snapshot());
    }

    public Collection<PhysicsObjectSnapshot> snapshots() {
        return objects.values().stream()
                .map(PhysicsObject::snapshot)
                .toList();
    }

    public Collection<PhysicsJointSnapshot> jointSnapshots() {
        return joints.values().stream()
                .map(RigidPhysicsJoint::snapshot)
                .toList();
    }

    public Map<PhysicsObjectId, PhysicsObjectSnapshot> snapshots(List<PhysicsObjectId> ids) {
        ensureOpen();
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            return Map.of();
        }

        List<RigidPhysicsObject> requestedObjects = new ArrayList<>();
        List<PhysicsBody> requestedBodies = new ArrayList<>();
        for (PhysicsObjectId id : ids) {
            Objects.requireNonNull(id, "id");
            RigidPhysicsObject object = objects.get(id);
            if (object == null || object.isClosed()) {
                continue;
            }
            requestedObjects.add(object);
            requestedBodies.add(object.body());
        }
        if (requestedObjects.isEmpty()) {
            return Map.of();
        }

        Map<PhysicsBody, PhysicsBodyState> statesByBody = new LinkedHashMap<>();
        for (PhysicsBodyState state : world.readBodyStates(requestedBodies)) {
            statesByBody.put(state.body(), state);
        }

        Map<PhysicsObjectId, PhysicsObjectSnapshot> snapshots = new LinkedHashMap<>();
        for (RigidPhysicsObject object : requestedObjects) {
            PhysicsBodyState state = statesByBody.get(object.body());
            if (state == null) {
                continue;
            }
            snapshots.put(object.id(), object.snapshot(state));
        }
        return Map.copyOf(snapshots);
    }

    public Collection<PhysicsObjectSnapshot> snapshotsOfType(PhysicsObjectType type) {
        Objects.requireNonNull(type, "type");
        return objects.values().stream()
                .filter(object -> object.type() == type)
                .map(PhysicsObject::snapshot)
                .toList();
    }

    public List<PhysicsObject> objectsOfType(PhysicsObjectType type) {
        Objects.requireNonNull(type, "type");
        return objects.values().stream()
                .filter(object -> object.type() == type)
                .map(PhysicsObject.class::cast)
                .toList();
    }

    public void clearObjects() {
        for (RigidPhysicsJoint joint : List.copyOf(joints.values())) {
            joint.close();
        }
        joints.clear();
        for (RigidPhysicsObject object : List.copyOf(objects.values())) {
            object.close();
        }
        objects.clear();
        for (PhysicsDeformableVolume deformableVolume : List.copyOf(deformableVolumes.values())) {
            deformableVolume.close();
        }
        deformableVolumes.clear();
    }

    public boolean removeDeformableVolume(PhysicsObjectId id) {
        Objects.requireNonNull(id, "id");
        PhysicsDeformableVolume deformableVolume = deformableVolumes.remove(id);
        if (deformableVolume == null) {
            return false;
        }
        deformableVolume.close();
        return true;
    }

    public boolean removeObject(PhysicsObjectId id) {
        Objects.requireNonNull(id, "id");
        RigidPhysicsObject object = objects.get(id);
        if (object == null) {
            return false;
        }
        object.close();
        return true;
    }

    public boolean removeJoint(PhysicsJointId id) {
        Objects.requireNonNull(id, "id");
        RigidPhysicsJoint joint = joints.get(id);
        if (joint == null) {
            return false;
        }
        joint.close();
        return true;
    }

    public void step(float deltaSeconds) {
        ensureOpen();
        world.step(deltaSeconds);
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        clearObjects();
        world.close();
    }

    void forgetObject(PhysicsObjectId id) {
        objects.remove(id);
    }

    void forgetJoint(PhysicsJointId id) {
        joints.remove(id);
    }

    void removeJointsForObject(PhysicsObjectId objectId) {
        Objects.requireNonNull(objectId, "objectId");
        for (RigidPhysicsJoint joint : List.copyOf(joints.values())) {
            if (joint.references(objectId)) {
                joint.close();
            }
        }
    }

    private PhysicsObject addObject(PhysicsObjectType type, PhysicsBody body, PhysicsShape ownedShape) {
        return addObject(PhysicsObjectId.random(), type, body, ownedShape);
    }

    private PhysicsObject addObject(PhysicsObjectId id, PhysicsObjectType type, PhysicsBody body, PhysicsShape ownedShape) {
        RigidPhysicsObject object = new RigidPhysicsObject(this, id, type, body, ownedShape);
        objects.put(id, object);
        return object;
    }

    private PhysicsJointSnapshot addJoint(PhysicsJointId id, RigidPhysicsObject first, RigidPhysicsObject second, PhysicsJoint joint) {
        RigidPhysicsJoint rigidJoint = new RigidPhysicsJoint(this, id, first.id(), second.id(), joint);
        joints.put(id, rigidJoint);
        return rigidJoint.snapshot();
    }

    private void ensureObjectIdAvailable(PhysicsObjectId id) {
        if (objects.containsKey(id)) {
            throw new IllegalArgumentException("Physics object id already exists in this scene: " + id);
        }
    }

    private void ensureJointIdAvailable(PhysicsJointId id) {
        if (joints.containsKey(id)) {
            throw new IllegalArgumentException("Physics joint id already exists in this scene: " + id);
        }
    }

    private RigidPhysicsObject requireObject(PhysicsObjectId id, String name) {
        Objects.requireNonNull(id, name);
        RigidPhysicsObject object = objects.get(id);
        if (object == null) {
            throw new IllegalArgumentException(name + " does not exist in this physics scene");
        }
        if (object.isClosed()) {
            throw new IllegalArgumentException(name + " is closed");
        }
        return object;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Physics scene is closed: " + sceneKey);
        }
    }
}
