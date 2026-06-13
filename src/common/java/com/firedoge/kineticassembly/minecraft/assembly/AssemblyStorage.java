package com.firedoge.kineticassembly.minecraft.assembly;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.firedoge.kineticassembly.KineticAssembly;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

final class AssemblyStorage implements AutoCloseable {
    private static final int MAX_CACHE_SIZE = 128;

    private final Path folder;
    private final LinkedHashMap<Long, AssemblyRegionFile> regionCache = new LinkedHashMap<>(16, 0.75F, true);
    private final LinkedHashMap<StorageFileKey, AssemblyStorageFile> storageCache = new LinkedHashMap<>(16, 0.75F, true);

    AssemblyStorage(Path folder) {
        this.folder = Objects.requireNonNull(folder, "folder");
    }

    static AssemblyStorage forLevel(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        ResourceLocation dimension = level.dimension().location();
        Path path = level.getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("kinetic_assembly_assemblies")
                .resolve(safePathPart(dimension.getNamespace()))
                .resolve(safePathPart(dimension.getPath()));
        return new AssemblyStorage(path);
    }

    Optional<HoldingChunkData> attemptLoadHoldingChunk(ChunkPos chunkPos) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        try {
            return Optional.ofNullable(regionFile(chunkPos).read(chunkPos));
        } catch (IOException exception) {
            KineticAssembly.LOGGER.error("Failed to load assembly holding chunk {}", chunkPos, exception);
            return Optional.empty();
        }
    }

    void attemptSaveHoldingChunk(ChunkPos chunkPos, HoldingChunkData data) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        Objects.requireNonNull(data, "data");
        try {
            regionFile(chunkPos).write(chunkPos, data);
        } catch (IOException exception) {
            KineticAssembly.LOGGER.error("Failed to save assembly holding chunk {}", chunkPos, exception);
        }
    }

    Optional<CompoundTag> attemptLoadAssembly(ChunkPos chunkPos, SavedAssemblyPointer pointer) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        Objects.requireNonNull(pointer, "pointer");
        try {
            CompoundTag tag = storageFile(chunkPos, pointer.storageIndex()).read(pointer.assemblyIndex());
            if (tag == null) {
                KineticAssembly.LOGGER.warn("Missing held assembly data at pointer {} in chunk {}", pointer, chunkPos);
                return Optional.empty();
            }
            return Optional.of(tag);
        } catch (IOException exception) {
            KineticAssembly.LOGGER.error("Failed to load held assembly data at pointer {} in chunk {}", pointer, chunkPos, exception);
            return Optional.empty();
        }
    }

    Optional<GlobalSavedAssemblyPointer> attemptSaveAssembly(ChunkPos chunkPos, CompoundTag tag) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        Objects.requireNonNull(tag, "tag");
        try {
            for (short storageIndex = 0; storageIndex < Short.MAX_VALUE; storageIndex++) {
                AssemblyStorageFile storageFile = storageFile(chunkPos, storageIndex);
                int assemblyIndex = storageFile.findFreeIndex();
                if (assemblyIndex < 0 || assemblyIndex >= storageFile.totalIndexCapacity()) {
                    continue;
                }
                storageFile.write(assemblyIndex, tag);
                return Optional.of(new GlobalSavedAssemblyPointer(chunkPos, storageIndex, (short) assemblyIndex));
            }
        } catch (IOException exception) {
            KineticAssembly.LOGGER.error("Failed to save held assembly data for chunk {}", chunkPos, exception);
        }
        return Optional.empty();
    }

    void attemptSaveAssembly(GlobalSavedAssemblyPointer pointer, CompoundTag tag) {
        Objects.requireNonNull(pointer, "pointer");
        try {
            storageFile(pointer.chunkPos(), pointer.storageIndex()).write(pointer.assemblyIndex(), tag);
        } catch (IOException exception) {
            KineticAssembly.LOGGER.error("Failed to update held assembly data at pointer {}", pointer, exception);
        }
    }

    void flush() {
        for (AssemblyRegionFile regionFile : regionCache.values()) {
            try {
                regionFile.flush();
            } catch (IOException exception) {
                KineticAssembly.LOGGER.error("Failed to flush assembly region file", exception);
            }
        }
        for (AssemblyStorageFile storageFile : storageCache.values()) {
            try {
                storageFile.flush();
            } catch (IOException exception) {
                KineticAssembly.LOGGER.error("Failed to flush assembly storage file", exception);
            }
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (AssemblyStorageFile storageFile : storageCache.values()) {
            try {
                storageFile.close();
            } catch (IOException exception) {
                failure = append(failure, exception);
            }
        }
        storageCache.clear();

        for (AssemblyRegionFile regionFile : regionCache.values()) {
            try {
                regionFile.close();
            } catch (IOException exception) {
                failure = append(failure, exception);
            }
        }
        regionCache.clear();

        if (failure != null) {
            throw failure;
        }
    }

    private AssemblyRegionFile regionFile(ChunkPos chunkPos) throws IOException {
        long key = ChunkPos.asLong(chunkPos.getRegionX(), chunkPos.getRegionZ());
        AssemblyRegionFile cached = regionCache.get(key);
        if (cached != null) {
            return cached;
        }
        evictRegionIfNeeded();
        AssemblyRegionFile regionFile = new AssemblyRegionFile(regionPath(chunkPos), regionExternalPath(chunkPos));
        regionCache.put(key, regionFile);
        return regionFile;
    }

    private AssemblyStorageFile storageFile(ChunkPos chunkPos, int storageIndex) throws IOException {
        StorageFileKey key = new StorageFileKey(chunkPos.getRegionX(), chunkPos.getRegionZ(), storageIndex);
        AssemblyStorageFile cached = storageCache.get(key);
        if (cached != null) {
            return cached;
        }
        evictStorageIfNeeded();
        AssemblyStorageFile storageFile = new AssemblyStorageFile(storagePath(chunkPos, storageIndex), storageExternalPath(chunkPos, storageIndex));
        storageCache.put(key, storageFile);
        return storageFile;
    }

    private void evictRegionIfNeeded() throws IOException {
        if (regionCache.size() < MAX_CACHE_SIZE) {
            return;
        }
        Map.Entry<Long, AssemblyRegionFile> eldest = regionCache.entrySet().iterator().next();
        regionCache.remove(eldest.getKey());
        eldest.getValue().close();
    }

    private void evictStorageIfNeeded() throws IOException {
        if (storageCache.size() < MAX_CACHE_SIZE) {
            return;
        }
        Map.Entry<StorageFileKey, AssemblyStorageFile> eldest = storageCache.entrySet().iterator().next();
        storageCache.remove(eldest.getKey());
        eldest.getValue().close();
    }

    private Path regionPath(ChunkPos chunkPos) {
        return folder.resolve(regionFileName(chunkPos) + AssemblyRegionFile.FILE_EXTENSION);
    }

    private Path regionExternalPath(ChunkPos chunkPos) {
        return folder.resolve(regionFileName(chunkPos) + ".r");
    }

    private Path storagePath(ChunkPos chunkPos, int storageIndex) {
        return folder.resolve(regionFileName(chunkPos, storageIndex) + AssemblyStorageFile.FILE_EXTENSION);
    }

    private Path storageExternalPath(ChunkPos chunkPos, int storageIndex) {
        return folder.resolve(regionFileName(chunkPos, storageIndex) + ".s");
    }

    private static String regionFileName(ChunkPos chunkPos) {
        return "r." + chunkPos.getRegionX() + "." + chunkPos.getRegionZ();
    }

    private static String regionFileName(ChunkPos chunkPos, int storageIndex) {
        return regionFileName(chunkPos) + "." + storageIndex;
    }

    private static IOException append(IOException existing, IOException next) {
        if (existing == null) {
            return next;
        }
        existing.addSuppressed(next);
        return existing;
    }

    private static String safePathPart(String value) {
        return value.replace(':', '_').replace('/', '_').replace('\\', '_');
    }

    record HoldingChunkData(List<SavedAssemblyPointer> pointers) {
        HoldingChunkData {
            pointers = List.copyOf(pointers);
        }

        static HoldingChunkData from(CompoundTag tag) {
            int[] packedPointers = tag.getIntArray("pointers");
            List<SavedAssemblyPointer> pointers = new ArrayList<>(packedPointers.length);
            for (int packed : packedPointers) {
                pointers.add(SavedAssemblyPointer.unpack(packed));
            }
            return new HoldingChunkData(pointers);
        }

        void writeTo(CompoundTag tag) {
            int[] packedPointers = pointers.stream()
                    .mapToInt(SavedAssemblyPointer::packed)
                    .toArray();
            tag.putIntArray("pointers", packedPointers);
        }
    }

    private record StorageFileKey(int regionX, int regionZ, int storageIndex) {
    }
}
