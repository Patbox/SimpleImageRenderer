package eu.pb4.simpleimagerenderer.renderer;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import eu.pb4.simpleimagerenderer.ManualCamera;
import eu.pb4.simpleimagerenderer.ModInit;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.state.ParticleGroupRenderState;
import net.minecraft.client.renderer.state.ParticlesRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.*;
import org.jspecify.annotations.Nullable;

import java.lang.Math;
import java.util.*;
import java.util.function.BiConsumer;


public class RegionImageRenderer extends AbstractImageRenderer<Void> {
    private final List<Section> sections = new ArrayList<>();
    private final List<EntityRenderState> entities = new ArrayList<>();
    private final List<BlockEntityRenderState> blockEntities = new ArrayList<>();
    private final List<ParticleGroupRenderState> particles = new ArrayList<>();

    private final GpuSampler chunkLayerSampler;
    private final BlockBox area;

    private boolean renderEntities = true;
    private boolean renderNametags = true;
    private boolean renderSelf = true;

    public RegionImageRenderer(Minecraft minecraft, int width, int height, ClientLevel level, BlockBox area) {
        super(minecraft, width, height);
        this.area = area;

        {
            int i = this.minecraft.options.textureFiltering().get() == TextureFilteringMethod.ANISOTROPIC ? this.minecraft.options.maxAnisotropyValue() : 1;
            this.chunkLayerSampler = RenderSystem.getDevice()
                    .createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, i, OptionalDouble.empty());
        }

        var region = new FauxRenderSectionRegion(level, area);
        var compiler = new SectionCompiler(minecraft.getBlockRenderer(), minecraft.getBlockEntityRenderDispatcher());
        var sectionStart = SectionPos.of(area.min());
        var sectionEnd = SectionPos.of(area.max());
        var builder = minecraft.renderBuffers().fixedBufferPack();
        var iter = SectionPos.betweenClosedStream(sectionStart.x(), sectionStart.y(), sectionStart.z(), sectionEnd.x(), sectionEnd.y(), sectionEnd.z()).iterator();
        while (iter.hasNext()) {
            builder.discardAll();
            var pos = iter.next();
            var results = compiler.compile(pos, region, VertexSorting.ORTHOGRAPHIC_Z, builder);
            var compiledSectionMesh = new CompiledSectionMesh(TranslucencyPointOfView.of(Vec3.ZERO, 0), results);
            for (var layer : ChunkSectionLayer.values()) {
                if (results.renderedLayers.containsKey(layer)) {
                    var meshData = results.renderedLayers.get(layer);
                    compiledSectionMesh.uploadMeshLayer(layer, meshData, pos.asLong());
                }
            }
            if (compiledSectionMesh.hasRenderableLayers()) {
                this.sections.add(new Section(pos.origin(), compiledSectionMesh));
            } else {
                compiledSectionMesh.close();
            }
            results.release();
        }

        for (var section : this.sections) {
            for (var be : section.sectionMesh.getRenderableBlockEntities()) {
                var state = this.minecraft.getBlockEntityRenderDispatcher().tryExtractRenderState(be, 0, null);
                if (state != null) {
                    this.blockEntities.add(state);
                }
            }
        }

        for (var entity : level.getEntities(null, area.aabb())) {
            var state = minecraft.getEntityRenderDispatcher().extractEntity(entity, 0);
            this.entities.add(state);
        }

        //var fakeFrustrum = new BoxyFrustum(area);
        //var camera = new ManualCamera();
        //camera.setPosition(area.aabb().getCenter());

        //for (var particle : ((ParticleEngineAccessor) minecraft.particleEngine).getParticles().values()) {
        //    this.particles.add(particle.extractRenderState(fakeFrustrum, camera, minecraft.getDeltaTracker().getGameTimeDeltaTicks()));
        //}

