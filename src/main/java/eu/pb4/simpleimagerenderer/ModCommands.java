package eu.pb4.simpleimagerenderer;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
//import eu.pb4.polymer.core.impl.client.InternalClientItemGroup;
//import eu.pb4.polymer.core.impl.client.InternalClientRegistry;
import eu.pb4.simpleimagerenderer.renderer.*;
import eu.pb4.simpleimagerenderer.util.ClientEntityUtils;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.core.BlockBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static eu.pb4.simpleimagerenderer.util.RenderUtils.writeToNativeImage;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

class ModCommands {
    public static final Path MAIN_PATH = ModInit.MAIN_PATH;
    private static final boolean POLYMER = FabricLoader.getInstance().isModLoaded("polymer-core");

    static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext bctx) {
        //noinspection unchecked
        dispatcher.register(literal("render")
                .then(literal("item")
                        .then(argument("item", ItemArgument.item(bctx))
                                .executes(ctx -> renderItems(ctx, ItemArgument.getItem(ctx, "item").createItemStack(1, false)))
                        ).then(argument("id", ResourceOrTagArgument.resourceOrTag(bctx, Registries.ITEM))
                                .executes(ctx -> renderItems(ctx, ((ResourceOrTagArgument.Result<Item>) ResourceOrTagArgument.getResourceOrTag((CommandContext) ctx, "id", Registries.ITEM))
                                        .unwrap().map(Stream::<Holder<Item>>of, HolderSet::stream).map(ItemStack::new).toArray(ItemStack[]::new)))
                        ).then(literal("hand")
                                .executes(ctx -> renderItems(ctx, ctx.getSource().getPlayer().getMainHandItem()))
                        ).then(literal("creative").then(
                                argument("id", IdentifierArgument.id()).suggests(ModCommands::suggestCreativeTabs)
                                        .executes(ctx -> renderItems(ctx, getCreativeTabsItems(ctx.getArgument("id", Identifier.class))))
                        ))
                        .executes(ctx -> renderItems(ctx, ctx.getSource().getPlayer().getMainHandItem()))
                )
                .then(literal("entity")
                        .executes(ctx -> ModCommands.renderEntity(ctx, Objects.requireNonNull(ctx.getSource().getClient().crosshairPickEntity, "No selected entity!")))
                        .then(argument("entity", EntityArgument.entity())
                                .executes(ctx -> ModCommands.renderEntity(ctx,
                                        ClientEntityUtils.findEntities(ctx.getSource(), ctx.getArgument("entity", EntitySelector.class)).getFirst()))))
                .then(literal("block")
                        .executes(ctx -> ModCommands.renderBlockState(ctx, ctx.getSource().getLevel().getBlockState(
                                ctx.getSource().getClient().hitResult instanceof BlockHitResult result ? result.getBlockPos() : ctx.getSource().getPlayer().getOnPos()
                        )))
                        .then(argument("state", BlockStateArgument.block(bctx))
                                .executes(ctx -> ModCommands.renderBlockState(ctx, ctx.getArgument("state", BlockInput.class).getState()))))
                .then(literal("area").then(argument("start", BlockPosArgument.blockPos())
                        .then(argument("end", BlockPosArgument.blockPos()).executes(ModCommands::renderArea))))
                .then(literal("use_id_as_name").then(argument("value", BoolArgumentType.bool()).executes(ModCommands::setUseIdAsName)))
                .then(literal("open_folder").executes(ModCommands::openFolder))
        );
    }

    private static int openFolder(CommandContext<FabricClientCommandSource> ctx) {
        Util.getPlatform().openPath(MAIN_PATH);
        return 1;
    }

    private static int setUseIdAsName(CommandContext<FabricClientCommandSource> ctx) {
        ModInit.useIdAsName = BoolArgumentType.getBool(ctx, "value");
        ctx.getSource().sendFeedback(Component.translatable("text.simple_image_renderer.filename_use_" + (ModInit.useIdAsName ? "id" : "display_name")));
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
            if (POLYMER) {
                //if (x instanceof InternalClientItemGroup ex) {
                //    ids.add(ex.getIdentifier());
                //    continue;
                //}
            }
            ids.add(BuiltInRegistries.CREATIVE_MODE_TAB.getKey(x));
        }

        return SharedSuggestionProvider.suggestResource(ids, b);
    }

    private static int renderEntity(CommandContext<FabricClientCommandSource> ctx, Entity entity) throws CommandSyntaxException {
        var renderer = new EntityImageRenderer(ctx.getSource().getClient(), RendererSettings.defaultSettings.width, RendererSettings.defaultSettings.height, entity);
        openRendererScreen(renderer, (textureTarget, entityx, frame) -> {
            try {
                var itemName = entity.getDisplayName().getString();
                var name = ModInit.useIdAsName ? BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toDebugFileName() : itemName;
                var path = MAIN_PATH.resolve(name + ".png");
                writeToNativeImage(textureTarget, x -> x.writeToFile(path));
                ctx.getSource().sendFeedback(Component.literal("Saved " + itemName + " as ").append(Component.literal(path.toString())
                        .setStyle(Style.EMPTY.withUnderlined(true).withClickEvent(new ClickEvent.OpenFile(path)))));
            } catch (Throwable e) {
                e.printStackTrace();
            }
        });

        return 0;
    }

    private static int renderBlockState(CommandContext<FabricClientCommandSource> ctx, BlockState state) throws CommandSyntaxException {
        var renderer = new BlockImageRenderer(ctx.getSource().getClient(), RendererSettings.defaultSettings.width, RendererSettings.defaultSettings.height, state);
        openRendererScreen(renderer, (textureTarget, entityx, frame) -> {
            try {
                var itemName = state.getBlock().getName().getString();
                var name = ModInit.useIdAsName ? BuiltInRegistries.BLOCK.getKey(state.getBlock()).toDebugFileName() : itemName;
                var path = MAIN_PATH.resolve(name + ".png");
                writeToNativeImage(textureTarget, x -> x.writeToFile(path));
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

        var renderer = new RegionImageRenderer(ctx.getSource().getClient(), RendererSettings.defaultSettings.width, RendererSettings.defaultSettings.height, ctx.getSource().getWorld(),
                BlockBox.of(start, end), RendererSettings.defaultSettings.renderEdge, RendererSettings.defaultSettings.ignoreLighting);
        openRendererScreen(renderer, (textureTarget, entityx, frame) -> {
            try {
                var name = "area_" + Util.getFilenameFormattedDateTime();
                var path = MAIN_PATH.resolve(name + ".png");
                writeToNativeImage(textureTarget, x -> x.writeToFile(path));
                ctx.getSource().sendFeedback(Component.translatable("text.simple_image_renderer.saved_image_region",
                        Component.translatable("text.simple_image_renderer.region", start.toShortString(), end.toShortString()), Component.literal(path.toString())
                                .setStyle(Style.EMPTY.withUnderlined(true).withClickEvent(new ClickEvent.OpenFile(path)))));
            } catch (Throwable e) {
                e.printStackTrace();
            }
        });

        return 0;
    }

    private static <T> void openRendererScreen(AbstractImageRenderer<T> renderer, AbstractImageRenderer.RenderConsumer<T> consumer) {
        RendererSettings.defaultSettings.applyAll(renderer);
        Minecraft.getInstance().execute(() -> {
            Minecraft.getInstance().setScreen(new PreviewScreen<>(renderer, RendererSettings.defaultSettings.clone(), consumer));
        });
    }

    private static int renderItems(CommandContext<FabricClientCommandSource> ctx, ItemStack... items) {
        var renderer = new ItemImageRenderer(ctx.getSource().getClient(), RendererSettings.defaultSettings.width, RendererSettings.defaultSettings.height, List.of(items));

        openRendererScreen(renderer, (textureTarget, itemStack, frame) -> {
            try {
                var itemName = itemStack.getHoverName().getString();
                var name = ModInit.useIdAsName ? itemStack.getOrDefault(DataComponents.ITEM_MODEL, BuiltInRegistries.ITEM.getKey(itemStack.getItem())).toDebugFileName() : itemName;
                var path = MAIN_PATH.resolve(name + ".png");
                writeToNativeImage(textureTarget, x -> x.writeToFile(path));
                ctx.getSource().sendFeedback(Component.translatable("text.simple_image_renderer.saved_image", itemName, Component.literal(path.toString())
                        .setStyle(Style.EMPTY.withUnderlined(true).withClickEvent(new ClickEvent.OpenFile(path)))));
            } catch (Throwable e) {
                e.printStackTrace();
            }
        });

        return 0;
    }
}
