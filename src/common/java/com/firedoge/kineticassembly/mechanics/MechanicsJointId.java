package com.firedoge.kineticassembly.mechanics;

import java.util.Objects;
import java.util.UUID;

public record MechanicsJointId(UUID value) {
    public MechanicsJointId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
