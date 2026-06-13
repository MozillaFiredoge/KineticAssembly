package com.firedoge.kineticassembly.network;

import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class KineticAssemblyNetworking {
    private static final String NETWORK_VERSION = "3";

    private KineticAssemblyNetworking() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NETWORK_VERSION);
        registrar.playToClient(
                ClientboundStartTrackingAssemblyPayload.TYPE,
                ClientboundStartTrackingAssemblyPayload.STREAM_CODEC,
                KineticAssemblyNetworking::handleStartTracking
        );
        registrar.playToClient(
                ClientboundFinalizeAssemblyPayload.TYPE,
                ClientboundFinalizeAssemblyPayload.STREAM_CODEC,
                KineticAssemblyNetworking::handleFinalize
        );
        registrar.playToClient(
                ClientboundAssemblyTransformPayload.TYPE,
                ClientboundAssemblyTransformPayload.STREAM_CODEC,
                KineticAssemblyNetworking::handleTransform
        );
        registrar.playToClient(
                ClientboundStopTrackingAssemblyPayload.TYPE,
                ClientboundStopTrackingAssemblyPayload.STREAM_CODEC,
                KineticAssemblyNetworking::handleStopTracking
        );
    }

    private static void handleStartTracking(ClientboundStartTrackingAssemblyPayload payload, IPayloadContext context) {
        AssemblyContainers.container(context.player().level())
                .ifPresent(container -> container.clientStartTracking(payload.metadata()));
    }

    private static void handleFinalize(ClientboundFinalizeAssemblyPayload payload, IPayloadContext context) {
        AssemblyContainers.container(context.player().level())
                .ifPresent(container -> container.clientFinalizeTracking(payload.id()));
    }

    private static void handleTransform(ClientboundAssemblyTransformPayload payload, IPayloadContext context) {
        AssemblyContainers.container(context.player().level())
                .ifPresent(container -> container.clientUpdateTransform(payload.frame()));
    }

    private static void handleStopTracking(ClientboundStopTrackingAssemblyPayload payload, IPayloadContext context) {
        AssemblyContainers.container(context.player().level())
                .ifPresent(container -> container.clientStopTracking(payload.id()));
    }

}
