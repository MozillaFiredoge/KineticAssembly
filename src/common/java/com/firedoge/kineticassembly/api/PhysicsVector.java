package com.firedoge.kineticassembly.api;

public record PhysicsVector(double x, double y, double z) {
    public static final PhysicsVector ZERO = new PhysicsVector(0.0D, 0.0D, 0.0D);
    public static final PhysicsVector MC_GRAVITY = new PhysicsVector(0.0D, -9.81D, 0.0D);
}
