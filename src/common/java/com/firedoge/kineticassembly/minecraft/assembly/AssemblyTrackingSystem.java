package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.network.ClientboundFinalizeAssemblyPayload;
import com.firedoge.kineticassembly.network.ClientboundStartTrackingAssemblyPayload;
import com.firedoge.kineticassembly.network.ClientboundStopTrackingAssemblyPayload;
import com.firedoge.kineticassembly.network.ClientboundAssemblyTransformPayload;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;

public final class AssemblyTrackingSystem {
    private static final double TRACKING_RANGE = 512.0D;

    private final ServerAssemblyContainer container;
    private final AssemblyPoseService poseService = new AssemblyPoseService();
    private final Map<AssemblyId, Set<UUID>> trackingPlayers = new LinkedHashMap<>();
    private final Map<AssemblyId, AssemblyPoseFrame> lastSentFrames = new LinkedHashMap<>();
    private final Map<AssemblyId, Set<ChunkPos>> pendingChunkResyncs = new LinkedHashMap<>();
    private final Map<AssemblyId, MovingPistonChunks> movingPistonChunks = new LinkedHashMap<>();

    AssemblyTrackingSystem(ServerAssemblyContainer container) {
        this.container = Objects.requireNonNull(container, "container");
    }

    public void tick() {
        long sectionStarted = AssemblyProfiler.start();
        try {
            for (PhysicsAssembly assembly : container.assemblies()) {
                Optional<AssemblyPoseFrame> maybeFrame = runtimePoseFrame(assembly);
                if (maybeFrame.isEmpty()) {
                    continue;
                }
                AssemblyPoseFrame frame = maybeFrame.get();
                updatePlayers(assembly, frame);
                sendTransformIfChanged(assembly, frame);
            }
        } finally {
            AssemblyProfiler.record("tracking.assemblies", sectionStarted);
        }

        sectionStarted = AssemblyProfiler.start();
        try {
            for (PlotChunkHolder holder : container.plotChunkHolders()) {
                PhysicsAssembly assembly = container.assemblyAtChunk(holder.chunk().getPos()).orElse(null);
                if (assembly != null && containsMovingPiston(assembly, holder.chunk().getPos())) {
                    continue;
                }
                holder.broadcastPlotChanges();
            }
        } finally {
            AssemblyProfiler.record("tracking.plotChunks", sectionStarted);
        }

        sectionStarted = AssemblyProfiler.start();
        try {
            sendPendingChunkResyncs();
        } finally {
            AssemblyProfiler.record("tracking.pendingResyncs", sectionStarted);
        }
    }

    public void resyncPlayer(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        if (player.serverLevel() != level()) {
            return;
        }
        for (PhysicsAssembly assembly : container.assemblies()) {
            Optional<PhysicsPose> maybePose = pose(assembly);
            if (maybePose.isEmpty()) {
                continue;
            }
            AssemblyPoseFrame frame = poseFrame(assembly, maybePose.get());
            UUID playerId = player.getGameProfile().getId();
            Set<UUID> tracking = trackingPlayers.computeIfAbsent(assembly.id(), ignored -> new LinkedHashSet<>());
            if (shouldTrack(player, frame)) {
                tracking.add(playerId);
                sendFullSync(player, assembly, frame);
            } else if (tracking.remove(playerId)) {
                sendRemoval(player, assembly, assembly.plot().chunkPositions());
            }
        }
    }

    public void onAssemblyAdded(PhysicsAssembly assembly) {
        Objects.requireNonNull(assembly, "assembly");
        pose(assembly).ifPresent(pose -> {
            AssemblyPoseFrame frame = poseFrame(assembly, pose);
            updatePlayers(assembly, frame);
        });
    }

    public void onAssemblyChunksRebuilt(PhysicsAssembly assembly, List<LevelChunk> chunks) {
        Objects.requireNonNull(assembly, "assembly");
        Objects.requireNonNull(chunks, "chunks");
        Set<ChunkPos> pending = pendingChunkResyncs.computeIfAbsent(assembly.id(), ignored -> new LinkedHashSet<>());
        for (LevelChunk chunk : chunks) {
            if (containsMovingPiston(assembly, chunk.getPos())) {
                continue;
            }
            pending.add(chunk.getPos());
        }
    }

    public void resyncAssemblyMetadata(PhysicsAssembly assembly) {
        Objects.requireNonNull(assembly, "assembly");
        Optional<PhysicsPose> maybePose = pose(assembly);
        if (maybePose.isEmpty()) {
            return;
        }

        List<ServerPlayer> players = playersTracking(assembly.id());
        if (players.isEmpty()) {
            return;
        }

        poseService.remove(assembly.id());
        AssemblyPoseFrame frame = poseFrame(assembly, maybePose.get());
        for (ServerPlayer player : players) {
            sendFullSync(player, assembly, frame);
        }
        lastSentFrames.put(assembly.id(), frame);
        pendingChunkResyncs.remove(assembly.id());
    }

