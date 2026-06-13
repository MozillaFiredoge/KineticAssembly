package com.firedoge.kineticassembly.util;

import com.firedoge.kineticassembly.api.PhysicsVector;

import net.minecraft.world.phys.Vec3;

public final class MathConversions {
    private MathConversions() {
    }

    public static PhysicsVector fromMinecraft(Vec3 vector) {
        return new PhysicsVector(vector.x, vector.y, vector.z);
    }

    public static Vec3 toMinecraft(PhysicsVector vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }
}
