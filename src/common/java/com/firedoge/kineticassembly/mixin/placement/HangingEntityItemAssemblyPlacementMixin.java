package com.firedoge.kineticassembly.mixin.placement;

import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPlacementContext;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HangingEntityItem.class)
public abstract class HangingEntityItemAssemblyPlacementMixin {
    @Shadow
    @Final
    private EntityType<? extends HangingEntity> type;

    @Shadow
    protected abstract boolean mayPlace(Player player, Direction direction, ItemStack stack, BlockPos pos);

    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$placeAssemblyAttachedEntity(
            UseOnContext context,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        Optional<AssemblyPlacementContext.HangingEntityPlacement> maybePlacement =
                AssemblyPlacementContext.hangingEntityPlacement(
                        context.getLevel(),
                        context.getClickedPos(),
                        context.getClickedFace(),
                        context.getClickLocation()
                );
        if (maybePlacement.isEmpty()) {
            return;
        }

        AssemblyPlacementContext.HangingEntityPlacement placement = maybePlacement.get();
        Direction direction = placement.clickedFace();
        BlockPos attachmentPos = placement.attachmentPos();
        Player player = context.getPlayer();
        ItemStack itemStack = context.getItemInHand();
        if (player != null && !this.mayPlace(player, direction, itemStack, attachmentPos)) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }

        Level level = context.getLevel();
        HangingEntity entity = kinetic_assembly$createAttachedEntity(level, attachmentPos, direction, cir);
        if (entity == null) {
            return;
        }

        CustomData customData = itemStack.getOrDefault(DataComponents.ENTITY_DATA, CustomData.EMPTY);
        if (!customData.isEmpty()) {
            EntityType.updateCustomEntityTag(level, player, entity, customData);
        }

        if (entity.survives()) {
            if (!level.isClientSide) {
                entity.playPlacementSound();
                level.gameEvent(player, GameEvent.ENTITY_PLACE, entity.position());
                level.addFreshEntity(entity);
            }

            itemStack.shrink(1);
            cir.setReturnValue(InteractionResult.sidedSuccess(level.isClientSide));
        } else {
            cir.setReturnValue(InteractionResult.CONSUME);
        }
    }

    private HangingEntity kinetic_assembly$createAttachedEntity(
            Level level,
            BlockPos attachmentPos,
            Direction direction,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        if (this.type == EntityType.PAINTING) {
            Optional<Painting> painting = Painting.create(level, attachmentPos, direction);
            if (painting.isEmpty()) {
                cir.setReturnValue(InteractionResult.CONSUME);
                return null;
            }
            return painting.get();
        }
        if (this.type == EntityType.ITEM_FRAME) {
            return new ItemFrame(level, attachmentPos, direction);
        }
        if (this.type == EntityType.GLOW_ITEM_FRAME) {
            return new GlowItemFrame(level, attachmentPos, direction);
        }

        cir.setReturnValue(InteractionResult.sidedSuccess(level.isClientSide));
        return null;
    }
}
