package com.firedoge.kineticassembly.minecraft.player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsQuaternion;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.mechanics.MechanicsBodyId;
import com.firedoge.kineticassembly.mechanics.MechanicsBodyRole;
import com.firedoge.kineticassembly.mechanics.MechanicsBodySnapshot;
import com.firedoge.kineticassembly.mechanics.MechanicsBoxDefinition;
import com.firedoge.kineticassembly.mechanics.MechanicsDebugProxies;
import com.firedoge.kineticassembly.mechanics.MechanicsWorld;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

public final class PlayerPhysicsManager {
    public static final PlayerPhysicsManager INSTANCE = new PlayerPhysicsManager();
    public static final float DEFAULT_PLAYER_MASS = 80.0F;
    public static final double PLAYER_HALF_WIDTH = 0.3D;
    public static final double PLAYER_HALF_HEIGHT = 0.9D;

    private static final PhysicsVector PLAYER_HALF_EXTENTS = new PhysicsVector(PLAYER_HALF_WIDTH, PLAYER_HALF_HEIGHT, PLAYER_HALF_WIDTH);

    private final Map<UUID, PlayerBinding> bindings = new LinkedHashMap<>();

    private PlayerPhysicsManager() {
    }

    public synchronized PlayerPhysicsSnapshot enable(ServerPlayer player, float mass, boolean debugProxy) {
        Objects.requireNonNull(player, "player");
        if (mass <= 0.0F) {
            throw new IllegalArgumentException("Mass must be positive");
        }
        if (PlayerProxyManager.INSTANCE.snapshot(player).isPresent()) {
            throw new IllegalStateException("Disable the vanilla player proxy before enabling PhysX-owned player physics");
        }

        Optional<PlayerPhysicsSnapshot> existing = snapshot(player);
        if (existing.isPresent()) {
            return existing.get();
        }

        ServerLevel level = player.serverLevel();
        MechanicsWorld world = KineticAssembly.api().world(level);
        MechanicsBodySnapshot body = world.createDynamicBox(new MechanicsBoxDefinition(
                playerBodyPose(player),
                PLAYER_HALF_EXTENTS,
                mass,
                MechanicsBodyRole.PLAYER
        ));
        PlayerBinding binding = new PlayerBinding(
                player.getUUID(),
                player.getScoreboardName(),
                level.dimension(),
                body.id(),
                player.isNoGravity(),
                debugProxy
        );
        bindings.put(binding.playerId(), binding);
        player.setNoGravity(true);
        player.resetFallDistance();
        player.setDeltaMovement(Vec3.ZERO);
        if (debugProxy) {
            MechanicsDebugProxies.show(level, body.id());
        }
        return snapshot(body, binding);
    }

    public synchronized Optional<PlayerPhysicsSnapshot> snapshot(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        PlayerBinding binding = bindings.get(player.getUUID());
        if (binding == null) {
            return Optional.empty();
        }
        MinecraftServer server = player.getServer();
        if (server == null || !player.serverLevel().dimension().equals(binding.levelKey())) {
            removeBodyIfPossible(server, binding);
            restorePlayer(player, binding);
            bindings.remove(binding.playerId());
            return Optional.empty();
        }

        Optional<MechanicsBodySnapshot> maybeBody = snapshotBody(server, binding);
        if (maybeBody.isEmpty()) {
            restorePlayer(player, binding);
            bindings.remove(binding.playerId());
            return Optional.empty();
        }
        return Optional.of(snapshot(maybeBody.get(), binding));
    }

    public synchronized List<PlayerPhysicsSnapshot> snapshots(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return bindings.values().stream()
                .map(binding -> snapshot(server, binding))
                .flatMap(Optional::stream)
                .toList();
    }

    public synchronized Optional<PlayerPhysicsSnapshot> applyImpulse(ServerPlayer player, PhysicsVector impulse) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(impulse, "impulse");
        PlayerBinding binding = bindings.get(player.getUUID());
        if (binding == null) {
            return Optional.empty();
        }
        MinecraftServer server = player.getServer();
        if (server == null || !player.serverLevel().dimension().equals(binding.levelKey())) {
            removeBodyIfPossible(server, binding);
            restorePlayer(player, binding);
            bindings.remove(binding.playerId());
            return Optional.empty();
        }

