package dev.everview.client;

import dev.everview.core.ClipmapLayout;
import dev.everview.core.LodRing;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Client runtime state. v0.0.1 intentionally contains no terrain mesh generation yet;
 * it establishes the clipmap plan and instrumentation we will benchmark against.
 */
public final class EverviewRuntime {
    public static final int TARGET_DISTANCE_BLOCKS = 65_536;
    public static final ClipmapLayout CLIPMAP = new ClipmapLayout(TARGET_DISTANCE_BLOCKS, 256, 64);

    private static long worldRenderCallbacks;
    private static long lastRenderNanos;
    private static double callbackMs;

    private EverviewRuntime() {
    }

    public static void recordWorldRenderCallback() {
        long start = System.nanoTime();
        worldRenderCallbacks++;

        // Placeholder for the future GPU submission path.
        // Keeping this hook effectively free gives us a clean baseline.

        lastRenderNanos = System.nanoTime();
        callbackMs = (lastRenderNanos - start) / 1_000_000.0;
    }

    public static String[] debugLines() {
        Minecraft mc = Minecraft.getInstance();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        int ringCount = CLIPMAP.rings().size();
        LodRing far = CLIPMAP.rings().get(ringCount - 1);

        return new String[] {
                "Everview 0.0.1-alpha",
                "Target LOD: " + TARGET_DISTANCE_BLOCKS + " blocks (" + (TARGET_DISTANCE_BLOCKS / 16) + " chunks)",
                "Clipmap rings: " + ringCount + " | far spacing: " + far.sampleSpacing() + " blocks",
                String.format("Camera: %.1f / %.1f / %.1f", camera.x, camera.y, camera.z),
                String.format("LOD callback: %.4f ms | frames: %d", callbackMs, worldRenderCallbacks),
                "Renderer: bootstrap only - terrain meshes next"
        };
    }
}
