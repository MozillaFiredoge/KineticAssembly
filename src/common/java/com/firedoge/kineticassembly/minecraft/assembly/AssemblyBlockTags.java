package com.firedoge.kineticassembly.minecraft.assembly;

import com.firedoge.kineticassembly.KineticAssembly;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

public final class AssemblyBlockTags {
    public static final TagKey<Block> BOUNCY = TagKey.create(
            Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(KineticAssembly.MODID, "bouncy")
    );

    private AssemblyBlockTags() {
    }
}
