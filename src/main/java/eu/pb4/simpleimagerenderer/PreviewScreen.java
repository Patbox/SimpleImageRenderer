package eu.pb4.simpleimagerenderer;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import eu.pb4.simpleimagerenderer.mixin.GuiGraphicsAccessor;
import eu.pb4.simpleimagerenderer.renderer.AbstractImageRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

import java.util.function.BiConsumer;

public class PreviewScreen<T> extends Screen {
    private HeaderAndFooterLayout layout;
    private final AbstractImageRenderer<T> renderer;
    private final BiConsumer<TextureTarget, T> consumer;
    private int yaw = 0;
    private int pitch = 0;
    private int roll = 0;
    private float scale = 1;

    protected PreviewScreen(AbstractImageRenderer<T> renderer, BiConsumer<TextureTarget, T> consumer) {
        super(Component.literal("Preview image..."));
        this.renderer = renderer;
        this.consumer = consumer;
    }

    @Override
    protected void init() {
        this.layout = new HeaderAndFooterLayout(this);
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
        var hor = this.layout.addToContents(LinearLayout.horizontal().spacing(0));
        hor.addChild(new SpacerElement(this.width / 2, 10));
        var list = LinearLayout.vertical().spacing(8);
        list.defaultCellSetting().alignHorizontallyCenter();
        {
            var group = LinearLayout.horizontal().spacing(8);
            group.addChild(new StringWidget(Component.literal("Image Width"), font), group.newCellSettings().alignVerticallyMiddle());
            var size = new EditBox(this.font, 50, 20, Component.literal("Image Width"));

            group.addChild(size);
            list.addChild(group);

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
        }

        list.addChild(new AbstractSliderButton(0, 0, 100, 20, Component.empty(), this.pitch / 360f + 0.5) {
            {
                this.updateMessage();
            }

            protected void updateMessage() {
                this.setMessage(Component.literal("Pitch: " + pitch));
            }

            @Override
            protected void applyValue() {
                pitch = Mth.lerpInt((float) this.value, -180, 180);
                updateMatrix();
            }
        });

        list.addChild(new AbstractSliderButton(0, 0, 100, 20, Component.empty(), this.yaw / 360f + 0.5) {
            {
                this.updateMessage();
            }

            protected void updateMessage() {
                this.setMessage(Component.literal("Yaw: " + yaw));
            }

            @Override
            protected void applyValue() {
                yaw = Mth.lerpInt((float) this.value, -180, 180);
                updateMatrix();
            }
        });
        list.addChild(new AbstractSliderButton(0, 0, 100, 20, Component.empty(), this.roll / 360f + 0.5) {
            {
                this.updateMessage();
            }

            protected void updateMessage() {
                this.setMessage(Component.literal("Roll: " + roll));
            }

            @Override
            protected void applyValue() {
                roll = Mth.lerpInt((float) this.value, -180, 180);
                updateMatrix();
            }
        });

        list.addChild(new AbstractSliderButton(0, 0, 100, 20, Component.empty(), this.scale / 4) {
            {
                this.updateMessage();
            }

            protected void updateMessage() {
                this.setMessage(Component.literal("Scale: " + scale));
            }

            @Override
            protected void applyValue() {
                scale = Math.max(Mth.lerpInt((float) this.value, 0, 400), 1) / 100f;
                updateMatrix();
            }
        });

        list.addChild(Button.builder(Component.literal("Rotate light: " + this.renderer.multiplyNormals()), b -> {
            this.renderer.setMultiplyNormals(!this.renderer.multiplyNormals());
            b.setMessage(Component.literal("Rotate light: " + this.renderer.multiplyNormals()));
        }).width(150).build());

        var scrl = hor.addChild(new ScrollableLayout(minecraft, list, this.layout.getContentHeight()));
        scrl.setMaxHeight(this.layout.getContentHeight());
        list.arrangeElements();
    }

    private void updateMatrix() {
        this.renderer.updateMatrix(new Matrix4f()
                .rotateXYZ(this.pitch * Mth.DEG_TO_RAD, this.yaw * Mth.DEG_TO_RAD, this.roll * Mth.DEG_TO_RAD)
                .scale(this.scale)
        );
    }

    protected void addFooter() {
        LinearLayout linearLayout = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));

        linearLayout.addChild(Button.builder(Component.literal("Render"), (button) -> {
            this.minecraft.setScreen(null);
            this.renderer.render(this.consumer, false);
        }).width(100).build());

        linearLayout.addChild(Button.builder(CommonComponents.GUI_CANCEL, (button) -> {
            this.minecraft.setScreen(null);
        }).width(100).build());
    }

    @Override
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
            var mult = this.minecraft.getWindow().getGuiScale();
            var maxHeight = main.height - (this.layout.getHeaderHeight() + this.layout.getFooterHeight() + 2) * mult;
            var maxWidth = main.width / 2;

            int height = x.height;
            int width = x.width;
            var scaledDown = false;
            if (height > maxHeight) {
                width = (int) ((maxHeight / (float) height) * width);
                height = maxHeight;
                scaledDown = true;
            }

            if (width > maxWidth) {
                height = (int) ((maxWidth / (float) width) * height);
                width = maxWidth;
                scaledDown = true;
            }

            var startX = main.width / 4 - width / 2;
            var startY = main.height / 2 - height / 2;
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
                    0, 1, 1, 0, -1
            );

            guiGraphics.pose().popMatrix();
        }, true);
    }
}
