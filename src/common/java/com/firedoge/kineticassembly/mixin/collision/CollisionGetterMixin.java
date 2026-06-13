package com.firedoge.kineticassembly.mixin.collision;

import java.util.List;

import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityBridge;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityCollisionAccess;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyManager;
import com.google.common.collect.Iterables;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CollisionGetter.class)
public interface CollisionGetterMixin {
    @Inject(method = "getBlockCollisions", at = @At("RETURN"), cancellable = true)
    private void kinetic_assembly$appendServerAssemblyBlockCollisions(
            Entity entity,
            AABB collisionBox,
            CallbackInfoReturnable<Iterable<VoxelShape>> cir
    ) {
        if (!((Object) this instanceof ServerLevel level)) {
            return;
        }
        if (entity instanceof AssemblyEntityCollisionAccess) {
            return;
        }
        if (entity != null && AssemblyEntityBridge.isRegisteredAttachedEntity(level, entity)) {
            return;
        }

        List<VoxelShape> assemblyCollisions = AssemblyManager.INSTANCE.blockCollisionShapes(level, collisionBox);
        if (!assemblyCollisions.isEmpty()) {
            cir.setReturnValue(Iterables.concat(cir.getReturnValue(), assemblyCollisions));
        }
    }
}
