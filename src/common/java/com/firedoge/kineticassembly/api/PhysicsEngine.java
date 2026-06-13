package com.firedoge.kineticassembly.api;

import java.util.Optional;

public interface PhysicsEngine {
    Iterable<PhysicsBackend> backends();

    Optional<PhysicsBackend> backend(String id);

    PhysicsWorld createWorld(String backendId, PhysicsWorldConfig config);
}
