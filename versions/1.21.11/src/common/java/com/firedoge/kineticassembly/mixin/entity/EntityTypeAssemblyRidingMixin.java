package com.firedoge.kineticassembly.mixin.entity;

import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityKicking;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityPositioning;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPlotProjection;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityType.class)
public abstract class EntityTypeAssemblyRidingMixin {
    @Inject(
            method = "loadPassengersRecursive",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z"
            )
    )
    private static void kinetic_assembly$kickLoadedPassengerOutOfAssemblyVehicle(
            Entity vehicle,
            ValueInput input,
            Level level,
            EntitySpawnReason spawnReason,
            EntityProcessor processor,
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
