package eu.pb4.simpleimagerenderer.util;

import eu.pb4.simpleimagerenderer.mixin.CameraAccessor;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

public class ManualCamera extends Camera {

    @Override
    public void setPosition(Vec3 vec3) {
        super.setPosition(vec3);
    }

    @Override
    public void setRotation(float f, float g) {
        super.setRotation(f, g);
    }


    public void setRotation(Quaternionf quaternionf) {
        var acc = (CameraAccessor) this;

        acc.getRotation().set(quaternionf);
        CameraAccessor.getFORWARDS().rotate(quaternionf, acc.getForwards());
        CameraAccessor.getUP().rotate(quaternionf, acc.getUp());
        CameraAccessor.getLEFT().rotate(quaternionf, acc.getLeft());
    }
}
