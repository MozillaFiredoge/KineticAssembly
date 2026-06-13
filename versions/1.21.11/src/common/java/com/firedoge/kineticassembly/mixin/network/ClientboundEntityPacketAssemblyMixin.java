package com.firedoge.kineticassembly.mixin.network;

import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityPacketAccess;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({
        ClientboundEntityPositionSyncPacket.class,
        ClientboundMoveEntityPacket.Pos.class,
        ClientboundMoveEntityPacket.PosRot.class,
        ClientboundTeleportEntityPacket.class
})
public abstract class ClientboundEntityPacketAssemblyMixin implements AssemblyEntityPacketAccess {
    @Shadow(remap = false)
    @Final
    @Mutable
    @SuppressWarnings("rawtypes")
    private static StreamCodec STREAM_CODEC;

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

    @Inject(method = "<clinit>", at = @At("TAIL"))
    @SuppressWarnings("unchecked")
    private static void kinetic_assembly$wrapStreamCodec(CallbackInfo ci) {
        STREAM_CODEC = kinetic_assembly$assemblyCodec(STREAM_CODEC);
    }

    @Unique
    private static StreamCodec<FriendlyByteBuf, Object> kinetic_assembly$assemblyCodec(
            StreamCodec<FriendlyByteBuf, Object> original
    ) {
        return new StreamCodec<>() {
            @Override
            public Object decode(FriendlyByteBuf buffer) {
                Object packet = original.decode(buffer);
                if (packet instanceof AssemblyEntityPacketAccess access) {
                    access.kinetic_assembly$setActuallyInAssembly(buffer.readBoolean());
                }
                return packet;
            }

            @Override
            public void encode(FriendlyByteBuf buffer, Object packet) {
                original.encode(buffer, packet);
                buffer.writeBoolean(packet instanceof AssemblyEntityPacketAccess access
                        && access.kinetic_assembly$actuallyInAssembly());
            }
        };
    }
}
