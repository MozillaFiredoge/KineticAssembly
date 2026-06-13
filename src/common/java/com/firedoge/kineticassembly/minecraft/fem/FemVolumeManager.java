package com.firedoge.kineticassembly.minecraft.fem;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.api.DeformableVolumeDefinition;
import com.firedoge.kineticassembly.api.PhysicsDeformableVolume;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.minecraft.scene.PhysicsObjectId;
import com.firedoge.kineticassembly.minecraft.scene.ServerPhysicsRuntime;
import com.firedoge.kineticassembly.minecraft.scene.ServerPhysicsScene;
import com.mojang.math.Transformation;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class FemVolumeManager {
    public static final FemVolumeManager INSTANCE = new FemVolumeManager();

    private static final float DEFAULT_DENSITY = 100.0F;
    private static final float DEFAULT_YOUNGS = 200_000.0F;
    private static final float DEFAULT_POISSONS = 0.30F;
    private static final float DEFAULT_DYNAMIC_FRICTION = 0.10F;
    private static final float DEFAULT_DAMPING = 0.08F;
    private static final int DEFAULT_VOXELS = 5;
    private static final int TERRAIN_CHUNK_RADIUS = 1;
    private static final int MAX_MARKERS = 96;
    private static final double MARKER_SIZE = 0.08D;
    private static final String MARKER_TAG = "kinetic_assembly_fem_volume_marker";
    private static final String BLOCK_SHELL_TAG = "kinetic_assembly_fem_block_shell";
    private static final MethodHandle DISPLAY_SET_TRANSFORMATION = findDisplaySetTransformation();
    private static final BlockState PANEL_MARKER_BLOCK = Blocks.CYAN_STAINED_GLASS.defaultBlockState();
    private static final BlockState BLOCK_MARKER_BLOCK = Blocks.YELLOW_STAINED_GLASS.defaultBlockState();
    private static final BlockState BLOCK_SHELL_BLOCK = Blocks.SLIME_BLOCK.defaultBlockState();

    private final Map<ResourceKey<Level>, Map<PhysicsObjectId, FemVolume>> volumes = new LinkedHashMap<>();

    private FemVolumeManager() {
    }

    public synchronized FemVolumeSnapshot spawnPanel(ServerLevel level, Vec3 center, float width, float depth, float thickness, float density, float youngs, int voxels) {
        return spawnVolume(level, FemVolumeKind.PANEL, center, new PhysicsVector(width, thickness, depth), density, youngs, voxels);
    }

    public synchronized FemVolumeSnapshot spawnBlock(ServerLevel level, Vec3 center, float size, float density, float youngs, int voxels) {
        return spawnVolume(level, FemVolumeKind.BLOCK, center, new PhysicsVector(size, size, size), density, youngs, voxels);
    }

    public synchronized void tick(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        for (ServerLevel level : server.getAllLevels()) {
            Map<PhysicsObjectId, FemVolume> levelVolumes = volumes.get(level.dimension());
            if (levelVolumes == null || levelVolumes.isEmpty()) {
                continue;
            }
            for (FemVolume volume : List.copyOf(levelVolumes.values())) {
                syncVisuals(level, volume);
            }
        }
    }

    public synchronized List<FemVolumeSnapshot> snapshots(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        Map<PhysicsObjectId, FemVolume> levelVolumes = volumes.get(level.dimension());
        if (levelVolumes == null || levelVolumes.isEmpty()) {
            return List.of();
        }
        return levelVolumes.values().stream()
                .map(FemVolume::snapshot)
                .toList();
    }

    public synchronized Optional<FemVolumeSnapshot> find(ServerLevel level, String idPrefix) {
        String normalized = idPrefix.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return snapshots(level).stream()
                .filter(snapshot -> snapshot.id().toString().toLowerCase(Locale.ROOT).startsWith(normalized))
                .findFirst();
    }

    public synchronized Optional<FemVolumeSnapshot> remove(ServerLevel level, PhysicsObjectId id) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        Map<PhysicsObjectId, FemVolume> levelVolumes = volumes.get(level.dimension());
        if (levelVolumes == null) {
            return Optional.empty();
        }
        FemVolume volume = levelVolumes.remove(id);
        if (volume == null) {
            return Optional.empty();
        }
        FemVolumeSnapshot snapshot = volume.snapshot();
        discardVolume(level, volume);
        if (levelVolumes.isEmpty()) {
            volumes.remove(level.dimension());
        }
        return Optional.of(snapshot);
    }

    public synchronized int forgetLevel(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        Map<PhysicsObjectId, FemVolume> removed = volumes.remove(level.dimension());
        if (removed == null || removed.isEmpty()) {
            return 0;
        }
        for (FemVolume volume : removed.values()) {
            discardVolume(level, volume);
        }
        return removed.size();
    }

    public synchronized void close(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        for (ServerLevel level : server.getAllLevels()) {
            forgetLevel(level);
        }
        volumes.clear();
    }

    public static float defaultDensity() {
        return DEFAULT_DENSITY;
    }

    public static float defaultYoungs() {
        return DEFAULT_YOUNGS;
    }

    public static int defaultVoxels() {
        return DEFAULT_VOXELS;
    }

    private FemVolumeSnapshot spawnVolume(ServerLevel level, FemVolumeKind kind, Vec3 center, PhysicsVector dimensions, float density, float youngs, int voxels) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(kind, "kind");
        ServerPhysicsRuntime.INSTANCE.buildTerrainCollisionAround(level, BlockPos.containing(center), TERRAIN_CHUNK_RADIUS);
        ServerPhysicsScene scene = ServerPhysicsRuntime.INSTANCE.sceneFor(level);
        PhysicsVector physicsCenter = new PhysicsVector(center.x(), center.y(), center.z());
        float maxEdgeLength = (float) Math.max(0.05D, Math.min(Math.min(dimensions.x(), dimensions.y()), dimensions.z()) * 0.75D);
        DeformableVolumeDefinition definition = new DeformableVolumeDefinition(
                physicsCenter,
                dimensions,
                density,
                youngs,
                DEFAULT_POISSONS,
                DEFAULT_DYNAMIC_FRICTION,
                DEFAULT_DAMPING,
                maxEdgeLength,
                voxels
        );
        PhysicsObjectId id = scene.createDeformableVolumeBox(definition);
        PhysicsDeformableVolume deformableVolume = scene.deformableVolume(id)
                .orElseThrow(() -> new IllegalStateException("Created FEM volume did not resolve in scene"));
        FemVolume volume = new FemVolume(id, level.dimension(), kind, deformableVolume, physicsCenter, dimensions, density, youngs, voxels);
        volumes.computeIfAbsent(level.dimension(), ignored -> new LinkedHashMap<>()).put(id, volume);
        syncVisuals(level, volume);
        return volume.snapshot();
    }

    private void syncVisuals(ServerLevel level, FemVolume volume) {
        if (volume.deformableVolume.isClosed()) {
            remove(level, volume.id);
            return;
        }
        double[] vertices = volume.deformableVolume.collisionVertexPositions();
        int vertexCount = vertices.length / 3;
        if (vertexCount == 0) {
            return;
        }
        volume.updateDeformation(vertices);
        if (volume.kind == FemVolumeKind.BLOCK) {
            syncBlockShell(level, volume);
        }
        ensureMarkers(level, volume, vertexCount);
        for (int i = 0; i < volume.markerEntityIds.size(); i++) {
            int vertexIndex = sampledVertexIndex(i, volume.markerEntityIds.size(), vertexCount);
            Entity entity = level.getEntity(volume.markerEntityIds.get(i));
            if (entity == null || entity.isRemoved()) {
                continue;
            }
            int offset = vertexIndex * 3;
            entity.setPos(vertices[offset], vertices[offset + 1], vertices[offset + 2]);
        }
    }

    private void syncBlockShell(ServerLevel level, FemVolume volume) {
        if (volume.blockShellPose == null) {
            return;
        }
        Display.BlockDisplay shell = ensureBlockShell(level, volume);
        if (shell == null) {
            return;
        }
        BlockShellPose pose = volume.blockShellPose;
        shell.setPos(pose.center().x(), pose.center().y(), pose.center().z());
        setDisplayTransformation(shell, blockShellTransformation(pose));
    }

    private Display.BlockDisplay ensureBlockShell(ServerLevel level, FemVolume volume) {
        if (volume.blockShellEntityId != null) {
            Entity entity = level.getEntity(volume.blockShellEntityId);
            if (entity instanceof Display.BlockDisplay shell && !shell.isRemoved()) {
                return shell;
            }
        }
        Display.BlockDisplay shell = createBlockShell(level, volume.id);
        if (!level.addFreshEntity(shell)) {
            return null;
        }
        volume.blockShellEntityId = shell.getUUID();
        return shell;
    }

    private void ensureMarkers(ServerLevel level, FemVolume volume, int vertexCount) {
        int desiredCount = Math.min(vertexCount, MAX_MARKERS);
        while (volume.markerEntityIds.size() < desiredCount) {
            Display.BlockDisplay marker = createMarker(level, volume.kind, volume.id);
            if (!level.addFreshEntity(marker)) {
                return;
            }
            volume.markerEntityIds.add(marker.getUUID());
        }
        for (int i = 0; i < volume.markerEntityIds.size(); i++) {
            Entity entity = level.getEntity(volume.markerEntityIds.get(i));
            if (entity instanceof Display.BlockDisplay && !entity.isRemoved()) {
                continue;
            }
            Display.BlockDisplay replacement = createMarker(level, volume.kind, volume.id);
            if (level.addFreshEntity(replacement)) {
                volume.markerEntityIds.set(i, replacement.getUUID());
            }
        }
    }

    private void discardVolume(ServerLevel level, FemVolume volume) {
        for (UUID markerEntityId : volume.markerEntityIds) {
            Entity entity = level.getEntity(markerEntityId);
            if (entity != null && !entity.isRemoved()) {
                entity.discard();
            }
        }
        if (volume.blockShellEntityId != null) {
            Entity entity = level.getEntity(volume.blockShellEntityId);
            if (entity != null && !entity.isRemoved()) {
                entity.discard();
            }
        }
        ServerPhysicsRuntime.INSTANCE.existingScene(level)
                .ifPresent(scene -> scene.removeDeformableVolume(volume.id));
        if (!volume.deformableVolume.isClosed()) {
            volume.deformableVolume.close();
        }
    }

    private static int sampledVertexIndex(int sample, int sampleCount, int vertexCount) {
        if (sampleCount <= 1) {
            return 0;
        }
        return Math.min(vertexCount - 1, (int) Math.floor((sample / (double) sampleCount) * vertexCount));
    }

    private static Display.BlockDisplay createMarker(ServerLevel level, FemVolumeKind kind, PhysicsObjectId id) {
        Display.BlockDisplay entity = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
        applyMarkerState(entity, kind == FemVolumeKind.PANEL ? PANEL_MARKER_BLOCK : BLOCK_MARKER_BLOCK);
        entity.setNoGravity(true);
        entity.setInvulnerable(true);
        entity.setCustomName(net.minecraft.network.chat.Component.literal("FEM " + id.toString().substring(0, 8)));
        entity.setCustomNameVisible(false);
        entity.addTag(MARKER_TAG);
        entity.addTag(MARKER_TAG + "_" + id);
        return entity;
    }

    private static Display.BlockDisplay createBlockShell(ServerLevel level, PhysicsObjectId id) {
        Display.BlockDisplay entity = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
        applyBlockShellState(entity);
        entity.setNoGravity(true);
        entity.setInvulnerable(true);
        entity.setCustomName(net.minecraft.network.chat.Component.literal("FEM block " + id.toString().substring(0, 8)));
        entity.setCustomNameVisible(false);
        entity.addTag(BLOCK_SHELL_TAG);
        entity.addTag(BLOCK_SHELL_TAG + "_" + id);
        return entity;
    }

    private static void applyMarkerState(Display.BlockDisplay entity, BlockState displayState) {
        CompoundTag tag = new CompoundTag();
        tag.put("Pos", doubleList(0.0D, 0.0D, 0.0D));
        tag.put("Motion", doubleList(0.0D, 0.0D, 0.0D));
        tag.put("Rotation", floatList(0.0F, 0.0F));
        tag.putBoolean("NoGravity", true);
        tag.putBoolean("Invulnerable", true);
        tag.put("block_state", NbtUtils.writeBlockState(displayState));
        tag.putFloat("width", (float) MARKER_SIZE);
        tag.putFloat("height", (float) MARKER_SIZE);
        tag.putFloat("view_range", 64.0F);
        tag.putFloat("shadow_radius", 0.0F);
        tag.putFloat("shadow_strength", 0.0F);
        tag.putInt("interpolation_duration", 1);
        tag.putInt("teleport_duration", 1);
        Transformation.EXTENDED_CODEC
                .encodeStart(NbtOps.INSTANCE, markerTransformation())
                .resultOrPartial(message -> KineticAssembly.LOGGER.warn("Failed to encode FEM marker transform: {}", message))
                .ifPresent(transformation -> tag.put("transformation", transformation));
        entity.load(tag);
    }

    private static void applyBlockShellState(Display.BlockDisplay entity) {
        CompoundTag tag = new CompoundTag();
        tag.put("Pos", doubleList(0.0D, 0.0D, 0.0D));
        tag.put("Motion", doubleList(0.0D, 0.0D, 0.0D));
        tag.put("Rotation", floatList(0.0F, 0.0F));
        tag.putBoolean("NoGravity", true);
        tag.putBoolean("Invulnerable", true);
        tag.put("block_state", NbtUtils.writeBlockState(BLOCK_SHELL_BLOCK));
        tag.putFloat("width", 16.0F);
        tag.putFloat("height", 16.0F);
        tag.putFloat("view_range", 96.0F);
        tag.putFloat("shadow_radius", 0.2F);
        tag.putFloat("shadow_strength", 0.35F);
        tag.putInt("interpolation_duration", 1);
        tag.putInt("teleport_duration", 1);
        Transformation.EXTENDED_CODEC
                .encodeStart(NbtOps.INSTANCE, blockShellTransformation(BlockShellPose.unit()))
                .resultOrPartial(message -> KineticAssembly.LOGGER.warn("Failed to encode FEM block shell transform: {}", message))
                .ifPresent(transformation -> tag.put("transformation", transformation));
        entity.load(tag);
    }

    private static Transformation markerTransformation() {
        return new Transformation(
                new Vector3f((float) (-MARKER_SIZE * 0.5D), (float) (-MARKER_SIZE * 0.5D), (float) (-MARKER_SIZE * 0.5D)),
                new Quaternionf(),
                new Vector3f((float) MARKER_SIZE, (float) MARKER_SIZE, (float) MARKER_SIZE),
                new Quaternionf()
        );
    }

    private static Transformation blockShellTransformation(BlockShellPose pose) {
        Quaternionf rotation = new Quaternionf(pose.rotation());
        Vector3f halfSize = new Vector3f(
                (float) (pose.sizeX() * 0.5D),
                (float) (pose.sizeY() * 0.5D),
                (float) (pose.sizeZ() * 0.5D)
        );
        Vector3f translation = halfSize.rotate(new Quaternionf(rotation)).negate();
        return new Transformation(
                translation,
                rotation,
                new Vector3f((float) pose.sizeX(), (float) pose.sizeY(), (float) pose.sizeZ()),
                new Quaternionf()
        );
    }

    private static void setDisplayTransformation(Display.BlockDisplay entity, Transformation transformation) {
        if (DISPLAY_SET_TRANSFORMATION == null) {
            return;
        }
        try {
            DISPLAY_SET_TRANSFORMATION.invoke(entity, transformation);
        } catch (Throwable ignored) {
            // Position sync still keeps the shell approximately useful.
        }
    }

    private static MethodHandle findDisplaySetTransformation() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Display.class, MethodHandles.lookup());
            return lookup.findVirtual(Display.class, "setTransformation", MethodType.methodType(void.class, Transformation.class));
        } catch (ReflectiveOperationException exception) {
            KineticAssembly.LOGGER.warn("Display#setTransformation is unavailable; FEM block shells will only sync position", exception);
            return null;
        }
    }

    private static Bounds boundsOf(double[] vertices) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int offset = 0; offset + 2 < vertices.length; offset += 3) {
            double x = vertices[offset];
            double y = vertices[offset + 1];
            double z = vertices[offset + 2];
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        if (!Double.isFinite(minX) || !Double.isFinite(maxX)) {
            return Bounds.unit();
        }
        return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static BlockShellPose fitBlockShellPose(double[] restVertices, double[] vertices, Bounds restBounds) {
        int count = Math.min(restVertices.length, vertices.length) / 3;
        if (count <= 0 || restBounds == null) {
            return BlockShellPose.unit();
        }

        double restCenterX = restBounds.centerX();
        double restCenterY = restBounds.centerY();
        double restCenterZ = restBounds.centerZ();
        AxisSamples xSamples = new AxisSamples();
        AxisSamples ySamples = new AxisSamples();
        AxisSamples zSamples = new AxisSamples();
        Vector3f centroid = new Vector3f();

        for (int i = 0; i < count; i++) {
            int offset = i * 3;
            double x = vertices[offset];
            double y = vertices[offset + 1];
            double z = vertices[offset + 2];
            centroid.add((float) x, (float) y, (float) z);

            double restX = restVertices[offset] - restCenterX;
            double restY = restVertices[offset + 1] - restCenterY;
            double restZ = restVertices[offset + 2] - restCenterZ;
            xSamples.add(restX >= 0.0D, x, y, z);
            ySamples.add(restY >= 0.0D, x, y, z);
            zSamples.add(restZ >= 0.0D, x, y, z);
        }
        centroid.div((float) count);

        Vector3f xAxis = normalizedOr(xSamples.axis(), new Vector3f(1.0F, 0.0F, 0.0F));
        Vector3f yMeasured = normalizedOr(ySamples.axis(), new Vector3f(0.0F, 1.0F, 0.0F));
        Vector3f zMeasured = normalizedOr(zSamples.axis(), new Vector3f(0.0F, 0.0F, 1.0F));
        Vector3f yAxis = new Vector3f(yMeasured).sub(new Vector3f(xAxis).mul(xAxis.dot(yMeasured)));
        if (yAxis.lengthSquared() < 1.0E-8F) {
            yAxis = perpendicularTo(xAxis);
        } else {
            yAxis.normalize();
        }
        Vector3f zAxis = new Vector3f(xAxis).cross(yAxis).normalize();
        if (zAxis.dot(zMeasured) < 0.0F) {
            yAxis.negate();
            zAxis = new Vector3f(xAxis).cross(yAxis).normalize();
        }

        OrientedSpan span = orientedSpan(vertices, count, centroid, xAxis, yAxis, zAxis);
        Vector3f center = new Vector3f(centroid)
                .add(new Vector3f(xAxis).mul((float) span.midX()))
                .add(new Vector3f(yAxis).mul((float) span.midY()))
                .add(new Vector3f(zAxis).mul((float) span.midZ()));
        return new BlockShellPose(
                center,
                quaternionFromAxes(xAxis, yAxis, zAxis),
                span.sizeX(),
                span.sizeY(),
                span.sizeZ()
        );
    }

    private static OrientedSpan orientedSpan(
            double[] vertices,
            int count,
            Vector3f center,
            Vector3f xAxis,
            Vector3f yAxis,
            Vector3f zAxis
    ) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            int offset = i * 3;
            Vector3f delta = new Vector3f(
                    (float) (vertices[offset] - center.x()),
                    (float) (vertices[offset + 1] - center.y()),
                    (float) (vertices[offset + 2] - center.z())
            );
            double x = delta.dot(xAxis);
            double y = delta.dot(yAxis);
            double z = delta.dot(zAxis);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        return new OrientedSpan(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static Vector3f normalizedOr(Vector3f vector, Vector3f fallback) {
        if (vector.lengthSquared() < 1.0E-8F) {
            return fallback;
        }
        return vector.normalize();
    }

    private static Vector3f perpendicularTo(Vector3f axis) {
        Vector3f candidate = Math.abs(axis.y()) < 0.9F
                ? new Vector3f(0.0F, 1.0F, 0.0F)
                : new Vector3f(1.0F, 0.0F, 0.0F);
        return candidate.sub(new Vector3f(axis).mul(axis.dot(candidate))).normalize();
    }

    private static Quaternionf quaternionFromAxes(Vector3f xAxis, Vector3f yAxis, Vector3f zAxis) {
        float m00 = xAxis.x();
        float m01 = yAxis.x();
        float m02 = zAxis.x();
        float m10 = xAxis.y();
        float m11 = yAxis.y();
        float m12 = zAxis.y();
        float m20 = xAxis.z();
        float m21 = yAxis.z();
        float m22 = zAxis.z();
        float trace = m00 + m11 + m22;
        float x;
        float y;
        float z;
        float w;
        if (trace > 0.0F) {
            float s = (float) Math.sqrt(trace + 1.0F) * 2.0F;
            w = 0.25F * s;
            x = (m21 - m12) / s;
            y = (m02 - m20) / s;
            z = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            float s = (float) Math.sqrt(1.0F + m00 - m11 - m22) * 2.0F;
            w = (m21 - m12) / s;
            x = 0.25F * s;
            y = (m01 + m10) / s;
            z = (m02 + m20) / s;
        } else if (m11 > m22) {
            float s = (float) Math.sqrt(1.0F + m11 - m00 - m22) * 2.0F;
            w = (m02 - m20) / s;
            x = (m01 + m10) / s;
            y = 0.25F * s;
            z = (m12 + m21) / s;
        } else {
            float s = (float) Math.sqrt(1.0F + m22 - m00 - m11) * 2.0F;
            w = (m10 - m01) / s;
            x = (m02 + m20) / s;
            y = (m12 + m21) / s;
            z = 0.25F * s;
        }
        return new Quaternionf(x, y, z, w).normalize();
    }

    private static ListTag doubleList(double first, double second, double third) {
        ListTag list = new ListTag();
        list.addTag(0, DoubleTag.valueOf(first));
        list.addTag(1, DoubleTag.valueOf(second));
        list.addTag(2, DoubleTag.valueOf(third));
        return list;
    }

    private static ListTag floatList(float first, float second) {
        ListTag list = new ListTag();
        list.addTag(0, FloatTag.valueOf(first));
        list.addTag(1, FloatTag.valueOf(second));
        return list;
    }

    private static final class FemVolume {
        private final PhysicsObjectId id;
        private final ResourceKey<Level> levelKey;
        private final FemVolumeKind kind;
        private final PhysicsDeformableVolume deformableVolume;
        private final PhysicsVector center;
        private final PhysicsVector dimensions;
        private final float density;
        private final float youngs;
        private final int voxels;
        private final List<UUID> markerEntityIds = new ArrayList<>();
        private UUID blockShellEntityId;
        private double[] restVertexPositions = new double[0];
        private Bounds restBounds;
        private BlockShellPose blockShellPose;
        private double maxDisplacement;
        private double averageDisplacement;
        private double minScale = 1.0D;
        private double maxScale = 1.0D;
        private double volumeRatio = 1.0D;

        private FemVolume(
                PhysicsObjectId id,
                ResourceKey<Level> levelKey,
                FemVolumeKind kind,
                PhysicsDeformableVolume deformableVolume,
                PhysicsVector center,
                PhysicsVector dimensions,
                float density,
                float youngs,
                int voxels
        ) {
            this.id = id;
            this.levelKey = levelKey;
            this.kind = kind;
            this.deformableVolume = deformableVolume;
            this.center = center;
            this.dimensions = dimensions;
            this.density = density;
            this.youngs = youngs;
            this.voxels = voxels;
        }

        private void updateDeformation(double[] vertices) {
            if (restVertexPositions.length != vertices.length) {
                restVertexPositions = Arrays.copyOf(vertices, vertices.length);
                restBounds = boundsOf(restVertexPositions);
            }

            blockShellPose = fitBlockShellPose(restVertexPositions, vertices, restBounds);
            double displacementSum = 0.0D;
            double largestDisplacement = 0.0D;
            int count = Math.min(vertices.length, restVertexPositions.length) / 3;
            for (int i = 0; i < count; i++) {
                int offset = i * 3;
                double dx = vertices[offset] - restVertexPositions[offset];
                double dy = vertices[offset + 1] - restVertexPositions[offset + 1];
                double dz = vertices[offset + 2] - restVertexPositions[offset + 2];
                double displacement = Math.sqrt(dx * dx + dy * dy + dz * dz);
                displacementSum += displacement;
                largestDisplacement = Math.max(largestDisplacement, displacement);
            }
            maxDisplacement = largestDisplacement;
            averageDisplacement = count == 0 ? 0.0D : displacementSum / count;

            if (restBounds != null && blockShellPose != null) {
                double scaleX = safeRatio(blockShellPose.sizeX(), restBounds.sizeX());
                double scaleY = safeRatio(blockShellPose.sizeY(), restBounds.sizeY());
                double scaleZ = safeRatio(blockShellPose.sizeZ(), restBounds.sizeZ());
                minScale = Math.min(scaleX, Math.min(scaleY, scaleZ));
                maxScale = Math.max(scaleX, Math.max(scaleY, scaleZ));
                volumeRatio = safeRatio(blockShellPose.volume(), restBounds.volume());
            }
        }

        private FemVolumeSnapshot snapshot() {
            return new FemVolumeSnapshot(
                    id,
                    levelKey,
                    kind,
                    center,
                    dimensions,
                    density,
                    youngs,
                    voxels,
                    deformableVolume.collisionVertexCount(),
                    deformableVolume.collisionTetrahedronCount(),
                    deformableVolume.simulationVertexCount(),
                    deformableVolume.simulationTetrahedronCount(),
                    markerEntityIds.size(),
                    maxDisplacement,
                    averageDisplacement,
                    minScale,
                    maxScale,
                    volumeRatio
            );
        }
    }

    private static double safeRatio(double numerator, double denominator) {
        if (denominator <= 1.0E-9D) {
            return 1.0D;
        }
        return numerator / denominator;
    }

    private record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        private static Bounds unit() {
            return new Bounds(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
        }

        private double sizeX() {
            return Math.max(0.01D, maxX - minX);
        }

        private double sizeY() {
            return Math.max(0.01D, maxY - minY);
        }

        private double sizeZ() {
            return Math.max(0.01D, maxZ - minZ);
        }

        private double volume() {
            return sizeX() * sizeY() * sizeZ();
        }

        private double centerX() {
            return (minX + maxX) * 0.5D;
        }

        private double centerY() {
            return (minY + maxY) * 0.5D;
        }

        private double centerZ() {
            return (minZ + maxZ) * 0.5D;
        }
    }

    private record BlockShellPose(Vector3f center, Quaternionf rotation, double rawSizeX, double rawSizeY, double rawSizeZ) {
        private static BlockShellPose unit() {
            return new BlockShellPose(
                    new Vector3f(0.5F, 0.5F, 0.5F),
                    new Quaternionf(),
                    1.0D,
                    1.0D,
                    1.0D
            );
        }

        private double sizeX() {
            return Math.max(0.01D, rawSizeX);
        }

        private double sizeY() {
            return Math.max(0.01D, rawSizeY);
        }

        private double sizeZ() {
            return Math.max(0.01D, rawSizeZ);
        }

        private double volume() {
            return sizeX() * sizeY() * sizeZ();
        }
    }

    private record OrientedSpan(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        private double midX() {
            return (minX + maxX) * 0.5D;
        }

        private double midY() {
            return (minY + maxY) * 0.5D;
        }

        private double midZ() {
            return (minZ + maxZ) * 0.5D;
        }

        private double sizeX() {
            return Math.max(0.01D, maxX - minX);
        }

        private double sizeY() {
            return Math.max(0.01D, maxY - minY);
        }

        private double sizeZ() {
            return Math.max(0.01D, maxZ - minZ);
        }
    }

    private static final class AxisSamples {
        private double positiveX;
        private double positiveY;
        private double positiveZ;
        private double negativeX;
        private double negativeY;
        private double negativeZ;
        private int positiveCount;
        private int negativeCount;

        private void add(boolean positive, double x, double y, double z) {
            if (positive) {
                positiveX += x;
                positiveY += y;
                positiveZ += z;
                positiveCount++;
            } else {
                negativeX += x;
                negativeY += y;
                negativeZ += z;
                negativeCount++;
            }
        }

        private Vector3f axis() {
            if (positiveCount == 0 || negativeCount == 0) {
                return new Vector3f();
            }
            return new Vector3f(
                    (float) ((positiveX / positiveCount) - (negativeX / negativeCount)),
                    (float) ((positiveY / positiveCount) - (negativeY / negativeCount)),
                    (float) ((positiveZ / positiveCount) - (negativeZ / negativeCount))
            );
        }
    }
}
