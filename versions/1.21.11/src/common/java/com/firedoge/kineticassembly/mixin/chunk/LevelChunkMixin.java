package com.firedoge.kineticassembly.mixin.chunk;

import java.util.Optional;

import com.firedoge.kineticassembly.minecraft.assembly.ClientAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.PhysicsAssembly;
import com.firedoge.kineticassembly.minecraft.assembly.ServerAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyManager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Shadow
    @Final
    Level level;

    @Inject(method = "setBlockState", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$setPlotChunkBlockState(
            BlockPos pos,
            BlockState state,
            int flags,
            CallbackInfoReturnable<BlockState> cir
    ) {
        ServerAssemblyContainer container = kinetic_assembly$plotContainer(pos);
        if (container == null || container.isRebuildingPlotChunks()) {
            return;
        }

        Optional<PhysicsAssembly> maybeAssembly = container.assemblyAtPlotBlock(pos);
        if (maybeAssembly.isEmpty()) {
            return;
        }

        boolean isMoving = (flags & 64) != 0;
        BlockState previous = ((LevelChunk) (Object) this).getBlockState(pos);
        if (!AssemblyManager.INSTANCE.setPlotBlockState((ServerLevel) level, pos, state, isMoving)) {
            cir.setReturnValue(null);
            return;
        }
        kinetic_assembly$runLatePlotDiodePlaceHook(pos, previous, state, isMoving);
        cir.setReturnValue(previous);
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void kinetic_assembly$markClientPlotChunkBlockChanged(
            BlockPos pos,
            BlockState state,
            int flags,
            CallbackInfoReturnable<BlockState> cir
    ) {
        if (!level.isClientSide()) {
            return;
        }

        ClientAssemblyContainer container = AssemblyContainers.container(level)
                .filter(ClientAssemblyContainer.class::isInstance)
                .map(ClientAssemblyContainer.class::cast)
                .orElse(null);
        if (container != null && container.inPlotBounds(new ChunkPos(pos))) {
            container.markPlotBlockChanged(pos);
        }
    }

    @Inject(method = "setBlockEntity", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$setPlotChunkBlockEntity(BlockEntity blockEntity, CallbackInfo ci) {
        ServerAssemblyContainer container = kinetic_assembly$plotContainer(blockEntity.getBlockPos());
        if (container == null || container.isRebuildingPlotChunks()) {
            return;
        }
        if (AssemblyManager.INSTANCE.setPlotBlockEntity((ServerLevel) level, blockEntity)) {
            ci.cancel();
        }
    }

    @Inject(method = "removeBlockEntity", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$removePlotChunkBlockEntity(BlockPos pos, CallbackInfo ci) {
        ServerAssemblyContainer container = kinetic_assembly$plotContainer(pos);
        if (container == null || container.isRebuildingPlotChunks()) {
            return;
        }
        if (AssemblyManager.INSTANCE.removePlotBlockEntity((ServerLevel) level, pos)) {
            ci.cancel();
        }
    }

    @Unique
    private ServerAssemblyContainer kinetic_assembly$plotContainer(BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        ServerAssemblyContainer container = AssemblyContainers.server(serverLevel).orElse(null);
        if (container == null || !container.inPlotBounds(new ChunkPos(pos))) {
            return null;
        }
        return container;
    }

    @Unique
    private void kinetic_assembly$runLatePlotDiodePlaceHook(BlockPos pos, BlockState previous, BlockState requested, boolean isMoving) {
        if (level.captureBlockSnapshots || previous.equals(requested) || !(requested.getBlock() instanceof DiodeBlock)) {
            return;
        }
        if (((LevelChunk) (Object) this).getBlockState(pos).equals(requested)) {
            return;
        }

        BlockState live = level.getBlockState(pos);
        if (live.getBlock() == requested.getBlock()) {
            live.onPlace(level, pos, previous, isMoving);
        }
    }
}
