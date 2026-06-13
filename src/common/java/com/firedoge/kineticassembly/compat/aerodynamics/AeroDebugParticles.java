package com.firedoge.kineticassembly.compat.aerodynamics;

import com.firedoge.kineticassembly.api.PhysicsVector;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3f;

final class AeroDebugParticles {
    private static final double EPSILON = 1.0E-6D;
    private static final double PARTICLE_SPACING = 0.2D;
    private static final double MAX_ARROW_LENGTH = 4.0D;
    private static final DustParticleOptions WIND = new DustParticleOptions(new Vector3f(0.15F, 0.55F, 1.0F), 1.0F);
    private static final DustParticleOptions FORCE = new DustParticleOptions(new Vector3f(1.0F, 0.15F, 0.1F), 1.0F);
    private static final DustParticleOptions MOMENT = new DustParticleOptions(new Vector3f(0.75F, 0.2F, 1.0F), 1.0F);

    private AeroDebugParticles() {
    }

    static void emit(
            ServerLevel level,
            PhysicsVector bodyPosition,
            PhysicsVector windVelocity,
            PhysicsVector forceWorld,
            PhysicsVector momentWorld
    ) {
        PhysicsVector base = add(bodyPosition, new PhysicsVector(0.0D, 1.15D, 0.0D));
        drawVector(level, base, windVelocity, 0.70D, WIND);
        drawVector(level, add(base, new PhysicsVector(0.0D, 0.35D, 0.0D)), forceWorld, 0.35D, FORCE);
        drawVector(level, add(base, new PhysicsVector(0.0D, 0.70D, 0.0D)), momentWorld, 0.35D, MOMENT);
    }

    private static void drawVector(
            ServerLevel level,
            PhysicsVector start,
            PhysicsVector vector,
            double displayScale,
            DustParticleOptions particle
    ) {
        PhysicsVector display = displayVector(vector, displayScale);
        double length = length(display);
        if (length <= EPSILON) {
            return;
        }
        int steps = Math.max(2, Math.min(32, (int) Math.ceil(length / PARTICLE_SPACING)));
        for (int step = 0; step <= steps; step++) {
            double t = (double) step / (double) steps;
            PhysicsVector point = add(start, multiply(display, t));
            level.sendParticles(particle, point.x(), point.y(), point.z(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        PhysicsVector end = add(start, display);
        level.sendParticles(particle, end.x(), end.y(), end.z(), 5, 0.06D, 0.06D, 0.06D, 0.0D);
    }

    private static PhysicsVector displayVector(PhysicsVector vector, double scale) {
        double magnitude = length(vector);
        if (magnitude <= EPSILON || !Double.isFinite(magnitude)) {
            return PhysicsVector.ZERO;
        }
        double displayLength = Math.min(MAX_ARROW_LENGTH, Math.log1p(magnitude) * scale);
        if (displayLength <= EPSILON) {
            return PhysicsVector.ZERO;
        }
        return multiply(vector, displayLength / magnitude);
    }

    private static PhysicsVector add(PhysicsVector first, PhysicsVector second) {
        return new PhysicsVector(first.x() + second.x(), first.y() + second.y(), first.z() + second.z());
    }

    private static PhysicsVector multiply(PhysicsVector vector, double scale) {
        return new PhysicsVector(vector.x() * scale, vector.y() * scale, vector.z() * scale);
    }

    private static double length(PhysicsVector vector) {
        return Math.sqrt(vector.x() * vector.x() + vector.y() * vector.y() + vector.z() * vector.z());
    }
}
