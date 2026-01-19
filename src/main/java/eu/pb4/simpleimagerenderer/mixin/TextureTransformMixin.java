package eu.pb4.simpleimagerenderer.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import eu.pb4.simpleimagerenderer.ModInit;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

@Mixin(TextureTransform.class)
public class TextureTransformMixin {
    @ModifyExpressionValue(method = "setupGlintTexturing", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;getMillis()J"))
    private static long replaceTime(long original) {
        return ModInit.glintTimeOverride != -1 ? ModInit.glintTimeOverride : original;
    }
}
