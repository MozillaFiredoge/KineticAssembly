package com.firedoge.kineticassembly.mixin.level;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityBridge;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyManager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelMixin {
    @Inject(method = "blockEntityChanged", at = @At("TAIL"))
    private void kinetic_assembly$syncPlotBlockEntityTag(BlockPos pos, CallbackInfo ci) {
        if ((Object) this instanceof ServerLevel level) {
            AssemblyManager.INSTANCE.syncPlotBlockEntityTag(level, pos);
        }
    }

    @Inject(
            method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true
    )
    private void kinetic_assembly$appendProjectedPlotEntities(
            @Nullable Entity entity,
            AABB boundingBox,
            Predicate<? super Entity> predicate,
            CallbackInfoReturnable<List<Entity>> cir
    ) {
        if (!((Object) this instanceof ServerLevel level) || AssemblyEntityBridge.isProjectingQuery()) {
            return;
        }

        List<Entity> projected = AssemblyEntityBridge.projectedEntities(level, entity, boundingBox, predicate, cir.getReturnValue());
        if (!projected.isEmpty()) {
            List<Entity> combined = new ArrayList<>(cir.getReturnValue());
            combined.addAll(projected);
            cir.setReturnValue(combined);
        }
    }

    @Inject(
            method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true
    )
    private <T extends Entity> void kinetic_assembly$appendProjectedPlotEntitiesOfType(
            EntityTypeTest<Entity, T> entityTypeTest,
            AABB bounds,
            Predicate<? super T> predicate,
            CallbackInfoReturnable<List<T>> cir
    ) {
        if (!((Object) this instanceof ServerLevel level) || AssemblyEntityBridge.isProjectingQuery()) {
            return;
        }

        List<T> projected = AssemblyEntityBridge.projectedEntities(level, entityTypeTest, bounds, predicate, cir.getReturnValue());
        if (!projected.isEmpty()) {
            List<T> combined = new ArrayList<>(cir.getReturnValue());
            combined.addAll(projected);
            cir.setReturnValue(combined);
        }
    }

    @Inject(
            method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;Ljava/util/List;I)V",
            at = @At("RETURN")
    )
    private <T extends Entity> void kinetic_assembly$appendProjectedPlotEntitiesOfTypeToOutput(
            EntityTypeTest<Entity, T> entityTypeTest,
            AABB bounds,
            Predicate<? super T> predicate,
            List<? super T> output,
            int maxResults,
            CallbackInfo ci
    ) {
        if (!((Object) this instanceof ServerLevel level) || AssemblyEntityBridge.isProjectingQuery()) {
            return;
        }
        int remaining = maxResults - output.size();
        if (remaining <= 0) {
            return;
        }

        List<T> projected = AssemblyEntityBridge.projectedEntities(level, entityTypeTest, bounds, predicate, output, remaining);
        if (!projected.isEmpty()) {
            output.addAll(projected);
        }
    }
}
