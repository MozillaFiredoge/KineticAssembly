package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;

import com.firedoge.kineticassembly.KineticAssembly;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

public final class AssemblyTicketsSavedData extends SavedData {
    private static final String DATA_NAME = KineticAssembly.MODID + "_assembly_force_load_tickets";

    private final ServerLevel level;

    private AssemblyTicketsSavedData(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    public static AssemblyTicketsSavedData getOrLoad(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(
                        () -> new AssemblyTicketsSavedData(level),
                        (tag, registries) -> load(level, tag)
                ),
                DATA_NAME
        );
    }

    private static AssemblyTicketsSavedData load(ServerLevel level, CompoundTag tag) {
        AssemblyTicketsSavedData data = new AssemblyTicketsSavedData(level);
        ServerAssemblyContainer container = AssemblyContainers.server(level).orElse(null);
        if (container == null) {
            return data;
        }

        Map<AssemblyId, AssemblyTicketInfo> ticketsByAssembly = new LinkedHashMap<>();
        ListTag ticketInfos = tag.getList("tickets", Tag.TAG_COMPOUND);
        for (int index = 0; index < ticketInfos.size(); index++) {
            CompoundTag infoTag = ticketInfos.getCompound(index);
            if (!infoTag.hasUUID("uuid")) {
                continue;
            }

            AssemblyId assemblyId = new AssemblyId(infoTag.getUUID("uuid"));
            Set<AssemblyLoadingTicket<?>> tickets = new LinkedHashSet<>();
            ListTag entriesTag = infoTag.getList("entries", Tag.TAG_COMPOUND);
            for (int entryIndex = 0; entryIndex < entriesTag.size(); entryIndex++) {
                AssemblyLoadingTicket<?> ticket = deserializeTicket(assemblyId, entriesTag.getCompound(entryIndex));
                if (ticket != null) {
                    tickets.add(ticket);
                }
            }

            GlobalSavedAssemblyPointer pointer = infoTag.contains("pointer", Tag.TAG_COMPOUND)
                    ? GlobalSavedAssemblyPointer.read(infoTag.getCompound("pointer"))
                    : null;
            if (!tickets.isEmpty()) {
                ticketsByAssembly.put(assemblyId, new AssemblyTicketInfo(pointer, tickets));
            }
        }

        container.loadTickets(ticketsByAssembly);
        return data;
    }

    @Nullable
    private static <T> AssemblyLoadingTicket<T> deserializeTicket(AssemblyId assemblyId, CompoundTag tag) {
        ResourceLocation typeName = ResourceLocation.tryParse(tag.getString("type"));
        if (typeName == null) {
            return null;
        }

        @SuppressWarnings("unchecked")
        AssemblyLoadingTicketType<T> type = (AssemblyLoadingTicketType<T>) AssemblyLoadingTicketType.byName(typeName);
        if (type == null) {
            KineticAssembly.LOGGER.error("Unknown assembly loading ticket type: {}", typeName);
            return null;
        }

        Tag keyTag = tag.get("key");
        if (keyTag == null) {
            return null;
        }

        T key = type.codec().parse(NbtOps.INSTANCE, keyTag)
                .resultOrPartial(error -> KineticAssembly.LOGGER.warn(
                        "Failed to deserialize assembly ticket key for type {}: {}",
                        type.name(),
                        error
                ))
                .orElse(null);
        return key == null ? null : new AssemblyLoadingTicket<>(type, assemblyId, key);
    }

    @Nullable
    private static <T> CompoundTag serializeTicket(AssemblyLoadingTicket<T> ticket) {
        AssemblyLoadingTicketType<T> type = ticket.type();
        Codec<T> codec = type.codec();
        return codec.encodeStart(NbtOps.INSTANCE, ticket.key())
                .resultOrPartial(error -> KineticAssembly.LOGGER.warn(
                        "Failed to serialize assembly ticket key for type {}: {}",
                        type.name(),
                        error
                ))
                .map(keyTag -> {
                    CompoundTag tag = new CompoundTag();
                    tag.putString("type", type.name().toString());
                    tag.put("key", keyTag);
                    return tag;
                })
                .orElse(null);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ServerAssemblyContainer container = AssemblyContainers.server(level).orElse(null);
        if (container == null) {
            tag.put("tickets", new ListTag());
            return tag;
        }

        ListTag ticketInfos = new ListTag();
        for (Map.Entry<AssemblyId, AssemblyTicketInfo> entry : container.allTickets().entrySet()) {
            AssemblyId id = entry.getKey();
            AssemblyTicketInfo info = entry.getValue();
            if (info.tickets().isEmpty()) {
                continue;
            }

            CompoundTag infoTag = new CompoundTag();
            infoTag.putUUID("uuid", id.value());

            ListTag entriesTag = new ListTag();
            for (AssemblyLoadingTicket<?> ticket : info.tickets()) {
                CompoundTag entryTag = serializeTicket(ticket);
                if (entryTag != null) {
                    entriesTag.add(entryTag);
                }
            }
            if (entriesTag.isEmpty()) {
                continue;
            }

            GlobalSavedAssemblyPointer pointer = info.pointer();
            if (pointer == null) {
                KineticAssembly.LOGGER.error(
                        "Pointer is null for assembly loading ticket on {}; this ticket cannot force-load the assembly after restart",
                        id
                );
            } else {
                infoTag.put("pointer", pointer.write());
            }

            infoTag.put("entries", entriesTag);
            ticketInfos.add(infoTag);
        }

        tag.put("tickets", ticketInfos);
        return tag;
    }
}
