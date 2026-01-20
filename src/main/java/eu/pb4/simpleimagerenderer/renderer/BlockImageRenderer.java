package eu.pb4.simpleimagerenderer.renderer;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.BiConsumer;

public class BlockImageRenderer extends AbstractImageRenderer<BlockState> {
    private final BlockState state;

    public BlockImageRenderer(Minecraft minecraft, int width, int height, BlockState state) {
        super(minecraft, width, height);
        this.state = state;
    }

    @Override
    protected void renderInner(BiConsumer<TextureTarget, BlockState> targetConsumer, boolean preview) {
        var poseStack = new PoseStack();
        poseStack.pushPose();

        this.multiplyPoseStack(poseStack);
        poseStack.translate(-width / 2f, width / 2f, -width / 2f);

        poseStack.scale(width, -width, width);
        minecraft.gameRenderer.getLighting().setupFor(this.lightingType.getEntry(Lighting.Entry.ITEMS_3D));

        this.featureRenderDispatcher.getSubmitNodeStorage().submitBlock(poseStack, this.state, 0, OverlayTexture.NO_OVERLAY, 0);
        this.featureRenderDispatcher.renderAllFeatures();

        this.featureRenderDispatcher.endFrame();
        bufferSource.endBatch();

        targetConsumer.accept(this.renderTarget, this.state);
    }

    @Override
    public Component getTitle() {
        return this.state.getBlock().getName();
    }
}
