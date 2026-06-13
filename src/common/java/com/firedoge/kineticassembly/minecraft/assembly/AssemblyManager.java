package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.mechanics.MechanicsBodyId;
import com.firedoge.kineticassembly.mechanics.MechanicsBodySnapshot;
import com.firedoge.kineticassembly.mechanics.MechanicsWorld;
import com.firedoge.kineticassembly.minecraft.scene.ServerPhysicsRuntime;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class AssemblyManager {
    public static final AssemblyManager INSTANCE = new AssemblyManager();
    private static final int BLOCK_UPDATE_FLAGS = 3;
    private static final double BODY_ORIGIN_EPSILON = 1.0E-7D;

    private long vanillaBreakActions;
    private long vanillaUseActions;
    private long vanillaBreakAccepted;
    private long vanillaUseAccepted;
    private long vanillaBreakRejected;
    private long vanillaUseRejected;
    private long plotBlockWrites;
    private long splitEvents;
    private long splitCreatedAssemblies;
    private final Map<RemovedPlotProjectionKey, RemovedPlotProjection> removedPlotProjections = new LinkedHashMap<>();

    private AssemblyManager() {
    }

    public AssemblySnapshot assembleBlock(ServerLevel level, BlockPos pos, boolean debugProxy) {
        return assembleBox(level, pos, pos, debugProxy);
    }

    public AssemblySnapshot assembleBlock(ServerLevel level, BlockPos pos, float mass, boolean debugProxy) {
        return assembleBox(level, pos, pos, mass, debugProxy);
    }

    public AssemblySnapshot assembleBox(ServerLevel level, BlockPos first, BlockPos second, boolean debugProxy) {
        return assembleBox(level, first, second, null, debugProxy);
    }

    public AssemblySnapshot assembleBox(ServerLevel level, BlockPos first, BlockPos second, float mass, boolean debugProxy) {
        return assembleBox(level, first, second, Float.valueOf(mass), debugProxy);
    }

    private AssemblySnapshot assembleBox(ServerLevel level, BlockPos first, BlockPos second, Float massOverride, boolean debugProxy) {
        ServerAssemblyContainer container = AssemblyContainers.requireServer(level);
        List<AssemblyEntityBridge.AttachedEntityCapture> attachedEntities =
                AssemblyEntityBridge.captureAttachedEntitiesForAssembly(level, AssemblyBounds.from(first, second));
        AssemblyAssembler.Result result = AssemblyAssembler.assembleBox(level, first, second, massOverride, container);
        PhysicsAssembly assembly = result.assembly();
        container.add(assembly);
        AssemblyEntityBridge.registerCapturedAttachedEntities(level, assembly, attachedEntities);
        container.moveSourceScheduledTicksToPlot(assembly);
        container.requestPlotBlockUpdatePrime(assembly);
        assembly.activate();
        try {
            if (debugProxy) {
                createVisuals(level, result.body(), assembly);
            }
            return snapshot(result.body(), assembly);
        } catch (RuntimeException exception) {
            discardVisuals(level, result.body(), assembly);
            prepareAssemblyRemoval(level, assembly);
            container.remove(assembly.id());
            KineticAssembly.api().existingWorld(level).ifPresent(world -> world.removeBody(assembly.bodyId()));
            throw exception;
        }
    }

    public void tick(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        removedPlotProjections.clear();

        for (ServerLevel level : server.getAllLevels()) {
            ServerAssemblyContainer container = AssemblyContainers.server(level).orElse(null);
            if (container == null || container.isEmpty()) {
                continue;
            }
            for (PhysicsAssembly assembly : container.assemblies()) {
                Optional<MechanicsBodySnapshot> maybeBody = KineticAssembly.api().existingWorld(level)
                        .flatMap(world -> world.snapshot(assembly.bodyId()));
                if (maybeBody.isEmpty()) {
                    discardVisuals(level, assembly);
                    prepareAssemblyRemoval(level, assembly);
                    container.remove(assembly.id());
                    continue;
                }
                syncVisuals(level, maybeBody.get(), assembly);
            }
        }
    }

    public List<AssemblySnapshot> snapshots(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        ServerAssemblyContainer container = AssemblyContainers.requireServer(level);
        Optional<MechanicsWorld> maybeWorld = KineticAssembly.api().existingWorld(level);
        if (maybeWorld.isEmpty()) {
            removeStaleLevelEntries(level);
            return List.of();
        }

        MechanicsWorld world = maybeWorld.get();
        List<AssemblySnapshot> snapshots = new ArrayList<>();
        for (PhysicsAssembly assembly : container.assemblies()) {
            Optional<MechanicsBodySnapshot> maybeBody = world.snapshot(assembly.bodyId());
            if (maybeBody.isEmpty()) {
                discardVisuals(level, assembly);
                prepareAssemblyRemoval(level, assembly);
                container.remove(assembly.id());
                continue;
            }
            snapshots.add(snapshot(maybeBody.get(), assembly));
        }
        return List.copyOf(snapshots);
    }

    public Optional<AssemblySnapshot> snapshot(ServerLevel level, AssemblyId id) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        ServerAssemblyContainer container = AssemblyContainers.requireServer(level);
        Optional<PhysicsAssembly> maybeAssembly = container.assembly(id);
        if (maybeAssembly.isEmpty()) {
            return Optional.empty();
        }
        PhysicsAssembly assembly = maybeAssembly.get();

        Optional<MechanicsBodySnapshot> maybeBody = KineticAssembly.api().existingWorld(level)
                .flatMap(world -> world.snapshot(assembly.bodyId()));
        if (maybeBody.isEmpty()) {
            discardVisuals(level, assembly);
            prepareAssemblyRemoval(level, assembly);
            container.remove(id);
            return Optional.empty();
        }
        return Optional.of(snapshot(maybeBody.get(), assembly));
    }

    public Optional<AssemblyBlock> blockAtPlotBlock(ServerLevel level, BlockPos plotPos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        return assemblyAtPlotBlock(level, plotPos)
                .flatMap(assembly -> assembly.section().block(assembly.plot().toSectionLocalPos(plotPos)));
    }

    public Optional<BlockState> blockStateAtPlotBlock(ServerLevel level, BlockPos plotPos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        return assemblyAtPlotBlock(level, plotPos)
                .map(assembly -> assembly.section()
                        .block(assembly.plot().toSectionLocalPos(plotPos))
                        .map(AssemblyBlock::blockState)
                        .orElseGet(() -> Blocks.AIR.defaultBlockState()));
    }

    public Optional<BlockEntity> blockEntityAtPlotBlock(ServerLevel level, BlockPos plotPos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        Optional<PhysicsAssembly> maybeAssembly = assemblyAtPlotBlock(level, plotPos);
        if (maybeAssembly.isEmpty()) {
            return Optional.empty();
        }

        PhysicsAssembly assembly = maybeAssembly.get();
        BlockPos localPos = assembly.plot().toSectionLocalPos(plotPos);
        Optional<AssemblyBlock> maybeBlock = assembly.section().block(localPos);
        if (maybeBlock.isEmpty()) {
            return Optional.empty();
        }

        Optional<BlockEntity> cached = assembly.blockEntity(localPos);
        if (cached.isPresent() && !cached.get().isRemoved()) {
            return cached;
        }

        BlockEntity blockEntity = createBlockEntity(level, plotPos, maybeBlock.get());
        if (blockEntity == null) {
            return Optional.empty();
        }
        assembly.putBlockEntity(localPos, blockEntity);
        assembly.section().updateBlockEntityTag(localPos, blockEntity.saveWithFullMetadata(level.registryAccess()));
        AssemblyContainers.requireServer(level).rebuildPlotChunks(assembly);
        return Optional.of(blockEntity);
    }

    public boolean setPlotBlockEntity(ServerLevel level, BlockEntity blockEntity) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(blockEntity, "blockEntity");
        BlockPos plotPos = blockEntity.getBlockPos();
        Optional<PhysicsAssembly> maybeAssembly = assemblyAtPlotBlock(level, plotPos);
        if (maybeAssembly.isEmpty()) {
            return false;
        }

        PhysicsAssembly assembly = maybeAssembly.get();
        BlockPos localPos = assembly.plot().toSectionLocalPos(plotPos);
        if (assembly.section().block(localPos).isEmpty()) {
            return false;
        }

        blockEntity.setLevel(level);
        blockEntity.clearRemoved();
        assembly.putBlockEntity(localPos, blockEntity);
        assembly.section().updateBlockEntityTag(localPos, blockEntity.saveWithFullMetadata(level.registryAccess()));
        AssemblyContainers.requireServer(level).rebuildPlotChunks(assembly);
        return true;
    }

    public boolean removePlotBlockEntity(ServerLevel level, BlockPos plotPos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        Optional<PhysicsAssembly> maybeAssembly = assemblyAtPlotBlock(level, plotPos);
        if (maybeAssembly.isEmpty()) {
            return false;
        }

        PhysicsAssembly assembly = maybeAssembly.get();
        BlockPos localPos = assembly.plot().toSectionLocalPos(plotPos);
        if (assembly.section().block(localPos).isEmpty()) {
            return false;
        }
        assembly.removeBlockEntity(localPos);
        assembly.section().updateBlockEntityTag(localPos, null);
        AssemblyContainers.requireServer(level).rebuildPlotChunks(assembly);
        return true;
    }

    public boolean syncPlotBlockEntityTag(ServerLevel level, BlockPos plotPos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        Optional<PhysicsAssembly> maybeAssembly = assemblyAtPlotBlock(level, plotPos);
        if (maybeAssembly.isEmpty()) {
            return false;
        }

        PhysicsAssembly assembly = maybeAssembly.get();
        BlockPos localPos = assembly.plot().toSectionLocalPos(plotPos);
        if (assembly.section().block(localPos).isEmpty()) {
            return false;
        }

        BlockEntity blockEntity = level.getBlockEntity(plotPos);
        CompoundTag tag = blockEntity == null || blockEntity.isRemoved()
                ? null
                : blockEntity.saveWithFullMetadata(level.registryAccess());
        return assembly.section().updateBlockEntityTag(localPos, tag);
    }

    public Optional<AssemblyPlotTarget> plotTargetAtBlock(ServerLevel level, BlockPos plotPos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        return assemblyAtPlotBlock(level, plotPos).flatMap(assembly -> {
            BlockPos localPos = assembly.plot().toSectionLocalPos(plotPos);
            return assembly.section()
                    .block(localPos)
                    .map(block -> new AssemblyPlotTarget(assembly.id(), localPos, block.blockState()));
        });
    }

    public boolean setPlotBlockState(ServerLevel level, BlockPos plotPos, BlockState blockState) {
        return setPlotBlockState(level, plotPos, blockState, false);
    }

    public boolean setPlotBlockState(ServerLevel level, BlockPos plotPos, BlockState blockState, boolean isMoving) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        Objects.requireNonNull(blockState, "blockState");
        Optional<PhysicsAssembly> maybeAssembly = assemblyAtPlotBlock(level, plotPos);
        if (maybeAssembly.isEmpty()) {
            return false;
        }

        PhysicsAssembly assembly = maybeAssembly.get();
        ServerAssemblyContainer container = AssemblyContainers.requireServer(level);
        BlockPos localPos = assembly.plot().toSectionLocalPos(plotPos);
        Optional<AssemblyBlock> maybePreviousBlock = assembly.section().block(localPos);
        Optional<MechanicsBodySnapshot> maybeBody = KineticAssembly.api().existingWorld(level)
                .flatMap(world -> world.snapshot(assembly.bodyId()));
        if (maybeBody.isEmpty()) {
            return false;
        }

        if (maybePreviousBlock.isEmpty()) {
            if (blockState.isAir()) {
                container.setPlotChunkBlockStateInPlace(plotPos, blockState, isMoving);
                return true;
            }
            if (isMovingPiston(blockState)) {
                if (!putTransientMovingPiston(level, container, assembly, localPos, plotPos, blockState, null, isMoving)) {
                    return false;
                }
                plotBlockWrites++;
                return true;
            }
            if (!hasAdjacentBlock(assembly, localPos)) {
                return false;
            }
            List<AABB> localCollisionBoxes = AssemblyAssembler.physicalCollisionBoxes(blockState, level, plotPos);
            if (localCollisionBoxes.isEmpty() && !AssemblyAssembler.hasPhysicalCollision(assembly.blocks())) {
                return false;
            }
            PhysicsVector visualLocalOrigin = visualLocalOriginFor(assembly, localPos);
            if (!isPlacementUnobstructed(level, maybeBody.get(), visualLocalOrigin, localCollisionBoxes)) {
                return false;
            }
            AssemblyBlock addedBlock = new AssemblyBlock(
                    assembly.section().toSourcePos(localPos),
                    localPos.immutable(),
                    blockState,
                    AssemblyAssembler.localGeometryBounds(blockState, level, plotPos),
                    localCollisionBoxes,
                    visualLocalOrigin
            );
            assembly.section().putBlock(addedBlock);
            assembly.refreshBoundsFromBlocks();
            assembly.markDirty();
            rebuildAssemblyBody(level, assembly, maybeBody.get());
            plotBlockWrites++;
            return true;
        }

        AssemblyBlock previousBlock = maybePreviousBlock.get();
        if (previousBlock.blockState().equals(blockState)) {
            return container.setPlotChunkBlockStateInPlace(plotPos, blockState, isMoving);
        }

        if (blockState.isAir()) {
            if (isMovingPiston(previousBlock.blockState()) && !previousBlock.hasPhysicalCollision()) {
                if (!container.setPlotChunkBlockStateInPlace(plotPos, blockState, isMoving)) {
                    return false;
                }
                assembly.removeBlockEntity(localPos);
                assembly.section().removeBlock(localPos);
                assembly.bumpShapeEpoch();
                assembly.markDirty();
                plotBlockWrites++;
                return true;
            }
            recordRemovedPlotProjection(level, assembly, maybeBody.get(), plotPos);
            AssemblyPlotProjection trackingProjection = trackingProjection(assembly, maybeBody.get());
            RemovedBlocks removedBlocks = removeBlockAndDependents(level, assembly, maybeBody.get(), localPos, DropMode.CONTENTS, true);
            if (removedBlocks.isEmpty()) {
                return false;
            }
            if (assembly.section().isEmpty()) {
                projectTrackingPointsBeforePermanentRemoval(level, trackingProjection);
                discardVisuals(level, maybeBody.get(), assembly);
                prepareAssemblyRemoval(level, assembly);
                KineticAssembly.api().existingWorld(level).ifPresent(world -> world.removeBody(assembly.bodyId()));
                AssemblyContainers.requireServer(level).remove(assembly.id());
            } else {
                rebuildAfterBlockRemoval(level, AssemblyContainers.requireServer(level), assembly, maybeBody.get(), removedBlocks);
            }
            plotBlockWrites++;
            return true;
        }
        if (isMovingPiston(blockState)) {
            if (!putTransientMovingPiston(level, container, assembly, localPos, plotPos, blockState, previousBlock, isMoving)) {
                return false;
            }
            plotBlockWrites++;
            return true;
        }

        List<AABB> localCollisionBoxes = AssemblyAssembler.physicalCollisionBoxes(blockState, level, plotPos);
        if (localCollisionBoxes.isEmpty() && !hasOtherPhysicalCollision(assembly, localPos)) {
            return false;
        }
        AABB localCollisionBounds = AssemblyAssembler.localGeometryBounds(blockState, level, plotPos);
        if (!isPlacementUnobstructed(level, maybeBody.get(), previousBlock.visualLocalOrigin(), localCollisionBoxes)) {
            return false;
        }
        if (canUpdatePlotStateInPlace(previousBlock, blockState, localCollisionBounds, localCollisionBoxes)) {
            AssemblyBlock updatedBlock = new AssemblyBlock(
                    previousBlock.sourcePos(),
                    localPos.immutable(),
                    blockState,
                    localCollisionBounds,
                    localCollisionBoxes,
                    previousBlock.visualLocalOrigin(),
                    previousBlock.blockEntityTag()
            );
            assembly.section().putBlock(updatedBlock);
            assembly.bumpShapeEpoch();
            assembly.markDirty();
            if (!AssemblyContainers.requireServer(level).setPlotChunkBlockStateInPlace(plotPos, blockState, false)) {
                return false;
            }
            plotBlockWrites++;
            return true;
        }
        if (previousBlock.blockState().getBlock() == blockState.getBlock()) {
            AssemblyBlock updatedBlock = new AssemblyBlock(
                    previousBlock.sourcePos(),
                    localPos.immutable(),
                    blockState,
                    localCollisionBounds,
                    localCollisionBoxes,
                    previousBlock.visualLocalOrigin(),
                    previousBlock.blockEntityTag()
            );
            assembly.section().putBlock(updatedBlock);
            assembly.refreshBoundsFromBlocks();
            assembly.markDirty();
            if (!AssemblyContainers.requireServer(level).setPlotChunkBlockStateInPlace(plotPos, blockState, false)) {
                return false;
            }
            rebuildAssemblyBody(level, assembly, maybeBody.get(), false);
            plotBlockWrites++;
            return true;
        }
        if (previousBlock.blockState().getBlock() != blockState.getBlock()) {
            assembly.removeBlockEntity(localPos);
        }
        AssemblyBlock updatedBlock = new AssemblyBlock(
                previousBlock.sourcePos(),
                localPos.immutable(),
                blockState,
                localCollisionBounds,
                localCollisionBoxes,
                previousBlock.visualLocalOrigin(),
                previousBlock.blockState().getBlock() == blockState.getBlock() ? previousBlock.blockEntityTag() : null
        );
        assembly.section().putBlock(updatedBlock);
        assembly.refreshBoundsFromBlocks();
        assembly.markDirty();
        rebuildAssemblyBody(level, assembly, maybeBody.get());
        plotBlockWrites++;
        return true;
    }

    private static boolean isMovingPiston(BlockState blockState) {
        return blockState.is(Blocks.MOVING_PISTON);
    }

    private static boolean putTransientMovingPiston(
            ServerLevel level,
            ServerAssemblyContainer container,
            PhysicsAssembly assembly,
            BlockPos localPos,
            BlockPos plotPos,
            BlockState blockState,
            @Nullable AssemblyBlock previousBlock,
            boolean isMoving
    ) {
        if (!container.setPlotChunkBlockStateInPlace(plotPos, blockState, isMoving)) {
            return false;
        }
        AssemblyBlock updatedBlock;
        if (previousBlock == null) {
            updatedBlock = new AssemblyBlock(
                    assembly.section().toSourcePos(localPos),
                    localPos.immutable(),
                    blockState,
                    AssemblyAssembler.localGeometryBounds(blockState, level, plotPos),
                    List.of(),
                    visualLocalOriginFor(assembly, localPos)
            );
        } else {
            updatedBlock = new AssemblyBlock(
                    previousBlock.sourcePos(),
                    localPos.immutable(),
                    blockState,
                    previousBlock.localCollisionBounds(),
                    previousBlock.localCollisionBoxes(),
                    previousBlock.visualLocalOrigin(),
                    previousBlock.blockEntityTag()
            );
        }
        assembly.section().putBlock(updatedBlock);
        assembly.bumpShapeEpoch();
        assembly.markDirty();
        return true;
    }

    private static boolean isPlacementUnobstructed(
            ServerLevel level,
            MechanicsBodySnapshot body,
            PhysicsVector visualLocalOrigin,
            List<AABB> localCollisionBoxes
    ) {
        if (localCollisionBoxes.isEmpty()) {
            return true;
        }

        List<AABB> bodyLocalBoxes = new ArrayList<>(localCollisionBoxes.size());
        for (AABB localCollisionBox : localCollisionBoxes) {
            bodyLocalBoxes.add(new AABB(
                    visualLocalOrigin.x() + localCollisionBox.minX,
                    visualLocalOrigin.y() + localCollisionBox.minY,
                    visualLocalOrigin.z() + localCollisionBox.minZ,
                    visualLocalOrigin.x() + localCollisionBox.maxX,
                    visualLocalOrigin.y() + localCollisionBox.maxY,
                    visualLocalOrigin.z() + localCollisionBox.maxZ
            ));
        }
        return AssemblyPlacementObstruction.areBodyLocalBoxesUnobstructed(
                level,
                AssemblyTransform.from(body),
                bodyLocalBoxes
        );
    }

    private static boolean canUpdatePlotStateInPlace(
            AssemblyBlock previousBlock,
            BlockState blockState,
            AABB localCollisionBounds,
            List<AABB> localCollisionBoxes
    ) {
        return previousBlock.blockState().getBlock() == blockState.getBlock()
                && sameBox(previousBlock.localCollisionBounds(), localCollisionBounds)
                && sameBoxes(previousBlock.localCollisionBoxes(), localCollisionBoxes);
    }

    private static boolean sameBoxes(List<AABB> first, List<AABB> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int i = 0; i < first.size(); i++) {
            if (!sameBox(first.get(i), second.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameBox(AABB first, AABB second) {
        return Double.compare(first.minX, second.minX) == 0
                && Double.compare(first.minY, second.minY) == 0
                && Double.compare(first.minZ, second.minZ) == 0
                && Double.compare(first.maxX, second.maxX) == 0
                && Double.compare(first.maxY, second.maxY) == 0
                && Double.compare(first.maxZ, second.maxZ) == 0;
    }

    private static boolean hasOtherPhysicalCollision(PhysicsAssembly assembly, BlockPos localPos) {
        for (AssemblyBlock block : assembly.blocks()) {
            if (!block.localPos().equals(localPos) && block.hasPhysicalCollision()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAdjacentBlock(PhysicsAssembly assembly, BlockPos localPos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = localPos.relative(direction);
            if (AssemblySectionStorage.isValidLocal(neighbor) && assembly.section().hasBlock(neighbor)) {
                return true;
            }
        }
        return false;
    }

    private RemovedBlocks removeBlockAndDependents(
            ServerLevel level,
            PhysicsAssembly assembly,
            MechanicsBodySnapshot body,
            BlockPos primaryLocalPos,
            DropMode primaryDropMode,
            boolean dropDependents
    ) {
        Map<BlockPos, AssemblyBlock> removed = new LinkedHashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        removeAssemblyBlock(level, assembly, body, primaryLocalPos, primaryDropMode)
                .ifPresent(block -> {
                    removed.put(primaryLocalPos.immutable(), block);
                    queue.add(primaryLocalPos.immutable());
                });
        if (removed.isEmpty()) {
            return RemovedBlocks.EMPTY;
        }

        while (!queue.isEmpty()) {
            BlockPos removedLocalPos = queue.removeFirst();
            for (BlockPos candidateLocalPos : dependentCandidatePositions(removedLocalPos)) {
                if (removed.containsKey(candidateLocalPos) || !AssemblySectionStorage.isValidLocal(candidateLocalPos)) {
                    continue;
                }
                Optional<AssemblyBlock> maybeCandidate = assembly.section().block(candidateLocalPos);
                if (maybeCandidate.isEmpty()) {
                    continue;
                }
                AssemblyBlock candidate = maybeCandidate.get();
                if (!shouldRemoveDependentBlock(assembly, candidate, removedLocalPos)) {
                    continue;
                }
                removeAssemblyBlock(
                        level,
                        assembly,
                        body,
                        candidateLocalPos,
                        dropDependents && shouldDropDependentBlock(candidate) ? DropMode.RESOURCES : DropMode.NONE
                )
                        .ifPresent(block -> {
                            removed.put(candidateLocalPos.immutable(), block);
                            queue.add(candidateLocalPos.immutable());
                        });
            }
        }

        return new RemovedBlocks(removed);
    }

    private Optional<AssemblyBlock> removeAssemblyBlock(
            ServerLevel level,
            PhysicsAssembly assembly,
            MechanicsBodySnapshot body,
            BlockPos localPos,
            DropMode dropMode
    ) {
        Optional<AssemblyBlock> maybeBlock = assembly.section().block(localPos);
        if (maybeBlock.isEmpty()) {
            return Optional.empty();
        }

        BlockPos plotPos = assembly.plot().toPlotBlockPos(localPos);
        recordRemovedPlotProjection(level, assembly, body, plotPos);
        AssemblyBlock block = maybeBlock.get();
        if (dropMode == DropMode.RESOURCES) {
            dropResources(level, assembly, localPos, block, plotPos);
        } else if (dropMode == DropMode.CONTENTS) {
            dropContainerContents(level, assembly, localPos, block, plotPos);
        }
        assembly.removeBlockEntity(localPos);
        Optional<AssemblyBlock> removed = assembly.section().removeBlock(localPos);
        removed.ifPresent(ignored -> assembly.bumpShapeEpoch());
        return removed;
    }

    private void dropResources(
            ServerLevel level,
            PhysicsAssembly assembly,
            BlockPos localPos,
            AssemblyBlock block,
            BlockPos plotPos
    ) {
        BlockEntity blockEntity = assembly.blockEntity(localPos).filter(entity -> !entity.isRemoved()).orElse(null);
        Block.dropResources(block.blockState(), level, plotPos, blockEntity, null, ItemStack.EMPTY);
        dropContainerContents(level, assembly, localPos, block, plotPos);
    }

    private void dropContainerContents(
            ServerLevel level,
            PhysicsAssembly assembly,
            BlockPos localPos,
            AssemblyBlock block,
            BlockPos plotPos
    ) {
        BlockEntity blockEntity = assembly.blockEntity(localPos)
                .filter(entity -> !entity.isRemoved())
                .orElseGet(() -> createBlockEntity(level, plotPos, block));
        if (!(blockEntity instanceof Container container) || container.isEmpty()) {
            return;
        }

        Containers.dropContents(level, plotPos, container);
        container.clearContent();
        container.setChanged();
        level.updateNeighbourForOutputSignal(plotPos, block.blockState().getBlock());
    }

    private static List<BlockPos> dependentCandidatePositions(BlockPos localPos) {
        List<BlockPos> candidates = new ArrayList<>(8);
        for (Direction direction : Direction.values()) {
            candidates.add(localPos.relative(direction));
        }
        return List.copyOf(candidates);
    }

    private static boolean shouldRemoveDependentBlock(PhysicsAssembly assembly, AssemblyBlock candidate, BlockPos removedLocalPos) {
        BlockState state = candidate.blockState();
        BlockPos localPos = candidate.localPos();
        BlockPos pair = pairedLocalPos(state, localPos);
        if (pair != null && pair.equals(removedLocalPos)) {
            return true;
        }

        Direction supportDirection = supportDirection(state);
        if (supportDirection != null && localPos.relative(supportDirection).equals(removedLocalPos)) {
            return true;
        }

        if (localPos.below().equals(removedLocalPos) && isFragileAttachment(assembly, candidate)) {
            return true;
        }
        return false;
    }

    private static boolean shouldDropDependentBlock(AssemblyBlock block) {
        return pairedLocalPos(block.blockState(), block.localPos()) == null;
    }

    private static BlockPos pairedLocalPos(BlockState state, BlockPos localPos) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            DoubleBlockHalf half = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
            return localPos.relative(half == DoubleBlockHalf.LOWER ? Direction.UP : Direction.DOWN);
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)
                && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            BedPart part = state.getValue(BlockStateProperties.BED_PART);
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            return localPos.relative(part == BedPart.FOOT ? facing : facing.getOpposite());
        }
        if ((state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON))
                && state.hasProperty(BlockStateProperties.EXTENDED)
                && state.hasProperty(BlockStateProperties.FACING)
                && state.getValue(BlockStateProperties.EXTENDED)) {
            return localPos.relative(state.getValue(BlockStateProperties.FACING));
        }
        if ((state.is(Blocks.PISTON_HEAD) || state.is(Blocks.MOVING_PISTON))
                && state.hasProperty(BlockStateProperties.FACING)) {
            return localPos.relative(state.getValue(BlockStateProperties.FACING).getOpposite());
        }
        return null;
    }

    private static Direction supportDirection(BlockState state) {
        if (state.hasProperty(BlockStateProperties.ATTACH_FACE)
                && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            AttachFace attachFace = state.getValue(BlockStateProperties.ATTACH_FACE);
            return switch (attachFace) {
                case FLOOR -> Direction.DOWN;
                case CEILING -> Direction.UP;
                case WALL -> state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
            };
        }
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                && state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
        }
        return null;
    }

    private static boolean isFragileAttachment(PhysicsAssembly assembly, AssemblyBlock block) {
        BlockPos plotPos = assembly.plot().toPlotBlockPos(block.localPos());
        return block.blockState().getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, plotPos).isEmpty();
    }

    public void recordVanillaBreakAction() {
        vanillaBreakActions++;
    }

    public void recordVanillaUseAction() {
        vanillaUseActions++;
    }

    public void recordVanillaBreakAccepted() {
        vanillaBreakAccepted++;
    }

    public void recordVanillaUseAccepted() {
        vanillaUseAccepted++;
    }

    public void recordVanillaBreakRejected() {
        vanillaBreakRejected++;
    }

    public void recordVanillaUseRejected() {
        vanillaUseRejected++;
    }

    public BridgeStats bridgeStats() {
        return new BridgeStats(
                vanillaBreakActions,
                vanillaUseActions,
                vanillaBreakAccepted,
                vanillaUseAccepted,
                vanillaBreakRejected,
                vanillaUseRejected,
                plotBlockWrites,
                splitEvents,
                splitCreatedAssemblies
        );
    }

    public Optional<PhysicsVector> plotBlockWorldCenter(ServerLevel level, BlockPos plotPos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        Optional<PhysicsAssembly> maybeAssembly = assemblyAtPlotBlock(level, plotPos);
        if (maybeAssembly.isEmpty()) {
            return Optional.empty();
        }

        PhysicsAssembly assembly = maybeAssembly.get();
        BlockPos localPos = assembly.plot().toSectionLocalPos(plotPos);
        Optional<AssemblyBlock> maybeBlock = assembly.section().block(localPos);
        if (maybeBlock.isEmpty()) {
            return Optional.empty();
        }

        Optional<PhysicsPose> maybePose = KineticAssembly.api().existingWorld(level)
                .flatMap(world -> world.pose(assembly.bodyId()));
        if (maybePose.isEmpty()) {
            return Optional.empty();
        }

        AABB bounds = maybeBlock.get().localCollisionBounds();
        PhysicsVector localCenter = new PhysicsVector(
                maybeBlock.get().visualLocalOrigin().x() + (bounds.minX + bounds.maxX) * 0.5D,
                maybeBlock.get().visualLocalOrigin().y() + (bounds.minY + bounds.maxY) * 0.5D,
                maybeBlock.get().visualLocalOrigin().z() + (bounds.minZ + bounds.maxZ) * 0.5D
        );
        return Optional.of(AssemblyTransform.from(maybePose.get()).localToWorld(localCenter));
    }

    public Optional<PhysicsVector> plotPositionToWorld(ServerLevel level, Vec3 plotPosition) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPosition, "plotPosition");
        BlockPos plotPos = BlockPos.containing(plotPosition);
        return plotProjection(level, plotPos)
                .map(projection -> projection.toWorld(plotPosition));
    }

    public Optional<PhysicsVector> plotDirectionToWorld(ServerLevel level, BlockPos plotPos, Vec3 plotDirection) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        Objects.requireNonNull(plotDirection, "plotDirection");
        return plotProjection(level, plotPos)
                .map(projection -> projection.directionToWorld(plotDirection));
    }

    public Optional<PlotProjection> plotProjection(ServerLevel level, BlockPos plotPos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        Optional<PhysicsAssembly> maybeAssembly = assemblyAtPlotBlock(level, plotPos);
        if (maybeAssembly.isEmpty()) {
            return removedPlotProjection(level, plotPos);
        }

        PhysicsAssembly assembly = maybeAssembly.get();
        Optional<PhysicsPose> maybePose = KineticAssembly.api().existingWorld(level)
                .flatMap(world -> world.pose(assembly.bodyId()));
        if (maybePose.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new ActivePlotProjection(assembly, AssemblyTransform.from(maybePose.get())));
    }

    public boolean playerCanReachPlotBlock(ServerLevel level, ServerPlayer player, BlockPos plotPos) {
        Objects.requireNonNull(player, "player");
        Optional<PhysicsVector> maybeCenter = plotBlockWorldCenter(level, plotPos);
        if (maybeCenter.isEmpty()) {
            return false;
        }

        Vec3 eye = player.getEyePosition();
        PhysicsVector center = maybeCenter.get();
        double dx = center.x() - eye.x();
        double dy = center.y() - eye.y();
        double dz = center.z() - eye.z();
        double maxDistance = Math.max(16.0D, player.blockInteractionRange()) + 2.0D;
        return dx * dx + dy * dy + dz * dz <= maxDistance * maxDistance;
    }

    public boolean playerCanReachPlotBlock(ServerLevel level, ServerPlayer player, BlockPos plotPos, double distanceBuffer) {
        Objects.requireNonNull(player, "player");
        Optional<PhysicsAssembly> maybeAssembly = assemblyAtPlotBlock(level, plotPos);
        if (maybeAssembly.isEmpty()) {
            return false;
        }

        Optional<MechanicsBodySnapshot> maybeBody = KineticAssembly.api().existingWorld(level)
                .flatMap(world -> world.snapshot(maybeAssembly.get().bodyId()));
        if (maybeBody.isEmpty()) {
            return false;
        }

        PhysicsAssembly assembly = maybeAssembly.get();
        PhysicsVector localEye = AssemblyTransform.from(maybeBody.get()).worldToLocal(new PhysicsVector(
                player.getEyePosition().x(),
                player.getEyePosition().y(),
                player.getEyePosition().z()
        ));
        PhysicsVector bodyToPlotOrigin = bodyToPlotOrigin(assembly);
        Vec3 plotEye = new Vec3(
                localEye.x() - bodyToPlotOrigin.x() + assembly.plot().minPlotX(),
                localEye.y() - bodyToPlotOrigin.y() + assembly.plot().minPlotY(),
                localEye.z() - bodyToPlotOrigin.z() + assembly.plot().minPlotZ()
        );
        double maxDistance = Math.max(0.0D, player.blockInteractionRange() + distanceBuffer);
        return new AABB(plotPos).distanceToSqr(plotEye) < maxDistance * maxDistance;
    }

    public List<VoxelShape> blockCollisionShapes(ServerLevel level, AABB worldBounds) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(worldBounds, "worldBounds");
        ServerAssemblyContainer container = AssemblyContainers.server(level).orElse(null);
        if (container == null || container.isEmpty()) {
            return List.of();
        }

        AABB queryBounds = worldBounds.inflate(1.0E-7D);
        List<VoxelShape> shapes = new ArrayList<>();
        for (AssemblyCollisionTarget target : container.collisionTargets(queryBounds, null)) {
            AssemblyTransform transform = target.transform();
            for (AssemblyCollisionBlock block : target.collisionBlocks()) {
                for (AABB localBox : block.bodyLocalBoxes()) {
                    AABB worldBox = transform.localAabbToWorldBounds(localBox);
                    if (worldBox.intersects(queryBounds)) {
                        shapes.add(Shapes.create(worldBox));
                    }
                }
            }
        }
        return List.copyOf(shapes);
    }

    public Optional<AssemblyPickResult> pickBlock(
            ServerLevel level,
            PhysicsVector worldOrigin,
            PhysicsVector worldDirection,
            double maxDistance
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(worldOrigin, "worldOrigin");
        Objects.requireNonNull(worldDirection, "worldDirection");
        if (maxDistance <= 0.0D || Double.isNaN(maxDistance)) {
            throw new IllegalArgumentException("maxDistance must be positive");
        }

        PhysicsVector direction = normalize(worldDirection);
        Optional<MechanicsWorld> maybeWorld = KineticAssembly.api().existingWorld(level);
        if (maybeWorld.isEmpty()) {
            removeStaleLevelEntries(level);
            return Optional.empty();
        }

        MechanicsWorld world = maybeWorld.get();
        ServerAssemblyContainer container = AssemblyContainers.requireServer(level);
        AssemblyPickResult best = null;
        for (PhysicsAssembly assembly : container.assemblies()) {
            Optional<PhysicsPose> maybePose = world.pose(assembly.bodyId());
            if (maybePose.isEmpty()) {
                discardVisuals(level, assembly);
                container.remove(assembly.id());
                continue;
            }
            AssemblyTransform transform = AssemblyTransform.from(maybePose.get());
            PhysicsVector localOrigin = transform.worldToLocal(worldOrigin);
            PhysicsVector localDirection = transform.worldDirectionToLocal(direction);
            MechanicsBodySnapshot body = null;
            boolean missingBody = false;
            for (AssemblyBlock block : assembly.blocks()) {
                Optional<Double> maybeDistance = intersectBlock(localOrigin, localDirection, block, maxDistance);
                if (maybeDistance.isEmpty()) {
                    continue;
                }
                double distance = maybeDistance.get();
                if (best != null && distance >= best.distance()) {
                    continue;
                }
                if (body == null) {
                    Optional<MechanicsBodySnapshot> maybeBody = world.snapshot(assembly.bodyId());
                    if (maybeBody.isEmpty()) {
                        missingBody = true;
                        break;
                    }
                    body = maybeBody.get();
                }
                PhysicsVector localHit = add(localOrigin, scale(localDirection, distance));
                PhysicsVector worldHit = transform.localToWorld(localHit);
                best = new AssemblyPickResult(
                        assembly.id(),
                        body,
                        block,
                        block.localPos(),
                        block.blockState(),
                        worldHit,
                        localHit,
                        distance
                );
            }
            if (missingBody) {
                discardVisuals(level, assembly);
                container.remove(assembly.id());
            }
        }
        return Optional.ofNullable(best);
    }

    public Optional<AssemblyBreakResult> breakPickedBlock(
            ServerLevel level,
            PhysicsVector worldOrigin,
            PhysicsVector worldDirection,
            double maxDistance
    ) {
        return breakPickedBlockIfMatches(level, worldOrigin, worldDirection, maxDistance, null, null);
    }

    public Optional<AssemblyBreakResult> breakPickedBlockIfMatches(
            ServerLevel level,
            PhysicsVector worldOrigin,
            PhysicsVector worldDirection,
            double maxDistance,
            AssemblyId expectedId,
            BlockPos expectedLocalPos
    ) {
        Objects.requireNonNull(level, "level");
        Optional<AssemblyPickResult> maybePick = pickBlock(level, worldOrigin, worldDirection, maxDistance);
        if (maybePick.isEmpty()) {
            return Optional.empty();
        }

        AssemblyPickResult pick = maybePick.get();
        if (expectedId != null && !expectedId.equals(pick.id())) {
            return Optional.empty();
        }
        if (expectedLocalPos != null && !expectedLocalPos.equals(pick.localPos())) {
            return Optional.empty();
        }

        ServerAssemblyContainer container = AssemblyContainers.requireServer(level);
        Optional<PhysicsAssembly> maybeAssembly = container.assembly(pick.id());
        if (maybeAssembly.isEmpty()) {
            return Optional.empty();
        }
        PhysicsAssembly assembly = maybeAssembly.get();

        recordRemovedPlotProjection(level, assembly, pick.body(), assembly.plot().toPlotBlockPos(pick.localPos()));
        AssemblyPlotProjection trackingProjection = trackingProjection(assembly, pick.body());
        RemovedBlocks removedBlocks = removeBlockAndDependents(level, assembly, pick.body(), pick.localPos(), DropMode.RESOURCES, true);
        if (removedBlocks.isEmpty()) {
            return Optional.empty();
        }

        int removedVisuals = countVisualsForBlocks(assembly, removedBlocks.localPositions());
        boolean removedAssembly = false;
        SplitResult splitResult = SplitResult.notSplit(assembly.section().blockCount());
        if (assembly.section().isEmpty()) {
            projectTrackingPointsBeforePermanentRemoval(level, trackingProjection);
            discardVisuals(level, pick.body(), assembly);
            prepareAssemblyRemoval(level, assembly);
            KineticAssembly.api().existingWorld(level).ifPresent(world -> world.removeBody(assembly.bodyId()));
            container.remove(assembly.id());
            removedAssembly = true;
        } else {
            splitResult = rebuildAfterBlockRemoval(level, container, assembly, pick.body(), removedBlocks);
            removedAssembly = splitResult.removedOriginal();
        }

        AssemblyBreakResult result = new AssemblyBreakResult(
                pick,
                removedAssembly,
                assembly.section().blockCount(),
                assembly.section().dirtyBlockCount(),
                removedVisuals,
                splitResult.components(),
                splitResult.createdAssemblies()
        );
        return Optional.of(result);
    }

    public Optional<AssemblyBreakResult> breakPlotBlock(ServerLevel level, BlockPos plotPos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        Optional<PhysicsAssembly> maybeAssembly = assemblyAtPlotBlock(level, plotPos);
        if (maybeAssembly.isEmpty()) {
            return Optional.empty();
        }

        PhysicsAssembly assembly = maybeAssembly.get();
        BlockPos localPos = assembly.plot().toSectionLocalPos(plotPos);
        Optional<AssemblyBlock> maybeBlock = assembly.section().block(localPos);
        if (maybeBlock.isEmpty()) {
            return Optional.empty();
        }

        Optional<MechanicsBodySnapshot> maybeBody = KineticAssembly.api().existingWorld(level)
                .flatMap(world -> world.snapshot(assembly.bodyId()));
        if (maybeBody.isEmpty()) {
            return Optional.empty();
        }

        AssemblyBlock block = maybeBlock.get();
        AABB bounds = block.localCollisionBounds();
        PhysicsVector localHit = new PhysicsVector(
                block.visualLocalOrigin().x() + (bounds.minX + bounds.maxX) * 0.5D,
                block.visualLocalOrigin().y() + (bounds.minY + bounds.maxY) * 0.5D,
                block.visualLocalOrigin().z() + (bounds.minZ + bounds.maxZ) * 0.5D
        );
        PhysicsVector worldHit = AssemblyTransform.from(maybeBody.get()).localToWorld(localHit);
        AssemblyPickResult pick = new AssemblyPickResult(
                assembly.id(),
                maybeBody.get(),
                block,
                localPos,
                block.blockState(),
                worldHit,
                localHit,
                0.0D
        );
        recordRemovedPlotProjection(level, assembly, maybeBody.get(), plotPos);
        AssemblyPlotProjection trackingProjection = trackingProjection(assembly, maybeBody.get());
        RemovedBlocks removedBlocks = removeBlockAndDependents(level, assembly, maybeBody.get(), localPos, DropMode.RESOURCES, true);
        if (removedBlocks.isEmpty()) {
            return Optional.empty();
        }

        int removedVisuals = countVisualsForBlocks(assembly, removedBlocks.localPositions());
        boolean removedAssembly = false;
        SplitResult splitResult = SplitResult.notSplit(assembly.section().blockCount());
        if (assembly.section().isEmpty()) {
            projectTrackingPointsBeforePermanentRemoval(level, trackingProjection);
            discardVisuals(level, maybeBody.get(), assembly);
            prepareAssemblyRemoval(level, assembly);
            KineticAssembly.api().existingWorld(level).ifPresent(world -> world.removeBody(assembly.bodyId()));
            AssemblyContainers.requireServer(level).remove(assembly.id());
            removedAssembly = true;
        } else {
            splitResult = rebuildAfterBlockRemoval(level, AssemblyContainers.requireServer(level), assembly, maybeBody.get(), removedBlocks);
            removedAssembly = splitResult.removedOriginal();
        }

        AssemblyBreakResult result = new AssemblyBreakResult(
                pick,
                removedAssembly,
                assembly.section().blockCount(),
                assembly.section().dirtyBlockCount(),
                removedVisuals,
                splitResult.components(),
                splitResult.createdAssemblies()
        );
        return Optional.of(result);
    }

    public Optional<AssemblySnapshot> restoreOriginal(ServerLevel level, AssemblyId id) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        ServerAssemblyContainer container = AssemblyContainers.requireServer(level);
        Optional<PhysicsAssembly> maybeAssembly = container.assembly(id);
        if (maybeAssembly.isEmpty()) {
            return Optional.empty();
        }
        PhysicsAssembly assembly = maybeAssembly.get();

        Optional<MechanicsWorld> maybeWorld = KineticAssembly.api().existingWorld(level);
        Optional<MechanicsBodySnapshot> maybeBody = maybeWorld.flatMap(world -> world.snapshot(assembly.bodyId()));
        if (maybeBody.isEmpty()) {
            discardVisuals(level, assembly);
            prepareAssemblyRemoval(level, assembly);
            container.remove(id);
            return Optional.empty();
        }
        AssemblyPlotProjection trackingProjection = trackingProjection(assembly, maybeBody.get());

        for (AssemblyBlock block : assembly.blocks()) {
            BlockState currentState = level.getBlockState(block.sourcePos());
            if (!currentState.isAir()) {
                throw new IllegalStateException("Cannot restore " + id + "; source position " + describePos(block.sourcePos()) + " is occupied");
            }
        }
        Map<BlockPos, CompoundTag> blockEntityTags = snapshotBlockEntityTagsBySource(level, assembly);
        for (AssemblyBlock block : assembly.blocks()) {
            if (!level.setBlock(block.sourcePos(), block.blockState(), BLOCK_UPDATE_FLAGS)) {
                throw new IllegalStateException("Failed to restore block at " + describePos(block.sourcePos()));
            }
            CompoundTag blockEntityTag = blockEntityTags.get(block.sourcePos());
            if (blockEntityTag != null) {
                restoreSourceBlockEntity(level, block, blockEntityTag);
            }
        }
        container.movePlotScheduledTicksToSource(assembly);
        AssemblyAssembler.refreshTerrainAround(level, assembly.blocks());
        AssemblyEntityBridge.restoreAttachedEntitiesToSource(level, assembly);

        projectTrackingPointsBeforePermanentRemoval(level, trackingProjection);
        discardVisuals(level, maybeBody.get(), assembly);
        prepareAssemblyRemoval(level, assembly);
        maybeWorld.ifPresent(world -> world.removeBody(assembly.bodyId()));
        container.remove(id);
        AssemblySnapshot snapshot = snapshot(maybeBody.get(), assembly);
        return Optional.of(snapshot);
    }

    public Optional<AssemblySnapshot> remove(ServerLevel level, AssemblyId id) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        ServerAssemblyContainer container = AssemblyContainers.requireServer(level);
        Optional<PhysicsAssembly> maybeAssembly = container.assembly(id);
        if (maybeAssembly.isEmpty()) {
            return Optional.empty();
        }
        PhysicsAssembly assembly = maybeAssembly.get();

        Optional<MechanicsWorld> maybeWorld = KineticAssembly.api().existingWorld(level);
        Optional<MechanicsBodySnapshot> maybeBody = maybeWorld.flatMap(world -> world.snapshot(assembly.bodyId()));
        maybeBody.ifPresentOrElse(
                body -> discardVisuals(level, body, assembly),
                () -> discardVisuals(level, assembly)
        );
        maybeBody.ifPresent(body -> projectTrackingPointsBeforePermanentRemoval(level, trackingProjection(assembly, body)));
        prepareAssemblyRemoval(level, assembly);
        maybeWorld.ifPresent(world -> world.removeBody(assembly.bodyId()));
        container.remove(id);
        Optional<AssemblySnapshot> snapshot = maybeBody.map(body -> snapshot(body, assembly));
        return snapshot;
    }

    public int forgetLevel(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        ServerAssemblyContainer container = AssemblyContainers.requireServer(level);
        int removed = 0;
        for (PhysicsAssembly assembly : container.assemblies()) {
            discardVisuals(level, assembly);
            prepareAssemblyRemoval(level, assembly);
            container.remove(assembly.id());
            removed++;
        }
        return removed;
    }

    public void close(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        for (ServerLevel level : server.getAllLevels()) {
            ServerAssemblyContainer container = AssemblyContainers.server(level).orElse(null);
            if (container == null) {
                continue;
            }
            for (PhysicsAssembly assembly : container.assemblies()) {
                discardVisuals(level, assembly);
                prepareAssemblyRemoval(level, assembly);
            }
            container.clear();
            AssemblyEntityBridge.discardAttachedEntities(level);
        }
    }

    private void prepareAssemblyRemoval(ServerLevel level, PhysicsAssembly assembly) {
        assembly.markRemoving();
        AssemblyEntityBridge.discardAttachedEntities(level, assembly);
        assembly.clearBlockEntities();
    }

    private static AssemblyPlotProjection trackingProjection(PhysicsAssembly assembly, MechanicsBodySnapshot body) {
        return new AssemblyPlotProjection(
                assembly.id(),
                assembly.plot(),
                AssemblyCoordinateSpace.bodyToPlotOrigin(assembly),
                AssemblyPoseFrame.initial(assembly.id(), body.pose())
        );
    }

    private static void projectTrackingPointsBeforePermanentRemoval(
            ServerLevel level,
            AssemblyPlotProjection projection
    ) {
        AssemblyTrackingPointSavedData.getOrLoad(level).projectAssemblyTrackingPoints(projection);
    }

    private void createVisuals(ServerLevel level, MechanicsBodySnapshot body, PhysicsAssembly assembly) {
        assembly.setDebugVisualsEnabled(true);
        AssemblyDebugVisuals.create(level, body, assembly);
    }

    private void syncVisuals(ServerLevel level, MechanicsBodySnapshot body, PhysicsAssembly assembly) {
        AssemblyDebugVisuals.sync(level, body, assembly);
    }

    private void discardVisuals(ServerLevel level, PhysicsAssembly assembly) {
        AssemblyDebugVisuals.discard(level, assembly);
        assembly.setDebugVisualsEnabled(false);
    }

    private void discardVisuals(ServerLevel level, MechanicsBodySnapshot body, PhysicsAssembly assembly) {
        AssemblyDebugVisuals.discard(level, body, assembly);
        assembly.setDebugVisualsEnabled(false);
    }

    private void refreshVisualsAfterBodyReplacement(
            ServerLevel level,
            PhysicsAssembly assembly,
            MechanicsBodySnapshot previousBody,
            MechanicsBodySnapshot replacementBody
    ) {
        if (!assembly.debugVisualsEnabled() && assembly.visuals().isEmpty()) {
            return;
        }
        discardVisuals(level, previousBody, assembly);
        discardVisuals(level, replacementBody, assembly);
        createVisuals(level, replacementBody, assembly);
    }

    private static int countVisualsForBlocks(PhysicsAssembly assembly, List<BlockPos> localPositions) {
        int count = 0;
        for (PhysicsAssembly.VisualBinding visual : assembly.visuals()) {
            if (localPositions.contains(visual.block().localPos())) {
                count++;
            }
        }
        return count;
    }

    private void removeStaleLevelEntries(ServerLevel level) {
        ServerAssemblyContainer container = AssemblyContainers.requireServer(level);
        for (PhysicsAssembly assembly : container.assemblies()) {
            discardVisuals(level, assembly);
            prepareAssemblyRemoval(level, assembly);
            container.remove(assembly.id());
        }
    }

    private void rebuildAssemblyBody(ServerLevel level, PhysicsAssembly assembly, MechanicsBodySnapshot previousBody) {
        rebuildAssemblyBody(level, assembly, previousBody, true);
    }

    private void rebuildAssemblyBody(
            ServerLevel level,
            PhysicsAssembly assembly,
            MechanicsBodySnapshot previousBody,
            boolean rebuildPlotChunks
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(assembly, "assembly");
        Objects.requireNonNull(previousBody, "previousBody");
        if (assembly.section().isEmpty()) {
            return;
        }
        if (!AssemblyAssembler.hasPhysicalCollision(assembly.blocks())) {
            throw new IllegalArgumentException("Assembly has no valid collision boxes");
        }

        MechanicsWorld world = KineticAssembly.api().world(level);
        ServerAssemblyContainer container = AssemblyContainers.requireServer(level);
        List<AssemblyBlock> previousBlocks = assembly.blocks();
        // Keep the actor frame on the current collision aggregate center across block edits.
        PhysicsVector bodyOriginShift = physicalBoundsCenterBodyLocal(previousBlocks);
        boolean bodyOriginChanged = !nearlyZero(bodyOriginShift);
        PhysicsPose replacementPose = recenteredPose(previousBody, bodyOriginShift);
        PhysicsVector replacementLinearVelocity = linearVelocityAt(previousBody, replacementPose.position());
        List<AssemblyBlock> replacementBlocks = recenteredBlocks(previousBlocks, bodyOriginShift);
        MechanicsBodySnapshot replacement = null;
        boolean blocksReplaced = false;
        try {
            replacement = world.createDynamicCompoundBox(AssemblyAssembler.compoundDefinition(
                    replacementPose,
                    replacementBlocks
            ));
            world.setLinearVelocity(replacement.id(), replacementLinearVelocity);
            world.setAngularVelocity(replacement.id(), previousBody.angularVelocity());
            if (bodyOriginChanged) {
                assembly.section().replaceBlocks(replacementBlocks);
                assembly.bumpShapeEpoch();
                blocksReplaced = true;
            }
            if (rebuildPlotChunks) {
                container.rebuildPlotChunks(assembly);
            }
        } catch (RuntimeException exception) {
            if (blocksReplaced) {
                assembly.section().replaceBlocks(previousBlocks);
                assembly.bumpShapeEpoch();
            }
            if (replacement != null) {
                world.removeBody(replacement.id());
            }
            throw exception;
        }

        MechanicsBodyId previousId = assembly.bodyId();
        assembly.replaceBody(replacement.id());
        ServerPhysicsRuntime.INSTANCE.replaceMechanicsBodyPreservingJoints(level, previousId, replacement.id());
        try {
            refreshVisualsAfterBodyReplacement(level, assembly, previousBody, replacement);
        } catch (RuntimeException exception) {
            KineticAssembly.LOGGER.warn("Failed to refresh debug visuals after replacing assembly body {}", assembly.id(), exception);
        }
        if (bodyOriginChanged) {
            container.trackingSystem().resyncAssemblyMetadata(assembly);
        }
    }

    private SplitResult rebuildAfterBlockRemoval(
            ServerLevel level,
            ServerAssemblyContainer container,
            PhysicsAssembly assembly,
            MechanicsBodySnapshot previousBody,
            RemovedBlocks removedBlocks
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(assembly, "assembly");
        Objects.requireNonNull(previousBody, "previousBody");
        Objects.requireNonNull(removedBlocks, "removedBlocks");
        AssemblyPlotProjection trackingProjection = trackingProjection(assembly, previousBody);
        if (assembly.section().isEmpty()) {
            return SplitResult.notSplit(0);
        }
        if (!AssemblyAssembler.hasPhysicalCollision(assembly.blocks())) {
            projectTrackingPointsBeforePermanentRemoval(level, trackingProjection);
            discardVisuals(level, previousBody, assembly);
            prepareAssemblyRemoval(level, assembly);
            KineticAssembly.api().existingWorld(level).ifPresent(world -> world.removeBody(assembly.bodyId()));
            container.remove(assembly.id());
            return new SplitResult(true, 0, 0);
        }

        List<List<AssemblyBlock>> components = connectedComponents(assembly);
        if (components.size() <= 1) {
            assembly.refreshBoundsFromBlocks();
            assembly.markDirty();
            boolean syncedInPlace = syncRemovedPlotBlocksInPlace(level, container, assembly, removedBlocks);
            rebuildAssemblyBody(level, assembly, previousBody, !syncedInPlace);
            return SplitResult.notSplit(1);
        }

        List<List<AssemblyBlock>> physicalComponents = components.stream()
                .filter(AssemblyAssembler::hasPhysicalCollision)
                .toList();
        if (physicalComponents.size() <= 1) {
            assembly.refreshBoundsFromBlocks();
            assembly.markDirty();
            boolean syncedInPlace = syncRemovedPlotBlocksInPlace(level, container, assembly, removedBlocks);
            rebuildAssemblyBody(level, assembly, previousBody, !syncedInPlace);
            return SplitResult.notSplit(1);
        }

        int created = splitAssembly(level, container, assembly, previousBody, physicalComponents, trackingProjection);
        splitEvents++;
        splitCreatedAssemblies += created;
        return new SplitResult(true, physicalComponents.size(), created);
    }

    private static boolean syncRemovedPlotBlocksInPlace(
            ServerLevel level,
            ServerAssemblyContainer container,
            PhysicsAssembly assembly,
            RemovedBlocks removedBlocks
    ) {
        if (removedBlocks.isEmpty()) {
            return true;
        }

        boolean synced = true;
        BlockState air = Blocks.AIR.defaultBlockState();
        for (Map.Entry<BlockPos, AssemblyBlock> entry : removedBlocks.blocksByLocalPos().entrySet()) {
            BlockPos plotPos = assembly.plot().toPlotBlockPos(entry.getKey());
            AssemblyBlock block = entry.getValue();
            boolean blockSynced = container.setPlotChunkBlockStateInPlace(plotPos, air, false);
            container.removePlotChunkBlockEntityInPlace(plotPos);
            if (blockSynced) {
                level.sendBlockUpdated(plotPos, block.blockState(), air, BLOCK_UPDATE_FLAGS);
            } else {
                synced = false;
            }
        }
        return synced;
    }

    private int splitAssembly(
            ServerLevel level,
            ServerAssemblyContainer container,
            PhysicsAssembly assembly,
            MechanicsBodySnapshot previousBody,
            List<List<AssemblyBlock>> components,
            AssemblyPlotProjection trackingProjection
    ) {
        MechanicsWorld world = KineticAssembly.api().world(level);
        List<SplitAssembly> created = new ArrayList<>(components.size());
        try {
            int totalBlocks = components.stream().mapToInt(List::size).sum();
            for (List<AssemblyBlock> component : components) {
                SplitComponent splitComponent = splitComponent(level, assembly, previousBody, component);
                float componentMass = splitMass(previousBody.mass(), component.size(), totalBlocks);
                MechanicsBodySnapshot body = world.createDynamicCompoundBox(AssemblyAssembler.compoundDefinition(
                        splitComponent.pose(),
                        splitComponent.blocks(),
                        componentMass
                ));
                inheritSplitMotion(world, previousBody, body, splitComponent);
                PhysicsAssembly child = new PhysicsAssembly(
                        AssemblyId.random(),
                        level.dimension(),
                        container.allocatePlot(splitComponent.bounds()),
                        body.id(),
                        splitComponent.bounds(),
                        splitComponent.blocks()
                );
                created.add(new SplitAssembly(child, body));
                container.add(child);
                container.movePlotScheduledTicksToChild(assembly, child);
                child.activate();
                if (assembly.debugVisualsEnabled() || !assembly.visuals().isEmpty()) {
                    createVisuals(level, body, child);
                }
            }
        } catch (RuntimeException exception) {
            for (SplitAssembly split : created) {
                discardVisuals(level, split.body(), split.assembly());
                prepareAssemblyRemoval(level, split.assembly());
                container.remove(split.assembly().id());
                world.removeBody(split.body().id());
            }
            throw exception;
        }

        projectTrackingPointsBeforePermanentRemoval(level, trackingProjection);
        discardVisuals(level, previousBody, assembly);
        AssemblyEntityBridge.transferAttachedEntitiesToChildren(
                level,
                assembly,
                created.stream().map(SplitAssembly::assembly).toList()
        );
        prepareAssemblyRemoval(level, assembly);
        world.removeBody(assembly.bodyId());
        container.remove(assembly.id());
        return created.size();
    }

    private SplitComponent splitComponent(
            ServerLevel level,
            PhysicsAssembly assembly,
            MechanicsBodySnapshot previousBody,
            List<AssemblyBlock> component
    ) {
        AssemblyBounds actualBounds = boundsForComponent(assembly, component);
        AssemblyBounds bounds = AssemblyAssembler.framedBounds(level, actualBounds);
        PhysicsVector componentCenterBodyLocal = physicalBoundsCenterBodyLocal(component);
        PhysicsVector newWorldPosition = AssemblyTransform.from(previousBody).localToWorld(componentCenterBodyLocal);
        PhysicsPose pose = new PhysicsPose(newWorldPosition, previousBody.pose().rotation());

        List<AssemblyBlock> splitBlocks = component.stream()
                .map(block -> {
                    BlockPos newLocalPos = bounds.toLocal(block.sourcePos());
                    PhysicsVector visualLocalOrigin = subtract(block.visualLocalOrigin(), componentCenterBodyLocal);
                    return new AssemblyBlock(
                            block.sourcePos(),
                            newLocalPos.immutable(),
                            block.blockState(),
                            block.localCollisionBounds(),
                            block.localCollisionBoxes(),
                            visualLocalOrigin,
                            splitBlockEntityTag(level, assembly, block)
                    );
                })
                .toList();
        return new SplitComponent(bounds, splitBlocks, pose);
    }

    private static void inheritSplitMotion(
            MechanicsWorld world,
            MechanicsBodySnapshot previousBody,
            MechanicsBodySnapshot childBody,
            SplitComponent splitComponent
    ) {
        world.setLinearVelocity(childBody.id(), splitLinearVelocity(previousBody, splitComponent));
        world.setAngularVelocity(childBody.id(), PhysicsVector.ZERO);
    }

    private static PhysicsVector splitLinearVelocity(MechanicsBodySnapshot previousBody, SplitComponent splitComponent) {
        PhysicsVector parentToChildCenter = subtract(splitComponent.pose().position(), previousBody.pose().position());
        PhysicsVector inheritedRotationVelocity = cross(previousBody.angularVelocity(), parentToChildCenter);
        return finiteOrZero(add(previousBody.linearVelocity(), inheritedRotationVelocity));
    }

    private static PhysicsVector physicalBoundsCenterBodyLocal(List<AssemblyBlock> blocks) {
        AABB bounds = null;
        for (AssemblyBlock block : blocks) {
            for (AABB box : block.bodyLocalCollisionBoxes()) {
                bounds = bounds == null ? copy(box) : bounds.minmax(box);
            }
        }
        if (bounds == null) {
            return PhysicsVector.ZERO;
        }
        return new PhysicsVector(
                (bounds.minX + bounds.maxX) * 0.5D,
                (bounds.minY + bounds.maxY) * 0.5D,
                (bounds.minZ + bounds.maxZ) * 0.5D
        );
    }

    private static PhysicsPose recenteredPose(MechanicsBodySnapshot previousBody, PhysicsVector bodyOriginShift) {
        if (nearlyZero(bodyOriginShift)) {
            return previousBody.pose();
        }
        PhysicsVector position = AssemblyTransform.from(previousBody).localToWorld(bodyOriginShift);
        return new PhysicsPose(position, previousBody.pose().rotation());
    }

    private static List<AssemblyBlock> recenteredBlocks(List<AssemblyBlock> blocks, PhysicsVector bodyOriginShift) {
        if (nearlyZero(bodyOriginShift)) {
            return blocks;
        }
        return blocks.stream()
                .map(block -> block.withVisualLocalOrigin(subtract(block.visualLocalOrigin(), bodyOriginShift)))
                .toList();
    }

    private static PhysicsVector linearVelocityAt(MechanicsBodySnapshot previousBody, PhysicsVector worldPosition) {
        PhysicsVector previousToNewOrigin = subtract(worldPosition, previousBody.pose().position());
        PhysicsVector inheritedRotationVelocity = cross(previousBody.angularVelocity(), previousToNewOrigin);
        return finiteOrZero(add(previousBody.linearVelocity(), inheritedRotationVelocity));
    }

    private static boolean nearlyZero(PhysicsVector vector) {
        return Math.abs(vector.x()) <= BODY_ORIGIN_EPSILON
                && Math.abs(vector.y()) <= BODY_ORIGIN_EPSILON
                && Math.abs(vector.z()) <= BODY_ORIGIN_EPSILON;
    }

    private static AABB copy(AABB box) {
        return new AABB(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private static List<List<AssemblyBlock>> connectedComponents(PhysicsAssembly assembly) {
        Map<BlockPos, AssemblyBlock> blocksByLocalPos = new LinkedHashMap<>();
        for (AssemblyBlock block : assembly.blocks()) {
            blocksByLocalPos.put(block.localPos(), block);
        }

        List<List<AssemblyBlock>> components = new ArrayList<>();
        Set<BlockPos> visited = new LinkedHashSet<>();
        for (AssemblyBlock start : assembly.blocks()) {
            if (!visited.add(start.localPos())) {
                continue;
            }

            List<AssemblyBlock> component = new ArrayList<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start.localPos());
            while (!queue.isEmpty()) {
                BlockPos current = queue.removeFirst();
                AssemblyBlock block = blocksByLocalPos.get(current);
                if (block == null) {
                    continue;
                }
                component.add(block);
                for (Direction direction : Direction.values()) {
                    BlockPos neighbor = current.relative(direction);
                    if (visited.contains(neighbor) || !blocksByLocalPos.containsKey(neighbor)) {
                        continue;
                    }
                    visited.add(neighbor);
                    queue.add(neighbor);
                }
            }
            components.add(List.copyOf(component));
        }
        return List.copyOf(components);
    }

    private static AssemblyBounds boundsForComponent(PhysicsAssembly assembly, List<AssemblyBlock> blocks) {
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("component must not be empty");
        }

        BlockPos minLocal = blocks.getFirst().localPos();
        int minX = minLocal.getX();
        int minY = minLocal.getY();
        int minZ = minLocal.getZ();
        int maxX = minX;
        int maxY = minY;
        int maxZ = minZ;
        for (AssemblyBlock block : blocks) {
            BlockPos localPos = block.localPos();
            minX = Math.min(minX, localPos.getX());
            minY = Math.min(minY, localPos.getY());
            minZ = Math.min(minZ, localPos.getZ());
            maxX = Math.max(maxX, localPos.getX());
            maxY = Math.max(maxY, localPos.getY());
            maxZ = Math.max(maxZ, localPos.getZ());
        }

        BlockPos sourceOrigin = assembly.section().toSourcePos(new BlockPos(minX, minY, minZ));
        return new AssemblyBounds(
                sourceOrigin,
                sourceOrigin,
                assembly.section().toSourcePos(new BlockPos(maxX, maxY, maxZ))
        );
    }

    private static CompoundTag splitBlockEntityTag(ServerLevel level, PhysicsAssembly assembly, AssemblyBlock block) {
        return currentBlockEntityTag(level, assembly, block);
    }

    private static Map<BlockPos, CompoundTag> snapshotBlockEntityTagsBySource(ServerLevel level, PhysicsAssembly assembly) {
        Map<BlockPos, CompoundTag> tagsBySource = new LinkedHashMap<>();
        for (AssemblyBlock block : assembly.blocks()) {
            CompoundTag tag = currentBlockEntityTag(level, assembly, block);
            if (tag != null) {
                tagsBySource.put(block.sourcePos(), tag);
            }
        }
        return Map.copyOf(tagsBySource);
    }

    private static CompoundTag currentBlockEntityTag(ServerLevel level, PhysicsAssembly assembly, AssemblyBlock block) {
        BlockPos plotPos = assembly.plot().toPlotBlockPos(block.localPos());
        BlockEntity blockEntity = level.getBlockEntity(plotPos);
        if (blockEntity != null && !blockEntity.isRemoved()) {
            return blockEntity.saveWithFullMetadata(level.registryAccess());
        }
        Optional<BlockEntity> cached = assembly.blockEntity(block.localPos()).filter(entity -> !entity.isRemoved());
        if (cached.isPresent()) {
            return cached.get().saveWithFullMetadata(level.registryAccess());
        }
        return block.blockEntityTag();
    }

    private static void restoreSourceBlockEntity(ServerLevel level, AssemblyBlock block, CompoundTag tag) {
        if (!block.blockState().hasBlockEntity()) {
            return;
        }

        CompoundTag sourceTag = tag.copy();
        sourceTag.putInt("x", block.sourcePos().getX());
        sourceTag.putInt("y", block.sourcePos().getY());
        sourceTag.putInt("z", block.sourcePos().getZ());
        BlockEntity blockEntity = BlockEntity.loadStatic(block.sourcePos(), block.blockState(), sourceTag, level.registryAccess());
        if (blockEntity == null) {
            return;
        }

        blockEntity.setLevel(level);
        blockEntity.clearRemoved();
        level.setBlockEntity(blockEntity);
        blockEntity.setChanged();
    }

    private static float splitMass(float previousMass, int componentBlocks, int totalBlocks) {
        if (totalBlocks <= 0) {
            return previousMass;
        }
        return Math.max(0.001F, previousMass * ((float) componentBlocks / (float) totalBlocks));
    }

    private static AABB bodyLocalBoxToWorldBounds(AssemblyTransform transform, AABB localBox) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int xIndex = 0; xIndex < 2; xIndex++) {
            double x = xIndex == 0 ? localBox.minX : localBox.maxX;
            for (int yIndex = 0; yIndex < 2; yIndex++) {
                double y = yIndex == 0 ? localBox.minY : localBox.maxY;
                for (int zIndex = 0; zIndex < 2; zIndex++) {
                    double z = zIndex == 0 ? localBox.minZ : localBox.maxZ;
                    PhysicsVector world = transform.localToWorld(new PhysicsVector(x, y, z));
                    minX = Math.min(minX, world.x());
                    minY = Math.min(minY, world.y());
                    minZ = Math.min(minZ, world.z());
                    maxX = Math.max(maxX, world.x());
                    maxY = Math.max(maxY, world.y());
                    maxZ = Math.max(maxZ, world.z());
                }
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private void recordRemovedPlotProjection(
            ServerLevel level,
            PhysicsAssembly assembly,
            MechanicsBodySnapshot body,
            BlockPos plotPos
    ) {
        RemovedPlotProjectionKey key = new RemovedPlotProjectionKey(level.dimension(), plotPos.immutable());
        removedPlotProjections.put(key, new RemovedPlotProjection(
                assembly.plot(),
                bodyToPlotOrigin(assembly),
                AssemblyTransform.from(body)
        ));
    }

    private Optional<PlotProjection> removedPlotProjection(ServerLevel level, BlockPos plotPos) {
        RemovedPlotProjection projection = removedPlotProjections.get(new RemovedPlotProjectionKey(level.dimension(), plotPos));
        return projection == null ? Optional.empty() : Optional.of(projection);
    }

    private Optional<PhysicsAssembly> assemblyAtPlotBlock(ServerLevel level, BlockPos plotPos) {
        return AssemblyContainers.requireServer(level).assemblyAtPlotBlock(plotPos);
    }

    private static PhysicsVector plotPositionToBodyLocal(PhysicsAssembly assembly, Vec3 plotPosition) {
        return plotPositionToBodyLocal(assembly.plot(), bodyToPlotOrigin(assembly), plotPosition);
    }

    private static PhysicsVector plotPositionToBodyLocal(AssemblyPlot plot, PhysicsVector bodyToPlotOrigin, Vec3 plotPosition) {
        return new PhysicsVector(
                bodyToPlotOrigin.x() + plotPosition.x() - plot.minPlotX(),
                bodyToPlotOrigin.y() + plotPosition.y() - plot.minPlotY(),
                bodyToPlotOrigin.z() + plotPosition.z() - plot.minPlotZ()
        );
    }

    private static PhysicsVector visualLocalOriginFor(PhysicsAssembly assembly, BlockPos localPos) {
        List<AssemblyBlock> blocks = assembly.blocks();
        if (blocks.isEmpty()) {
            return new PhysicsVector(localPos.getX(), localPos.getY(), localPos.getZ());
        }
        AssemblyBlock first = blocks.getFirst();
        return new PhysicsVector(
                first.visualLocalOrigin().x() + localPos.getX() - first.localPos().getX(),
                first.visualLocalOrigin().y() + localPos.getY() - first.localPos().getY(),
                first.visualLocalOrigin().z() + localPos.getZ() - first.localPos().getZ()
        );
    }

    private static PhysicsVector bodyToPlotOrigin(PhysicsAssembly assembly) {
        return assembly.blocks().stream()
                .findFirst()
                .map(block -> new PhysicsVector(
                        block.visualLocalOrigin().x() - block.localPos().getX(),
                        block.visualLocalOrigin().y() - block.localPos().getY(),
                        block.visualLocalOrigin().z() - block.localPos().getZ()
                ))
                .orElse(PhysicsVector.ZERO);
    }

    private static BlockEntity createBlockEntity(ServerLevel level, BlockPos plotPos, AssemblyBlock block) {
        BlockEntity blockEntity = null;
        CompoundTag tag = block.blockEntityTag();
        if (tag != null) {
            CompoundTag plotTag = tag.copy();
            plotTag.putInt("x", plotPos.getX());
            plotTag.putInt("y", plotPos.getY());
            plotTag.putInt("z", plotPos.getZ());
            blockEntity = BlockEntity.loadStatic(plotPos, block.blockState(), plotTag, level.registryAccess());
        }
        if (blockEntity == null && block.blockState().getBlock() instanceof EntityBlock entityBlock) {
            blockEntity = entityBlock.newBlockEntity(plotPos, block.blockState());
        }
        if (blockEntity != null) {
            blockEntity.setLevel(level);
            blockEntity.clearRemoved();
        }
        return blockEntity;
    }

    private static AssemblySnapshot snapshot(MechanicsBodySnapshot body, PhysicsAssembly assembly) {
        return new AssemblySnapshot(
                assembly.id(),
                assembly.levelKey(),
                assembly.state(),
                assembly.plot(),
                body,
                assembly.bounds(),
                assembly.blocks(),
                assembly.visuals().size(),
                assembly.section().dirtyBlockCount()
        );
    }

    private static Optional<Double> intersectBlock(PhysicsVector origin, PhysicsVector direction, AssemblyBlock block, double maxDistance) {
        Optional<Double> best = Optional.empty();
        List<AABB> pickBoxes = block.hasPhysicalCollision()
                ? block.bodyLocalCollisionBoxes()
                : List.of(block.bodyLocalBounds());
        for (AABB bounds : pickBoxes) {
            Optional<Double> maybeDistance = intersectAabb(origin, direction, bounds, maxDistance);
            if (maybeDistance.isEmpty()) {
                continue;
            }
            if (best.isEmpty() || maybeDistance.get() < best.get()) {
                best = maybeDistance;
            }
        }
        return best;
    }

    private static Optional<Double> intersectAabb(PhysicsVector origin, PhysicsVector direction, AABB bounds, double maxDistance) {
        RayInterval x = clipAxis(origin.x(), direction.x(), bounds.minX, bounds.maxX, 0.0D, maxDistance);
        if (x == null) {
            return Optional.empty();
        }
        RayInterval y = clipAxis(origin.y(), direction.y(), bounds.minY, bounds.maxY, x.min(), x.max());
        if (y == null) {
            return Optional.empty();
        }
        RayInterval z = clipAxis(origin.z(), direction.z(), bounds.minZ, bounds.maxZ, y.min(), y.max());
        if (z == null) {
            return Optional.empty();
        }
        return Optional.of(z.min());
    }

    private static RayInterval clipAxis(double origin, double direction, double min, double max, double tMin, double tMax) {
        if (Math.abs(direction) < 1.0E-12D) {
            return origin >= min && origin <= max ? new RayInterval(tMin, tMax) : null;
        }
        double invDirection = 1.0D / direction;
        double first = (min - origin) * invDirection;
        double second = (max - origin) * invDirection;
        if (first > second) {
            double tmp = first;
            first = second;
            second = tmp;
        }
        double clippedMin = Math.max(tMin, first);
        double clippedMax = Math.min(tMax, second);
        return clippedMin <= clippedMax ? new RayInterval(clippedMin, clippedMax) : null;
    }

    private static PhysicsVector normalize(PhysicsVector vector) {
        double length = Math.sqrt(vector.x() * vector.x() + vector.y() * vector.y() + vector.z() * vector.z());
        if (length <= 1.0E-12D || Double.isNaN(length)) {
            throw new IllegalArgumentException("Ray direction must be non-zero");
        }
        return new PhysicsVector(vector.x() / length, vector.y() / length, vector.z() / length);
    }

    private static PhysicsVector add(PhysicsVector first, PhysicsVector second) {
        return new PhysicsVector(first.x() + second.x(), first.y() + second.y(), first.z() + second.z());
    }

    private static PhysicsVector subtract(PhysicsVector first, PhysicsVector second) {
        return new PhysicsVector(first.x() - second.x(), first.y() - second.y(), first.z() - second.z());
    }

    private static PhysicsVector add(PhysicsVector first, BlockPos second) {
        return new PhysicsVector(first.x() + second.getX(), first.y() + second.getY(), first.z() + second.getZ());
    }

    private static PhysicsVector scale(PhysicsVector vector, double scalar) {
        return new PhysicsVector(vector.x() * scalar, vector.y() * scalar, vector.z() * scalar);
    }

    private static PhysicsVector cross(PhysicsVector first, PhysicsVector second) {
        return new PhysicsVector(
                first.y() * second.z() - first.z() * second.y(),
                first.z() * second.x() - first.x() * second.z(),
                first.x() * second.y() - first.y() * second.x()
        );
    }

    private static PhysicsVector finiteOrZero(PhysicsVector vector) {
        if (!Double.isFinite(vector.x()) || !Double.isFinite(vector.y()) || !Double.isFinite(vector.z())) {
            return PhysicsVector.ZERO;
        }
        return vector;
    }

    private static String describePos(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private record RayInterval(double min, double max) {
    }

    private record SplitResult(boolean removedOriginal, int components, int createdAssemblies) {
        private SplitResult {
            if (components < 0) {
                throw new IllegalArgumentException("components must not be negative");
            }
            if (createdAssemblies < 0) {
                throw new IllegalArgumentException("createdAssemblies must not be negative");
            }
        }

        private static SplitResult notSplit(int components) {
            return new SplitResult(false, components, 0);
        }
    }

    private record SplitComponent(AssemblyBounds bounds, List<AssemblyBlock> blocks, PhysicsPose pose) {
        private SplitComponent {
            Objects.requireNonNull(bounds, "bounds");
            blocks = List.copyOf(blocks);
            Objects.requireNonNull(pose, "pose");
        }
    }

    private record SplitAssembly(PhysicsAssembly assembly, MechanicsBodySnapshot body) {
        private SplitAssembly {
            Objects.requireNonNull(assembly, "assembly");
            Objects.requireNonNull(body, "body");
        }
    }

    private enum DropMode {
        NONE,
        CONTENTS,
        RESOURCES
    }

    private record RemovedBlocks(Map<BlockPos, AssemblyBlock> blocksByLocalPos) {
        private static final RemovedBlocks EMPTY = new RemovedBlocks(Map.of());

        private RemovedBlocks {
            blocksByLocalPos = Map.copyOf(blocksByLocalPos);
        }

        private boolean isEmpty() {
            return blocksByLocalPos.isEmpty();
        }

        private List<BlockPos> localPositions() {
            return List.copyOf(blocksByLocalPos.keySet());
        }
    }

    private record RemovedPlotProjectionKey(ResourceKey<Level> levelKey, BlockPos plotPos) {
        private RemovedPlotProjectionKey {
            Objects.requireNonNull(levelKey, "levelKey");
            Objects.requireNonNull(plotPos, "plotPos");
            plotPos = plotPos.immutable();
        }
    }

    public record BridgeStats(
            long vanillaBreakActions,
            long vanillaUseActions,
            long vanillaBreakAccepted,
            long vanillaUseAccepted,
            long vanillaBreakRejected,
            long vanillaUseRejected,
            long plotBlockWrites,
            long splitEvents,
            long splitCreatedAssemblies
    ) {
    }

    public interface PlotProjection {
        PhysicsVector toWorld(Vec3 plotPosition);

        PhysicsVector directionToWorld(Vec3 plotDirection);
    }

    private record ActivePlotProjection(PhysicsAssembly assembly, AssemblyTransform transform) implements PlotProjection {
        private ActivePlotProjection {
            Objects.requireNonNull(assembly, "assembly");
            Objects.requireNonNull(transform, "transform");
        }

        @Override
        public PhysicsVector toWorld(Vec3 plotPosition) {
            Objects.requireNonNull(plotPosition, "plotPosition");
            return transform.localToWorld(plotPositionToBodyLocal(assembly, plotPosition));
        }

        @Override
        public PhysicsVector directionToWorld(Vec3 plotDirection) {
            Objects.requireNonNull(plotDirection, "plotDirection");
            return transform.localDirectionToWorld(new PhysicsVector(plotDirection.x(), plotDirection.y(), plotDirection.z()));
        }
    }

    private record RemovedPlotProjection(
            AssemblyPlot plot,
            PhysicsVector bodyToPlotOrigin,
            AssemblyTransform transform
    ) implements PlotProjection {
        private RemovedPlotProjection {
            Objects.requireNonNull(plot, "plot");
            Objects.requireNonNull(bodyToPlotOrigin, "bodyToPlotOrigin");
            Objects.requireNonNull(transform, "transform");
        }

        @Override
        public PhysicsVector toWorld(Vec3 plotPosition) {
            Objects.requireNonNull(plotPosition, "plotPosition");
            return transform.localToWorld(plotPositionToBodyLocal(plot, bodyToPlotOrigin, plotPosition));
        }

        @Override
        public PhysicsVector directionToWorld(Vec3 plotDirection) {
            Objects.requireNonNull(plotDirection, "plotDirection");
            return transform.localDirectionToWorld(new PhysicsVector(plotDirection.x(), plotDirection.y(), plotDirection.z()));
        }
    }
}
