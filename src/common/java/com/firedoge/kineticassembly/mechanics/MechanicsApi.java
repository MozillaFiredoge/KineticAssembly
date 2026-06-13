package com.firedoge.kineticassembly.mechanics;

import java.util.Optional;

import net.minecraft.server.level.ServerLevel;

public interface MechanicsApi {
    MechanicsWorld world(ServerLevel level);

    Optional<MechanicsWorld> existingWorld(ServerLevel level);
}
