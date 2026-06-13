package com.firedoge.kineticassembly.compat.aerodynamics;

import java.util.Locale;

import net.minecraft.server.MinecraftServer;

public final class AerodynamicsCompat {
    public static final String MOD_ID = "aerodynamics4mc";
    private static final AssemblyAerodynamicsManager MANAGER = new AssemblyAerodynamicsManager();

    private AerodynamicsCompat() {
    }

    public static void onServerStarting() {
        MANAGER.refreshAvailability();
    }

    public static void tickBeforePhysics(MinecraftServer server) {
        MANAGER.tickBeforePhysics(server);
    }

    public static void close(MinecraftServer server) {
        MANAGER.close(server);
    }

    public static Status status() {
        return MANAGER.status();
    }

    public static void setDebugParticlesEnabled(boolean enabled) {
        MANAGER.setDebugParticlesEnabled(enabled);
    }

    public static boolean debugParticlesEnabled() {
        return MANAGER.debugParticlesEnabled();
    }

    public record Status(
            boolean modLoaded,
            boolean samplerReady,
            int candidateAssemblies,
            int sampledAssemblies,
            int flowSamples,
            boolean forceCouplingReady,
            int profileCount,
            int geometryProfiles,
            int solidCells,
            int profileRebuilds,
            long totalProfileRebuilds,
            int formulaRuns,
            int forceApplications,
            long totalFormulaRuns,
            long totalForceApplications,
            double maxLinearImpulse,
            double maxAngularImpulse,
            double maxForceNewton,
            double maxMomentNewtonMeters,
            double maxRelativeWindMetersPerSecond,
            long lastTickNanos,
            String lastError
    ) {
        public static final Status UNSEEN = new Status(false, false, 0, 0, 0, false, 0, 0, 0, 0, 0L, 0, 0, 0L, 0L, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0L, "");

        public Status {
            lastError = lastError == null ? "" : lastError;
            if (!Double.isFinite(maxLinearImpulse)) {
                maxLinearImpulse = 0.0D;
            }
            if (!Double.isFinite(maxAngularImpulse)) {
                maxAngularImpulse = 0.0D;
            }
            if (!Double.isFinite(maxForceNewton)) {
                maxForceNewton = 0.0D;
            }
            if (!Double.isFinite(maxMomentNewtonMeters)) {
                maxMomentNewtonMeters = 0.0D;
            }
            if (!Double.isFinite(maxRelativeWindMetersPerSecond)) {
                maxRelativeWindMetersPerSecond = 0.0D;
            }
        }

        public double lastTickMillis() {
            return lastTickNanos / 1_000_000.0D;
        }

        public String describe() {
            if (!modLoaded) {
                return "missing";
            }
            if (!samplerReady) {
                return "loaded/unready" + (lastError.isBlank() ? "" : "(" + lastError + ")");
            }
            return String.format(
                    Locale.ROOT,
                    "loaded samples=%d/%d flow=%d profiles=%d geometry=%d rebuilt=%d totalRebuilt=%d formulas=%d applied=%d totals=%d/%d maxImpulse=%.2f/%.2f maxFM=%.2f/%.2f solid=%d maxRel=%.2fm/s force=%s debugParticles=%s %.3fms%s",
                    sampledAssemblies,
                    candidateAssemblies,
                    flowSamples,
                    profileCount,
                    geometryProfiles,
                    profileRebuilds,
                    totalProfileRebuilds,
                    formulaRuns,
                    forceApplications,
                    totalFormulaRuns,
                    totalForceApplications,
                    maxLinearImpulse,
                    maxAngularImpulse,
                    maxForceNewton,
                    maxMomentNewtonMeters,
                    solidCells,
                    maxRelativeWindMetersPerSecond,
                    forceCouplingReady ? "ready" : "off",
                    AerodynamicsCompat.debugParticlesEnabled() ? "on" : "off",
                    lastTickMillis(),
                    lastError.isBlank() ? "" : " error=" + lastError
            );
        }
    }
}
