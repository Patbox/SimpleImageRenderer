package eu.pb4.simpleimagerenderer;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import eu.pb4.simpleimagerenderer.mixin.GuiGraphicsAccessor;
import eu.pb4.simpleimagerenderer.renderer.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;

import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Locale;
import java.util.function.*;

public class PreviewScreen<T> extends Screen {
    private final AbstractImageRenderer<T> renderer;
    private final BiConsumer<TextureTarget, T> consumer;
    private RendererSettings settings;
    private HeaderAndFooterLayout layout;
    private int imageWidth;
    private int startX;
    private int startY;
    private int endX;
    private int endY;
    private SliderWithText scale;
    private SliderWithText yaw;
    private SliderWithText pitch;
    private SliderWithText xpos;
    private SliderWithText ypos;

    private boolean startDraggingImage;
    private double posOverflowX;
    private double posOverflowY;
    private double rotOverflowX;
    private double rotOverflowY;

    protected PreviewScreen(AbstractImageRenderer<T> renderer, RendererSettings settings, BiConsumer<TextureTarget, T> consumer) {
        super(Component.translatable("title.simple_image_renderer.preview." + switch (renderer) {
            case ItemImageRenderer x -> "item";
            case BlockImageRenderer x -> "block";
            case EntityImageRenderer x -> "entity";
            case RegionImageRenderer x -> "region";
            default -> "default";
        }, renderer.getTitle()));
        this.renderer = renderer;
        this.consumer = consumer;
        this.settings = settings;
    }

    @Override
    protected void init() {
        this.layout = new HeaderAndFooterLayout(this);
        this.addTitle();
        this.addContents();
        this.addFooter();
        this.layout.visitWidgets(this::addRenderableWidget);
        this.layout.arrangeElements();
    }

    protected void addTitle() {
        this.layout.addTitleHeader(this.title, this.font);
    }

    protected void addContents() {
        var list = new ArrayList<LayoutElement>();
        this.createButtons(list::add);

        var list2 = LinearLayout.vertical().spacing(2);
        list2.defaultCellSetting().alignHorizontallyCenter();
        list.forEach(list2::addChild);
        list2.arrangeElements();
        var hor = this.layout.addToContents(LinearLayout.horizontal().spacing(0));

        this.imageWidth = Math.min(this.width - list2.getWidth() - 20, this.width / 2);
        hor.addChild(new SpacerElement(this.imageWidth, 10));


        var scrl = hor.addChild(new ScrollableLayout(minecraft, list2, this.layout.getContentHeight()));
        scrl.setMaxHeight(this.layout.getContentHeight());
        list2.arrangeElements();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (this.isWithinImage(x, y)) {
            this.scale.update((int) (this.scale.get() + dy * (this.minecraft.hasControlDown() ? 1 :this.minecraft.hasShiftDown() ? 4 : 8)));
            return true;
        }

        return super.mouseScrolled(x, y, dx, dy);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent mouseButtonEvent, boolean bl) {
        if (this.isWithinImage(mouseButtonEvent.x(), mouseButtonEvent.y())) {
            this.startDraggingImage = true;
            return true;
        }

        return super.mouseClicked(mouseButtonEvent, bl);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent mouseButtonEvent) {
        this.startDraggingImage = false;
        this.posOverflowX = this.posOverflowY = this.rotOverflowY = this.rotOverflowX = 0;
        return super.mouseReleased(mouseButtonEvent);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent mouseButtonEvent, double dx, double dy) {
        if (this.startDraggingImage) {
            var scale = this.minecraft.getWindow().getGuiScale();
            if (mouseButtonEvent.hasShiftDown() || mouseButtonEvent.input() == 1) {
                var newX = this.xpos.get() + dx * scale * 1000 / (this.endX - this.startX) + this.posOverflowX;
                var newY = this.ypos.get() + dy * scale * 1000 / (this.endY - this.startY) + this.posOverflowY;

                this.xpos.update((int) newX);
                this.ypos.update((int) newY);

                this.posOverflowX = newX - ((int) newX);
                this.posOverflowY = newY - ((int) newY);
            } else {
                var newYaw = this.yaw.get() + dx * scale / 2d + this.rotOverflowX;
                var newPitch = this.pitch.get() - dy * scale / 2d + this.rotOverflowY;

                this.yaw.update((int) Mth.wrapDegrees(newYaw));
                this.pitch.update((int) Mth.wrapDegrees(newPitch));

                this.rotOverflowX = newYaw - ((int) newYaw);
                this.rotOverflowY = newPitch - ((int) newPitch);
            }

            return true;
        }
        return super.mouseDragged(mouseButtonEvent, dx, dy);
    }

