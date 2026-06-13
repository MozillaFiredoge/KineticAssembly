package com.firedoge.kineticassembly.mixin.placement;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.minecraft.assembly.PhysicsAssembly;
import com.firedoge.kineticassembly.minecraft.assembly.ServerAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyCoordinateSpace;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyManager;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPickResult;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPlotProjection;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyVectors;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.ChestBoat;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BoatItem.class)
public abstract class BoatItemAssemblyPlacementMixin {
    private static final Predicate<Entity> KINETIC_ASSEMBLY_ENTITY_PREDICATE = EntitySelector.NO_SPECTATORS.and(Entity::isPickable);

    @Shadow
    @Final
    private Boat.Type type;

    @Shadow
    @Final
    private boolean hasChest;

    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$placeBoatOnAssembly(
            Level level,
            Player player,
            InteractionHand hand,
            CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir
    ) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        ItemStack itemStack = player.getItemInHand(hand);
        BoatPlacement placement = kinetic_assembly$boatPlacement(serverLevel, player).orElse(null);
        if (placement == null) {
            return;
        }

        Boat boat = this.hasChest
                ? new ChestBoat(serverLevel, placement.plotPosition().x, placement.plotPosition().y, placement.plotPosition().z)
                : new Boat(serverLevel, placement.plotPosition().x, placement.plotPosition().y, placement.plotPosition().z);
        EntityType.<Boat>createDefaultStackConfig(serverLevel, itemStack, player).accept(boat);
        boat.setVariant(this.type);
        boat.setYRot(placement.yRot());
        if (!serverLevel.noCollision(boat, boat.getBoundingBox())) {
            cir.setReturnValue(InteractionResultHolder.fail(itemStack));
            return;
        }

        serverLevel.addFreshEntity(boat);
        serverLevel.gameEvent(player, GameEvent.ENTITY_PLACE, placement.plotPosition());
        itemStack.consume(1, player);
        player.awardStat(Stats.ITEM_USED.get((BoatItem) (Object) this));
        cir.setReturnValue(InteractionResultHolder.sidedSuccess(itemStack, false));
    }

    private static Optional<BoatPlacement> kinetic_assembly$boatPlacement(ServerLevel level, Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        double maxDistance = 5.0D;
        Optional<AssemblyPickResult> maybePick = AssemblyManager.INSTANCE.pickBlock(
                level,
                AssemblyCoordinateSpace.toPhysicsVector(eye),
                AssemblyCoordinateSpace.toPhysicsVector(look),
                maxDistance
        );
        if (maybePick.isEmpty()) {
            return Optional.empty();
        }

        AssemblyPickResult pick = maybePick.get();
        HitResult vanillaHit = Item.getPlayerPOVHitResult(level, player, ClipContext.Fluid.ANY);
        if (vanillaHit.getType() != HitResult.Type.MISS
                && vanillaHit.getLocation().distanceTo(eye) <= pick.distance() + 1.0E-6D) {
            return Optional.empty();
        }
        if (kinetic_assembly$viewBlockedByEntity(level, player, eye, look, maxDistance)) {
            return Optional.empty();
        }

        ServerAssemblyContainer container = AssemblyContainers.server(level).orElse(null);
        PhysicsAssembly assembly = container == null ? null : container.assembly(pick.id()).orElse(null);
        if (assembly == null) {
            return Optional.empty();
        }

        PhysicsVector plotHit = AssemblyCoordinateSpace.bodyLocalToPlot(
                assembly.plot(),
                AssemblyCoordinateSpace.bodyToPlotOrigin(assembly),
                pick.localHit()
        );
        Vec3 plotPosition = AssemblyCoordinateSpace.toVec3(plotHit);
        if (!AssemblyVectors.finite(plotPosition)) {
            return Optional.empty();
        }

        float yRot = player.getYRot();
        AssemblyPlotProjection projection = container.plotProjection(BlockPos.containing(plotPosition)).orElse(null);
        if (projection != null) {
            Vec3 plotLook = projection.worldDirectionToPlot(look);
            if (AssemblyVectors.finite(plotLook) && plotLook.horizontalDistanceSqr() > 1.0E-12D) {
                yRot = (float) (Math.atan2(-plotLook.x, plotLook.z) * 180.0D / Math.PI);
            }
        }
        return Optional.of(new BoatPlacement(plotPosition, yRot));
    }

    private static boolean kinetic_assembly$viewBlockedByEntity(
            Level level,
            Player player,
            Vec3 eye,
            Vec3 look,
            double maxDistance
    ) {
        List<Entity> entities = level.getEntities(
                player,
                player.getBoundingBox().expandTowards(look.scale(maxDistance)).inflate(1.0D),
                KINETIC_ASSEMBLY_ENTITY_PREDICATE
        );
        for (Entity entity : entities) {
            AABB bounds = entity.getBoundingBox().inflate(entity.getPickRadius());
            if (bounds.contains(eye)) {
                return true;
            }
        }
        return false;
    }

    private record BoatPlacement(Vec3 plotPosition, float yRot) {
    }
}