    public void onAssemblyRemoved(PhysicsAssembly assembly, List<ChunkPos> removedChunks) {
        onAssemblyRemoved(assembly, removedChunks, AssemblyRemovalReason.REMOVED);
    }

    public void onAssemblyRemoved(PhysicsAssembly assembly, List<ChunkPos> removedChunks, AssemblyRemovalReason reason) {
        Objects.requireNonNull(assembly, "assembly");
        Objects.requireNonNull(removedChunks, "removedChunks");
        Objects.requireNonNull(reason, "reason");
        Set<UUID> tracking = trackingPlayers.remove(assembly.id());
        poseService.remove(assembly.id());
        lastSentFrames.remove(assembly.id());
        pendingChunkResyncs.remove(assembly.id());
        movingPistonChunks.remove(assembly.id());
        if (tracking == null || tracking.isEmpty()) {
            return;
        }
        for (UUID playerId : tracking) {
            ServerPlayer player = level().getServer().getPlayerList().getPlayer(playerId);
            if (player != null && player.serverLevel() == level()) {
                sendRemoval(player, assembly, removedChunks);
            }
        }
    }

    public void clear() {
        trackingPlayers.clear();
        poseService.clear();
        lastSentFrames.clear();
        pendingChunkResyncs.clear();
        movingPistonChunks.clear();
    }

