package com.firedoge.kineticassembly.platform.neoforge;

import java.nio.file.Path;
import java.util.Objects;

import com.firedoge.kineticassembly.config.NeoForgePhysicsRuntimeConfig;
import com.firedoge.kineticassembly.config.PhysicsRuntimeConfig;
import com.firedoge.kineticassembly.platform.PlatformServices;

import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.IOUtilities;

public enum NeoForgePlatformServices implements PlatformServices.Services {
    INSTANCE;

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public PhysicsRuntimeConfig config() {
        return NeoForgePhysicsRuntimeConfig.INSTANCE;
    }

    @Override
    public void waitForIoCompletion() {
        IOUtilities.waitUntilIOWorkerComplete();
    }

    @Override
    public boolean isModLoaded(String modId) {
        Objects.requireNonNull(modId, "modId");
        return ModList.get().isLoaded(modId);
    }

    @Override
    public void onLivingJump(LivingEntity entity) {
        CommonHooks.onLivingJump(entity);
    }
}
