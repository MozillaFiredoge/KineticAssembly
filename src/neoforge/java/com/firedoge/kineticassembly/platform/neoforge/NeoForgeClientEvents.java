package com.firedoge.kineticassembly.platform.neoforge;

import com.firedoge.kineticassembly.command.KineticAssemblyClientCommands;
import com.firedoge.kineticassembly.minecraft.assembly.ClientAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;
import com.firedoge.kineticassembly.render.AssemblyPlotRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

public final class NeoForgeClientEvents {
    @SubscribeEvent
    public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        KineticAssemblyClientCommands.register(event);
    }

    @SubscribeEvent
    public void onRenderLevelStage(RenderLevelStageEvent event) {
        if (Minecraft.getInstance().level == null) {
            return;
        }
        AssemblyContainers.container(Minecraft.getInstance().level)
                .filter(ClientAssemblyContainer.class::isInstance)
                .map(ClientAssemblyContainer.class::cast)
                .ifPresent(container -> {
                    AssemblyPlotRenderer.render(event, container);
                    AssemblyPlotRenderer.renderBlockEntities(event, container);
                    AssemblyPlotRenderer.renderBreakingProgress(event, container);
                    AssemblyPlotRenderer.renderSelectionOutline(event, container);
                });
    }

    @SubscribeEvent
    public void onRenderBlockHighlight(RenderHighlightEvent.Block event) {
        if (Minecraft.getInstance().level == null) {
            return;
        }
        AssemblyContainers.container(Minecraft.getInstance().level)
                .filter(ClientAssemblyContainer.class::isInstance)
                .map(ClientAssemblyContainer.class::cast)
                .filter(container -> container.inPlotBounds(new ChunkPos(event.getTarget().getBlockPos())))
                .ifPresent(ignored -> event.setCanceled(true));
    }
}
