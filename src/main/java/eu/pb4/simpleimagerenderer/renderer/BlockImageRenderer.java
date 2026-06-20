package eu.pb4.simpleimagerenderer.renderer;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import eu.pb4.simpleimagerenderer.util.renderregion.SingleBlockRenderRegion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
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
    private final BlockModelRenderState blockModelRenderState;
    private final FluidRenderer fluidRenderer;
    private TextureTarget fluidTarget;

    public BlockImageRenderer(Minecraft minecraft, int width, int height, BlockState state) {
        super(minecraft, width, height);
        this.state = state;
        this.pseudoRegion = new SingleBlockRenderRegion(this.minecraft.level, state);
        var blockModelResolver = new BlockModelResolver(minecraft.getModelManager());

        this.blockModelRenderState = new BlockModelRenderState();
        blockModelResolver.update(this.blockModelRenderState, state, BlockDisplayContext.create());
        this.fluidRenderer = new FluidRenderer(this.minecraft.getModelManager().getFluidStateModelSet());
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
        minecraft.gameRenderer.lighting().setupFor(light);
        if (light == Lighting.Entry.LEVEL) {
            poseStack.last().normal().scale(1, -1, 1);
        }


        this.blockModelRenderState.submit(poseStack, this.submitNodeStorage, 0, OverlayTexture.NO_OVERLAY, 0);
        this.featureRenderDispatcher.renderAllFeatures(this.submitNodeStorage);

        if (!this.state.getFluidState().isEmpty()) {
            var fluid = this.state.getFluidState();

            this.submitNodeStorage.submitCustomGeometry(poseStack, RenderTypes.translucentMovingBlock(), (pose, buffer) -> {

                this.fluidRenderer.tesselate(this.pseudoRegion, BlockPos.ZERO, x -> buffer, this.state, fluid);
            });

            RenderSystem.getModelViewStack().pushMatrix();
            RenderSystem.getModelViewStack().set(poseStack.last().pose());
            this.featureRenderDispatcher.renderAllFeatures(this.submitNodeStorage);
            RenderSystem.getModelViewStack().popMatrix();
        }


        targetConsumer.rendered(this.renderTarget, this.state, -1);
    }

    @Override
    public Component getTitle() {
        return this.state.getBlock().getName();
    }
}
