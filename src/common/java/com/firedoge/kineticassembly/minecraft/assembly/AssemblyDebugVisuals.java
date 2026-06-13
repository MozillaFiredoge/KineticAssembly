package com.firedoge.kineticassembly.minecraft.assembly;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsQuaternion;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.mechanics.MechanicsBodySnapshot;
import com.mojang.math.Transformation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;

final class AssemblyDebugVisuals {
    private static final String VISUAL_NAME = "PhysX assembly debug visual";
    private static final String VISUAL_TAG = "kinetic_assembly_assembly_visual";
    private static final String ASSEMBLY_TAG_PREFIX = "kinetic_assembly_assembly_";
    private static final MethodHandle DISPLAY_SET_TRANSFORMATION = findDisplaySetTransformation();

    private AssemblyDebugVisuals() {
    }

    static void create(ServerLevel level, MechanicsBodySnapshot body, PhysicsAssembly assembly) {
        for (AssemblyBlock block : assembly.blocks()) {
            Display.BlockDisplay entity = createVisualEntity(level, body.pose(), assembly, block);
            if (level.addFreshEntity(entity)) {
                assembly.visuals().add(new PhysicsAssembly.VisualBinding(block, entity.getUUID()));
            }
        }
    }

    static void sync(ServerLevel level, MechanicsBodySnapshot body, PhysicsAssembly assembly) {
        if (assembly.visuals().isEmpty()) {
            return;
        }
        for (PhysicsAssembly.VisualBinding visual : assembly.visuals()) {
            Entity entity = level.getEntity(visual.entityId());
            if (!(entity instanceof Display.BlockDisplay display) || display.isRemoved()) {
                continue;
            }
            syncVisualEntity(display, body.pose(), visual.block());
        }
    }

    static void discard(ServerLevel level, PhysicsAssembly assembly) {
        discardBoundVisuals(level, assembly);
        assembly.visuals().clear();
    }

    static void discard(ServerLevel level, MechanicsBodySnapshot body, PhysicsAssembly assembly) {
        discardBoundVisuals(level, assembly);
        discardTaggedVisuals(level, body, assembly);
        assembly.visuals().clear();
    }

    private static void discardBoundVisuals(ServerLevel level, PhysicsAssembly assembly) {
        for (PhysicsAssembly.VisualBinding visual : assembly.visuals()) {
            Entity entity = level.getEntity(visual.entityId());
            if (entity != null && !entity.isRemoved()) {
                entity.discard();
            }
        }
    }

    private static void discardTaggedVisuals(ServerLevel level, MechanicsBodySnapshot body, PhysicsAssembly assembly) {
        String assemblyTag = assemblyTag(assembly);
        for (Entity entity : level.getEntities((Entity) null, visualSearchBounds(body, assembly), entity ->
                entity instanceof Display.BlockDisplay
                        && (isTaggedAssemblyVisual(entity, assemblyTag) || isLegacyAssemblyVisual(entity)))) {
            if (!entity.isRemoved()) {
                entity.discard();
            }
        }
    }

    private static Display.BlockDisplay createVisualEntity(ServerLevel level, PhysicsPose pose, PhysicsAssembly assembly, AssemblyBlock block) {
        Display.BlockDisplay entity = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
        entity.setNoGravity(true);
        entity.setInvulnerable(true);
        entity.setCustomName(Component.literal(VISUAL_NAME));
        entity.setCustomNameVisible(false);
        applyInitialVisualState(entity, pose, block);
        tagVisualEntity(entity, assembly);
        return entity;
    }

    private static void tagVisualEntity(Display.BlockDisplay entity, PhysicsAssembly assembly) {
        entity.addTag(VISUAL_TAG);
        entity.addTag(assemblyTag(assembly));
    }

    private static String assemblyTag(PhysicsAssembly assembly) {
        return ASSEMBLY_TAG_PREFIX + assembly.id();
    }

    private static boolean isTaggedAssemblyVisual(Entity entity, String assemblyTag) {
        return entity.getTags().contains(VISUAL_TAG) && entity.getTags().contains(assemblyTag);
    }

