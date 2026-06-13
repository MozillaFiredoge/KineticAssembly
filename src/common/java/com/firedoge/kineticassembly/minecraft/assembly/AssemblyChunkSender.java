package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.BitSet;
import java.util.Objects;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

public final class AssemblyChunkSender {
    private AssemblyChunkSender() {
    }

    public static Packet<? super ClientGamePacketListener> chunkPacket(ServerLevel level, LevelChunk chunk) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(chunk, "chunk");
        return new ClientboundLevelChunkWithLightPacket(
                chunk,
                level.getChunkSource().getLightEngine(),
                (BitSet) null,
                (BitSet) null
        );
    }

    public static Packet<? super ClientGamePacketListener> forgetPacket(ChunkPos chunkPos) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        return new ClientboundForgetLevelChunkPacket(chunkPos);
    }

    public static void sendChunk(ServerPlayer player, LevelChunk chunk) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(chunk, "chunk");
        if (player.connection == null) {
            return;
        }
        player.connection.send(chunkPacket(player.serverLevel(), chunk));
    }
}
