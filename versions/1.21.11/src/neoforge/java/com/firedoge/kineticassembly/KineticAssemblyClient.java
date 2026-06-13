package com.firedoge.kineticassembly;

import com.firedoge.kineticassembly.platform.neoforge.NeoForgeClientBootstrap;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = KineticAssembly.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = KineticAssembly.MODID, value = Dist.CLIENT)
public class KineticAssemblyClient {
    public KineticAssemblyClient(IEventBus modEventBus, ModContainer container) {
        NeoForgeClientBootstrap.register(modEventBus, container);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        KineticAssembly.LOGGER.debug("KineticAssembly client setup for {}", Minecraft.getInstance().getUser().getName());
    }
}
