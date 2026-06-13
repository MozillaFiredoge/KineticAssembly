package com.firedoge.kineticassembly.mixin.entity;

import javax.annotation.Nullable;

import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.minecraft.assembly.ClientAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.ClientTrackedAssembly;
import com.firedoge.kineticassembly.minecraft.assembly.EntityAssemblyState;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyCoordinateSpace;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityCollisionAccess;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyId;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyTransform;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyVerticalTrace;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyVectors;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerAssemblyMoveMixin {
    @Unique
    @Nullable
    private Vec3 kinetic_assembly$oldWorldPosition;

    @Inject(
            method = "sendPosition",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;getX()D",
                    ordinal = 0,
                    shift = At.Shift.BEFORE
            )
    )
    private void kinetic_assembly$preSendAssemblyPosition(CallbackInfo ci) {
        kinetic_assembly$oldWorldPosition = null;
        Entity self = (Entity) (Object) this;
        AssemblyEntityCollisionAccess access = (AssemblyEntityCollisionAccess) self;
        EntityAssemblyState state = access.kinetic_assembly$assemblyState();
        if (state == null || !state.active() || state.id() == null) {
            access.kinetic_assembly$setPlotPosition(null);
            AssemblyVerticalTrace.clientPacket(
                    self,
                    "world",
                    state,
                    access.kinetic_assembly$trackingAssemblyId(),
                    self.position(),
                    null,
                    "no-active-state"
            );
            return;
        }
        AssemblyId trackingId = state.id();

        ClientTrackedAssembly tracked = AssemblyContainers.container(self.level())
                .filter(ClientAssemblyContainer.class::isInstance)
                .map(ClientAssemblyContainer.class::cast)
                .flatMap(container -> container.trackedAssembly(trackingId))
                .orElse(null);
        if (tracked == null || !tracked.finalized()) {
            AssemblyVerticalTrace.clientPacket(
                    self,
                    "world",
                    state,
                    access.kinetic_assembly$trackingAssemblyId(),
                    self.position(),
                    null,
                    tracked == null ? "missing-client-assembly" : "client-assembly-not-finalized"
            );
            return;
        }

        Vec3 worldPosition = self.position();
        PhysicsVector bodyLocalPosition = AssemblyTransform.from(tracked.pose()).worldToLocal(new PhysicsVector(
                worldPosition.x,
                worldPosition.y,
                worldPosition.z
        ));
        PhysicsVector plotPosition = AssemblyCoordinateSpace.bodyLocalToPlot(tracked.metadata(), bodyLocalPosition);
        if (!AssemblyVectors.finite(plotPosition)) {
            AssemblyVerticalTrace.clientPacket(
                    self,
                    "world",
                    state,
                    access.kinetic_assembly$trackingAssemblyId(),
                    self.position(),
                    null,
                    "non-finite-plot"
            );
            return;
        }

        kinetic_assembly$oldWorldPosition = worldPosition;
        Vec3 plotVec = new Vec3(plotPosition.x(), plotPosition.y(), plotPosition.z());
        AssemblyVerticalTrace.clientPacket(
                self,
                "plot",
                state,
                access.kinetic_assembly$trackingAssemblyId(),
                worldPosition,
                plotVec,
                "send-local-position"
        );
        access.kinetic_assembly$setPlotPosition(plotVec);
        access.kinetic_assembly$setPosField(plotVec);
    }

    @Inject(method = "sendPosition", at = @At("RETURN"))
    private void kinetic_assembly$postSendAssemblyPosition(CallbackInfo ci) {
        if (kinetic_assembly$oldWorldPosition == null) {
            return;
        }
        Entity self = (Entity) (Object) this;
        ((AssemblyEntityCollisionAccess) self).kinetic_assembly$setPosField(kinetic_assembly$oldWorldPosition);
        kinetic_assembly$oldWorldPosition = null;
    }
}
