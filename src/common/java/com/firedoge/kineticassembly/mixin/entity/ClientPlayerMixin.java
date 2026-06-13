package com.firedoge.kineticassembly.mixin.entity;

import com.firedoge.kineticassembly.render.ClientAssemblyEffectProjection;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class ClientPlayerMixin {
    @Inject(method = "canInteractWithBlock", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$canInteractWithClientPlotBlock(
            BlockPos pos,
            double distance,
            CallbackInfoReturnable<Boolean> cir
    ) {
        Player player = (Player) (Object) this;
        Level level = player.level();
        if (!(level instanceof ClientLevel clientLevel)) {
            return;
        }

        ClientAssemblyEffectProjection.Projection projection = ClientAssemblyEffectProjection
                .projection(clientLevel, pos)
                .orElse(null);
        if (projection == null) {
            return;
        }

        Vec3 plotEye = projection.worldToPlot(player.getEyePosition());
        double maxDistance = player.blockInteractionRange() + distance;
        cir.setReturnValue(new AABB(pos).distanceToSqr(plotEye) < maxDistance * maxDistance);
    }
}
