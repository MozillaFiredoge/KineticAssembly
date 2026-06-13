package com.firedoge.kineticassembly.mixin.placement;

import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPlacementObstruction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class BlockItemAssemblyPlacementMixin {
    @Inject(method = "canPlace", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$preventPlotBlockPlacementInsideEntity(
            BlockPlaceContext context,
            BlockState blockState,
            CallbackInfoReturnable<Boolean> cir
    ) {
        BlockPos placementPos = context.getClickedPos();
        if (!AssemblyPlacementObstruction.isBlockPlacementUnobstructed(
                context.getLevel(),
                placementPos,
                hitPlotPos(context, placementPos),
                blockState,
                context.getPlayer()
        )) {
            cir.setReturnValue(false);
        }
    }

    private static BlockPos hitPlotPos(BlockPlaceContext context, BlockPos placementPos) {
        return context.replacingClickedOnBlock()
                ? placementPos
                : placementPos.relative(context.getClickedFace().getOpposite());
    }
}
