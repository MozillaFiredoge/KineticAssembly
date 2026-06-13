package com.firedoge.kineticassembly.mixin.render;

import com.firedoge.kineticassembly.render.ClientAssemblyBreakingProgress;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow
    private ClientLevel level;

    @Inject(method = "destroyBlockProgress", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$storePlotDestroyProgress(int breakerId, BlockPos pos, int progress, CallbackInfo ci) {
        ClientLevel clientLevel = level != null ? level : Minecraft.getInstance().level;
        if (clientLevel != null && ClientAssemblyBreakingProgress.update(clientLevel, breakerId, pos, progress)) {
            ci.cancel();
        } else if (progress < 0 && ClientAssemblyBreakingProgress.removeIfTracked(breakerId)) {
            ci.cancel();
        }
    }
}
