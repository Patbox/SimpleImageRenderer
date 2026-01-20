package eu.pb4.simpleimagerenderer.renderer;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.BiConsumer;

public class ItemImageRenderer extends AbstractImageRenderer<ItemStack> {
    private final List<ItemStack> stacks;
    private ItemDisplayContext displayContext = ItemDisplayContext.GUI;

    public ItemImageRenderer(Minecraft minecraft, int width, int height, List<ItemStack> stacks) {
        super(minecraft, width, height);
        this.stacks = stacks.isEmpty() ? List.of(ItemStack.EMPTY) : stacks;
    }

    @Override
    public boolean isSingleRender() {
        return this.stacks.size() < 2;
    }

    @Override
    public Component getTitle() {
        return this.stacks.size() == 1 ? this.stacks.getFirst().getDisplayName() : Component.translatable("text.simple_image_renderer.and_more", this.stacks.getFirst().getDisplayName(), this.stacks.size() - 1);
    }

    @Override
    protected void renderInner(BiConsumer<TextureTarget, ItemStack> targetConsumer, boolean preview) {
        var poseStack = new PoseStack();
        poseStack.pushPose();
        this.multiplyPoseStack(poseStack);
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
        minecraft.getItemModelResolver().updateForTopItem(state, stack, this.displayContext, null, null, 0);
        minecraft.gameRenderer.getLighting().setupFor(this.lightingType.getEntry(state.usesBlockLight() ? Lighting.Entry.ITEMS_3D : Lighting.Entry.ITEMS_FLAT));
        //minecraft.options.glintSpeed()
        state.submit(poseStack, this.featureRenderDispatcher.getSubmitNodeStorage(), 0, OverlayTexture.NO_OVERLAY, 0);

        this.featureRenderDispatcher.renderAllFeatures();
        this.featureRenderDispatcher.endFrame();
        bufferSource.endBatch();
    }

    public ItemDisplayContext displayContext() {
        return displayContext;
    }

    public void setDisplayContext(ItemDisplayContext displayContext) {
        this.displayContext = displayContext;
    }
}
