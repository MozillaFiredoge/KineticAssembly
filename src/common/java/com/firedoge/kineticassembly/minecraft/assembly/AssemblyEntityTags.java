package com.firedoge.kineticassembly.minecraft.assembly;

import com.firedoge.kineticassembly.KineticAssembly;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;

public final class AssemblyEntityTags {
    public static final TagKey<EntityType<?>> RETAIN_IN_ASSEMBLY = TagKey.create(
            Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(KineticAssembly.MODID, "retain_in_assembly")
    );
    public static final TagKey<EntityType<?>> DESTROY_WITH_ASSEMBLY = TagKey.create(
            Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(KineticAssembly.MODID, "destroy_with_assembly")
    );
    public static final TagKey<EntityType<?>> DESTROY_WHEN_LEAVING_PLOT = TagKey.create(
            Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(KineticAssembly.MODID, "destroy_when_leaving_plot")
    );

    private AssemblyEntityTags() {
    }
}
