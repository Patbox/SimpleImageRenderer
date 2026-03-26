package eu.pb4.simpleimagerenderer.util.renderregion;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

import java.util.function.ToIntFunction;

public abstract class FakeRenderSectionRegion extends RenderSectionRegion {
    protected final ClientLevel level;

    public FakeRenderSectionRegion(ClientLevel level) {
        super(level, 0, 0, 0, new SectionCopy[0]);
        this.level = level;
    }

    @Override
    public abstract BlockState getBlockState(BlockPos blockPos);

    @Override
    public FluidState getFluidState(BlockPos blockPos) {
        return getBlockState(blockPos).getFluidState();
    }

    @Override
    public int getBlockTint(BlockPos blockPos, ColorResolver colorResolver) {
        return colorResolver.getColor(this.level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(), blockPos.getX(), blockPos.getZ());
    }

    @Override
    public abstract LevelLightEngine getLightEngine();

    @Override
    public abstract int getMinY();

    @Override
    public abstract int getHeight();

    @Override
    public abstract @Nullable BlockEntity getBlockEntity(BlockPos blockPos);

    public BlockPos limitPos(BlockPos pos) {
        return pos;
    }

    @Override
    public CardinalLighting cardinalLighting() {
        return this.level.cardinalLighting();
    }

    public static class CustomLightEngine extends LevelLightEngine {
        public static final CustomLightEngine FULL_BRIGHT = new CustomLightEngine(x -> 15, x -> 15);
        @Nullable
        private final LayerLightEventListener blockLight;
        @Nullable
        private final LayerLightEventListener skyLight;

        public CustomLightEngine(ToIntFunction<BlockPos> blockLight, ToIntFunction<BlockPos> skyLight) {
            this(EmptyBlockGetter.INSTANCE, blockLight, skyLight);
        }

        public CustomLightEngine(BlockGetter render, ToIntFunction<BlockPos> blockLight, ToIntFunction<BlockPos> skyLight) {
            this(render, blockLight != null ? new FakeLightLayer(blockLight) : null, skyLight != null ? new FakeLightLayer(skyLight) : null);
        }

        public CustomLightEngine(BlockGetter render, LayerLightEventListener blockLight, LayerLightEventListener skyLight) {
            super(new LightChunkGetter() {
                @Override
                public @Nullable LightChunk getChunkForLighting(int i, int j) {
                    return null;
                }

                @Override
                public BlockGetter getLevel() {
                    return render;
                }
            }, false, false);
            this.blockLight = blockLight;
            this.skyLight = skyLight;
        }

        @Override
        public LayerLightEventListener getLayerListener(LightLayer lightLayer) {
            return switch (lightLayer) {
                case SKY -> this.skyLight != null ? this.skyLight : super.getLayerListener(lightLayer);
                case BLOCK -> this.blockLight != null ? this.blockLight : super.getLayerListener(lightLayer);
            };
        }

        @Override
        public int getRawBrightness(BlockPos blockPos, int i) {
            int j = this.skyLight == null ? 0 : this.skyLight.getLightValue(blockPos) - i;
            int k = this.blockLight == null ? 0 : this.blockLight.getLightValue(blockPos);
            return Math.max(k, j);
        }

        @Override
        public boolean hasLightWork() {
            return this.blockLight != null || this.skyLight != null;
        }

        private record FakeLightLayer(ToIntFunction<BlockPos> function) implements LayerLightEventListener {
            @Override
            public @Nullable DataLayer getDataLayerData(SectionPos sectionPos) {
                return null;
            }

            @Override
            public int getLightValue(BlockPos blockPos) {
                return function.applyAsInt(blockPos);
            }

            @Override
            public void checkBlock(BlockPos blockPos) {

            }

            @Override
            public boolean hasLightWork() {
                return true;
            }

            @Override
            public int runLightUpdates() {
                return 0;
            }

            @Override
            public void updateSectionStatus(SectionPos sectionPos, boolean bl) {

            }

            @Override
            public void setLightEnabled(ChunkPos chunkPos, boolean bl) {

            }

            @Override
            public void propagateLightSources(ChunkPos chunkPos) {

            }
        }
    }
}