    public List<ServerPlayer> playersTracking(ChunkPos chunkPos) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        return container.assemblyAtChunk(chunkPos)
                .map(assembly -> playersTracking(assembly.id()))
                .orElseGet(List::of);
    }

    public List<ServerPlayer> playersTracking(AssemblyId id) {
        Objects.requireNonNull(id, "id");
        Set<UUID> tracking = trackingPlayers.get(id);
        if (tracking == null || tracking.isEmpty()) {
            return List.of();
        }
        List<ServerPlayer> players = new ArrayList<>(tracking.size());
        for (UUID playerId : tracking) {
            ServerPlayer player = level().getServer().getPlayerList().getPlayer(playerId);
            if (player != null && player.serverLevel() == level()) {
                players.add(player);
            }
        }
        return List.copyOf(players);
    }

    public AssemblyPoseFrame poseFrame(PhysicsAssembly assembly, PhysicsPose pose) {
        AssemblyPoseFrame frame = poseService.update(assembly, pose);
        container.updateRuntimePose(assembly, frame);
        return frame;
    }

    public Optional<AssemblyPoseFrame> poseFrame(PhysicsAssembly assembly) {
        Objects.requireNonNull(assembly, "assembly");
        return pose(assembly).map(pose -> poseFrame(assembly, pose));
    }

    private void updatePlayers(PhysicsAssembly assembly, AssemblyPoseFrame frame) {
        Set<UUID> tracking = trackingPlayers.computeIfAbsent(assembly.id(), ignored -> new LinkedHashSet<>());
        Set<UUID> shouldTrack = new LinkedHashSet<>();
        for (ServerPlayer player : level().players()) {
            if (shouldTrack(player, frame)) {
                shouldTrack.add(player.getGameProfile().getId());
                if (tracking.add(player.getGameProfile().getId())) {
                    sendFullSync(player, assembly, frame);
                }
            }
        }

        tracking.removeIf(playerId -> {
            if (shouldTrack.contains(playerId)) {
                return false;
            }
            ServerPlayer player = level().getServer().getPlayerList().getPlayer(playerId);
            if (player != null && player.serverLevel() == level()) {
                sendRemoval(player, assembly, assembly.plot().chunkPositions());
            }
            return true;
        });
    }

    private void sendFullSync(ServerPlayer player, PhysicsAssembly assembly, AssemblyPoseFrame frame) {
        if (player.connection == null) {
            return;
        }
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        AssemblyClientMetadata metadata = AssemblyClientMetadata.from(assembly, frame);
        packets.add(new ClientboundCustomPayloadPacket(new ClientboundStartTrackingAssemblyPayload(metadata)));
        for (ChunkPos chunkPos : metadata.chunkPositions()) {
            container.plotChunk(chunkPos)
                    .map(chunk -> AssemblyChunkSender.chunkPacket(level(), chunk))
                    .ifPresent(packets::add);
        }
        packets.add(new ClientboundCustomPayloadPacket(new ClientboundFinalizeAssemblyPayload(assembly.id())));
        player.connection.send(new ClientboundBundlePacket(packets));
        lastSentFrames.put(assembly.id(), frame);
        if (playersTracking(assembly.id()).size() <= 1) {
            pendingChunkResyncs.remove(assembly.id());
        }
    }

    private void sendRemoval(ServerPlayer player, PhysicsAssembly assembly, List<ChunkPos> removedChunks) {
        if (player.connection == null) {
            return;
        }
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        packets.add(new ClientboundCustomPayloadPacket(new ClientboundStopTrackingAssemblyPayload(assembly.id())));
        for (ChunkPos chunkPos : removedChunks) {
            packets.add(AssemblyChunkSender.forgetPacket(chunkPos));
        }
        player.connection.send(new ClientboundBundlePacket(packets));
    }

    private void sendTransformIfChanged(PhysicsAssembly assembly, AssemblyPoseFrame frame) {
        Set<UUID> tracking = trackingPlayers.get(assembly.id());
        if (tracking == null || tracking.isEmpty()) {
            return;
        }
        AssemblyPoseFrame previous = lastSentFrames.get(assembly.id());
        if (previous != null && AssemblyPoseService.closeEnough(previous.currentPose(), frame.currentPose())) {
            return;
        }
        lastSentFrames.put(assembly.id(), frame);
        ClientboundAssemblyTransformPayload payload = new ClientboundAssemblyTransformPayload(frame);
        ClientboundCustomPayloadPacket packet = new ClientboundCustomPayloadPacket(payload);
        for (ServerPlayer player : playersTracking(assembly.id())) {
            player.connection.send(packet);
        }
    }

    private void sendPendingChunkResyncs() {
        if (pendingChunkResyncs.isEmpty()) {
            return;
        }

        Map<AssemblyId, Set<ChunkPos>> pending = new LinkedHashMap<>(pendingChunkResyncs);
        pendingChunkResyncs.clear();
        for (Map.Entry<AssemblyId, Set<ChunkPos>> entry : pending.entrySet()) {
            PhysicsAssembly assembly = container.assembly(entry.getKey()).orElse(null);
            if (assembly == null) {
                continue;
            }
            List<ServerPlayer> players = playersTracking(entry.getKey());
            if (players.isEmpty()) {
                continue;
            }
            List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
            for (ChunkPos chunkPos : entry.getValue()) {
                if (containsMovingPiston(assembly, chunkPos)) {
                    pendingChunkResyncs
                            .computeIfAbsent(entry.getKey(), ignored -> new LinkedHashSet<>())
                            .add(chunkPos);
                    continue;
                }
                container.plotChunk(chunkPos)
                        .map(chunk -> AssemblyChunkSender.chunkPacket(level(), chunk))
                        .ifPresent(packets::add);
            }
            if (packets.isEmpty()) {
                continue;
            }
            ClientboundBundlePacket bundle = new ClientboundBundlePacket(packets);
            for (ServerPlayer player : players) {
                if (player.connection != null) {
                    player.connection.send(bundle);
                }
            }
        }
    }

    private Optional<PhysicsPose> pose(PhysicsAssembly assembly) {
        return KineticAssembly.api().existingWorld(level())
                .flatMap(world -> world.pose(assembly.bodyId()));
    }

    private Optional<AssemblyPoseFrame> runtimePoseFrame(PhysicsAssembly assembly) {
        Optional<AssemblyPoseFrame> cached = container.runtimeState(assembly.id())
                .flatMap(AssemblyRuntimeState::poseFrame);
        if (cached.isPresent()) {
            return cached;
        }
        return pose(assembly).map(pose -> poseFrame(assembly, pose));
    }

    private boolean containsMovingPiston(PhysicsAssembly assembly, ChunkPos chunkPos) {
        return movingPistonChunks(assembly).contains(chunkPos);
    }

    private Set<ChunkPos> movingPistonChunks(PhysicsAssembly assembly) {
        MovingPistonChunks cached = movingPistonChunks.get(assembly.id());
        if (cached != null && cached.shapeEpoch() == assembly.shapeEpoch()) {
            return cached.chunks();
        }

        Set<ChunkPos> chunks = new LinkedHashSet<>();
        for (AssemblyBlock block : assembly.blocks()) {
            if (block.blockState().is(Blocks.MOVING_PISTON)) {
                chunks.add(new ChunkPos(assembly.plot().toPlotBlockPos(block.localPos())));
            }
        }
        MovingPistonChunks updated = new MovingPistonChunks(assembly.shapeEpoch(), chunks);
        movingPistonChunks.put(assembly.id(), updated);
        return updated.chunks();
    }

    private boolean shouldTrack(ServerPlayer player, AssemblyPoseFrame frame) {
        PhysicsVector position = frame.currentPose().position();
        double dx = position.x() - player.getX();
        double dy = position.y() - player.getY();
        double dz = position.z() - player.getZ();
        return dx * dx + dy * dy + dz * dz <= TRACKING_RANGE * TRACKING_RANGE;
    }

    private ServerLevel level() {
        return container.level();
    }

    private record MovingPistonChunks(long shapeEpoch, Set<ChunkPos> chunks) {
        private MovingPistonChunks {
            chunks = Set.copyOf(chunks);
        }
    }
}
