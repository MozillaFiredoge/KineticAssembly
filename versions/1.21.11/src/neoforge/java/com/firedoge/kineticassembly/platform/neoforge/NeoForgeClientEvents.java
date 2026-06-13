package com.firedoge.kineticassembly.platform.neoforge;

import com.firedoge.kineticassembly.command.KineticAssemblyClientCommands;
import com.firedoge.kineticassembly.minecraft.assembly.ClientAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;
import com.firedoge.kineticassembly.render.AssemblyPlotRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ExtractBlockOutlineRenderStateEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

public final class NeoForgeClientEvents {
    @SubscribeEvent
    public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        KineticAssemblyClientCommands.register(event);
    }

    @SubscribeEvent
    public void onExtractLevelRenderState(ExtractLevelRenderStateEvent event) {
        AssemblyPlotRenderer.extract(event);
    }

    @SubscribeEvent
    public void onRenderLevelAfterOpaqueBlocks(RenderLevelStageEvent.AfterOpaqueBlocks event) {
        renderAssemblies(event);
    }

    @SubscribeEvent
    public void onRenderLevelAfterEntities(RenderLevelStageEvent.AfterEntities event) {
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
                });
    }

    @SubscribeEvent
    public void onRenderLevelAfterTranslucentBlocks(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        renderAssemblies(event);
    }

    @SubscribeEvent
    public void onRenderLevelAfterTripwireBlocks(RenderLevelStageEvent.AfterTripwireBlocks event) {
        renderAssemblies(event);
    }

    @SubscribeEvent
    public void onRenderLevelAfterLevel(RenderLevelStageEvent.AfterLevel event) {
        if (Minecraft.getInstance().level == null) {
            return;
        }
        AssemblyContainers.container(Minecraft.getInstance().level)
                .filter(ClientAssemblyContainer.class::isInstance)
                .map(ClientAssemblyContainer.class::cast)
                .ifPresent(container -> AssemblyPlotRenderer.renderSelectionOutline(event, container));
    }

    @SubscribeEvent
    public void onExtractBlockOutlineRenderState(ExtractBlockOutlineRenderStateEvent event) {
        if (Minecraft.getInstance().level == null) {
            return;
        }
        AssemblyContainers.container(Minecraft.getInstance().level)
                .filter(ClientAssemblyContainer.class::isInstance)
                .map(ClientAssemblyContainer.class::cast)
                .filter(container -> container.inPlotBounds(new ChunkPos(event.getBlockPos())))
                .ifPresent(ignored -> event.setCanceled(true));
    }

    private static void renderAssemblies(RenderLevelStageEvent event) {
        if (Minecraft.getInstance().level == null) {
            return;
        }
        AssemblyContainers.container(Minecraft.getInstance().level)
                .filter(ClientAssemblyContainer.class::isInstance)
                .map(ClientAssemblyContainer.class::cast)
                .ifPresent(container -> AssemblyPlotRenderer.render(event, container));
    }
}
