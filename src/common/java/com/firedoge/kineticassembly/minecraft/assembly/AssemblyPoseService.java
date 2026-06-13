package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsQuaternion;
import com.firedoge.kineticassembly.api.PhysicsVector;

public final class AssemblyPoseService {
    private static final double POSITION_EPSILON_SQR = 1.0E-6D;
    private static final double ROTATION_EPSILON = 5.0E-4D;

    private final Map<AssemblyId, AssemblyPoseFrame> poseFrames = new LinkedHashMap<>();

    public AssemblyPoseFrame update(PhysicsAssembly assembly, PhysicsPose pose) {
        Objects.requireNonNull(assembly, "assembly");
        Objects.requireNonNull(pose, "pose");
        AssemblyPoseFrame current = poseFrames.get(assembly.id());
        AssemblyPoseFrame next;
        if (current == null) {
            next = AssemblyPoseFrame.initial(assembly.id(), pose);
        } else if (closeEnough(current.currentPose(), pose)) {
            next = current;
        } else {
            next = current.advance(pose);
        }
        if (next != current) {
            poseFrames.put(assembly.id(), next);
        }
        return next;
    }

    public void remove(AssemblyId id) {
        Objects.requireNonNull(id, "id");
        poseFrames.remove(id);
    }

    public void clear() {
        poseFrames.clear();
    }

    public static boolean closeEnough(PhysicsPose previous, PhysicsPose current) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        PhysicsVector a = previous.position();
        PhysicsVector b = current.position();
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        if (dx * dx + dy * dy + dz * dz > POSITION_EPSILON_SQR) {
            return false;
        }
        PhysicsQuaternion qa = previous.rotation();
        PhysicsQuaternion qb = current.rotation();
        return Math.abs(qa.x() - qb.x()) <= ROTATION_EPSILON
                && Math.abs(qa.y() - qb.y()) <= ROTATION_EPSILON
                && Math.abs(qa.z() - qb.z()) <= ROTATION_EPSILON
                && Math.abs(qa.w() - qb.w()) <= ROTATION_EPSILON;
    }
}