    private boolean isWithinImage(double x, double y) {
        var mult = this.minecraft.getWindow().getGuiScale();
        x *= mult;
        y *= mult;
        return x >= this.startX && x <= this.endX && y >= this.startY && y <= this.endY;
    }

    private void createButtons(Consumer<LayoutElement> list) {
        var nf = NumberFormat.getNumberInstance(Locale.ROOT);
        nf.setMaximumFractionDigits(2);
        nf.setRoundingMode(RoundingMode.HALF_EVEN);

        // Image size
        {
            var group = LinearLayout.horizontal().spacing(4);
            group.addChild(new StringWidget(button("width"), font), group.newCellSettings().alignVerticallyMiddle());
            group.addChild(createIntEditBox(button("width"), v -> {
                this.renderer.setupTexture(this.settings.width = v, this.settings.height);
                this.updateMatrix();
            }, this.renderer::width, 40, 16, 2048 * 4));
            group.addChild(new StringWidget(button("height"), font), group.newCellSettings().alignVerticallyMiddle());
            group.addChild(createIntEditBox(button("height"), v -> {
                this.renderer.setupTexture(this.settings.width, this.settings.height = v);
                this.updateMatrix();
            }, this.renderer::height, 40, 16, 2048 * 4));

            group.addChild(Button.builder(Component.literal("\uD83D\uDDD8"), btn -> {
                this.settings = new RendererSettings();
                this.settings.applyAll(this.renderer);
                this.rebuildWidgets();
            }).size(20, 20).tooltip(Tooltip.create(text("reset_local"))).build());
            list.accept(group);
        }

        // Pitch
        this.pitch = createIntSliderWithText(button("pitch"), v -> {
            this.settings.pitch = v;
            this.updateMatrix();
        }, () -> this.settings.pitch, 140, 45, -180, 180);
        list.accept(this.pitch.group());

        // Yaw
        this.yaw = createIntSliderWithText(button("yaw"), v -> {
            this.settings.yaw = v;
            this.updateMatrix();
        }, () -> this.settings.yaw, 140, 45, -180, 180);

        list.accept(this.yaw.group());

        // Roll
        list.accept(createIntSliderWithText(button("roll"), v -> {
            this.settings.roll = v;
            this.updateMatrix();
        }, () -> this.settings.roll, 140, 45, -180, 180).group());

        // Scale
        this.scale = createIntSliderWithText(button("scale"), v -> {
            this.settings.scale = v;
            this.updateMatrix();
        }, () -> this.settings.scale, 140, 45, 1, 1000, 100, x -> x + "%");
        list.accept(this.scale.group());

        // X
        this.xpos = createIntSliderWithText(button("x"), v -> {
                    this.settings.x = v;
                    this.updateMatrix();
                }, () -> settings.x, 140, 45, -6400, 6400, 0, x -> nf.format(x / 100d),
                x -> (int) (Double.parseDouble(x) * 100), x -> Double.toString(x / 100d));
        list.accept(this.xpos.group());

        // Y
        this.ypos = createIntSliderWithText(button("y"), v -> {
                    this.settings.y = v;
                    this.updateMatrix();
                }, () -> settings.y, 140, 45, -6400, 6400, 0, x -> nf.format(x / 100d),
                x -> (int) (Double.parseDouble(x) * 100), x -> Double.toString(x / 100d));
        list.accept(this.ypos.group());

        if (!(this.renderer instanceof RegionImageRenderer)) {
            var group = LinearLayout.horizontal().spacing(4);

            // Rotate Light
            group.addChild(Button.builder(button("rotate_light").append(": ").append(CommonComponents.optionStatus(this.renderer.multiplyNormals())), b -> {
                this.renderer.setMultiplyNormals(!this.renderer.multiplyNormals());
                settings.multiplyNormals = this.renderer.multiplyNormals();
                b.setMessage(button("rotate_light").append(": ").append(CommonComponents.optionStatus(this.renderer.multiplyNormals())));
            }).width(100).build());

            group.addChild(CycleButton.<AbstractImageRenderer.LightingType>builder(x -> Component.literal(x.name()), this.renderer::lightingType)
                    .withValues(AbstractImageRenderer.LightingType.values())
                    .create(0, 0, 120, 20, button("lighting"), (btn, val) -> {
                        this.renderer.setLightingType(val);
                        settings.lightingType = val;
                    })
            );

            list.accept(group);
        }

        if (this.renderer instanceof ItemImageRenderer itemImageRenderer) {
            list.accept(CycleButton.<ItemDisplayContext>builder(x -> Component.literal(x.name()), itemImageRenderer::displayContext)
                    .withValues(ItemDisplayContext.values())
                    .create(button("item_display_context"), (btn, val) -> {
                        itemImageRenderer.setDisplayContext(val);
                        settings.context = val;
                    })
            );
        }

        if (this.renderer instanceof EntityImageRenderer entityImageRenderer) {
            var unchanged = value("unchanged").getString();
            DoubleFunction<String> format = x -> x < 0 ? unchanged : nf.format(x);
            var group = LinearLayout.horizontal().spacing(4);

            list.accept(group);

            group.addChild(CycleButton.<Boolean>builder(CommonComponents::optionStatus, entityImageRenderer::bodyRotation)
                    .withValues(true, false)
                    .create(0, 0, 110, 20, button("body_rotation"), (btn, val) -> {
                        entityImageRenderer.setBodyRotation(val);
                        settings.bodyRotation = val;
                    })
            );

            group.addChild(CycleButton.<Boolean>builder(CommonComponents::optionStatus, entityImageRenderer::headRotation)
                    .withValues(true, false)
                    .create(0, 0, 110, 20, button("head_rotation"), (btn, val) -> {
                        entityImageRenderer.setHeadRotation(val);
                        settings.headRotation = val;
                    })
            );

            list.accept(createIntSliderWithText(button("entity_age"), v -> {
                        entityImageRenderer.setAge((float) (settings.age = v));
                        entityImageRenderer.setGlintTime(v * 1000L / 20);
                    },
                    () -> (int) settings.age, 140, 50, -1, 200, x -> x == -1 ? unchanged : String.valueOf(x)).group());

            if (entityImageRenderer.isLivingEntity()) {
                list.accept(createIntSliderWithText(button("walking_pos"), v -> entityImageRenderer.setWalkAnimationPos((float) (settings.walkAnimationPos = v / 200d)),
                        () -> Math.toIntExact(Math.round(settings.walkAnimationPos * 200)), 140, 50, -1, 2000, -1, x -> format.apply(x / 200f),
                        x -> x.equals("-1") ? -1 : (int) (Double.parseDouble(x) * 200), x -> x == -1 ? "-1" : Double.toString(x / 200d)).group());
                list.accept(createIntSliderWithText(button("walking_speed"), v -> entityImageRenderer.setWalkAnimationSpeed((float) (settings.walkAnimationSpeed = v / 100d)),
                        () -> Math.toIntExact(Math.round(settings.walkAnimationSpeed * 100)), 140, 50, -1, 100, -1, x -> format.apply(x / 100f),
                        x -> x.equals("-1") ? -1 : (int) (Double.parseDouble(x) * 100), x -> x == -1 ? "-1" : Double.toString(x / 100d)).group());
            }
        }

        if (this.renderer instanceof RegionImageRenderer regionImageRenderer) {
            var group = LinearLayout.horizontal().spacing(4);

            group.addChild(CycleButton.<Boolean>builder(CommonComponents::optionStatus, regionImageRenderer::renderEntities)
                    .withValues(true, false)
                    .create(0, 0, 110, 20, button("show_entities"), (btn, val) -> {
                        regionImageRenderer.setRenderEntities(val);
                        settings.renderEntities = val;
                    })
            );

            group.addChild(CycleButton.<Boolean>builder(CommonComponents::optionStatus, regionImageRenderer::renderSelf)
                    .withValues(true, false)
                    .create(0, 0, 110, 20, button("show_self"), (btn, val) -> {
                        regionImageRenderer.setRenderSelf(val);
                        settings.renderSelf = val;
                    })
            );
            list.accept(group);
            group = LinearLayout.horizontal().spacing(4);

            group.addChild(CycleButton.<Boolean>builder(CommonComponents::optionStatus, regionImageRenderer::renderNametags)
                    .withValues(true, false)
                    .create(0, 0, 110, 20, button("show_name_tags"), (btn, val) -> {
                        regionImageRenderer.setRenderNametags(val);
                        settings.renderNametags = val;
                    })
            );

            group.addChild(CycleButton.<Boolean>builder(CommonComponents::optionStatus, regionImageRenderer::renderParticles)
                    .withValues(true, false)
                    .create(0, 0, 110, 20, button("show_particles"), (btn, val) -> {
                        regionImageRenderer.setRenderParticles(val);
                        settings.renderParticles = val;
                    })
            );
            list.accept(group);
        }
    }

