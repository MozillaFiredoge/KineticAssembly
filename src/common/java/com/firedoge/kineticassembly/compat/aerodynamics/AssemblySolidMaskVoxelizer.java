package com.firedoge.kineticassembly.compat.aerodynamics;

import java.util.ArrayList;
import java.util.List;

import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.minecraft.assembly.PhysicsAssembly;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

final class AssemblySolidMaskVoxelizer {
    static final int GRID_X = 32;
    static final int GRID_Y = 32;
    static final int GRID_Z = 32;
    private static final int CELLS = GRID_X * GRID_Y * GRID_Z;
    private static final int SAMPLES_PER_AXIS = 2;
    private static final double DOMAIN_PADDING = 1.5D;
    private static final double MIN_DOMAIN_SIZE_METERS = 1.0D;

    private AssemblySolidMaskVoxelizer() {
    }

    static Plan plan(PhysicsAssembly assembly, AeroWindFrame frame) {
        List<AABB> boxes = bodyLocalCollisionBoxes(assembly);
        if (boxes.isEmpty()) {
            return Plan.empty(frame);
        }
        AABB bounds = union(boxes);
        double span = Math.max(bounds.getXsize(), Math.max(bounds.getYsize(), bounds.getZsize()));
        double domainSize = Math.max(MIN_DOMAIN_SIZE_METERS, span * DOMAIN_PADDING);
        double dxMeters = domainSize / GRID_X;
        PhysicsVector objectCenter = center(bounds);
        long signature = signature(assembly, boxes, frame, dxMeters);
        double characteristicLength = Math.max(dxMeters, span);
        return new Plan(boxes, frame, objectCenter, domainSize, dxMeters, characteristicLength, signature, true);
    }

    static MaskResult voxelize(Plan plan) {
        if (!plan.hasGeometry()) {
            return new MaskResult(0, plan.dxMeters(), 0.0D, plan.characteristicLengthMeters(), plan.signature());
        }
        byte[] mask = new byte[CELLS];
        int solidCells = 0;
        double dx = plan.dxMeters();
        double sampleStep = dx / SAMPLES_PER_AXIS;
        double pad = sampleStep * 0.5D;
        double gridCenter = plan.domainSizeMeters() * 0.5D;
        List<AABB> boxes = plan.boxes().stream()
                .map(box -> box.inflate(pad))
                .toList();

        for (int x = 0; x < GRID_X; x++) {
            for (int y = 0; y < GRID_Y; y++) {
                for (int z = 0; z < GRID_Z; z++) {
                    if (!cellIntersectsSolid(x, y, z, dx, gridCenter, plan, boxes)) {
                        continue;
                    }
                    mask[index(x, y, z)] = 1;
                    solidCells++;
                }
            }
        }
        return new MaskResult(
                solidCells,
                dx,
                projectedAreaMeters2(mask, dx),
                plan.characteristicLengthMeters(),
                plan.signature()
        );
    }

    private static double projectedAreaMeters2(byte[] mask, double dxMeters) {
        int projectedCells = 0;
        for (int y = 0; y < GRID_Y; y++) {
            for (int z = 0; z < GRID_Z; z++) {
                boolean occupied = false;
                for (int x = 0; x < GRID_X; x++) {
                    if (mask[index(x, y, z)] != 0) {
                        occupied = true;
                        break;
                    }
                }
                if (occupied) {
                    projectedCells++;
                }
            }
        }
        return projectedCells * dxMeters * dxMeters;
    }

