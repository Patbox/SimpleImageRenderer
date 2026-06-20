package eu.pb4.simpleimagerenderer.renderer;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.*;
import eu.pb4.simpleimagerenderer.mixin.GameRendererAccessor;
import eu.pb4.simpleimagerenderer.mixin.LevelRendererAccessor;
import eu.pb4.simpleimagerenderer.util.BoxyFrustum;
import eu.pb4.simpleimagerenderer.util.ManualCamera;
import eu.pb4.simpleimagerenderer.util.RenderUtils;
import eu.pb4.simpleimagerenderer.util.renderregion.AreaRenderSectionRegion;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;
import org.joml.*;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.*;


public class RegionImageRenderer extends AbstractImageRenderer<BlockBox> {
    private final List<Section> sections = new ArrayList<>();
    private final LevelRenderState levelRenderState = new LevelRenderState();
    private final CrossFrameResourcePool resourcePool = new CrossFrameResourcePool(3);
    private final ParticlesRenderState particlesRenderState = new ParticlesRenderState();
    private final List<StateBackup> entityStateBackup = new ArrayList<>();
    private final List<StateBackup> blockEntityStateBackup = new ArrayList<>();

    private final GpuSampler chunkLayerSampler;
    private final BlockBox area;
    private final AreaRenderSectionRegion region;
    @Nullable
    private final EntityRenderState selfEntityState;
    private final Map<ChunkSectionLayer, SectionUberBuffers> chunkUberBuffers;
    private final StagingBuffer stagingBuffer;

    private boolean renderEntities = true;
    private boolean renderNametags = true;
    private boolean renderSelf = true;
    private boolean renderParticles = true;

    private TextureTarget entityOutlineTarget;
    private boolean sectionsDirty = false;
    private boolean statesDirty = false;


    public RegionImageRenderer(Minecraft minecraft, int width, int height, ClientLevel level, BlockBox area, boolean renderEdge, boolean ignoreLighting) {
        super(minecraft, width, height);
        this.useUiLightmapByDefault = false;
        this.area = area;

        {
            this.chunkLayerSampler = RenderSystem.getDevice()
                    .createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
        }

        var gpuDevice = RenderSystem.getDevice();
        this.stagingBuffer = StagingBuffer.create("Chunk", gpuDevice, 102760448);
        this.chunkUberBuffers = Util.makeEnumMap(ChunkSectionLayer.class, (layer) -> {
            VertexFormat vertexFormat = layer.pipeline().getVertexFormatBinding(0);
            UberGpuBuffer<SectionMesh> vertexUberBuffer = new UberGpuBuffer<>(layer.label(), 32, 134217728, vertexFormat.getVertexSize(), stagingBuffer);
            UberGpuBuffer<SectionMesh> indexUberBuffer = layer == ChunkSectionLayer.TRANSLUCENT ? new UberGpuBuffer<>(layer.label(), 64, 33554432, 8, stagingBuffer) : null;
            return new SectionUberBuffers(vertexUberBuffer, indexUberBuffer);
        });

        this.region = new AreaRenderSectionRegion(level, area);
        this.region.setAllowExternalLookup(!renderEdge);
        this.region.setIgnoreLighting(ignoreLighting);
        this.rebuildSections();

        for (var section : this.sections) {
            for (var be : section.sectionMesh.getRenderableBlockEntities()) {
                var state = this.minecraft.getBlockEntityRenderDispatcher().tryExtractRenderState(be, 0, null, true);
                if (state != null) {
                    this.levelRenderState.blockEntityRenderStates.add(state);
                }
            }

            for (var be : level.getGloballyRenderedBlockEntities()) {
                if (area.contains(be.getBlockPos())) {
                    var state = this.minecraft.getBlockEntityRenderDispatcher().tryExtractRenderState(be, 0, null, true);
                    if (state != null) {
                        this.levelRenderState.blockEntityRenderStates.add(state);
                    }
                }
            }
        }

        for (var state : this.levelRenderState.blockEntityRenderStates) {
            this.blockEntityStateBackup.add(new StateBackup(null, state.lightCoords));
        }

        EntityRenderState self = null;

        for (var entity : level.getEntities(null, area.aabb().inflate(0.01f))) {
            var state = minecraft.getEntityRenderDispatcher().extractEntity(entity, 0);
            this.levelRenderState.shouldShowEntityOutlines |= state.appearsGlowing();
            if (state instanceof AvatarRenderState avatarRenderState && avatarRenderState.id == minecraft.player.getId()) {
                self = state;
                continue;
            }

            this.levelRenderState.entityRenderStates.add(state);
        }

        this.selfEntityState = self;

        if (self != null) {
            this.levelRenderState.entityRenderStates.add(self);
        }

        for (var state : this.levelRenderState.entityRenderStates) {
            this.entityStateBackup.add(new StateBackup(state.nameTag, state.lightCoords));
        }

        this.entityOutlineTarget = new TextureTarget("Entity outline Renderer", this.renderTarget.width, this.renderTarget.height, true, GpuFormat.RGBA8_UNORM);

        this.updateStates();
    }

