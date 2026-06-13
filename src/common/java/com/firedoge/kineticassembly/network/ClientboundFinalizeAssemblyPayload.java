package com.firedoge.kineticassembly.network;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyId;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ClientboundFinalizeAssemblyPayload(AssemblyId id) implements CustomPacketPayload {
    public static final Type<ClientboundFinalizeAssemblyPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(KineticAssembly.MODID, "finalize_assembly")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundFinalizeAssemblyPayload> STREAM_CODEC =
            StreamCodec.ofMember(ClientboundFinalizeAssemblyPayload::write, ClientboundFinalizeAssemblyPayload::read);

    private static ClientboundFinalizeAssemblyPayload read(RegistryFriendlyByteBuf buffer) {
        return new ClientboundFinalizeAssemblyPayload(AssemblyPayloadCodecs.readAssemblyId(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        AssemblyPayloadCodecs.writeAssemblyId(buffer, id);
    }

    @Override
    public Type<ClientboundFinalizeAssemblyPayload> type() {
        return TYPE;
    }
}
