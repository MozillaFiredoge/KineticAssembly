package com.firedoge.kineticassembly.mixin.accessor;

import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(HangingEntity.class)
public interface HangingEntityAccess {
    @Invoker("calculateSupportBox")
    AABB kinetic_assembly$calculateSupportBox();
}
