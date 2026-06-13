package com.firedoge.kineticassembly.config;

public enum NeoForgePhysicsRuntimeConfig implements PhysicsRuntimeConfig {
    INSTANCE;

    @Override
    public boolean loadNativeOnStartup() {
        return PhysXConfig.LOAD_NATIVE_ON_STARTUP.getAsBoolean();
    }

    @Override
    public String defaultBackend() {
        return PhysXConfig.DEFAULT_BACKEND.get();
    }

    @Override
    public double fixedTimeStep() {
        return PhysXConfig.FIXED_TIME_STEP.get();
    }

    @Override
    public int maxSubSteps() {
        return PhysXConfig.MAX_SUB_STEPS.get();
    }

    @Override
    public boolean enableGpuDynamics() {
        return PhysXConfig.ENABLE_GPU_DYNAMICS.getAsBoolean();
    }

    @Override
    public int activeTerrainMaxScansPerTick() {
        return PhysXConfig.ACTIVE_TERRAIN_MAX_SCANS_PER_TICK.get();
    }

    @Override
    public int activeTerrainVerticalMargin() {
        return PhysXConfig.ACTIVE_TERRAIN_VERTICAL_MARGIN.get();
    }

    @Override
    public int debugProxyMaxSyncsPerTick() {
        return PhysXConfig.DEBUG_PROXY_MAX_SYNCS_PER_TICK.get();
    }

    @Override
    public boolean debugProxySyncTransform() {
        return PhysXConfig.DEBUG_PROXY_SYNC_TRANSFORM.getAsBoolean();
    }

    @Override
    public boolean enableAerodynamicsCoupling() {
        return PhysXConfig.ENABLE_AERODYNAMICS_COUPLING.getAsBoolean();
    }

    @Override
    public double aerodynamicsMaxWindSpeedMetersPerSecond() {
        return PhysXConfig.AERODYNAMICS_MAX_WIND_SPEED_METERS_PER_SECOND.get();
    }

    @Override
    public double aerodynamicsDragCoefficient() {
        return PhysXConfig.AERODYNAMICS_DRAG_COEFFICIENT.get();
    }

    @Override
    public double aerodynamicsLiftCoefficient() {
        return PhysXConfig.AERODYNAMICS_LIFT_COEFFICIENT.get();
    }

    @Override
    public double aerodynamicsForceScale() {
        return PhysXConfig.AERODYNAMICS_FORCE_SCALE.get();
    }

    @Override
    public double aerodynamicsMaxForceCoefficient() {
        return PhysXConfig.AERODYNAMICS_MAX_FORCE_COEFFICIENT.get();
    }

    @Override
    public double aerodynamicsMaxMomentCoefficient() {
        return PhysXConfig.AERODYNAMICS_MAX_MOMENT_COEFFICIENT.get();
    }

    @Override
    public double aerodynamicsMaxLinearImpulse() {
        return PhysXConfig.AERODYNAMICS_MAX_LINEAR_IMPULSE.get();
    }

    @Override
    public double aerodynamicsMaxLinearDeltaVelocityPerTick() {
        return PhysXConfig.AERODYNAMICS_MAX_LINEAR_DELTA_VELOCITY_PER_TICK.get();
    }

    @Override
    public double aerodynamicsMaxAngularImpulse() {
        return PhysXConfig.AERODYNAMICS_MAX_ANGULAR_IMPULSE.get();
    }

    @Override
    public boolean enableAerodynamicsDebugParticles() {
        return PhysXConfig.ENABLE_AERODYNAMICS_DEBUG_PARTICLES.getAsBoolean();
    }

    @Override
    public int aerodynamicsDebugParticleIntervalTicks() {
        return PhysXConfig.AERODYNAMICS_DEBUG_PARTICLE_INTERVAL_TICKS.get();
    }

    @Override
    public boolean debugLogging() {
        return PhysXConfig.DEBUG_LOGGING.getAsBoolean();
    }
}
