package com.firedoge.kineticassembly.api;

import java.util.Objects;

public record PhysicsMassProperties(
        float mass,
        PhysicsVector centerOfMass,
        PhysicsVector inertiaTensor
) {
    public PhysicsMassProperties {
        Objects.requireNonNull(centerOfMass, "centerOfMass");
        Objects.requireNonNull(inertiaTensor, "inertiaTensor");
        if (!Float.isFinite(mass) || mass <= 0.0F) {
            throw new IllegalArgumentException("mass must be a finite positive float");
        }
        validateFinite(centerOfMass, "centerOfMass");
        validateFinite(inertiaTensor, "inertiaTensor");
        if (inertiaTensor.x() <= 0.0D || inertiaTensor.y() <= 0.0D || inertiaTensor.z() <= 0.0D) {
            throw new IllegalArgumentException("inertiaTensor components must be positive");
        }
    }

    public static PhysicsMassProperties uniform(float mass) {
        return new PhysicsMassProperties(mass, PhysicsVector.ZERO, new PhysicsVector(1.0D, 1.0D, 1.0D));
    }

    private static void validateFinite(PhysicsVector vector, String name) {
        if (!Double.isFinite(vector.x()) || !Double.isFinite(vector.y()) || !Double.isFinite(vector.z())) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
