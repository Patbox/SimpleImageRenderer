package eu.pb4.simpleimagerenderer.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.Inject;

@org.spongepowered.asm.mixin.Mixin(net.minecraft.client.renderer.LevelRenderer.class)
public interface LevelRendererAccessor {
    @Accessor
    static Identifier getENTITY_OUTLINE_POST_CHAIN_ID() {
        throw new UnsupportedOperationException();
    }

    @Accessor
    static Identifier getTRANSPARENCY_POST_CHAIN_ID() {
        throw new UnsupportedOperationException();
    }

    @Accessor("targets")
    LevelTargetBundle sim_getTargets();

    @Invoker("getTransparencyChain")
    PostChain sim_getTransparencyChain();

    @Invoker("renderBlockOutline")
    void sim_renderBlockOutline(MultiBufferSource.BufferSource bufferSource, PoseStack poseStack, boolean bl, LevelRenderState levelRenderState);

    @Invoker("submitEntities")
    void sim_submitEntities(PoseStack poseStack, LevelRenderState levelRenderState, SubmitNodeCollector submitNodeCollector);

    @Invoker("submitBlockEntities")
    void sim_submitBlockEntities(PoseStack poseStack, LevelRenderState levelRenderState, SubmitNodeStorage submitNodeStorage);

    @Invoker("renderBlockDestroyAnimation")
    void sim_renderBlockDestroyAnimation(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource2, LevelRenderState levelRenderState);
}
