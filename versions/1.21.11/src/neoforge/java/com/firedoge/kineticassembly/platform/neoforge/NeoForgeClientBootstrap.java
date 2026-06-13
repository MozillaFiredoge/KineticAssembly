package com.firedoge.kineticassembly.platform.neoforge;

import com.firedoge.kineticassembly.KineticAssembly;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.util.Unit;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.entity.animation.json.AnimationLoader;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.resources.VanillaClientListeners;
import net.neoforged.neoforge.common.NeoForge;

public final class NeoForgeClientBootstrap {
    private static final Identifier ANIMATION_SHARED_STATE_FALLBACK_ID =
            Identifier.fromNamespaceAndPath(KineticAssembly.MODID, "animation_shared_state_fallback");

    private static boolean registered;

    private NeoForgeClientBootstrap() {
    }

    public static void register(IEventBus modEventBus, ModContainer modContainer) {
        if (registered) {
            return;
        }
        registered = true;
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modEventBus.addListener(NeoForgeClientBootstrap::onAddClientReloadListeners);
        NeoForge.EVENT_BUS.register(new NeoForgeClientEvents());
    }

    private static void onAddClientReloadListeners(AddClientReloadListenersEvent event) {
        if (event.getRegistry().containsKey(ANIMATION_SHARED_STATE_FALLBACK_ID)) {
            return;
        }
        event.addListener(ANIMATION_SHARED_STATE_FALLBACK_ID, new AnimationSharedStateFallbackReloadListener());
        event.addDependency(ANIMATION_SHARED_STATE_FALLBACK_ID, VanillaClientListeners.MODELS);
        KineticAssembly.LOGGER.debug("Registered fallback for missing NeoForge animation reload shared state");
    }

    private static final class AnimationSharedStateFallbackReloadListener implements PreparableReloadListener {
        @Override
        public void prepareSharedState(SharedState sharedState) {
            try {
                sharedState.get(AnimationLoader.STATE_KEY);
            } catch (NullPointerException exception) {
                sharedState.set(AnimationLoader.STATE_KEY, AnimationLoader.PendingAnimations.EMPTY);
                KineticAssembly.LOGGER.debug("Filled missing NeoForge animation reload shared state");
            }
        }

        @Override
        public CompletableFuture<Void> reload(
                SharedState sharedState,
                Executor prepareExecutor,
                PreparationBarrier barrier,
                Executor applyExecutor
        ) {
            return barrier.wait(Unit.INSTANCE).thenAccept(ignored -> {
            });
        }

        @Override
        public String getName() {
            return "KineticAssembly animation shared-state fallback";
        }
    }
}
