package com.firedoge.kineticassembly.mixin.placement;

import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityBridge;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemFrame.class)
public abstract class ItemFrameMixin {
    @Shadow
    @Final
    private static EntityDataAccessor<ItemStack> DATA_ITEM;

    @Shadow
    @Final
    private static EntityDataAccessor<Integer> DATA_ROTATION;

    @Inject(method = "survives", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$assemblyAttachedEntitySurvives(CallbackInfoReturnable<Boolean> cir) {
        ItemFrame itemFrame = (ItemFrame) (Object) this;
        Level level = itemFrame.level();
        Optional<Boolean> survives = Optional.empty();
        if (level instanceof ServerLevel serverLevel) {
            survives = AssemblyEntityBridge.attachedEntitySurvives(serverLevel, itemFrame);
        }
        if (survives.isEmpty()) {
            survives = AssemblyEntityBridge.plotAttachedEntitySurvives(level, itemFrame);
        }
        survives.ifPresent(cir::setReturnValue);
    }

    @Redirect(
            method = "survives",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
            )
    )
    private BlockState kinetic_assembly$getPlotSupportBlockState(Level level, BlockPos supportPos) {
        if (level instanceof ServerLevel serverLevel) {
            return AssemblyEntityBridge.attachedSupportBlockState(serverLevel, (ItemFrame) (Object) this)
                    .orElseGet(() -> level.getBlockState(supportPos));
        }
        return level.getBlockState(supportPos);
    }

    @Redirect(
            method = "setRotation(IZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;updateNeighbourForOutputSignal(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V"
            )
    )
    private void kinetic_assembly$updatePlotOutputSignal(Level level, BlockPos pos, Block block) {
        if (level instanceof ServerLevel serverLevel) {
            BlockPos plotAnchor = AssemblyEntityBridge.attachedPlotAnchor(serverLevel, (ItemFrame) (Object) this)
                    .orElse(null);
            if (plotAnchor != null) {
                level.updateNeighbourForOutputSignal(plotAnchor, block);
                return;
            }
        }
        level.updateNeighbourForOutputSignal(pos, block);
    }

    @Inject(method = "setItem(Lnet/minecraft/world/item/ItemStack;Z)V", at = @At("TAIL"))
    private void kinetic_assembly$syncAssemblyItemFrameItem(ItemStack stack, boolean updateNeighbours, CallbackInfo ci) {
        kinetic_assembly$syncAssemblyItemFrameData(DATA_ITEM);
    }

    @Inject(method = "setRotation(IZ)V", at = @At("TAIL"))
    private void kinetic_assembly$syncAssemblyItemFrameRotation(int rotation, boolean updateNeighbours, CallbackInfo ci) {
        kinetic_assembly$syncAssemblyItemFrameData(DATA_ROTATION);
    }

    private <T> void kinetic_assembly$syncAssemblyItemFrameData(EntityDataAccessor<T> accessor) {
        ItemFrame itemFrame = (ItemFrame) (Object) this;
        if (!(itemFrame.level() instanceof ServerLevel level)) {
            return;
        }

        AssemblyEntityBridge.broadcastAttachedEntityData(
                level,
                itemFrame,
                List.of(SynchedEntityData.DataValue.create(accessor, itemFrame.getEntityData().get(accessor)))
        );
    }
}
