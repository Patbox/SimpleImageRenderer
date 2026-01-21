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
import eu.pb4.simpleimagerenderer.mixin.LevelRendererAccessor;
import eu.pb4.simpleimagerenderer.util.BoxyFrustum;
import eu.pb4.simpleimagerenderer.util.ManualCamera;
import eu.pb4.simpleimagerenderer.util.RenderUtils;
import eu.pb4.simpleimagerenderer.util.renderregion.AreaRenderSectionRegion;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
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

import java.util.*;
import java.util.function.BiConsumer;


public class RegionImageRenderer extends AbstractImageRenderer<Void> {
    private final List<Section> sections = new ArrayList<>();
    private final LevelRenderState levelRenderState = new LevelRenderState();
    private final CrossFrameResourcePool resourcePool = new CrossFrameResourcePool(3);
    private final ParticlesRenderState particlesRenderState = new ParticlesRenderState();

    private final boolean entityOutlines;

    private final GpuSampler chunkLayerSampler;
    private final BlockBox area;

    private boolean renderEntities = true;
    private boolean renderNametags = true;
    private boolean renderSelf = true;
    private boolean renderParticles = true;

    private TextureTarget entityOutlineTarget;

    public RegionImageRenderer(Minecraft minecraft, int width, int height, ClientLevel level, BlockBox area) {
        super(minecraft, width, height);
        this.area = area;

        {
            int i = this.minecraft.options.textureFiltering().get() == TextureFilteringMethod.ANISOTROPIC ? this.minecraft.options.maxAnisotropyValue() : 1;
            this.chunkLayerSampler = RenderSystem.getDevice()
                    .createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, i, OptionalDouble.empty());
        }

        var region = new AreaRenderSectionRegion(level, area);
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
        region = null;

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

        var outline = false;
        for (var entity : level.getEntities(null, area.aabb().inflate(0.01f))) {
            var state = minecraft.getEntityRenderDispatcher().extractEntity(entity, 0);
            outline |= state.appearsGlowing();
            this.levelRenderState.entityRenderStates.add(state);
        }
        this.levelRenderState.haveGlowingEntities = outline;
        if (this.levelRenderState.haveGlowingEntities) {
            this.entityOutlineTarget = new TextureTarget("Entity outline Renderer", this.renderTarget.width, this.renderTarget.height, true);
        }
        this.entityOutlines = levelRenderState.haveGlowingEntities;

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

    public boolean renderParticles() {
        return renderParticles;
    }

    public void setRenderParticles(boolean renderParticles) {
        this.renderParticles = renderParticles;
    }

    public void setRenderNametags(boolean renderNametags) {
        this.renderNametags = renderNametags;
    }

    @Override
    protected void renderInner(BiConsumer<TextureTarget, Void> targetConsumer, boolean preview) {
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
                        this.minecraft.options.textureFiltering().get() == TextureFilteringMethod.RGSS
                );

        poseStack.scale(width, -width, width);
        var scale = 1f / ((area.sizeX() + area.sizeY() + area.sizeZ()) / 3f);
        poseStack.scale(scale, scale, scale);

        var targets = ((LevelRendererAccessor) this.minecraft.levelRenderer).sim_getTargets();
        minecraft.gameRenderer.getLighting().setupFor(this.lightingType.getEntry(Lighting.Entry.LEVEL));
        RenderUtils.mainRenderTargetReplacement = this.renderTarget;
        FrameGraphBuilder frameGraphBuilder = new FrameGraphBuilder();

        var oldTargetMain = targets.main;
        var oldTargetTranslucent = targets.translucent;
        var oldTargetItemEntity = targets.itemEntity;
        var oldTargetParticles = targets.particles;
        var oldTargetWeather = targets.weather;
        var oldTargetClouds = targets.clouds;
        var oldEntityOutline = targets.entityOutline;

        targets.clear();

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

        this.addMainPass(targets, frameGraphBuilder, poseStack, RenderSystem.getShaderFog(),
                false, this.levelRenderState);

        PostChain outlinePostChain = this.minecraft.getShaderManager().getPostChain(LevelRendererAccessor.getENTITY_OUTLINE_POST_CHAIN_ID(), LevelTargetBundle.OUTLINE_TARGETS);
        if (this.levelRenderState.haveGlowingEntities && outlinePostChain != null) {
            outlinePostChain.addToFrame(frameGraphBuilder, this.renderTarget.width, this.renderTarget.height, targets);
        }

        if (this.renderParticles) {
            this.minecraft.particleEngine.extract(this.particlesRenderState, frustum, camera, deltaTracker.getGameTimeDeltaTicks());
            this.addParticlesPass(targets, poseStack.last().pose(), frameGraphBuilder, RenderSystem.getShaderFog());
        }
        frameGraphBuilder.execute(this.resourcePool);

        if (this.levelRenderState.haveGlowingEntities) {
            this.entityOutlineTarget.blitAndBlendToTexture(this.renderTarget.getColorTextureView());
        }
        RenderUtils.mainRenderTargetReplacement = null;

        targetConsumer.accept(this.renderTarget, null);
        targets.clear();
// Todo: Refactor this all to match latest changes in LevelRenderer
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
                    var uiLightmap = this.lightmapType.useUiLightmap(this.useUiLightmapByDefault);

