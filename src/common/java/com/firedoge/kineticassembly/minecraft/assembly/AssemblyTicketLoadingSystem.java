package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.LinkedHashSet;

final class AssemblyTicketLoadingSystem {
    private final ServerAssemblyContainer container;

    AssemblyTicketLoadingSystem(ServerAssemblyContainer container) {
        this.container = container;
    }

    void onAssemblyAdded(PhysicsAssembly assembly) {
        AssemblyTicketInfo info = container.ticketInfo(assembly.id());
        if (info != null && !info.tickets().isEmpty()) {
            container.setActiveTickets(assembly, new LinkedHashSet<>(info.tickets()));
        }
    }

    void onAssemblyRemoved(PhysicsAssembly assembly, AssemblyRemovalReason reason) {
        container.clearActiveTickets(assembly);
        if (reason == AssemblyRemovalReason.REMOVED) {
            container.removeTicketInfo(assembly.id());
            AssemblyTicketsSavedData.getOrLoad(container.level()).setDirty();
        }
    }
}
