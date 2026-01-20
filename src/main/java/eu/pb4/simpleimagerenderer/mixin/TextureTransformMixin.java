package eu.pb4.simpleimagerenderer.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import eu.pb4.simpleimagerenderer.util.RenderUtils;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(TextureTransform.class)
public class TextureTransformMixin {
    @ModifyExpressionValue(method = "setupGlintTexturing", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;getMillis()J"))
    private static long replaceTime(long original) {
        return RenderUtils.glintTimeOverride != -1 ? RenderUtils.glintTimeOverride : original;
    }
}
