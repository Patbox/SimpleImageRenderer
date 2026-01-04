package eu.pb4.simpleimagerenderer;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import eu.pb4.polymer.core.impl.client.InternalClientItemGroup;
import eu.pb4.polymer.core.impl.client.InternalClientRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.core.Rotations;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class ModInit implements ClientModInitializer {
    public static final Logger LOGGER = LogManager.getLogger("SimpleImageRenderer");
    public static final String ID = "simple-image-renderer";
    private static final boolean POLYMER = FabricLoader.getInstance().isModLoaded("polymer-core");
    private static boolean useIdAsName = false;
    private static int imageWidth = 64;

    private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext bctx) {
        dispatcher.register(literal("render")
                .then(literal("item").then(
                                argument("item", ItemArgument.item(bctx))
                                        .executes(ctx -> renderItems(ctx, ItemArgument.getItem(ctx, "item").createItemStack(1, false)))
                        ).then(literal("hand")
                                .executes(ctx -> renderItems(ctx, ctx.getSource().getPlayer().getMainHandItem()))
                        ).then(literal("creative").then(
                                argument("id", IdentifierArgument.id()).suggests(ModInit::suggestCreativeTabs)
                                        .executes(ctx -> renderItems(ctx, getCreativeTabsItems(ctx.getArgument("id", Identifier.class))))
                        ))
                )
                //.then(literal("entity").then(argument("entity", EntityArgument.entity()).executes(ModInit::renderEntity)))
                .then(literal("size").then(argument("size", IntegerArgumentType.integer(16)).executes(ModInit::setWidth)))
                .then(literal("use_id_as_name").then(argument("value", BoolArgumentType.bool()).executes(ModInit::setUseIdAsName)))
        );
    }

    private static int setUseIdAsName(CommandContext<FabricClientCommandSource> ctx) {
        useIdAsName = BoolArgumentType.getBool(ctx, "value");
        ctx.getSource().sendFeedback(Component.literal(useIdAsName ? "Images will be now saved using their id as a filename." : "Images will be now saved using their item name as a filename."));
        return 1;
    }

    private static int setWidth(CommandContext<FabricClientCommandSource> ctx) {
        imageWidth = IntegerArgumentType.getInteger(ctx, "size");
        ctx.getSource().sendFeedback(Component.literal("Image dimensions are set to " + imageWidth + "x" + imageWidth));

        return 1;
    }

    private static ItemStack[] getCreativeTabsItems(Identifier identifier) {
        var tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(identifier);

        if (POLYMER && tab == null) {
            tab = InternalClientRegistry.ITEM_GROUPS.get(identifier);
        }

        if (tab != null) {
            return tab.getDisplayItems().toArray(ItemStack[]::new);
        }
        return new ItemStack[0];
    }

    private static CompletableFuture<Suggestions> suggestCreativeTabs(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder b) {
        var ids = new ArrayList<Identifier>();
        for (var x : CreativeModeTabs.allTabs()) {
            if (POLYMER) {
                if (x instanceof InternalClientItemGroup ex) {
                    ids.add(ex.getIdentifier());
                    continue;
                }
            }
            ids.add(BuiltInRegistries.CREATIVE_MODE_TAB.getKey(x));
        }

        return SharedSuggestionProvider.suggestResource(ids, b);
    }

    private interface RenderCallback {
        void render(Minecraft minecraft, RenderTarget renderTarget, PoseStack poseStack, FeatureRenderDispatcher dispatcher, MultiBufferSource.BufferSource bufferSource, Path mainPath);
    }

    private static int renderEntity(CommandContext<FabricClientCommandSource> ctx) {
        var entity = ctx.getSource().getEntity();// ctx.getArgument("entity", EntitySelector.class);

        return renderAndSave(ctx, (minecraft, renderTarget, poseStack, dispatcher, bufferSource, mainPath) ->  {
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(renderTarget.getColorTexture(), 0, renderTarget.getDepthTexture(), 1);
            minecraft.gameRenderer.getLighting().setupFor(Lighting.Entry.ENTITY_IN_UI);

            poseStack.pushPose();
            poseStack.translate(imageWidth / 2.0, imageWidth / 1.1f, 256);
            poseStack.scale(imageWidth, -imageWidth, imageWidth);
            var maxDim = 1 / (Math.max(entity.getBbHeight(), entity.getBbWidth()) + 0.5f);
            poseStack.scale(maxDim, maxDim, maxDim);
            poseStack.mulPose(new Quaternionf().rotateY(-entity.getYRot() * Mth.DEG_TO_RAD));

            var state = minecraft.getEntityRenderDispatcher().extractEntity(entity, 0);
            minecraft.getEntityRenderDispatcher().submit(state, new CameraRenderState(), 0, 0, 0, poseStack, dispatcher.getSubmitNodeStorage());

            dispatcher.renderAllFeatures();
            dispatcher.endFrame();
            bufferSource.endBatch();
            var itemName = entity.getDisplayName().getString();
            var name = useIdAsName ? BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toDebugFileName() : itemName;
            var path = mainPath.resolve(name + ".png");
            writeFile(renderTarget, mainPath.resolve(name + ".png"));
            ctx.getSource().sendFeedback(Component.literal("Saved " + itemName + " as ").append(Component.literal(path.toString())
                    .setStyle(Style.EMPTY.withUnderlined(true).withClickEvent(new ClickEvent.OpenFile(path)))));

            poseStack.popPose();
        });
    }

    private static int renderItems(CommandContext<FabricClientCommandSource> ctx, ItemStack... items) {
        return renderAndSave(ctx, (minecraft, renderTarget, poseStack, dispatcher, bufferSource, mainPath) ->  {
            for (var itemStack : items) {
                if (itemStack.isEmpty()) {
                    continue;
                }
                poseStack.pushPose();
                poseStack.translate(imageWidth / 2.0, imageWidth / 2.0, 256);
                poseStack.scale(imageWidth, -imageWidth, imageWidth);
                try {
                    RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(renderTarget.getColorTexture(), 0, renderTarget.getDepthTexture(), 1);

                    var state = new TrackingItemStackRenderState();
                    minecraft.getItemModelResolver().updateForTopItem(state, itemStack, ItemDisplayContext.GUI, ctx.getSource().getWorld(), null, 0);
                    minecraft.gameRenderer.getLighting().setupFor(state.usesBlockLight() ? Lighting.Entry.ITEMS_3D : Lighting.Entry.ITEMS_FLAT);

                    state.submit(poseStack, dispatcher.getSubmitNodeStorage(), 15728880, OverlayTexture.NO_OVERLAY, 0);

                    dispatcher.renderAllFeatures();
                    dispatcher.endFrame();
                    bufferSource.endBatch();
                    var itemName = itemStack.getHoverName().getString();
                    var name = useIdAsName ? itemStack.getOrDefault(DataComponents.ITEM_MODEL, BuiltInRegistries.ITEM.getKey(itemStack.getItem())).toDebugFileName() : itemName;
                    var path = mainPath.resolve(name + ".png");
                    writeFile(renderTarget, mainPath.resolve(name + ".png"));
                    ctx.getSource().sendFeedback(Component.literal("Saved " + itemName + " as ").append(Component.literal(path.toString())
                            .setStyle(Style.EMPTY.withUnderlined(true).withClickEvent(new ClickEvent.OpenFile(path)))));
                } catch (Throwable e) {
                    e.printStackTrace();
                }
                poseStack.popPose();
            }
        });
    }
    private static int renderAndSave(CommandContext<FabricClientCommandSource> ctx, RenderCallback callback) {
        var mainPath = FabricLoader.getInstance().getGameDir().relativize(FabricLoader.getInstance().getGameDir()).resolve("simple-image-renderer");
        try {
            Files.createDirectories(mainPath);
        } catch (Throwable e) {
            return 0;
        }

        var minecraft = ctx.getSource().getClient();
        var renderTarget = new TextureTarget("image_out", imageWidth, imageWidth, true);
        var renderDispatcher = minecraft.gameRenderer.getFeatureRenderDispatcher();
        var bufferSource = minecraft.renderBuffers().bufferSource();
        var matrix = new Matrix4f().setOrtho(0.0F, imageWidth, imageWidth, 0.0F, -1000.0F, 1000.0F);
        var perspective = new PerspectiveProjectionMatrixBuffer("render");

        var oldOutputColor = RenderSystem.outputColorTextureOverride;
        var oldOutputDepth = RenderSystem.outputDepthTextureOverride;

        RenderSystem.outputColorTextureOverride = renderTarget.getColorTextureView();
        RenderSystem.outputDepthTextureOverride = renderTarget.getDepthTextureView();
        RenderSystem.setProjectionMatrix(perspective.getBuffer(matrix), ProjectionType.ORTHOGRAPHIC);
        var poseStack = new PoseStack();
        try {
            callback.render(minecraft, renderTarget, poseStack, renderDispatcher, bufferSource, mainPath);
        } catch (Throwable e) {
            e.printStackTrace();
        }

        RenderSystem.outputColorTextureOverride = oldOutputColor;
        RenderSystem.outputDepthTextureOverride = oldOutputDepth;

        perspective.close();

        return 1;
    }

    public static void writeFile(final RenderTarget target, Path path) {
        int width = target.width;
        int height = target.height;
        GpuTexture sourceTexture = target.getColorTexture();
        if (sourceTexture == null) {
            throw new IllegalStateException("Tried to capture screenshot of an incomplete framebuffer");
        } else {
            GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Screenshot buffer", 9, (long) width * height * sourceTexture.getFormat().pixelSize());
            CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
            RenderSystem.getDevice()
                    .createCommandEncoder()
                    .copyTextureToBuffer(
                            sourceTexture,
                            buffer,
                            0L,
                            () -> {
                                try (GpuBuffer.MappedView read = commandEncoder.mapBuffer(buffer, true, false);
                                     NativeImage image = new NativeImage(width, height, false)
                                ) {
                                    for (int y = 0; y < height; y++) {
                                        for (int x = 0; x < width; x++) {
                                            int argb = read.data().getInt((x + y * width) * sourceTexture.getFormat().pixelSize());
                                            image.setPixelABGR(x, height - y - 1, argb);
                                        }
                                    }

                                    image.writeToFile(path);
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }

                                buffer.close();
                            },
                            0
                    );
        }
    }

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register(ModInit::registerCommands);
    }
}
