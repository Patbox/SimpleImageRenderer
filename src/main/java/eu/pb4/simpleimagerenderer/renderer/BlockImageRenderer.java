package eu.pb4.simpleimagerenderer.renderer;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import eu.pb4.simpleimagerenderer.util.renderregion.SingleBlockRenderRegion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

import java.util.function.BiConsumer;

public class BlockImageRenderer extends AbstractImageRenderer<BlockState> {
    private final BlockState state;
    private final SingleBlockRenderRegion pseudoRegion;
    private TextureTarget fluidTarget;

    public BlockImageRenderer(Minecraft minecraft, int width, int height, BlockState state) {
        super(minecraft, width, height);
        this.state = state;
        this.pseudoRegion = new SingleBlockRenderRegion(this.minecraft.level, state);
    }

    @Override
    public void setupTexture(int width, int height) {
        super.setupTexture(width, height);
    }

    @Override
    public void close() {
        super.close();
        if (this.fluidTarget != null) {
            this.fluidTarget.destroyBuffers();
        }
    }

    @Override
    protected void renderInner(RenderConsumer<BlockState> targetConsumer, boolean preview) {
        var poseStack = new PoseStack();
        poseStack.pushPose();

        this.multiplyPoseStack(poseStack);
        poseStack.translate(-width / 2f, width / 2f, -width / 2f);

        poseStack.scale(width, -width, width);
        var light = this.lightingType.getEntry(Lighting.Entry.LEVEL);
        minecraft.gameRenderer.getLighting().setupFor(light);
        if (light == Lighting.Entry.LEVEL) {
            poseStack.last().normal().scale(1, -1, 1);
        }

        this.featureRenderDispatcher.getSubmitNodeStorage().submitBlock(poseStack, this.state, 15728880, OverlayTexture.NO_OVERLAY, 0);
        this.featureRenderDispatcher.renderAllFeatures();
        this.featureRenderDispatcher.endFrame();
        bufferSource.endBatch();

        if (!this.state.getFluidState().isEmpty()) {
            var mat = new Matrix4f(RenderSystem.getModelViewMatrix());
            RenderSystem.getModelViewMatrix().set(poseStack.last().pose());

            var fluid = this.state.getFluidState();
            var buffer = this.bufferSource.getBuffer(RenderTypes.translucentMovingBlock());
            this.minecraft.getBlockRenderer().renderLiquid(BlockPos.ZERO, this.pseudoRegion, buffer, this.state, fluid);
            bufferSource.endBatch();

            RenderSystem.getModelViewMatrix().set(mat);
        }


        targetConsumer.rendered(this.renderTarget, this.state, -1);
    }

    @Override
    public Component getTitle() {
        return this.state.getBlock().getName();
    }
}
