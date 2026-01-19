package eu.pb4.simpleimagerenderer.mixin;

import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@org.spongepowered.asm.mixin.Mixin(net.minecraft.client.particle.ParticleEngine.class)
public interface ParticleEngineAccessor {
    @Accessor
    Map<ParticleRenderType, ParticleGroup<?>> getParticles();
}
