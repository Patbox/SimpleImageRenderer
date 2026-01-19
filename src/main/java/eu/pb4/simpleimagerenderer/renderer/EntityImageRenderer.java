package eu.pb4.simpleimagerenderer.renderer;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.function.BiConsumer;

public class EntityImageRenderer extends AbstractImageRenderer<Entity> {
    private final Entity entity;
    private float age = 0;
    private float walkAnimationPos = 0;
    private float walkAnimationSpeed = 0;

    public EntityImageRenderer(Minecraft minecraft, int width, int height, Entity entity) {
        super(minecraft, width, height);
        this.entity = entity;
    }

    @Override
    protected void renderInner(BiConsumer<TextureTarget, Entity> targetConsumer, boolean preview) {
        minecraft.gameRenderer.getLighting().setupFor(this.lightingType.getEntry(Lighting.Entry.ENTITY_IN_UI));
        var state = minecraft.getEntityRenderDispatcher().extractEntity(entity, 0);

        var poseStack = new PoseStack();
        poseStack.pushPose();
        this.multiplyPoseStack(poseStack);
        poseStack.translate(0, width / 1.1f - width / 2f, 0);

        poseStack.scale(width, -width, width);
        var maxDim = 1 / (Math.max(state.boundingBoxHeight, state.boundingBoxWidth) + 0.5f);
        poseStack.scale(maxDim, maxDim, maxDim);

        state.lightCoords = 15728880;
        state.shadowPieces.clear();
        if (this.age >= 0) {
            state.ageInTicks = this.age;
        }
        state.outlineColor = 0;

        if (state instanceof LivingEntityRenderState livingEntityRenderState) {
            livingEntityRenderState.bodyRot = 0;
            livingEntityRenderState.yRot = 0;
            livingEntityRenderState.xRot = 0;
            if (this.walkAnimationPos >= 0) {
                livingEntityRenderState.walkAnimationPos = this.walkAnimationPos;
            }
            if (this.walkAnimationSpeed >= 0) {
                livingEntityRenderState.walkAnimationSpeed = this.walkAnimationSpeed;
            }
        }
        var cameraState = new CameraRenderState();
        cameraState.orientation = this.cameraOrientation;

        minecraft.getEntityRenderDispatcher().submit(state, cameraState, 0, 0, 0, poseStack, this.featureRenderDispatcher.getSubmitNodeStorage());

        this.featureRenderDispatcher.renderAllFeatures();
        this.featureRenderDispatcher.endFrame();
        this.bufferSource.endBatch();

        targetConsumer.accept(this.renderTarget, this.entity);
        poseStack.popPose();
    }

    @Override
    public Component getTitle() {
        return this.entity.getName();
    }

    public float age() {
        return age;
    }

    public void setAge(float age) {
        this.age = age;
    }

    public boolean isLivingEntity() {
        return this.entity instanceof LivingEntity;
    }

    public float walkAnimationPos() {
        return walkAnimationPos;
    }

    public void setWalkAnimationPos(float walkAnimationPos) {
        this.walkAnimationPos = walkAnimationPos;
    }

    public float walkAnimationSpeed() {
        return walkAnimationSpeed;
    }

    public void setWalkAnimationSpeed(float walkAnimationSpeed) {
        this.walkAnimationSpeed = walkAnimationSpeed;
    }
}
