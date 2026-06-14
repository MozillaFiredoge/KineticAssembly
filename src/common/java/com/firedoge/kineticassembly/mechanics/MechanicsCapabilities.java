package com.firedoge.kineticassembly.mechanics;

public record MechanicsCapabilities(
        boolean nativeBackendAvailable,
        boolean dynamicBoxes,
        boolean compoundBoxes,
        boolean blockAssemblies,
        boolean joints,
        boolean impulses,
        boolean forces,
        boolean tickEvents
) {
    public static final MechanicsCapabilities UNAVAILABLE = new MechanicsCapabilities(
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false
    );
}
