package com.firedoge.kineticassembly.config;

public interface PhysicsRuntimeConfig {
    PhysicsRuntimeConfig DEFAULTS = new Defaults();

    default boolean loadNativeOnStartup() {
        return false;
    }

    default String defaultBackend() {
        return "physx";
    }

    default double fixedTimeStep() {
        return 1.0D / 20.0D;
    }

    default int maxSubSteps() {
        return 4;
    }

    default boolean enableGpuDynamics() {
        return false;
    }

    default int activeTerrainMaxScansPerTick() {
        return 1024;
    }

    default int activeTerrainVerticalMargin() {
        return 64;
    }

    default int debugProxyMaxSyncsPerTick() {
        return 256;
    }

    default boolean debugProxySyncTransform() {
        return false;
    }

    default boolean enableAerodynamicsCoupling() {
        return true;
    }

    default double aerodynamicsMaxWindSpeedMetersPerSecond() {
        return 30.0D;
    }

    default double aerodynamicsDragCoefficient() {
        return 1.1D;
    }

    default double aerodynamicsLiftCoefficient() {
        return 0.35D;
    }

    default double aerodynamicsForceScale() {
        return 1.0D;
    }

    default double aerodynamicsMaxForceCoefficient() {
        return 3.0D;
    }

    default double aerodynamicsMaxMomentCoefficient() {
        return 3.0D;
    }

    default double aerodynamicsMaxLinearImpulse() {
        return 25.0D;
    }

    default double aerodynamicsMaxLinearDeltaVelocityPerTick() {
        return 0.5D;
    }

    default double aerodynamicsMaxAngularImpulse() {
        return 25.0D;
    }

    default boolean enableAerodynamicsDebugParticles() {
        return false;
    }

    default int aerodynamicsDebugParticleIntervalTicks() {
        return 5;
    }

    default boolean debugLogging() {
        return false;
    }

    final class Defaults implements PhysicsRuntimeConfig {
        private Defaults() {
        }
    }
}
