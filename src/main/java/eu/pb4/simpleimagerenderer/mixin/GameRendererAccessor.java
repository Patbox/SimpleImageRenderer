package eu.pb4.simpleimagerenderer.mixin;

import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.gen.Accessor;

@org.spongepowered.asm.mixin.Mixin(net.minecraft.client.renderer.GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor
    FogRenderer getFogRenderer();

    @Accessor
    boolean isUseUiLightmap();

    @Accessor
    void setUseUiLightmap(boolean useUiLightmap);
}