        builder.discardAll();
    }

    @Override
    public void close() {
        super.close();
        for (var section : this.sections) {
            section.sectionMesh.close();
        }
        this.sections.clear();
    }

    public boolean renderEntities() {
        return renderEntities;
    }

    public void setRenderEntities(boolean renderEntities) {
        this.renderEntities = renderEntities;
    }

    public boolean renderSelf() {
        return renderSelf;
    }

    public void setRenderSelf(boolean renderSelf) {
        this.renderSelf = renderSelf;
    }

    public boolean renderNametags() {
        return renderNametags;
    }

    public void setRenderNametags(boolean renderNametags) {
        this.renderNametags = renderNametags;
    }

    @Override
    protected void renderInner(BiConsumer<TextureTarget, Void> targetConsumer, boolean preview) {
        var center = area.aabb().getCenter();

        var poseStack = new PoseStack();
        poseStack.pushPose();
        this.multiplyPoseStack(poseStack);
        var cameraState = new CameraRenderState();
        cameraState.entityPos = center;
        cameraState.blockPos = BlockPos.containing(center);
        cameraState.pos = cameraState.entityPos;
        cameraState.orientation = this.cameraOrientation;
        cameraState.initialized = true;

        var camera = new ManualCamera();
        camera.setPosition(center);
        camera.setRotation(cameraState.orientation);
        var fakeFrustrum = new BoxyFrustum(area);

        this.minecraft.gameRenderer.getGlobalSettingsUniform()
                .update(
                        this.renderTarget.width,
                        this.renderTarget.height,
                        this.minecraft.options.glintStrength().get(),
                        this.minecraft.level == null ? 0L : this.minecraft.level.getGameTime(),
                        this.minecraft.getDeltaTracker(),
                        this.minecraft.options.getMenuBackgroundBlurriness(),
                        camera,
                        this.minecraft.options.textureFiltering().get() == TextureFilteringMethod.RGSS
                );

        poseStack.scale(width, -width, width);
        var scale = 1f / Math.min(Math.min(area.sizeX(), area.sizeY()), area.sizeZ());
        poseStack.scale(scale, scale, scale);

        poseStack.last().normal().scale(1, -1, 1);

        minecraft.gameRenderer.getLighting().setupFor(this.lightingType.getEntry(Lighting.Entry.LEVEL));

        var render = prepareChunkRenders(poseStack.last().pose());

        renderGroup(render, ChunkSectionLayerGroup.OPAQUE, this.chunkLayerSampler);

        ModInit.mainRenderTargetReplacement = this.renderTarget;
        for (var state : this.blockEntities) {
            poseStack.pushPose();
            poseStack.translate(state.blockPos.getX() - center.x, state.blockPos.getY() - center.y, state.blockPos.getZ() - center.z);
            this.minecraft.getBlockEntityRenderDispatcher().submit(state, poseStack,
                    this.renderDispatcher.getSubmitNodeStorage(), new CameraRenderState());
            poseStack.popPose();
        }
        this.renderDispatcher.renderAllFeatures();
        if (this.renderEntities) {
            for (var state : this.entities) {
                if (!this.renderSelf && state instanceof AvatarRenderState state1 && state1.id == this.minecraft.player.getId()) {
                    continue;
                }

                poseStack.pushPose();
                poseStack.translate(state.x - center.x, state.y - center.y, state.z - center.z);
                var nameTag = state.nameTag;
                if (!this.renderNametags) {
                    state.nameTag = null;
                }
                minecraft.getEntityRenderDispatcher().submit(state, cameraState, 0, 0, 0, poseStack, this.renderDispatcher.getSubmitNodeStorage());
                state.nameTag = nameTag;
                poseStack.popPose();
            }
            this.renderDispatcher.renderAllFeatures();
        }

        /*for (var state : this.particles) {
            poseStack.pushPose();
            state.submit(this.renderDispatcher.getSubmitNodeStorage(), cameraState);
            poseStack.popPose();
        }*/
        var particles = new ParticlesRenderState();
        this.minecraft.particleEngine.extract(particles, fakeFrustrum, camera, this.minecraft.getDeltaTracker().getGameTimeDeltaTicks());
        particles.submit(this.renderDispatcher.getSubmitNodeStorage(), cameraState);
        particles.reset();

        this.renderDispatcher.renderAllFeatures();

        renderGroup(render, ChunkSectionLayerGroup.TRANSLUCENT, this.chunkLayerSampler);
        renderGroup(render, ChunkSectionLayerGroup.TRIPWIRE, this.chunkLayerSampler);

        this.renderDispatcher.renderAllFeatures();
        this.renderDispatcher.endFrame();
        bufferSource.endBatch();
        ModInit.mainRenderTargetReplacement = null;

        targetConsumer.accept(this.renderTarget, null);


        this.minecraft.gameRenderer.getGlobalSettingsUniform()
                .update(
                        this.minecraft.getWindow().getWidth(),
                        this.minecraft.getWindow().getHeight(),
                        this.minecraft.options.glintStrength().get(),
                        this.minecraft.level == null ? 0L : this.minecraft.level.getGameTime(),
                        this.minecraft.getDeltaTracker(),
                        this.minecraft.options.getMenuBackgroundBlurriness(),
                        this.minecraft.gameRenderer.getMainCamera(),
                        this.minecraft.options.textureFiltering().get() == TextureFilteringMethod.RGSS
                );
    }

    @Override
    public void updateMatrix(Matrix4f matrix4f, Quaternionf quaternionf) {
        super.updateMatrix(matrix4f, quaternionf);

        var camPos = new Vector4f(0, 0, -1, 0);
        camPos.mul(new Matrix4f(projectionMatrix).invert()).mul(new Matrix4f(matrix4f).invert());

        var vec = new Vector3f(camPos.x, camPos.y, camPos.z).normalize().mul(1000).mul(1, -1,1).add(this.area.aabb().getCenter().toVector3f());
        var vec3 = new Vec3(vec);
        var builder = minecraft.renderBuffers().fixedBufferPack();

        builder.discardAll();
        for (var section : this.sections) {
            var sectionId = SectionPos.asLong(section.origin);
            var pow = TranslucencyPointOfView.of(new Vec3(vec), sectionId);

            var sortState = section.sectionMesh.getTransparencyState();
            if (sortState == null || !section.sectionMesh.hasTranslucentGeometry() || !section.sectionMesh.isDifferentPointOfView(pow))
                continue;

            var result = sortState.buildSortedIndexBuffer(builder.buffer(ChunkSectionLayer.TRANSLUCENT),
                    VertexSorting.byDistance(vec));
            if (result == null) {
                continue;
            }

            section.sectionMesh.uploadLayerIndexBuffer(ChunkSectionLayer.TRANSLUCENT, result, sectionId);
            section.sectionMesh.setTranslucencyPointOfView(pow);
            builder.discardAll();
        }


        this.sections.sort(Comparator.comparing(x -> x.origin.distToCenterSqr(vec3)));
    }

    @Override
    public Component getTitle() {
        return Component.translatable("text.simple_image_renderer.region", this.area.min().toShortString(), this.area.max().toShortString());
    }

    private void renderGroup(ChunkSectionsToRender sectionsToRender, ChunkSectionLayerGroup chunkSectionLayerGroup, GpuSampler gpuSampler) {
        RenderSystem.AutoStorageIndexBuffer autoStorageIndexBuffer = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        GpuBuffer gpuBuffer = sectionsToRender.maxIndicesRequired() == 0 ? null : autoStorageIndexBuffer.getBuffer(sectionsToRender.maxIndicesRequired());
        VertexFormat.IndexType indexType = sectionsToRender.maxIndicesRequired() == 0 ? null : autoStorageIndexBuffer.type();
        ChunkSectionLayer[] chunkSectionLayers = chunkSectionLayerGroup.layers();
        Minecraft minecraft = Minecraft.getInstance();
        boolean bl = SharedConstants.DEBUG_HOTKEYS && minecraft.wireframe;
        RenderTarget renderTarget = this.renderTarget;//chunkSectionLayerGroup.outputTarget();

        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(
                        () -> "Section layers for " + chunkSectionLayerGroup.label(),
                        renderTarget.getColorTextureView(),
                        OptionalInt.empty(),
                        renderTarget.getDepthTextureView(),
                        OptionalDouble.empty()
                )) {
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.bindTexture("Sampler2", minecraft.gameRenderer.lightTexture().getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));

            for (ChunkSectionLayer chunkSectionLayer : chunkSectionLayers) {
                List<RenderPass.Draw<GpuBufferSlice[]>> list = sectionsToRender.drawsPerLayer().get(chunkSectionLayer);
                if (!list.isEmpty()) {
                    if (chunkSectionLayer == ChunkSectionLayer.TRANSLUCENT) {
                        list = list.reversed();
                    }

                    renderPass.setPipeline(bl ? RenderPipelines.WIREFRAME : chunkSectionLayer.pipeline());
                    renderPass.bindTexture("Sampler0", sectionsToRender.textureView(), gpuSampler);
                    renderPass.drawMultipleIndexed(list, gpuBuffer, indexType, List.of("ChunkSection"), sectionsToRender.chunkSectionInfos());
                }
            }
        }
    }

    private ChunkSectionsToRender prepareChunkRenders(final Matrix4fc modelViewMatrix) {

        EnumMap<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> drawsByLayer = new EnumMap(ChunkSectionLayer.class);
        int largestIndexCount = 0;

        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            drawsByLayer.put(layer, new ArrayList<>());
        }

        ArrayList<DynamicUniforms.ChunkSectionInfo> sectionInfos = new ArrayList<>();
        GpuTextureView blockAtlas = this.minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        int textureAtlasWidth = blockAtlas.getWidth(0);
        int textureAtlasHeight = blockAtlas.getHeight(0);

        for (var section : this.sections) {
            var sectionMesh = section.sectionMesh();
            var renderOffset = section.origin;
            int uboIndex = -1;

            for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                SectionBuffers buffers = sectionMesh.getBuffers(layer);
                if (buffers != null) {
                    if (uboIndex == -1) {
                        uboIndex = sectionInfos.size();
                        sectionInfos.add(
                                new DynamicUniforms.ChunkSectionInfo(
                                        new Matrix4f(modelViewMatrix),
                                        renderOffset.getX(),
                                        renderOffset.getY(),
                                        renderOffset.getZ(),
                                        1,
                                        textureAtlasWidth,
                                        textureAtlasHeight
                                )
                        );
                    }

                    GpuBuffer indexBuffer;
                    VertexFormat.IndexType indexType;
                    if (buffers.getIndexBuffer() == null) {
                        if (buffers.getIndexCount() > largestIndexCount) {
                            largestIndexCount = buffers.getIndexCount();
                        }

                        indexBuffer = null;
                        indexType = null;
                    } else {
                        indexBuffer = buffers.getIndexBuffer();
                        indexType = buffers.getIndexType();
                    }

                    int finalUboIndex = uboIndex;
                    drawsByLayer.get(layer).add(
                            new RenderPass.Draw<>(
                                    0,
                                    buffers.getVertexBuffer(),
                                    indexBuffer,
                                    indexType,
                                    0,
                                    buffers.getIndexCount(),
                                    (sectionUbos, uploader) -> uploader.upload("ChunkSection", sectionUbos[finalUboIndex])
                            )
                    );
                }
            }
        }

        GpuBufferSlice[] chunkSectionInfos = RenderSystem.getDynamicUniforms()
                .writeChunkSections(sectionInfos.toArray(new DynamicUniforms.ChunkSectionInfo[0]));

        return new ChunkSectionsToRender(blockAtlas, drawsByLayer, largestIndexCount, chunkSectionInfos);
    }

    public record Section(BlockPos origin, CompiledSectionMesh sectionMesh) {
    }

    private static class FauxRenderSectionRegion extends RenderSectionRegion {
        private LevelChunkSection emptySection;
        private final BlockBox area;
        private final Level level;
        private final Long2ObjectMap<LevelChunkSection> sections = new Long2ObjectOpenHashMap<>();

        public FauxRenderSectionRegion(Level level, BlockBox area) {
            super(level, 0, 0, 0, new SectionCopy[0]);
            this.level = level;
            this.area = area;
        }

        @Override
        public BlockState getBlockState(BlockPos blockPos) {
            if (this.area.contains(blockPos)) {
                var key = SectionPos.asLong(blockPos);
                var section = this.sections.get(key);
                if (section == null) {
                    var chunk = this.level.getChunk(SectionPos.x(key), SectionPos.z(key));
                    section = chunk.getSection(chunk.getSectionIndexFromSectionY(SectionPos.y(key))).copy();
                    this.sections.put(key, section);
                }

                return section.getStates().get(blockPos.getX() & 15, blockPos.getY() & 15, blockPos.getZ() & 15);
            }

            return Blocks.VOID_AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos blockPos) {
            if (this.area.contains(blockPos)) {
                var key = SectionPos.asLong(blockPos);
                var section = this.sections.get(key);
                if (section == null) {
                    var chunk = this.level.getChunk(SectionPos.x(key), SectionPos.z(key));
                    section = chunk.getSection(chunk.getSectionIndexFromSectionY(SectionPos.y(key))).copy();
                    this.sections.put(key, section);
                }

                return section.getFluidState(blockPos.getX() & 15, blockPos.getY() & 15, blockPos.getZ() & 15);
            }

            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public @Nullable BlockEntity getBlockEntity(BlockPos blockPos) {
            if (this.area.contains(blockPos)) {
                return this.level.getBlockEntity(blockPos);
            }

            return null;
        }
    }

    private static class BoxyFrustum extends Frustum {
        private final AABB area;

        public BoxyFrustum(BlockBox area) {
            super(new Matrix4f(), new Matrix4f());
            this.area = area.aabb();
        }

        @Override
        public boolean isVisible(AABB aABB) {
            return this.area.intersects(aABB);
        }

        @Override
        public int cubeInFrustum(BoundingBox boundingBox) {
            return this.area.intersects(AABB.of(boundingBox)) ? 1 : 0;
        }

        @Override
        public boolean pointInFrustum(double d, double e, double f) {
            return true;
        }
    }
}
