package com.firedoge.kineticassembly.command;

import java.util.Locale;

import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.minecraft.assembly.ClientAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.ClientTrackedAssembly;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;
import com.firedoge.kineticassembly.render.ClientAssemblySelection;
import com.firedoge.kineticassembly.render.AssemblyPlotRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

public final class KineticAssemblyClientCommands {
    private KineticAssemblyClientCommands() {
    }

    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kinetic_assembly_client")
                .then(Commands.literal("assembly_status")
                        .executes(context -> assemblyStatus(context.getSource()))));
    }

    private static int assemblyStatus(CommandSourceStack source) {
        if (Minecraft.getInstance().level == null) {
            source.sendFailure(Component.literal("No client level is loaded"));
            return 0;
        }
        ClientAssemblyContainer container = AssemblyContainers.container(Minecraft.getInstance().level)
                .filter(ClientAssemblyContainer.class::isInstance)
                .map(ClientAssemblyContainer.class::cast)
                .orElse(null);
        if (container == null) {
            source.sendFailure(Component.literal("No client assembly container is attached"));
            return 0;
        }

        int finalized = 0;
        int trackedChunks = 0;
        for (ClientTrackedAssembly assembly : container.trackedAssemblies()) {
            if (assembly.finalized()) {
                finalized++;
            }
            trackedChunks += assembly.loadedChunks().size();
        }
        int finalizedCount = finalized;
        int trackedLoadedChunks = trackedChunks;
        AssemblyPlotRenderer.RenderStats renderStats = AssemblyPlotRenderer.lastStats();
        ClientAssemblySelection.Result selection = ClientAssemblySelection.lastResult().orElse(null);
        ClientTrackedAssembly selectedAssembly = selection == null
                ? null
                : container.trackedAssembly(selection.id()).orElse(null);
        source.sendSuccess(() -> Component.literal(
                "ClientAssemblies=" + container.size()
                        + ", finalized=" + finalizedCount
                        + ", loadedPlotChunks=" + container.loadedPlotChunkCount()
                        + ", trackedLoadedChunks=" + trackedLoadedChunks
                        + ", lastRenderAssemblies=" + renderStats.assemblies()
                        + ", lastRenderChunks=" + renderStats.chunks()
                        + ", lastRenderBlocks=" + renderStats.blocks()
                        + ", lastRenderBlockEntities=" + renderStats.blockEntities()
                        + ", selected=" + describeSelection(selection)
                        + ", selectedTransform=" + describeSelectionTransform(selection, selectedAssembly)
        ), false);
        return container.size();
    }

    private static String describeSelection(ClientAssemblySelection.Result selection) {
        if (selection == null) {
            return "none";
        }
        return selection.id()
                + "@"
                + describeBlockPos(selection.plotPos())
                + "/face="
                + selection.hit().getDirection().getName()
                + "/distance="
                + String.format(Locale.ROOT, "%.3f", selection.distance());
    }

    private static String describeSelectionTransform(ClientAssemblySelection.Result selection, ClientTrackedAssembly assembly) {
        if (selection == null || assembly == null) {
            return "none";
        }
        Vec3 bodyLocalCenter = ClientAssemblySelection.plotCenterToBodyLocal(selection.plotPos(), assembly.metadata());
        Vec3 worldCenter = ClientAssemblySelection.plotCenterToWorld(selection.plotPos(), assembly);
        PhysicsPose pose = assembly.pose();
        PhysicsVector bodyToPlotOrigin = assembly.metadata().bodyToPlotOrigin();
        return "worldCenter="
                + describeVec3(worldCenter)
                + "/bodyLocalCenter="
                + describeVec3(bodyLocalCenter)
                + "/bodyPos="
                + describeVector(pose.position())
                + "/bodyToPlotOrigin="
                + describeVector(bodyToPlotOrigin);
    }

    private static String describeBlockPos(net.minecraft.core.BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String describeVec3(Vec3 vec) {
        return String.format(Locale.ROOT, "%.3f %.3f %.3f", vec.x, vec.y, vec.z);
    }

    private static String describeVector(PhysicsVector vector) {
        return String.format(Locale.ROOT, "%.3f %.3f %.3f", vector.x(), vector.y(), vector.z());
    }
}
