package dev.everview.mixin.client;

import dev.everview.client.EverviewFarPlane;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Raises Camera.depthFar immediately after vanilla computes it.
 *
 * Camera then uses the extended value for both createProjectionMatrixForCulling()
 * and setupPerspective(), so Everview's distant geometry can survive both the
 * frustum test and the actual world projection.
 */
@Mixin(Camera.class)
public abstract class CameraFarPlaneMixin {
    @Shadow
    private float depthFar;

    @Inject(
            method = "update",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/Camera;depthFar:F",
                    opcode = Opcodes.PUTFIELD,
                    shift = At.Shift.AFTER
            )
    )
    private void everview$extendDepthFar(DeltaTracker deltaTracker, CallbackInfo ci) {
        this.depthFar = EverviewFarPlane.extend(this.depthFar);
    }
}