                    RenderSystem.setShaderFog(fog);
                    var chunkSections = this.prepareChunkRenders(sourcePoseStack.last().pose());
                    chunkSections.renderGroup(ChunkSectionLayerGroup.OPAQUE, this.chunkLayerSampler);
                    //this.minecraft.gameRenderer.getLighting().setupFor(Lighting.Entry.LEVEL);
                    if (itemEntityHandle != null) {
                        itemEntityHandle.get().copyDepthFrom(this.minecraft.getMainRenderTarget());
                    }

                    if (this.levelRenderState.haveGlowingEntities && entityOutlineHandle != null) {
                        RenderTarget renderTarget = entityOutlineHandle.get();
                        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(renderTarget.getColorTexture(), 0, renderTarget.getDepthTexture(), 1.0);
                    }

                    PoseStack poseStack = new PoseStack();
                    poseStack.last().pose().set(sourcePoseStack.last().pose());
                    //poseStack.last().normal().set(sourcePoseStack.last().normal());

                    MultiBufferSource.BufferSource bufferSource = this.renderBuffers.bufferSource();
                    MultiBufferSource.BufferSource bufferSource2 = this.renderBuffers.crumblingBufferSource();

                    if (this.renderEntities) {

                        AvatarRenderState selfState = null;
                        List<Component> nameTags = this.renderNametags ? null : new ArrayList<>(levelRenderState.entityRenderStates.size());
                        IntList lights = uiLightmap ? new IntArrayList(levelRenderState.entityRenderStates.size()) : null;

                        if (!this.renderSelf || !this.renderNametags || uiLightmap) {
                            for (int i = 0; i < levelRenderState.entityRenderStates.size(); i++) {
                                var state = levelRenderState.entityRenderStates.get(i);
                                if (!this.renderSelf && state instanceof AvatarRenderState avatarRenderState && avatarRenderState.id == this.minecraft.player.getId()) {
                                    selfState = avatarRenderState;
                                    levelRenderState.entityRenderStates.remove(i);
                                    if (this.renderNametags && !uiLightmap) {
                                        break;
                                    } else {
                                        continue;
                                    }
                                }
                                if (!this.renderNametags) {
                                    assert nameTags != null;
                                    nameTags.add(state.nameTag);
                                    state.nameTag = null;
                                }
                                if (uiLightmap) {
                                    lights.add(state.lightCoords);
                                    state.lightCoords = 0;
                                }
                            }
                        }

                        ((LevelRendererAccessor) this.minecraft.levelRenderer).sim_submitEntities(poseStack, levelRenderState, this.submitNodeStorage);

                        if (nameTags != null) {
                            for (int i = 0; i < levelRenderState.entityRenderStates.size(); i++) {
                                levelRenderState.entityRenderStates.get(i).nameTag = nameTags.get(i);
                            }
                        }

                        if (lights != null) {
                            for (int i = 0; i < levelRenderState.entityRenderStates.size(); i++) {
                                levelRenderState.entityRenderStates.get(i).lightCoords = lights.getInt(i);
                            }
                        }

                        if (selfState != null) {
                            levelRenderState.entityRenderStates.add(selfState);
                        }

                    }

                    {
                        IntList lights = uiLightmap ? new IntArrayList(levelRenderState.blockEntityRenderStates.size()) : null;
                        if (uiLightmap) {
                            for (var state : levelRenderState.blockEntityRenderStates) {
                                lights.add(state.lightCoords);
                                state.lightCoords = 0;
                            }
                        }
                        ((LevelRendererAccessor) this.minecraft.levelRenderer).sim_submitBlockEntities(poseStack, levelRenderState, this.submitNodeStorage);

                        if (lights != null) {
                            for (int i = 0; i < levelRenderState.blockEntityRenderStates.size(); i++) {
                                levelRenderState.blockEntityRenderStates.get(i).lightCoords = lights.getInt(i);
                            }
                        }
                    }
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
            var oldMatrix = new Matrix4f(RenderSystem.getModelViewMatrix());
            RenderSystem.getModelViewMatrix().set(pose);
            this.particlesRenderState.submit(this.submitNodeStorage, this.levelRenderState.cameraRenderState);
            this.featureRenderDispatcher.renderAllFeatures();
            this.particlesRenderState.reset();
            RenderSystem.getModelViewMatrix().set(oldMatrix);
        });
    }

    private void checkPoseStack(PoseStack poseStack) {
        if (!poseStack.isEmpty()) {
            throw new IllegalStateException("Pose stack not empty");
        }
    }


    @Override
    public void updateMatrix(Matrix4f matrix4f, Quaternionf quaternionf) {
        super.updateMatrix(matrix4f, quaternionf);

        var camPos = new Vector4f(0, 0, -1, 0);
        camPos.mul(new Matrix4f(projectionMatrix).invert()).mul(new Matrix4f(matrix4f).invert());

        var vec = new Vector3f(camPos.x, camPos.y, camPos.z).normalize().mul(1000).mul(1, -1, 1).add(this.area.aabb().getCenter().toVector3f());
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
}
