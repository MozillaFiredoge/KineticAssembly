package com.firedoge.kineticassembly.network;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyClientMetadata;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ClientboundStartTrackingAssemblyPayload(AssemblyClientMetadata metadata) implements CustomPacketPayload {
    public static final Type<ClientboundStartTrackingAssemblyPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(KineticAssembly.MODID, "start_tracking_assembly")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundStartTrackingAssemblyPayload> STREAM_CODEC =
            StreamCodec.ofMember(ClientboundStartTrackingAssemblyPayload::write, ClientboundStartTrackingAssemblyPayload::read);

    private static ClientboundStartTrackingAssemblyPayload read(RegistryFriendlyByteBuf buffer) {
        return new ClientboundStartTrackingAssemblyPayload(AssemblyPayloadCodecs.readMetadata(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        AssemblyPayloadCodecs.writeMetadata(buffer, metadata);
    }

    @Override
    public Type<ClientboundStartTrackingAssemblyPayload> type() {
        return TYPE;
    }
}
