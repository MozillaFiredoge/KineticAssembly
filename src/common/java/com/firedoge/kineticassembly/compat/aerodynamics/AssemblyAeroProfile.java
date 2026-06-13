package com.firedoge.kineticassembly.compat.aerodynamics;

import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.mechanics.MechanicsBodySnapshot;
import com.firedoge.kineticassembly.minecraft.assembly.PhysicsAssembly;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyTransform;

final class AssemblyAeroProfile implements AutoCloseable {
    private static final double MIN_RELATIVE_WIND_SPEED_MPS = 0.05D;

    private long profileSignature = Long.MIN_VALUE;
    private int solidCells;
    private double dxMeters;
    private double projectedAreaMeters2;
    private double characteristicLengthMeters;
    private double lastRelativeWindSpeed;
    private AeroWindFrame windFrame;
    private PhysicsVector referenceBodyLocal = PhysicsVector.ZERO;

    UpdateResult refreshGeometryProfile(
            PhysicsAssembly assembly,
            MechanicsBodySnapshot body,
            PhysicsVector windVelocity
    ) {
        PhysicsVector relativeWindWorld = subtract(windVelocity, body.linearVelocity());
        double relativeWindSpeed = length(relativeWindWorld);
        lastRelativeWindSpeed = relativeWindSpeed;
        if (relativeWindSpeed < MIN_RELATIVE_WIND_SPEED_MPS) {
            return new UpdateResult(false, false, solidCells, dxMeters, relativeWindSpeed);
        }

        AssemblyTransform transform = AssemblyTransform.from(body);
        PhysicsVector bodyLocalWind = transform.worldDirectionToLocal(relativeWindWorld);
        PhysicsVector bodyLocalUp = transform.worldDirectionToLocal(new PhysicsVector(0.0D, 1.0D, 0.0D));
        AeroWindFrame frame = AeroWindFrame.fromBodyLocalWind(bodyLocalWind, bodyLocalUp);
        AssemblySolidMaskVoxelizer.Plan plan = AssemblySolidMaskVoxelizer.plan(assembly, frame);
        if (!plan.hasGeometry()) {
            return new UpdateResult(false, false, solidCells, dxMeters, relativeWindSpeed);
        }
        windFrame = frame;
        referenceBodyLocal = plan.objectCenterBodyLocal();
        dxMeters = plan.dxMeters();
        if (plan.signature() == profileSignature) {
            return new UpdateResult(true, false, solidCells, dxMeters, relativeWindSpeed);
        }

        AssemblySolidMaskVoxelizer.MaskResult result = AssemblySolidMaskVoxelizer.voxelize(plan);
        profileSignature = result.signature();
        solidCells = result.solidCells();
        projectedAreaMeters2 = result.projectedAreaMeters2();
        characteristicLengthMeters = result.characteristicLengthMeters();
        return new UpdateResult(true, true, solidCells, dxMeters, relativeWindSpeed);
    }

    int solidCells() {
        return solidCells;
    }

    double dxMeters() {
        return dxMeters;
    }

    double projectedAreaMeters2() {
        return projectedAreaMeters2;
    }

    double characteristicLengthMeters() {
        return characteristicLengthMeters;
    }

    double lastRelativeWindSpeed() {
        return lastRelativeWindSpeed;
    }

    boolean hasGeometryProfile() {
        return windFrame != null
                && solidCells > 0
                && dxMeters > 0.0D
                && projectedAreaMeters2 > 0.0D
                && characteristicLengthMeters > 0.0D;
    }

    AeroWindFrame windFrame() {
        if (windFrame == null) {
            throw new IllegalStateException("Aerodynamics wind frame has not been initialized");
        }
        return windFrame;
    }

    PhysicsVector referenceBodyLocal() {
        return referenceBodyLocal;
    }

    @Override
    public void close() {
        solidCells = 0;
        dxMeters = 0.0D;
        projectedAreaMeters2 = 0.0D;
        characteristicLengthMeters = 0.0D;
        profileSignature = Long.MIN_VALUE;
        windFrame = null;
        referenceBodyLocal = PhysicsVector.ZERO;
    }

    private static PhysicsVector subtract(PhysicsVector first, PhysicsVector second) {
        return new PhysicsVector(first.x() - second.x(), first.y() - second.y(), first.z() - second.z());
    }

    private static double length(PhysicsVector vector) {
        return Math.sqrt(vector.x() * vector.x() + vector.y() * vector.y() + vector.z() * vector.z());
    }

    record UpdateResult(
            boolean eligible,
            boolean rebuilt,
            int solidCells,
            double dxMeters,
            double relativeWindSpeed
    ) {
    }
}
