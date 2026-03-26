package eu.pb4.simpleimagerenderer.util.renderregion;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jspecify.annotations.Nullable;

public class SingleBlockRenderRegion extends FakeRenderSectionRegion {
    private final BlockState state;
    private final LevelLightEngine light;

    public SingleBlockRenderRegion(ClientLevel level, BlockState state) {
        super(level);
        this.state = state;
        this.light = new CustomLightEngine(this, x -> 15, x -> 15);
    }

    @Override
    public BlockState getBlockState(BlockPos blockPos) {
        if (blockPos.equals(BlockPos.ZERO)) {
            return this.state;
        }

        return Blocks.VOID_AIR.defaultBlockState();
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return this.light;
    }

    @Override
    public int getMinY() {
        return 0;
    }

    @Override
    public int getHeight() {
        return 1;
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos blockPos) {
        return null;
    }
}