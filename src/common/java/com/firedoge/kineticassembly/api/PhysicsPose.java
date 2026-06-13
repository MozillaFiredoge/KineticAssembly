package com.firedoge.kineticassembly.api;

import java.util.Objects;

public record PhysicsPose(PhysicsVector position, PhysicsQuaternion rotation) {
    public static final PhysicsPose IDENTITY = new PhysicsPose(PhysicsVector.ZERO, PhysicsQuaternion.IDENTITY);

    public PhysicsPose {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(rotation, "rotation");
    }
}
