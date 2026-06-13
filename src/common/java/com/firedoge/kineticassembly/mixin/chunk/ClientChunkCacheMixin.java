package com.firedoge.kineticassembly.mixin.chunk;

import java.util.function.Consumer;

import com.firedoge.kineticassembly.minecraft.assembly.ClientAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
    @Shadow
    @Final
    private static Logger LOGGER;

    @Shadow
    @Final
    private ClientLevel level;

    @Shadow
    @Final
    private LevelChunk emptyChunk;

    @Shadow
    private static boolean isValidChunk(LevelChunk levelChunk, int x, int z) {
        return false;
    }

    @Inject(
            method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void kinetic_assembly$getPlotChunk(
            int x,
            int z,
            ChunkStatus status,
            boolean create,
            CallbackInfoReturnable<LevelChunk> cir
    ) {
        ClientAssemblyContainer container = kinetic_assembly$plotContainer();
        if (container == null || !container.inPlotBounds(x, z)) {
            return;
        }

        LevelChunk chunk = container.plotChunk(new ChunkPos(x, z)).orElse(null);
        cir.setReturnValue(chunk != null ? chunk : create ? emptyChunk : null);
    }

    @Inject(method = "drop", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$dropPlotChunk(ChunkPos chunkPos, CallbackInfo ci) {
        ClientAssemblyContainer container = kinetic_assembly$plotContainer();
        if (container == null || !container.inPlotBounds(chunkPos)) {
            return;
        }

        container.removePlotChunk(chunkPos).ifPresent(chunk -> {
            chunk.setLoaded(false);
            level.unload(chunk);
            level.getLightEngine().setLightEnabled(chunkPos, false);
        });
        ci.cancel();
    }

    @Inject(method = "replaceBiomes", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$replacePlotBiomes(int x, int z, FriendlyByteBuf buffer, CallbackInfo ci) {
        ClientAssemblyContainer container = kinetic_assembly$plotContainer();
        if (container == null || !container.inPlotBounds(x, z)) {
            return;
        }

        LevelChunk chunk = container.plotChunk(new ChunkPos(x, z)).orElse(null);
        if (chunk == null || !isValidChunk(chunk, x, z)) {
            LOGGER.warn("Ignoring plot chunk biome data since it is not present: {}, {}", x, z);
        } else {
            chunk.replaceBiomes(buffer);
        }
        ci.cancel();
    }

    @Inject(method = "replaceWithPacketData", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$replacePlotChunkWithPacketData(
            int x,
            int z,
            FriendlyByteBuf buffer,
            CompoundTag heightmaps,
            Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> blockEntityTagOutput,
            CallbackInfoReturnable<LevelChunk> cir
    ) {
        ClientAssemblyContainer container = kinetic_assembly$plotContainer();
        if (container == null || !container.inPlotBounds(x, z)) {
            return;
        }

        ChunkPos chunkPos = new ChunkPos(x, z);
        LevelChunk previous = container.plotChunk(chunkPos).orElse(null);
        if (previous != null) {
            previous.setLoaded(false);
            level.unload(previous);
        }

        LevelChunk chunk = new LevelChunk(level, chunkPos);
        chunk.replaceWithPacketData(buffer, heightmaps, blockEntityTagOutput);
        container.putPlotChunk(chunkPos, chunk);
        chunk.setLoaded(true);
        level.onChunkLoaded(chunkPos);
        level.getLightEngine().setLightEnabled(chunkPos, true);
        cir.setReturnValue(chunk);
    }

    @Unique
    private ClientAssemblyContainer kinetic_assembly$plotContainer() {
        return AssemblyContainers.container(level)
                .filter(ClientAssemblyContainer.class::isInstance)
                .map(ClientAssemblyContainer.class::cast)
                .orElse(null);
    }
}
