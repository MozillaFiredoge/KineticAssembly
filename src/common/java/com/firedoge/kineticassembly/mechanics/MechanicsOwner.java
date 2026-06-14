package com.firedoge.kineticassembly.mechanics;

import java.util.Objects;

import net.minecraft.resources.ResourceLocation;

public record MechanicsOwner(ResourceLocation id) {
    public static final MechanicsOwner KINETIC_ASSEMBLY = new MechanicsOwner(ResourceLocation.fromNamespaceAndPath(
            "kinetic_assembly",
            "internal"
    ));
    public static final MechanicsOwner UNSPECIFIED = new MechanicsOwner(ResourceLocation.fromNamespaceAndPath(
            "kinetic_assembly",
            "unspecified"
    ));

    public MechanicsOwner {
        Objects.requireNonNull(id, "id");
    }

    public static MechanicsOwner of(String namespace, String path) {
        return new MechanicsOwner(ResourceLocation.fromNamespaceAndPath(namespace, path));
    }
}
