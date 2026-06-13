package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.Objects;
import java.util.UUID;

public record AssemblyId(UUID value) {
    public AssemblyId {
        Objects.requireNonNull(value, "value");
    }

    public static AssemblyId random() {
        return new AssemblyId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
