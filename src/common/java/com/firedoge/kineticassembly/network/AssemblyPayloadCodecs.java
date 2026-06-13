package com.firedoge.kineticassembly.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsQuaternion;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyClientMetadata;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyId;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPlot;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPlotId;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;

final class AssemblyPayloadCodecs {
    private AssemblyPayloadCodecs() {
    }

    static void writeMetadata(RegistryFriendlyByteBuf buffer, AssemblyClientMetadata metadata) {
        writeAssemblyId(buffer, metadata.id());
        writePlot(buffer, metadata.plot());
        writePoseFrame(buffer, metadata.poseFrame());
        writeVector(buffer, metadata.bodyToPlotOrigin());
        buffer.writeVarInt(metadata.chunkPositions().size());
        for (ChunkPos chunkPos : metadata.chunkPositions()) {
            writeChunkPos(buffer, chunkPos);
        }
    }

    static AssemblyClientMetadata readMetadata(RegistryFriendlyByteBuf buffer) {
        AssemblyId id = readAssemblyId(buffer);
        AssemblyPlot plot = readPlot(buffer);
        com.firedoge.kineticassembly.minecraft.assembly.AssemblyPoseFrame poseFrame = readPoseFrame(buffer);
        if (!poseFrame.id().equals(id)) {
            throw new IllegalArgumentException("metadata pose frame id does not match metadata id");
        }
        PhysicsVector bodyToPlotOrigin = readVector(buffer);
        int chunkCount = buffer.readVarInt();
        List<ChunkPos> chunkPositions = new ArrayList<>(chunkCount);
        for (int index = 0; index < chunkCount; index++) {
            chunkPositions.add(readChunkPos(buffer));
        }
        return new AssemblyClientMetadata(id, plot, poseFrame, bodyToPlotOrigin, chunkPositions);
    }

    static void writePoseFrame(
            RegistryFriendlyByteBuf buffer,
            com.firedoge.kineticassembly.minecraft.assembly.AssemblyPoseFrame frame
    ) {
        writeAssemblyId(buffer, frame.id());
        buffer.writeVarLong(frame.epoch());
        writePose(buffer, frame.previousPose());
        writePose(buffer, frame.currentPose());
    }

    static com.firedoge.kineticassembly.minecraft.assembly.AssemblyPoseFrame readPoseFrame(RegistryFriendlyByteBuf buffer) {
        AssemblyId id = readAssemblyId(buffer);
        long epoch = buffer.readVarLong();
        PhysicsPose previousPose = readPose(buffer);
        PhysicsPose currentPose = readPose(buffer);
        return new com.firedoge.kineticassembly.minecraft.assembly.AssemblyPoseFrame(id, epoch, previousPose, currentPose);
    }

    static void writeAssemblyId(RegistryFriendlyByteBuf buffer, AssemblyId id) {
        buffer.writeUUID(id.value());
    }

    static AssemblyId readAssemblyId(RegistryFriendlyByteBuf buffer) {
        UUID value = buffer.readUUID();
        return new AssemblyId(value);
    }

    static void writePose(RegistryFriendlyByteBuf buffer, PhysicsPose pose) {
        PhysicsVector position = pose.position();
        PhysicsQuaternion rotation = pose.rotation();
        buffer.writeDouble(position.x());
        buffer.writeDouble(position.y());
        buffer.writeDouble(position.z());
        buffer.writeDouble(rotation.x());
        buffer.writeDouble(rotation.y());
        buffer.writeDouble(rotation.z());
        buffer.writeDouble(rotation.w());
    }

    static PhysicsPose readPose(RegistryFriendlyByteBuf buffer) {
        PhysicsVector position = new PhysicsVector(
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble()
        );
        PhysicsQuaternion rotation = new PhysicsQuaternion(
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble()
        );
        return new PhysicsPose(position, rotation);
    }

    static void writeVector(RegistryFriendlyByteBuf buffer, PhysicsVector vector) {
        buffer.writeDouble(vector.x());
        buffer.writeDouble(vector.y());
        buffer.writeDouble(vector.z());
    }

    static PhysicsVector readVector(RegistryFriendlyByteBuf buffer) {
        return new PhysicsVector(
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble()
        );
    }

    private static void writePlot(RegistryFriendlyByteBuf buffer, AssemblyPlot plot) {
        buffer.writeLong(plot.id().value());
        writeChunkPos(buffer, plot.originChunk());
        buffer.writeInt(plot.sectionY());
        buffer.writeVarInt(plot.chunkSpan());
        buffer.writeVarInt(plot.sectionSpan());
    }

    private static AssemblyPlot readPlot(RegistryFriendlyByteBuf buffer) {
        AssemblyPlotId id = new AssemblyPlotId(buffer.readLong());
        ChunkPos originChunk = readChunkPos(buffer);
        int sectionY = buffer.readInt();
        int chunkSpan = buffer.readVarInt();
        int sectionSpan = buffer.readVarInt();
        return new AssemblyPlot(id, originChunk, sectionY, chunkSpan, sectionSpan);
    }

    private static void writeChunkPos(RegistryFriendlyByteBuf buffer, ChunkPos chunkPos) {
        buffer.writeInt(chunkPos.x);
        buffer.writeInt(chunkPos.z);
    }

    private static ChunkPos readChunkPos(RegistryFriendlyByteBuf buffer) {
        return new ChunkPos(buffer.readInt(), buffer.readInt());
    }
}
