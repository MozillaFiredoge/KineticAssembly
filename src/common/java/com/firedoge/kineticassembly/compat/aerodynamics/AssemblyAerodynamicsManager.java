package com.firedoge.kineticassembly.compat.aerodynamics;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.config.PhysicsRuntimeConfig;
import com.firedoge.kineticassembly.mechanics.MechanicsBodySnapshot;
import com.firedoge.kineticassembly.mechanics.MechanicsWorld;
import com.firedoge.kineticassembly.minecraft.assembly.PhysicsAssembly;
import com.firedoge.kineticassembly.minecraft.assembly.ServerAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyId;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyTransform;
import com.firedoge.kineticassembly.platform.PlatformServices;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

final class AssemblyAerodynamicsManager {
    private static final int MAX_SAMPLES_PER_TICK = 16;
    private static final double AIR_DENSITY_KG_M3 = 1.225D;
    private static final double MIN_IMPULSE_MAGNITUDE = 1.0E-7D;
    private static final double SHELTER_FORCE_REDUCTION = 0.65D;
    private static final double TURBULENCE_DRAG_BOOST = 0.18D;
    private static final double TURBULENCE_LIFT_BOOST = 0.08D;
    private static final double SHEAR_DRAG_BOOST = 0.12D;
    private static final double UPDRAFT_LIFT_WEIGHT = 0.35D;

    private final Map<ProfileKey, AssemblyAeroProfile> profiles = new LinkedHashMap<>();
    private ReflectiveAeroWindSampler sampler;
    private boolean samplerAttempted;
    private Boolean debugParticlesOverride;
    private int debugParticleTicker;
    private long totalProfileRebuilds;
    private long totalFormulaRuns;
    private long totalForceApplications;
    private AerodynamicsCompat.Status status = AerodynamicsCompat.Status.UNSEEN;

    void refreshAvailability() {
        closeProfiles();
        totalProfileRebuilds = 0L;
        totalFormulaRuns = 0L;
        totalForceApplications = 0L;
        sampler = null;
        samplerAttempted = false;
        boolean loaded = modLoaded();
        status = emptyStatus(loaded, false, 0L, "");
        if (loaded) {
            KineticAssembly.LOGGER.info("Aerodynamics4MC compatibility hooks enabled");
        } else {
            KineticAssembly.LOGGER.debug("Aerodynamics4MC is not loaded; aerodynamics compatibility hooks are inactive");
        }
    }

