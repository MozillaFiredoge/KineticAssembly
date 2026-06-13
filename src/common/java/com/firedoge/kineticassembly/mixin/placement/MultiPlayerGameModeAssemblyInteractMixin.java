package com.firedoge.kineticassembly.mixin.placement;

import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityBridge;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPlotProjection;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyVectors;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeAssemblyInteractMixin {
    @Redirect(
            method = "interactAt",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/Vec3;subtract(DDD)Lnet/minecraft/world/phys/Vec3;"
            )
    )
    private Vec3 kinetic_assembly$assemblyInteractAtLocation(
            Vec3 hitLocation,
            double x,
            double y,
            double z,
            Player player,
            Entity target,
            EntityHitResult ray,
            InteractionHand hand
    ) {
        AssemblyPlotProjection projection = AssemblyEntityBridge
                .plotProjectionForRetainedEntity(target.level(), target)
                .orElse(null);
        if (projection == null) {
            return hitLocation.subtract(x, y, z);
        }

        Vec3 plotHit = projection.worldToPlot(hitLocation);
        if (!AssemblyVectors.finite(plotHit)) {
            return hitLocation.subtract(x, y, z);
        }
        return plotHit.subtract(target.position());
    }
}