    public void updateStates() {
        this.statesDirty = false;
        if (this.selfEntityState != null) {
            if (this.renderSelf && (this.levelRenderState.entityRenderStates.isEmpty()
                    || this.levelRenderState.entityRenderStates.getLast() != this.selfEntityState)) {
                this.levelRenderState.entityRenderStates.add(this.selfEntityState);
            } else if (!this.renderSelf && !this.levelRenderState.entityRenderStates.isEmpty()
                    && this.levelRenderState.entityRenderStates.getLast() == this.selfEntityState) {
                this.levelRenderState.entityRenderStates.removeLast();
            }
        }

        for (var i = 0; i < this.levelRenderState.entityRenderStates.size(); i++) {
            var state = this.levelRenderState.entityRenderStates.get(i);
            var backup = this.entityStateBackup.get(i);
            state.lightCoords = !this.ignoreLighting() ? backup.lightCoords : 15728880;
            state.nameTag = this.renderNametags ? backup.nameTag : null;
        }


        for (var i = 0; i < this.levelRenderState.blockEntityRenderStates.size(); i++) {
            var state = this.levelRenderState.blockEntityRenderStates.get(i);
            var backup = this.blockEntityStateBackup.get(i);
            state.lightCoords = !this.ignoreLighting() ? backup.lightCoords : 15728880;
        }
    }

    private void rebuildSections() {
        this.sectionsDirty = false;
        for (var section : this.sections) {
            this.releaseSectionMesh(section.sectionMesh);
            section.sectionMesh.close();
        }

        this.sections.clear();

        var sorting = VertexSorting.byDistance(this.getCameraPos());

        var options = this.minecraft.options;
        var compiler = new SectionCompiler(
                options.ambientOcclusion().get(),
                options.cutoutLeaves().get(),
                this.minecraft.getModelManager().getBlockStateModelSet(),
                this.minecraft.getModelManager().getFluidStateModelSet(),
                this.minecraft.getBlockColors()
        );

        var sectionStart = SectionPos.of(area.min());
        var sectionEnd = SectionPos.of(area.max());
        var builder = minecraft.gameRenderer.renderBuffers().fixedBufferPack();
        var iter = SectionPos.betweenClosedStream(sectionStart.x(), sectionStart.y(), sectionStart.z(), sectionEnd.x(), sectionEnd.y(), sectionEnd.z()).iterator();
        while (iter.hasNext()) {
            builder.discardAll();
            var pos = iter.next();
            var results = compiler.compile(pos, region, sorting, builder);
            var compiledSectionMesh = new CompiledSectionMesh(TranslucencyPointOfView.of(Vec3.ZERO, 0), results);
            for (var layer : ChunkSectionLayer.values()) {
                if (results.renderedLayers.containsKey(layer)) {
                    var meshData = results.renderedLayers.get(layer);
                    var success = false;
                    while(!success) {
                        success = this.addSectionBuffersToUberBuffer(layer, compiledSectionMesh, meshData.vertexBuffer(), meshData.indexBuffer());
                        if (!success && !RenderSystem.isOnRenderThread()) {
                            Thread.onSpinWait();
                        }
                    }
                }
            }
            if (compiledSectionMesh.hasRenderableLayers()) {
                this.sections.add(new Section(pos.origin(), compiledSectionMesh));
            } else {
                compiledSectionMesh.close();
            }
            results.release();
        }
        builder.discardAll();
        this.updateMatrix(this.matrix, this.cameraOrientation);
    }

