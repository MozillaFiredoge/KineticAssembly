package com.firedoge.kineticassembly.backend.physx;

import java.util.Objects;

public record PhysXMaterial(long nativeHandle, com.firedoge.kineticassembly.api.PhysicsMaterial definition) {
    public PhysXMaterial {
        Objects.requireNonNull(definition, "definition");
    }
}
