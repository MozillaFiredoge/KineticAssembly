package com.firedoge.kineticassembly.network;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyId;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPoseFrame;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ClientboundAssemblyTransformPayload(AssemblyPoseFrame frame) implements CustomPacketPayload {
    public static final Type<ClientboundAssemblyTransformPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(KineticAssembly.MODID, "assembly_transform")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundAssemblyTransformPayload> STREAM_CODEC =
            StreamCodec.ofMember(ClientboundAssemblyTransformPayload::write, ClientboundAssemblyTransformPayload::read);

    public ClientboundAssemblyTransformPayload {
        java.util.Objects.requireNonNull(frame, "frame");
    }

    public AssemblyId id() {
        return frame.id();
    }

    public PhysicsPose pose() {
        return frame.currentPose();
    }

    private static ClientboundAssemblyTransformPayload read(RegistryFriendlyByteBuf buffer) {
        return new ClientboundAssemblyTransformPayload(AssemblyPayloadCodecs.readPoseFrame(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        AssemblyPayloadCodecs.writePoseFrame(buffer, frame);
    }

    @Override
    public Type<ClientboundAssemblyTransformPayload> type() {
        return TYPE;
    }
}