    @Override
    public void close() {
        super.close();
        for (var section : this.sections) {
            this.releaseSectionMesh(section.sectionMesh);
            section.sectionMesh.close();
        }
        this.sections.clear();
        if (this.entityOutlineTarget != null) {
            this.entityOutlineTarget.destroyBuffers();
        }
        this.resourcePool.close();
        this.chunkLayerSampler.close();
        for (var buffers : this.chunkUberBuffers.values()) {
            buffers.vertexBuffer.close();
            if (buffers.indexBuffer != null) {
                buffers.indexBuffer.close();
            }
        }
        this.stagingBuffer.close();
    }

    @Override
    public void setupTexture(int width, int height) {
        super.setupTexture(width, height);
        if (this.resourcePool != null) {
            this.resourcePool.clear();
        }
        if (this.entityOutlineTarget != null) {
            this.entityOutlineTarget.destroyBuffers();
            this.entityOutlineTarget = new TextureTarget("Entity outline Renderer", this.renderTarget.width, this.renderTarget.height, true, GpuFormat.RGBA8_UNORM);
        }
    }

    public boolean renderEntities() {
        return renderEntities;
    }

    public void setRenderEntities(boolean renderEntities) {
        this.renderEntities = renderEntities;
        this.statesDirty = true;
    }

    public boolean renderSelf() {
        return renderSelf;
    }

    public void setRenderSelf(boolean renderSelf) {
        this.renderSelf = renderSelf;
        this.statesDirty = true;
    }

    public boolean renderNametags() {
        return renderNametags;
    }

    public boolean renderParticles() {
        return renderParticles;
    }

    public void setRenderParticles(boolean renderParticles) {
        this.renderParticles = renderParticles;
    }

    public void setRenderNametags(boolean renderNametags) {
        this.renderNametags = renderNametags;
        this.statesDirty = true;
    }

    public boolean renderEdge() {
        return !this.region.allowExternalLookup();
    }

    public void setRenderEdge(boolean value) {
        if (renderEdge() != value) {
            this.region.setAllowExternalLookup(!value);
            this.sectionsDirty = true;
        }
    }

