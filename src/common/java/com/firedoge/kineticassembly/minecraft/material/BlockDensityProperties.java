package com.firedoge.kineticassembly.minecraft.material;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import net.minecraft.resources.ResourceLocation;

public record BlockDensityProperties(
        double massScale,
        double defaultDensity,
        Map<ResourceLocation, Double> blockDensities,
        Map<ResourceLocation, Double> tagDensities
) {
    public BlockDensityProperties {
        if (!Double.isFinite(massScale) || massScale <= 0.0D) {
            throw new IllegalArgumentException("massScale must be a finite positive value");
        }
        if (!Double.isFinite(defaultDensity) || defaultDensity <= 0.0D) {
            throw new IllegalArgumentException("defaultDensity must be a finite positive value");
        }
        blockDensities = copyDensities(blockDensities, "blockDensities");
        tagDensities = copyDensities(tagDensities, "tagDensities");
    }

    public static BlockDensityProperties defaults() {
        return new BlockDensityProperties(0.05D, 1000.0D, Map.of(), Map.of());
    }

    private static Map<ResourceLocation, Double> copyDensities(Map<ResourceLocation, Double> densities, String name) {
        Objects.requireNonNull(densities, name);
        Map<ResourceLocation, Double> copy = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, Double> entry : densities.entrySet()) {
            ResourceLocation id = Objects.requireNonNull(entry.getKey(), name + " id");
            double density = Objects.requireNonNull(entry.getValue(), name + " density");
            if (!Double.isFinite(density) || density <= 0.0D) {
                throw new IllegalArgumentException(name + " contains invalid density for " + id + ": " + density);
            }
            copy.put(id, density);
        }
        return Collections.unmodifiableMap(copy);
    }
}
