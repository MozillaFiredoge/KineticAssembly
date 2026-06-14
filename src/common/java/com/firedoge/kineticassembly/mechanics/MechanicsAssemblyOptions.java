package com.firedoge.kineticassembly.mechanics;

import java.util.Objects;
import java.util.Optional;

public record MechanicsAssemblyOptions(
        MechanicsOwner owner,
        Optional<Float> massOverride,
        boolean debugProxy
) {
    public static final MechanicsAssemblyOptions DEFAULT = new MechanicsAssemblyOptions(
            MechanicsOwner.UNSPECIFIED,
            Optional.empty(),
            false
    );

    public MechanicsAssemblyOptions {
        Objects.requireNonNull(owner, "owner");
        massOverride = Objects.requireNonNull(massOverride, "massOverride");
        massOverride.ifPresent(mass -> {
            if (!Float.isFinite(mass) || mass <= 0.0F) {
                throw new IllegalArgumentException("massOverride must be finite and positive");
            }
        });
    }

    public static MechanicsAssemblyOptions defaults() {
        return DEFAULT;
    }

    public static MechanicsAssemblyOptions owned(MechanicsOwner owner) {
        return new MechanicsAssemblyOptions(owner, Optional.empty(), false);
    }

    public MechanicsAssemblyOptions withOwner(MechanicsOwner owner) {
        return new MechanicsAssemblyOptions(owner, massOverride, debugProxy);
    }

    public MechanicsAssemblyOptions withMassOverride(float mass) {
        return new MechanicsAssemblyOptions(owner, Optional.of(mass), debugProxy);
    }

    public MechanicsAssemblyOptions withoutMassOverride() {
        return new MechanicsAssemblyOptions(owner, Optional.empty(), debugProxy);
    }

    public MechanicsAssemblyOptions withDebugProxy(boolean debugProxy) {
        return new MechanicsAssemblyOptions(owner, massOverride, debugProxy);
    }
}
