package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.Objects;
import java.util.UUID;

public final class AssemblyLoadingTicket<T> {
    private final AssemblyLoadingTicketType<T> type;
    private final AssemblyId assemblyId;
    private final T key;

    public AssemblyLoadingTicket(AssemblyLoadingTicketType<T> type, AssemblyId assemblyId, T key) {
        this.type = Objects.requireNonNull(type, "type");
        this.assemblyId = Objects.requireNonNull(assemblyId, "assemblyId");
        this.key = Objects.requireNonNull(key, "key");
    }

    public AssemblyLoadingTicketType<T> type() {
        return type;
    }

    public AssemblyId assemblyId() {
        return assemblyId;
    }

    public UUID assemblyUuid() {
        return assemblyId.value();
    }

    public T key() {
        return key;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AssemblyLoadingTicket<?> ticket)) {
            return false;
        }
        return type.equals(ticket.type)
                && assemblyId.equals(ticket.assemblyId)
                && key.equals(ticket.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, assemblyId, key);
    }

    @Override
    public String toString() {
        return "AssemblyLoadingTicket[" + type.name() + " " + assemblyId + " (" + key + ")]";
    }
}
