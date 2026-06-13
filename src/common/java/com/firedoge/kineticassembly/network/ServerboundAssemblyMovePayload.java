package com.firedoge.kineticassembly.network;

import javax.annotation.Nullable;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyId;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ServerboundAssemblyMovePayload(
        @Nullable AssemblyId id,
        PhysicsVector localPosition
) implements CustomPacketPayload {
    public static final Type<ServerboundAssemblyMovePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(KineticAssembly.MODID, "assembly_move")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundAssemblyMovePayload> STREAM_CODEC =
            StreamCodec.ofMember(ServerboundAssemblyMovePayload::write, ServerboundAssemblyMovePayload::read);

    public static ServerboundAssemblyMovePayload tracked(AssemblyId id, PhysicsVector localPosition) {
        return new ServerboundAssemblyMovePayload(id, localPosition);
    }

    public static ServerboundAssemblyMovePayload clear() {
        return new ServerboundAssemblyMovePayload(null, PhysicsVector.ZERO);
    }

    private static ServerboundAssemblyMovePayload read(RegistryFriendlyByteBuf buffer) {
        if (!buffer.readBoolean()) {
            return clear();
        }
        return tracked(
                AssemblyPayloadCodecs.readAssemblyId(buffer),
                AssemblyPayloadCodecs.readVector(buffer)
        );
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        boolean tracked = id != null;
        buffer.writeBoolean(tracked);
        if (!tracked) {
            return;
        }
        AssemblyPayloadCodecs.writeAssemblyId(buffer, id);
        AssemblyPayloadCodecs.writeVector(buffer, localPosition);
    }

    @Override
    public Type<ServerboundAssemblyMovePayload> type() {
        return TYPE;
    }
}
