package eu.pb4.simpleimagerenderer.renderer;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;

import java.util.function.BiConsumer;

public class EntityImageRenderer extends AbstractImageRenderer<Entity> {
    private final Entity entity;

    public EntityImageRenderer(Minecraft minecraft, int width, int height, Entity entity) {
        super(minecraft, width, height);
        this.entity = entity;
    }

    @Override
    protected void renderInner(BiConsumer<TextureTarget, Entity> targetConsumer, boolean preview) {
        minecraft.gameRenderer.getLighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
        var poseStack = new PoseStack();
        poseStack.pushPose();
        poseStack.translate(width / 2.0, width / 1.1f, 0);
        poseStack.scale(width, -width, width);
        var maxDim = 1 / (Math.max(entity.getBbHeight(), entity.getBbWidth()) + 0.5f);
        poseStack.scale(maxDim, maxDim, maxDim);

        var state = minecraft.getEntityRenderDispatcher().extractEntity(entity, 0);
        //state.lightCoords = 16;
        if (state instanceof LivingEntityRenderState livingEntityRenderState) {
            livingEntityRenderState.bodyRot = 0;
            livingEntityRenderState.yRot = 0;
            livingEntityRenderState.xRot = 0;
        }

        minecraft.getEntityRenderDispatcher().submit(state, new CameraRenderState(), 0, 0, 0, poseStack, this.renderDispatcher.getSubmitNodeStorage());

        this.renderDispatcher.renderAllFeatures();
        this.renderDispatcher.endFrame();
        this.bufferSource.endBatch();

        targetConsumer.accept(this.renderTarget, this.entity);
        poseStack.popPose();
    }
}
