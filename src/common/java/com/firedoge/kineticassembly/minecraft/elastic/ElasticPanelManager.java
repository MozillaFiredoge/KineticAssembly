package com.firedoge.kineticassembly.minecraft.elastic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.mechanics.MechanicsBodySnapshot;
import com.firedoge.kineticassembly.mechanics.MechanicsWorld;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class ElasticPanelManager {
    public static final ElasticPanelManager INSTANCE = new ElasticPanelManager();

    private static final double PANEL_THICKNESS = 0.125D;
    private static final double DEFAULT_DAMPING = 0.72D;
    private static final int ACTIVATION_SIGNAL = 8;
    private static final int BLOCK_UPDATE_FLAGS = 3;
    private static final String VISUAL_TAG = "kinetic_assembly_elastic_panel";
    private static final BlockState PANEL_BLOCK = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState();
    private static final BlockState OUTPUT_BLOCK = Blocks.REDSTONE_BLOCK.defaultBlockState();

    private final Map<ResourceKey<Level>, Map<UUID, ElasticPanel>> panels = new LinkedHashMap<>();

    private ElasticPanelManager() {
    }

    public synchronized ElasticPanelSnapshot spawnPanel(
            ServerLevel level,
            Vec3 center,
            double width,
            double depth,
            double stiffness,
            double maxDeflection,
            BlockPos outputPos
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(center, "center");
        if (width < 1.0D || depth < 1.0D) {
            throw new IllegalArgumentException("Panel width and depth must be at least 1 block");
        }
        if (stiffness <= 0.0D) {
            throw new IllegalArgumentException("Panel stiffness must be positive");
        }
        if (maxDeflection <= 0.0D) {
            throw new IllegalArgumentException("Panel max deflection must be positive");
        }

        BlockPos resolvedOutput = outputPos == null
                ? BlockPos.containing(center.x() + width * 0.5D + 1.0D, center.y(), center.z())
                : outputPos;
        if (!level.getBlockState(resolvedOutput).isAir()) {
            throw new IllegalArgumentException("Elastic panel output position must be air: " + formatBlockPos(resolvedOutput));
        }

        UUID id = UUID.randomUUID();
        Display.BlockDisplay visual = createVisual(level, id, center.x(), center.y() - PANEL_THICKNESS * 0.5D, center.z(), width, depth);
        if (!level.addFreshEntity(visual)) {
            throw new IllegalStateException("Failed to spawn elastic panel display");
        }

        ElasticPanel panel = new ElasticPanel(
                id,
                level.dimension(),
                center.x(),
                center.y(),
                center.z(),
                width,
                depth,
                stiffness,
                maxDeflection,
                resolvedOutput,
                visual.getUUID()
        );
        panels.computeIfAbsent(level.dimension(), ignored -> new LinkedHashMap<>()).put(id, panel);
        return panel.snapshot();
    }

    public synchronized void tick(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        for (ServerLevel level : server.getAllLevels()) {
            Map<UUID, ElasticPanel> levelPanels = panels.get(level.dimension());
            if (levelPanels == null || levelPanels.isEmpty()) {
                continue;
            }
            for (ElasticPanel panel : List.copyOf(levelPanels.values())) {
                tickPanel(level, panel);
            }
        }
    }

    public synchronized List<ElasticPanelSnapshot> snapshots(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        Map<UUID, ElasticPanel> levelPanels = panels.get(level.dimension());
        if (levelPanels == null || levelPanels.isEmpty()) {
            return List.of();
        }
        return levelPanels.values().stream()
                .map(ElasticPanel::snapshot)
                .toList();
    }

    public synchronized Optional<ElasticPanelSnapshot> remove(ServerLevel level, UUID id) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        Map<UUID, ElasticPanel> levelPanels = panels.get(level.dimension());
        if (levelPanels == null) {
            return Optional.empty();
        }
        ElasticPanel panel = levelPanels.remove(id);
        if (panel == null) {
            return Optional.empty();
        }
        ElasticPanelSnapshot snapshot = panel.snapshot();
        discardPanel(level, panel);
        if (levelPanels.isEmpty()) {
            panels.remove(level.dimension());
        }
        return Optional.of(snapshot);
    }

    public synchronized int forgetLevel(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        Map<UUID, ElasticPanel> removed = panels.remove(level.dimension());
        if (removed == null || removed.isEmpty()) {
            return 0;
        }
        for (ElasticPanel panel : removed.values()) {
            discardPanel(level, panel);
        }
        return removed.size();
    }

    public synchronized void close(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        for (ServerLevel level : server.getAllLevels()) {
            forgetLevel(level);
        }
        panels.clear();
    }

    private void tickPanel(ServerLevel level, ElasticPanel panel) {
        ensureVisual(level, panel);
        double load = applyEntityLoads(level, panel) + applyMechanicsLoads(level, panel);
        panel.lastLoad = load;

        double targetDeflection = clamp(load / panel.stiffness, 0.0D, panel.maxDeflection);
        double acceleration = (targetDeflection - panel.deflection) * 0.35D;
        panel.velocity = (panel.velocity + acceleration) * DEFAULT_DAMPING;
        panel.deflection = clamp(panel.deflection + panel.velocity, 0.0D, panel.maxDeflection);
        if (panel.deflection == 0.0D && panel.velocity < 0.0D) {
            panel.velocity = 0.0D;
        }

        panel.signal = (int) Math.round((panel.deflection / panel.maxDeflection) * 15.0D);
        updateOutput(level, panel);
        syncVisual(level, panel);
    }

    private double applyEntityLoads(ServerLevel level, ElasticPanel panel) {
        AABB bounds = panel.loadBounds();
        double load = 0.0D;
        for (Entity entity : level.getEntities((Entity) null, bounds, entity -> isLoadEntity(entity))) {
            AABB entityBounds = entity.getBoundingBox();
            if (!panel.overlaps(entityBounds.minX, entityBounds.maxX, entityBounds.minZ, entityBounds.maxZ)) {
                continue;
            }
            double surfaceY = panel.surfaceY();
            if (entityBounds.minY < surfaceY - 0.6D || entityBounds.minY > panel.topY + 1.2D) {
                continue;
            }
            Vec3 velocity = entity.getDeltaMovement();
            double impact = Math.min(3.0D, Math.max(0.0D, -velocity.y * 2.0D));
            load += entityLoad(entity) * (1.0D + impact);
            applyEntityResponse(entity, velocity, panel.deflection);
        }
        return load;
    }

    private double applyMechanicsLoads(ServerLevel level, ElasticPanel panel) {
        Optional<MechanicsWorld> maybeWorld = KineticAssembly.api().existingWorld(level);
        if (maybeWorld.isEmpty()) {
            return 0.0D;
        }

        MechanicsWorld world = maybeWorld.get();
        double load = 0.0D;
        for (MechanicsBodySnapshot body : world.snapshots()) {
            if (body.closed()) {
                continue;
            }
            PhysicsVector position = body.pose().position();
            PhysicsVector halfExtents = body.halfExtents();
            if (!panel.overlaps(
                    position.x() - halfExtents.x(),
                    position.x() + halfExtents.x(),
                    position.z() - halfExtents.z(),
                    position.z() + halfExtents.z()
            )) {
                continue;
            }
            double bottomY = position.y() - halfExtents.y();
            if (bottomY < panel.surfaceY() - 0.6D || bottomY > panel.topY + 1.2D) {
                continue;
            }

            PhysicsVector velocity = body.linearVelocity();
            double impact = Math.min(3.0D, Math.max(0.0D, -velocity.y() * 2.0D));
            load += Math.max(0.1D, body.mass()) * (1.0D + impact);
            if (velocity.y() < -0.08D) {
                world.setLinearVelocity(body.id(), new PhysicsVector(
                        velocity.x(),
                        Math.max(-velocity.y() * 0.25D, 0.12D + panel.deflection * 1.4D),
                        velocity.z()
                ));
            }
        }
        return load;
    }

    private static boolean isLoadEntity(Entity entity) {
        return !entity.isRemoved()
                && !entity.isSpectator()
                && !(entity instanceof Display);
    }

    private static double entityLoad(Entity entity) {
        if (entity instanceof ServerPlayer) {
            return 1.0D;
        }
        if (entity instanceof LivingEntity) {
            return 1.2D;
        }
        return 0.25D;
    }

    private static void applyEntityResponse(Entity entity, Vec3 velocity, double deflection) {
        if (velocity.y >= -0.08D) {
            entity.fallDistance = 0.0F;
            return;
        }
        double bounce = Math.min(0.9D, 0.10D + deflection * 1.6D);
        entity.setDeltaMovement(velocity.x, Math.max(-velocity.y * 0.2D, bounce), velocity.z);
        entity.fallDistance = 0.0F;
    }

    private void updateOutput(ServerLevel level, ElasticPanel panel) {
        boolean shouldPower = panel.signal >= ACTIVATION_SIGNAL;
        BlockState current = level.getBlockState(panel.outputPos);
        panel.outputBlocked = false;

        if (shouldPower) {
            if (current.isAir()) {
                level.setBlock(panel.outputPos, OUTPUT_BLOCK, BLOCK_UPDATE_FLAGS);
                panel.outputPlaced = true;
                panel.powered = true;
            } else if (panel.outputPlaced && current.is(Blocks.REDSTONE_BLOCK)) {
                panel.powered = true;
            } else {
                panel.outputBlocked = true;
                panel.powered = false;
            }
            return;
        }

        if (panel.outputPlaced && current.is(Blocks.REDSTONE_BLOCK)) {
            level.setBlock(panel.outputPos, Blocks.AIR.defaultBlockState(), BLOCK_UPDATE_FLAGS);
        }
        panel.outputPlaced = false;
        panel.powered = false;
    }

    private void syncVisual(ServerLevel level, ElasticPanel panel) {
        Entity entity = level.getEntity(panel.visualEntityId);
        if (!(entity instanceof Display.BlockDisplay visual) || entity.isRemoved()) {
            return;
        }
        visual.setPos(panel.centerX, panel.topY - panel.deflection - PANEL_THICKNESS * 0.5D, panel.centerZ);
    }

    private void ensureVisual(ServerLevel level, ElasticPanel panel) {
        Entity existing = level.getEntity(panel.visualEntityId);
        if (existing instanceof Display.BlockDisplay && !existing.isRemoved()) {
            return;
        }
        Display.BlockDisplay replacement = createVisual(
                level,
                panel.id,
                panel.centerX,
                panel.topY - panel.deflection - PANEL_THICKNESS * 0.5D,
                panel.centerZ,
                panel.width,
                panel.depth
        );
        if (level.addFreshEntity(replacement)) {
            panel.visualEntityId = replacement.getUUID();
        }
    }

    private void discardPanel(ServerLevel level, ElasticPanel panel) {
        Entity entity = level.getEntity(panel.visualEntityId);
        if (entity != null && !entity.isRemoved()) {
            entity.discard();
        }
        if (panel.outputPlaced && level.getBlockState(panel.outputPos).is(Blocks.REDSTONE_BLOCK)) {
            level.setBlock(panel.outputPos, Blocks.AIR.defaultBlockState(), BLOCK_UPDATE_FLAGS);
        }
        panel.outputPlaced = false;
        panel.powered = false;
    }

    private static Display.BlockDisplay createVisual(
            ServerLevel level,
            UUID panelId,
            double centerX,
            double centerY,
            double centerZ,
            double width,
            double depth
    ) {
        Display.BlockDisplay entity = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
        entity.setNoGravity(true);
        entity.setInvulnerable(true);
        entity.setCustomName(net.minecraft.network.chat.Component.literal("Elastic " + panelId.toString().substring(0, 8)));
        entity.setCustomNameVisible(false);
        entity.addTag(VISUAL_TAG);
        entity.addTag(VISUAL_TAG + "_" + panelId);
        applyVisualState(entity, centerX, centerY, centerZ, width, depth);
        return entity;
    }

    private static void applyVisualState(Display.BlockDisplay entity, double centerX, double centerY, double centerZ, double width, double depth) {
        CompoundTag tag = new CompoundTag();
        tag.put("Pos", doubleList(centerX, centerY, centerZ));
        tag.put("Motion", doubleList(0.0D, 0.0D, 0.0D));
        tag.put("Rotation", floatList(0.0F, 0.0F));
        tag.putBoolean("NoGravity", true);
        tag.putBoolean("Invulnerable", true);
        tag.put("block_state", NbtUtils.writeBlockState(PANEL_BLOCK));
        tag.putFloat("width", (float) width);
        tag.putFloat("height", (float) PANEL_THICKNESS);
        tag.putFloat("view_range", 64.0F);
        tag.putFloat("shadow_radius", 0.1F);
        tag.putFloat("shadow_strength", 0.25F);
        tag.putInt("interpolation_duration", 1);
        tag.putInt("teleport_duration", 1);
        Transformation.EXTENDED_CODEC
                .encodeStart(NbtOps.INSTANCE, panelTransformation(width, depth))
                .resultOrPartial(message -> KineticAssembly.LOGGER.warn("Failed to encode elastic panel transform: {}", message))
                .ifPresent(transformation -> tag.put("transformation", transformation));
        entity.load(tag);
    }

    private static Transformation panelTransformation(double width, double depth) {
        return new Transformation(
                new Vector3f((float) (-width * 0.5D), (float) (-PANEL_THICKNESS * 0.5D), (float) (-depth * 0.5D)),
                new Quaternionf(),
                new Vector3f((float) width, (float) PANEL_THICKNESS, (float) depth),
                new Quaternionf()
        );
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

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String formatBlockPos(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    private static final class ElasticPanel {
        private final UUID id;
        private final ResourceKey<Level> levelKey;
        private final double centerX;
        private final double topY;
        private final double centerZ;
        private final double width;
        private final double depth;
        private final double stiffness;
        private final double maxDeflection;
        private final BlockPos outputPos;
        private UUID visualEntityId;
        private double deflection;
        private double velocity;
        private double lastLoad;
        private int signal;
        private boolean powered;
        private boolean outputPlaced;
        private boolean outputBlocked;

        private ElasticPanel(
                UUID id,
                ResourceKey<Level> levelKey,
                double centerX,
                double topY,
                double centerZ,
                double width,
                double depth,
                double stiffness,
                double maxDeflection,
                BlockPos outputPos,
                UUID visualEntityId
        ) {
            this.id = id;
            this.levelKey = levelKey;
            this.centerX = centerX;
            this.topY = topY;
            this.centerZ = centerZ;
            this.width = width;
            this.depth = depth;
            this.stiffness = stiffness;
            this.maxDeflection = maxDeflection;
            this.outputPos = outputPos.immutable();
            this.visualEntityId = visualEntityId;
        }

        private AABB loadBounds() {
            return new AABB(
                    centerX - width * 0.5D,
                    topY - maxDeflection - 0.75D,
                    centerZ - depth * 0.5D,
                    centerX + width * 0.5D,
                    topY + 1.5D,
                    centerZ + depth * 0.5D
            );
        }

        private boolean overlaps(double minX, double maxX, double minZ, double maxZ) {
            return maxX >= centerX - width * 0.5D
                    && minX <= centerX + width * 0.5D
                    && maxZ >= centerZ - depth * 0.5D
                    && minZ <= centerZ + depth * 0.5D;
        }

        private double surfaceY() {
            return topY - deflection;
        }

        private ElasticPanelSnapshot snapshot() {
            return new ElasticPanelSnapshot(
                    id,
                    levelKey,
                    centerX,
                    topY,
                    centerZ,
                    width,
                    depth,
                    stiffness,
                    maxDeflection,
                    deflection,
                    lastLoad,
                    signal,
                    powered,
                    outputBlocked,
                    outputPos,
                    visualEntityId
            );
        }
    }
}
