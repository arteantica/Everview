package dev.everview.mixin.client;

import com.mojang.renderpearl.api.commands.RenderPass;
import dev.everview.client.EverviewRenderer;
import dev.everview.client.VanillaTerrainReadiness;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
public abstract class LevelRendererMixin implements VanillaTerrainReadiness {
    @Shadow private ViewArea viewArea;
    @Shadow private SectionRenderDispatcher sectionRenderDispatcher;

    @Override public boolean everview$terrainUploaded(BlockPos position) {
        if (viewArea == null || sectionRenderDispatcher == null) return false;
        var section = viewArea.getRenderSectionAt(position);
        if (section == null) return false;
        sectionRenderDispatcher.lock();
        try {
            var mesh = section.getSectionMesh();
            if (mesh == CompiledSectionMesh.UNCOMPILED) return false;
            // Match extractSectionDrawGroups, including the translucent water layer.
            for (var layer : ChunkSectionLayer.values()) {
                var draw = mesh.getSectionDraw(layer);
                if (draw == null || draw.indexCount() == 0) continue;
                var buffers = sectionRenderDispatcher.getRenderSectionSlice(mesh, layer);
                if (buffers == null || buffers.vertexBuffer() == null
                        || (draw.hasCustomIndexBuffer() && buffers.indexBuffer() == null)) return false;
            }
            return true;
        } finally { sectionRenderDispatcher.unlock(); }
    }

    // Both vanilla and Sodium have finalized their uploads/draw preparation here.
    // COLLECT_SUBMITS is earlier and can otherwise leave a one-frame stale mask.
    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;addMainPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Z)V"))
    private void everview$finalizeOwnership(CallbackInfo ci) {
        EverviewRenderer.prepareVanillaOwnership();
    }
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
