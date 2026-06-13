package com.firedoge.kineticassembly.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class PhysXConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue LOAD_NATIVE_ON_STARTUP = BUILDER
            .comment("Attempt to load the native PhysX bridge during common setup.")
            .define("loadNativeOnStartup", false);

    public static final ModConfigSpec.ConfigValue<String> DEFAULT_BACKEND = BUILDER
            .comment("Default physics backend id.")
            .define("defaultBackend", "physx");

    public static final ModConfigSpec.DoubleValue FIXED_TIME_STEP = BUILDER
            .comment("Physics fixed time step in seconds.")
            .defineInRange("fixedTimeStep", 1.0D / 20.0D, 1.0D / 240.0D, 1.0D);

    public static final ModConfigSpec.IntValue MAX_SUB_STEPS = BUILDER
            .comment("Maximum physics substeps per game tick.")
            .defineInRange("maxSubSteps", 4, 1, 32);

    public static final ModConfigSpec.BooleanValue ENABLE_GPU_DYNAMICS = BUILDER
            .comment("Request PhysX GPU rigid body dynamics for newly created scenes. Requires a PhysX GPU build, NVIDIA driver, and PhysXGpu runtime library.")
            .define("enableGpuDynamics", false);

    public static final ModConfigSpec.IntValue ACTIVE_TERRAIN_MAX_SCANS_PER_TICK = BUILDER
            .comment("Maximum dynamic boxes scanned per server tick for active-object terrain queueing. Use 0 to disable active terrain queueing.")
            .defineInRange("activeTerrainMaxScansPerTick", 1024, 0, 100000);

    public static final ModConfigSpec.IntValue ACTIVE_TERRAIN_VERTICAL_MARGIN = BUILDER
            .comment("Vertical margin outside the Minecraft build height where dynamic boxes still request terrain colliders.")
            .defineInRange("activeTerrainVerticalMargin", 64, 0, 4096);

    public static final ModConfigSpec.IntValue DEBUG_PROXY_MAX_SYNCS_PER_TICK = BUILDER
            .comment("Maximum BlockDisplay debug proxy pose syncs per server tick. Use 0 to disable proxy pose sync.")
            .defineInRange("debugProxyMaxSyncsPerTick", 256, 0, 20000);

    public static final ModConfigSpec.BooleanValue DEBUG_PROXY_SYNC_TRANSFORM = BUILDER
            .comment("Synchronize BlockDisplay transformation every proxy sync. Disable this to sync position only and reduce SynchedEntityData/network churn.")
            .define("debugProxySyncTransform", false);

    public static final ModConfigSpec.BooleanValue ENABLE_AERODYNAMICS_COUPLING = BUILDER
            .comment("When Aerodynamics4MC is loaded, sample gameplay wind around physics assemblies and apply empirical aerodynamic impulses.")
            .define("enableAerodynamicsCoupling", true);

    public static final ModConfigSpec.DoubleValue AERODYNAMICS_MAX_WIND_SPEED_METERS_PER_SECOND = BUILDER
            .comment("Maximum relative wind speed used by the empirical aerodynamics model, in meters per second.")
            .defineInRange("aerodynamicsMaxWindSpeedMetersPerSecond", 30.0D, 0.0D, 1000.0D);

    public static final ModConfigSpec.DoubleValue AERODYNAMICS_DRAG_COEFFICIENT = BUILDER
            .comment("Base drag coefficient Cd used by the empirical aerodynamics model before gameplay wind modifiers.")
            .defineInRange("aerodynamicsDragCoefficient", 1.1D, 0.0D, 100.0D);

    public static final ModConfigSpec.DoubleValue AERODYNAMICS_LIFT_COEFFICIENT = BUILDER
            .comment("Base lift coefficient Cl used by the empirical aerodynamics model before angle/updraft and gameplay wind modifiers.")
            .defineInRange("aerodynamicsLiftCoefficient", 0.35D, 0.0D, 100.0D);

    public static final ModConfigSpec.DoubleValue AERODYNAMICS_FORCE_SCALE = BUILDER
            .comment("Multiplier applied to empirical aerodynamic force and offset moment before impulse limiting. Use this for gameplay calibration.")
            .defineInRange("aerodynamicsForceScale", 1.0D, 0.0D, 1000.0D);

    public static final ModConfigSpec.DoubleValue AERODYNAMICS_MAX_FORCE_COEFFICIENT = BUILDER
            .comment("Maximum Cd/Cl value allowed after empirical gameplay wind modifiers.")
            .defineInRange("aerodynamicsMaxForceCoefficient", 3.0D, 0.0D, 100.0D);

    public static final ModConfigSpec.DoubleValue AERODYNAMICS_MAX_MOMENT_COEFFICIENT = BUILDER
            .comment("Maximum aerodynamic moment coefficient used to bound empirical moment by dynamic pressure, projected area, and characteristic length.")
            .defineInRange("aerodynamicsMaxMomentCoefficient", 3.0D, 0.0D, 100.0D);

    public static final ModConfigSpec.DoubleValue AERODYNAMICS_MAX_LINEAR_IMPULSE = BUILDER
            .comment("Maximum aerodynamic linear impulse magnitude applied to one body in one physics tick, in N*s.")
            .defineInRange("aerodynamicsMaxLinearImpulse", 25.0D, 0.0D, 1.0E6D);

    public static final ModConfigSpec.DoubleValue AERODYNAMICS_MAX_LINEAR_DELTA_VELOCITY_PER_TICK = BUILDER
            .comment("Maximum linear velocity change from aerodynamic force per body per physics tick, in meters per second. The effective impulse cap is also limited by mass times this value.")
            .defineInRange("aerodynamicsMaxLinearDeltaVelocityPerTick", 0.5D, 0.0D, 1000.0D);

    public static final ModConfigSpec.DoubleValue AERODYNAMICS_MAX_ANGULAR_IMPULSE = BUILDER
            .comment("Maximum aerodynamic angular impulse magnitude applied to one body in one physics tick, in N*m*s.")
            .defineInRange("aerodynamicsMaxAngularImpulse", 25.0D, 0.0D, 1.0E6D);

    public static final ModConfigSpec.BooleanValue ENABLE_AERODYNAMICS_DEBUG_PARTICLES = BUILDER
            .comment("Show Aerodynamics4MC compatibility debug particles for sampled wind, force, and moment vectors.")
            .define("enableAerodynamicsDebugParticles", false);

    public static final ModConfigSpec.IntValue AERODYNAMICS_DEBUG_PARTICLE_INTERVAL_TICKS = BUILDER
            .comment("Server tick interval for aerodynamics debug particles.")
            .defineInRange("aerodynamicsDebugParticleIntervalTicks", 5, 1, 200);

    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING = BUILDER
            .comment("Enable verbose physics debug logging.")
            .define("debugLogging", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private PhysXConfig() {
    }
}
