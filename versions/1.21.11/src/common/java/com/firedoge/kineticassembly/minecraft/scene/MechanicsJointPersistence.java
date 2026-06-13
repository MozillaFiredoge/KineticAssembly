package com.firedoge.kineticassembly.minecraft.scene;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.mojang.serialization.Codec;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsQuaternion;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.mechanics.MechanicsJointType;
import com.firedoge.kineticassembly.platform.PlatformServices;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class MechanicsJointPersistence {
    private static final int DATA_VERSION = 1;
    private static final int CAPTURE_INTERVAL_TICKS = 100;
    private static final String DATA_NAME = KineticAssembly.MODID + "_mechanics_joints";
    private static final Codec<Data> CODEC = CompoundTag.CODEC.xmap(
            tag -> Data.load(tag, null),
            data -> data.save(new CompoundTag(), null)
    );
    private static final SavedDataType<Data> TYPE = new SavedDataType<>(DATA_NAME, Data::new, CODEC);

    private static int captureTick;
    private static boolean serverStopping;

    private MechanicsJointPersistence() {
    }

    public static void startServer() {
        serverStopping = false;
        captureTick = 0;
    }

    public static void restore(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        for (ServerLevel level : server.getAllLevels()) {
            restore(level);
        }
    }

    public static void capturePeriodically(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (serverStopping) {
            return;
        }
        captureTick++;
        if (captureTick < CAPTURE_INTERVAL_TICKS) {
            return;
        }
        captureTick = 0;
        capture(server);
    }

    public static void captureBeforeLevelSave(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        if (serverStopping) {
            return;
        }
        if (capture(level)) {
            level.getDataStorage().saveAndJoin();
            PlatformServices.services().waitForIoCompletion();
        }
    }

    public static void flush(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        capture(server);
        serverStopping = true;
        for (ServerLevel level : server.getAllLevels()) {
            level.getDataStorage().saveAndJoin();
        }
        PlatformServices.services().waitForIoCompletion();
    }

    private static void restore(ServerLevel level) {
        Data data = data(level);
        if (data.restored()) {
            return;
        }
        if (data.isEmpty()) {
            data.markRestored();
            return;
        }
        if (!data.restoreLoadLogged()) {
            KineticAssembly.LOGGER.info(
                    "Loaded {} persisted mechanics joints for {}",
                    data.size(),
                    level.dimension().identifier()
            );
            data.markRestoreLoadLogged();
        }

        int restored = 0;
        int alreadyActive = 0;
        int deferred = 0;
        int failed = 0;
        List<CompoundTag> kept = new ArrayList<>();
        boolean changed = false;
        for (CompoundTag tag : data.joints()) {
            PersistedMechanicsJoint joint;
            try {
                joint = readJoint(level, tag);
            } catch (RuntimeException exception) {
                failed++;
                changed = true;
                KineticAssembly.LOGGER.warn(
                        "Dropping invalid persisted mechanics joint in {}",
                        level.dimension().identifier(),
                        exception
                );
                continue;
            }

            MechanicsJointRestoreResult result;
            try {
                result = ServerPhysicsRuntime.INSTANCE.restorePersistedMechanicsJoint(level, joint);
            } catch (RuntimeException exception) {
                failed++;
                changed = true;
                KineticAssembly.LOGGER.warn(
                        "Dropping persisted mechanics joint {} in {} after restore failure",
                        joint.id(),
                        level.dimension().identifier(),
                        exception
                );
                continue;
            }

            if (result == MechanicsJointRestoreResult.FAILED) {
                failed++;
                changed = true;
                continue;
            }
            kept.add(tag.copy());
            switch (result) {
                case RESTORED -> restored++;
                case ALREADY_ACTIVE -> alreadyActive++;
                case DEFERRED -> deferred++;
                case FAILED -> {
                }
            }
        }
        if (changed) {
            data.replaceJoints(kept);
        }
        if (deferred > 0) {
            if (restored > 0 || alreadyActive > 0 || failed > 0) {
                KineticAssembly.LOGGER.info(
                        "Restored {} mechanics joints for {}; already active={}, deferred={}, failed={}",
                        restored,
                        level.dimension().identifier(),
                        alreadyActive,
                        deferred,
                        failed
                );
            }
            return;
        }

        data.markRestored();
        if (restored > 0 || alreadyActive > 0 || failed > 0) {
            KineticAssembly.LOGGER.info(
                    "Restored {} mechanics joints for {}; already active={}, failed={}",
                    restored,
                    level.dimension().identifier(),
                    alreadyActive,
                    failed
            );
        }
    }

    private static boolean capture(MinecraftServer server) {
        boolean changed = false;
        for (ServerLevel level : server.getAllLevels()) {
            changed |= capture(level);
        }
        return changed;
    }

    private static boolean capture(ServerLevel level) {
        Data existing = existingData(level);
        List<PersistedMechanicsJoint> active = ServerPhysicsRuntime.INSTANCE.persistedMechanicsJoints(level);
        if (active.isEmpty() && (existing == null || existing.isEmpty())) {
            return false;
        }

        List<CompoundTag> saved = new ArrayList<>();
        Set<PhysicsJointId> activeIds = new LinkedHashSet<>();
        for (PersistedMechanicsJoint joint : active) {
            activeIds.add(joint.id());
            saved.add(writeJoint(joint));
        }

        if (existing != null) {
            for (CompoundTag tag : existing.joints()) {
                Optional<PhysicsJointId> maybeId = jointIdFromTag(tag);
                if (maybeId.isEmpty()) {
                    continue;
                }
                PhysicsJointId jointId = maybeId.get();
                if (activeIds.contains(jointId) || ServerPhysicsRuntime.INSTANCE.wasMechanicsJointExplicitlyRemoved(jointId)) {
                    continue;
                }

                PersistedMechanicsJoint joint;
                try {
                    joint = readJoint(level, tag);
                } catch (RuntimeException exception) {
                    KineticAssembly.LOGGER.warn(
                            "Dropping invalid persisted mechanics joint during capture in {}",
                            level.dimension().identifier(),
                            exception
                    );
                    continue;
                }
                if (!ServerPhysicsRuntime.INSTANCE.mechanicsBodiesAvailable(level, joint.firstBodyId(), joint.secondBodyId())) {
                    saved.add(tag.copy());
                }
            }
        }

        return data(level).replaceJoints(saved);
    }

    private static CompoundTag writeJoint(PersistedMechanicsJoint joint) {
        CompoundTag tag = new CompoundTag();
        tag.store("id", net.minecraft.core.UUIDUtil.CODEC, joint.id().value());
        tag.putString("type", joint.type().name().toLowerCase(Locale.ROOT));
        tag.store("first_body", net.minecraft.core.UUIDUtil.CODEC, joint.firstBodyId().value());
        tag.store("second_body", net.minecraft.core.UUIDUtil.CODEC, joint.secondBodyId().value());
        tag.putBoolean("collide_connected", joint.definition().collideConnected());

        if (joint.type() == MechanicsJointType.DISTANCE) {
            tag.put("first_local_anchor", writeVector(joint.definition().firstLocalAnchor()));
            tag.put("second_local_anchor", writeVector(joint.definition().secondLocalAnchor()));
            tag.putFloat("min_distance", joint.definition().minDistance());
            tag.putFloat("max_distance", joint.definition().maxDistance());
            tag.putFloat("stiffness", joint.definition().stiffness());
            tag.putFloat("damping", joint.definition().damping());
        } else {
            tag.put("first_local_frame", writePose(joint.definition().firstLocalFrame()));
            tag.put("second_local_frame", writePose(joint.definition().secondLocalFrame()));
        }
        return tag;
    }

    private static PersistedMechanicsJoint readJoint(ServerLevel level, CompoundTag tag) {
        PhysicsJointId id = new PhysicsJointId(tag.read("id", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
        MechanicsJointType type = MechanicsJointType.valueOf(tag.getStringOr("type", "").toUpperCase(Locale.ROOT));
        PhysicsObjectId firstBodyId = new PhysicsObjectId(tag.read("first_body", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
        PhysicsObjectId secondBodyId = new PhysicsObjectId(tag.read("second_body", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
        boolean collideConnected = tag.getBooleanOr("collide_connected", false);
        MechanicsJointDefinition definition = type == MechanicsJointType.DISTANCE
                ? MechanicsJointDefinition.distance(
                        readVector(tag.getCompoundOrEmpty("first_local_anchor")),
                        readVector(tag.getCompoundOrEmpty("second_local_anchor")),
                        tag.getFloatOr("min_distance", 0.0F),
                        tag.getFloatOr("max_distance", 0.0F),
                        tag.getFloatOr("stiffness", 0.0F),
                        tag.getFloatOr("damping", 0.0F),
                        collideConnected
                )
                : MechanicsJointDefinition.frames(
                        readPose(tag.getCompoundOrEmpty("first_local_frame")),
                        readPose(tag.getCompoundOrEmpty("second_local_frame")),
                        collideConnected
                );
        return new PersistedMechanicsJoint(
                id,
                level.dimension(),
                type,
                firstBodyId,
                secondBodyId,
                definition
        );
    }

    private static Optional<PhysicsJointId> jointIdFromTag(CompoundTag tag) {
        return tag.read("id", net.minecraft.core.UUIDUtil.CODEC).isPresent() ? Optional.of(new PhysicsJointId(tag.read("id", net.minecraft.core.UUIDUtil.CODEC).orElseThrow())) : Optional.empty();
    }

    private static CompoundTag writePose(PhysicsPose pose) {
        CompoundTag tag = new CompoundTag();
        tag.put("position", writeVector(pose.position()));
        tag.put("rotation", writeQuaternion(pose.rotation()));
        return tag;
    }

    private static PhysicsPose readPose(CompoundTag tag) {
        return new PhysicsPose(
                readVector(tag.getCompoundOrEmpty("position")),
                readQuaternion(tag.getCompoundOrEmpty("rotation"))
        );
    }

    private static CompoundTag writeVector(PhysicsVector vector) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("x", vector.x());
        tag.putDouble("y", vector.y());
        tag.putDouble("z", vector.z());
        return tag;
    }

    private static PhysicsVector readVector(CompoundTag tag) {
        return new PhysicsVector(tag.getDoubleOr("x", 0.0D), tag.getDoubleOr("y", 0.0D), tag.getDoubleOr("z", 0.0D));
    }

    private static CompoundTag writeQuaternion(PhysicsQuaternion quaternion) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("x", quaternion.x());
        tag.putDouble("y", quaternion.y());
        tag.putDouble("z", quaternion.z());
        tag.putDouble("w", quaternion.w());
        return tag;
    }

    private static PhysicsQuaternion readQuaternion(CompoundTag tag) {
        return new PhysicsQuaternion(
                tag.getDoubleOr("x", 0.0D),
                tag.getDoubleOr("y", 0.0D),
                tag.getDoubleOr("z", 0.0D),
                tag.getDoubleOr("w", 0.0D)
        );
    }

    private static Data data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    private static Data existingData(ServerLevel level) {
        return level.getDataStorage().get(TYPE);
    }

    private static final class Data extends SavedData {
        private List<CompoundTag> joints = List.of();
        private boolean restored;
        private boolean restoreLoadLogged;

        private static Data load(CompoundTag tag, HolderLookup.Provider registries) {
            Data data = new Data();
            ListTag joints = tag.getListOrEmpty("joints");
            List<CompoundTag> loaded = new ArrayList<>(joints.size());
            for (int i = 0; i < joints.size(); i++) {
                loaded.add(joints.getCompoundOrEmpty(i).copy());
            }
            data.joints = List.copyOf(loaded);
            return data;
        }

        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putInt("version", DATA_VERSION);
            ListTag jointTags = new ListTag();
            for (CompoundTag joint : joints) {
                jointTags.add(joint.copy());
            }
            tag.put("joints", jointTags);
            return tag;
        }

        private boolean isEmpty() {
            return joints.isEmpty();
        }

        private int size() {
            return joints.size();
        }

        private List<CompoundTag> joints() {
            return joints.stream()
                    .map(CompoundTag::copy)
                    .toList();
        }

        private boolean restored() {
            return restored;
        }

        private boolean restoreLoadLogged() {
            return restoreLoadLogged;
        }

        private void markRestoreLoadLogged() {
            restoreLoadLogged = true;
        }

        private void markRestored() {
            restored = true;
        }

        private boolean replaceJoints(List<CompoundTag> joints) {
            List<CompoundTag> copy = joints.stream()
                    .map(CompoundTag::copy)
                    .toList();
            if (this.joints.equals(copy)) {
                return false;
            }
            this.joints = copy;
            restored = false;
            setDirty();
            return true;
        }
    }
}
