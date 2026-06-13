package com.firedoge.kineticassembly.mixin.level;

import java.util.Optional;
import java.util.function.Supplier;

import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Block.class)
public abstract class BlockMixin {
    @Shadow
    private static void popResource(Level level, Supplier<ItemEntity> itemEntitySupplier, ItemStack stack) {
    }

    @Inject(
            method = "popResource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void kinetic_assembly$popPlotResource(Level level, BlockPos pos, ItemStack stack, CallbackInfo ci) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        Optional<AssemblyManager.PlotProjection> maybeProjection = AssemblyManager.INSTANCE.plotProjection(serverLevel, pos);
        if (maybeProjection.isEmpty()) {
            return;
        }

        double halfItemHeight = (double) EntityType.ITEM.getHeight() / 2.0D;
        Vec3 plotSpawn = new Vec3(
                (double) pos.getX() + 0.5D + Mth.nextDouble(level.random, -0.25D, 0.25D),
                (double) pos.getY() + 0.5D + Mth.nextDouble(level.random, -0.25D, 0.25D) - halfItemHeight,
                (double) pos.getZ() + 0.5D + Mth.nextDouble(level.random, -0.25D, 0.25D)
        );
        AssemblyManager.PlotProjection projection = maybeProjection.get();
        PhysicsVector worldSpawn = projection.toWorld(plotSpawn);
        PhysicsVector worldMotion = projection.directionToWorld(new Vec3(
                level.random.nextDouble() * 0.2D - 0.1D,
                0.2D,
                level.random.nextDouble() * 0.2D - 0.1D
        ));

        popResource(level, () -> new ItemEntity(
                level,
                worldSpawn.x(),
                worldSpawn.y(),
                worldSpawn.z(),
                stack,
                worldMotion.x(),
                worldMotion.y(),
                worldMotion.z()
        ), stack);
        ci.cancel();
    }

    @Inject(
            method = "popResourceFromFace",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void kinetic_assembly$popPlotResourceFromFace(
            Level level,
            BlockPos pos,
            Direction direction,
            ItemStack stack,
            CallbackInfo ci
    ) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        Optional<AssemblyManager.PlotProjection> maybeProjection = AssemblyManager.INSTANCE.plotProjection(serverLevel, pos);
        if (maybeProjection.isEmpty()) {
            return;
        }

        int stepX = direction.getStepX();
        int stepY = direction.getStepY();
        int stepZ = direction.getStepZ();
        double halfItemWidth = (double) EntityType.ITEM.getWidth() / 2.0D;
        double halfItemHeight = (double) EntityType.ITEM.getHeight() / 2.0D;
        Vec3 plotSpawn = new Vec3(
                (double) pos.getX() + 0.5D + (stepX == 0 ? Mth.nextDouble(level.random, -0.25D, 0.25D) : (double) stepX * (0.5D + halfItemWidth)),
                (double) pos.getY() + 0.5D + (stepY == 0 ? Mth.nextDouble(level.random, -0.25D, 0.25D) : (double) stepY * (0.5D + halfItemHeight)) - halfItemHeight,
                (double) pos.getZ() + 0.5D + (stepZ == 0 ? Mth.nextDouble(level.random, -0.25D, 0.25D) : (double) stepZ * (0.5D + halfItemWidth))
        );
        Vec3 plotMotion = new Vec3(
                stepX == 0 ? Mth.nextDouble(level.random, -0.1D, 0.1D) : (double) stepX * 0.1D,
                stepY == 0 ? Mth.nextDouble(level.random, 0.0D, 0.1D) : (double) stepY * 0.1D + 0.1D,
                stepZ == 0 ? Mth.nextDouble(level.random, -0.1D, 0.1D) : (double) stepZ * 0.1D
        );
        AssemblyManager.PlotProjection projection = maybeProjection.get();
        PhysicsVector worldSpawn = projection.toWorld(plotSpawn);
        PhysicsVector worldMotion = projection.directionToWorld(plotMotion);

        popResource(level, () -> new ItemEntity(
                level,
                worldSpawn.x(),
                worldSpawn.y(),
                worldSpawn.z(),
                stack,
                worldMotion.x(),
                worldMotion.y(),
                worldMotion.z()
        ), stack);
        ci.cancel();
    }

    @Inject(method = "popExperience", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$popPlotExperience(ServerLevel level, BlockPos pos, int amount, CallbackInfo ci) {
        Optional<PhysicsVector> maybeWorld = AssemblyManager.INSTANCE.plotPositionToWorld(level, Vec3.atCenterOf(pos));
        if (maybeWorld.isEmpty()) {
            return;
        }

        if (!level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS) || level.restoringBlockSnapshots) {
            ci.cancel();
            return;
        }

        PhysicsVector world = maybeWorld.get();
        ExperienceOrb.award(level, new Vec3(world.x(), world.y(), world.z()), amount);
        ci.cancel();
    }
}