    void tickBeforePhysics(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        long start = System.nanoTime();
        boolean loaded = modLoaded();
        if (!loaded) {
            closeProfiles();
            status = emptyStatus(false, false, System.nanoTime() - start, "");
            return;
        }

        ReflectiveAeroWindSampler activeSampler = sampler();
        if (activeSampler == null) {
            closeProfiles();
            status = emptyStatus(true, false, System.nanoTime() - start, status.lastError());
            return;
        }

        int candidates = 0;
        int sampled = 0;
        int flowSamples = 0;
        int profileRebuilds = 0;
        int formulaRuns = 0;
        int forceApplications = 0;
        double maxLinearImpulse = 0.0D;
        double maxAngularImpulse = 0.0D;
        double maxForce = 0.0D;
        double maxMoment = 0.0D;
        boolean forceCouplingEnabled = runtimeConfig().enableAerodynamicsCoupling();
        boolean emitDebugParticles = shouldEmitDebugParticles();
        String error = "";
        boolean completed = false;
        Set<ProfileKey> seenProfiles = new LinkedHashSet<>();
        try {
            for (ServerLevel level : server.getAllLevels()) {
                Optional<MechanicsWorld> maybeWorld = KineticAssembly.api().existingWorld(level);
                Optional<ServerAssemblyContainer> maybeContainer = AssemblyContainers.server(level);
                if (maybeWorld.isEmpty() || maybeContainer.isEmpty()) {
                    continue;
                }
                MechanicsWorld world = maybeWorld.get();
                for (PhysicsAssembly assembly : maybeContainer.get().assemblies()) {
                    Optional<MechanicsBodySnapshot> maybeBody = world.snapshot(assembly.bodyId());
                    if (maybeBody.isEmpty() || maybeBody.get().closed()) {
                        continue;
                    }
                    MechanicsBodySnapshot body = maybeBody.get();
                    ProfileKey profileKey = new ProfileKey(level.dimension(), assembly.id());
                    seenProfiles.add(profileKey);
                    candidates++;
                    if (sampled >= MAX_SAMPLES_PER_TICK) {
                        continue;
                    }
                    ReflectiveAeroWindSampler.WindSample sample = activeSampler.sample(level, body.pose().position());
                    sampled++;
                    if (sample.hasFlow()) {
                        if (sample.sourceLevel().equalsIgnoreCase("L2")) {
                            continue;
                        }
                        flowSamples++;
                        AssemblyAeroProfile profile = profiles.computeIfAbsent(profileKey, ignored -> new AssemblyAeroProfile());
                        AssemblyAeroProfile.UpdateResult result = profile.refreshGeometryProfile(assembly, body, sample.velocity());
                        PhysicsVector debugForceWorld = PhysicsVector.ZERO;
                        PhysicsVector debugMomentWorld = PhysicsVector.ZERO;
                        if (result.rebuilt()) {
                            profileRebuilds++;
                            totalProfileRebuilds++;
                        }
                        if (forceCouplingEnabled && result.eligible() && profile.hasGeometryProfile()) {
                            AppliedImpulse impulse = estimateAndApply(world, body, profile, sample);
                            formulaRuns++;
                            totalFormulaRuns++;
                            if (impulse.applied()) {
                                forceApplications++;
                                totalForceApplications++;
                                maxLinearImpulse = Math.max(maxLinearImpulse, impulse.linearMagnitude());
                                maxAngularImpulse = Math.max(maxAngularImpulse, impulse.angularMagnitude());
                                maxForce = Math.max(maxForce, impulse.forceMagnitude());
                                maxMoment = Math.max(maxMoment, impulse.momentMagnitude());
                            }
                            debugForceWorld = impulse.forceWorld();
                            debugMomentWorld = impulse.momentWorld();
                        }
                        if (emitDebugParticles) {
                            AeroDebugParticles.emit(level, body.pose().position(), sample.velocity(), debugForceWorld, debugMomentWorld);
                        }
                    }
                }
            }
            completed = true;
        } catch (RuntimeException exception) {
            error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            KineticAssembly.LOGGER.warn("Aerodynamics4MC compatibility tick failed", exception);
        }
        if (completed) {
            removeStaleProfiles(seenProfiles);
        }
        ProfileStats profileStats = profileStats();
        status = new AerodynamicsCompat.Status(
                true,
                true,
                candidates,
                sampled,
                flowSamples,
                forceCouplingEnabled,
                profileStats.profileCount(),
                profileStats.geometryProfiles(),
                profileStats.solidCells(),
                profileRebuilds,
                totalProfileRebuilds,
                formulaRuns,
                forceApplications,
                totalFormulaRuns,
                totalForceApplications,
                maxLinearImpulse,
                maxAngularImpulse,
                maxForce,
                maxMoment,
                profileStats.maxRelativeWindSpeed(),
                System.nanoTime() - start,
                error
        );
    }

    void close(MinecraftServer server) {
        closeProfiles();
        totalProfileRebuilds = 0L;
        totalFormulaRuns = 0L;
        totalForceApplications = 0L;
        sampler = null;
        samplerAttempted = false;
        status = emptyStatus(modLoaded(), false, 0L, "");
    }

    AerodynamicsCompat.Status status() {
        return status;
    }

    void setDebugParticlesEnabled(boolean enabled) {
        debugParticlesOverride = enabled;
        debugParticleTicker = 0;
    }

    boolean debugParticlesEnabled() {
        return debugParticlesOverride == null
                ? runtimeConfig().enableAerodynamicsDebugParticles()
                : debugParticlesOverride;
    }

    private ReflectiveAeroWindSampler sampler() {
        if (sampler != null) {
            return sampler;
        }
        if (samplerAttempted) {
            return null;
        }
        samplerAttempted = true;
        try {
            sampler = ReflectiveAeroWindSampler.create();
            return sampler;
        } catch (RuntimeException exception) {
            String error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            status = emptyStatus(true, false, 0L, error);
            KineticAssembly.LOGGER.warn("Failed to initialize Aerodynamics4MC compatibility hooks", exception);
            return null;
        }
    }

