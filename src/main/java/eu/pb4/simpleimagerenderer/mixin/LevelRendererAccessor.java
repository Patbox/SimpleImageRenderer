package eu.pb4.simpleimagerenderer.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.BlockDestructionProgress;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.SortedSet;

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

    @Invoker("submitBlockOutline")
    void sim_renderBlockOutline(final PoseStack poseStack, final SubmitNodeCollector submitNodeCollector, final LevelRenderState levelRenderState);

    @Invoker("submitEntities")
    void sim_submitEntities(PoseStack poseStack, LevelRenderState levelRenderState, SubmitNodeCollector submitNodeCollector);

    @Invoker("submitBlockEntities")
    void sim_submitBlockEntities(final PoseStack poseStack, final LevelRenderState levelRenderState, final SubmitNodeCollector submitNodeCollector);

    @Invoker("submitBlockDestroyAnimation")
    void sim_submitBlockDestroyAnimation(final PoseStack poseStack, final SubmitNodeCollector submitNodeCollector, final LevelRenderState levelRenderState);

    //@Accessor("destructionProgress")
    //Long2ObjectMap<SortedSet<BlockDestructionProgress>> sim_getDestructionProgress();
}
