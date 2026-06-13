package com.firedoge.kineticassembly.physics;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import com.firedoge.kineticassembly.api.PhysicsBackend;
import com.firedoge.kineticassembly.api.PhysicsEngine;
import com.firedoge.kineticassembly.api.PhysicsWorld;
import com.firedoge.kineticassembly.api.PhysicsWorldConfig;
import com.firedoge.kineticassembly.backend.BackendRegistry;

public final class PhysicsManager implements PhysicsEngine {
    public static final PhysicsManager INSTANCE = new PhysicsManager();

    private final BackendRegistry backends = new BackendRegistry();
    private final List<PhysicsWorld> worlds = new CopyOnWriteArrayList<>();

    private PhysicsManager() {
    }

    public void registerBackend(PhysicsBackend backend) {
        backends.register(backend);
    }

    @Override
    public Iterable<PhysicsBackend> backends() {
        return backends.all();
    }

    @Override
    public Optional<PhysicsBackend> backend(String id) {
        return backends.get(id);
    }

    @Override
    public PhysicsWorld createWorld(String backendId, PhysicsWorldConfig config) {
        Objects.requireNonNull(backendId, "backendId");
        Objects.requireNonNull(config, "config");
        PhysicsBackend backend = backends.get(backendId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown physics backend: " + backendId));
        PhysicsWorld world = backend.createWorld(config);
        worlds.add(world);
        return world;
    }

    public void tick(float deltaSeconds) {
        for (PhysicsWorld world : worlds) {
            if (!world.isClosed()) {
                world.step(deltaSeconds);
            }
        }
    }

    public void closeWorld(PhysicsWorld world) {
        Objects.requireNonNull(world, "world");
        world.close();
        worlds.remove(world);
    }

    public void shutdown() {
        for (PhysicsWorld world : worlds) {
            world.close();
        }
        worlds.clear();
    }
}
