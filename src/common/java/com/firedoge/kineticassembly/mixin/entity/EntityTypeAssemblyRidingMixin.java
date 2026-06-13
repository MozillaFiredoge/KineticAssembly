package com.firedoge.kineticassembly.mixin.entity;

import java.util.function.Function;

import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityKicking;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityPositioning;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPlotProjection;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityType.class)
public abstract class EntityTypeAssemblyRidingMixin {
    @Inject(
            method = "lambda$loadEntityRecursive$7",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;startRiding(Lnet/minecraft/world/entity/Entity;Z)Z"
            )
    )
    private static void kinetic_assembly$kickLoadedPassengerOutOfAssemblyVehicle(
            CompoundTag compound,
            Level level,
            Function<Entity, Entity> entityFunction,
            Entity vehicle,
            CallbackInfoReturnable<Entity> cir,
            @Local(ordinal = 1) Entity passenger
    ) {
        if (!AssemblyEntityKicking.shouldKick(passenger)) {
            return;
        }

        AssemblyPlotProjection projection = AssemblyEntityPositioning.containingProjection(vehicle);
        if (projection == null) {
            return;
        }
        passenger.setPos(AssemblyEntityPositioning.kickRidingEntity(passenger, projection));
    }
}
