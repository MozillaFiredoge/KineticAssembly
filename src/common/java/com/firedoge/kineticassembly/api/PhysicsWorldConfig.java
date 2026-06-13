package com.firedoge.kineticassembly.api;

import java.util.Objects;

public record PhysicsWorldConfig(PhysicsVector gravity, float fixedTimeStep, int maxSubSteps, boolean enableGpuDynamics) {
    public static final PhysicsWorldConfig DEFAULT = new PhysicsWorldConfig(PhysicsVector.MC_GRAVITY, 1.0F / 20.0F, 4, false);

    public PhysicsWorldConfig(PhysicsVector gravity, float fixedTimeStep, int maxSubSteps) {
        this(gravity, fixedTimeStep, maxSubSteps, false);
    }

    public PhysicsWorldConfig {
        Objects.requireNonNull(gravity, "gravity");
        if (fixedTimeStep <= 0.0F) {
            throw new IllegalArgumentException("fixedTimeStep must be positive");
        }
        if (maxSubSteps < 1) {
            throw new IllegalArgumentException("maxSubSteps must be at least 1");
        }
    }
}
