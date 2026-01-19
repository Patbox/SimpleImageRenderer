package eu.pb4.simpleimagerenderer.util;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import eu.pb4.simpleimagerenderer.mixin.ClientLevelAccessor;
import eu.pb4.simpleimagerenderer.mixin.EntitySelectorAccessor;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.LocalCoordinates;
import net.minecraft.commands.arguments.coordinates.WorldCoordinate;
import net.minecraft.commands.arguments.coordinates.WorldCoordinates;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

public class ClientEntityUtils {
    public static List<? extends Entity> findEntities(final FabricClientCommandSource sender, EntitySelector selector) throws CommandSyntaxException {
        var self = (EntitySelectorAccessor) selector;

        if (self.getPlayerName() != null) {
            var result = sender.getWorld().players().stream().filter(x -> x.nameAndId().name().equals(self.getPlayerName())).findAny().orElse(null);
            return result == null ? List.of() : List.of(result);
        } else if (self.getEntityUUID() != null) {
            Entity entity = sender.getWorld().getEntity(self.getEntityUUID());
            if (entity != null) {
                if (entity.getType().isEnabled(sender.enabledFeatures())) {
                    return List.of(entity);
                }
            }

            return List.of();
        } else {
            Vec3 pos = self.getPosition().apply(sender.getPosition());
            AABB absoluteAabb = self.callGetAbsoluteAabb(pos);
            if (self.isCurrentEntity()) {
                Predicate<Entity> predicate = self.callGetPredicate(pos, absoluteAabb, null);
                return sender.getEntity() != null && predicate.test(sender.getEntity()) ? List.of(sender.getEntity()) : List.of();
            } else {
                Predicate<Entity> predicate = self.callGetPredicate(pos, absoluteAabb, sender.enabledFeatures());
                List<Entity> result = new ObjectArrayList();
                addEntities(self, result, sender.getWorld(), absoluteAabb, predicate);

                return self.callSortAndLimit(pos, result);
            }
        }
    }

    private static void addEntities(EntitySelectorAccessor self, final List<Entity> result, final ClientLevel level, final @Nullable AABB absoluteAABB, final Predicate<Entity> predicate) {
        int limit = self.callGetResultLimit();
        if (result.size() < limit) {
            if (absoluteAABB != null) {
                level.getEntities(self.getType(), absoluteAABB, predicate, result, limit);
            } else {
                ((ClientLevelAccessor) level).callGetEntities().get(self.getType(), (entity) -> {
                    if (predicate.test(entity)) {
                        result.add(entity);
                        if (result.size() >= limit) {
                            return AbortableIterationConsumer.Continuation.ABORT;
                        }
                    }

                    return AbortableIterationConsumer.Continuation.CONTINUE;
                });
            }
        }
    }

    public static Vec3 getPos(Vec3 position, Vec2 rotation, Coordinates coords) {
        if (coords instanceof LocalCoordinates(double left, double up, double forwards)) {
            return Vec3.applyLocalCoordinatesToRotation(rotation, new Vec3(left, up, forwards)).add(position);
        } else if (coords instanceof WorldCoordinates(WorldCoordinate x, WorldCoordinate y, WorldCoordinate z)) {
            return new Vec3(x.get(position.x), y.get(position.y), z.get(position.z));
        }
        return position;
    }
}
