package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

public final class AssemblyTicketInfo {
    private final Set<AssemblyLoadingTicket<?>> tickets = new LinkedHashSet<>();
    @Nullable
    private GlobalSavedAssemblyPointer pointer;

    public AssemblyTicketInfo() {
    }

    public AssemblyTicketInfo(@Nullable GlobalSavedAssemblyPointer pointer, Set<AssemblyLoadingTicket<?>> tickets) {
        this.pointer = pointer;
        this.tickets.addAll(Objects.requireNonNull(tickets, "tickets"));
    }

    public Set<AssemblyLoadingTicket<?>> tickets() {
        return tickets;
    }

    @Nullable
    public GlobalSavedAssemblyPointer pointer() {
        return pointer;
    }

    public void setPointer(@Nullable GlobalSavedAssemblyPointer pointer) {
        this.pointer = pointer;
    }
}
