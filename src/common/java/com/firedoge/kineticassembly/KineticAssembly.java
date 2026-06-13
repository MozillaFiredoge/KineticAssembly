package com.firedoge.kineticassembly;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import com.firedoge.kineticassembly.mechanics.MechanicsApi;
import com.firedoge.kineticassembly.mechanics.ServerMechanicsApi;

public final class KineticAssembly {
    public static final String MODID = "kinetic_assembly";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final MechanicsApi MECHANICS_API = ServerMechanicsApi.INSTANCE;

    private KineticAssembly() {
    }

    public static MechanicsApi api() {
        return MECHANICS_API;
    }
}
