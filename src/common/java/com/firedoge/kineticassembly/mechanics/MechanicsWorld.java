package com.firedoge.kineticassembly.mechanics;

import java.util.List;
import java.util.Optional;

import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsQuaternion;
import com.firedoge.kineticassembly.api.PhysicsVector;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public interface MechanicsWorld {
    ResourceKey<Level> levelKey();

    MechanicsBodySnapshot createDynamicBox(MechanicsBoxDefinition definition);

    default MechanicsBodySnapshot createDynamicBox(MechanicsBodyId id, MechanicsBoxDefinition definition) {
        throw new UnsupportedOperationException("Stable body ids are not supported by this mechanics world");
    }

    default MechanicsBodySnapshot createDynamicBox(MechanicsOwner owner, MechanicsBoxDefinition definition) {
        return createDynamicBox(definition);
    }

    default MechanicsBodySnapshot createDynamicBox(MechanicsOwner owner, MechanicsBodyId id, MechanicsBoxDefinition definition) {
        return createDynamicBox(id, definition);
    }

    MechanicsBodySnapshot createDynamicCompoundBox(MechanicsCompoundBoxDefinition definition);

    default MechanicsBodySnapshot createDynamicCompoundBox(MechanicsBodyId id, MechanicsCompoundBoxDefinition definition) {
        throw new UnsupportedOperationException("Stable body ids are not supported by this mechanics world");
    }

    default MechanicsBodySnapshot createDynamicCompoundBox(MechanicsOwner owner, MechanicsCompoundBoxDefinition definition) {
        return createDynamicCompoundBox(definition);
    }

    default MechanicsBodySnapshot createDynamicCompoundBox(
            MechanicsOwner owner,
            MechanicsBodyId id,
            MechanicsCompoundBoxDefinition definition
    ) {
        return createDynamicCompoundBox(id, definition);
    }

    default MechanicsResult<MechanicsAssemblySnapshot> assembleBlock(BlockPos pos) {
        return assembleBlock(pos, MechanicsAssemblyOptions.DEFAULT);
    }

    default MechanicsResult<MechanicsAssemblySnapshot> assembleBlock(BlockPos pos, MechanicsAssemblyOptions options) {
        return MechanicsResult.failure(MechanicsResultCode.UNSUPPORTED, "Block assembly is not supported by this mechanics world");
    }

    default MechanicsResult<MechanicsAssemblySnapshot> assembleBox(BlockPos first, BlockPos second) {
        return assembleBox(first, second, MechanicsAssemblyOptions.DEFAULT);
    }

    default MechanicsResult<MechanicsAssemblySnapshot> assembleBox(
            BlockPos first,
            BlockPos second,
            MechanicsAssemblyOptions options
    ) {
        return MechanicsResult.failure(MechanicsResultCode.UNSUPPORTED, "Block assembly is not supported by this mechanics world");
    }

    default MechanicsJointSnapshot createFixedJoint(MechanicsBodyId firstBodyId, MechanicsBodyId secondBodyId) {
        return createFixedJoint(firstBodyId, secondBodyId, false);
    }

    default MechanicsJointSnapshot createFixedJoint(MechanicsBodyId firstBodyId, MechanicsBodyId secondBodyId, boolean collideConnected) {
        throw new UnsupportedOperationException("Fixed joints are not supported by this mechanics world");
    }

    default MechanicsJointSnapshot createFixedJointAtWorldFrame(
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            PhysicsPose worldFrame
    ) {
        return createFixedJointAtWorldFrame(firstBodyId, secondBodyId, worldFrame, false);
    }

    default MechanicsJointSnapshot createFixedJointAtWorldFrame(
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            PhysicsPose worldFrame,
            boolean collideConnected
    ) {
        throw new UnsupportedOperationException("Fixed joint world frames are not supported by this mechanics world");
    }

    default MechanicsJointSnapshot createFixedJointAtWorldAnchor(
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            PhysicsVector worldAnchor
    ) {
        return createFixedJointAtWorldFrame(firstBodyId, secondBodyId, new PhysicsPose(worldAnchor, PhysicsQuaternion.IDENTITY));
    }

    default MechanicsJointSnapshot createFixedJointAtWorldAnchor(
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            PhysicsVector worldAnchor,
            boolean collideConnected
    ) {
        return createFixedJointAtWorldFrame(
                firstBodyId,
                secondBodyId,
                new PhysicsPose(worldAnchor, PhysicsQuaternion.IDENTITY),
                collideConnected
        );
    }

    default MechanicsJointSnapshot createRevoluteJointAtWorldFrame(
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            PhysicsPose worldFrame
    ) {
        return createRevoluteJointAtWorldFrame(firstBodyId, secondBodyId, worldFrame, false);
    }

    default MechanicsJointSnapshot createRevoluteJointAtWorldFrame(
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            PhysicsPose worldFrame,
            boolean collideConnected
    ) {
        throw new UnsupportedOperationException("Revolute joint world frames are not supported by this mechanics world");
    }

    default MechanicsJointSnapshot createRevoluteJointAtWorldAxis(
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            PhysicsVector worldAnchor,
            PhysicsVector worldAxis
    ) {
        return createRevoluteJointAtWorldAxis(firstBodyId, secondBodyId, worldAnchor, worldAxis, false);
    }

    default MechanicsJointSnapshot createRevoluteJointAtWorldAxis(
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            PhysicsVector worldAnchor,
            PhysicsVector worldAxis,
            boolean collideConnected
    ) {
        return createRevoluteJointAtWorldFrame(
                firstBodyId,
                secondBodyId,
                new PhysicsPose(worldAnchor, frameRotationFromXAxis(worldAxis)),
                collideConnected
        );
    }

    default MechanicsJointSnapshot createPrismaticJointAtWorldFrame(
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            PhysicsPose worldFrame
    ) {
        return createPrismaticJointAtWorldFrame(firstBodyId, secondBodyId, worldFrame, false);
    }

    default MechanicsJointSnapshot createPrismaticJointAtWorldFrame(
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            PhysicsPose worldFrame,
            boolean collideConnected
    ) {
        throw new UnsupportedOperationException("Prismatic joint world frames are not supported by this mechanics world");
    }

    default MechanicsJointSnapshot createPrismaticJointAtWorldAxis(
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            PhysicsVector worldAnchor,
            PhysicsVector worldAxis
    ) {
        return createPrismaticJointAtWorldAxis(firstBodyId, secondBodyId, worldAnchor, worldAxis, false);
    }

    default MechanicsJointSnapshot createPrismaticJointAtWorldAxis(
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            PhysicsVector worldAnchor,
            PhysicsVector worldAxis,
            boolean collideConnected
    ) {
        return createPrismaticJointAtWorldFrame(
                firstBodyId,
                secondBodyId,
                new PhysicsPose(worldAnchor, frameRotationFromXAxis(worldAxis)),
                collideConnected
        );
    }

    default MechanicsJointSnapshot createDistanceJoint(
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            float minDistance,
            float maxDistance
    ) {
        return createDistanceJoint(firstBodyId, secondBodyId, minDistance, maxDistance, false);
    }

    default MechanicsJointSnapshot createDistanceJoint(
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            float minDistance,
            float maxDistance,
            boolean collideConnected
    ) {
        return createDistanceJoint(firstBodyId, secondBodyId, minDistance, maxDistance, 0.0F, 0.0F, collideConnected);
    }

    default MechanicsJointSnapshot createDistanceJoint(
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping
    ) {
        return createDistanceJoint(firstBodyId, secondBodyId, minDistance, maxDistance, stiffness, damping, false);
    }

    default MechanicsJointSnapshot createDistanceJoint(
            MechanicsBodyId firstBodyId,
            MechanicsBodyId secondBodyId,
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping,
            boolean collideConnected
    ) {
        throw new UnsupportedOperationException("Distance joints are not supported by this mechanics world");
    }

    default MechanicsJointSnapshot createDistanceJointAtWorldAnchors(
            MechanicsBodyId firstBodyId,
            PhysicsVector firstWorldAnchor,
            MechanicsBodyId secondBodyId,
            PhysicsVector secondWorldAnchor,
            float minDistance,
            float maxDistance
    ) {
        return createDistanceJointAtWorldAnchors(
                firstBodyId,
                firstWorldAnchor,
                secondBodyId,
                secondWorldAnchor,
                minDistance,
                maxDistance,
                0.0F,
                0.0F
        );
    }

    default MechanicsJointSnapshot createDistanceJointAtWorldAnchors(
            MechanicsBodyId firstBodyId,
            PhysicsVector firstWorldAnchor,
            MechanicsBodyId secondBodyId,
            PhysicsVector secondWorldAnchor,
            float minDistance,
            float maxDistance,
            boolean collideConnected
    ) {
        return createDistanceJointAtWorldAnchors(
                firstBodyId,
                firstWorldAnchor,
                secondBodyId,
                secondWorldAnchor,
                minDistance,
                maxDistance,
                0.0F,
                0.0F,
                collideConnected
        );
    }

    default MechanicsJointSnapshot createDistanceJointAtWorldAnchors(
            MechanicsBodyId firstBodyId,
            PhysicsVector firstWorldAnchor,
            MechanicsBodyId secondBodyId,
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

    default MechanicsJointSnapshot createDistanceJointAtWorldAnchors(
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
        throw new UnsupportedOperationException("Distance joint world anchors are not supported by this mechanics world");
    }

    Optional<MechanicsBodySnapshot> snapshot(MechanicsBodyId id);

    default Optional<MechanicsJointSnapshot> jointSnapshot(MechanicsJointId id) {
        return Optional.empty();
    }

    default Optional<PhysicsPose> pose(MechanicsBodyId id) {
        return snapshot(id)
                .filter(body -> !body.closed())
                .map(MechanicsBodySnapshot::pose);
    }

    List<MechanicsBodySnapshot> snapshots();

    default List<MechanicsJointSnapshot> jointSnapshots() {
        return List.of();
    }

    boolean setPose(MechanicsBodyId id, PhysicsPose pose);

    boolean setLinearVelocity(MechanicsBodyId id, PhysicsVector velocity);

    boolean setAngularVelocity(MechanicsBodyId id, PhysicsVector velocity);

    boolean applyLinearImpulse(MechanicsBodyId id, PhysicsVector impulse);

    boolean applyAngularImpulse(MechanicsBodyId id, PhysicsVector impulse);

    boolean applyImpulseAtPoint(MechanicsBodyId id, PhysicsVector impulse, PhysicsVector point);

    default MechanicsResult<Void> applyForce(MechanicsBodyId id, PhysicsVector force) {
        return MechanicsResult.failure(MechanicsResultCode.UNSUPPORTED, "Forces are not supported by this mechanics world");
    }

    default MechanicsResult<Void> applyTorque(MechanicsBodyId id, PhysicsVector torque) {
        return MechanicsResult.failure(MechanicsResultCode.UNSUPPORTED, "Torques are not supported by this mechanics world");
    }

    default MechanicsResult<Void> applyForceAtPoint(MechanicsBodyId id, PhysicsVector force, PhysicsVector point) {
        return MechanicsResult.failure(MechanicsResultCode.UNSUPPORTED, "Point forces are not supported by this mechanics world");
    }

    boolean removeBody(MechanicsBodyId id);

    default boolean removeJoint(MechanicsJointId id) {
        return false;
    }

    private static PhysicsQuaternion frameRotationFromXAxis(PhysicsVector axis) {
        double length = Math.sqrt(axis.x() * axis.x() + axis.y() * axis.y() + axis.z() * axis.z());
        if (!Double.isFinite(length) || length <= 1.0E-9D) {
            throw new IllegalArgumentException("Joint axis must be finite and non-zero");
        }

        double axisX = axis.x() / length;
        double axisY = axis.y() / length;
        double axisZ = axis.z() / length;
        double dot = axisX;
        if (dot > 0.999999D) {
            return PhysicsQuaternion.IDENTITY;
        }
        if (dot < -0.999999D) {
            return new PhysicsQuaternion(0.0D, 1.0D, 0.0D, 0.0D);
        }

        double scale = Math.sqrt((1.0D + dot) * 2.0D);
        double inverseScale = 1.0D / scale;
        return new PhysicsQuaternion(
                0.0D,
                -axisZ * inverseScale,
                axisY * inverseScale,
                scale * 0.5D
        );
    }
}
