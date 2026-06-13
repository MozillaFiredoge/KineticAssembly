package com.firedoge.kineticassembly.render;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.firedoge.kineticassembly.minecraft.assembly.ClientAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.ClientTrackedAssembly;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyId;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

public final class ClientAssemblyBreakingProgress {
    private static final int STALE_TICKS = 400;
    private static final Map<Integer, Entry> ENTRIES_BY_BREAKER = new LinkedHashMap<>();

    private ClientAssemblyBreakingProgress() {
    }

    public static boolean update(ClientLevel level, int breakerId, BlockPos plotPos, int progress) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        ClientTrackedAssembly assembly = plotAssembly(level, plotPos);
        if (assembly == null) {
            return false;
        }

        if (progress >= 0 && progress < 10) {
            ENTRIES_BY_BREAKER.put(
                    breakerId,
                    new Entry(assembly.id(), plotPos.immutable(), progress, level.getGameTime())
            );
        } else {
            ENTRIES_BY_BREAKER.remove(breakerId);
        }
        return true;
    }

    public static boolean removeIfTracked(int breakerId) {
        return ENTRIES_BY_BREAKER.remove(breakerId) != null;
    }

    public static List<Entry> entries(ClientAssemblyContainer container) {
        Objects.requireNonNull(container, "container");
        long gameTime = container.level().getGameTime();
        ENTRIES_BY_BREAKER.values().removeIf(entry -> {
            if (gameTime - entry.updatedGameTime() > STALE_TICKS) {
                return true;
            }
            return container.trackedAssembly(entry.id())
                    .filter(ClientTrackedAssembly::finalized)
                    .filter(assembly -> assembly.plot().containsPlotBlockPos(entry.plotPos()))
                    .isEmpty();
        });
        return List.copyOf(ENTRIES_BY_BREAKER.values());
    }

    private static ClientTrackedAssembly plotAssembly(ClientLevel level, BlockPos plotPos) {
        return AssemblyContainers.container(level)
                .filter(ClientAssemblyContainer.class::isInstance)
                .map(ClientAssemblyContainer.class::cast)
                .flatMap(container -> container.trackedAssemblyForChunk(new ChunkPos(plotPos)))
                .filter(ClientTrackedAssembly::finalized)
                .filter(assembly -> assembly.plot().containsPlotBlockPos(plotPos))
                .orElse(null);
    }

    public record Entry(AssemblyId id, BlockPos plotPos, int progress, long updatedGameTime) {
        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(plotPos, "plotPos");
            if (progress < 0 || progress >= 10) {
                throw new IllegalArgumentException("progress must be in [0, 10)");
            }
        }
    }
}
