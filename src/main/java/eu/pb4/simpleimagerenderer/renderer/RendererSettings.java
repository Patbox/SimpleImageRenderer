package eu.pb4.simpleimagerenderer.renderer;

import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

public class RendererSettings implements Cloneable {
    public static RendererSettings defaultSettings = new RendererSettings();

    public int width = 512;
    public int height = 512;
    public int yaw = 0;
    public int pitch = 0;
    public int roll = 0;
    public int scale = 100;
    public int x = 0;
    public int y = 0;
    public int z = 0;

    public AbstractImageRenderer.LightingType lightingType = AbstractImageRenderer.LightingType.DEFAULT;
    public boolean multiplyNormals = true;

    // Entity
    public double age = -1;
    public double walkAnimationSpeed = -1;
    public double walkAnimationPos = -1;

    // Item
    public ItemDisplayContext context = ItemDisplayContext.GUI;

    // Region
    public boolean renderEntities = true;
    public boolean renderNametags = true;
    public boolean renderSelf = true;



    public void updateMatrix(AbstractImageRenderer<?> renderer) {
        renderer.updateMatrix(new Matrix4f()
                        .translate(x / 1000f * renderer.width, y / 1000f * renderer.height, z / 1000f * renderer.width)
                        .scale(this.scale / 100f)
                .rotateXYZ(this.pitch * Mth.DEG_TO_RAD, this.yaw * Mth.DEG_TO_RAD, this.roll * Mth.DEG_TO_RAD),
                new Quaternionf().rotateZYX(this.roll * Mth.DEG_TO_RAD, -this.yaw * Mth.DEG_TO_RAD, this.pitch * Mth.DEG_TO_RAD)
        );
    }

    public void applyAll(AbstractImageRenderer<?> renderer) {
        updateMatrix(renderer);
        renderer.setupTexture(width, height);
        renderer.setLightingType(this.lightingType);
        renderer.setMultiplyNormals(this.multiplyNormals);

        if (renderer instanceof ItemImageRenderer renderer1) {
            renderer1.setDisplayContext(this.context);
        }

        if (renderer instanceof EntityImageRenderer renderer1) {
            renderer1.setAge((float) this.age);
            renderer.setGlintTime((long) (this.age * 1000L / 20));
            renderer1.setWalkAnimationPos((float) this.walkAnimationPos);
            renderer1.setWalkAnimationSpeed((float) this.walkAnimationSpeed);
        }

        if (renderer instanceof RegionImageRenderer renderer1) {
            renderer1.setRenderNametags(this.renderNametags);
            renderer1.setRenderSelf(this.renderSelf);
            renderer1.setRenderEntities(this.renderEntities);
        }
    }

    @Override
    public RendererSettings clone() {
        try {
            return (RendererSettings) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }
}