    private void removeStaleProfiles(Set<ProfileKey> liveProfiles) {
        profiles.entrySet().removeIf(entry -> {
            if (liveProfiles.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().close();
            return true;
        });
    }

    private ProfileStats profileStats() {
        int profileCount = 0;
        int geometryProfiles = 0;
        int solidCells = 0;
        double maxRelativeWindSpeed = 0.0D;
        for (AssemblyAeroProfile profile : profiles.values()) {
            profileCount++;
            if (profile.solidCells() > 0) {
                geometryProfiles++;
                solidCells += profile.solidCells();
            }
            maxRelativeWindSpeed = Math.max(maxRelativeWindSpeed, profile.lastRelativeWindSpeed());
        }
        return new ProfileStats(profileCount, geometryProfiles, solidCells, maxRelativeWindSpeed);
    }

    private void closeProfiles() {
        for (AssemblyAeroProfile profile : profiles.values()) {
            profile.close();
        }
        profiles.clear();
    }

    private static AppliedImpulse estimateAndApply(
            MechanicsWorld world,
            MechanicsBodySnapshot body,
            AssemblyAeroProfile profile,
            ReflectiveAeroWindSampler.WindSample sample
    ) {
        double windSpeed = Math.min(maxFormulaWindSpeedMetersPerSecond(), profile.lastRelativeWindSpeed());
        if (windSpeed < MIN_IMPULSE_MAGNITUDE) {
            return AppliedImpulse.NONE;
        }
        BodyForceMoment bodyForceMoment = empiricalBodyForceMoment(body, profile, sample, windSpeed);
        PhysicsVector forceBodyLocal = bodyForceMoment.force();
        PhysicsVector momentBodyLocal = bodyForceMoment.moment();
        AssemblyTransform transform = AssemblyTransform.from(body);
        PhysicsVector forceWorld = finiteOrZero(transform.localDirectionToWorld(forceBodyLocal));
        PhysicsVector momentWorld = finiteOrZero(transform.localDirectionToWorld(momentBodyLocal));
        PhysicsRuntimeConfig runtimeConfig = runtimeConfig();
        double physicsDt = Math.max(0.0D, runtimeConfig.fixedTimeStep());
        PhysicsVector linearImpulse = clampMagnitude(
                multiply(forceWorld, physicsDt),
                maxLinearImpulse(body.mass())
        );
        PhysicsVector angularImpulse = clampMagnitude(
                multiply(momentWorld, physicsDt),
                runtimeConfig.aerodynamicsMaxAngularImpulse()
        );
        double linearMagnitude = length(linearImpulse);
        double angularMagnitude = length(angularImpulse);
        boolean applied = false;
        if (linearMagnitude > MIN_IMPULSE_MAGNITUDE) {
            applied |= world.applyLinearImpulse(body.id(), linearImpulse);
        }
        if (angularMagnitude > MIN_IMPULSE_MAGNITUDE) {
            applied |= world.applyAngularImpulse(body.id(), angularImpulse);
        }
        return new AppliedImpulse(
                applied,
                linearMagnitude,
                angularMagnitude,
                length(forceWorld),
                length(momentWorld),
                forceWorld,
                momentWorld
        );
    }

    private static BodyForceMoment empiricalBodyForceMoment(
            MechanicsBodySnapshot body,
            AssemblyAeroProfile profile,
            ReflectiveAeroWindSampler.WindSample sample,
            double windSpeedMetersPerSecond
    ) {
        double projectedArea = positiveOrZero(profile.projectedAreaMeters2());
        double characteristicLength = positiveOrZero(profile.characteristicLengthMeters());
        if (projectedArea <= 0.0D || characteristicLength <= 0.0D) {
            return BodyForceMoment.ZERO;
        }

        double confidence = clamp01(sample.confidence());
        if (confidence <= 0.0D) {
            return BodyForceMoment.ZERO;
        }
        double shelter = clamp01(sample.shelterFactor());
        double turbulence = positiveOrZero(sample.turbulenceIntensity());
        double shear = positiveOrZero(sample.windShearMagnitudePerBlock());
        PhysicsRuntimeConfig runtimeConfig = runtimeConfig();
        double outputScale = Math.max(0.0D, runtimeConfig.aerodynamicsForceScale());
        if (outputScale <= 0.0D) {
            return BodyForceMoment.ZERO;
        }

        double dynamicPressure = 0.5D
                * AIR_DENSITY_KG_M3
                * windSpeedMetersPerSecond
                * windSpeedMetersPerSecond;
        double shelterDerate = 1.0D - shelter * SHELTER_FORCE_REDUCTION;
        double shearBoost = 1.0D + Math.min(1.0D, shear * characteristicLength) * SHEAR_DRAG_BOOST;
        double maxForceCoefficient = Math.max(0.0D, runtimeConfig.aerodynamicsMaxForceCoefficient());

        double cd = Math.max(0.0D, runtimeConfig.aerodynamicsDragCoefficient())
                * (1.0D + turbulence * TURBULENCE_DRAG_BOOST)
                * shearBoost;
        cd = Math.min(cd, maxForceCoefficient);

        AssemblyTransform transform = AssemblyTransform.from(body);
        PhysicsVector relativeWindWorld = subtract(sample.velocity(), body.linearVelocity());
        PhysicsVector relativeWindBodyLocal = finiteOrZero(transform.worldDirectionToLocal(relativeWindWorld));
        double windSpeedInv = 1.0D / Math.max(windSpeedMetersPerSecond, 1.0E-6D);
        double angleLiftProxy = clamp(-relativeWindBodyLocal.y() * windSpeedInv, 0.0D, 1.0D);
        double updraftLiftProxy = clamp(sample.updraftMetersPerSecond() * windSpeedInv, 0.0D, 1.0D);
        double liftProxy = clamp(angleLiftProxy + updraftLiftProxy * UPDRAFT_LIFT_WEIGHT, 0.0D, 1.0D);
        double cl = Math.max(0.0D, runtimeConfig.aerodynamicsLiftCoefficient())
                * liftProxy
                * (1.0D + turbulence * TURBULENCE_LIFT_BOOST);
        cl = Math.min(cl, maxForceCoefficient);

        double forceScale = dynamicPressure * projectedArea * confidence * shelterDerate * outputScale;
        double dragMagnitude = forceScale * cd;
        double liftMagnitude = forceScale * cl;
        AeroWindFrame frame = profile.windFrame();
        PhysicsVector drag = frame.tunnelDirectionToBodyLocal(new PhysicsVector(dragMagnitude, 0.0D, 0.0D));
        PhysicsVector lift = frame.tunnelDirectionToBodyLocal(new PhysicsVector(0.0D, liftMagnitude, 0.0D));
        PhysicsVector force = finiteOrZero(add(drag, lift));

        double maxMomentCoefficient = Math.max(0.0D, runtimeConfig.aerodynamicsMaxMomentCoefficient());
        double momentLimit = dynamicPressure * projectedArea * characteristicLength * maxMomentCoefficient;
        PhysicsVector moment = cross(profile.referenceBodyLocal(), force);
        moment = clampMagnitude(finiteOrZero(moment), momentLimit);
        return new BodyForceMoment(force, moment);
    }

    private boolean shouldEmitDebugParticles() {
        if (!debugParticlesEnabled()) {
            return false;
        }
        int interval = Math.max(1, runtimeConfig().aerodynamicsDebugParticleIntervalTicks());
        debugParticleTicker = (debugParticleTicker + 1) % interval;
        return debugParticleTicker == 0;
    }

    private static double maxFormulaWindSpeedMetersPerSecond() {
        double configured = runtimeConfig().aerodynamicsMaxWindSpeedMetersPerSecond();
        if (!Double.isFinite(configured) || configured <= 0.0D) {
            return 0.0D;
        }
        return configured;
    }

    private static double maxLinearImpulse(float mass) {
        PhysicsRuntimeConfig runtimeConfig = runtimeConfig();
        double configured = Math.max(0.0D, runtimeConfig.aerodynamicsMaxLinearImpulse());
        double maxDeltaVelocity = Math.max(0.0D, runtimeConfig.aerodynamicsMaxLinearDeltaVelocityPerTick());
        if (!Float.isFinite(mass) || mass <= 0.0F || maxDeltaVelocity <= 0.0D) {
            return configured;
        }
        return Math.min(configured, mass * maxDeltaVelocity);
    }

    private static PhysicsVector clampMagnitude(PhysicsVector vector, double maxMagnitude) {
        if (!Double.isFinite(maxMagnitude) || maxMagnitude <= 0.0D) {
            return PhysicsVector.ZERO;
        }
        double magnitude = length(vector);
        if (magnitude <= maxMagnitude || magnitude <= MIN_IMPULSE_MAGNITUDE) {
            return vector;
        }
        return multiply(vector, maxMagnitude / magnitude);
    }

    private static double positiveOrZero(double value) {
        return Double.isFinite(value) && value > 0.0D ? value : 0.0D;
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0D, 1.0D);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static PhysicsVector finiteOrZero(PhysicsVector vector) {
        if (!Double.isFinite(vector.x()) || !Double.isFinite(vector.y()) || !Double.isFinite(vector.z())) {
            return PhysicsVector.ZERO;
        }
        return vector;
    }

    private static PhysicsVector add(PhysicsVector first, PhysicsVector second) {
        return new PhysicsVector(first.x() + second.x(), first.y() + second.y(), first.z() + second.z());
    }

    private static PhysicsVector subtract(PhysicsVector first, PhysicsVector second) {
        return new PhysicsVector(first.x() - second.x(), first.y() - second.y(), first.z() - second.z());
    }

    private static PhysicsVector multiply(PhysicsVector vector, double scale) {
        return new PhysicsVector(vector.x() * scale, vector.y() * scale, vector.z() * scale);
    }

    private static PhysicsVector cross(PhysicsVector first, PhysicsVector second) {
        return new PhysicsVector(
                first.y() * second.z() - first.z() * second.y(),
                first.z() * second.x() - first.x() * second.z(),
                first.x() * second.y() - first.y() * second.x()
        );
    }

    private static double length(PhysicsVector vector) {
        return Math.sqrt(vector.x() * vector.x() + vector.y() * vector.y() + vector.z() * vector.z());
    }

    private static AerodynamicsCompat.Status emptyStatus(
            boolean modLoaded,
            boolean samplerReady,
            long lastTickNanos,
            String lastError
    ) {
        return new AerodynamicsCompat.Status(
                modLoaded,
                samplerReady,
                0,
                0,
                0,
                false,
                0,
                0,
                0,
                0,
                0L,
                0,
                0,
                0L,
                0L,
                0.0D,
                0.0D,
                0.0D,
                0.0D,
                0.0D,
                lastTickNanos,
                lastError
        );
    }

    private static boolean modLoaded() {
        return PlatformServices.services().isModLoaded(AerodynamicsCompat.MOD_ID);
    }

    private static PhysicsRuntimeConfig runtimeConfig() {
        return PlatformServices.services().config();
    }

    private record ProfileKey(ResourceKey<Level> levelKey, AssemblyId assemblyId) {
        private ProfileKey {
            Objects.requireNonNull(levelKey, "levelKey");
            Objects.requireNonNull(assemblyId, "assemblyId");
        }
    }

    private record ProfileStats(
            int profileCount,
            int geometryProfiles,
            int solidCells,
            double maxRelativeWindSpeed
    ) {
    }

    private record BodyForceMoment(PhysicsVector force, PhysicsVector moment) {
        private static final BodyForceMoment ZERO = new BodyForceMoment(PhysicsVector.ZERO, PhysicsVector.ZERO);
    }

    private record AppliedImpulse(
            boolean applied,
            double linearMagnitude,
            double angularMagnitude,
            double forceMagnitude,
            double momentMagnitude,
            PhysicsVector forceWorld,
            PhysicsVector momentWorld
    ) {
        private static final AppliedImpulse NONE = new AppliedImpulse(
                false,
                0.0D,
                0.0D,
                0.0D,
                0.0D,
                PhysicsVector.ZERO,
                PhysicsVector.ZERO
        );
    }
}
