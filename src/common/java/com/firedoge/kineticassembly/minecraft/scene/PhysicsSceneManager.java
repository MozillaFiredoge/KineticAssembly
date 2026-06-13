package com.firedoge.kineticassembly.minecraft.scene;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.firedoge.kineticassembly.api.PhysicsBackend;
import com.firedoge.kineticassembly.api.PhysicsWorldConfig;

public final class PhysicsSceneManager implements AutoCloseable {
    private final Map<String, ServerPhysicsScene> scenes = new LinkedHashMap<>();

    public synchronized ServerPhysicsScene createScene(String sceneKey, PhysicsBackend backend, PhysicsWorldConfig config) {
        Objects.requireNonNull(sceneKey, "sceneKey");
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(config, "config");
        if (scenes.containsKey(sceneKey)) {
            throw new IllegalArgumentException("Physics scene already exists: " + sceneKey);
        }
        ServerPhysicsScene scene = new ServerPhysicsScene(sceneKey, backend, config);
        scenes.put(sceneKey, scene);
        return scene;
    }

    public synchronized Optional<ServerPhysicsScene> scene(String sceneKey) {
        return Optional.ofNullable(scenes.get(sceneKey));
    }

    public synchronized Collection<ServerPhysicsScene> scenes() {
        return List.copyOf(scenes.values());
    }

    public synchronized boolean closeScene(String sceneKey) {
        ServerPhysicsScene scene = scenes.remove(sceneKey);
        if (scene == null) {
            return false;
        }
        scene.close();
        return true;
    }

    @Override
    public synchronized void close() {
        for (ServerPhysicsScene scene : List.copyOf(scenes.values())) {
            scene.close();
        }
        scenes.clear();
    }
}
