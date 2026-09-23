package dev.everview.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
    @ModifyVariable(
            method = "getBuffer",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private FogRenderer.FogMode everview$forceNoWorldFog(
            FogRenderer.FogMode mode
    ) {
        return mode == FogRenderer.FogMode.WORLD
                ? FogRenderer.FogMode.NONE
                : mode;
    }

    @Inject(method = "setupFog", at = @At("RETURN"))
    private void everview$clearAtmosphericFogDistances(
            Camera camera,
            int renderDistanceInChunks,
            DeltaTracker deltaTracker,
            float darkenWorldAmount,
            ClientLevel level,
            CallbackInfoReturnable<FogData> cir
    ) {
        if (camera.getFluidInCamera() != FogType.NONE) {
            return;
        }

        FogData fog = cir.getReturnValue();
        fog.environmentalStart = Float.MAX_VALUE;
        fog.environmentalEnd = Float.MAX_VALUE;
        fog.renderDistanceStart = Float.MAX_VALUE;
        fog.renderDistanceEnd = Float.MAX_VALUE;
        fog.skyEnd = Float.MAX_VALUE;
        fog.cloudEnd = Float.MAX_VALUE;
    }
}
