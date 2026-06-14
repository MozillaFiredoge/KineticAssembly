package com.firedoge.kineticassembly.mechanics;

@FunctionalInterface
public interface MechanicsTickListener {
    void onMechanicsTick(MechanicsTickContext context);
}
