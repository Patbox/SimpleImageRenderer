package eu.pb4.simpleimagerenderer.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GuiGraphics.class)
public interface GuiGraphicsAccessor {
    @Invoker
    void callSubmitBlit(RenderPipeline renderPipeline, GpuTextureView gpuTextureView, GpuSampler gpuSampler, int i, int j, int k, int l, float f, float g, float h, float m, int n);
}
