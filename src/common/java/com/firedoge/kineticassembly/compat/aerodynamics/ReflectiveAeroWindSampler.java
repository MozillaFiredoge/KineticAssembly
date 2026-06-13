package com.firedoge.kineticassembly.compat.aerodynamics;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import com.firedoge.kineticassembly.api.PhysicsVector;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

final class ReflectiveAeroWindSampler {
    private final Method sampleGameplay;
    private final Method hasFlow;
    private final Method effectiveVelocity;
    private final Method turbulenceIntensity;
    private final Method shelterFactor;
    private final Method confidence;
    private final Method sourceLevel;
    private final Method updraftMetersPerSecond;
    private final Method windShearMagnitudePerBlock;
    private final Object serverCoarseOnlyPolicy;

    private ReflectiveAeroWindSampler(
            Method sampleGameplay,
            Method hasFlow,
            Method effectiveVelocity,
            Method turbulenceIntensity,
            Method shelterFactor,
            Method confidence,
            Method sourceLevel,
            Method updraftMetersPerSecond,
            Method windShearMagnitudePerBlock,
            Object serverCoarseOnlyPolicy
    ) {
        this.sampleGameplay = sampleGameplay;
        this.hasFlow = hasFlow;
        this.effectiveVelocity = effectiveVelocity;
        this.turbulenceIntensity = turbulenceIntensity;
        this.shelterFactor = shelterFactor;
        this.confidence = confidence;
        this.sourceLevel = sourceLevel;
        this.updraftMetersPerSecond = updraftMetersPerSecond;
        this.windShearMagnitudePerBlock = windShearMagnitudePerBlock;
        this.serverCoarseOnlyPolicy = serverCoarseOnlyPolicy;
    }

    static ReflectiveAeroWindSampler create() {
        try {
            Class<?> apiClass = Class.forName("com.aerodynamics4mc.api.AeroWindApi");
            Class<?> policyClass = Class.forName("com.aerodynamics4mc.api.SamplePolicy");
            Class<?> sampleClass = Class.forName("com.aerodynamics4mc.api.GameplayWindSample");

            Object serverCoarseOnly = enumConstant(policyClass, "SERVER_COARSE_ONLY");
            Method sampleGameplay = apiClass.getMethod("sampleGameplay", ServerLevel.class, Vec3.class, policyClass);
            Method hasFlow = sampleClass.getMethod("hasFlow");
            Method effectiveVelocity = sampleClass.getMethod("effectiveVelocity");
            Method turbulenceIntensity = sampleClass.getMethod("turbulenceIntensity");
            Method shelterFactor = sampleClass.getMethod("shelterFactor");
            Method confidence = sampleClass.getMethod("confidence");
            Method sourceLevel = sampleClass.getMethod("sourceLevel");
            Method updraftMetersPerSecond = sampleClass.getMethod("updraftMetersPerSecond");
            Method windShearMagnitudePerBlock = sampleClass.getMethod("windShearMagnitudePerBlock");
            return new ReflectiveAeroWindSampler(
                    sampleGameplay,
                    hasFlow,
                    effectiveVelocity,
                    turbulenceIntensity,
                    shelterFactor,
                    confidence,
                    sourceLevel,
                    updraftMetersPerSecond,
                    windShearMagnitudePerBlock,
                    serverCoarseOnly
            );
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Aerodynamics4MC wind sampling API is not available", exception);
        }
    }

    WindSample sample(ServerLevel level, PhysicsVector position) {
        try {
            Object sample = sampleGameplay.invoke(
                    null,
                    level,
                    new Vec3(position.x(), position.y(), position.z()),
                    serverCoarseOnlyPolicy
            );
            if (sample == null || !(Boolean) hasFlow.invoke(sample)) {
                return WindSample.NO_FLOW;
            }
            Vec3 velocity = (Vec3) effectiveVelocity.invoke(sample);
            Object source = sourceLevel.invoke(sample);
            return new WindSample(
                    true,
                    new PhysicsVector(velocity.x, velocity.y, velocity.z),
                    floatValue(sample, turbulenceIntensity),
                    floatValue(sample, shelterFactor),
                    floatValue(sample, confidence),
                    source == null ? "" : source.toString(),
                    floatValue(sample, updraftMetersPerSecond),
                    floatValue(sample, windShearMagnitudePerBlock)
            );
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Failed to access Aerodynamics4MC wind sampling API", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Aerodynamics4MC wind sampling API threw an exception", exception.getCause());
        }
    }

    private static float floatValue(Object sample, Method method) throws IllegalAccessException, InvocationTargetException {
        return (Float) method.invoke(sample);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> enumClass, String name) {
        return Enum.valueOf((Class<? extends Enum>) enumClass.asSubclass(Enum.class), name);
    }

    record WindSample(
            boolean hasFlow,
            PhysicsVector velocity,
            double turbulenceIntensity,
            double shelterFactor,
            double confidence,
            String sourceLevel,
            double updraftMetersPerSecond,
            double windShearMagnitudePerBlock
    ) {
        WindSample {
            sourceLevel = sourceLevel == null ? "" : sourceLevel;
        }

        private static final WindSample NO_FLOW = new WindSample(
                false,
                PhysicsVector.ZERO,
                0.0D,
                0.0D,
                0.0D,
                "",
                0.0D,
                0.0D
        );
    }
}
