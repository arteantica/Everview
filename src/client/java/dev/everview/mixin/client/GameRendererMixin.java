package dev.everview.mixin.client;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Everview renders a world-scale horizon, so vanilla's short-distance WORLD
 * fog defeats the purpose by hiding the LODs long before the extended far
 * plane. Redirect the terrain fog buffer to FogMode.NONE. Weather rendering is
 * untouched; this only removes the world-distance fog applied to terrain.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Redirect(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/fog/FogRenderer;getBuffer(Lnet/minecraft/client/renderer/fog/FogRenderer$FogMode;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"
            )
    )
    private GpuBufferSlice everview$disableWorldFog(
            FogRenderer fogRenderer,
            FogRenderer.FogMode mode
    ) {
        return fogRenderer.getBuffer(FogRenderer.FogMode.NONE);
    }
}
