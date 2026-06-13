package com.firedoge.kineticassembly.minecraft.assembly;

import javax.annotation.Nullable;

/**
 * Implement on block entities that need to keep other assemblies loaded together with their host assembly.
 */
public interface BlockEntityAssemblyActor {
    /**
     * Returns assemblies that must load and unload with the assembly containing this block entity.
     *
     * <p>This method can be called while source chunks are unloading, so implementations should use cached
     * assembly references or ids instead of querying live world chunks.</p>
     */
    @Nullable
    default Iterable<PhysicsAssembly> kinetic_assembly$getLoadingDependencies() {
        return kinetic_assembly$getConnectionDependencies();
    }

    /**
     * Returns assemblies connected to this actor. Loading dependencies default to this connection set.
     */
    @Nullable
    default Iterable<PhysicsAssembly> kinetic_assembly$getConnectionDependencies() {
        return null;
    }
}
