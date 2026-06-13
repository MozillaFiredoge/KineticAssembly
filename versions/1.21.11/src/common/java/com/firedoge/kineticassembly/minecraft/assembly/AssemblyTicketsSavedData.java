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
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class AssemblyTicketsSavedData extends SavedData {
    private static final String DATA_NAME = KineticAssembly.MODID + "_assembly_force_load_tickets";
    private static final SavedDataType<AssemblyTicketsSavedData> TYPE = new SavedDataType<>(
            DATA_NAME,
            AssemblyTicketsSavedData::new,
            level -> CompoundTag.CODEC.xmap(
                    tag -> load(Objects.requireNonNull(level, "level"), tag),
                    data -> data.save(new CompoundTag(), null)
            )
    );

    private final ServerLevel level;

    private AssemblyTicketsSavedData(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    public static AssemblyTicketsSavedData getOrLoad(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    private static AssemblyTicketsSavedData load(ServerLevel level, CompoundTag tag) {
        AssemblyTicketsSavedData data = new AssemblyTicketsSavedData(level);
        ServerAssemblyContainer container = AssemblyContainers.server(level).orElse(null);
        if (container == null) {
            return data;
        }

        Map<AssemblyId, AssemblyTicketInfo> ticketsByAssembly = new LinkedHashMap<>();
        ListTag ticketInfos = tag.getListOrEmpty("tickets");
        for (int index = 0; index < ticketInfos.size(); index++) {
            CompoundTag infoTag = ticketInfos.getCompoundOrEmpty(index);
            if (!infoTag.read("uuid", net.minecraft.core.UUIDUtil.CODEC).isPresent()) {
                continue;
            }

            AssemblyId assemblyId = new AssemblyId(infoTag.read("uuid", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
            Set<AssemblyLoadingTicket<?>> tickets = new LinkedHashSet<>();
            ListTag entriesTag = infoTag.getListOrEmpty("entries");
            for (int entryIndex = 0; entryIndex < entriesTag.size(); entryIndex++) {
                AssemblyLoadingTicket<?> ticket = deserializeTicket(assemblyId, entriesTag.getCompoundOrEmpty(entryIndex));
                if (ticket != null) {
                    tickets.add(ticket);
                }
            }

            GlobalSavedAssemblyPointer pointer = infoTag.contains("pointer")
                    ? GlobalSavedAssemblyPointer.read(infoTag.getCompoundOrEmpty("pointer"))
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
        Identifier typeName = Identifier.tryParse(tag.getStringOr("type", ""));
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
            infoTag.store("uuid", net.minecraft.core.UUIDUtil.CODEC, id.value());

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
