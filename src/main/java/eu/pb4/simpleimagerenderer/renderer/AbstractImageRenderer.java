package eu.pb4.simpleimagerenderer.renderer;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import eu.pb4.simpleimagerenderer.mixin.GameRendererAccessor;
import eu.pb4.simpleimagerenderer.util.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;

import java.util.OptionalInt;
import java.util.function.BiConsumer;

public abstract class AbstractImageRenderer<T> implements AutoCloseable {
    protected final Minecraft minecraft;
    protected final RenderBuffers renderBuffers;
    protected final SubmitNodeStorage submitNodeStorage;
    protected final FeatureRenderDispatcher featureRenderDispatcher;
    protected final ProjectionMatrixBuffer perspectiveBuffer;
    protected final Matrix4f projectionMatrix = new Matrix4f();
    protected final Matrix4f matrix = new Matrix4f();
    protected final Quaternionf cameraOrientation = new Quaternionf();
    protected final CrossFrameResourcePool resourcePool = new CrossFrameResourcePool(3);

    protected RenderTarget renderTarget;
    protected int height;
    protected int width;
    protected LightingType lightingType = LightingType.DEFAULT;
    protected long glintTime = 0;
    protected LightmapType lightmapType = LightmapType.DEFAULT;
    protected boolean useUiLightmapByDefault = true;
    protected boolean multiplyNormals = true;
    private ProjectionType projectionType = ProjectionType.ORTHOGRAPHIC;

