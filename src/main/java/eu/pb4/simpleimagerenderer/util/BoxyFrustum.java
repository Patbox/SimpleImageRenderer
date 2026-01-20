package eu.pb4.simpleimagerenderer.util;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockBox;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

public class BoxyFrustum extends Frustum {
    private final AABB area;

    public BoxyFrustum(BlockBox area) {
        super(new Matrix4f(), new Matrix4f());
        this.area = area.aabb();
    }

    @Override
    public boolean isVisible(AABB aABB) {
        return this.area.intersects(aABB);
    }

    @Override
    public int cubeInFrustum(BoundingBox boundingBox) {
        return this.area.intersects(AABB.of(boundingBox)) ? 1 : 0;
    }

    @Override
    public boolean pointInFrustum(double d, double e, double f) {
        return this.area.contains(d, e, f);
    }
}