package com.firedoge.kineticassembly.api;

public record PhysicsMaterial(float staticFriction, float dynamicFriction, float restitution) {
    public static final PhysicsMaterial DEFAULT = new PhysicsMaterial(0.6F, 0.6F, 0.0F);

    public PhysicsMaterial {
        if (staticFriction < 0.0F || dynamicFriction < 0.0F) {
            throw new IllegalArgumentException("Friction values must be non-negative");
        }
        if (restitution < 0.0F || restitution > 1.0F) {
            throw new IllegalArgumentException("Restitution must be between 0 and 1");
        }
    }
}
