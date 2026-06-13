package com.firedoge.kineticassembly.api;

import java.util.Objects;

public record PhysicsBoxCollider(PhysicsVector center, PhysicsVector halfExtents) {
    public PhysicsBoxCollider {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(halfExtents, "halfExtents");
        validateFinite(center, "center");
        validateFinite(halfExtents, "halfExtents");
        if (halfExtents.x() <= 0.0D || halfExtents.y() <= 0.0D || halfExtents.z() <= 0.0D) {
            throw new IllegalArgumentException("Box collider half extents must be positive");
        }
    }

    private static void validateFinite(PhysicsVector vector, String name) {
        if (!Double.isFinite(vector.x()) || !Double.isFinite(vector.y()) || !Double.isFinite(vector.z())) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
