package dev.everview.client;

import dev.everview.core.ClipmapLayout;
import dev.everview.core.LodRing;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Client runtime state and instrumentation.
 */
public final class EverviewRuntime {
    public static final int TARGET_DISTANCE_BLOCKS = 65_536;
    public static final ClipmapLayout CLIPMAP = new ClipmapLayout(TARGET_DISTANCE_BLOCKS, 256, 64);

    private static long worldRenderCallbacks;
    private static double renderMs;
    private static int validSamples;
    private static int triangles;

    private EverviewRuntime() {
    }

    public static void render(RenderLevelStageEvent event) {
        long start = System.nanoTime();
        worldRenderCallbacks++;

        LoadedSurfaceRenderer.RenderStats stats = LoadedSurfaceRenderer.render(event);
        validSamples = stats.validSamples();
        triangles = stats.triangles();

        renderMs = (System.nanoTime() - start) / 1_000_000.0;
    }

    public static String[] debugLines() {
        Minecraft mc = Minecraft.getInstance();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        int ringCount = CLIPMAP.rings().size();
        LodRing far = CLIPMAP.rings().get(ringCount - 1);

        return new String[] {
                "Everview 0.0.1-alpha | M1 smoke test",
                "Target LOD: " + TARGET_DISTANCE_BLOCKS + " blocks (" + (TARGET_DISTANCE_BLOCKS / 16) + " chunks)",
                "Clipmap rings: " + ringCount + " | far spacing: " + far.sampleSpacing() + " blocks",
                String.format("Camera: %.1f / %.1f / %.1f", camera.x, camera.y, camera.z),
                String.format("M1 mesh: %d samples | %d triangles", validSamples, triangles),
                String.format("Everview CPU submit: %.3f ms | frames: %d", renderMs, worldRenderCallbacks),
                "Source: loaded client chunks only (temporary)"
        };
    }
}
