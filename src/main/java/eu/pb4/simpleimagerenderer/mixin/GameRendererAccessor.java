package eu.pb4.simpleimagerenderer.mixin;

import org.spongepowered.asm.mixin.gen.Accessor;

@org.spongepowered.asm.mixin.Mixin(net.minecraft.client.renderer.GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor
    boolean isUseUiLightmap();

    @Accessor
    void setUseUiLightmap(boolean useUiLightmap);
}
