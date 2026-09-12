package eu.pb4.simpleimagerenderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

public record TransformingVertexConsumer(VertexConsumer delegate, PoseStack.Pose pose) implements VertexConsumer {
    private static final Vector3f VERTEX = new Vector3f();
    private static final Vector3f NORMAL = new Vector3f();

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        VERTEX.set(x, y, z);
        pose.pose().transformProject(VERTEX);
        this.delegate.addVertex(VERTEX.x, VERTEX.y, VERTEX.z);
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        this.delegate.setColor(r, g, b, a);
        return this;
    }

    @Override
    public VertexConsumer setColor(int color) {
        this.delegate.setColor(color);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        this.delegate.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        this.delegate.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        this.delegate.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv3(float u, float v) {
        this.delegate.setUv3(u, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        this.pose.transformNormal(x, y, z, NORMAL);
        this.delegate.setNormal(NORMAL.x, NORMAL.y, NORMAL.z);
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        this.delegate.setLineWidth(width);
        return this;
    }
}
