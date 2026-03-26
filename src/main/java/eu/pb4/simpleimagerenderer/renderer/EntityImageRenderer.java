package eu.pb4.simpleimagerenderer.renderer;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class EntityImageRenderer extends AbstractImageRenderer<Entity> {
    private final Entity entity;
    private boolean bodyRotation = true;
    private boolean headRotation = true;
    private float age = 0;
    private float walkAnimationPos = 0;
    private float walkAnimationSpeed = 0;

    public EntityImageRenderer(Minecraft minecraft, int width, int height, Entity entity) {
        super(minecraft, width, height);
        this.entity = entity;
    }

    @Override
    protected void renderInner(RenderConsumer<Entity> targetConsumer, boolean preview) {
        var list = new ArrayList<EntityRenderState>();
        this.extractStates(entity, list::add);
        var firstState = list.getFirst();

        var bHeight = 0d;
        var bWidth = 0d;

        for (var state : list) {
            bHeight = Math.max(bHeight, state.boundingBoxHeight + state.y - firstState.y);
            bWidth = Math.max(bWidth, state.boundingBoxWidth + state.x - firstState.x);
        }

        var poseStack = new PoseStack();
        poseStack.pushPose();
        this.multiplyPoseStack(poseStack);
        poseStack.translate(0, width / 1.1f - width / 2f, 0);

        poseStack.scale(width, -width, width);
        var maxDim = 1 /  (float) (Math.max(bHeight, bWidth) + 0.5f);
        poseStack.scale(maxDim, maxDim, maxDim);

        var light = this.lightingType.getEntry(Lighting.Entry.ENTITY_IN_UI);
        minecraft.gameRenderer.getLighting().setupFor(light);
        if (light == Lighting.Entry.LEVEL) {
            poseStack.last().normal().scale(1, -1, 1);
        }

        var cameraState = new CameraRenderState();
        cameraState.orientation = this.cameraOrientation;

        var d = minecraft.getEntityRenderDispatcher();
        for (var state : list) {
            d.submit(state, cameraState, state.x - firstState.x, state.y - firstState.y, state.z - firstState.z, poseStack, this.featureRenderDispatcher.getSubmitNodeStorage());
        }

        this.featureRenderDispatcher.renderAllFeatures();
        this.featureRenderDispatcher.endFrame();
        this.bufferSource.endBatch();

        targetConsumer.rendered(this.renderTarget, this.entity, -1);
        poseStack.popPose();
    }

    private void extractStates(Entity entity, Consumer<EntityRenderState> consumer) {
        var uiLightmap = this.lightmapType.useUiLightmap(this.useUiLightmapByDefault);

        var state = minecraft.getEntityRenderDispatcher().extractEntity(entity, 0);

        state.shadowPieces.clear();
        if (this.age >= 0) {
            state.ageInTicks = this.age;
        }
        state.outlineColor = 0;
        if (uiLightmap) {
            state.lightCoords = 0;
        }

        if (state instanceof LivingEntityRenderState livingEntityRenderState) {
            if (!this.headRotation) {
                livingEntityRenderState.yRot = 0;
                livingEntityRenderState.xRot = 0;
            }

            if (!this.bodyRotation) {
                livingEntityRenderState.bodyRot = 0;
            }




            if (this.walkAnimationPos >= 0) {
                livingEntityRenderState.walkAnimationPos = this.walkAnimationPos;
            }
            if (this.walkAnimationSpeed >= 0) {
                livingEntityRenderState.walkAnimationSpeed = this.walkAnimationSpeed;
            }
        }

        consumer.accept(state);
        for (var passenger : entity.getPassengers()) {
            extractStates(passenger, consumer);
        }
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

    public boolean bodyRotation() {
        return bodyRotation;
    }

    public boolean headRotation() {
        return headRotation;
    }

    public void setBodyRotation(boolean clearBodyRotation) {
        this.bodyRotation = clearBodyRotation;
    }

    public void setHeadRotation(boolean clearHeadRotation) {
        this.headRotation = clearHeadRotation;
    }
}
