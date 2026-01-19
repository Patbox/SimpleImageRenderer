package eu.pb4.simpleimagerenderer.util.renderregion;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

public class AreaRenderSectionRegion extends FakeRenderSectionRegion {
    private final BlockBox area;
    private final Long2ObjectMap<LevelChunkSection> sections = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectMap<Biome[]> fastBiomeMap = new Long2ObjectOpenHashMap<>();

    public AreaRenderSectionRegion(Level level, BlockBox area) {
        super(level);
        this.area = area;
    }

    @Override
    public BlockState getBlockState(BlockPos blockPos) {
        if (this.area.contains(blockPos)) {
            var key = SectionPos.asLong(blockPos);
            var section = this.sections.get(key);
            if (section == null) {
                var chunk = this.level.getChunk(SectionPos.x(key), SectionPos.z(key));
                section = chunk.getSection(chunk.getSectionIndexFromSectionY(SectionPos.y(key))).copy();
                this.sections.put(key, section);
            }

            return section.getStates().get(blockPos.getX() & 15, blockPos.getY() & 15, blockPos.getZ() & 15);
        }

        return Blocks.VOID_AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos blockPos) {
        if (this.area.contains(blockPos)) {
            var key = SectionPos.asLong(blockPos);
            var section = this.sections.get(key);
            if (section == null) {
                var chunk = this.level.getChunk(SectionPos.x(key), SectionPos.z(key));
                section = chunk.getSection(chunk.getSectionIndexFromSectionY(SectionPos.y(key))).copy();
                this.sections.put(key, section);
            }

            return section.getFluidState(blockPos.getX() & 15, blockPos.getY() & 15, blockPos.getZ() & 15);
        }

        return Fluids.EMPTY.defaultFluidState();
    }

    @Override
    public int getBlockTint(BlockPos blockPos, ColorResolver colorResolver) {
        var key = SectionPos.asLong(blockPos);
        var index = blockPos.getX() & 15 | (blockPos.getY() & 15) << 4 | (blockPos.getZ() & 15) << 8;
        var cache = this.fastBiomeMap.get(key);
        if (cache == null) {
            cache = new Biome[16 * 16 * 16];
            this.fastBiomeMap.put(key, cache);
        }
        var biome = cache[index];
        if (biome == null) {
            cache[index] = biome = this.level.getBiome(blockPos).value();
        }

        return colorResolver.getColor(biome, blockPos.getX(), blockPos.getZ());
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return this.level.getLightEngine();
    }

    @Override
    public int getMinY() {
        return this.level.getMinY();
    }

    @Override
    public int getHeight() {
        return this.level.getHeight();
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos blockPos) {
        if (this.area.contains(blockPos)) {
            return this.level.getBlockEntity(blockPos);
        }

        return null;
    }
}