    private static boolean isLegacyAssemblyVisual(Entity entity) {
        Component name = entity.getCustomName();
        return name != null && VISUAL_NAME.equals(name.getString());
    }

    private static AABB visualSearchBounds(MechanicsBodySnapshot body, PhysicsAssembly assembly) {
        PhysicsVector position = body.pose().position();
        double radius = 16.0D;
        for (AssemblyBlock block : assembly.blocks()) {
            AABB bounds = block.bodyLocalBounds();
            radius = Math.max(radius, distanceFromOrigin(bounds.minX, bounds.minY, bounds.minZ) + 4.0D);
            radius = Math.max(radius, distanceFromOrigin(bounds.maxX, bounds.maxY, bounds.maxZ) + 4.0D);
        }
        return new AABB(
                position.x() - radius,
                position.y() - radius,
                position.z() - radius,
                position.x() + radius,
                position.y() + radius,
                position.z() + radius
        );
    }

    private static double distanceFromOrigin(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static void syncVisualEntity(Display.BlockDisplay entity, PhysicsPose pose, AssemblyBlock block) {
        PhysicsVector position = pose.position();
        entity.setPos(position.x(), position.y(), position.z());
        setDisplayTransformation(entity, visualTransformation(pose, block));
    }

    private static void applyInitialVisualState(Display.BlockDisplay entity, PhysicsPose pose, AssemblyBlock block) {
        CompoundTag tag = new CompoundTag();
        PhysicsVector position = pose.position();
        tag.put("Pos", doubleList(position.x(), position.y(), position.z()));
        tag.put("Motion", doubleList(0.0D, 0.0D, 0.0D));
        tag.put("Rotation", floatList(0.0F, 0.0F));
        tag.putBoolean("NoGravity", true);
        tag.putBoolean("Invulnerable", true);
        tag.put("block_state", NbtUtils.writeBlockState(block.blockState()));
        tag.putFloat("width", 1.0F);
        tag.putFloat("height", 1.0F);
        tag.putFloat("view_range", 64.0F);
        tag.putFloat("shadow_radius", 0.2F);
        tag.putFloat("shadow_strength", 0.4F);
        tag.putInt("interpolation_duration", 0);
        tag.putInt("teleport_duration", 1);
        encodeVisualTransformation(pose, block).ifPresent(transformation -> tag.put("transformation", transformation));
        entity.load(tag);
    }

    private static Optional<net.minecraft.nbt.Tag> encodeVisualTransformation(PhysicsPose pose, AssemblyBlock block) {
        return Transformation.EXTENDED_CODEC
                .encodeStart(NbtOps.INSTANCE, visualTransformation(pose, block))
                .resultOrPartial(message -> KineticAssembly.LOGGER.warn("Failed to encode assembly display transformation: {}", message));
    }

    private static Transformation visualTransformation(PhysicsPose pose, AssemblyBlock block) {
        Quaternionf rotation = toJomlQuaternion(pose.rotation());
        Vector3f translation = vector(block.visualLocalOrigin()).rotate(new Quaternionf(rotation));
        return new Transformation(
                translation,
                rotation,
                new Vector3f(1.0F, 1.0F, 1.0F),
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
            // Position sync still keeps the debug proxy approximately useful.
        }
    }

    private static MethodHandle findDisplaySetTransformation() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Display.class, MethodHandles.lookup());
            return lookup.findVirtual(Display.class, "setTransformation", MethodType.methodType(void.class, Transformation.class));
        } catch (ReflectiveOperationException exception) {
            KineticAssembly.LOGGER.warn("Display#setTransformation is unavailable; assembly debug visuals will only sync position", exception);
            return null;
        }
    }

    private static Quaternionf toJomlQuaternion(PhysicsQuaternion rotation) {
        return new Quaternionf(
                (float) rotation.x(),
                (float) rotation.y(),
                (float) rotation.z(),
                (float) rotation.w()
        ).normalize();
    }

    private static Vector3f vector(PhysicsVector vector) {
        return new Vector3f(
                (float) vector.x(),
                (float) vector.y(),
                (float) vector.z()
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
}
