package com.firedoge.kineticassembly.minecraft.assembly;

import java.io.IOException;
import java.nio.file.Path;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

final class AssemblyRegionFile extends AssemblyStorageFile {
    static final String FILE_EXTENSION = ".assem";
    private static final int SECTOR_BYTES = 128;
    private static final int LOG_SIDE_LENGTH = 5;

    AssemblyRegionFile(Path path, Path externalFileDir) throws IOException {
        super(path, externalFileDir, SECTOR_BYTES);
    }

    AssemblyStorage.HoldingChunkData read(ChunkPos chunkPos) throws IOException {
        CompoundTag tag = read(index(chunkPos.getRegionLocalX(), chunkPos.getRegionLocalZ()));
        return tag == null ? null : AssemblyStorage.HoldingChunkData.from(tag);
    }

    void write(ChunkPos chunkPos, AssemblyStorage.HoldingChunkData data) throws IOException {
        if (data == null || data.pointers().isEmpty()) {
            write(index(chunkPos.getRegionLocalX(), chunkPos.getRegionLocalZ()), null);
            return;
        }
        CompoundTag tag = new CompoundTag();
        data.writeTo(tag);
        write(index(chunkPos.getRegionLocalX(), chunkPos.getRegionLocalZ()), tag);
    }

    private static int index(int localX, int localZ) {
        return localX | (localZ << LOG_SIDE_LENGTH);
    }
}
