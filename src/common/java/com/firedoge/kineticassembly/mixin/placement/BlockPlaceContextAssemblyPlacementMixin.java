package com.firedoge.kineticassembly.mixin.placement;

import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPlacementContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BlockPlaceContext.class)
public abstract class BlockPlaceContextAssemblyPlacementMixin extends UseOnContext {
    protected BlockPlaceContextAssemblyPlacementMixin(
            Level level,
            Player player,
            InteractionHand hand,
            ItemStack itemStack,
            BlockHitResult hitResult
    ) {
        super(level, player, hand, itemStack, hitResult);
    }

    @Shadow
    public abstract BlockPos getClickedPos();

    @Redirect(
            method = "*",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/core/Direction;getFacingAxis(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Direction$Axis;)Lnet/minecraft/core/Direction;"
            )
    )
    private Direction kinetic_assembly$getAssemblyFacingAxis(Entity entity, Direction.Axis axis) {
        Direction direction = AssemblyPlacementContext.facingAxis(
                this.getLevel(),
                this.getClickedPos(),
                this.getHitResult().getBlockPos(),
                entity,
                axis
        );
        return direction != null ? direction : Direction.getFacingAxis(entity, axis);
    }

    @Redirect(
            method = "*",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/core/Direction;orderedByNearest(Lnet/minecraft/world/entity/Entity;)[Lnet/minecraft/core/Direction;"
            )
    )
    private Direction[] kinetic_assembly$getAssemblyOrderedByNearest(Entity entity) {
        Direction[] directions = AssemblyPlacementContext.orderedByNearest(
                this.getLevel(),
                this.getClickedPos(),
                this.getHitResult().getBlockPos(),
                entity
        );
        return directions != null ? directions : Direction.orderedByNearest(entity);
    }
}