    private MutableComponent button(String name) {
        return Component.translatable("button.simple_image_renderer." + name);
    }

    private MutableComponent text(String name) {
        return Component.translatable("text.simple_image_renderer." + name);
    }

    private MutableComponent value(String name) {
        return Component.translatable("value.simple_image_renderer." + name);
    }

    private void updateMatrix() {
        this.settings.updateMatrix(this.renderer);
    }

    protected void addFooter() {
        LinearLayout linearLayout = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));

        linearLayout.addChild(Button.builder(button("render"), (button) -> {
            if (!Minecraft.getInstance().hasShiftDown()) {
                this.minecraft.setScreen(null);
            } else {
                this.minecraft.getToastManager().addToast(new SystemToast(
                        SystemToast.SystemToastId.PACK_LOAD_FAILURE,
                        text("rendered_image"),
                        null
                ));
            }
            this.renderer.render(this.consumer, false);
        }).width(100).build());

        linearLayout.addChild(Button.builder(button("save_settings"), btn -> {
            RendererSettings.defaultSettings = this.settings.clone();
            this.minecraft.getToastManager().addToast(new SystemToast(
                    SystemToast.SystemToastId.PACK_LOAD_FAILURE,
                    text("saved_configuration"),
                    null
            ));
        }).width(100).build());

        linearLayout.addChild(Button.builder(CommonComponents.GUI_CANCEL, (button) -> {
            this.minecraft.setScreen(null);
        }).width(100).build());
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
            var maxWidth = this.imageWidth * mult;

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

            this.startX = (maxWidth - width) / 2;
            this.startY = main.height / 2 - height / 2;
            this.endX = startX + width;
            this.endY = startY + height;

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


    public abstract static class SliderButton extends AbstractSliderButton {
        public SliderButton(int i, int j, int k, int l, Component component, double d) {
            super(i, j, k, l, component, d);
        }


        @Override
        public void setValue(double d) {
            super.setValue(d);
        }
    }


    private EditBox createIntEditBox(Component name, IntConsumer consumer, IntSupplier supplier, int width, int min, int max) {
        return createIntEditBox(name, consumer, supplier, width, min, max, (max + min) / 2, Integer::parseInt, String::valueOf);
    }

    private EditBox createIntEditBox(Component name, IntConsumer consumer, IntSupplier supplier, int width, int min, int max, int defaultVal,
                                     ToIntFunction<String> parser, IntFunction<String> textBoxString) {
        var size = new EditBox(this.font, width, 20, name) {
            @Override
            public void setFocused(boolean bl) {
                super.setFocused(bl);
                if (!bl) {
                    this.setValue(textBoxString.apply(supplier.getAsInt()));
                }
            }
        };

        size.setCentered(true);

        size.setResponder((input) -> {
            try {
                var value = input.isEmpty() ? defaultVal : parser.applyAsInt(input);
                if (value < min || value > max) {
                    size.setTextColor(0xFFFFAAAA);
                } else {
                    size.setTextColor(0xFFFFFFFF);
                    if (value != supplier.getAsInt()) {
                        consumer.accept(value);
                    }
                }
            } catch (Exception e) {
                // Silence!
            }

        });

        size.setFilter((input) -> {
            if (input.isEmpty()) {
                return true;
            }
            try {
                var i = parser.applyAsInt(input);
                return true;
            } catch (Exception e) {
                if (min < 0 && input.length() == 1 && input.charAt(0) == '-') {
                    return true;
                }

                // Silence!
            }

            return false;
        });

        size.setValue(textBoxString.apply(Mth.clamp(supplier.getAsInt(), min, max)));
        return size;
    }

    private EditBox createDoubleEditBox(Component name, DoubleConsumer consumer, DoubleSupplier supplier, int width, double min, double max, DoubleFunction<String> rounder) {
        var size = new EditBox(this.font, width, 20, name) {
            @Override
            public void setFocused(boolean bl) {
                super.setFocused(bl);
                if (!bl) {
                    this.setValue(rounder.apply(supplier.getAsDouble()));
                }
            }
        };

        size.setCentered(true);

        size.setResponder((input) -> {
            try {
                var value = input.isEmpty() ? min : Double.parseDouble(input);
                if (value < min || value > max) {
                    size.setTextColor(0xFFFFAAAA);
                } else {
                    size.setTextColor(0xFFFFFFFF);
                    if (!rounder.apply(value).equals(rounder.apply(supplier.getAsDouble()))) {
                        consumer.accept(value);
                    }
                }
            } catch (Exception e) {
                // Silence!
            }

        });

        size.setFilter((input) -> {
            if (input.isEmpty()) {
                return true;
            }
            try {
                var i = Double.parseDouble(input);
                return true;
            } catch (Exception e) {
                if (min < 0 && input.length() == 1 && input.charAt(0) == '-') {
                    return true;
                }

                // Silence!
            }

            return false;
        });

        size.setValue(rounder.apply(supplier.getAsDouble()));
        return size;
    }

    private SliderButton createIntSlider(Component name, IntConsumer consumer, IntSupplier supplier, int width, int min, int max) {
        return createIntSlider(name, consumer, supplier, width, min, max, String::valueOf);
    }

    private SliderButton createIntSlider(Component name, IntConsumer consumer, IntSupplier supplier, int width, int min, int max, IntFunction<String> stringify) {
        return new SliderButton(0, 0, width, 20, Component.empty(), (Mth.clamp(supplier.getAsInt() - min, min, max) / (double) (max - min))) {
            {
                this.updateMessage();
            }

            protected void updateMessage() {
                this.setMessage(Component.empty().append(name).append(": " + stringify.apply(supplier.getAsInt())));
            }

            @Override
            protected void applyValue() {
                var value = Mth.lerpDiscrete((float) this.value, min, max);
                if (value != supplier.getAsInt()) {
                    consumer.accept(value);
                }
            }
        };
    }

    private SliderButton createDoubleSlider(Component name, DoubleConsumer consumer, DoubleSupplier supplier, int width, double min, double max, double step, DoubleFunction<String> stringify) {
        return new SliderButton(0, 0, width, 20, Component.empty(), ((supplier.getAsDouble() - min) / (max - min))) {
            {
                this.updateMessage();
            }

            protected void updateMessage() {
                this.setMessage(Component.empty().append(name).append(": " + stringify.apply(supplier.getAsDouble())));
            }

            @Override
            protected void applyValue() {
                var value = Math.round(Mth.lerp(this.value, min, max) / step) * step;
                if (value != supplier.getAsDouble()) {
                    consumer.accept(value);
                }
            }
        };
    }

    private SliderWithText createIntSliderWithText(Component name, IntConsumer consumer, IntSupplier supplier, int width, int width2, int min, int max) {
        return createIntSliderWithText(name, consumer, supplier, width, width2, min, max, String::valueOf);
    }

    private SliderWithText createIntSliderWithText(Component name, IntConsumer consumer, IntSupplier supplier, int width, int width2, int min, int max, IntFunction<String> display) {
        return createIntSliderWithText(name, consumer, supplier, width, width2, min, max, (min + max) / 2, display, Integer::parseInt, Integer::toString);
    }

    private SliderWithText createIntSliderWithText(Component name, IntConsumer consumer, IntSupplier supplier, int width, int width2, int min, int max, int defaultValue, IntFunction<String> display) {
        return createIntSliderWithText(name, consumer, supplier, width, width2, min, max, defaultValue, display, Integer::parseInt, Integer::toString);
    }

    private SliderWithText createIntSliderWithText(Component name, IntConsumer consumer, IntSupplier supplier, int width, int width2, int min, int max, int defaultValue,
                                                  IntFunction<String> display, ToIntFunction<String> parser, IntFunction<String> textBoxString) {
        var obj = new Object() {
            SliderButton slider;
            EditBox editBox;
        };

        obj.slider = createIntSlider(name, x -> {
            consumer.accept(x);
            obj.editBox.setValue(textBoxString.apply(x));
        }, supplier, width, min, max, display);

        obj.editBox = createIntEditBox(name, x -> {
            consumer.accept(x);
            obj.slider.setValue(((supplier.getAsInt() - min) / (double) (max - min)));
        }, supplier, width2, min, max, defaultValue, parser, textBoxString);

        var group = LinearLayout.horizontal().spacing(2);
        group.addChild(obj.slider);
        group.addChild(obj.editBox);

        return new SliderWithText(supplier, x -> {
            x = Mth.clamp(x, min, max);
            consumer.accept(x);
            obj.slider.setValue(((supplier.getAsInt() - min) / (double) (max - min)));
            obj.editBox.setValue(textBoxString.apply(x));
        }, obj.slider, obj.editBox, group);
    }

    public record SliderWithText(IntSupplier supplier, IntConsumer consumer, SliderButton slider, EditBox editBox, LayoutElement group) {
        public void update(int value) {
            this.consumer.accept(value);
        }

        int get() {
            return supplier.getAsInt();
        }
    }
}
