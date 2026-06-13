package com.firedoge.kineticassembly.minecraft.player;

import java.util.Objects;
import java.util.UUID;

import com.firedoge.kineticassembly.mechanics.MechanicsBodySnapshot;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record PlayerProxySnapshot(
        UUID playerId,
        String playerName,
        ResourceKey<Level> levelKey,
        MechanicsBodySnapshot body,
        boolean debugProxy,
        int syncedTicks
) {
    public PlayerProxySnapshot {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(levelKey, "levelKey");
        Objects.requireNonNull(body, "body");
        if (syncedTicks < 0) {
            throw new IllegalArgumentException("syncedTicks must not be negative");
        }
    }
}
