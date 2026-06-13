package com.firedoge.kineticassembly.mechanics;

import java.util.Objects;
import java.util.Optional;

import com.firedoge.kineticassembly.minecraft.scene.ServerPhysicsRuntime;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

public final class MechanicsDebugProxies {
    private MechanicsDebugProxies() {
    }

    public static Optional<MechanicsDebugProxy> show(ServerLevel level, MechanicsBodyId id) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        return ServerPhysicsRuntime.INSTANCE.showMechanicsDebugProxy(level, id);
    }

    public static Optional<MechanicsDebugProxy> show(ServerLevel level, MechanicsBodyId id, BlockState displayState) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayState, "displayState");
        return ServerPhysicsRuntime.INSTANCE.showMechanicsDebugProxy(level, id, displayState);
    }

    public static boolean hide(ServerLevel level, MechanicsBodyId id) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        return ServerPhysicsRuntime.INSTANCE.hideMechanicsDebugProxy(level, id);
    }
}
