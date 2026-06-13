package com.firedoge.kineticassembly.api;

public interface PhysicsBackend {
    String id();

    boolean isAvailable();

    PhysicsWorld createWorld(PhysicsWorldConfig config);
}
