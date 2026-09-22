package dev.everview.mixin.client;

import com.mojang.renderpearl.api.commands.RenderPass;
import dev.everview.client.EverviewRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 26.3 keeps the opaque terrain RenderPass open while Fabric fires
 * AFTER_OPAQUE_TERRAIN. The public Fabric context intentionally does not expose
 * that RenderPass, so Everview injects immediately after vanilla opaque chunks
 * and reuses the existing pass instead of illegally nesting another pass.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(
            method = "executeSolid",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V",
                    shift = At.Shift.AFTER
            )
    )
    private void everview$drawPersistentTerrain(
            ChunkSectionsToRender chunkSectionsToRender,
            FeatureRenderDispatcher.PreparedFrame featureFrame,
            RenderPass renderPass,
            CallbackInfo ci
    ) {
        EverviewRenderer.drawPersistentTerrain(renderPass);
    }
}
