package eu.pb4.simpleimagerenderer.util.renderregion;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintCache;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.*;
import net.minecraft.util.ARGB;
import net.minecraft.util.Util;
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
import org.jetbrains.annotations.UnknownNullability;
import org.jspecify.annotations.Nullable;

public class AreaRenderSectionRegion extends FakeRenderSectionRegion {
    private final BlockBox area;
    private final Long2ObjectMap<LevelChunkSection> sections = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectMap<Holder<Biome>[]> fastBiomeMap = new Long2ObjectOpenHashMap<>();
    private final Object2ObjectArrayMap<ColorResolver, BlockTintCache> tintCaches = Util.make(
            new Object2ObjectArrayMap<>(3),
            object2ObjectArrayMap -> {
                object2ObjectArrayMap.put(
                        BiomeColors.GRASS_COLOR_RESOLVER, new BlockTintCache(blockPos -> this.calculateBlockTint(blockPos, BiomeColors.GRASS_COLOR_RESOLVER))
                );
                object2ObjectArrayMap.put(
                        BiomeColors.FOLIAGE_COLOR_RESOLVER, new BlockTintCache(blockPos -> this.calculateBlockTint(blockPos, BiomeColors.FOLIAGE_COLOR_RESOLVER))
                );
                object2ObjectArrayMap.put(
                        BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER, new BlockTintCache(blockPos -> this.calculateBlockTint(blockPos, BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER))
                );
                object2ObjectArrayMap.put(
                        BiomeColors.WATER_COLOR_RESOLVER, new BlockTintCache(blockPos -> this.calculateBlockTint(blockPos, BiomeColors.WATER_COLOR_RESOLVER))
                );
            }
    );
    private boolean allowExternalLookup = false;
    private boolean ignoreLighting = false;

    public AreaRenderSectionRegion(Level level, BlockBox area) {
        super(level);
        this.area = area;
    }

    public boolean allowExternalLookup() {
        return allowExternalLookup;
    }

    public void setAllowExternalLookup(boolean allowExternalLookup) {
        this.allowExternalLookup = allowExternalLookup;
    }

    public boolean ignoreLighting() {
        return this.ignoreLighting;
    }

    public void setIgnoreLighting(boolean ignoreLighting) {
        this.ignoreLighting = ignoreLighting;
    }

    @Override
    public BlockState getBlockState(BlockPos blockPos) {
        if (this.area.contains(blockPos) || this.allowExternalLookup) {
            var key = SectionPos.asLong(blockPos);
            var section = this.sections.get(key);
            if (section == null) {
                var chunk = this.level.getChunk(SectionPos.x(key), SectionPos.z(key));
                section = chunk.getSection(chunk.getSectionIndexFromSectionY(SectionPos.y(key))).copy();
                this.sections.put(key, section.copy());
            }

            return section.getStates().get(blockPos.getX() & 15, blockPos.getY() & 15, blockPos.getZ() & 15);
        }

        return Blocks.VOID_AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos blockPos) {
        if (this.area.contains(blockPos) || this.allowExternalLookup) {
            var key = SectionPos.asLong(blockPos);
            var section = this.sections.get(key);
            if (section == null) {
                var chunk = this.level.getChunk(SectionPos.x(key), SectionPos.z(key));
                section = chunk.getSection(chunk.getSectionIndexFromSectionY(SectionPos.y(key))).copy();
                this.sections.put(key, section.copy());
            }

            return section.getFluidState(blockPos.getX() & 15, blockPos.getY() & 15, blockPos.getZ() & 15);
        }

        return Fluids.EMPTY.defaultFluidState();
    }

    @Override
    public int getBlockTint(BlockPos blockPos, ColorResolver colorResolver) {
        BlockTintCache blockTintCache = this.tintCaches.get(colorResolver);
        if (blockTintCache == null) {
            return this.level.getBlockTint(blockPos, colorResolver);
        }
        return blockTintCache.getColor(blockPos);
    }


    private int calculateBlockTint(BlockPos pos, ColorResolver colorResolver) {
        int dist = Minecraft.getInstance().options.biomeBlendRadius().get();
        if (dist == 0) {
            return colorResolver.getColor(this.getBiomeFabric(pos).value(), pos.getX(), pos.getZ());
        } else {
            int count = (dist * 2 + 1) * (dist * 2 + 1);
            int totalRed = 0;
            int totalGreen = 0;
            int totalBlue = 0;
            Cursor3D cursor = new Cursor3D(pos.getX() - dist, pos.getY(), pos.getZ() - dist, pos.getX() + dist, pos.getY(), pos.getZ() + dist);

            int color;
            for (var nextPos = new BlockPos.MutableBlockPos(); cursor.advance();) {
                nextPos.set(cursor.nextX(), cursor.nextY(), cursor.nextZ());
                color = colorResolver.getColor(this.getBiomeFabric(nextPos).value(), nextPos.getX(), nextPos.getZ());
                totalRed += ARGB.red(color);
                totalGreen += ARGB.green(color);
                totalBlue += ARGB.blue(color);
            }

            return ARGB.color(totalRed / count, totalGreen / count, totalBlue / count);
        }
    }

    @Override
    public @UnknownNullability Holder<Biome> getBiomeFabric(BlockPos blockPos) {
        var key = SectionPos.asLong(blockPos);
        var index = blockPos.getX() & 15 | (blockPos.getY() & 15) << 4 | (blockPos.getZ() & 15) << 8;
        var cache = this.fastBiomeMap.get(key);
        if (cache == null) {
            //noinspection unchecked
            cache = new Holder[16 * 16 * 16];
            this.fastBiomeMap.put(key, cache);
        }
        var biome = cache[index];
        if (biome == null) {
            cache[index] = biome = this.level.getBiome(blockPos);
        }

        return biome;
    }

    @Override
    public boolean hasBiomes() {
        return true;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return this.ignoreLighting ? CustomLightEngine.FULL_BRIGHT : this.level.getLightEngine();
    }

    @Override
    public float getShade(Direction direction, boolean bl) {
        return this.level.getShade(direction, bl);
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
    public BlockPos limitPos(BlockPos pos) {
        return this.area.contains(pos) ? pos : BlockPos.min(BlockPos.max(pos, this.area.min()), this.area.max());
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos blockPos) {
        if (this.area.contains(blockPos)) {
            return this.level.getBlockEntity(blockPos);
        }

        return null;
    }
}