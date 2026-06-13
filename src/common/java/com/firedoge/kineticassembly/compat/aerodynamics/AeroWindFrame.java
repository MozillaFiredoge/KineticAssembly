package com.firedoge.kineticassembly.compat.aerodynamics;

import com.firedoge.kineticassembly.api.PhysicsVector;

final class AeroWindFrame {
    private static final double EPSILON = 1.0E-8D;
    private static final PhysicsVector BODY_X = new PhysicsVector(1.0D, 0.0D, 0.0D);
    private static final PhysicsVector BODY_Y = new PhysicsVector(0.0D, 1.0D, 0.0D);
    private static final PhysicsVector BODY_Z = new PhysicsVector(0.0D, 0.0D, 1.0D);

    private final PhysicsVector xAxisBodyLocal;
    private final PhysicsVector yAxisBodyLocal;
    private final PhysicsVector zAxisBodyLocal;
    private final long bucketSignature;

    private AeroWindFrame(PhysicsVector xAxisBodyLocal, PhysicsVector yAxisBodyLocal, PhysicsVector zAxisBodyLocal) {
        this.xAxisBodyLocal = xAxisBodyLocal;
        this.yAxisBodyLocal = yAxisBodyLocal;
        this.zAxisBodyLocal = zAxisBodyLocal;
        this.bucketSignature = bucketSignature(xAxisBodyLocal, yAxisBodyLocal, zAxisBodyLocal);
    }

    static AeroWindFrame fromBodyLocalWind(PhysicsVector bodyLocalWind, PhysicsVector bodyLocalUp) {
        PhysicsVector xAxis = normalize(bodyLocalWind);
        PhysicsVector upHint = lengthSqr(bodyLocalUp) > EPSILON ? normalize(bodyLocalUp) : BODY_Y;
        PhysicsVector yProjected = subtract(upHint, multiply(xAxis, dot(upHint, xAxis)));
        PhysicsVector yAxis = lengthSqr(yProjected) > EPSILON ? normalize(yProjected) : fallbackPerpendicular(xAxis);
        PhysicsVector zAxis = normalize(cross(xAxis, yAxis));
        yAxis = normalize(cross(zAxis, xAxis));
        return new AeroWindFrame(xAxis, yAxis, zAxis);
    }

    PhysicsVector tunnelOffsetToBodyLocal(PhysicsVector tunnelOffset) {
        return add(
                add(multiply(xAxisBodyLocal, tunnelOffset.x()), multiply(yAxisBodyLocal, tunnelOffset.y())),
                multiply(zAxisBodyLocal, tunnelOffset.z())
        );
    }

    PhysicsVector tunnelDirectionToBodyLocal(PhysicsVector tunnelDirection) {
        return tunnelOffsetToBodyLocal(tunnelDirection);
    }

    long bucketSignature() {
        return bucketSignature;
    }

    private static PhysicsVector fallbackPerpendicular(PhysicsVector axis) {
        PhysicsVector candidate = Math.abs(dot(axis, BODY_Y)) < 0.9D ? BODY_Y : BODY_Z;
        PhysicsVector projected = subtract(candidate, multiply(axis, dot(candidate, axis)));
        if (lengthSqr(projected) <= EPSILON) {
            projected = subtract(BODY_X, multiply(axis, dot(BODY_X, axis)));
        }
        return normalize(projected);
    }

    private static long bucketSignature(PhysicsVector xAxis, PhysicsVector yAxis, PhysicsVector zAxis) {
        long hash = 17L;
        hash = mix(hash, quantize(xAxis.x()));
        hash = mix(hash, quantize(xAxis.y()));
        hash = mix(hash, quantize(xAxis.z()));
        hash = mix(hash, quantize(yAxis.x()));
        hash = mix(hash, quantize(yAxis.y()));
        hash = mix(hash, quantize(yAxis.z()));
        hash = mix(hash, quantize(zAxis.x()));
        hash = mix(hash, quantize(zAxis.y()));
        hash = mix(hash, quantize(zAxis.z()));
        return hash;
    }

    private static long mix(long hash, long value) {
        return hash * 31L + value;
    }

    private static long quantize(double value) {
        return Math.round(value * 32.0D);
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

    private static double dot(PhysicsVector first, PhysicsVector second) {
        return first.x() * second.x() + first.y() * second.y() + first.z() * second.z();
    }

    private static double lengthSqr(PhysicsVector vector) {
        return dot(vector, vector);
    }

    private static PhysicsVector normalize(PhysicsVector vector) {
        double length = Math.sqrt(lengthSqr(vector));
        if (length <= EPSILON || !Double.isFinite(length)) {
            return BODY_X;
        }
        return multiply(vector, 1.0D / length);
    }
}
