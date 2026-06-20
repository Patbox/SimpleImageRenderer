package eu.pb4.simpleimagerenderer.mixin;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.gen.Accessor;

@org.spongepowered.asm.mixin.Mixin(net.minecraft.client.Camera.class)
public interface CameraAccessor {
    @Accessor
    Quaternionf getRotation();

    @Accessor
    static Vector3fc getFORWARDS() {
        throw new UnsupportedOperationException();
    }

    @Accessor
    static Vector3fc getUP() {
        throw new UnsupportedOperationException();
    }

    @Accessor
    static Vector3fc getLEFT() {
        throw new UnsupportedOperationException();
    }

    @Accessor
    Vector3f getLeft();

    @Accessor
    Vector3f getUp();

    @Accessor
    Vector3f getForwards();
}
