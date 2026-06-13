package com.firedoge.kineticassembly.minecraft.scene;

import java.util.Objects;
import java.util.UUID;

public record PhysicsJointId(UUID value) {
    public PhysicsJointId {
        Objects.requireNonNull(value, "value");
    }

    public static PhysicsJointId random() {
        return new PhysicsJointId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
