package com.firedoge.kineticassembly.api;

public record PhysicsQuaternion(double x, double y, double z, double w) {
    public static final PhysicsQuaternion IDENTITY = new PhysicsQuaternion(0.0D, 0.0D, 0.0D, 1.0D);
}
