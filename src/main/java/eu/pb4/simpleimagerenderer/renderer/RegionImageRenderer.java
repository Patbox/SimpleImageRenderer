package eu.pb4.simpleimagerenderer.renderer;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import eu.pb4.simpleimagerenderer.mixin.GameRendererAccessor;
import eu.pb4.simpleimagerenderer.mixin.LevelRendererAccessor;
import eu.pb4.simpleimagerenderer.util.BoxyFrustum;
import eu.pb4.simpleimagerenderer.util.ManualCamera;
import eu.pb4.simpleimagerenderer.util.RenderUtils;
import eu.pb4.simpleimagerenderer.util.renderregion.AreaRenderSectionRegion;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.client.renderer.state.ParticlesRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.*;
import org.jspecify.annotations.Nullable;

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

    private boolean renderEntities = true;
    private boolean renderNametags = true;
    private boolean renderSelf = true;
    private boolean renderParticles = true;

    private TextureTarget entityOutlineTarget;
    private boolean sectionsDirty = false;
    private boolean statesDirty = false;


    public RegionImageRenderer(Minecraft minecraft, int width, int height, ClientLevel level, BlockBox area, boolean renderEdge, boolean ignoreLighting) {
        super(minecraft, width, height);
        this.area = area;

        {
            this.chunkLayerSampler = RenderSystem.getDevice()
                    .createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
        }

        this.region = new AreaRenderSectionRegion(level, area);
        this.region.setAllowExternalLookup(!renderEdge);
        this.region.setIgnoreLighting(ignoreLighting);
        this.rebuildSections();

        for (var section : this.sections) {
            for (var be : section.sectionMesh.getRenderableBlockEntities()) {
                var state = this.minecraft.getBlockEntityRenderDispatcher().tryExtractRenderState(be, 0, null);
                if (state != null) {
                    this.levelRenderState.blockEntityRenderStates.add(state);
                }
            }

            for (var be : level.getGloballyRenderedBlockEntities()) {
                if (area.contains(be.getBlockPos())) {
                    var state = this.minecraft.getBlockEntityRenderDispatcher().tryExtractRenderState(be, 0, null);
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
            this.levelRenderState.haveGlowingEntities |= state.appearsGlowing();
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

        if (this.levelRenderState.haveGlowingEntities) {
            this.entityOutlineTarget = new TextureTarget("Entity outline Renderer", this.renderTarget.width, this.renderTarget.height, true);
        }

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
            section.sectionMesh.close();
        }
        this.sections.clear();

        var sorting = VertexSorting.byDistance(this.getCameraPos());

        var compiler = new SectionCompiler(minecraft.getBlockRenderer(), minecraft.getBlockEntityRenderDispatcher());
        var sectionStart = SectionPos.of(area.min());
        var sectionEnd = SectionPos.of(area.max());
        var builder = minecraft.renderBuffers().fixedBufferPack();
        var iter = SectionPos.betweenClosedStream(sectionStart.x(), sectionStart.y(), sectionStart.z(), sectionEnd.x(), sectionEnd.y(), sectionEnd.z()).iterator();
        while (iter.hasNext()) {
            builder.discardAll();
            var pos = iter.next();
            var results = compiler.compile(pos, region, sorting, builder);
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
        builder.discardAll();
    }

    @Override
    public void close() {
        super.close();
        for (var section : this.sections) {
            section.sectionMesh.close();
        }
        this.sections.clear();
        if (this.entityOutlineTarget != null) {
            this.entityOutlineTarget.destroyBuffers();
        }
        this.resourcePool.close();
        this.chunkLayerSampler.close();
    }

    @Override
    public void setupTexture(int width, int height) {
        super.setupTexture(width, height);
        if (this.resourcePool != null) {
            this.resourcePool.clear();
        }
        if (this.entityOutlineTarget != null) {
            this.entityOutlineTarget.destroyBuffers();
            this.entityOutlineTarget = new TextureTarget("Entity outline Renderer", this.renderTarget.width, this.renderTarget.height, true);
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
        var cameraState = this.levelRenderState.cameraRenderState;
        cameraState.entityPos = center;
        cameraState.blockPos = BlockPos.containing(center);
        cameraState.pos = center;
        cameraState.orientation = this.cameraOrientation;
        cameraState.initialized = true;

        var camera = new ManualCamera();
        camera.setPosition(center);
        camera.setRotation(cameraState.orientation);
        var frustum = new BoxyFrustum(area);

        this.minecraft.gameRenderer.getGlobalSettingsUniform()
                .update(
                        this.renderTarget.width,
                        this.renderTarget.height,
                        this.minecraft.options.glintStrength().get(),
                        this.minecraft.level == null ? 0L : this.minecraft.level.getGameTime(),
                        deltaTracker,
                        this.minecraft.options.getMenuBackgroundBlurriness(),
                        camera,
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
        FrameGraphBuilder frameGraphBuilder = new FrameGraphBuilder();

        targets.main = frameGraphBuilder.importExternal("main", this.renderTarget);
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
            targets.entityOutline = frameGraphBuilder.importExternal("entity_outline", this.entityOutlineTarget);
        }

        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.mul(poseStack.last().pose());

        var fogRenderer = ((GameRendererAccessor) this.minecraft.gameRenderer).getFogRenderer();
        var worldFog = fogRenderer.getBuffer(FogRenderer.FogMode.NONE);
        this.addMainPass(targets, frameGraphBuilder, poseStack, worldFog, false, this.levelRenderState);

        PostChain outlinePostChain = this.minecraft.getShaderManager().getPostChain(LevelRendererAccessor.getENTITY_OUTLINE_POST_CHAIN_ID(), LevelTargetBundle.OUTLINE_TARGETS);
        if (this.levelRenderState.haveGlowingEntities && outlinePostChain != null) {
            outlinePostChain.addToFrame(frameGraphBuilder, this.renderTarget.width, this.renderTarget.height, targets);
        }

        if (this.renderParticles) {
            this.minecraft.particleEngine.extract(this.particlesRenderState, frustum, camera, deltaTracker.getGameTimeDeltaTicks());
            this.addParticlesPass(targets, poseStack.last().pose(), frameGraphBuilder, worldFog);
        }
        frameGraphBuilder.execute(this.resourcePool);

        if (this.levelRenderState.haveGlowingEntities) {
            this.entityOutlineTarget.blitAndBlendToTexture(this.renderTarget.getColorTextureView());
        }
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

        RenderSystem.setShaderFog(fogRenderer.getBuffer(FogRenderer.FogMode.NONE));
    }

    private void addMainPass(
            LevelTargetBundle targets,
            FrameGraphBuilder frameGraphBuilder,
            PoseStack sourcePoseStack,
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

        if (levelRenderState.haveGlowingEntities && targets.entityOutline != null) {
            targets.entityOutline = framePass.readsAndWrites(targets.entityOutline);
        }

        var mainHandle = targets.main;
        var translucentHandle = targets.translucent;
        var itemEntityHandle = targets.itemEntity;
        var entityOutlineHandle = targets.entityOutline;
        framePass.executes(
                () -> {
                    RenderSystem.setShaderFog(fog);
                    this.minecraft.gameRenderer.getLighting().setupFor(Lighting.Entry.LEVEL);

                    var chunkSections = this.prepareChunkRenders(sourcePoseStack.last().pose());
                    chunkSections.renderGroup(ChunkSectionLayerGroup.OPAQUE, this.chunkLayerSampler);
                    this.solidifyBuffer(this.renderTarget);

                    if (itemEntityHandle != null) {
                        itemEntityHandle.get().copyDepthFrom(this.minecraft.getMainRenderTarget());
                    }

                    if (this.levelRenderState.haveGlowingEntities && entityOutlineHandle != null) {
                        RenderTarget renderTarget = entityOutlineHandle.get();
                        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(renderTarget.getColorTexture(), 0, renderTarget.getDepthTexture(), 1.0);
                    }

                    PoseStack poseStack = new PoseStack();

                    MultiBufferSource.BufferSource bufferSource = this.renderBuffers.bufferSource();
                    MultiBufferSource.BufferSource bufferSource2 = this.renderBuffers.crumblingBufferSource();

                    if (this.renderEntities) {
                        ((LevelRendererAccessor) this.minecraft.levelRenderer).sim_submitEntities(poseStack, levelRenderState, this.submitNodeStorage);
                    }

                    ((LevelRendererAccessor) this.minecraft.levelRenderer).sim_submitBlockEntities(poseStack, levelRenderState, this.submitNodeStorage);
                    this.featureRenderDispatcher.renderAllFeatures();

                    bufferSource.endLastBatch();
                    this.checkPoseStack(poseStack);
                    bufferSource.endBatch(RenderTypes.solidMovingBlock());
                    bufferSource.endBatch(RenderTypes.endPortal());
                    bufferSource.endBatch(RenderTypes.endGateway());
                    bufferSource.endBatch(Sheets.solidBlockSheet());
                    bufferSource.endBatch(Sheets.cutoutBlockSheet());
                    bufferSource.endBatch(Sheets.bedSheet());
                    bufferSource.endBatch(Sheets.shulkerBoxSheet());
                    bufferSource.endBatch(Sheets.signSheet());
                    bufferSource.endBatch(Sheets.hangingSignSheet());
                    bufferSource.endBatch(Sheets.chestSheet());
                    this.renderBuffers.outlineBufferSource().endOutlineBatch();
                    if (renderOutlines) {
                        ((LevelRendererAccessor) this.minecraft.levelRenderer).sim_renderBlockOutline(bufferSource, poseStack, false, levelRenderState);
                    }

                    //this.finalizeGizmoCollection();
                    //this.finalizedGizmos.standardPrimitives().render(poseStack, bufferSource, levelRenderState.cameraRenderState, matrix);
                    bufferSource.endLastBatch();
                    this.checkPoseStack(poseStack);
                    bufferSource.endBatch(Sheets.translucentItemSheet());
                    bufferSource.endBatch(Sheets.bannerSheet());
                    bufferSource.endBatch(Sheets.shieldSheet());
                    bufferSource.endBatch(RenderTypes.armorEntityGlint());
                    bufferSource.endBatch(RenderTypes.glint());
                    bufferSource.endBatch(RenderTypes.glintTranslucent());
                    bufferSource.endBatch(RenderTypes.entityGlint());
                    ((LevelRendererAccessor) this.minecraft.levelRenderer).sim_renderBlockDestroyAnimation(poseStack, bufferSource2, levelRenderState);
                    bufferSource2.endBatch();
                    this.checkPoseStack(poseStack);
                    bufferSource.endBatch(RenderTypes.waterMask());
                    bufferSource.endBatch();
                    if (translucentHandle != null) {
                        translucentHandle.get().copyDepthFrom(mainHandle.get());
                    }

                    chunkSections.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, this.chunkLayerSampler);
                    chunkSections.renderGroup(ChunkSectionLayerGroup.TRIPWIRE, this.chunkLayerSampler);

                    bufferSource.endBatch();
                }
        );
    }

    private void addParticlesPass(LevelTargetBundle targets, Matrix4fc pose, FrameGraphBuilder frameGraphBuilder, GpuBufferSlice gpuBufferSlice) {
        FramePass framePass = frameGraphBuilder.addPass("particles");
        if (targets.particles != null) {
            targets.particles = framePass.readsAndWrites(targets.particles);
            framePass.reads(targets.main);
        } else {
            targets.main = framePass.readsAndWrites(targets.main);
        }

        ResourceHandle<RenderTarget> resourceHandle = targets.main;
        ResourceHandle<RenderTarget> resourceHandle2 = targets.particles;
        framePass.executes(() -> {
            RenderSystem.setShaderFog(gpuBufferSlice);
            if (resourceHandle2 != null) {
                resourceHandle2.get().copyDepthFrom(resourceHandle.get());
            }
            this.particlesRenderState.submit(this.submitNodeStorage, this.levelRenderState.cameraRenderState);
            this.featureRenderDispatcher.renderAllFeatures();
            this.particlesRenderState.reset();
        });
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
        var builder = minecraft.renderBuffers().fixedBufferPack();

        builder.discardAll();
        for (var section : this.sections) {
            var sectionId = SectionPos.asLong(section.origin);
            var pow = TranslucencyPointOfView.of(vec3, sectionId);

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

    record StateBackup(Component nameTag, int lightCoords) {}
}