    private static boolean cellIntersectsSolid(
            int cellX,
            int cellY,
            int cellZ,
            double dx,
            double gridCenter,
            Plan plan,
            List<AABB> boxes
    ) {
        for (int sampleX = 0; sampleX < SAMPLES_PER_AXIS; sampleX++) {
            for (int sampleY = 0; sampleY < SAMPLES_PER_AXIS; sampleY++) {
                for (int sampleZ = 0; sampleZ < SAMPLES_PER_AXIS; sampleZ++) {
                    PhysicsVector tunnelOffset = new PhysicsVector(
                            (cellX + (sampleX + 0.5D) / SAMPLES_PER_AXIS) * dx - gridCenter,
                            (cellY + (sampleY + 0.5D) / SAMPLES_PER_AXIS) * dx - gridCenter,
                            (cellZ + (sampleZ + 0.5D) / SAMPLES_PER_AXIS) * dx - gridCenter
                    );
                    PhysicsVector bodyLocal = add(
                            plan.objectCenterBodyLocal(),
                            plan.frame().tunnelOffsetToBodyLocal(tunnelOffset)
                    );
                    if (containsAny(boxes, bodyLocal)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static List<AABB> bodyLocalCollisionBoxes(PhysicsAssembly assembly) {
        List<AABB> boxes = new ArrayList<>();
        for (AssemblyBlock block : assembly.blocks()) {
            boxes.addAll(block.bodyLocalCollisionBoxes());
        }
        return List.copyOf(boxes);
    }

    private static AABB union(List<AABB> boxes) {
        AABB first = boxes.getFirst();
        double minX = first.minX;
        double minY = first.minY;
        double minZ = first.minZ;
        double maxX = first.maxX;
        double maxY = first.maxY;
        double maxZ = first.maxZ;
        for (AABB box : boxes) {
            minX = Math.min(minX, box.minX);
            minY = Math.min(minY, box.minY);
            minZ = Math.min(minZ, box.minZ);
            maxX = Math.max(maxX, box.maxX);
            maxY = Math.max(maxY, box.maxY);
            maxZ = Math.max(maxZ, box.maxZ);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static PhysicsVector center(AABB bounds) {
        return new PhysicsVector(
                (bounds.minX + bounds.maxX) * 0.5D,
                (bounds.minY + bounds.maxY) * 0.5D,
                (bounds.minZ + bounds.maxZ) * 0.5D
        );
    }

    private static boolean containsAny(List<AABB> boxes, PhysicsVector point) {
        for (AABB box : boxes) {
            if (point.x() >= box.minX && point.x() <= box.maxX
                    && point.y() >= box.minY && point.y() <= box.maxY
                    && point.z() >= box.minZ && point.z() <= box.maxZ) {
                return true;
            }
        }
        return false;
    }

    private static PhysicsVector add(PhysicsVector first, PhysicsVector second) {
        return new PhysicsVector(first.x() + second.x(), first.y() + second.y(), first.z() + second.z());
    }

    private static int index(int x, int y, int z) {
        return (x * GRID_Y + y) * GRID_Z + z;
    }

    private static long signature(PhysicsAssembly assembly, List<AABB> boxes, AeroWindFrame frame, double dxMeters) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, assembly.id().value().getMostSignificantBits());
        hash = mix(hash, assembly.id().value().getLeastSignificantBits());
        hash = mix(hash, frame.bucketSignature());
        hash = mix(hash, quantize(dxMeters));
        for (AssemblyBlock block : assembly.blocks()) {
            hash = mix(hash, block.blockState().hashCode());
            hash = mix(hash, block.localPos().asLong());
        }
        for (AABB box : boxes) {
            hash = mix(hash, quantize(box.minX));
            hash = mix(hash, quantize(box.minY));
            hash = mix(hash, quantize(box.minZ));
            hash = mix(hash, quantize(box.maxX));
            hash = mix(hash, quantize(box.maxY));
            hash = mix(hash, quantize(box.maxZ));
        }
        return hash;
    }

    private static long mix(long hash, long value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    private static long quantize(double value) {
        return Math.round(value * 4096.0D);
    }

    record Plan(
            List<AABB> boxes,
            AeroWindFrame frame,
            PhysicsVector objectCenterBodyLocal,
            double domainSizeMeters,
            double dxMeters,
            double characteristicLengthMeters,
            long signature,
            boolean hasGeometry
    ) {
        private static Plan empty(AeroWindFrame frame) {
            double dxMeters = MIN_DOMAIN_SIZE_METERS / GRID_X;
            return new Plan(List.of(), frame, PhysicsVector.ZERO, MIN_DOMAIN_SIZE_METERS, dxMeters, dxMeters, 0L, false);
        }

        public Plan {
            boxes = List.copyOf(boxes);
        }
    }

    record MaskResult(
            int solidCells,
            double dxMeters,
            double projectedAreaMeters2,
            double characteristicLengthMeters,
            long signature
    ) {
    }
}
