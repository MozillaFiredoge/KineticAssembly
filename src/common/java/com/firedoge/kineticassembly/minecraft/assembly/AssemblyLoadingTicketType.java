package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;

import com.firedoge.kineticassembly.KineticAssembly;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Unit;

public record AssemblyLoadingTicketType<T>(ResourceLocation name, Codec<T> codec) {
    private static final Map<ResourceLocation, AssemblyLoadingTicketType<?>> REGISTRY = new HashMap<>();

    public static final AssemblyLoadingTicketType<Unit> COMMAND_FORCED = create(
            ResourceLocation.fromNamespaceAndPath(KineticAssembly.MODID, "command_forced"),
            Unit.CODEC
    );

    public AssemblyLoadingTicketType {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(codec, "codec");
    }

    public static <T> AssemblyLoadingTicketType<T> create(ResourceLocation name, Codec<T> codec) {
        AssemblyLoadingTicketType<T> type = new AssemblyLoadingTicketType<>(name, codec);
        REGISTRY.put(name, type);
        return type;
    }

    @Nullable
    public static AssemblyLoadingTicketType<?> byName(ResourceLocation name) {
        return REGISTRY.get(name);
    }
}
