package com.firedoge.kineticassembly.render;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsQuaternion;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.minecraft.assembly.ClientAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.ClientTrackedAssembly;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyClientMetadata;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexBuffer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;

public final class AssemblyPlotRenderer {
    private static final int EMPTY_FRAMES_TO_KEEP_STATS = 20;

    private static RenderStats lastStats = RenderStats.EMPTY;
    private static FrameStats currentFrameStats = new FrameStats();
    private static int emptyFrameCount;

    private AssemblyPlotRenderer() {
    }

    public static RenderStats lastStats() {
        return lastStats;
    }

    public static void render(RenderLevelStageEvent event, ClientAssemblyContainer container) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(container, "container");
        RenderType renderType = renderType(event.getStage());
        if (renderType == null) {
            return;
        }
        beginLayer(renderType);

        List<ClientTrackedAssembly> assemblies = container.trackedAssemblies();
        if (assemblies.isEmpty()) {
            publishLayerStats(RenderStats.EMPTY);
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        BlockRenderDispatcher blockRenderer = minecraft.getBlockRenderer();
        RandomSource random = RandomSource.create();
        double camX = event.getCamera().getPosition().x();
        double camY = event.getCamera().getPosition().y();
        double camZ = event.getCamera().getPosition().z();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        int renderedAssemblies = 0;
        int renderedChunks = 0;
        int renderedBlocks = 0;

        for (ClientTrackedAssembly assembly : assemblies) {
            if (!assembly.finalized()) {
                continue;
            }
            if (!isVisible(assembly, event.getFrustum(), partialTick)) {
                continue;
            }
            RenderStats stats = renderAssembly(
                    container,
                    assembly,
                    renderType,
                    blockRenderer,
                    random,
                    partialTick,
                    event.getModelViewMatrix(),
                    camX,
                    camY,
                    camZ
            );
            if (stats.blocks() > 0) {
                renderedAssemblies++;
                renderedChunks += stats.chunks();
                renderedBlocks += stats.blocks();
            }
        }
        publishLayerStats(new RenderStats(renderedAssemblies, renderedChunks, renderedBlocks, 0));
    }

    public static void renderBlockEntities(RenderLevelStageEvent event, ClientAssemblyContainer container) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(container, "container");
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        beginBlockEntityStage();

        List<ClientTrackedAssembly> assemblies = container.trackedAssemblies();
        if (assemblies.isEmpty()) {
            publishBlockEntityStats(0);
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        double camX = event.getCamera().getPosition().x();
        double camY = event.getCamera().getPosition().y();
        double camZ = event.getCamera().getPosition().z();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        int rendered = 0;

        for (ClientTrackedAssembly assembly : assemblies) {
            if (!assembly.finalized()) {
                continue;
            }
            if (!isVisible(assembly, event.getFrustum(), partialTick)) {
                continue;
            }
            rendered += renderAssemblyBlockEntities(
                    minecraft,
                    container,
                    assembly,
                    partialTick,
                    camX,
                    camY,
                    camZ
            );
        }
        publishBlockEntityStats(rendered);
    }