    @Override
    protected void renderInner(RenderConsumer<BlockBox> targetConsumer, boolean preview) {
        if (this.sectionsDirty) {
            this.rebuildSections();
        }
        if (this.statesDirty) {
            this.updateStates();
        }
        RenderSystem.outputColorTextureOverride = null;
        RenderSystem.outputDepthTextureOverride = null;
        var center = area.aabb().getCenter();
        var deltaTracker = DeltaTracker.ZERO;

        var poseStack = new PoseStack();
        poseStack.pushPose();
        this.multiplyPoseStack(poseStack);

        var frustum = new BoxyFrustum(area);

        var cameraState = this.levelRenderState.cameraRenderState;
        cameraState.blockPos = BlockPos.containing(center);
        cameraState.pos = center;
        cameraState.cullFrustum = frustum;
        cameraState.orientation = this.cameraOrientation;
        cameraState.initialized = true;

        var camera = new ManualCamera();
        camera.setPosition(center);
        camera.setRotation(cameraState.orientation);


        ((GameRendererAccessor) this.minecraft.gameRenderer).getGlobalSettingsUniform()
                .update(
                        this.renderTarget.width,
                        this.renderTarget.height,
                        this.minecraft.options.glintStrength().get(),
                        this.minecraft.level == null ? 0L : this.minecraft.level.getGameTime(),
                        deltaTracker,
                        this.minecraft.options.getMenuBackgroundBlurriness(),
                        center,
                        false
                );

        this.multiplyNormals = false;

        poseStack.scale(width, -width, width);
        //poseStack.scale(1, -1, 1);
        poseStack.last().normal().scale(1, -1, 1);
        var scale = 1f / ((area.sizeX() + area.sizeY() + area.sizeZ()) / 3f);
        poseStack.scale(scale, scale, scale);

        var targets = levelTargetBundle();
        RenderUtils.mainRenderTargetReplacement = this.renderTarget;

        var oldTargetMain = targets.main;
        var oldTargetTranslucent = targets.translucent;
        var oldTargetItemEntity = targets.itemEntity;
        var oldTargetParticles = targets.particles;
        var oldTargetWeather = targets.weather;
        var oldTargetClouds = targets.clouds;
        var oldEntityOutline = targets.entityOutline;

        targets.clear();

        var featureFrame = this.featureRenderDispatcher.prepareFrame(this.submitNodeStorage);

        FrameGraphBuilder frame = new FrameGraphBuilder();

        targets.main = frame.importExternal("main", this.renderTarget);
        /*var renderTargetDescriptor = new RenderTargetDescriptor(this.renderTarget.width, this.renderTarget.height, true, 0);
        var postChain = ((LevelRendererAccessor) this.minecraft.levelRenderer).sim_getTransparencyChain();
        if (postChain != null) {
            targets.translucent = frameGraphBuilder.createInternal("translucent", renderTargetDescriptor);
            targets.itemEntity = frameGraphBuilder.createInternal("item_entity", renderTargetDescriptor);
            targets.particles = frameGraphBuilder.createInternal("particles", renderTargetDescriptor);
            targets.weather = frameGraphBuilder.createInternal("weather", renderTargetDescriptor);
            targets.clouds = frameGraphBuilder.createInternal("clouds", renderTargetDescriptor);
        }*/


        if (this.entityOutlineTarget != null) {
            targets.entityOutline = frame.importExternal("entity_outline", this.entityOutlineTarget);
        }

        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.mul(poseStack.last().pose());


        if (this.renderEntities) {
            ((LevelRendererAccessor) this.minecraft.levelRenderer).sim_submitEntities(poseStack, levelRenderState, this.submitNodeStorage);
        }

        ((LevelRendererAccessor) this.minecraft.levelRenderer).sim_submitBlockEntities(poseStack, levelRenderState, this.submitNodeStorage);

        this.particlesRenderState.submit(this.submitNodeStorage, this.levelRenderState.cameraRenderState);

        ((LevelRendererAccessor) this.minecraft.levelRenderer).sim_submitBlockDestroyAnimation(poseStack, this.submitNodeStorage, levelRenderState);

        //if (renderOutlines) {
        //    ((LevelRendererAccessor) this.minecraft.levelRenderer).sim_renderBlockOutline(poseStack, this.submitNodeStorage, levelRenderState);
        //}

        if (this.renderParticles) {
            this.minecraft.particleEngine.extract(this.particlesRenderState, frustum, camera, deltaTracker.getGameTimeDeltaTicks());
        } else {
            this.particlesRenderState.reset();
        }


        var fogRenderer = ((GameRendererAccessor) this.minecraft.gameRenderer).getFogRenderer();
        var worldFog = fogRenderer.getBuffer(FogRenderer.FogMode.NONE);
        this.addMainPass(targets, frame, featureFrame, poseStack, worldFog, false, this.levelRenderState);

        PostChain outlinePostChain = this.minecraft.getShaderManager().getPostChain(LevelRendererAccessor.getENTITY_OUTLINE_POST_CHAIN_ID(), LevelTargetBundle.OUTLINE_TARGETS);
        if (featureFrame.hasAnyOutline() && outlinePostChain != null) {
            outlinePostChain.addToFrame(frame, this.renderTarget.width, this.renderTarget.height, targets);
        }

        frame.execute(this.resourcePool);

        if (featureFrame.hasAnyOutline()) {
            this.entityOutlineTarget.blitAndBlendToTexture(this.renderTarget.getColorTextureView(), this.renderTarget.getDepthTextureView());
        }

        featureFrame.close();
        RenderUtils.mainRenderTargetReplacement = null;

        targetConsumer.rendered(this.renderTarget, this.area, -1);
        targets.clear();

        modelView.popMatrix();

        targets.main = oldTargetMain;
        targets.translucent = oldTargetTranslucent;
        targets.itemEntity = oldTargetItemEntity;
        targets.particles = oldTargetParticles;
        targets.weather = oldTargetWeather;
        targets.clouds = oldTargetClouds;
        targets.entityOutline = oldEntityOutline;

        ((GameRendererAccessor) this.minecraft.gameRenderer).getGlobalSettingsUniform()
                .update(
                        this.minecraft.getWindow().getWidth(),
                        this.minecraft.getWindow().getHeight(),
                        this.minecraft.options.glintStrength().get(),
                        this.minecraft.level == null ? 0L : this.minecraft.level.getGameTime(),
                        this.minecraft.getDeltaTracker(),
                        this.minecraft.options.getMenuBackgroundBlurriness(),
                        this.minecraft.gameRenderer.mainCamera().position(),
                        this.minecraft.options.textureFiltering().get() == TextureFilteringMethod.RGSS
                );

        RenderSystem.setShaderFog(fogRenderer.getBuffer(FogRenderer.FogMode.NONE));
    }

