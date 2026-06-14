package com.firedoge.kineticassembly.backend.physx;

import java.util.Objects;

import com.firedoge.kineticassembly.api.PhysicsBackend;
import com.firedoge.kineticassembly.api.PhysicsWorld;
import com.firedoge.kineticassembly.api.PhysicsWorldConfig;

public final class PhysXBackend implements PhysicsBackend {
    public static final String ID = "physx";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isAvailable() {
        try {
            PhysXNative.load();
            return PhysXNative.isPhysXLinked();
        } catch (RuntimeException | UnsatisfiedLinkError error) {
            return false;
        }
    }

    @Override
    public PhysicsWorld createWorld(PhysicsWorldConfig config) {
        Objects.requireNonNull(config, "config");
        PhysXNative.load();
        if (!PhysXNative.isPhysXLinked()) {
            throw new IllegalStateException("PhysX native bridge was built without linked PhysX libraries");
        }
        long handle = PhysXNative.nativeCreateWorld(
                config.gravity().x(),
                config.gravity().y(),
                config.gravity().z(),
                config.fixedTimeStep(),
                config.maxSubSteps(),
                config.enableGpuDynamics()
        );
        return new PhysXWorld(handle, config);
    }
}
