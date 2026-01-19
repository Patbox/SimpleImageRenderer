package eu.pb4.simpleimagerenderer;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import eu.pb4.simpleimagerenderer.renderer.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.core.BlockBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;


public class ModInit implements ClientModInitializer {
    public static final Logger LOGGER = LogManager.getLogger("SimpleImageRenderer");
    public static final String ID = "simple-image-renderer";
    private static final boolean POLYMER = FabricLoader.getInstance().isModLoaded("polymer-core");
    public static final Path MAIN_PATH = FabricLoader.getInstance().getGameDir().relativize(FabricLoader.getInstance().getGameDir()).resolve("simple-image-renderer");
    public static long glintTimeOverride = -1;
    @Nullable
    public static RenderTarget mainRenderTargetReplacement;
    private static boolean useIdAsName = false;
    public static RendererSettings settings = new RendererSettings();

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
                .then(literal("entity").then(argument("entity", EntityArgument.entity()).executes(ModInit::renderEntity)))
                .then(literal("block").then(argument("state", BlockStateArgument.block(bctx)).executes(ModInit::renderBlockState)))
                .then(literal("area").then(argument("start", BlockPosArgument.blockPos())
                                .then(argument("end", BlockPosArgument.blockPos()).executes(ModInit::renderArea))))
                .then(literal("use_id_as_name").then(argument("value", BoolArgumentType.bool()).executes(ModInit::setUseIdAsName)))
                .then(literal("open_folder").executes(ModInit::openFolder))
        );
    }

    private static int openFolder(CommandContext<FabricClientCommandSource> ctx) {
        Util.getPlatform().openPath(MAIN_PATH);
        return 1;
    }

    private static int setUseIdAsName(CommandContext<FabricClientCommandSource> ctx) {
        useIdAsName = BoolArgumentType.getBool(ctx, "value");
        ctx.getSource().sendFeedback(Component.translatable("text.simple_image_renderer.filename_use_" + (useIdAsName ? "id" : "display_name")));
        return 1;
    }

    private static ItemStack[] getCreativeTabsItems(Identifier identifier) {
        if (identifier.equals(CreativeModeTabs.INVENTORY.identifier())) {
            var list = new ArrayList<ItemStack>();
            for (var item : Minecraft.getInstance().player.getInventory()) {
                if (!item.isEmpty()) {
                    list.add(item);
                }
            }
            return list.toArray(ItemStack[]::new);
        }

        var tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(identifier);

        //if (POLYMER && tab == null) {
        //    tab = InternalClientRegistry.ITEM_GROUPS.get(identifier);
        //}

        if (tab != null) {
            if (!tab.hasAnyItems()) {
                var level = Minecraft.getInstance().level;
                CreativeModeTabs.tryRebuildTabContents(level.enabledFeatures(), false, level.registryAccess());
            }

            return tab.getDisplayItems().toArray(ItemStack[]::new);
        }

        return new ItemStack[0];
    }

    private static CompletableFuture<Suggestions> suggestCreativeTabs(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder b) {
        var ids = new ArrayList<Identifier>();
        for (var x : CreativeModeTabs.allTabs()) {
            //if (POLYMER) {
            //    if (x instanceof InternalClientItemGroup ex) {
            //        ids.add(ex.getIdentifier());
            //        continue;
            //    }
            //}
            ids.add(BuiltInRegistries.CREATIVE_MODE_TAB.getKey(x));
        }

        return SharedSuggestionProvider.suggestResource(ids, b);
    }

    private static int renderEntity(CommandContext<FabricClientCommandSource> ctx) throws CommandSyntaxException {
        var entity = ClientEntityUtils.findEntities(ctx.getSource(), ctx.getArgument("entity", EntitySelector.class)).getFirst();
        var renderer = new EntityImageRenderer(ctx.getSource().getClient(), settings.width, settings.height, entity);
        openRendererScreen(renderer, (textureTarget, entityx) -> {
            try {
                var itemName = entity.getDisplayName().getString();
                var name = useIdAsName ? BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toDebugFileName() : itemName;
                var path = MAIN_PATH.resolve(name + ".png");
                writeToImage(textureTarget, x -> x.writeToFile(path));
                ctx.getSource().sendFeedback(Component.literal("Saved " + itemName + " as ").append(Component.literal(path.toString())
                        .setStyle(Style.EMPTY.withUnderlined(true).withClickEvent(new ClickEvent.OpenFile(path)))));
            } catch (Throwable e) {
                e.printStackTrace();
            }
        });

        return 0;
    }

    private static int renderBlockState(CommandContext<FabricClientCommandSource> ctx) throws CommandSyntaxException {
        var state = ctx.getArgument("state", BlockInput.class).getState();

        var renderer = new BlockImageRenderer(ctx.getSource().getClient(), settings.width, settings.height, state);
        openRendererScreen(renderer, (textureTarget, entityx) -> {
            try {
                var itemName = state.getBlock().getName().getString();
                var name = useIdAsName ? BuiltInRegistries.BLOCK.getKey(state.getBlock()).toDebugFileName() : itemName;
                var path = MAIN_PATH.resolve(name + ".png");
                writeToImage(textureTarget, x -> x.writeToFile(path));
                ctx.getSource().sendFeedback(Component.literal("Saved " + itemName + " as ").append(Component.literal(path.toString())
                        .setStyle(Style.EMPTY.withUnderlined(true).withClickEvent(new ClickEvent.OpenFile(path)))));
            } catch (Throwable e) {
                e.printStackTrace();
            }
        });

        return 0;
    }

    private static int renderArea(CommandContext<FabricClientCommandSource> ctx) {
        var start = BlockPos.containing(ClientEntityUtils.getPos(ctx.getSource().getPosition(), ctx.getSource().getRotation(), ctx.getArgument("start", Coordinates.class)));
        var end = BlockPos.containing(ClientEntityUtils.getPos(ctx.getSource().getPosition(), ctx.getSource().getRotation(), ctx.getArgument("end", Coordinates.class)));

        var renderer = new RegionImageRenderer(ctx.getSource().getClient(), settings.width, settings.height, ctx.getSource().getLevel(), BlockBox.of(start, end));
        openRendererScreen(renderer, (textureTarget, entityx) -> {
            try {
                var name = "area_" + Util.getFilenameFormattedDateTime();
                var path = MAIN_PATH.resolve(name + ".png");
                writeToImage(textureTarget, x -> x.writeToFile(path));
                ctx.getSource().sendFeedback(Component.translatable("text.simple_image_renderer.saved_image_region",
                        Component.translatable("text.simple_image_renderer.region", start.toShortString(), end.toShortString()), Component.literal(path.toString())
                        .setStyle(Style.EMPTY.withUnderlined(true).withClickEvent(new ClickEvent.OpenFile(path)))));
            } catch (Throwable e) {
                e.printStackTrace();
            }
        });

        return 0;
    }

    private static <T> void openRendererScreen(AbstractImageRenderer<T> renderer, BiConsumer<TextureTarget, T> consumer) {
        settings.applyAll(renderer);
        Minecraft.getInstance().execute(() -> {
            Minecraft.getInstance().setScreen(new PreviewScreen<>(renderer, settings.clone(), consumer));
        });
    }

    private static int renderItems(CommandContext<FabricClientCommandSource> ctx, ItemStack... items) {
        var renderer = new ItemImageRenderer(ctx.getSource().getClient(), settings.width, settings.height, List.of(items));

        openRendererScreen(renderer, (textureTarget, itemStack) -> {
            try {
                var itemName = itemStack.getHoverName().getString();
                var name = useIdAsName ? itemStack.getOrDefault(DataComponents.ITEM_MODEL, BuiltInRegistries.ITEM.getKey(itemStack.getItem())).toDebugFileName() : itemName;
                var path = MAIN_PATH.resolve(name + ".png");
                writeToImage(textureTarget, x -> x.writeToFile(path));
                ctx.getSource().sendFeedback(Component.translatable("text.simple_image_renderer.saved_image", itemName, Component.literal(path.toString())
                        .setStyle(Style.EMPTY.withUnderlined(true).withClickEvent(new ClickEvent.OpenFile(path)))));
            } catch (Throwable e) {
                e.printStackTrace();
            }
        });

        return 0;
    }

    public static void writeToImage(final RenderTarget target, ThrowingConsumer<NativeImage> imageConsumer) {
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

                                    imageConsumer.accept(image);
                                } catch (Throwable e) {
                                    LOGGER.error("Image handling failer", e);
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

    @Override
    public void onInitializeClient() {
        try {
            Files.createDirectories(MAIN_PATH);
        } catch (Throwable e) {
        }
        ClientCommandRegistrationCallback.EVENT.register(ModInit::registerCommands);
    }
}
