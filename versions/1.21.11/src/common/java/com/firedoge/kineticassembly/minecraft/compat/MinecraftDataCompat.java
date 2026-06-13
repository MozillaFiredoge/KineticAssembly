package com.firedoge.kineticassembly.minecraft.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.TagValueInput;

public final class MinecraftDataCompat {
    private MinecraftDataCompat() {
    }

    public static void loadEntity(Entity entity, CompoundTag tag) {
        ProblemReporter.Collector reporter = new ProblemReporter.Collector();
        entity.load(TagValueInput.create(reporter, entity.registryAccess(), tag));
        if (!reporter.isEmpty()) {
            throw new IllegalArgumentException("Failed to load entity from NBT: " + reporter.getReport());
        }
    }
}
