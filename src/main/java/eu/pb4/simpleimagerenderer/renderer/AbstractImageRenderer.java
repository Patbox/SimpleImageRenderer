package eu.pb4.simpleimagerenderer.renderer;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.joml.Matrix4f;

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


    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public AbstractImageRenderer(Minecraft minecraft, int width, int height) {
        this.minecraft = minecraft;
        this.renderDispatcher = minecraft.gameRenderer.getFeatureRenderDispatcher();
        this.bufferSource = minecraft.renderBuffers().bufferSource();
        this.perspectiveBuffer = new PerspectiveProjectionMatrixBuffer("render");

        this.setupTexture(width, height);
    }

    public void render(BiConsumer<TextureTarget, T> targetConsumer, boolean preview) {
        var oldOutputColor = RenderSystem.outputColorTextureOverride;
        var oldOutputDepth = RenderSystem.outputDepthTextureOverride;

        RenderSystem.outputColorTextureOverride = renderTarget.getColorTextureView();
        RenderSystem.outputDepthTextureOverride = renderTarget.getDepthTextureView();
        RenderSystem.setProjectionMatrix(this.perspectiveBuffer.getBuffer(this.projectionMatrix), ProjectionType.ORTHOGRAPHIC);
        try {
            this.clearBuffer();
            this.renderInner(targetConsumer, preview);
        } catch (Throwable e) {
            e.printStackTrace();
        }

        RenderSystem.outputColorTextureOverride = oldOutputColor;
        RenderSystem.outputDepthTextureOverride = oldOutputDepth;
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
        this.projectionMatrix.identity().setOrtho(0.0F, width, height, 0.0F, -5000.0F, 5000.0F);
    }

    @Override
    public void close() {
        this.perspectiveBuffer.close();
        this.renderTarget.destroyBuffers();
    }
}
