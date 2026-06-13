package com.firedoge.kineticassembly.api;

import java.util.Objects;

public record PhysicsBodyState(
        PhysicsBody body,
        PhysicsPose pose,
        PhysicsVector linearVelocity,
        PhysicsVector angularVelocity
) {
    public PhysicsBodyState {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(linearVelocity, "linearVelocity");
        Objects.requireNonNull(angularVelocity, "angularVelocity");
    }
}
