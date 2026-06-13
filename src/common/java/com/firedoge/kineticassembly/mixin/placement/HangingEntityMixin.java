package com.firedoge.kineticassembly.mixin.placement;

import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityBridge;

import java.util.Optional;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HangingEntity.class)
public abstract class HangingEntityMixin {
    @Inject(method = "survives", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$assemblyAttachedEntitySurvives(CallbackInfoReturnable<Boolean> cir) {
        HangingEntity hangingEntity = (HangingEntity) (Object) this;
        Level level = hangingEntity.level();
        Optional<Boolean> survives = Optional.empty();
        if (level instanceof ServerLevel serverLevel) {
            survives = AssemblyEntityBridge.attachedEntitySurvives(serverLevel, hangingEntity);
        }
        if (survives.isEmpty()) {
            survives = AssemblyEntityBridge.plotAttachedEntitySurvives(level, hangingEntity);
        }
        survives.ifPresent(cir::setReturnValue);
    }
}
