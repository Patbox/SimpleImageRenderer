package eu.pb4.simpleimagerenderer;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import eu.pb4.simpleimagerenderer.mixin.GuiGraphicsAccessor;
import eu.pb4.simpleimagerenderer.renderer.AbstractImageRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.data.AtlasIds;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.joml.Matrix3x2f;

import java.util.function.BiConsumer;

public class PreviewScreen<T> extends Screen {
    public final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private final AbstractImageRenderer<T> renderer;
    private final BiConsumer<TextureTarget, T> consumer;

    protected PreviewScreen(AbstractImageRenderer<T> renderer, BiConsumer<TextureTarget, T> consumer) {
        super(Component.literal("Preview image..."));
        this.renderer = renderer;
        this.consumer = consumer;
    }

    @Override
    protected void init() {
        this.addTitle();
        this.addContents();
        this.addFooter();
        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
    }

    protected void addTitle() {
        this.layout.addTitleHeader(this.title, this.font);
    }

    protected void addContents() {


    }

    protected void addFooter() {
        LinearLayout linearLayout = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));

        {
            var size = new EditBox(this.font, 50, 20, Component.empty());

            size.setResponder((input) -> {
                if (!input.isEmpty()) {
                    try {
                        var value = Integer.parseInt(input);
                        this.renderer.setupTexture(Mth.clamp(value, 16, 2048));
                    } catch (Exception e) {
                        // Silence!
                    }
                }
            });

            size.setFilter((input) -> {
                if (input.isEmpty()) {
                    return true;
                }
                try {
                    var i = Integer.parseInt(input);

                    if (i >= 1 && i <= 2048) {
                        return true;
                    }
                } catch (Exception e) {
                    // Silence!
                }

                return false;
            });

            size.setValue("" + this.renderer.width());

            linearLayout.addChild(size);
        }

        linearLayout.addChild(Button.builder(Component.literal("Render"), (button) -> {
            this.minecraft.setScreen(null);
            this.renderer.render(this.consumer, false);
        }).width(100).build());

        linearLayout.addChild(Button.builder(CommonComponents.GUI_CANCEL, (button) -> {
            this.minecraft.setScreen(null);
        }).width(100).build());
    }

    protected void repositionElements() {
        this.layout.arrangeElements();
    }

    @Override
    public void onClose() {
        super.onClose();
        this.renderer.close();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int i, int j, float f) {
        super.render(guiGraphics, i, j, f);
        this.renderer.render((x, y) -> {
            var main = this.minecraft.getMainRenderTarget();
            var mult = main.width / this.width;
            var maxHeight = main.height - (this.layout.getHeaderHeight() + this.layout.getFooterHeight() + 2) * mult;

            int height = x.height;
            int width = x.width;
            var scaledDown = false;
            if (x.height > maxHeight) {
                height = maxHeight;
                width = (int) ((maxHeight / (float) x.height) * x.width);
                scaledDown = true;
            }

            var startX = main.width / 2 - width / 2;
            var startY = this.layout.getHeaderHeight() * mult + 2;
            var endX = startX + width;
            var endY = startY + height;

            guiGraphics.pose().pushMatrix();
            guiGraphics.pose().scale(1f / mult);

            guiGraphics.fill(startX, startY, endX, endY, 0xFF000000);
            guiGraphics.renderOutline(startX - 1, startY - 1, width + 2, height + 2, scaledDown ? 0xFFFF9944 : 0xFFFFFFFF);
            var sampler = RenderSystem.getSamplerCache().getSampler(AddressMode.REPEAT, AddressMode.REPEAT, FilterMode.NEAREST, FilterMode.NEAREST, false);
            ((GuiGraphicsAccessor) guiGraphics).callSubmitBlit(RenderPipelines.GUI_TEXTURED,
                    x.getColorTextureView(), sampler,
                    startX, startY, endX, endY,
                    0, 1,1, 0, -1
            );

            guiGraphics.pose().popMatrix();
        }, true);
    }
}
