package com.firedoge.kineticassembly.platform;

import java.nio.file.Path;
import java.util.Objects;

import com.firedoge.kineticassembly.config.PhysicsRuntimeConfig;

import net.minecraft.world.entity.LivingEntity;

public final class PlatformServices {
    private static volatile Services services = new DefaultServices();

    private PlatformServices() {
    }

    public static void install(Services newServices) {
        services = Objects.requireNonNull(newServices, "newServices");
    }

    public static Services services() {
        return services;
    }

    public interface Services {
        Path configDir();

        PhysicsRuntimeConfig config();

        void waitForIoCompletion();

        boolean isModLoaded(String modId);

        void onLivingJump(LivingEntity entity);
    }

    private static final class DefaultServices implements Services {
        @Override
        public Path configDir() {
            return Path.of("config");
        }

        @Override
        public PhysicsRuntimeConfig config() {
            return PhysicsRuntimeConfig.DEFAULTS;
        }

        @Override
        public void waitForIoCompletion() {
        }

        @Override
        public boolean isModLoaded(String modId) {
            return false;
        }

        @Override
        public void onLivingJump(LivingEntity entity) {
        }
    }
}
