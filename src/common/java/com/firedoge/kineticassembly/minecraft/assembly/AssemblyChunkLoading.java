package com.firedoge.kineticassembly.minecraft.assembly;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

final class AssemblyChunkLoading {
    private AssemblyChunkLoading() {
    }

    static boolean isChunkLoadedEnough(ServerLevel level, int chunkX, int chunkZ) {
        if (AssemblyContainers.server(level)
                .map(container -> container.inPlotBounds(chunkX, chunkZ))
                .orElse(false)) {
            return true;
        }
        return level.getChunkSource()
                .chunkMap
                .getDistanceManager()
                .inBlockTickingRange(ChunkPos.asLong(chunkX, chunkZ));
    }
}
