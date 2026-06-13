package com.firedoge.kineticassembly.platform.neoforge;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.api.PhysicsBackend;
import com.firedoge.kineticassembly.backend.physx.PhysXBackend;
import com.firedoge.kineticassembly.backend.physx.PhysXNative;
import com.firedoge.kineticassembly.config.PhysXConfig;
import com.firedoge.kineticassembly.nativebridge.NativeException;
import com.firedoge.kineticassembly.network.KineticAssemblyNetworking;
import com.firedoge.kineticassembly.physics.PhysicsManager;
import com.firedoge.kineticassembly.platform.PlatformServices;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

@Mod(KineticAssembly.MODID)
public final class NeoForgeKineticAssembly {
    public NeoForgeKineticAssembly(IEventBus modEventBus, ModContainer modContainer) {
        PlatformServices.install(NeoForgePlatformServices.INSTANCE);
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(KineticAssemblyNetworking::registerPayloads);
        modContainer.registerConfig(ModConfig.Type.COMMON, PhysXConfig.SPEC);
        PhysicsManager.INSTANCE.registerBackend(new PhysXBackend());
        NeoForge.EVENT_BUS.register(new NeoForgeEvents());
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            NeoForgeClientBootstrap.register(modEventBus, modContainer);
        }
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        KineticAssembly.LOGGER.info("Registered physics backends: {}", describeBackends());
        if (PlatformServices.services().config().loadNativeOnStartup()) {
            event.enqueueWork(() -> {
                try {
                    PhysXNative.load();
                    if (PhysXNative.isPhysXLinked()) {
                        KineticAssembly.LOGGER.info("Loaded PhysX native bridge with linked PhysX SDK");
                    } else {
                        KineticAssembly.LOGGER.warn("Loaded PhysX native bridge, but it was built without linked PhysX SDK libraries");
                    }
                } catch (NativeException exception) {
                    KineticAssembly.LOGGER.warn("PhysX native bridge is not available yet", exception);
                }
            });
        }
    }

    private static String describeBackends() {
        StringBuilder builder = new StringBuilder();
        for (PhysicsBackend backend : PhysicsManager.INSTANCE.backends()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(backend.id());
        }
        return builder.length() == 0 ? "<none>" : builder.toString();
    }
}
