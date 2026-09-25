package dev.everview.client;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opaque terrain pipeline seam for future material/shader integration. Backend hooks
 * run in the existing opaque pass; uploads run before it. The default backend uses
 * Minecraft's position/color pipeline. Shader-pack support itself is not implemented.
 */
public interface EverviewTerrainBackend {
    AtomicReference<EverviewTerrainBackend> ACTIVE =
            new AtomicReference<>(new EverviewTerrainBackend() {
                @Override public RenderPipeline pipeline() { return EverviewGpuPipeline.TERRAIN; }
            });

    RenderPipeline pipeline();

    default void regionUploaded(EverviewGpuRegionCache.GpuRegion region) {}
    default void regionRetired(EverviewGpuRegionCache.GpuRegion region) {}
    default void bindRegion(RenderPass pass, EverviewRenderState.RegionCommands commands) {}
    default void bindStitches(RenderPass pass, EverviewGpuRegionCache.StitchBuffer stitches) {}

    static EverviewTerrainBackend active() { return ACTIVE.get(); }
    static void install(EverviewTerrainBackend backend) { ACTIVE.set(Objects.requireNonNull(backend)); }
}