        Optional<MechanicsWorld> maybeWorld = KineticAssembly.api().existingWorld(player.serverLevel());
        if (maybeWorld.isEmpty() || !maybeWorld.get().applyLinearImpulse(binding.bodyId(), impulse)) {
            return Optional.empty();
        }
        return maybeWorld.get().snapshot(binding.bodyId()).map(body -> snapshot(body, binding));
    }

    public synchronized Optional<PlayerPhysicsSnapshot> disable(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        PlayerBinding binding = bindings.remove(player.getUUID());
        if (binding == null) {
            return Optional.empty();
        }
        MinecraftServer server = player.getServer();
        Optional<MechanicsBodySnapshot> maybeBody = snapshotBody(server, binding);
        removeBodyIfPossible(server, binding);
        restorePlayer(player, binding);
        return maybeBody.map(body -> snapshot(body, binding));
    }

    public synchronized void tick(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (bindings.isEmpty()) {
            return;
        }

        for (PlayerBinding binding : List.copyOf(bindings.values())) {
            ServerPlayer player = server.getPlayerList().getPlayer(binding.playerId());
            if (player == null) {
                removeBody(server, binding);
                bindings.remove(binding.playerId());
                continue;
            }
            if (!player.serverLevel().dimension().equals(binding.levelKey())) {
                restorePlayer(player, binding);
                removeBody(server, binding);
                bindings.remove(binding.playerId());
                continue;
            }

            Optional<MechanicsBodySnapshot> maybeBody = KineticAssembly.api().existingWorld(player.serverLevel())
                    .flatMap(world -> world.snapshot(binding.bodyId()));
            if (maybeBody.isEmpty()) {
                restorePlayer(player, binding);
                bindings.remove(binding.playerId());
                continue;
            }
            syncPlayerFromBody(player, maybeBody.get());
            binding.syncedTicks++;
        }
    }

    public synchronized int forgetLevel(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        int removed = 0;
        MinecraftServer server = level.getServer();
        for (PlayerBinding binding : List.copyOf(bindings.values())) {
            if (!binding.levelKey().equals(level.dimension())) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(binding.playerId());
            if (player != null) {
                restorePlayer(player, binding);
            }
            removeBody(server, binding);
            bindings.remove(binding.playerId());
            removed++;
        }
        return removed;
    }

    public synchronized void close(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        for (PlayerBinding binding : List.copyOf(bindings.values())) {
            ServerPlayer player = server.getPlayerList().getPlayer(binding.playerId());
            if (player != null) {
                restorePlayer(player, binding);
            }
            removeBody(server, binding);
        }
        bindings.clear();
    }

    private Optional<PlayerPhysicsSnapshot> snapshot(MinecraftServer server, PlayerBinding binding) {
        return snapshotBody(server, binding)
                .map(body -> snapshot(body, binding));
    }

    private static Optional<MechanicsBodySnapshot> snapshotBody(MinecraftServer server, PlayerBinding binding) {
        if (server == null) {
            return Optional.empty();
        }
        ServerLevel level = server.getLevel(binding.levelKey());
        if (level == null) {
            return Optional.empty();
        }
        return KineticAssembly.api().existingWorld(level)
                .flatMap(world -> world.snapshot(binding.bodyId()));
    }

    private static PlayerPhysicsSnapshot snapshot(MechanicsBodySnapshot body, PlayerBinding binding) {
        return new PlayerPhysicsSnapshot(
                binding.playerId(),
                binding.playerName(),
                binding.levelKey(),
                body,
                binding.debugProxy(),
                binding.syncedTicks()
        );
    }

    private static PhysicsPose playerBodyPose(ServerPlayer player) {
        return new PhysicsPose(
                new PhysicsVector(player.getX(), player.getY() + PLAYER_HALF_HEIGHT, player.getZ()),
                PhysicsQuaternion.IDENTITY
        );
    }

    private static void syncPlayerFromBody(ServerPlayer player, MechanicsBodySnapshot body) {
        PhysicsVector center = body.pose().position();
        double feetY = center.y() - PLAYER_HALF_HEIGHT;
        player.teleportTo(center.x(), feetY, center.z());
        player.setNoGravity(true);
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
    }

    private static void restorePlayer(ServerPlayer player, PlayerBinding binding) {
        player.setNoGravity(binding.previousNoGravity());
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
    }

    private static void removeBody(MinecraftServer server, PlayerBinding binding) {
        removeBodyIfPossible(server, binding);
    }

    private static void removeBodyIfPossible(MinecraftServer server, PlayerBinding binding) {
        if (server == null) {
            return;
        }
        ServerLevel level = server.getLevel(binding.levelKey());
        if (level == null) {
            return;
        }
        KineticAssembly.api().existingWorld(level).ifPresent(world -> world.removeBody(binding.bodyId()));
    }

    private static final class PlayerBinding {
        private final UUID playerId;
        private final String playerName;
        private final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> levelKey;
        private final MechanicsBodyId bodyId;
        private final boolean previousNoGravity;
        private final boolean debugProxy;
        private int syncedTicks;

        private PlayerBinding(
                UUID playerId,
                String playerName,
                net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> levelKey,
                MechanicsBodyId bodyId,
                boolean previousNoGravity,
                boolean debugProxy
        ) {
            this.playerId = Objects.requireNonNull(playerId, "playerId");
            this.playerName = Objects.requireNonNull(playerName, "playerName");
            this.levelKey = Objects.requireNonNull(levelKey, "levelKey");
            this.bodyId = Objects.requireNonNull(bodyId, "bodyId");
            this.previousNoGravity = previousNoGravity;
            this.debugProxy = debugProxy;
        }

        private UUID playerId() {
            return playerId;
        }

        private String playerName() {
            return playerName;
        }

        private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> levelKey() {
            return levelKey;
        }

        private MechanicsBodyId bodyId() {
            return bodyId;
        }

        private boolean previousNoGravity() {
            return previousNoGravity;
        }

        private boolean debugProxy() {
            return debugProxy;
        }

        private int syncedTicks() {
            return syncedTicks;
        }
    }
}
