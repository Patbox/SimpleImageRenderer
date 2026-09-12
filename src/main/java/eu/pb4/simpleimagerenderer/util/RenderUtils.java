package eu.pb4.simpleimagerenderer.util;

import com.google.common.util.concurrent.Runnables;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTexture;
import eu.pb4.simpleimagerenderer.ModInit;
import org.jetbrains.annotations.Nullable;

public class RenderUtils {
    public static long glintTimeOverride = -1;
    @Nullable
    public static RenderTarget mainRenderTargetReplacement;

    public static void writeToNativeImage(final RenderTarget target, ThrowingConsumer<NativeImage> imageConsumer) {
        int width = target.width;
        int height = target.height;
        GpuTexture sourceTexture = target.getColorTexture();
        if (sourceTexture == null) {
            throw new IllegalStateException("Tried to capture screenshot of an incomplete framebuffer");
        } else {
            GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Screenshot buffer", 9, (long) width * height * sourceTexture.getFormat().blockSize());

            RenderSystem.getDevice()
                    .createCommandEncoder()
                    .copyTextureToBuffer(
                            sourceTexture,
                            buffer,
                            0L,
                            () -> {
                                try (GpuBufferSlice.MappedView read = buffer.map(true, false);
                                     NativeImage image = new NativeImage(width, height, false)
                                ) {
                                    for (int y = 0; y < height; y++) {
                                        for (int x = 0; x < width; x++) {
                                            int argb = read.data().getInt((x + y * width) * sourceTexture.getFormat().blockSize());
                                            image.setPixelABGR(x, height - y - 1, argb);
                                        }
                                    }

                                    imageConsumer.accept(image);
                                } catch (Throwable e) {
                                    ModInit.LOGGER.error("Image handling failer", e);
                                } finally {
                                    buffer.close();
                                }
                            },
                            0
                    );
        }
    }

    public interface ThrowingConsumer<T> {
        void accept(T value) throws Throwable;
    }
}
