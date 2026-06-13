package com.firedoge.kineticassembly.render;

import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsQuaternion;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.minecraft.assembly.ClientAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.ClientTrackedAssembly;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyClientMetadata;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;

public final class AssemblyPlotRenderer {
    private static final ContextKey<ExtractedState> EXTRACTED_STATE_KEY =
            new ContextKey<>(Identifier.fromNamespaceAndPath(KineticAssembly.MODID, "assembly_render_state"));
    private static final int EMPTY_FRAMES_TO_KEEP_STATS = 20;
    private static final EnumMap<ChunkSectionLayer, RenderType> RENDER_TYPES = new EnumMap<>(ChunkSectionLayer.class);

    private static RenderStats lastStats = RenderStats.EMPTY;
    private static FrameStats currentFrameStats = new FrameStats();
    private static int emptyFrameCount;

    static {
        RENDER_TYPES.put(ChunkSectionLayer.SOLID, RenderTypes.solidMovingBlock());
        RENDER_TYPES.put(ChunkSectionLayer.CUTOUT, RenderTypes.cutoutMovingBlock());
        RENDER_TYPES.put(ChunkSectionLayer.TRANSLUCENT, RenderTypes.translucentMovingBlock());
        RENDER_TYPES.put(ChunkSectionLayer.TRIPWIRE, RenderTypes.tripwireMovingBlock());
    }

    private AssemblyPlotRenderer() {
    }

    public static RenderStats lastStats() {
        return lastStats;
    }

    public static void extract(ExtractLevelRenderStateEvent event) {
        Objects.requireNonNull(event, "event");
        Vec3 cameraPos = event.getCamera().position();
        float partialTick = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        event.getRenderState().setRenderData(
                EXTRACTED_STATE_KEY,
                new ExtractedState(cameraPos.x(), cameraPos.y(), cameraPos.z(), partialTick, event.getFrustum())
        );
    }

    public static void render(RenderLevelStageEvent event, ClientAssemblyContainer container) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(container, "container");
        List<ChunkSectionLayer> layers = layers(event);
        if (layers.isEmpty()) {
            return;
        }
        ExtractedState extractedState = extractedState(event.getLevelRenderState());
        if (extractedState == null) {
            return;
        }
        beginLayer(layers.get(0));

        List<ClientTrackedAssembly> assemblies = container.trackedAssemblies();
        if (assemblies.isEmpty()) {
            publishLayerStats(RenderStats.EMPTY);
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        BlockRenderDispatcher blockRenderer = minecraft.getBlockRenderer();
        RandomSource random = RandomSource.create();
        int renderedAssemblies = 0;
        int renderedChunks = 0;
        int renderedBlocks = 0;

        for (ChunkSectionLayer layer : layers) {
            RenderType renderType = RENDER_TYPES.get(layer);
            if (renderType == null) {
                continue;
            }
            for (ClientTrackedAssembly assembly : assemblies) {
                if (!assembly.finalized()) {
                    continue;
                }
                if (!isVisible(assembly, extractedState.frustum(), extractedState.partialTick())) {
                    continue;
                }
                RenderStats stats = renderAssemblyImmediate(
                        container,
                        assembly,
                        layer,
                        renderType,
                        blockRenderer,
                        random,
                        extractedState.partialTick(),
                        event.getModelViewMatrix(),
                        extractedState.camX(),
                        extractedState.camY(),
                        extractedState.camZ()
                );
                if (stats.blocks() > 0) {
                    renderedAssemblies++;
                    renderedChunks += stats.chunks();
                    renderedBlocks += stats.blocks();
                }
            }
        }
        publishLayerStats(new RenderStats(renderedAssemblies, renderedChunks, renderedBlocks, 0));
    }

