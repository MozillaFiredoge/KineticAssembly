package com.firedoge.kineticassembly.platform.neoforge;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.backend.physx.PhysXBackend;
import com.firedoge.kineticassembly.command.KineticAssemblyCommands;
import com.firedoge.kineticassembly.compat.aerodynamics.AerodynamicsCompat;
import com.firedoge.kineticassembly.minecraft.elastic.ElasticPanelManager;
import com.firedoge.kineticassembly.minecraft.fem.FemVolumeManager;
import com.firedoge.kineticassembly.minecraft.material.BlockDensityResolver;
import com.firedoge.kineticassembly.minecraft.player.PlayerPhysicsManager;
import com.firedoge.kineticassembly.minecraft.player.PlayerProxyManager;
import com.firedoge.kineticassembly.minecraft.scene.MechanicsJointPersistence;
import com.firedoge.kineticassembly.minecraft.scene.ServerPhysicsRuntime;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityBridge;
import com.firedoge.kineticassembly.minecraft.assembly.ServerAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyManager;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPersistence;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyProfiler;
import com.firedoge.kineticassembly.physics.PhysicsManager;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class NeoForgeEvents {
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        KineticAssemblyCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        AssemblyPersistence.startServer();
        MechanicsJointPersistence.startServer();
        BlockDensityResolver.INSTANCE.reload();
        AerodynamicsCompat.onServerStarting();
        boolean physxAvailable = PhysicsManager.INSTANCE.backend(PhysXBackend.ID)
                .map(backend -> backend.isAvailable())
                .orElse(false);
        KineticAssembly.LOGGER.info("KineticAssembly server hooks ready; PhysX native loaded={}", physxAvailable);
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        AssemblyProfiler.beginTick();
        long tickStarted = AssemblyProfiler.start();
        try {
            long sectionStarted = AssemblyProfiler.start();
            try {
                for (ServerLevel level : event.getServer().getAllLevels()) {
                    AssemblyContainers.server(level).ifPresent(ServerAssemblyContainer::initializeForceLoadTickets);
                }
            } finally {
                AssemblyProfiler.record("tick.forceLoadTickets", sectionStarted);
            }

            sectionStarted = AssemblyProfiler.start();
            try {
                AssemblyPersistence.restore(event.getServer());
            } finally {
                AssemblyProfiler.record("tick.persistence.restore", sectionStarted);
            }

            sectionStarted = AssemblyProfiler.start();
            try {
                MechanicsJointPersistence.restore(event.getServer());
            } finally {
                AssemblyProfiler.record("tick.mechanicsJointPersistence.restore", sectionStarted);
            }

            sectionStarted = AssemblyProfiler.start();
            try {
                PlayerProxyManager.INSTANCE.syncBeforePhysics(event.getServer());
            } finally {
                AssemblyProfiler.record("tick.playerProxy.beforePhysics", sectionStarted);
            }

            sectionStarted = AssemblyProfiler.start();
            try {
                AerodynamicsCompat.tickBeforePhysics(event.getServer());
            } finally {
                AssemblyProfiler.record("tick.aero.beforePhysics", sectionStarted);
            }

            sectionStarted = AssemblyProfiler.start();
            try {
                ServerPhysicsRuntime.INSTANCE.tick(event.getServer());
            } finally {
                AssemblyProfiler.record("tick.physicsRuntime", sectionStarted);
            }

            sectionStarted = AssemblyProfiler.start();
            try {
                FemVolumeManager.INSTANCE.tick(event.getServer());
            } finally {
                AssemblyProfiler.record("tick.fem", sectionStarted);
            }

            sectionStarted = AssemblyProfiler.start();
            try {
                PlayerPhysicsManager.INSTANCE.tick(event.getServer());
            } finally {
                AssemblyProfiler.record("tick.playerPhysics", sectionStarted);
            }

            sectionStarted = AssemblyProfiler.start();
            try {
                AssemblyManager.INSTANCE.tick(event.getServer());
            } finally {
                AssemblyProfiler.record("tick.assemblyManager", sectionStarted);
            }

            sectionStarted = AssemblyProfiler.start();
            try {
                ElasticPanelManager.INSTANCE.tick(event.getServer());
            } finally {
                AssemblyProfiler.record("tick.elastic", sectionStarted);
            }

            for (ServerLevel level : event.getServer().getAllLevels()) {
                AssemblyContainers.server(level).ifPresent(container -> {
                    long containerSectionStarted = AssemblyProfiler.start();
                    try {
                        container.holdingChunkMap().processChanges();
                    } finally {
                        AssemblyProfiler.record("tick.assembly.holdingChanges", containerSectionStarted);
                    }

                    containerSectionStarted = AssemblyProfiler.start();
                    try {
                        container.refreshRuntimeStates();
                    } finally {
                        AssemblyProfiler.record("tick.assembly.refreshRuntime", containerSectionStarted);
                    }

                    containerSectionStarted = AssemblyProfiler.start();
                    try {
                        AssemblyEntityBridge.tickAttachedEntities(level, container);
                    } finally {
                        AssemblyProfiler.record("tick.assembly.attachedEntities", containerSectionStarted);
                    }

                    containerSectionStarted = AssemblyProfiler.start();
                    try {
                        AssemblyEntityBridge.tickEntityInside(level, container);
                    } finally {
                        AssemblyProfiler.record("tick.assembly.entityInside", containerSectionStarted);
                    }

                    containerSectionStarted = AssemblyProfiler.start();
                    try {
                        container.trackingSystem().tick();
                    } finally {
                        AssemblyProfiler.record("tick.assembly.tracking", containerSectionStarted);
                    }
                });
            }

            sectionStarted = AssemblyProfiler.start();
            try {
                AssemblyPersistence.capturePeriodically(event.getServer());
            } finally {
                AssemblyProfiler.record("tick.persistence.capture", sectionStarted);
            }

            sectionStarted = AssemblyProfiler.start();
            try {
                MechanicsJointPersistence.capturePeriodically(event.getServer());
            } finally {
                AssemblyProfiler.record("tick.mechanicsJointPersistence.capture", sectionStarted);
            }
        } finally {
            AssemblyProfiler.record("tick.total", tickStarted);
            AssemblyProfiler.endTick();
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendExistingPlotChunks(player);
        }
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendExistingPlotChunks(player);
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendExistingPlotChunks(player);
        }
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            AssemblyContainers.server(level)
                    .ifPresent(container -> container.holdingChunkMap().updateChunkStatus(event.getChunk().getPos(), false));
            int removed = ServerPhysicsRuntime.INSTANCE.unloadChunkCollision(level, event.getChunk().getPos().toLong());
            if (removed > 0) {
                KineticAssembly.LOGGER.debug("Released {} physics terrain colliders for chunk {}", removed, event.getChunk().getPos());
            }
        }
    }

    @SubscribeEvent
    public void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ServerPhysicsRuntime.INSTANCE.updateTerrainCollisionAt(level, event.getPos());
            for (Direction side : event.getNotifiedSides()) {
                ServerPhysicsRuntime.INSTANCE.updateTerrainCollisionAt(level, event.getPos().relative(side));
            }
        }
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ServerPhysicsRuntime.INSTANCE.removeTerrainCollisionAt(level, event.getPos());
        }
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ServerPhysicsRuntime.INSTANCE.updateTerrainCollisionAt(level, event.getPos());
        }
    }

    @SubscribeEvent
    public void onFluidPlaceBlock(BlockEvent.FluidPlaceBlockEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ServerPhysicsRuntime.INSTANCE.updateTerrainCollisionAt(level, event.getPos());
        }
    }

    @SubscribeEvent
    public void onLevelSave(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel level) {
            AssemblyPersistence.captureBeforeLevelSave(level);
            MechanicsJointPersistence.captureBeforeLevelSave(level);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        AssemblyPersistence.flush(event.getServer());
        MechanicsJointPersistence.flush(event.getServer());
        AerodynamicsCompat.close(event.getServer());
        PlayerProxyManager.INSTANCE.close(event.getServer());
        PlayerPhysicsManager.INSTANCE.close(event.getServer());
        FemVolumeManager.INSTANCE.close(event.getServer());
        AssemblyManager.INSTANCE.close(event.getServer());
        ElasticPanelManager.INSTANCE.close(event.getServer());
        ServerPhysicsRuntime.INSTANCE.close(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        ServerPhysicsRuntime.INSTANCE.close();
    }

    private static void sendExistingPlotChunks(ServerPlayer player) {
        ServerAssemblyContainer container = AssemblyContainers.server(player.serverLevel()).orElse(null);
        if (container != null) {
            container.trackingSystem().resyncPlayer(player);
        }
    }
}
