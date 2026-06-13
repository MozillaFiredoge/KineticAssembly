package com.firedoge.kineticassembly.minecraft.material;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.platform.PlatformServices;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class BlockDensityResolver {
    public static final BlockDensityResolver INSTANCE = new BlockDensityResolver();
    private static final String DEFAULT_RESOURCE = "/kinetic_assembly/default_block_density.json";
    private static final String CONFIG_FILE = "kinetic_assembly/block_density.json";

    private volatile BlockDensityProperties properties = BlockDensityProperties.defaults();

    private BlockDensityResolver() {
    }

    public synchronized void reload() {
        BlockDensityProperties loaded = loadProperties();
        properties = loaded;
        KineticAssembly.LOGGER.info(
                "Loaded block density properties: massScale={}, defaultDensity={}, blocks={}, tags={}",
                loaded.massScale(),
                loaded.defaultDensity(),
                loaded.blockDensities().size(),
                loaded.tagDensities().size()
        );
    }

    public BlockDensityProperties properties() {
        return properties;
    }

    public double density(BlockState state) {
        Objects.requireNonNull(state, "state");
        BlockDensityProperties current = properties;
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        Double blockDensity = current.blockDensities().get(blockId);
        if (blockDensity != null) {
            return blockDensity;
        }

        for (Map.Entry<ResourceLocation, Double> entry : current.tagDensities().entrySet()) {
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, entry.getKey());
            if (state.is(tag)) {
                return entry.getValue();
            }
        }
        return current.defaultDensity();
    }

    public double scaledMass(BlockState state, double volume) {
        if (!Double.isFinite(volume) || volume <= 0.0D) {
            return 0.0D;
        }
        return density(state) * properties.massScale() * volume;
    }

    private static BlockDensityProperties loadProperties() {
        BlockDensityProperties fallback = BlockDensityProperties.defaults();
        String defaultJson = readDefaultJson();
        BlockDensityProperties builtIn = parseProperties(defaultJson, fallback, "built-in defaults");
        Path configPath = PlatformServices.services().configDir().resolve(CONFIG_FILE);
        ensureConfigExists(configPath, defaultJson);
        if (!Files.isRegularFile(configPath)) {
            return builtIn;
        }

        try {
            String configJson = Files.readString(configPath, StandardCharsets.UTF_8);
            return parseProperties(configJson, builtIn, configPath.toString());
        } catch (IOException | RuntimeException exception) {
            KineticAssembly.LOGGER.warn("Failed to load block density config {}; using built-in defaults", configPath, exception);
            return builtIn;
        }
    }

    private static String readDefaultJson() {
        try (InputStream stream = BlockDensityResolver.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (stream == null) {
                KineticAssembly.LOGGER.warn("Missing built-in block density resource {}; using fallback values", DEFAULT_RESOURCE);
                return "{}";
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            KineticAssembly.LOGGER.warn("Failed to read built-in block density resource {}; using fallback values", DEFAULT_RESOURCE, exception);
            return "{}";
        }
    }

    private static void ensureConfigExists(Path configPath, String defaultJson) {
        if (Files.exists(configPath)) {
            return;
        }
        try {
            Path parent = configPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(configPath, defaultJson, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            KineticAssembly.LOGGER.warn("Failed to create default block density config {}", configPath, exception);
        }
    }

    private static BlockDensityProperties parseProperties(String json, BlockDensityProperties base, String source) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("root must be an object");
            }
            JsonObject root = parsed.getAsJsonObject();
            double massScale = readPositiveDouble(root, "mass_scale", base.massScale(), source);
            double defaultDensity = readPositiveDouble(root, "default_density", base.defaultDensity(), source);
            Map<ResourceLocation, Double> blocks = readDensityMap(root, "blocks", base.blockDensities(), source);
            Map<ResourceLocation, Double> tags = readDensityMap(root, "tags", base.tagDensities(), source);
            return new BlockDensityProperties(massScale, defaultDensity, blocks, tags);
        } catch (RuntimeException exception) {
            KineticAssembly.LOGGER.warn("Failed to parse block density properties from {}; using previous values", source, exception);
            return base;
        }
    }

    private static double readPositiveDouble(JsonObject root, String key, double fallback, String source) {
        JsonElement element = root.get(key);
        if (element == null) {
            return fallback;
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value) || value <= 0.0D) {
            KineticAssembly.LOGGER.warn("Ignoring invalid {}={} in block density properties from {}", key, value, source);
            return fallback;
        }
        return value;
    }

    private static Map<ResourceLocation, Double> readDensityMap(
            JsonObject root,
            String key,
            Map<ResourceLocation, Double> base,
            String source
    ) {
        Map<ResourceLocation, Double> result = new LinkedHashMap<>(base);
        JsonElement element = root.get(key);
        if (element == null) {
            return result;
        }
        if (!element.isJsonObject()) {
            KineticAssembly.LOGGER.warn("Ignoring non-object {} in block density properties from {}", key, source);
            return result;
        }
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
            if (id == null) {
                KineticAssembly.LOGGER.warn("Ignoring invalid {} id {} in block density properties from {}", key, entry.getKey(), source);
                continue;
            }
            double density = readDensityValue(entry.getValue(), key + "." + entry.getKey(), source);
            if (!Double.isFinite(density) || density <= 0.0D) {
                continue;
            }
            result.put(id, density);
        }
        return result;
    }

    private static double readDensityValue(JsonElement element, String key, String source) {
        double value;
        if (element.isJsonObject() && element.getAsJsonObject().has("density")) {
            value = element.getAsJsonObject().get("density").getAsDouble();
        } else {
            value = element.getAsDouble();
        }
        if (!Double.isFinite(value) || value <= 0.0D) {
            KineticAssembly.LOGGER.warn("Ignoring invalid density {}={} in block density properties from {}", key, value, source);
            return Double.NaN;
        }
        return value;
    }
}
