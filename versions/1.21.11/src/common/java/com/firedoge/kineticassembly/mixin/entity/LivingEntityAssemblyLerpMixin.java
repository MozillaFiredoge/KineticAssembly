package com.firedoge.kineticassembly.mixin.entity;

import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityCollision;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityCollisionAccess;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityKicking;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPlotProjection;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyVectors;

import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityAssemblyLerpMixin extends Entity implements AssemblyEntityCollisionAccess {
    public LivingEntityAssemblyLerpMixin(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    public void kinetic_assembly$plotLerpTo(Vec3 position, int lerpSteps) {
        kinetic_assembly$setPlotPosition(position);
    }

    @Inject(method = "recreateFromPacket", at = @At("TAIL"))
    private void kinetic_assembly$recreateFromAssemblyAddPacket(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        Vec3 packetPosition = new Vec3(packet.getX(), packet.getY(), packet.getZ());
        AssemblyPlotProjection projection = AssemblyEntityCollision.spawnPacketProjection(level(), packetPosition);
        if (projection == null) {
            return;
        }
        if (!AssemblyEntityKicking.shouldKick(this)) {
            return;
        }

        Vec3 worldPosition = projection.plotToWorld(packetPosition);
        if (!AssemblyVectors.finite(worldPosition)) {
            return;
        }
        setPos(worldPosition.x, worldPosition.y, worldPosition.z);
        kinetic_assembly$setTrackingAssemblyId(projection.id());
        kinetic_assembly$setPlotPosition(packetPosition);
    }
}
