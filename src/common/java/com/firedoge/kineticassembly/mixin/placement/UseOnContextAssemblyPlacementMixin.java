package com.firedoge.kineticassembly.mixin.placement;

import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPlacementContext;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(UseOnContext.class)
public abstract class UseOnContextAssemblyPlacementMixin {
    @Shadow
    public abstract BlockPos getClickedPos();

    @Shadow
    public abstract Level getLevel();

    @Shadow
    @Nullable
    public abstract Player getPlayer();

    @Inject(method = "getHorizontalDirection", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$getAssemblyHorizontalDirection(CallbackInfoReturnable<Direction> cir) {
        Direction direction = AssemblyPlacementContext.horizontalDirection(this.getLevel(), this.getClickedPos(), this.getPlayer());
        if (direction != null) {
            cir.setReturnValue(direction);
        }
    }

    @Inject(method = "getRotation", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$getAssemblyRotation(CallbackInfoReturnable<Float> cir) {
        Float rotation = AssemblyPlacementContext.rotation(this.getLevel(), this.getClickedPos(), this.getPlayer());
        if (rotation != null) {
            cir.setReturnValue(rotation);
        }
    }
}
