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

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

public final class PlayerProxyManager {
    public static final PlayerProxyManager INSTANCE = new PlayerProxyManager();
    public static final float DEFAULT_PROXY_MASS = 120.0F;
    public static final double PLAYER_HALF_WIDTH = 0.3D;
    public static final double PLAYER_HALF_HEIGHT = 0.9D;
    private static final double PHYSICS_TICK_SECONDS = 1.0D / 20.0D;
    private static final PhysicsVector PLAYER_HALF_EXTENTS = new PhysicsVector(PLAYER_HALF_WIDTH, PLAYER_HALF_HEIGHT, PLAYER_HALF_WIDTH);

    private final Map<UUID, PlayerProxyBinding> bindings = new LinkedHashMap<>();

    private PlayerProxyManager() {
    }

    public synchronized PlayerProxySnapshot enable(ServerPlayer player, float mass, boolean debugProxy) {
        Objects.requireNonNull(player, "player");
        if (mass <= 0.0F) {
            throw new IllegalArgumentException("Mass must be positive");
        }
        if (PlayerPhysicsManager.INSTANCE.snapshot(player).isPresent()) {
            throw new IllegalStateException("Disable PhysX-owned player physics before enabling the vanilla player proxy");
        }

        Optional<PlayerProxySnapshot> existing = snapshot(player);
        if (existing.isPresent()) {
            return existing.get();
        }

        ServerLevel level = player.serverLevel();
        PhysicsVector center = playerCenter(player);
        MechanicsWorld world = KineticAssembly.api().world(level);
        MechanicsBodySnapshot body = world.createDynamicBox(new MechanicsBoxDefinition(
                new PhysicsPose(center, PhysicsQuaternion.IDENTITY),
                PLAYER_HALF_EXTENTS,
                mass,
                MechanicsBodyRole.PLAYER_PROXY
        ));
        PlayerProxyBinding binding = new PlayerProxyBinding(
                player.getUUID(),
                player.getScoreboardName(),
                level.dimension(),
                body.id(),
                center,
                debugProxy
        );
        bindings.put(binding.playerId(), binding);
        if (debugProxy) {
            MechanicsDebugProxies.show(level, body.id());
        }
        return snapshot(body, binding);
    }

    public synchronized Optional<PlayerProxySnapshot> snapshot(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        PlayerProxyBinding binding = bindings.get(player.getUUID());
        if (binding == null) {
            return Optional.empty();
        }
        MinecraftServer server = player.getServer();
        if (server == null || !player.serverLevel().dimension().equals(binding.levelKey())) {
            removeBodyIfPossible(server, binding);
            bindings.remove(binding.playerId());
            return Optional.empty();
        }
        return snapshot(server, binding);
    }

    public synchronized List<PlayerProxySnapshot> snapshots(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return bindings.values().stream()
                .map(binding -> snapshot(server, binding))
                .flatMap(Optional::stream)
                .toList();
    }

    public synchronized Optional<PlayerProxySnapshot> disable(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        PlayerProxyBinding binding = bindings.remove(player.getUUID());
        if (binding == null) {
            return Optional.empty();
        }
        MinecraftServer server = player.getServer();
        Optional<MechanicsBodySnapshot> maybeBody = snapshotBody(server, binding);
        removeBodyIfPossible(server, binding);
        return maybeBody.map(body -> snapshot(body, binding));
    }

    public synchronized void syncBeforePhysics(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (bindings.isEmpty()) {
            return;
        }

        for (PlayerProxyBinding binding : List.copyOf(bindings.values())) {
            ServerPlayer player = server.getPlayerList().getPlayer(binding.playerId());
            if (player == null || !player.serverLevel().dimension().equals(binding.levelKey())) {
                removeBodyIfPossible(server, binding);
                bindings.remove(binding.playerId());
                continue;
            }

            Optional<MechanicsWorld> maybeWorld = KineticAssembly.api().existingWorld(player.serverLevel());
            if (maybeWorld.isEmpty()) {
                bindings.remove(binding.playerId());
                continue;
            }

            PhysicsVector center = playerCenter(player);
            PhysicsVector velocity = velocityBetween(binding.lastCenter(), center);
            MechanicsWorld world = maybeWorld.get();
            boolean poseUpdated = world.setPose(binding.bodyId(), new PhysicsPose(center, PhysicsQuaternion.IDENTITY));
            boolean velocityUpdated = world.setLinearVelocity(binding.bodyId(), velocity);
            if (!poseUpdated || !velocityUpdated) {
                bindings.remove(binding.playerId());
                continue;
            }
            binding.lastCenter(center);
            binding.syncedTicks++;
        }
    }

