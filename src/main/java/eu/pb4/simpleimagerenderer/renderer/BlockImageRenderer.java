package eu.pb4.simpleimagerenderer.renderer;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
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

        this.renderDispatcher.getSubmitNodeStorage().submitBlock(poseStack,  this.state, 15728880, OverlayTexture.NO_OVERLAY,  0);

        this.renderDispatcher.renderAllFeatures();
        this.renderDispatcher.endFrame();
        bufferSource.endBatch();

        targetConsumer.accept(this.renderTarget, this.state);
    }

    @Override
    public Component getTitle() {
        return this.state.getBlock().getName();
    }


    private final List<EntityRenderState> entities = new ArrayList<>();
}
