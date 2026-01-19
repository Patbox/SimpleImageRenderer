package eu.pb4.simpleimagerenderer.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import eu.pb4.simpleimagerenderer.ModInit;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void replaceMainRenderTarget(CallbackInfoReturnable<RenderTarget> cir) {
        if (ModInit.mainRenderTargetReplacement != null) {
            cir.setReturnValue(ModInit.mainRenderTargetReplacement);
        }
    }
}