    public static void renderSelectionOutline(RenderLevelStageEvent event, ClientAssemblyContainer container) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(container, "container");
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
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
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        applyRootPose(
                poseStack,
                assembly.renderPose(partialTick),
                assembly.metadata(),
                event.getCamera().getPosition().x(),
                event.getCamera().getPosition().y(),
                event.getCamera().getPosition().z()
        );
        PoseStack outlinePose = plotOffsetPose(poseStack, assembly.metadata(), result.plotPos());
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());
        LevelRenderer.renderVoxelShape(outlinePose, consumer, result.shape(), 0.0D, 0.0D, 0.0D, 0.15F, 0.45F, 1.0F, 0.9F, true);
        drawLinesWithModelView(bufferSource, event.getModelViewMatrix());
    }

    public static void renderBreakingProgress(RenderLevelStageEvent event, ClientAssemblyContainer container) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(container, "container");
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }

        List<ClientAssemblyBreakingProgress.Entry> entries = ClientAssemblyBreakingProgress.entries(container);
        if (entries.isEmpty()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        BlockRenderDispatcher blockRenderer = minecraft.getBlockRenderer();
        double camX = event.getCamera().getPosition().x();
        double camY = event.getCamera().getPosition().y();
        double camZ = event.getCamera().getPosition().z();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        for (ClientAssemblyBreakingProgress.Entry entry : entries) {
            ClientTrackedAssembly assembly = container.trackedAssembly(entry.id()).orElse(null);
            if (assembly == null || !assembly.finalized()) {
                continue;
            }
            if (!isVisible(assembly, event.getFrustum(), partialTick)) {
                continue;
            }
            renderBreakingProgressEntry(minecraft, blockRenderer, assembly, entry, partialTick, event.getModelViewMatrix(), camX, camY, camZ);
        }
    }

    private static RenderStats renderAssembly(
            ClientAssemblyContainer container,
            ClientTrackedAssembly assembly,
            RenderType renderType,
            BlockRenderDispatcher blockRenderer,
            RandomSource random,
            float partialTick,
            Matrix4f modelViewMatrix,
            double camX,
            double camY,
            double camZ
    ) {
        if (renderType.sortOnUpload()) {
            ClientTrackedAssembly.RenderMeshCache emptyCache = assembly.renderMeshCache(renderType);
            if (emptyCache != null && emptyCache.blocks() == 0) {
                return RenderStats.EMPTY;
            }

            RenderStats stats = renderAssemblyImmediate(container, assembly, renderType, blockRenderer, random, partialTick, modelViewMatrix, camX, camY, camZ);
            if (stats.blocks() == 0) {
                assembly.cacheRenderMesh(renderType, null, 0, 0);
            }
            return stats;
        }

        ClientTrackedAssembly.RenderMeshCache cache = assembly.renderMeshCache(renderType);
        if (cache == null) {
            cache = buildRenderMeshCache(container, assembly, renderType, blockRenderer, random);
        }
        if (cache.blocks() > 0 && cache.vertexBuffer() != null) {
            PoseStack rootPose = new PoseStack();
            applyRootPose(rootPose, assembly.renderPose(partialTick), assembly.metadata(), camX, camY, camZ);
            Matrix4f transformedModelView = new Matrix4f(modelViewMatrix).mul(rootPose.last().pose());
            drawCachedWithModelView(renderType, cache.vertexBuffer(), transformedModelView);
        }
        return new RenderStats(cache.blocks() > 0 ? 1 : 0, cache.chunks(), cache.blocks(), 0);
    }

    private static boolean isVisible(ClientTrackedAssembly assembly, Frustum frustum, float partialTick) {
        AABB bounds = assembly.renderWorldBounds(partialTick).orElse(null);
        return bounds == null || frustum.isVisible(bounds);
    }

    private static RenderStats renderAssemblyImmediate(
            ClientAssemblyContainer container,
            ClientTrackedAssembly assembly,
            RenderType renderType,
            BlockRenderDispatcher blockRenderer,
            RandomSource random,
            float partialTick,
            Matrix4f modelViewMatrix,
            double camX,
            double camY,
            double camZ
    ) {
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
                int blockCount = renderChunk(assembly, chunk, renderType, blockRenderer, random, poseStack, bufferBuilder);
                if (blockCount > 0) {
                    renderedChunks++;
                    renderedBlocks += blockCount;
                }
            }
            MeshData meshData = bufferBuilder.build();
            if (meshData != null) {
                if (renderType.sortOnUpload()) {
                    meshData.sortQuads(byteBuffer, RenderSystem.getVertexSorting());
                }
                drawWithModelView(renderType, meshData, modelViewMatrix);
            }
        } finally {
            ModelBlockRenderer.clearCache();
        }
        return new RenderStats(1, renderedChunks, renderedBlocks, 0);
    }

    private static ClientTrackedAssembly.RenderMeshCache buildRenderMeshCache(
            ClientAssemblyContainer container,
            ClientTrackedAssembly assembly,
            RenderType renderType,
            BlockRenderDispatcher blockRenderer,
            RandomSource random
    ) {
        PoseStack poseStack = new PoseStack();
        int renderedChunks = 0;
        int renderedBlocks = 0;
        VertexBuffer vertexBuffer = null;
        ModelBlockRenderer.enableCaching();
        try (ByteBufferBuilder byteBuffer = new ByteBufferBuilder(renderType.bufferSize())) {
            BufferBuilder bufferBuilder = new BufferBuilder(byteBuffer, renderType.mode(), renderType.format());
            for (ChunkPos chunkPos : assembly.loadedChunks()) {
                LevelChunk chunk = container.plotChunk(chunkPos).orElse(null);
                if (chunk == null) {
                    continue;
                }
                int blockCount = renderChunk(assembly, chunk, renderType, blockRenderer, random, poseStack, bufferBuilder);
                if (blockCount > 0) {
                    renderedChunks++;
                    renderedBlocks += blockCount;
                }
            }
            MeshData meshData = bufferBuilder.build();
            if (meshData != null) {
                vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                // VertexBuffer.upload records attribute and index bindings into the currently bound VAO.
                vertexBuffer.bind();
                try {
                    vertexBuffer.upload(meshData);
                } finally {
                    VertexBuffer.unbind();
                }
            }
        } finally {
            ModelBlockRenderer.clearCache();
        }
        assembly.cacheRenderMesh(renderType, vertexBuffer, renderedChunks, renderedBlocks);
        return assembly.renderMeshCache(renderType);
    }

    private static int renderChunk(
            ClientTrackedAssembly assembly,
            LevelChunk chunk,
            RenderType renderType,
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
            renderedBlocks += renderSection(assembly, chunk, section, sectionY, renderType, blockRenderer, random, rootPose, blockBuffer);
        }
        return renderedBlocks;
    }

    private static int renderSection(
            ClientTrackedAssembly assembly,
            LevelChunk chunk,
            LevelChunkSection section,
            int sectionY,
            RenderType renderType,
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
                    if (renderFluid(state, immutablePlotPos, assembly, renderType, blockRenderer, fluidBuffer)) {
                        renderedBlocks++;
                    }
                    if (renderBlock(state, immutablePlotPos, assembly, renderType, blockRenderer, random, rootPose, blockBuffer)) {
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
            RenderType renderType,
            BlockRenderDispatcher blockRenderer,
            RandomSource random,
            PoseStack rootPose,
            BufferBuilder buffer
    ) {
        if (state.getRenderShape() != RenderShape.MODEL) {
            return false;
        }

        BakedModel model = blockRenderer.getBlockModel(state);
        ModelData modelData = model.getModelData(assembly.levelView(), plotPos, state, ModelData.EMPTY);
        random.setSeed(state.getSeed(plotPos));
        if (!model.getRenderTypes(state, random, modelData).contains(renderType)) {
            return false;
        }

        PoseStack blockPose = blockPose(rootPose, assembly.metadata(), plotPos);
        random.setSeed(state.getSeed(plotPos));
        blockRenderer.renderBatched(state, plotPos, assembly.levelView(), blockPose, buffer, true, random, modelData, renderType);
        return true;
    }

    private static boolean renderFluid(
            BlockState state,
            BlockPos plotPos,
            ClientTrackedAssembly assembly,
            RenderType renderType,
            BlockRenderDispatcher blockRenderer,
            TransformingVertexConsumer buffer
    ) {
        FluidState fluidState = state.getFluidState();
        if (fluidState.isEmpty() || ItemBlockRenderTypes.getRenderLayer(fluidState) != renderType) {
            return false;
        }

        blockRenderer.renderLiquid(plotPos, assembly.levelView(), buffer, state, fluidState);
        return true;
    }

    private static int renderAssemblyBlockEntities(
            Minecraft minecraft,
            ClientAssemblyContainer container,
            ClientTrackedAssembly assembly,
            float partialTick,
            double camX,
            double camY,
            double camZ
    ) {
        int rendered = 0;
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        PoseStack rootPose = new PoseStack();
        applyRootPose(rootPose, assembly.renderPose(partialTick), assembly.metadata(), camX, camY, camZ);

        for (ChunkPos chunkPos : assembly.loadedChunks()) {
            LevelChunk chunk = container.plotChunk(chunkPos).orElse(null);
            if (chunk == null) {
                continue;
            }
            for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                if (blockEntity.isRemoved() || !assembly.plot().containsPlotBlockPos(blockEntity.getBlockPos())) {
                    continue;
                }
                if (!blockEntity.getType().isValid(blockEntity.getBlockState())) {
                    continue;
                }
                if (renderBlockEntity(minecraft, assembly, blockEntity, rootPose, partialTick, bufferSource)) {
                    rendered++;
                }
            }
        }
        return rendered;
    }

    private static <E extends BlockEntity> boolean renderBlockEntity(
            Minecraft minecraft,
            ClientTrackedAssembly assembly,
            E blockEntity,
            PoseStack rootPose,
            float partialTick,
            MultiBufferSource.BufferSource bufferSource
    ) {
        BlockEntityRenderer<E> renderer = minecraft.getBlockEntityRenderDispatcher().getRenderer(blockEntity);
        if (renderer == null) {
            return false;
        }

        PoseStack poseStack = blockPose(rootPose, assembly.metadata(), blockEntity.getBlockPos());
        int packedLight = LevelRenderer.getLightColor(assembly.levelView(), blockEntity.getBlockPos());
        renderer.render(blockEntity, partialTick, poseStack, bufferSource, packedLight, OverlayTexture.NO_OVERLAY);
        bufferSource.endBatch();
        return true;
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
                consumer,
                ModelData.EMPTY
        );
        drawCrumblingWithModelView(minecraft.renderBuffers().crumblingBufferSource(), modelViewMatrix);
    }

    private static void drawWithModelView(RenderType renderType, MeshData meshData, Matrix4f modelViewMatrix) {
        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        try {
            stack.identity();
            stack.mul(modelViewMatrix);
            RenderSystem.applyModelViewMatrix();
            renderType.draw(meshData);
        } finally {
            stack.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static void drawCachedWithModelView(RenderType renderType, VertexBuffer vertexBuffer, Matrix4f modelViewMatrix) {
        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        try {
            stack.identity();
            stack.mul(modelViewMatrix);
            RenderSystem.applyModelViewMatrix();
            renderType.setupRenderState();
            vertexBuffer.bind();
            vertexBuffer.drawWithShader(
                    RenderSystem.getModelViewMatrix(),
                    RenderSystem.getProjectionMatrix(),
                    RenderSystem.getShader()
            );
        } finally {
            VertexBuffer.unbind();
            renderType.clearRenderState();
            stack.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static void drawLinesWithModelView(MultiBufferSource.BufferSource bufferSource, Matrix4f modelViewMatrix) {
        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        try {
            stack.identity();
            stack.mul(modelViewMatrix);
            RenderSystem.applyModelViewMatrix();
            bufferSource.endBatch(RenderType.lines());
        } finally {
            stack.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static void drawCrumblingWithModelView(MultiBufferSource.BufferSource bufferSource, Matrix4f modelViewMatrix) {
        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        try {
            stack.identity();
            stack.mul(modelViewMatrix);
            RenderSystem.applyModelViewMatrix();
            bufferSource.endBatch();
        } finally {
            stack.popMatrix();
            RenderSystem.applyModelViewMatrix();
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

    private static void beginLayer(RenderType renderType) {
        if (renderType == RenderType.solid() || !currentFrameStats.started) {
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

    private static RenderType renderType(RenderLevelStageEvent.Stage stage) {
        if (stage == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            return RenderType.solid();
        }
        if (stage == RenderLevelStageEvent.Stage.AFTER_CUTOUT_MIPPED_BLOCKS_BLOCKS) {
            return RenderType.cutoutMipped();
        }
        if (stage == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) {
            return RenderType.cutout();
        }
        if (stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return RenderType.translucent();
        }
        if (stage == RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) {
            return RenderType.tripwire();
        }
        return null;
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
        static final RenderStats EMPTY = new RenderStats(0, 0, 0, 0);

        boolean hasGeometry() {
            return blocks > 0 || blockEntities > 0;
        }
    }
}
