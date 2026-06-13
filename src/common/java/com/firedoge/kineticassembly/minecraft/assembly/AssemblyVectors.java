package com.firedoge.kineticassembly.minecraft.assembly;

import com.firedoge.kineticassembly.api.PhysicsVector;

import net.minecraft.world.phys.Vec3;

public final class AssemblyVectors {
    private AssemblyVectors() {
    }

    public static boolean finite(Vec3 vector) {
        return vector != null
                && Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }

    public static boolean finite(PhysicsVector vector) {
        return vector != null
                && Double.isFinite(vector.x())
                && Double.isFinite(vector.y())
                && Double.isFinite(vector.z());
    }
}
