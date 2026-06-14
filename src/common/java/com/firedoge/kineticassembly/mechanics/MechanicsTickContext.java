package com.firedoge.kineticassembly.mechanics;

import java.util.Objects;

import net.minecraft.server.MinecraftServer;

public record MechanicsTickContext(
        MinecraftServer server,
        MechanicsTickPhase phase,
        float deltaSeconds
) {
    public MechanicsTickContext {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(phase, "phase");
    }
}
