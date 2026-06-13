package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;

import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsQuaternion;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.KineticAssembly;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;

public final class AssemblyTrackingPointSavedData extends SavedData {
    public static final String LOGIN_POINT_TAG = "LoginPoint";
    private static final String DATA_NAME = KineticAssembly.MODID + "_tracking_points";
    private static final Codec<AssemblyTrackingPointSavedData> CODEC = CompoundTag.CODEC.xmap(
            tag -> load(tag, null),
            data -> data.save(new CompoundTag(), null)
    );
    private static final SavedDataType<AssemblyTrackingPointSavedData> TYPE =
            new SavedDataType<>(DATA_NAME, AssemblyTrackingPointSavedData::new, CODEC);

    private final Map<UUID, TrackingPoint> trackingPoints = new LinkedHashMap<>();

    public static AssemblyTrackingPointSavedData getOrLoad(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    private static AssemblyTrackingPointSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        AssemblyTrackingPointSavedData data = new AssemblyTrackingPointSavedData();
        CompoundTag points = tag.getCompoundOrEmpty("tracking_points");
        for (String key : points.keySet()) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException ignored) {
                continue;
            }

            CompoundTag pointTag = points.getCompoundOrEmpty(key);
            TrackingPoint point = readTrackingPoint(pointTag);
            if (point != null) {
                data.trackingPoints.put(uuid, point);
            }
        }
        return data;
    }

    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag points = new CompoundTag();
        for (Map.Entry<UUID, TrackingPoint> entry : trackingPoints.entrySet()) {
            points.put(entry.getKey().toString(), writeTrackingPoint(entry.getValue()));
        }
        tag.put("tracking_points", points);
        return tag;
    }

    @Nullable
    public UUID generateTrackingPoint(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        if (!(player instanceof AssemblyEntityCollisionAccess access)) {
            return null;
        }
        AssemblyId trackingId = access.kinetic_assembly$trackingAssemblyId();
        if (trackingId == null) {
            return null;
        }
        AssemblyPlotProjection projection = AssemblyPathing.projection(player.level(), trackingId);
        return projection == null ? null : generateTrackingPoint(player, projection);
    }

    @Nullable
    public UUID generateTrackingPoint(ServerPlayer player, AssemblyPlotProjection projection) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(projection, "projection");

        Vec3 plotPosition = projection.worldToPlot(player.position());
        return generateTrackingPoint(player, projection, plotPosition);
    }

    @Nullable
    public UUID generateTrackingPoint(ServerPlayer player, AssemblyPlotProjection projection, Vec3 plotPosition) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(plotPosition, "plotPosition");

        if (!AssemblyPathing.finite(plotPosition)) {
            return null;
        }

        UUID uuid = player.getUUID();
        GlobalSavedAssemblyPointer pointer = lastSavedPointer(player.level(), projection.id());
        trackingPoints.put(uuid, new TrackingPoint(
                true,
                projection.id(),
                pointer,
                plotPosition,
                pointer == null ? player.position() : null,
                SavedProjection.from(projection)
        ));
        setDirty();
        return uuid;
    }

    @Nullable
    public TakenLoginPoint take(ServerLevel level, UUID uuid, boolean remove) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(uuid, "uuid");
        TrackingPoint point = remove ? trackingPoints.remove(uuid) : trackingPoints.get(uuid);
        if (remove) {
            setDirty();
        }
        if (point == null) {
            return null;
        }
        if (!point.inAssembly()) {
            return new TakenLoginPoint(point.point(), null, null);
        }

        AssemblyPlotProjection projection = point.assemblyId() == null
                ? null
                : AssemblyPathing.projection(level, point.assemblyId());
        if (projection == null) {
            projection = AssemblyPathing.projection(level, net.minecraft.core.BlockPos.containing(point.point()));
        }
        if (projection == null && point.assemblyId() != null) {
            projection = pointerProjection(level, point.lastSavedAssemblyPointer()).orElse(null);
        }
        if (projection == null && point.assemblyId() != null) {
            projection = AssemblyPersistence.savedProjection(level, point.assemblyId()).orElse(null);
        }
        if (projection == null) {
            projection = pointerProjection(level, point.lastSavedAssemblyPointer()).orElse(null);
        }
        if (projection == null && point.savedProjection() != null) {
            projection = point.savedProjection().toProjection();
        }
        if (projection == null && point.globalPlaceholderPosition() != null) {
            return new TakenLoginPoint(point.globalPlaceholderPosition(), null, null);
        }
        if (projection == null) {
            return null;
        }

        Vec3 position = projection.plotToWorld(point.point());
        if (!AssemblyPathing.finite(position)) {
            return point.globalPlaceholderPosition() == null
                    ? null
                    : new TakenLoginPoint(point.globalPlaceholderPosition(), null, null);
        }
        return new TakenLoginPoint(position, projection.id(), point.point());
    }

    public int projectAssemblyTrackingPoints(AssemblyPlotProjection projection) {
        Objects.requireNonNull(projection, "projection");
        int projected = 0;
        for (Map.Entry<UUID, TrackingPoint> entry : trackingPoints.entrySet()) {
            TrackingPoint point = entry.getValue();
            if (!point.inAssembly() || !referencesAssembly(point, projection.id())) {
                continue;
            }

            Vec3 worldPosition = projection.plotToWorld(point.point());
            if (!AssemblyPathing.finite(worldPosition)) {
                continue;
            }

            entry.setValue(new TrackingPoint(false, null, null, worldPosition, null, null));
            projected++;
        }
        if (projected > 0) {
            setDirty();
        }
        return projected;
    }

    public int updateSavedAssemblyPointer(AssemblyId id, GlobalSavedAssemblyPointer pointer) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(pointer, "pointer");
        int updated = 0;
        for (Map.Entry<UUID, TrackingPoint> entry : trackingPoints.entrySet()) {
            TrackingPoint point = entry.getValue();
            if (!point.inAssembly() || !referencesAssembly(point, id)) {
                continue;
            }
            if (pointer.equals(point.lastSavedAssemblyPointer()) && point.globalPlaceholderPosition() == null) {
                continue;
            }
            entry.setValue(new TrackingPoint(
                    true,
                    point.assemblyId(),
                    pointer,
                    point.point(),
                    null,
                    point.savedProjection()
            ));
            updated++;
        }
        if (updated > 0) {
            setDirty();
        }
        return updated;
    }

    private static boolean referencesAssembly(TrackingPoint point, AssemblyId id) {
        if (id.equals(point.assemblyId())) {
            return true;
        }
        return point.savedProjection() != null && id.equals(point.savedProjection().id());
    }

    private static Optional<GlobalSavedAssemblyPointer> savedPointer(ServerLevel level, AssemblyId id) {
        return AssemblyContainers.server(level)
                .flatMap(container -> container.holdingChunkMap().pointer(id));
    }

    @Nullable
    private static GlobalSavedAssemblyPointer lastSavedPointer(ServerLevel level, AssemblyId id) {
        return savedPointer(level, id).orElse(null);
    }

    private static Optional<AssemblyPlotProjection> pointerProjection(
            ServerLevel level,
            @Nullable GlobalSavedAssemblyPointer pointer
    ) {
        if (pointer == null) {
            return Optional.empty();
        }
        return AssemblyContainers.server(level)
                .flatMap(container -> container.holdingChunkMap().projection(pointer));
    }

    @Nullable
    private static TrackingPoint readTrackingPoint(CompoundTag tag) {
        boolean inAssembly = tag.getBooleanOr("InAssembly", false);
        AssemblyId assemblyId = tag.contains("AssemblyId")
                ? new AssemblyId(tag.read("AssemblyId", net.minecraft.core.UUIDUtil.CODEC).orElseThrow())
                : null;
        if (assemblyId == null && tag.contains("AssemblyID")) {
            assemblyId = new AssemblyId(tag.read("AssemblyID", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
        }
        GlobalSavedAssemblyPointer pointer = tag.contains("AssemblyPointer")
                ? GlobalSavedAssemblyPointer.read(tag.getCompoundOrEmpty("AssemblyPointer"))
                : null;
        if (!tag.contains("Point")) {
            return null;
        }
        Vec3 point = readVector(tag.getCompoundOrEmpty("Point"));
        Vec3 placeholder = tag.contains("GlobalPlaceholder")
                ? readVector(tag.getCompoundOrEmpty("GlobalPlaceholder"))
                : null;
        SavedProjection projection = tag.contains("Projection")
                ? readProjection(tag.getCompoundOrEmpty("Projection"))
                : null;
        if (!AssemblyPathing.finite(point)
                || (placeholder != null && !AssemblyPathing.finite(placeholder))
                || (projection != null && !projection.finite())) {
            return null;
        }
        return new TrackingPoint(inAssembly, assemblyId, pointer, point, placeholder, projection);
    }

    private static CompoundTag writeTrackingPoint(TrackingPoint point) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("InAssembly", point.inAssembly());
        if (point.assemblyId() != null) {
            tag.store("AssemblyId", net.minecraft.core.UUIDUtil.CODEC, point.assemblyId().value());
        }
        if (point.lastSavedAssemblyPointer() != null) {
            tag.put("AssemblyPointer", point.lastSavedAssemblyPointer().write());
        }
        tag.put("Point", writeVector(point.point()));
        if (point.globalPlaceholderPosition() != null) {
            tag.put("GlobalPlaceholder", writeVector(point.globalPlaceholderPosition()));
        }
        if (point.savedProjection() != null) {
            tag.put("Projection", writeProjection(point.savedProjection()));
        }
        return tag;
    }

    @Nullable
    private static SavedProjection readProjection(CompoundTag tag) {
        if (!tag.contains("Plot")
                || !tag.contains("BodyToPlotOrigin")
                || !tag.contains("Pose")) {
            return null;
        }
        AssemblyId id = tag.contains("AssemblyId")
                ? new AssemblyId(tag.read("AssemblyId", net.minecraft.core.UUIDUtil.CODEC).orElseThrow())
                : null;
        if (id == null) {
            return null;
        }
        return new SavedProjection(
                id,
                readPlot(tag.getCompoundOrEmpty("Plot")),
                readPhysicsVector(tag.getCompoundOrEmpty("BodyToPlotOrigin")),
                readPose(tag.getCompoundOrEmpty("Pose"))
        );
    }

    private static CompoundTag writeProjection(SavedProjection projection) {
        CompoundTag tag = new CompoundTag();
        tag.store("AssemblyId", net.minecraft.core.UUIDUtil.CODEC, projection.id().value());
        tag.put("Plot", writePlot(projection.plot()));
        tag.put("BodyToPlotOrigin", writePhysicsVector(projection.bodyToPlotOrigin()));
        tag.put("Pose", writePose(projection.pose()));
        return tag;
    }

    private static CompoundTag writePlot(AssemblyPlot plot) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("id", plot.id().value());
        tag.putInt("origin_chunk_x", plot.originChunk().x);
        tag.putInt("origin_chunk_z", plot.originChunk().z);
        tag.putInt("section_y", plot.sectionY());
        tag.putInt("chunk_span", plot.chunkSpan());
        tag.putInt("section_span", plot.sectionSpan());
        return tag;
    }

    private static AssemblyPlot readPlot(CompoundTag tag) {
        return AssemblyPlot.sections(
                new AssemblyPlotId(tag.getLongOr("id", 0L)),
                new net.minecraft.world.level.ChunkPos(tag.getIntOr("origin_chunk_x", 0), tag.getIntOr("origin_chunk_z", 0)),
                tag.getIntOr("section_y", 0),
                tag.getIntOr("chunk_span", 0),
                tag.getIntOr("section_span", 0)
        );
    }

    private static CompoundTag writePose(PhysicsPose pose) {
        CompoundTag tag = new CompoundTag();
        tag.put("position", writePhysicsVector(pose.position()));
        tag.put("rotation", writeQuaternion(pose.rotation()));
        return tag;
    }

    private static PhysicsPose readPose(CompoundTag tag) {
        return new PhysicsPose(
                readPhysicsVector(tag.getCompoundOrEmpty("position")),
                readQuaternion(tag.getCompoundOrEmpty("rotation"))
        );
    }

    private static CompoundTag writePhysicsVector(PhysicsVector vector) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("x", vector.x());
        tag.putDouble("y", vector.y());
        tag.putDouble("z", vector.z());
        return tag;
    }

    private static PhysicsVector readPhysicsVector(CompoundTag tag) {
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

    private static CompoundTag writeVector(Vec3 vector) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("x", vector.x);
        tag.putDouble("y", vector.y);
        tag.putDouble("z", vector.z);
        return tag;
    }

    private static Vec3 readVector(CompoundTag tag) {
        return new Vec3(tag.getDoubleOr("x", 0.0D), tag.getDoubleOr("y", 0.0D), tag.getDoubleOr("z", 0.0D));
    }

    private record TrackingPoint(
            boolean inAssembly,
            @Nullable AssemblyId assemblyId,
            @Nullable GlobalSavedAssemblyPointer lastSavedAssemblyPointer,
            Vec3 point,
            @Nullable Vec3 globalPlaceholderPosition,
            @Nullable SavedProjection savedProjection
    ) {
        private TrackingPoint {
            Objects.requireNonNull(point, "point");
        }
    }

    private record SavedProjection(
            AssemblyId id,
            AssemblyPlot plot,
            PhysicsVector bodyToPlotOrigin,
            PhysicsPose pose
    ) {
        private SavedProjection {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(plot, "plot");
            Objects.requireNonNull(bodyToPlotOrigin, "bodyToPlotOrigin");
            Objects.requireNonNull(pose, "pose");
        }

        private static SavedProjection from(AssemblyPlotProjection projection) {
            return new SavedProjection(
                    projection.id(),
                    projection.plot(),
                    projection.bodyToPlotOrigin(),
                    projection.poseFrame().currentPose()
            );
        }

        private AssemblyPlotProjection toProjection() {
            return new AssemblyPlotProjection(
                    id,
                    plot,
                    bodyToPlotOrigin,
                    AssemblyPoseFrame.initial(id, pose)
            );
        }

        private boolean finite() {
            PhysicsVector position = pose.position();
            PhysicsQuaternion rotation = pose.rotation();
            return AssemblyTrackingPointSavedData.finite(bodyToPlotOrigin.x())
                    && AssemblyTrackingPointSavedData.finite(bodyToPlotOrigin.y())
                    && AssemblyTrackingPointSavedData.finite(bodyToPlotOrigin.z())
                    && AssemblyTrackingPointSavedData.finite(position.x())
                    && AssemblyTrackingPointSavedData.finite(position.y())
                    && AssemblyTrackingPointSavedData.finite(position.z())
                    && AssemblyTrackingPointSavedData.finite(rotation.x())
                    && AssemblyTrackingPointSavedData.finite(rotation.y())
                    && AssemblyTrackingPointSavedData.finite(rotation.z())
                    && AssemblyTrackingPointSavedData.finite(rotation.w());
        }
    }

    public record TakenLoginPoint(
            Vec3 position,
            @Nullable AssemblyId assemblyId,
            @Nullable Vec3 localAnchor
    ) {
        public TakenLoginPoint {
            Objects.requireNonNull(position, "position");
        }
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }
}
