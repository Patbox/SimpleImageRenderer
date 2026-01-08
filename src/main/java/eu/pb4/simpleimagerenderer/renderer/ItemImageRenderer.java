package eu.pb4.simpleimagerenderer.renderer;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.BiConsumer;

public class ItemImageRenderer extends AbstractImageRenderer<ItemStack> {
    private final List<ItemStack> stacks;

    public ItemImageRenderer(Minecraft minecraft, int width, List<ItemStack> stacks) {
        super(minecraft, width, width);
        this.stacks = stacks;
    }

    @Override
    protected void renderInner(BiConsumer<TextureTarget, ItemStack> targetConsumer, boolean preview) {
        var poseStack = new PoseStack();
        poseStack.pushPose();
        poseStack.translate(width / 2.0, width / 2.0, 0);
        poseStack.scale(width, -width, width);
        if (preview) {
            var stack = this.stacks.get((int) ((System.currentTimeMillis() / 500) % this.stacks.size()));
            renderSingleItem(poseStack, stack);
            targetConsumer.accept(this.renderTarget, stack);
            return;
        }
        for (var stack : stacks) {
            poseStack.pushPose();
            this.clearBuffer();
            renderSingleItem(poseStack, stack);
            targetConsumer.accept(this.renderTarget, stack);
            poseStack.popPose();
        }
    }

    protected void renderSingleItem(PoseStack poseStack, ItemStack stack) {
        var state = new TrackingItemStackRenderState();
        minecraft.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.GUI, null, null, 0);
        minecraft.gameRenderer.getLighting().setupFor(state.usesBlockLight() ? Lighting.Entry.ITEMS_3D : Lighting.Entry.ITEMS_FLAT);

        state.submit(poseStack, this.renderDispatcher.getSubmitNodeStorage(), 15728880, OverlayTexture.NO_OVERLAY, 0);

        this.renderDispatcher.renderAllFeatures();
        this.renderDispatcher.endFrame();
        bufferSource.endBatch();
    }
}
