package com.firedoge.kineticassembly.mechanics;

import java.util.Objects;
import java.util.UUID;

public record MechanicsBodyId(UUID value) {
    public MechanicsBodyId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
