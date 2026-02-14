package eu.pb4.simpleimagerenderer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import eu.pb4.simpleimagerenderer.util.renderregion.FakeRenderSectionRegion;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SectionCompiler.class)
public class SectionCompilerMixin {
    @WrapOperation(method = "compile", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;betweenClosed(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Ljava/lang/Iterable;"))
    private Iterable<BlockPos> limitPositions(BlockPos blockPos, BlockPos blockPos2, Operation<Iterable<BlockPos>> original,
                                              @Local(argsOnly = true)RenderSectionRegion renderSectionRegion) {
        return renderSectionRegion instanceof FakeRenderSectionRegion fake
                ? original.call(fake.limitPos(blockPos), fake.limitPos(blockPos2))
                : original.call(blockPos, blockPos2);
    }
}
