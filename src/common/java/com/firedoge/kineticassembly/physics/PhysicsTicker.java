package com.firedoge.kineticassembly.physics;

public final class PhysicsTicker {
    private final PhysicsManager manager;
    private final float fixedTimeStep;
    private final int maxSubSteps;
    private float accumulator;

    public PhysicsTicker(PhysicsManager manager, float fixedTimeStep, int maxSubSteps) {
        if (fixedTimeStep <= 0.0F) {
            throw new IllegalArgumentException("fixedTimeStep must be positive");
        }
        if (maxSubSteps < 1) {
            throw new IllegalArgumentException("maxSubSteps must be at least 1");
        }
        this.manager = manager;
        this.fixedTimeStep = fixedTimeStep;
        this.maxSubSteps = maxSubSteps;
    }

    public void advance(float deltaSeconds) {
        accumulator += Math.max(0.0F, deltaSeconds);
        int steps = 0;
        while (accumulator >= fixedTimeStep && steps < maxSubSteps) {
            manager.tick(fixedTimeStep);
            accumulator -= fixedTimeStep;
            steps++;
        }
        if (steps == maxSubSteps) {
            accumulator = 0.0F;
        }
    }
}
