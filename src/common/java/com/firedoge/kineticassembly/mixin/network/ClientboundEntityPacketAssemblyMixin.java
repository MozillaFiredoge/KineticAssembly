package com.firedoge.kineticassembly.mixin.network;

import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityPacketAccess;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({
        ClientboundMoveEntityPacket.Pos.class,
        ClientboundMoveEntityPacket.PosRot.class,
        ClientboundTeleportEntityPacket.class
})
public abstract class ClientboundEntityPacketAssemblyMixin implements AssemblyEntityPacketAccess {
    @Unique
    private boolean kinetic_assembly$actuallyInAssembly;

    @Override
    public void kinetic_assembly$setActuallyInAssembly(boolean actuallyInAssembly) {
        kinetic_assembly$actuallyInAssembly = actuallyInAssembly;
    }

    @Override
    public boolean kinetic_assembly$actuallyInAssembly() {
        return kinetic_assembly$actuallyInAssembly;
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void kinetic_assembly$writeActuallyInAssembly(FriendlyByteBuf buffer, CallbackInfo ci) {
        buffer.writeBoolean(kinetic_assembly$actuallyInAssembly);
    }

    @Inject(method = "read", at = @At("RETURN"), require = 0)
    private static void kinetic_assembly$readMoveActuallyInAssembly(
            FriendlyByteBuf buffer,
            CallbackInfoReturnable<? extends ClientboundMoveEntityPacket> cir
    ) {
        ((AssemblyEntityPacketAccess) cir.getReturnValue()).kinetic_assembly$setActuallyInAssembly(buffer.readBoolean());
    }

    @Inject(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("RETURN"), require = 0)
    private void kinetic_assembly$readTeleportActuallyInAssembly(FriendlyByteBuf buffer, CallbackInfo ci) {
        kinetic_assembly$actuallyInAssembly = buffer.readBoolean();
    }
}