    public AbstractImageRenderer(Minecraft minecraft, int width, int height) {
        this.minecraft = minecraft;
        this.featureRenderDispatcher = minecraft.gameRenderer.featureRenderDispatcher();
        this.submitNodeStorage = new SubmitNodeStorage();
        this.renderBuffers = minecraft.gameRenderer.renderBuffers();
        this.perspectiveBuffer = new ProjectionMatrixBuffer("render");
        this.setupTexture(width, height);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean multiplyNormals() {
        return multiplyNormals;
    }

    public void setMultiplyNormals(boolean multiplyNormals) {
        this.multiplyNormals = multiplyNormals;
    }

    public void render(RenderConsumer<T> targetConsumer, boolean preview) {
        RenderUtils.glintTimeOverride = this.glintTime < 0 ? -1 : this.glintTime;
        var oldOutputColor = RenderSystem.outputColorTextureOverride;
        var oldOutputDepth = RenderSystem.outputDepthTextureOverride;
        var oldModelViewMatrix = RenderSystem.getModelViewMatrixCopy();
        RenderSystem.getModelViewStack().identity();
        RenderSystem.outputColorTextureOverride = renderTarget.getColorTextureView();
        RenderSystem.outputDepthTextureOverride = renderTarget.getDepthTextureView();
        RenderSystem.disableScissorForRenderTypeDraws();
        RenderSystem.backupProjectionMatrix();
        // Technically not correct, but this fixes issues with z-ordering of entity shadows and alike.
        RenderSystem.setProjectionMatrix(this.perspectiveBuffer.getBuffer(this.projectionMatrix), ProjectionType.ORTHOGRAPHIC);

        var oldLightmap = ((GameRendererAccessor) this.minecraft.gameRenderer).isUseUiLightmap();
        ((GameRendererAccessor) this.minecraft.gameRenderer).setUseUiLightmap(this.lightmapType.useUiLightmap(this.useUiLightmapByDefault));

        try {
            this.clearBuffer();
            this.renderInner(targetConsumer, preview);
        } catch (Throwable e) {
            e.printStackTrace();
        }

        ((GameRendererAccessor) this.minecraft.gameRenderer).setUseUiLightmap(oldLightmap);

        RenderSystem.restoreProjectionMatrix();

        RenderSystem.getModelViewStack().set(oldModelViewMatrix);
        RenderSystem.outputColorTextureOverride = oldOutputColor;
        RenderSystem.outputDepthTextureOverride = oldOutputDepth;
        RenderUtils.glintTimeOverride = -1;
    }

    protected void clearBuffer() {
        clearBuffer(this.renderTarget);
    }

    protected void clearBuffer(RenderTarget target) {
        var depth = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 0 : -1;
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), new Vector4f(0), target.getDepthTexture(), depth);
    }

    protected void solidifyBuffer(RenderTarget target) {
       var postChain = this.minecraft.getShaderManager().getPostChain(
               Identifier.fromNamespaceAndPath("simpleimagerenderer", "solidify"),
               LevelTargetBundle.MAIN_TARGETS
       );
        if (postChain != null) {
            postChain.process(target, this.resourcePool);
        }
    }

    protected abstract void renderInner(RenderConsumer<T> targetConsumer, boolean preview);


    public void setupTexture(int width) {
        setupTexture(width, width);
    }

    public void setupTexture(int width, int height) {
        if (this.renderTarget != null) {
            this.renderTarget.destroyBuffers();
        }
        this.width = width;
        this.height = height;

        this.renderTarget = new TextureTarget("image_out", width, height, true, GpuFormat.RGBA8_UNORM);
        this.updateProjectionMatrix();
    }

    public ProjectionType projectionType() {
        return projectionType;
    }

    public void setProjectionType(ProjectionType projectionType) {
        this.projectionType = projectionType;
        this.updateProjectionMatrix();
    }

    private void updateProjectionMatrix() {
        var zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();

        if (this.projectionType == ProjectionType.ORTHOGRAPHIC) {
            this.projectionMatrix.identity().setOrtho(-width / 2f, width / 2f, height / 2f, -height / 2f, 5000.0F, -5000.0F, zeroToOne);
        } else {
            this.projectionMatrix.identity().perspective(
                    90 * (float) (Math.PI / 180.0), 1, 50000, 0.05F, zeroToOne
                    );
        }
    }

    @Override
    public void close() {
        this.perspectiveBuffer.close();
        this.renderTarget.destroyBuffers();
        this.resourcePool.close();
    }

    public void updateMatrix(Matrix4f matrix4f, Quaternionf cameraOrientation) {
        this.matrix.set(matrix4f);
        this.cameraOrientation.set(cameraOrientation);
    }

    protected void multiplyPoseStack(PoseStack poseStack) {
        if (this.multiplyNormals) {
            poseStack.mulPose(this.matrix);
        } else {
            poseStack.last().pose().mul(this.matrix);
        }

        if (this.projectionType == ProjectionType.PERSPECTIVE) {
            poseStack.scale(1, -1, 1);
        }
    }

    public boolean isSingleRender() {
        return true;
    }

    public LightingType lightingType() {
        return lightingType;
    }

    public void setLightingType(LightingType lightingType) {
        this.lightingType = lightingType;
    }

    public LightmapType lightmapType() {
        return lightmapType;
    }

    public void setLightmapType(LightmapType lightmapType) {
        this.lightmapType = lightmapType;
    }

    public long glintTime() {
        return glintTime;
    }

    public void setGlintTime(long glintTime) {
        this.glintTime = glintTime;
    }

    public abstract Component getTitle();

    public Minecraft getMinecraft() {
        return this.minecraft;
    }

    public RenderBuffers renderBuffers() {
        return this.renderBuffers;
    }


    public enum LightingType {
        DEFAULT(null),
        LEVEL(Lighting.Entry.LEVEL),
        ITEMS_FLAT(Lighting.Entry.ITEMS_FLAT),
        ITEMS_3D(Lighting.Entry.ITEMS_3D),
        ENTITY_IN_UI(Lighting.Entry.ENTITY_IN_UI);

        private final Lighting.Entry entry;

        LightingType(Lighting.Entry entry) {
            this.entry = entry;
        }

        public Lighting.Entry getEntry(Lighting.Entry defaulted) {
            return this.entry == null ? defaulted : this.entry;
        }
    }

    public enum LightmapType {
        DEFAULT,
        LEVEL,
        UI;

        public boolean useUiLightmap(boolean defaultValue) {
            return switch (this) {
                case DEFAULT -> defaultValue;
                case UI -> true;
                case LEVEL -> false;
            };
        }
    }

    public interface RenderConsumer<T> {
        void rendered(RenderTarget target, T object, int frame);
    }
}
