package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.Objects;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

record GlobalSavedAssemblyPointer(ChunkPos chunkPos, short storageIndex, short assemblyIndex) {
    GlobalSavedAssemblyPointer {
        Objects.requireNonNull(chunkPos, "chunkPos");
    }

    SavedAssemblyPointer local() {
        return new SavedAssemblyPointer(storageIndex, assemblyIndex);
    }

    CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("chunk_x", chunkPos.x);
        tag.putInt("chunk_z", chunkPos.z);
        tag.putShort("storage_index", storageIndex);
        tag.putShort("assembly_index", assemblyIndex);
        return tag;
    }

    @Nullable
    static GlobalSavedAssemblyPointer read(CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");
        if (!tag.contains("chunk_x")
                || !tag.contains("chunk_z")
                || !tag.contains("storage_index")
                || !tag.contains("assembly_index")) {
            return null;
        }
        return new GlobalSavedAssemblyPointer(
                new ChunkPos(tag.getInt("chunk_x"), tag.getInt("chunk_z")),
                tag.getShort("storage_index"),
                tag.getShort("assembly_index")
        );
    }
}
