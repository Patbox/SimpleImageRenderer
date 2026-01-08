package eu.pb4.simpleimagerenderer.mixin;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.advancements.criterion.MinMaxBounds;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

@Mixin(EntitySelector.class)
public interface EntitySelectorAccessor {
    @Accessor
    List<Predicate<Entity>> getContextFreePredicates();

    @Accessor
    int getMaxResults();

    @Accessor
    boolean isIncludesEntities();

    @Accessor
    boolean isWorldLimited();

    @Accessor
    MinMaxBounds.@Nullable Doubles getRange();

    @Accessor
    Function<Vec3, Vec3> getPosition();

    @Accessor
    AABB getAabb();

    @Accessor
    BiConsumer<Vec3, List<? extends Entity>> getOrder();

    @Accessor
    boolean isCurrentEntity();

    @Accessor
    String getPlayerName();

    @Accessor
    UUID getEntityUUID();

    @Accessor
    boolean isUsesSelector();

    @Accessor
    EntityTypeTest<Entity, ?> getType();

    @Invoker
    AABB callGetAbsoluteAabb(Vec3 vec3);

    @Invoker
    <T> List<T> callSortAndLimit(Vec3 vec3, List<T> list);

    @Invoker
    Predicate<Entity> callGetPredicate(Vec3 vec3, @Nullable AABB aABB, @Nullable FeatureFlagSet featureFlagSet);

    @Invoker
    int callGetResultLimit();

    @Invoker
    void callAddEntities(List<Entity> list, ServerLevel serverLevel, @Nullable AABB aABB, Predicate<Entity> predicate);

    @Invoker
    void callCheckPermissions(CommandSourceStack commandSourceStack) throws CommandSyntaxException;
}
