package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.List;
import java.util.Objects;

import com.firedoge.kineticassembly.api.PhysicsMassProperties;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.minecraft.material.BlockDensityResolver;

import net.minecraft.world.phys.AABB;

record AssemblyMassProperties(PhysicsMassProperties massProperties) {
    private static final double MIN_MASS = 0.01D;
    private static final double MIN_INERTIA = 1.0E-6D;

    AssemblyMassProperties {
        Objects.requireNonNull(massProperties, "massProperties");
    }

    float mass() {
        return massProperties.mass();
    }

    AssemblyMassProperties scaledToMass(float mass) {
        if (!Float.isFinite(mass) || mass <= 0.0F) {
            throw new IllegalArgumentException("mass must be a finite positive float");
        }
        double scale = mass / massProperties.mass();
        PhysicsVector inertia = massProperties.inertiaTensor();
        return new AssemblyMassProperties(new PhysicsMassProperties(
                mass,
                massProperties.centerOfMass(),
                new PhysicsVector(
                        clampInertia(inertia.x() * scale),
                        clampInertia(inertia.y() * scale),
                        clampInertia(inertia.z() * scale)
                )
        ));
    }

    static AssemblyMassProperties compute(List<AssemblyBlock> blocks) {
        Objects.requireNonNull(blocks, "blocks");
        double totalMass = 0.0D;
        double weightedX = 0.0D;
        double weightedY = 0.0D;
        double weightedZ = 0.0D;
        List<MassElement> elements = new java.util.ArrayList<>();
        for (AssemblyBlock block : blocks) {
            Objects.requireNonNull(block, "block");
            for (AABB box : block.localCollisionBoxes()) {
                MassElement element = massElement(block, box);
                if (element == null) {
                    continue;
                }
                elements.add(element);
                totalMass += element.mass();
                weightedX += element.mass() * element.center().x();
                weightedY += element.mass() * element.center().y();
                weightedZ += element.mass() * element.center().z();
            }
        }
        if (!Double.isFinite(totalMass) || totalMass <= 0.0D) {
            throw new IllegalArgumentException("Assembly density calculation produced no positive mass");
        }
        if (totalMass > Float.MAX_VALUE) {
            throw new IllegalArgumentException("Assembly density calculation produced mass above float range: " + totalMass);
        }

        PhysicsVector centerOfMass = new PhysicsVector(
                weightedX / totalMass,
                weightedY / totalMass,
                weightedZ / totalMass
        );
        double inertiaX = 0.0D;
        double inertiaY = 0.0D;
        double inertiaZ = 0.0D;
        for (MassElement element : elements) {
            PhysicsVector center = element.center();
            double dx = center.x() - centerOfMass.x();
            double dy = center.y() - centerOfMass.y();
            double dz = center.z() - centerOfMass.z();
            double mass = element.mass();
            double sizeX = element.sizeX();
            double sizeY = element.sizeY();
            double sizeZ = element.sizeZ();
            inertiaX += mass * (sizeY * sizeY + sizeZ * sizeZ) / 12.0D + mass * (dy * dy + dz * dz);
            inertiaY += mass * (sizeX * sizeX + sizeZ * sizeZ) / 12.0D + mass * (dx * dx + dz * dz);
            inertiaZ += mass * (sizeX * sizeX + sizeY * sizeY) / 12.0D + mass * (dx * dx + dy * dy);
        }

        double clampedMass = Math.max(totalMass, MIN_MASS);
        double massScale = clampedMass / totalMass;
        PhysicsMassProperties properties = new PhysicsMassProperties(
                (float) clampedMass,
                centerOfMass,
                new PhysicsVector(
                        clampInertia(inertiaX * massScale),
                        clampInertia(inertiaY * massScale),
                        clampInertia(inertiaZ * massScale)
                )
        );
        return new AssemblyMassProperties(properties);
    }

    private static MassElement massElement(AssemblyBlock block, AABB box) {
        double width = box.maxX - box.minX;
        double height = box.maxY - box.minY;
        double depth = box.maxZ - box.minZ;
        if (width <= 0.0D || height <= 0.0D || depth <= 0.0D) {
            return null;
        }
        double mass = BlockDensityResolver.INSTANCE.scaledMass(block.blockState(), width * height * depth);
        if (!Double.isFinite(mass) || mass <= 0.0D) {
            return null;
        }
        PhysicsVector origin = block.visualLocalOrigin();
        PhysicsVector center = new PhysicsVector(
                origin.x() + (box.minX + box.maxX) * 0.5D,
                origin.y() + (box.minY + box.maxY) * 0.5D,
                origin.z() + (box.minZ + box.maxZ) * 0.5D
        );
        return new MassElement(mass, center, width, height, depth);
    }

    private static double clampInertia(double inertia) {
        if (!Double.isFinite(inertia)) {
            throw new IllegalArgumentException("Assembly density calculation produced non-finite inertia");
        }
        return Math.max(inertia, MIN_INERTIA);
    }

    private record MassElement(
            double mass,
            PhysicsVector center,
            double sizeX,
            double sizeY,
            double sizeZ
    ) {
    }
}