    public synchronized int forgetLevel(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        int removed = 0;
        MinecraftServer server = level.getServer();
        for (PlayerProxyBinding binding : List.copyOf(bindings.values())) {
            if (!binding.levelKey().equals(level.dimension())) {
                continue;
            }
            removeBodyIfPossible(server, binding);
            bindings.remove(binding.playerId());
            removed++;
        }
        return removed;
    }

    public synchronized void close(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        for (PlayerProxyBinding binding : List.copyOf(bindings.values())) {
            removeBodyIfPossible(server, binding);
        }
        bindings.clear();
    }

    private Optional<PlayerProxySnapshot> snapshot(MinecraftServer server, PlayerProxyBinding binding) {
        return snapshotBody(server, binding)
                .map(body -> snapshot(body, binding));
    }

    private static Optional<MechanicsBodySnapshot> snapshotBody(MinecraftServer server, PlayerProxyBinding binding) {
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

    private static PlayerProxySnapshot snapshot(MechanicsBodySnapshot body, PlayerProxyBinding binding) {
        return new PlayerProxySnapshot(
                binding.playerId(),
                binding.playerName(),
                binding.levelKey(),
                body,
                binding.debugProxy(),
                binding.syncedTicks()
        );
    }

    private static PhysicsVector playerCenter(ServerPlayer player) {
        return new PhysicsVector(player.getX(), player.getY() + PLAYER_HALF_HEIGHT, player.getZ());
    }

    private static PhysicsVector velocityBetween(PhysicsVector previous, PhysicsVector current) {
        return new PhysicsVector(
                (current.x() - previous.x()) / PHYSICS_TICK_SECONDS,
                (current.y() - previous.y()) / PHYSICS_TICK_SECONDS,
                (current.z() - previous.z()) / PHYSICS_TICK_SECONDS
        );
    }

    private static void removeBodyIfPossible(MinecraftServer server, PlayerProxyBinding binding) {
        if (server == null) {
            return;
        }
        ServerLevel level = server.getLevel(binding.levelKey());
        if (level == null) {
            return;
        }
        KineticAssembly.api().existingWorld(level).ifPresent(world -> world.removeBody(binding.bodyId()));
    }

    private static final class PlayerProxyBinding {
        private final UUID playerId;
        private final String playerName;
        private final ResourceKey<Level> levelKey;
        private final MechanicsBodyId bodyId;
        private final boolean debugProxy;
        private PhysicsVector lastCenter;
        private int syncedTicks;

        private PlayerProxyBinding(
                UUID playerId,
                String playerName,
                ResourceKey<Level> levelKey,
                MechanicsBodyId bodyId,
                PhysicsVector lastCenter,
                boolean debugProxy
        ) {
            this.playerId = Objects.requireNonNull(playerId, "playerId");
            this.playerName = Objects.requireNonNull(playerName, "playerName");
            this.levelKey = Objects.requireNonNull(levelKey, "levelKey");
            this.bodyId = Objects.requireNonNull(bodyId, "bodyId");
            this.lastCenter = Objects.requireNonNull(lastCenter, "lastCenter");
            this.debugProxy = debugProxy;
        }

        private UUID playerId() {
            return playerId;
        }

        private String playerName() {
            return playerName;
        }

        private ResourceKey<Level> levelKey() {
            return levelKey;
        }

        private MechanicsBodyId bodyId() {
            return bodyId;
        }

        private boolean debugProxy() {
            return debugProxy;
        }

        private PhysicsVector lastCenter() {
            return lastCenter;
        }

        private void lastCenter(PhysicsVector lastCenter) {
            this.lastCenter = Objects.requireNonNull(lastCenter, "lastCenter");
        }

        private int syncedTicks() {
            return syncedTicks;
        }
    }
}
