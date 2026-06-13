package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.mixin.accessor.BlockAttachedEntityAccess;
import com.firedoge.kineticassembly.mixin.accessor.HangingEntityAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class AssemblyEntityBridge {
    private static final double EPSILON = 1.0E-7D;
    private static final double SEARCH_EPSILON = 1.0E-5D;
    private static final int MAX_ENTITY_INSIDE_BLOCKS = 512;
    private static final String ATTACHED_ENTITY_TAG = "kinetic_assembly_assembly_attached";
    private static final ThreadLocal<Boolean> PROJECTING_QUERY = ThreadLocal.withInitial(() -> false);
    private static final Map<AttachedEntityKey, AttachedEntity> ATTACHED_ENTITIES = new LinkedHashMap<>();

    private AssemblyEntityBridge() {
    }

    public static boolean isProjectingQuery() {
        return PROJECTING_QUERY.get();
    }

    public static List<Entity> rawEntities(
            ServerLevel level,
            @Nullable Entity excluded,
            AABB worldBounds,
            Predicate<? super Entity> predicate
    ) {
        return queryWorldEntities(level, excluded, worldBounds, predicate);
    }

    public static void registerPlotAttachedEntity(
            ServerLevel level,
            BlockAttachedEntity entity,
            BlockPos plotAnchor,
            Vec3 plotPosition,
            AABB plotBounds
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(plotAnchor, "plotAnchor");
        Objects.requireNonNull(plotPosition, "plotPosition");
        Objects.requireNonNull(plotBounds, "plotBounds");
        AttachedEntity attachedEntity = new AttachedEntity(
                level.dimension(),
                entity.getUUID(),
                plotAnchor.immutable(),
                plotPosition,
                plotBounds
        );
        ATTACHED_ENTITIES.put(attachedEntity.key(), attachedEntity);
        entity.addTag(ATTACHED_ENTITY_TAG);
        pinAttachedEntityToPlot(entity, attachedEntity);
    }

    public static List<AttachedEntityCapture> captureAttachedEntitiesForAssembly(
            ServerLevel level,
            AssemblyBounds bounds
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(bounds, "bounds");
        AABB sourceBounds = sourceBounds(bounds);
        AABB searchBounds = sourceBounds.inflate(2.0D);
        List<AttachedEntityCapture> captures = new ArrayList<>();
        for (HangingEntity entity : level.getEntitiesOfClass(HangingEntity.class, searchBounds, entity -> !entity.isRemoved())) {
            AABB supportBox = ((HangingEntityAccess) entity).kinetic_assembly$calculateSupportBox();
            if (!supportIntersectsSourceBlocks(level, supportBox, bounds)) {
                continue;
            }
            captures.add(new AttachedEntityCapture(
                    entity.getUUID(),
                    entity.getPos().immutable(),
                    entity.position(),
                    entity.getBoundingBox()
            ));
        }
        return List.copyOf(captures);
    }

    public static void registerCapturedAttachedEntities(
            ServerLevel level,
            PhysicsAssembly assembly,
            List<AttachedEntityCapture> captures
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(assembly, "assembly");
        Objects.requireNonNull(captures, "captures");
        for (AttachedEntityCapture capture : captures) {
            Entity entity = level.getEntity(capture.entityId());
            if (!(entity instanceof BlockAttachedEntity blockAttachedEntity) || entity.isRemoved()) {
                continue;
            }
            registerPlotAttachedEntity(
                    level,
                    blockAttachedEntity,
                    sourceToPlotBlock(assembly, capture.sourceAnchor()),
                    sourceToPlot(assembly, capture.sourcePosition()),
                    sourceToPlot(assembly, capture.sourceBounds())
            );
        }
    }

    public static void unregisterPlotAttachedEntity(ServerLevel level, Entity entity) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(entity, "entity");
        ATTACHED_ENTITIES.remove(new AttachedEntityKey(level.dimension(), entity.getUUID()));
        entity.removeTag(ATTACHED_ENTITY_TAG);
    }

    public static int discardAttachedEntities(ServerLevel level, PhysicsAssembly assembly) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(assembly, "assembly");
        int discarded = 0;
        for (AttachedEntity attachedEntity : List.copyOf(ATTACHED_ENTITIES.values())) {
            if (!attachedEntity.levelKey().equals(level.dimension())
                    || !containsAttachedEntityAnchor(assembly, attachedEntity.plotAnchor())) {
                continue;
            }
            discardAttachedEntity(level, attachedEntity);
            discarded++;
        }
        return discarded;
    }

    public static int restoreAttachedEntitiesToSource(ServerLevel level, PhysicsAssembly assembly) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(assembly, "assembly");
        int restored = 0;
        for (AttachedEntity attachedEntity : List.copyOf(ATTACHED_ENTITIES.values())) {
            if (!attachedEntity.levelKey().equals(level.dimension())
                    || !containsAttachedEntityAnchor(assembly, attachedEntity.plotAnchor())) {
                continue;
            }

            Entity entity = level.getEntity(attachedEntity.entityId());
            ATTACHED_ENTITIES.remove(attachedEntity.key());
            if (entity instanceof BlockAttachedEntity blockAttachedEntity && !entity.isRemoved()) {
                entity.removeTag(ATTACHED_ENTITY_TAG);
                pinAttachedEntity(
                        blockAttachedEntity,
                        plotToSourceBlock(assembly, attachedEntity.plotAnchor()),
                        plotToSource(assembly, attachedEntity.plotPosition()),
                        plotToSource(assembly, attachedEntity.plotBounds())
                );
                restored++;
            }
        }
        return restored;
    }

    public static int transferAttachedEntitiesToChildren(
            ServerLevel level,
            PhysicsAssembly sourceAssembly,
            List<PhysicsAssembly> childAssemblies
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(sourceAssembly, "sourceAssembly");
        Objects.requireNonNull(childAssemblies, "childAssemblies");
        if (childAssemblies.isEmpty()) {
            return 0;
        }

        int transferred = 0;
        for (AttachedEntity attachedEntity : List.copyOf(ATTACHED_ENTITIES.values())) {
            if (!attachedEntity.levelKey().equals(level.dimension())
                    || !containsAttachedEntityAnchor(sourceAssembly, attachedEntity.plotAnchor())) {
                continue;
            }

            Entity entity = level.getEntity(attachedEntity.entityId());
            if (!(entity instanceof BlockAttachedEntity blockAttachedEntity) || entity.isRemoved()) {
                ATTACHED_ENTITIES.remove(attachedEntity.key());
                continue;
            }

            BlockPos sourceAnchor = plotToSourceBlock(sourceAssembly, attachedEntity.plotAnchor());
            Vec3 sourcePosition = plotToSource(sourceAssembly, attachedEntity.plotPosition());
            AABB sourceBounds = plotToSource(sourceAssembly, attachedEntity.plotBounds());
            AABB sourceSupportBox = sourceSupportBox(sourceAssembly, entity).orElse(null);
            PhysicsAssembly target = attachedEntityTransferTarget(sourceAnchor, sourceSupportBox, childAssemblies);
            if (target == null) {
                continue;
            }

            AttachedEntity transferredEntity = new AttachedEntity(
                    level.dimension(),
                    attachedEntity.entityId(),
                    sourceToPlotBlock(target, sourceAnchor),
                    sourceToPlot(target, sourcePosition),
                    sourceToPlot(target, sourceBounds)
            );
            ATTACHED_ENTITIES.put(transferredEntity.key(), transferredEntity);
            pinAttachedEntityToPlot(blockAttachedEntity, transferredEntity);
            transferred++;
        }
        return transferred;
    }

    public static int discardAttachedEntities(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        int discarded = 0;
        for (AttachedEntity attachedEntity : List.copyOf(ATTACHED_ENTITIES.values())) {
            if (!attachedEntity.levelKey().equals(level.dimension())) {
                continue;
            }
            discardAttachedEntity(level, attachedEntity);
            discarded++;
        }
        return discarded;
    }

    public static void tickAttachedEntities(ServerLevel level, ServerAssemblyContainer container) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(container, "container");
        if (ATTACHED_ENTITIES.isEmpty()) {
            return;
        }

        for (AttachedEntity attachedEntity : List.copyOf(ATTACHED_ENTITIES.values())) {
            if (!attachedEntity.levelKey().equals(level.dimension())) {
                continue;
            }
            Entity entity = level.getEntity(attachedEntity.entityId());
            if (!(entity instanceof BlockAttachedEntity blockAttachedEntity) || entity.isRemoved()) {
                ATTACHED_ENTITIES.remove(attachedEntity.key());
                continue;
            }
            if (!containsAttachedEntityAnchor(container, attachedEntity.plotAnchor())) {
                ATTACHED_ENTITIES.remove(attachedEntity.key());
                entity.removeTag(ATTACHED_ENTITY_TAG);
                continue;
            }
        }
    }

    public static Optional<BlockState> attachedSupportBlockState(ServerLevel level, Entity entity) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(entity, "entity");
        AttachedEntity attachedEntity = ATTACHED_ENTITIES.get(new AttachedEntityKey(level.dimension(), entity.getUUID()));
        if (!isRegisteredAttachedEntity(entity, attachedEntity)) {
            return Optional.empty();
        }
        DirectionAccessor direction = directionAccessor(entity);
        if (direction == null) {
            return Optional.empty();
        }
        BlockPos plotSupport = attachedEntity.plotAnchor().relative(direction.direction().getOpposite());
        return Optional.of(level.getBlockState(plotSupport));
    }

    public static Optional<BlockPos> attachedPlotAnchor(ServerLevel level, Entity entity) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(entity, "entity");
        AttachedEntity attachedEntity = ATTACHED_ENTITIES.get(new AttachedEntityKey(level.dimension(), entity.getUUID()));
        if (!isRegisteredAttachedEntity(entity, attachedEntity)) {
            return Optional.empty();
        }
        return Optional.of(attachedEntity.plotAnchor());
    }

    public static Optional<Vec3> attachedPlotPosition(ServerLevel level, Entity entity) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(entity, "entity");
        AttachedEntity attachedEntity = ATTACHED_ENTITIES.get(new AttachedEntityKey(level.dimension(), entity.getUUID()));
        if (!isRegisteredAttachedEntity(entity, attachedEntity)) {
            return Optional.empty();
        }
        return Optional.of(attachedEntity.plotPosition());
    }

    public static Optional<BlockPos> attachedTrackingPlotBlock(ServerLevel level, Entity entity) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(entity, "entity");
        AttachedEntity attachedEntity = ATTACHED_ENTITIES.get(new AttachedEntityKey(level.dimension(), entity.getUUID()));
        if (!isRegisteredAttachedEntity(entity, attachedEntity)) {
            return Optional.empty();
        }
        ServerAssemblyContainer container = AssemblyContainers.server(level).orElse(null);
        if (container == null) {
            return Optional.empty();
        }

        BlockPos anchor = attachedEntity.plotAnchor();
        if (container.assemblyAtPlotBlock(anchor).isPresent()) {
            return Optional.of(anchor);
        }
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = anchor.relative(direction);
            if (container.assemblyAtPlotBlock(adjacent).isPresent()) {
                return Optional.of(adjacent);
            }
        }
        return Optional.empty();
    }

    public static boolean isRegisteredAttachedEntity(ServerLevel level, Entity entity) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(entity, "entity");
        AttachedEntity attachedEntity = ATTACHED_ENTITIES.get(new AttachedEntityKey(level.dimension(), entity.getUUID()));
        return isRegisteredAttachedEntity(entity, attachedEntity);
    }

    public static Optional<Boolean> attachedEntitySurvives(ServerLevel level, Entity entity) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(entity, "entity");
        AttachedEntity attachedEntity = ATTACHED_ENTITIES.get(new AttachedEntityKey(level.dimension(), entity.getUUID()));
        if (!isRegisteredAttachedEntity(entity, attachedEntity)) {
            return Optional.empty();
        }
        DirectionAccessor directionAccessor = directionAccessor(entity);
        if (directionAccessor == null) {
            return Optional.empty();
        }

        Direction direction = directionAccessor.direction();
        boolean supported = entity instanceof ItemFrame
                ? itemFrameSupportSurvives(level, attachedEntity, direction)
                : hangingSupportSurvives(level, attachedEntity, direction);
        if (!supported) {
            return Optional.of(false);
        }

        boolean noHangingOverlap = queryWorldEntities(
                level,
                entity,
                entity.getBoundingBox().inflate(SEARCH_EPSILON),
                other -> other instanceof HangingEntity
        ).isEmpty();
        return Optional.of(noHangingOverlap);
    }

    public static Optional<Boolean> plotAttachedEntitySurvives(Level level, Entity entity) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(entity, "entity");
        if (!(entity instanceof HangingEntity hangingEntity)) {
            return Optional.empty();
        }

        DirectionAccessor directionAccessor = directionAccessor(entity);
        if (directionAccessor == null) {
            return Optional.empty();
        }
        Direction direction = directionAccessor.direction();
        if (direction == null) {
            return Optional.empty();
        }

        AssemblyContainer container = AssemblyContainers.container(level).orElse(null);
        if (container == null || container.isEmpty()) {
            return Optional.empty();
        }

        return entity instanceof ItemFrame itemFrame
                ? plotItemFrameSurvives(level, container, itemFrame, direction)
                : plotHangingEntitySurvives(level, container, hangingEntity, direction);
    }

    public static Optional<AssemblyPlotProjection> plotProjectionAtOrAdjacent(Level level, BlockPos plotPos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        AssemblyContainer container = AssemblyContainers.container(level).orElse(null);
        if (container == null || container.isEmpty()) {
            return Optional.empty();
        }

        Optional<AssemblyPlotProjection> direct = container.plotProjection(plotPos);
        if (direct.isPresent()) {
            return direct;
        }
        for (Direction direction : Direction.values()) {
            Optional<AssemblyPlotProjection> adjacent = container.plotProjection(plotPos.relative(direction));
            if (adjacent.isPresent()) {
                return adjacent;
            }
        }
        return Optional.empty();
    }

    public static Optional<AABB> plotAttachedEntityWorldBounds(Level level, Entity entity) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(entity, "entity");
        if (!(entity instanceof BlockAttachedEntity)) {
            return Optional.empty();
        }
        return plotEntityWorldBounds(level, entity);
    }

    public static Optional<AABB> plotAttachedEntityWorldBounds(Level level, Entity entity, double partialTick) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(entity, "entity");
        if (!(entity instanceof BlockAttachedEntity)) {
            return Optional.empty();
        }
        return plotEntityWorldBounds(level, entity, partialTick);
    }

    public static Optional<AABB> plotEntityWorldBounds(Level level, Entity entity) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(entity, "entity");
        AssemblyPlotProjection projection = plotProjectionForRetainedEntity(level, entity)
                .orElse(null);
        if (projection == null) {
            return Optional.empty();
        }
        return plotBoundsToWorld(entity.getBoundingBox(), projection);
    }

    public static Optional<AABB> plotEntityWorldBounds(Level level, Entity entity, double partialTick) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(entity, "entity");
        AssemblyPlotProjection projection = plotProjectionForRetainedEntity(level, entity)
                .orElse(null);
        if (projection == null) {
            return Optional.empty();
        }
        return plotBoundsToWorld(entity.getBoundingBox(), projection, partialTick);
    }

    public static Optional<AssemblyPlotProjection> plotProjectionForRetainedEntity(Level level, Entity entity) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(entity, "entity");
        if (AssemblyEntityKicking.shouldKick(entity)) {
            return Optional.empty();
        }
        return plotProjectionAtOrAdjacent(level, BlockPos.containing(entity.position()));
    }

    public static boolean isDebugAttachedEntity(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        return isDebugAttachedEntityType(entity.getType());
    }

    public static boolean isDebugAttachedEntityType(EntityType<?> type) {
        Objects.requireNonNull(type, "type");
        return type == EntityType.ITEM_FRAME
                || type == EntityType.GLOW_ITEM_FRAME
                || type == EntityType.PAINTING;
    }

    public static void debugAttachedEntity(String stage, Level level, Entity entity, String detail) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(detail, "detail");
        if (!isDebugAttachedEntity(entity)) {
            return;
        }

        BlockPos anchor = entity instanceof BlockAttachedEntity attachedEntity ? attachedEntity.getPos() : null;
        KineticAssembly.LOGGER.info(
                "[kinetic_assembly-attached] {} side={} type={} id={} uuid={} pos={} blockPos={} anchor={} bbox={} {}",
                stage,
                level.isClientSide() ? "client" : "server",
                EntityType.getKey(entity.getType()),
                entity.getId(),
                entity.getUUID(),
                entity.position(),
                entity.blockPosition(),
                anchor,
                entity.getBoundingBox(),
                detail
        );
    }

    public static void broadcastAttachedEntityData(
            ServerLevel level,
            Entity entity,
            List<SynchedEntityData.DataValue<?>> values
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(values, "values");
        if (values.isEmpty() || !isRegisteredAttachedEntity(level, entity)) {
            return;
        }

        ServerAssemblyContainer container = AssemblyContainers.server(level).orElse(null);
        BlockPos trackingPlotBlock = attachedTrackingPlotBlock(level, entity).orElse(null);
        if (container == null || trackingPlotBlock == null) {
            return;
        }

        ClientboundSetEntityDataPacket packet = new ClientboundSetEntityDataPacket(entity.getId(), List.copyOf(values));
        int sent = 0;
        for (ServerPlayer player : container.trackingSystem().playersTracking(new ChunkPos(trackingPlotBlock))) {
            player.connection.send(packet);
            sent++;
        }
        debugAttachedEntity(
                "server-data-sync",
                level,
                entity,
                "values=" + values.size() + " players=" + sent + " trackingPlotBlock=" + trackingPlotBlock
        );
    }

    public static void tickEntityInside(ServerLevel level, ServerAssemblyContainer container) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(container, "container");
        if (container.isEmpty()) {
            return;
        }

        for (PhysicsAssembly assembly : container.assemblies()) {
            AABB plotBounds = plotBounds(assembly.plot()).inflate(EPSILON);
            AssemblyRuntimeState runtimeState = container.runtimeState(assembly);
            AABB worldSearchBounds = runtimeState.sweptBounds()
                    .orElseGet(() -> runtimeState.worldBounds().orElse(null));
            QueryTarget target = null;
            if (worldSearchBounds == null) {
                Optional<QueryTarget> maybeTarget = queryTarget(level, assembly);
                if (maybeTarget.isEmpty()) {
                    continue;
                }
                target = maybeTarget.get();
                worldSearchBounds = plotAabbToWorldAabb(target, plotBounds);
            }
            worldSearchBounds = worldSearchBounds.inflate(SEARCH_EPSILON);
            List<Entity> entities = queryWorldEntities(level, (Entity) null, worldSearchBounds, entity -> !entity.isRemoved());
            if (entities.isEmpty()) {
                continue;
            }
            if (target == null) {
                Optional<QueryTarget> maybeTarget = queryTarget(level, assembly);
                if (maybeTarget.isEmpty()) {
                    continue;
                }
                target = maybeTarget.get();
            }
            for (Entity entity : entities) {
                if (entity.level() != level || entity.isRemoved()) {
                    continue;
                }
                AABB projectedBounds = worldAabbToPlotAabb(target, entity.getBoundingBox()).inflate(EPSILON);
                if (projectedBounds.intersects(plotBounds)) {
                    dispatchEntityInside(level, assembly, entity, projectedBounds);
                }
            }
        }
    }

    public static List<Entity> projectedEntities(
            ServerLevel level,
            @Nullable Entity excluded,
            AABB plotBounds,
            Predicate<? super Entity> predicate,
            List<Entity> existing
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotBounds, "plotBounds");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(existing, "existing");
        if (PROJECTING_QUERY.get()) {
            return List.of();
        }

        List<QueryTarget> targets = queryTargets(level, plotBounds);
        if (targets.isEmpty()) {
            return List.of();
        }

        Set<Entity> seen = identitySet(existing);
        List<Entity> projected = new ArrayList<>();
        appendRegisteredAttachedEntities(level, excluded, plotBounds, predicate, seen, projected, Integer.MAX_VALUE);
        for (QueryTarget target : targets) {
            AABB worldSearchBounds = plotAabbToWorldAabb(target, plotBounds).inflate(SEARCH_EPSILON);
            for (Entity entity : queryWorldEntities(level, excluded, worldSearchBounds, predicate)) {
                if (!seen.add(entity)) {
                    continue;
                }
                if (worldAabbToPlotAabb(target, entity.getBoundingBox()).intersects(plotBounds)) {
                    projected.add(entity);
                }
            }
        }
        return List.copyOf(projected);
    }

    public static <T extends Entity> List<T> projectedEntities(
            ServerLevel level,
            EntityTypeTest<Entity, T> entityTypeTest,
            AABB plotBounds,
            Predicate<? super T> predicate,
            List<T> existing
    ) {
        return projectedEntities(level, entityTypeTest, plotBounds, predicate, existing, Integer.MAX_VALUE);
    }

    public static <T extends Entity> List<T> projectedEntities(
            ServerLevel level,
            EntityTypeTest<Entity, T> entityTypeTest,
            AABB plotBounds,
            Predicate<? super T> predicate,
            Iterable<?> existing,
            int maxAdditionalResults
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(entityTypeTest, "entityTypeTest");
        Objects.requireNonNull(plotBounds, "plotBounds");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(existing, "existing");
        if (PROJECTING_QUERY.get() || maxAdditionalResults <= 0) {
            return List.of();
        }

        List<QueryTarget> targets = queryTargets(level, plotBounds);
        if (targets.isEmpty()) {
            return List.of();
        }

        Set<Entity> seen = identityEntitySet(existing);
        List<T> projected = new ArrayList<>();
        appendRegisteredAttachedEntities(level, entityTypeTest, plotBounds, predicate, seen, projected, maxAdditionalResults);
        if (projected.size() >= maxAdditionalResults) {
            return List.copyOf(projected);
        }
        for (QueryTarget target : targets) {
            AABB worldSearchBounds = plotAabbToWorldAabb(target, plotBounds).inflate(SEARCH_EPSILON);
            for (T entity : queryWorldEntities(level, entityTypeTest, worldSearchBounds, predicate)) {
                if (!seen.add(entity)) {
                    continue;
                }
                if (worldAabbToPlotAabb(target, entity.getBoundingBox()).intersects(plotBounds)) {
                    projected.add(entity);
                    if (projected.size() >= maxAdditionalResults) {
                        return List.copyOf(projected);
                    }
                }
            }
        }
        return List.copyOf(projected);
    }

    private static boolean itemFrameSupportSurvives(ServerLevel level, AttachedEntity attachedEntity, Direction direction) {
        BlockPos plotSupport = attachedEntity.plotAnchor().relative(direction.getOpposite());
        BlockState support = level.getBlockState(plotSupport);
        return support.isSolid() || direction.getAxis().isHorizontal() && DiodeBlock.isDiode(support);
    }

    private static boolean hangingSupportSurvives(ServerLevel level, AttachedEntity attachedEntity, Direction direction) {
        AABB supportBox = attachedEntity.plotBounds()
                .move(-direction.getStepX() * 0.5D, -direction.getStepY() * 0.5D, -direction.getStepZ() * 0.5D)
                .deflate(EPSILON);
        return BlockPos.betweenClosedStream(supportBox)
                .filter(pos -> !Block.canSupportCenter(level, pos, direction))
                .allMatch(pos -> {
                    BlockState state = level.getBlockState(pos);
                    return state.isSolid() || DiodeBlock.isDiode(state);
                });
    }

    private static Optional<Boolean> plotItemFrameSurvives(
            Level level,
            AssemblyContainer container,
            ItemFrame itemFrame,
            Direction direction
    ) {
        BlockPos plotSupport = itemFrame.getPos().relative(direction.getOpposite());
        if (!isPlotBlock(container, plotSupport)) {
            debugAttachedEntity(
                    "survives",
                    level,
                    itemFrame,
                    "result=empty reason=support-not-in-plot direction=" + direction + " support=" + plotSupport
            );
            return Optional.empty();
        }

        BlockState support = level.getBlockState(plotSupport);
        boolean supported = support.isSolid() || direction.getAxis().isHorizontal() && DiodeBlock.isDiode(support);
        if (!supported) {
            debugAttachedEntity(
                    "survives",
                    level,
                    itemFrame,
                    "result=false reason=unsupported direction=" + direction + " support=" + plotSupport + " state=" + support
            );
            return Optional.of(false);
        }
        boolean overlapClear = plotHangingOverlapClear(level, itemFrame);
        debugAttachedEntity(
                "survives",
                level,
                itemFrame,
                "result=" + overlapClear + " reason=item-frame direction=" + direction + " support=" + plotSupport
                        + " state=" + support + " overlapClear=" + overlapClear
        );
        return Optional.of(overlapClear);
    }

    private static Optional<Boolean> plotHangingEntitySurvives(
            Level level,
            AssemblyContainer container,
            HangingEntity hangingEntity,
            Direction direction
    ) {
        AABB supportBox = ((HangingEntityAccess) hangingEntity).kinetic_assembly$calculateSupportBox();
        BlockPos min = BlockPos.containing(
                supportBox.minX + EPSILON,
                supportBox.minY + EPSILON,
                supportBox.minZ + EPSILON
        );
        BlockPos max = BlockPos.containing(
                supportBox.maxX - EPSILON,
                supportBox.maxY - EPSILON,
                supportBox.maxZ - EPSILON
        );
        boolean touchesPlot = false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    pos.set(x, y, z);
                    touchesPlot |= isPlotBlock(container, pos);
                    if (!Block.canSupportCenter(level, pos, direction)) {
                        BlockState state = level.getBlockState(pos);
                        if (!state.isSolid() && !DiodeBlock.isDiode(state)) {
                            debugAttachedEntity(
                                    "survives",
                                    level,
                                    hangingEntity,
                                    "result=false reason=unsupported direction=" + direction + " support=" + pos.immutable()
                                            + " state=" + state + " supportBox=" + supportBox
                            );
                            return Optional.of(false);
                        }
                    }
                }
            }
        }
        if (!touchesPlot) {
            debugAttachedEntity(
                    "survives",
                    level,
                    hangingEntity,
                    "result=empty reason=support-box-not-in-plot direction=" + direction + " supportBox=" + supportBox
            );
            return Optional.empty();
        }
        boolean overlapClear = plotHangingOverlapClear(level, hangingEntity);
        debugAttachedEntity(
                "survives",
                level,
                hangingEntity,
                "result=" + overlapClear + " reason=hanging direction=" + direction + " supportBox=" + supportBox
                        + " overlapClear=" + overlapClear
        );
        return Optional.of(overlapClear);
    }

    private static boolean plotHangingOverlapClear(Level level, HangingEntity entity) {
        return level.getEntities(
                EntityTypeTest.forClass(HangingEntity.class),
                entity.getBoundingBox().inflate(SEARCH_EPSILON),
                other -> other != entity
        ).isEmpty();
    }

    private static boolean isPlotBlock(AssemblyContainer container, BlockPos plotPos) {
        return container.plotProjection(plotPos).isPresent()
                || container.assemblyAtPlotBlock(plotPos).isPresent();
    }

    private static void appendRegisteredAttachedEntities(
            ServerLevel level,
            @Nullable Entity excluded,
            AABB plotBounds,
            Predicate<? super Entity> predicate,
            Set<Entity> seen,
            List<Entity> projected,
            int maxAdditionalResults
    ) {
        if (projected.size() >= maxAdditionalResults) {
            return;
        }
        for (AttachedEntity attachedEntity : ATTACHED_ENTITIES.values()) {
            if (!attachedEntity.levelKey().equals(level.dimension())
                    || !attachedEntity.plotBounds().intersects(plotBounds)) {
                continue;
            }
            Entity entity = level.getEntity(attachedEntity.entityId());
            if (entity == null || entity == excluded || entity.isRemoved() || !predicate.test(entity) || !seen.add(entity)) {
                continue;
            }
            projected.add(entity);
            if (projected.size() >= maxAdditionalResults) {
                return;
            }
        }
    }

    private static <T extends Entity> void appendRegisteredAttachedEntities(
            ServerLevel level,
            EntityTypeTest<Entity, T> entityTypeTest,
            AABB plotBounds,
            Predicate<? super T> predicate,
            Set<Entity> seen,
            List<T> projected,
            int maxAdditionalResults
    ) {
        if (projected.size() >= maxAdditionalResults) {
            return;
        }
        for (AttachedEntity attachedEntity : ATTACHED_ENTITIES.values()) {
            if (!attachedEntity.levelKey().equals(level.dimension())
                    || !attachedEntity.plotBounds().intersects(plotBounds)) {
                continue;
            }
            Entity entity = level.getEntity(attachedEntity.entityId());
            T typed = entity == null ? null : entityTypeTest.tryCast(entity);
            if (typed == null || typed.isRemoved() || !predicate.test(typed) || !seen.add(typed)) {
                continue;
            }
            projected.add(typed);
            if (projected.size() >= maxAdditionalResults) {
                return;
            }
        }
    }

    private static void pinAttachedEntityToPlot(BlockAttachedEntity entity, AttachedEntity attachedEntity) {
        pinAttachedEntity(entity, attachedEntity.plotAnchor(), attachedEntity.plotPosition(), attachedEntity.plotBounds());
    }

    private static void pinAttachedEntity(BlockAttachedEntity entity, BlockPos anchor, Vec3 position, AABB bounds) {
        ((BlockAttachedEntityAccess) entity).kinetic_assembly$setAttachmentPos(anchor.immutable());
        Vec3 anchorCenter = Vec3.atCenterOf(anchor);
        entity.setPos(anchorCenter.x, anchorCenter.y, anchorCenter.z);
        entity.setPosRaw(position.x, position.y, position.z);
        entity.syncPacketPositionCodec(position.x, position.y, position.z);
        entity.setBoundingBox(bounds);
    }

    private static Optional<AABB> plotBoundsToWorld(AABB plotBounds, AssemblyPlotProjection projection) {
        AABB worldBounds = null;
        for (int xIndex = 0; xIndex < 2; xIndex++) {
            double x = xIndex == 0 ? plotBounds.minX : plotBounds.maxX;
            for (int yIndex = 0; yIndex < 2; yIndex++) {
                double y = yIndex == 0 ? plotBounds.minY : plotBounds.maxY;
                for (int zIndex = 0; zIndex < 2; zIndex++) {
                    double z = zIndex == 0 ? plotBounds.minZ : plotBounds.maxZ;
                    Vec3 world = projection.plotToWorld(new Vec3(x, y, z));
                    if (!AssemblyVectors.finite(world)) {
                        return Optional.empty();
                    }
                    AABB point = new AABB(world.x, world.y, world.z, world.x, world.y, world.z);
                    worldBounds = worldBounds == null ? point : worldBounds.minmax(point);
                }
            }
        }
        return Optional.ofNullable(worldBounds);
    }

    private static Optional<AABB> plotBoundsToWorld(
            AABB plotBounds,
            AssemblyPlotProjection projection,
            double partialTick
    ) {
        AABB worldBounds = null;
        for (int xIndex = 0; xIndex < 2; xIndex++) {
            double x = xIndex == 0 ? plotBounds.minX : plotBounds.maxX;
            for (int yIndex = 0; yIndex < 2; yIndex++) {
                double y = yIndex == 0 ? plotBounds.minY : plotBounds.maxY;
                for (int zIndex = 0; zIndex < 2; zIndex++) {
                    double z = zIndex == 0 ? plotBounds.minZ : plotBounds.maxZ;
                    Vec3 world = projection.plotToWorld(new Vec3(x, y, z), partialTick);
                    if (!AssemblyVectors.finite(world)) {
                        return Optional.empty();
                    }
                    AABB point = new AABB(world.x, world.y, world.z, world.x, world.y, world.z);
                    worldBounds = worldBounds == null ? point : worldBounds.minmax(point);
                }
            }
        }
        return Optional.ofNullable(worldBounds);
    }

    private static boolean containsAttachedEntityAnchor(ServerAssemblyContainer container, BlockPos plotAnchor) {
        if (container.assemblyAtPlotBlock(plotAnchor).isPresent()) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            if (container.assemblyAtPlotBlock(plotAnchor.relative(direction)).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAttachedEntityAnchor(PhysicsAssembly assembly, BlockPos plotAnchor) {
        if (assembly.plot().containsPlotBlockPos(plotAnchor)) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            if (assembly.plot().containsPlotBlockPos(plotAnchor.relative(direction))) {
                return true;
            }
        }
        return false;
    }

    private static boolean supportIntersectsSourceBlocks(ServerLevel level, AABB supportBox, AssemblyBounds bounds) {
        BlockPos min = BlockPos.containing(
                supportBox.minX + EPSILON,
                supportBox.minY + EPSILON,
                supportBox.minZ + EPSILON
        );
        BlockPos max = BlockPos.containing(
                supportBox.maxX - EPSILON,
                supportBox.maxY - EPSILON,
                supportBox.maxZ - EPSILON
        );
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    pos.set(x, y, z);
                    if (containsSource(bounds, pos) && !level.getBlockState(pos).isAir()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Optional<AABB> sourceSupportBox(PhysicsAssembly assembly, Entity entity) {
        if (!(entity instanceof HangingEntity hangingEntity)) {
            return Optional.empty();
        }
        return Optional.of(plotToSource(assembly, ((HangingEntityAccess) hangingEntity).kinetic_assembly$calculateSupportBox()));
    }

    @Nullable
    private static PhysicsAssembly attachedEntityTransferTarget(
            BlockPos sourceAnchor,
            @Nullable AABB sourceSupportBox,
            List<PhysicsAssembly> childAssemblies
    ) {
        if (sourceSupportBox != null) {
            for (PhysicsAssembly childAssembly : childAssemblies) {
                if (supportIntersectsBlocks(sourceSupportBox, childAssembly)) {
                    return childAssembly;
                }
            }
        }

        for (PhysicsAssembly childAssembly : childAssemblies) {
            if (containsAttachedEntityAnchor(childAssembly, sourceToPlotBlock(childAssembly, sourceAnchor))) {
                return childAssembly;
            }
        }
        return null;
    }

    private static boolean supportIntersectsBlocks(AABB sourceSupportBox, PhysicsAssembly assembly) {
        for (AssemblyBlock block : assembly.blocks()) {
            if (containsSourceBlock(sourceSupportBox, block.sourcePos())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSourceBlock(AABB sourceBounds, BlockPos pos) {
        return pos.getX() + 1.0D > sourceBounds.minX + EPSILON
                && pos.getX() < sourceBounds.maxX - EPSILON
                && pos.getY() + 1.0D > sourceBounds.minY + EPSILON
                && pos.getY() < sourceBounds.maxY - EPSILON
                && pos.getZ() + 1.0D > sourceBounds.minZ + EPSILON
                && pos.getZ() < sourceBounds.maxZ - EPSILON;
    }

    private static boolean containsSource(AssemblyBounds bounds, BlockPos pos) {
        return pos.getX() >= bounds.minSourcePos().getX() && pos.getX() <= bounds.maxSourcePos().getX()
                && pos.getY() >= bounds.minSourcePos().getY() && pos.getY() <= bounds.maxSourcePos().getY()
                && pos.getZ() >= bounds.minSourcePos().getZ() && pos.getZ() <= bounds.maxSourcePos().getZ();
    }

    private static AABB sourceBounds(AssemblyBounds bounds) {
        return new AABB(
                bounds.minSourcePos().getX(),
                bounds.minSourcePos().getY(),
                bounds.minSourcePos().getZ(),
                bounds.maxSourcePos().getX() + 1.0D,
                bounds.maxSourcePos().getY() + 1.0D,
                bounds.maxSourcePos().getZ() + 1.0D
        );
    }

    private static BlockPos sourceToPlotBlock(PhysicsAssembly assembly, BlockPos sourcePos) {
        return assembly.plot().toPlotBlockPos(assembly.bounds().toLocal(sourcePos));
    }

    private static Vec3 sourceToPlot(PhysicsAssembly assembly, Vec3 sourcePosition) {
        AssemblyBounds bounds = assembly.bounds();
        AssemblyPlot plot = assembly.plot();
        return new Vec3(
                plot.minPlotX() + sourcePosition.x() - bounds.sourceOrigin().getX(),
                plot.minPlotY() + sourcePosition.y() - bounds.sourceOrigin().getY(),
                plot.minPlotZ() + sourcePosition.z() - bounds.sourceOrigin().getZ()
        );
    }

    private static AABB sourceToPlot(PhysicsAssembly assembly, AABB sourceBounds) {
        Vec3 min = sourceToPlot(assembly, new Vec3(sourceBounds.minX, sourceBounds.minY, sourceBounds.minZ));
        Vec3 max = sourceToPlot(assembly, new Vec3(sourceBounds.maxX, sourceBounds.maxY, sourceBounds.maxZ));
        return new AABB(min, max);
    }

    private static BlockPos plotToSourceBlock(PhysicsAssembly assembly, BlockPos plotPos) {
        AssemblyBounds bounds = assembly.bounds();
        AssemblyPlot plot = assembly.plot();
        return new BlockPos(
                bounds.sourceOrigin().getX() + plotPos.getX() - plot.minPlotX(),
                bounds.sourceOrigin().getY() + plotPos.getY() - plot.minPlotY(),
                bounds.sourceOrigin().getZ() + plotPos.getZ() - plot.minPlotZ()
        );
    }

    private static Vec3 plotToSource(PhysicsAssembly assembly, Vec3 plotPosition) {
        AssemblyBounds bounds = assembly.bounds();
        AssemblyPlot plot = assembly.plot();
        return new Vec3(
                bounds.sourceOrigin().getX() + plotPosition.x() - plot.minPlotX(),
                bounds.sourceOrigin().getY() + plotPosition.y() - plot.minPlotY(),
                bounds.sourceOrigin().getZ() + plotPosition.z() - plot.minPlotZ()
        );
    }

    private static AABB plotToSource(PhysicsAssembly assembly, AABB plotBounds) {
        Vec3 min = plotToSource(assembly, new Vec3(plotBounds.minX, plotBounds.minY, plotBounds.minZ));
        Vec3 max = plotToSource(assembly, new Vec3(plotBounds.maxX, plotBounds.maxY, plotBounds.maxZ));
        return new AABB(min, max);
    }

    private static void discardAttachedEntity(ServerLevel level, AttachedEntity attachedEntity) {
        Entity entity = level.getEntity(attachedEntity.entityId());
        if (entity != null && !entity.isRemoved()) {
            entity.discard();
        }
        ATTACHED_ENTITIES.remove(attachedEntity.key());
    }

    private static void dispatchEntityInside(ServerLevel level, PhysicsAssembly assembly, Entity entity, AABB projectedBounds) {
        BlockPos min = BlockPos.containing(
                projectedBounds.minX + EPSILON,
                projectedBounds.minY + EPSILON,
                projectedBounds.minZ + EPSILON
        );
        BlockPos max = BlockPos.containing(
                projectedBounds.maxX - EPSILON,
                projectedBounds.maxY - EPSILON,
                projectedBounds.maxZ - EPSILON
        );
        int count = (max.getX() - min.getX() + 1)
                * (max.getY() - min.getY() + 1)
                * (max.getZ() - min.getZ() + 1);
        if (count <= 0 || count > MAX_ENTITY_INSIDE_BLOCKS) {
            return;
        }

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    pos.set(x, y, z);
                    if (!assembly.plot().containsPlotBlockPos(pos)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (!state.isAir()) {
                        state.entityInside(level, pos, entity);
                    }
                }
            }
        }
    }

    private static List<QueryTarget> queryTargets(ServerLevel level, AABB plotBounds) {
        ServerAssemblyContainer container = AssemblyContainers.server(level).orElse(null);
        if (container == null || container.isEmpty()) {
            return List.of();
        }

        int minChunkX = SectionPos.blockToSectionCoord(blockMin(plotBounds.minX));
        int maxChunkX = SectionPos.blockToSectionCoord(blockMax(plotBounds.maxX));
        int minChunkZ = SectionPos.blockToSectionCoord(blockMin(plotBounds.minZ));
        int maxChunkZ = SectionPos.blockToSectionCoord(blockMax(plotBounds.maxZ));
        Map<AssemblyId, QueryTarget> targets = new LinkedHashMap<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                container.assemblyAtChunk(new ChunkPos(chunkX, chunkZ))
                        .filter(assembly -> intersectsPlot(assembly.plot(), plotBounds))
                        .flatMap(assembly -> queryTarget(level, assembly))
                        .ifPresent(target -> targets.putIfAbsent(target.assembly().id(), target));
            }
        }
        return List.copyOf(targets.values());
    }

    private static Optional<QueryTarget> queryTarget(ServerLevel level, PhysicsAssembly assembly) {
        return KineticAssembly.api().existingWorld(level)
                .flatMap(world -> world.pose(assembly.bodyId()))
                .map(pose -> new QueryTarget(assembly, AssemblyTransform.from(pose), bodyToPlotOrigin(assembly)));
    }

    private static List<Entity> queryWorldEntities(
            ServerLevel level,
            @Nullable Entity excluded,
            AABB worldBounds,
            Predicate<? super Entity> predicate
    ) {
        PROJECTING_QUERY.set(true);
        try {
            return level.getEntities(excluded, worldBounds, predicate);
        } finally {
            PROJECTING_QUERY.set(false);
        }
    }

    private static <T extends Entity> List<T> queryWorldEntities(
            ServerLevel level,
            EntityTypeTest<Entity, T> entityTypeTest,
            AABB worldBounds,
            Predicate<? super T> predicate
    ) {
        PROJECTING_QUERY.set(true);
        try {
            return level.getEntities(entityTypeTest, worldBounds, predicate);
        } finally {
            PROJECTING_QUERY.set(false);
        }
    }

    private static AABB plotBounds(AssemblyPlot plot) {
        return new AABB(
                plot.minPlotX(),
                plot.minPlotY(),
                plot.minPlotZ(),
                plot.maxPlotX() + 1.0D,
                plot.maxPlotY() + 1.0D,
                plot.maxPlotZ() + 1.0D
        );
    }

    private static boolean intersectsPlot(AssemblyPlot plot, AABB plotBounds) {
        return plotBounds.maxX > plot.minPlotX()
                && plotBounds.minX < plot.maxPlotX() + 1.0D
                && plotBounds.maxY > plot.minPlotY()
                && plotBounds.minY < plot.maxPlotY() + 1.0D
                && plotBounds.maxZ > plot.minPlotZ()
                && plotBounds.minZ < plot.maxPlotZ() + 1.0D;
    }

    private static AABB plotAabbToWorldAabb(QueryTarget target, AABB plotBounds) {
        BoundsBuilder builder = new BoundsBuilder();
        forEachCorner(plotBounds, (x, y, z) -> builder.include(plotToWorld(target, x, y, z)));
        return builder.build();
    }

    private static AABB worldAabbToPlotAabb(QueryTarget target, AABB worldBounds) {
        BoundsBuilder builder = new BoundsBuilder();
        forEachCorner(worldBounds, (x, y, z) -> builder.include(worldToPlot(target, x, y, z)));
        return builder.build();
    }

    private static PhysicsVector plotToWorld(QueryTarget target, double plotX, double plotY, double plotZ) {
        AssemblyPlot plot = target.assembly().plot();
        PhysicsVector bodyToPlotOrigin = target.bodyToPlotOrigin();
        return target.transform().localToWorld(new PhysicsVector(
                bodyToPlotOrigin.x() + plotX - plot.minPlotX(),
                bodyToPlotOrigin.y() + plotY - plot.minPlotY(),
                bodyToPlotOrigin.z() + plotZ - plot.minPlotZ()
        ));
    }

    private static PhysicsVector worldToPlot(QueryTarget target, double worldX, double worldY, double worldZ) {
        AssemblyPlot plot = target.assembly().plot();
        PhysicsVector bodyToPlotOrigin = target.bodyToPlotOrigin();
        PhysicsVector local = target.transform().worldToLocal(new PhysicsVector(worldX, worldY, worldZ));
        return new PhysicsVector(
                local.x() - bodyToPlotOrigin.x() + plot.minPlotX(),
                local.y() - bodyToPlotOrigin.y() + plot.minPlotY(),
                local.z() - bodyToPlotOrigin.z() + plot.minPlotZ()
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

    private static int blockMin(double coordinate) {
        return Mth.floor(coordinate + EPSILON);
    }

    private static int blockMax(double coordinate) {
        return Mth.floor(coordinate - EPSILON);
    }

    private static <T extends Entity> Set<T> identitySet(List<? extends T> existing) {
        Set<T> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.addAll(existing);
        return seen;
    }

    private static Set<Entity> identityEntitySet(Iterable<?> existing) {
        Set<Entity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object value : existing) {
            if (value instanceof Entity entity) {
                seen.add(entity);
            }
        }
        return seen;
    }

    private static void forEachCorner(AABB bounds, CornerConsumer consumer) {
        consumer.accept(bounds.minX, bounds.minY, bounds.minZ);
        consumer.accept(bounds.minX, bounds.minY, bounds.maxZ);
        consumer.accept(bounds.minX, bounds.maxY, bounds.minZ);
        consumer.accept(bounds.minX, bounds.maxY, bounds.maxZ);
        consumer.accept(bounds.maxX, bounds.minY, bounds.minZ);
        consumer.accept(bounds.maxX, bounds.minY, bounds.maxZ);
        consumer.accept(bounds.maxX, bounds.maxY, bounds.minZ);
        consumer.accept(bounds.maxX, bounds.maxY, bounds.maxZ);
    }

    @FunctionalInterface
    private interface CornerConsumer {
        void accept(double x, double y, double z);
    }

    private static DirectionAccessor directionAccessor(Entity entity) {
        if (entity instanceof net.minecraft.world.entity.decoration.HangingEntity hangingEntity) {
            return hangingEntity::getDirection;
        }
        return null;
    }

    private static boolean isRegisteredAttachedEntity(Entity entity, @Nullable AttachedEntity attachedEntity) {
        return attachedEntity != null
                && entity instanceof BlockAttachedEntity
                && entity.getTags().contains(ATTACHED_ENTITY_TAG);
    }

    @FunctionalInterface
    private interface DirectionAccessor {
        Direction direction();
    }

    private record AttachedEntityKey(ResourceKey<Level> levelKey, UUID entityId) {
        private AttachedEntityKey {
            Objects.requireNonNull(levelKey, "levelKey");
            Objects.requireNonNull(entityId, "entityId");
        }
    }

    private record AttachedEntity(
            ResourceKey<Level> levelKey,
            UUID entityId,
            BlockPos plotAnchor,
            Vec3 plotPosition,
            AABB plotBounds
    ) {
        private AttachedEntity {
            Objects.requireNonNull(levelKey, "levelKey");
            Objects.requireNonNull(entityId, "entityId");
            Objects.requireNonNull(plotAnchor, "plotAnchor");
            Objects.requireNonNull(plotPosition, "plotPosition");
            Objects.requireNonNull(plotBounds, "plotBounds");
        }

        private AttachedEntityKey key() {
            return new AttachedEntityKey(levelKey, entityId);
        }
    }

    public record AttachedEntityCapture(
            UUID entityId,
            BlockPos sourceAnchor,
            Vec3 sourcePosition,
            AABB sourceBounds
    ) {
        public AttachedEntityCapture {
            Objects.requireNonNull(entityId, "entityId");
            Objects.requireNonNull(sourceAnchor, "sourceAnchor");
            Objects.requireNonNull(sourcePosition, "sourcePosition");
            Objects.requireNonNull(sourceBounds, "sourceBounds");
        }
    }

    private record QueryTarget(
            PhysicsAssembly assembly,
            AssemblyTransform transform,
            PhysicsVector bodyToPlotOrigin
    ) {
        private QueryTarget {
            Objects.requireNonNull(assembly, "assembly");
            Objects.requireNonNull(transform, "transform");
            Objects.requireNonNull(bodyToPlotOrigin, "bodyToPlotOrigin");
        }
    }

    private static final class BoundsBuilder {
        private double minX = Double.POSITIVE_INFINITY;
        private double minY = Double.POSITIVE_INFINITY;
        private double minZ = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY;
        private double maxY = Double.NEGATIVE_INFINITY;
        private double maxZ = Double.NEGATIVE_INFINITY;

        private void include(PhysicsVector point) {
            minX = Math.min(minX, point.x());
            minY = Math.min(minY, point.y());
            minZ = Math.min(minZ, point.z());
            maxX = Math.max(maxX, point.x());
            maxY = Math.max(maxY, point.y());
            maxZ = Math.max(maxZ, point.z());
        }

        private AABB build() {
            return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }
}
