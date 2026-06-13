package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.Optional;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

public final class AssemblyContainers {
    private AssemblyContainers() {
    }

    public static Optional<AssemblyContainer> container(Level level) {
        if (level instanceof AssemblyContainerHolder holder) {
            return Optional.ofNullable(holder.kinetic_assembly$assemblyContainer());
        }
        return Optional.empty();
    }

    public static Optional<ServerAssemblyContainer> server(ServerLevel level) {
        return container(level)
                .filter(ServerAssemblyContainer.class::isInstance)
                .map(ServerAssemblyContainer.class::cast);
    }

    public static ServerAssemblyContainer requireServer(ServerLevel level) {
        return server(level).orElseThrow(() -> new IllegalStateException("ServerLevel does not expose a AssemblyContainer"));
    }
}
