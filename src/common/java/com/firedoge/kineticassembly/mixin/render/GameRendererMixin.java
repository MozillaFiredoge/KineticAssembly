package com.firedoge.kineticassembly.mixin.render;

import com.firedoge.kineticassembly.render.ClientAssemblyPickBridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow
    @Final
    Minecraft minecraft;

    @Inject(method = "pick", at = @At("TAIL"))
    private void kinetic_assembly$pickAssembly(float partialTicks, CallbackInfo ci) {
        ClientAssemblyPickBridge.updateCrosshairTarget(minecraft);
    }
}
