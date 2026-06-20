package eu.pb4.simpleimagerenderer.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import eu.pb4.simpleimagerenderer.util.RenderUtils;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(method = "mainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void replaceMainRenderTarget(CallbackInfoReturnable<RenderTarget> cir) {
        if (RenderUtils.mainRenderTargetReplacement != null) {
            cir.setReturnValue(RenderUtils.mainRenderTargetReplacement);
        }
    }
}
