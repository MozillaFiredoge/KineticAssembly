package com.firedoge.kineticassembly.backend;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.firedoge.kineticassembly.api.PhysicsBackend;

public final class BackendRegistry {
    private final Map<String, PhysicsBackend> backends = new LinkedHashMap<>();

    public synchronized void register(PhysicsBackend backend) {
        Objects.requireNonNull(backend, "backend");
        PhysicsBackend previous = backends.putIfAbsent(backend.id(), backend);
        if (previous != null) {
            throw new IllegalArgumentException("Physics backend already registered: " + backend.id());
        }
    }

    public synchronized Optional<PhysicsBackend> get(String id) {
        return Optional.ofNullable(backends.get(id));
    }

    public synchronized Collection<PhysicsBackend> all() {
        return Collections.unmodifiableCollection(backends.values());
    }
}
