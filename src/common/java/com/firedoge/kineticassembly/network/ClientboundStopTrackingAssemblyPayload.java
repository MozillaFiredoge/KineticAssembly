package com.firedoge.kineticassembly.network;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyId;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ClientboundStopTrackingAssemblyPayload(AssemblyId id) implements CustomPacketPayload {
    public static final Type<ClientboundStopTrackingAssemblyPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(KineticAssembly.MODID, "stop_tracking_assembly")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundStopTrackingAssemblyPayload> STREAM_CODEC =
            StreamCodec.ofMember(ClientboundStopTrackingAssemblyPayload::write, ClientboundStopTrackingAssemblyPayload::read);

    private static ClientboundStopTrackingAssemblyPayload read(RegistryFriendlyByteBuf buffer) {
        return new ClientboundStopTrackingAssemblyPayload(AssemblyPayloadCodecs.readAssemblyId(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        AssemblyPayloadCodecs.writeAssemblyId(buffer, id);
    }

    @Override
    public Type<ClientboundStopTrackingAssemblyPayload> type() {
        return TYPE;
    }
}
