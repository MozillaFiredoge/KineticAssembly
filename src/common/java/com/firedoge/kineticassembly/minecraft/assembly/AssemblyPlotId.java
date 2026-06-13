package com.firedoge.kineticassembly.minecraft.assembly;

public record AssemblyPlotId(long value) {
    public AssemblyPlotId {
        if (value < 0L) {
            throw new IllegalArgumentException("value must not be negative");
        }
    }

    @Override
    public String toString() {
        return "plot-" + value;
    }
}