    public static void renderBlockEntities(RenderLevelStageEvent event, ClientAssemblyContainer container) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(container, "container");
        if (!(event instanceof RenderLevelStageEvent.AfterEntities)) {
            return;
        }
        beginBlockEntityStage();
        publishBlockEntityStats(countVisibleBlockEntities(event, container));
    }

    public static void renderSelectionOutline(RenderLevelStageEvent event, ClientAssemblyContainer container) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(container, "container");
        if (!(event instanceof RenderLevelStageEvent.AfterLevel)) {
            return;
        }
        ExtractedState extractedState = extractedState(event.getLevelRenderState());
        if (extractedState == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ClientAssemblySelection.Result result = ClientAssemblySelection.update(minecraft, container).orElse(null);
        if (result == null) {
            return;
        }

        ClientTrackedAssembly assembly = container.trackedAssembly(result.id()).orElse(null);
        if (assembly == null) {
            return;
        }

        PoseStack poseStack = new PoseStack();
        applyRootPose(
                poseStack,
                assembly.renderPose(extractedState.partialTick()),
                assembly.metadata(),
                extractedState.camX(),
                extractedState.camY(),
                extractedState.camZ()
        );
        PoseStack outlinePose = plotOffsetPose(poseStack, assembly.metadata(), result.plotPos());
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        VertexConsumer consumer = bufferSource.getBuffer(RenderTypes.lines());
        ShapeRenderer.renderShape(
                outlinePose,
                consumer,
                result.shape(),
                0.0D,
                0.0D,
                0.0D,
                ARGB.colorFromFloat(0.9F, 0.15F, 0.45F, 1.0F),
                7.0F
        );
        drawLinesWithModelView(bufferSource, event.getModelViewMatrix());
    }

    public static void renderBreakingProgress(RenderLevelStageEvent event, ClientAssemblyContainer container) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(container, "container");
        if (!(event instanceof RenderLevelStageEvent.AfterEntities)) {
            return;
        }
        ExtractedState extractedState = extractedState(event.getLevelRenderState());
        if (extractedState == null) {
            return;
        }

        List<ClientAssemblyBreakingProgress.Entry> entries = ClientAssemblyBreakingProgress.entries(container);
        if (entries.isEmpty()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        BlockRenderDispatcher blockRenderer = minecraft.getBlockRenderer();
        for (ClientAssemblyBreakingProgress.Entry entry : entries) {
            ClientTrackedAssembly assembly = container.trackedAssembly(entry.id()).orElse(null);
            if (assembly == null || !assembly.finalized()) {
                continue;
            }
            if (!isVisible(assembly, extractedState.frustum(), extractedState.partialTick())) {
                continue;
            }
            renderBreakingProgressEntry(
                    minecraft,
                    blockRenderer,
                    assembly,
                    entry,
                    extractedState.partialTick(),
                    event.getModelViewMatrix(),
                    extractedState.camX(),
                    extractedState.camY(),
                    extractedState.camZ()
            );
        }
    }

    private static ExtractedState extractedState(LevelRenderState levelRenderState) {
        return levelRenderState.getRenderData(EXTRACTED_STATE_KEY);
    }

    private static boolean isVisible(ClientTrackedAssembly assembly, Frustum frustum, float partialTick) {
        AABB bounds = assembly.renderWorldBounds(partialTick).orElse(null);
        return bounds == null || frustum.isVisible(bounds);
    }

    private static RenderStats renderAssemblyImmediate(
            ClientAssemblyContainer container,
            ClientTrackedAssembly assembly,
            ChunkSectionLayer layer,
            RenderType renderType,
            BlockRenderDispatcher blockRenderer,
            RandomSource random,
            float partialTick,
            Matrix4f modelViewMatrix,
            double camX,
            double camY,
            double camZ
    ) {
        ClientTrackedAssembly.RenderMeshCache emptyCache = assembly.renderMeshCache(renderType);
        if (emptyCache != null && emptyCache.blocks() == 0) {
            return RenderStats.EMPTY;
        }

        PoseStack poseStack = new PoseStack();
        applyRootPose(poseStack, assembly.renderPose(partialTick), assembly.metadata(), camX, camY, camZ);
        int renderedChunks = 0;
        int renderedBlocks = 0;
        ModelBlockRenderer.enableCaching();
        try (ByteBufferBuilder byteBuffer = new ByteBufferBuilder(renderType.bufferSize())) {
            BufferBuilder bufferBuilder = new BufferBuilder(byteBuffer, renderType.mode(), renderType.format());
            for (ChunkPos chunkPos : assembly.loadedChunks()) {
                LevelChunk chunk = container.plotChunk(chunkPos).orElse(null);
                if (chunk == null) {
                    continue;
                }
                int blockCount = renderChunk(assembly, chunk, layer, blockRenderer, random, poseStack, bufferBuilder);
                if (blockCount > 0) {
                    renderedChunks++;
                    renderedBlocks += blockCount;
                }
            }
            MeshData meshData = bufferBuilder.build();
            if (meshData != null) {
                if (renderType.sortOnUpload()) {
                    meshData.sortQuads(byteBuffer, RenderSystem.getProjectionType().vertexSorting());
                }
                drawWithModelView(renderType, meshData, modelViewMatrix);
            }
        } finally {
            ModelBlockRenderer.clearCache();
        }

        if (renderedBlocks == 0) {
            assembly.cacheRenderMesh(renderType, renderedChunks, 0);
        }
        return new RenderStats(renderedBlocks > 0 ? 1 : 0, renderedChunks, renderedBlocks, 0);
    }

    private static int renderChunk(
            ClientTrackedAssembly assembly,
            LevelChunk chunk,
            ChunkSectionLayer layer,
            BlockRenderDispatcher blockRenderer,
            RandomSource random,
            PoseStack rootPose,
            BufferBuilder blockBuffer
    ) {
        int renderedBlocks = 0;
        LevelChunkSection[] sections = chunk.getSections();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section.hasOnlyAir()) {
                continue;
            }
            int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
            renderedBlocks += renderSection(assembly, chunk, section, sectionY, layer, blockRenderer, random, rootPose, blockBuffer);
        }
        return renderedBlocks;
    }

    private static int renderSection(
            ClientTrackedAssembly assembly,
            LevelChunk chunk,
            LevelChunkSection section,
            int sectionY,
            ChunkSectionLayer layer,
            BlockRenderDispatcher blockRenderer,
            RandomSource random,
            PoseStack rootPose,
            BufferBuilder blockBuffer
    ) {
        int renderedBlocks = 0;
        BlockPos sectionOrigin = SectionPos.of(chunk.getPos(), sectionY).origin();
        PoseStack sectionPose = plotOffsetPose(rootPose, assembly.metadata(), sectionOrigin);
        TransformingVertexConsumer fluidBuffer = new TransformingVertexConsumer(
                blockBuffer,
                sectionPose.last().pose(),
                sectionPose.last().normal()
        );
        Function<ChunkSectionLayer, VertexConsumer> bufferLookup = candidate -> candidate == layer ? blockBuffer : NoOpVertexConsumer.INSTANCE;
        BlockPos.MutableBlockPos plotPos = new BlockPos.MutableBlockPos();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (state.isAir()) {
                        continue;
                    }
                    plotPos.setWithOffset(sectionOrigin, x, y, z);
                    if (!assembly.plot().containsPlotBlockPos(plotPos)) {
                        continue;
                    }
                    BlockPos immutablePlotPos = plotPos.immutable();
                    if (renderFluid(state, immutablePlotPos, assembly, layer, blockRenderer, fluidBuffer)) {
                        renderedBlocks++;
                    }
                    if (renderBlock(state, immutablePlotPos, assembly, layer, blockRenderer, random, rootPose, bufferLookup)) {
                        renderedBlocks++;
                    }
                }
            }
        }
        return renderedBlocks;
    }

    private static boolean renderBlock(
            BlockState state,
            BlockPos plotPos,
            ClientTrackedAssembly assembly,
            ChunkSectionLayer layer,
            BlockRenderDispatcher blockRenderer,
            RandomSource random,
            PoseStack rootPose,
            Function<ChunkSectionLayer, VertexConsumer> bufferLookup
    ) {
        if (state.getRenderShape() != RenderShape.MODEL) {
            return false;
        }

        BlockStateModel model = blockRenderer.getBlockModel(state);
        random.setSeed(state.getSeed(plotPos));
        List<BlockModelPart> parts = model.collectParts(assembly.levelView(), plotPos, state, random);
        if (parts.isEmpty() || parts.stream().noneMatch(part -> part.getRenderType(state) == layer)) {
            return false;
        }

        PoseStack blockPose = blockPose(rootPose, assembly.metadata(), plotPos);
        blockRenderer.renderBatched(state, plotPos, assembly.levelView(), blockPose, bufferLookup, true, parts);
        return true;
    }

    private static boolean renderFluid(
            BlockState state,
            BlockPos plotPos,
            ClientTrackedAssembly assembly,
            ChunkSectionLayer layer,
            BlockRenderDispatcher blockRenderer,
            TransformingVertexConsumer buffer
    ) {
        FluidState fluidState = state.getFluidState();
        if (fluidState.isEmpty() || ItemBlockRenderTypes.getRenderLayer(fluidState) != layer) {
            return false;
        }

        blockRenderer.renderLiquid(plotPos, assembly.levelView(), buffer, state, fluidState);
        return true;
    }

    private static int countVisibleBlockEntities(RenderLevelStageEvent event, ClientAssemblyContainer container) {
        ExtractedState extractedState = extractedState(event.getLevelRenderState());
        if (extractedState == null) {
            return 0;
        }

        int rendered = 0;
        for (ClientTrackedAssembly assembly : container.trackedAssemblies()) {
            if (!assembly.finalized()) {
                continue;
            }
            if (!isVisible(assembly, extractedState.frustum(), extractedState.partialTick())) {
                continue;
            }
            for (ChunkPos chunkPos : assembly.loadedChunks()) {
                LevelChunk chunk = container.plotChunk(chunkPos).orElse(null);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (!blockEntity.isRemoved()
                            && assembly.plot().containsPlotBlockPos(blockEntity.getBlockPos())
                            && blockEntity.getType().isValid(blockEntity.getBlockState())) {
                        rendered++;
                    }
                }
            }
        }
        return rendered;
    }

    private static void renderBreakingProgressEntry(
            Minecraft minecraft,
            BlockRenderDispatcher blockRenderer,
            ClientTrackedAssembly assembly,
            ClientAssemblyBreakingProgress.Entry entry,
            float partialTick,
            Matrix4f modelViewMatrix,
            double camX,
            double camY,
            double camZ
    ) {
        BlockPos plotPos = entry.plotPos();
        BlockState state = assembly.levelView().getBlockState(plotPos);
        if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
            return;
        }

        PoseStack rootPose = new PoseStack();
        applyRootPose(rootPose, assembly.renderPose(partialTick), assembly.metadata(), camX, camY, camZ);
        PoseStack blockPose = blockPose(rootPose, assembly.metadata(), plotPos);
        PoseStack.Pose pose = blockPose.last();
        VertexConsumer consumer = new SheetedDecalTextureGenerator(
                minecraft.renderBuffers().crumblingBufferSource().getBuffer(ModelBakery.DESTROY_TYPES.get(entry.progress())),
                pose,
                1.0F
        );
        blockRenderer.renderBreakingTexture(
                state,
                plotPos,
                assembly.levelView(),
                blockPose,
                consumer
        );
        drawCrumblingWithModelView(minecraft.renderBuffers().crumblingBufferSource(), modelViewMatrix);
    }

    private static void drawWithModelView(RenderType renderType, MeshData meshData, Matrix4f modelViewMatrix) {
        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        try {
            stack.identity();
            stack.mul(modelViewMatrix);
            renderType.draw(meshData);
        } finally {
            stack.popMatrix();
        }
    }

    private static void drawLinesWithModelView(MultiBufferSource.BufferSource bufferSource, Matrix4f modelViewMatrix) {
        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        try {
            stack.identity();
            stack.mul(modelViewMatrix);
            bufferSource.endBatch(RenderTypes.lines());
        } finally {
            stack.popMatrix();
        }
    }

    private static void drawCrumblingWithModelView(MultiBufferSource.BufferSource bufferSource, Matrix4f modelViewMatrix) {
        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        try {
            stack.identity();
            stack.mul(modelViewMatrix);
            bufferSource.endBatch();
        } finally {
            stack.popMatrix();
        }
    }

    private static PoseStack blockPose(PoseStack rootPose, AssemblyClientMetadata metadata, BlockPos plotPos) {
        return plotOffsetPose(rootPose, metadata, plotPos);
    }

    private static PoseStack plotOffsetPose(PoseStack rootPose, AssemblyClientMetadata metadata, BlockPos plotPos) {
        PoseStack blockPose = new PoseStack();
        blockPose.mulPose(rootPose.last().pose());
        blockPose.translate(
                plotPos.getX() - metadata.plot().minPlotX(),
                plotPos.getY() - metadata.plot().minPlotY(),
                plotPos.getZ() - metadata.plot().minPlotZ()
        );
        return blockPose;
    }

    private static void applyRootPose(
            PoseStack poseStack,
            PhysicsPose pose,
            AssemblyClientMetadata metadata,
            double camX,
            double camY,
            double camZ
    ) {
        PhysicsVector position = pose.position();
        PhysicsVector bodyToPlotOrigin = metadata.bodyToPlotOrigin();
        poseStack.translate(position.x() - camX, position.y() - camY, position.z() - camZ);
        poseStack.mulPose(toJomlQuaternion(pose.rotation()));
        poseStack.translate(bodyToPlotOrigin.x(), bodyToPlotOrigin.y(), bodyToPlotOrigin.z());
    }

    private static Quaternionf toJomlQuaternion(PhysicsQuaternion rotation) {
        return new Quaternionf(
                (float) rotation.x(),
                (float) rotation.y(),
                (float) rotation.z(),
                (float) rotation.w()
        ).normalize();
    }

    private static List<ChunkSectionLayer> layers(RenderLevelStageEvent event) {
        if (event instanceof RenderLevelStageEvent.AfterOpaqueBlocks) {
            return List.of(ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT);
        }
        if (event instanceof RenderLevelStageEvent.AfterTranslucentBlocks) {
            return List.of(ChunkSectionLayer.TRANSLUCENT);
        }
        if (event instanceof RenderLevelStageEvent.AfterTripwireBlocks) {
            return List.of(ChunkSectionLayer.TRIPWIRE);
        }
        return List.of();
    }

    private static void beginLayer(ChunkSectionLayer layer) {
        if (layer == ChunkSectionLayer.SOLID || !currentFrameStats.started) {
            beginFrame();
        }
    }

    private static void beginBlockEntityStage() {
        if (!currentFrameStats.started) {
            beginFrame();
        }
    }

    private static void beginFrame() {
        currentFrameStats = new FrameStats();
        currentFrameStats.started = true;
    }

    private static void publishLayerStats(RenderStats stats) {
        currentFrameStats.assemblies.addAndGet(stats.assemblies());
        currentFrameStats.chunks.addAndGet(stats.chunks());
        currentFrameStats.blocks.addAndGet(stats.blocks());
        publishCurrentFrame();
    }

    private static void publishBlockEntityStats(int blockEntities) {
        currentFrameStats.blockEntities.addAndGet(blockEntities);
        publishCurrentFrame();
    }

    private static void publishCurrentFrame() {
        RenderStats stats = currentFrameStats.toRenderStats();
        if (stats.hasGeometry()) {
            emptyFrameCount = 0;
            lastStats = stats;
            return;
        }

        emptyFrameCount++;
        if (emptyFrameCount > EMPTY_FRAMES_TO_KEEP_STATS) {
            lastStats = RenderStats.EMPTY;
        }
    }

    private record ExtractedState(double camX, double camY, double camZ, float partialTick, Frustum frustum) {
    }

    private enum NoOpVertexConsumer implements VertexConsumer {
        INSTANCE;

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float normalX, float normalY, float normalZ) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }

    private static final class FrameStats {
        private final AtomicInteger assemblies = new AtomicInteger();
        private final AtomicInteger chunks = new AtomicInteger();
        private final AtomicInteger blocks = new AtomicInteger();
        private final AtomicInteger blockEntities = new AtomicInteger();
        private boolean started;

        private RenderStats toRenderStats() {
            return new RenderStats(
                    assemblies.get(),
                    chunks.get(),
                    blocks.get(),
                    blockEntities.get()
            );
        }
    }

    public record RenderStats(int assemblies, int chunks, int blocks, int blockEntities) {
        public static final RenderStats EMPTY = new RenderStats(0, 0, 0, 0);

        boolean hasGeometry() {
            return blocks > 0 || blockEntities > 0;
        }
    }
}