    private void addMainPass(
            LevelTargetBundle targets,
            FrameGraphBuilder frameGraphBuilder,
            FeatureRenderDispatcher.PreparedFrame featureFrame, PoseStack sourcePoseStack,
            GpuBufferSlice fog,
            boolean renderOutlines,
            LevelRenderState levelRenderState
    ) {
        FramePass framePass = frameGraphBuilder.addPass("main");
        targets.main = framePass.readsAndWrites(targets.main);
        if (targets.translucent != null) {
            targets.translucent = framePass.readsAndWrites(targets.translucent);
        }

        if (targets.itemEntity != null) {
            targets.itemEntity = framePass.readsAndWrites(targets.itemEntity);
        }

        if (targets.weather != null) {
            targets.weather = framePass.readsAndWrites(targets.weather);
        }

        if (targets.particles != null) {
            targets.particles = framePass.readsAndWrites(targets.particles);
        }

        if (featureFrame.hasAnyOutline() && targets.entityOutline != null) {
            targets.entityOutline = framePass.readsAndWrites(targets.entityOutline);
        }

        var mainTarget = targets.main;
        var translucentTarget = targets.translucent;
        var itemEntityTarget = targets.itemEntity;
        var entityOutlineTarget = targets.entityOutline;
        var particleTarget = targets.particles;
        framePass.executes(
                () -> {
                    RenderSystem.setShaderFog(fog);
                    this.minecraft.gameRenderer.lighting().setupFor(Lighting.Entry.LEVEL);

                    var chunkSections = this.prepareChunkRenders(sourcePoseStack.last().pose());
                    chunkSections.renderGroup(ChunkSectionLayerGroup.OPAQUE, this.chunkLayerSampler);

                    if (this.levelRenderState.shouldShowEntityOutlines && entityOutlineTarget != null) {
                        RenderTarget renderTarget = entityOutlineTarget.get();
                        clearBuffer(renderTarget);
                    }


                    PoseStack poseStack = new PoseStack();

                    featureFrame.executeSolid();

                    this.solidifyBuffer(this.renderTarget);

                    if (itemEntityTarget != null) {
                        itemEntityTarget.get().copyDepthFrom(this.minecraft.gameRenderer.mainRenderTarget());
                    }

                    this.checkPoseStack(poseStack);
                    if (translucentTarget != null) {
                        translucentTarget.get().copyDepthFrom(mainTarget.get());
                    }

                    if (itemEntityTarget != null) {
                        itemEntityTarget.get().copyDepthFrom(mainTarget.get());
                    }

                    if (particleTarget != null) {
                        particleTarget.get().copyDepthFrom(mainTarget.get());
                    }

                    featureFrame.executeTranslucent();
                    featureFrame.executeOutline();

                    chunkSections.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, this.chunkLayerSampler);

                    featureFrame.executeTranslucentAfterTerrain();
                    this.particlesRenderState.reset();
                }
        );
    }

    private void checkPoseStack(PoseStack poseStack) {
        if (!poseStack.isEmpty()) {
            throw new IllegalStateException("Pose stack not empty");
        }
    }

    private Vector3f getCameraPos() {
        var camPos = new Vector4f(0, 0, -1, 0);
        camPos.mul(new Matrix4f(projectionMatrix).invert()).mul(new Matrix4f(this.matrix).invert());

        return new Vector3f(camPos.x, camPos.y, camPos.z).normalize().mul(1000).mul(1, -1, 1).add(this.area.aabb().getCenter().toVector3f());
    }


    @Override
    public void updateMatrix(Matrix4f matrix4f, Quaternionf quaternionf) {
        super.updateMatrix(matrix4f, quaternionf);
        var vec = this.getCameraPos();
        var vec3 = new Vec3(vec);
        var builder = minecraft.gameRenderer.renderBuffers().fixedBufferPack();

        builder.discardAll();
        for (var section : this.sections) {
            var sectionId = SectionPos.asLong(section.origin);
            var pow = TranslucencyPointOfView.of(vec3, sectionId);

            var sortState = section.sectionMesh.getTransparencyState();
            if (sortState == null || !section.sectionMesh.hasTranslucentGeometry() || !section.sectionMesh.isDifferentPointOfView(pow)) {
                continue;
            }

            var result = sortState.buildSortedIndexBuffer(builder.buffer(ChunkSectionLayer.TRANSLUCENT),
                    VertexSorting.byDistance(vec));
            if (result == null) {
                continue;
            }

            boolean success = false;

            while (!success) {
                success = this.addSectionBuffersToUberBuffer(ChunkSectionLayer.TRANSLUCENT, section.sectionMesh, null, result.byteBuffer());
                if (!success && !RenderSystem.isOnRenderThread()) {
                    Thread.onSpinWait();
                }
            }

            result.close();
            section.sectionMesh.setTranslucencyPointOfView(pow);
            builder.discardAll();
        }


        this.sections.sort(Comparator.comparing(x -> x.origin.distToCenterSqr(vec3)));
    }

    private boolean addSectionBuffersToUberBuffer(ChunkSectionLayer layer, CompiledSectionMesh key, @Nullable ByteBuffer vertexBuffer, ByteBuffer indexBuffer) {
        boolean success = true;

        SectionMesh.SectionDraw draw = key.getSectionDraw(layer);
        if (draw != null) {
            var sectionBuffers = this.chunkUberBuffers.get(layer);

            assert sectionBuffers != null;

            if (vertexBuffer != null) {
                UberGpuBuffer.UploadCallback<SectionMesh> callback = (mesh) -> this.vertexBufferUploadCallback(mesh, layer);
                success &= sectionBuffers.vertexBuffer.addAllocation(key, callback, vertexBuffer);
            }

            if (indexBuffer != null) {
                boolean sortedIndexBuffer = vertexBuffer == null;
                UberGpuBuffer.UploadCallback<SectionMesh> callback = (mesh) -> this.indexBufferUploadCallback(mesh, layer, sortedIndexBuffer);
                success &= sectionBuffers.indexBuffer.addAllocation(key, callback, indexBuffer);
            } else {
                key.setIndexBufferUploaded(layer);
            }
        }

        if (!success && RenderSystem.isOnRenderThread()) {
            this.uploadGlobalGeomBuffersToGPU();
        }

        return success;
    }

    public void uploadGlobalGeomBuffersToGPU() {
        var device = RenderSystem.getDevice();

        try (var uploader = this.stagingBuffer.startUploading(device.createCommandEncoder())) {
            for (var buffers : this.chunkUberBuffers.values()) {
                boolean performedBufferResize = buffers.vertexBuffer.uploadStagedAllocations(device, uploader);
                if (buffers.indexBuffer != null) {
                    buffers.indexBuffer.uploadStagedAllocations(device, uploader);
                }
                if (performedBufferResize) {
                    break;
                }
            }
        }
    }

    void vertexBufferUploadCallback(final SectionMesh sectionMesh, final ChunkSectionLayer layer) {
        if (sectionMesh instanceof CompiledSectionMesh compiledSectionMesh) {
            compiledSectionMesh.setVertexBufferUploaded(layer);
            this.checkSectionMesh(compiledSectionMesh);
        }

    }

    void indexBufferUploadCallback(final SectionMesh sectionMesh, final ChunkSectionLayer layer, final boolean sortedIndexBuffer) {
        if (sectionMesh instanceof CompiledSectionMesh compiledSectionMesh) {
            compiledSectionMesh.setIndexBufferUploaded(layer);
            if (!sortedIndexBuffer) {
                this.checkSectionMesh(compiledSectionMesh);
            }
        }
    }

    private void checkSectionMesh(final CompiledSectionMesh compiledSectionMesh) {
        boolean allBuffersUpdated = true;

        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            SectionMesh.SectionDraw draw = compiledSectionMesh.getSectionDraw(layer);
            if (draw != null) {
                allBuffersUpdated &= compiledSectionMesh.isIndexBufferUploaded(layer);
                allBuffersUpdated &= compiledSectionMesh.isVertexBufferUploaded(layer);
            }
        }

        //if (allBuffersUpdated && this.sectionMesh.get() != compiledSectionMesh) {
        //    SectionMesh oldMesh = this.setSectionMesh(compiledSectionMesh);
        //    this.releaseSectionMesh(oldMesh);
        //}
    }

    private void releaseSectionMesh(final SectionMesh oldMesh) {
        oldMesh.close();

        for (var buffers : this.chunkUberBuffers.values()) {
            UberGpuBuffer<SectionMesh> vertexBuffer = buffers.vertexBuffer;
            vertexBuffer.removeAllocation(oldMesh);
            UberGpuBuffer<SectionMesh> indexBuffer = buffers.indexBuffer;
            if (indexBuffer != null) {
                indexBuffer.removeAllocation(oldMesh);
            }
        }

    }

    @Override
    public Component getTitle() {
        return Component.translatable("text.simple_image_renderer.region", this.area.min().toShortString(), this.area.max().toShortString());
    }

    @SuppressWarnings("resource")
    private ChunkSectionsToRender prepareChunkRenders(final Matrix4fc modelViewMatrix) {
        var iterator = this.sections.iterator();
        EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>> drawGroups = new EnumMap<>(ChunkSectionLayer.class);
        int largestIndexCount = 0;

        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            drawGroups.put(layer, new Int2ObjectOpenHashMap<>());
        }

        List<DynamicUniforms.ChunkSectionInfo> sectionInfos = new ArrayList<>();
        GpuTextureView blockAtlas = this.minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        int textureAtlasWidth = blockAtlas.getWidth(0);
        int textureAtlasHeight = blockAtlas.getHeight(0);
        {
            //this.sectionRenderDispatcher.lock();

            try {
                this.uploadGlobalGeomBuffersToGPU();
            } catch (Throwable var35) {
                throw var35;
            }

            while (iterator.hasNext()) {
                var section = iterator.next();
                SectionMesh sectionMesh = section.sectionMesh;
                BlockPos renderOffset = section.origin;
                long now = Util.getMillis();
                int uboIndex = -1;

                for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                    SectionMesh.SectionDraw draw = sectionMesh.getSectionDraw(layer);
                    SectionRenderDispatcher.RenderSectionBufferSlice slice = this.getRenderSectionSlice(sectionMesh, layer);
                    if (slice != null && draw != null && (!draw.hasCustomIndexBuffer() || slice.indexBuffer() != null)) {
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

                        int combinedHash = 173;
                        VertexFormat vertexFormat = layer.pipeline().getVertexFormatBinding(0);
                        GpuBuffer vertexBuffer = slice.vertexBuffer();
                        if (layer != ChunkSectionLayer.TRANSLUCENT) {
                            combinedHash = 31 * combinedHash + vertexBuffer.hashCode();
                        }

                        int firstIndex = 0;
                        GpuBuffer indexBuffer;
                        IndexType indexType;
                        if (!draw.hasCustomIndexBuffer()) {
                            if (draw.indexCount() > largestIndexCount) {
                                largestIndexCount = draw.indexCount();
                            }

                            indexBuffer = null;
                            indexType = null;
                        } else {
                            indexBuffer = slice.indexBuffer();
                            indexType = draw.indexType();
                            if (layer != ChunkSectionLayer.TRANSLUCENT) {
                                combinedHash = 31 * combinedHash + indexBuffer.hashCode();
                                combinedHash = 31 * combinedHash + indexType.hashCode();
                            }

                            firstIndex = (int) (slice.indexBufferOffset() / indexType.bytes);
                        }

                        int finalUboIndex = uboIndex;
                        int baseVertex = (int) (slice.vertexBufferOffset() / vertexFormat.getVertexSize());
                        var draws = drawGroups.get(layer).computeIfAbsent(combinedHash, _ -> new ArrayList<>());
                        draws.add(
                                new RenderPass.Draw<>(
                                        0,
                                        vertexBuffer,
                                        indexBuffer,
                                        indexType,
                                        firstIndex,
                                        draw.indexCount(),
                                        baseVertex,
                                        (sectionUbos, uploader) -> uploader.upload("ChunkSection", sectionUbos[finalUboIndex])
                                )
                        );
                    }
                }
            }
        }

        GpuBufferSlice[] chunkSectionInfos = RenderSystem.getDynamicUniforms()
                .writeChunkSections(sectionInfos.toArray(new DynamicUniforms.ChunkSectionInfo[0]));
        return new ChunkSectionsToRender(blockAtlas, drawGroups, largestIndexCount, chunkSectionInfos);
    }

    public SectionRenderDispatcher.@Nullable RenderSectionBufferSlice getRenderSectionSlice(final SectionMesh sectionMesh, final ChunkSectionLayer layer) {
        var uberBuffers = this.chunkUberBuffers.get(layer);
        TlsfAllocator.Allocation vertexSlice = uberBuffers.vertexBuffer.getAllocation(sectionMesh);
        if (vertexSlice == null) {
            return null;
        } else {
            long vertexBufferOffset = vertexSlice.getOffsetFromHeap();
            TlsfAllocator.Allocation indexSlice = uberBuffers.indexBuffer != null ? uberBuffers.indexBuffer.getAllocation(sectionMesh) : null;
            long indexBufferOffset = 0L;
            GpuBuffer indexBuffer = null;
            if (indexSlice != null) {
                indexBufferOffset = indexSlice.getOffsetFromHeap();
                indexBuffer = uberBuffers.indexBuffer.getGpuBuffer(indexSlice);
            }

            return new SectionRenderDispatcher.RenderSectionBufferSlice(uberBuffers.vertexBuffer.getGpuBuffer(vertexSlice), vertexBufferOffset, indexBuffer, indexBufferOffset);
        }
    }

    public void setIgnoreLighting(boolean ignoreLighting) {
        if (this.region.ignoreLighting() != ignoreLighting) {
            this.region.setIgnoreLighting(ignoreLighting);
            this.sectionsDirty = true;
            this.statesDirty = true;
        }
    }

    public boolean ignoreLighting() {
        return this.region.ignoreLighting();
    }

    public LevelRenderState levelRenderState() {
        return levelRenderState;
    }

    public LevelTargetBundle levelTargetBundle() {
        return ((LevelRendererAccessor) this.minecraft.levelRenderer).sim_getTargets();
    }

    public record Section(BlockPos origin, CompiledSectionMesh sectionMesh) {
    }

    record StateBackup(Component nameTag, int lightCoords) {
    }

    private record SectionUberBuffers(UberGpuBuffer<SectionMesh> vertexBuffer,
                                      @Nullable UberGpuBuffer<SectionMesh> indexBuffer) {
    }
}
