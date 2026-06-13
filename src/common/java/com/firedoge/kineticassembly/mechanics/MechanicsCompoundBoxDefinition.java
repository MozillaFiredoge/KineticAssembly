package com.firedoge.kineticassembly.mechanics;

import java.util.List;
import java.util.Objects;

import com.firedoge.kineticassembly.api.PhysicsBoxCollider;
import com.firedoge.kineticassembly.api.PhysicsMassProperties;
import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsVector;
import org.jetbrains.annotations.Nullable;

public record MechanicsCompoundBoxDefinition(
        PhysicsPose pose,
        List<PhysicsBoxCollider> boxes,
        PhysicsVector halfExtents,
        float mass,
        @Nullable PhysicsMassProperties massProperties,
        MechanicsBodyRole role
) {
    public MechanicsCompoundBoxDefinition {
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(boxes, "boxes");
        Objects.requireNonNull(halfExtents, "halfExtents");
        boxes = List.copyOf(boxes);
        if (boxes.isEmpty()) {
            throw new IllegalArgumentException("Compound boxes must not be empty");
        }
        for (PhysicsBoxCollider box : boxes) {
            Objects.requireNonNull(box, "box");
        }
        if (halfExtents.x() <= 0.0D || halfExtents.y() <= 0.0D || halfExtents.z() <= 0.0D) {
            throw new IllegalArgumentException("Compound aggregate half extents must be positive");
        }
        if (!Float.isFinite(mass) || mass <= 0.0F) {
            throw new IllegalArgumentException("Compound box mass must be a finite positive float");
        }
        if (massProperties != null && Math.abs(massProperties.mass() - mass) > Math.max(1.0E-4F, mass * 1.0E-4F)) {
            throw new IllegalArgumentException("Compound box mass must match mass properties");
        }
        if (role == null) {
            role = MechanicsBodyRole.GAMEPLAY;
        }
    }

    public MechanicsCompoundBoxDefinition(
            PhysicsPose pose,
            List<PhysicsBoxCollider> boxes,
            PhysicsVector halfExtents,
            float mass,
            MechanicsBodyRole role
    ) {
        this(pose, boxes, halfExtents, mass, null, role);
    }

    public static MechanicsCompoundBoxDefinition gameplayDynamicCompoundBox(
            PhysicsPose pose,
            List<PhysicsBoxCollider> boxes,
            PhysicsVector halfExtents,
            float mass
    ) {
        return new MechanicsCompoundBoxDefinition(pose, boxes, halfExtents, mass, MechanicsBodyRole.GAMEPLAY);
    }

    public static MechanicsCompoundBoxDefinition gameplayDynamicCompoundBox(
            PhysicsPose pose,
            List<PhysicsBoxCollider> boxes,
            PhysicsVector halfExtents,
            PhysicsMassProperties massProperties
    ) {
        Objects.requireNonNull(massProperties, "massProperties");
        return new MechanicsCompoundBoxDefinition(pose, boxes, halfExtents, massProperties.mass(), massProperties, MechanicsBodyRole.GAMEPLAY);
    }
}
