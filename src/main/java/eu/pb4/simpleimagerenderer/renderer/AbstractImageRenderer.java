package eu.pb4.simpleimagerenderer.renderer;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.terraformersmc.modmenu.util.mod.Mod;
import eu.pb4.simpleimagerenderer.ModInit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.function.BiConsumer;

public abstract class AbstractImageRenderer<T> implements AutoCloseable {
    protected final Minecraft minecraft;
    protected TextureTarget renderTarget;
    protected final FeatureRenderDispatcher renderDispatcher;
    protected final MultiBufferSource.BufferSource bufferSource;
    protected final PerspectiveProjectionMatrixBuffer perspectiveBuffer;
    protected final Matrix4f projectionMatrix = new Matrix4f();
    protected int height;
    protected int width;
    protected final Matrix4f matrix = new Matrix4f();
    protected final Quaternionf cameraOrientation = new Quaternionf();
    private boolean multiplyNormals = true;
    protected LightingType lightingType = LightingType.DEFAULT;
    protected long glintTime = 0;

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

    public AbstractImageRenderer(Minecraft minecraft, int width, int height) {
        this.minecraft = minecraft;
        this.renderDispatcher = minecraft.gameRenderer.getFeatureRenderDispatcher();
        this.bufferSource = minecraft.renderBuffers().bufferSource();
        this.perspectiveBuffer = new PerspectiveProjectionMatrixBuffer("render");

        this.setupTexture(width, height);
    }

    public void render(BiConsumer<TextureTarget, T> targetConsumer, boolean preview) {
        ModInit.glintTimeOverride = this.glintTime < 0 ? -1 : this.glintTime;
        var oldOutputColor = RenderSystem.outputColorTextureOverride;
        var oldOutputDepth = RenderSystem.outputDepthTextureOverride;
        var oldModelViewMatrix = new Matrix4f(RenderSystem.getModelViewMatrix());
        RenderSystem.getModelViewMatrix().identity();
        RenderSystem.outputColorTextureOverride = renderTarget.getColorTextureView();
        RenderSystem.outputDepthTextureOverride = renderTarget.getDepthTextureView();
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(this.perspectiveBuffer.getBuffer(this.projectionMatrix), ProjectionType.ORTHOGRAPHIC);
        try {
            this.clearBuffer();
            this.renderInner(targetConsumer, preview);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        RenderSystem.restoreProjectionMatrix();

        RenderSystem.getModelViewMatrix().set(oldModelViewMatrix);
        RenderSystem.outputColorTextureOverride = oldOutputColor;
        RenderSystem.outputDepthTextureOverride = oldOutputDepth;
        ModInit.glintTimeOverride = -1;
    }

    protected void clearBuffer() {
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(renderTarget.getColorTexture(), 0, renderTarget.getDepthTexture(), 1);
    }

    protected abstract void renderInner(BiConsumer<TextureTarget, T> targetConsumer, boolean preview);


    public void setupTexture(int width) {
        setupTexture(width, width);
    }
    public void setupTexture(int width, int height) {
        if (this.renderTarget != null) {
            this.renderTarget.destroyBuffers();
        }
        this.width = width;
        this.height = height;

        this.renderTarget = new TextureTarget("image_out", width, height, true);
        this.projectionMatrix.identity().setOrtho(-width / 2f, width / 2f, height / 2f, -height / 2f, -5000.0F, 5000.0F);
        /*this.projectionMatrix.identity().perspective(
                90 * (float) (Math.PI / 180.0), 1, 0.05F, 50000
        );*/
    }

    @Override
    public void close() {
        this.perspectiveBuffer.close();
        this.renderTarget.destroyBuffers();
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

    public long glintTime() {
        return glintTime;
    }

    public void setGlintTime(long glintTime) {
        this.glintTime = glintTime;
    }

    public abstract Component getTitle();

    public enum LightingType {
        DEFAULT(null),
        LEVEL(Lighting.Entry.LEVEL),
        ITEMS_FLAT(Lighting.Entry.ITEMS_FLAT),
        ITEMS_3D(Lighting.Entry.ITEMS_3D),
        ENTITY_IN_UI(Lighting.Entry.ENTITY_IN_UI);

        private final Lighting.Entry entry;

        private LightingType(Lighting.Entry entry) {
            this.entry = entry;
        }

        public Lighting.Entry getEntry(Lighting.Entry defaulted) {
            return this.entry == null ? defaulted : this.entry;
        }
    }

    /*public interface RenderConsumer<T> {
        void rendered(RenderTarget target, T object, int frame);
    }*/
}
