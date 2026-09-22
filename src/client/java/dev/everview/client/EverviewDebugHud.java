package dev.everview.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Always-on development telemetry for alpha builds.
 */
public final class EverviewDebugHud {
    private static final Identifier HUD_ID =
            Identifier.fromNamespaceAndPath(EverviewClient.MOD_ID, "debug_hud");

    private EverviewDebugHud() {
    }

    public static void register() {
        HudElementRegistry.addLast(HUD_ID, EverviewDebugHud::extractRenderState);
    }

    private static void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (client.font == null || client.level == null || client.gui.hud.isHidden()) {
            return;
        }

        EverviewMetrics.Snapshot metrics = EverviewMetrics.snapshot();
        WorldgenSurfaceSnapshot far = WorldgenSurfaceSampler.snapshot();

        boolean sodium = FabricLoader.getInstance().isModLoaded("sodium");
        boolean iris = FabricLoader.getInstance().isModLoaded("iris");

        List<String> lines = new ArrayList<>();
        lines.add("Everview M3.2.2 | 4-BLOCK ULTRA-NEAR");
        lines.add("26.3 Fabric | Sodium " + yesNo(sodium) + " | Iris " + yesNo(iris));
        lines.add("Visual: L1 4-block ultra-near | L2 8-block near | 1-block steps | cliff walls");
        lines.add("Handoff: " + WorldgenSurfaceSampler.HANDOFF_OVERLAP_BLOCKS + "-block vanilla overlap");
        lines.add(String.format(
                "Camera far: vanilla %.0f -> Everview %.0f | ring target %d",
                EverviewFarPlane.vanillaDepthFar(),
                EverviewFarPlane.extendedDepthFar(),
                WorldgenSurfaceSampler.MAX_OUTER_RADIUS
        ));

        if (far.available()) {
            for (WorldgenRingStatus status : far.rings()) {
                WorldgenLodRing ring = status.ring();
                EverviewMetrics.RingRenderStats render = metrics.ring(ring.lodLevel());

                lines.add(String.format(
                        "L%d %d-%d s%d: gen %d/%d | c/s/d %d/%d/%d | q %d | max %.0f",
                        ring.lodLevel(),
                        ring.innerRadiusBlocks(),
                        ring.outerRadiusBlocks(),
                        ring.sampleSpacing(),
                        status.readyTileCount(),
                        status.desiredTileCount(),
                        render.culled(),
                        render.submitted(),
                        render.drawn(),
                        render.emittedQuads(),
                        render.maxQuadDistance()
                ));
            }

            lines.add(String.format(
                    "Total: %d/%d (%.0f%%) | cache %d | job %s",
                    far.readyTileCount(),
                    far.desiredTileCount(),
                    far.completionPercent(),
                    far.cacheSize(),
                    far.taskInFlight() ? "ON" : "OFF"
            ));

            double tilesPerSecond = far.initialFillSeconds() > 0.0
                    ? far.readyTileCount() / far.initialFillSeconds()
                    : 0.0;

            lines.add(String.format(
                    "Initial fill: %s %s | %.1f tiles/s",
                    formatDuration(far.initialFillSeconds()),
                    far.initialFillComplete() ? "DONE" : "RUNNING",
                    tilesPerSecond
            ));

            lines.add(String.format(
                    "Disk: %s | loaded %d in %.1f ms | saved %d %.2f MiB in %.1f ms%s",
                    far.diskCacheStatus(),
                    far.diskLoadedTiles(),
                    far.diskLoadMs(),
                    far.diskSavedTiles(),
                    far.diskFileMiB(),
                    far.diskSaveMs(),
                    far.diskIoInFlight() ? " | IO" : ""
            ));

            lines.add(String.format(
                    "Adaptive gen %.2f ms | server %.1f ms | frame %.1f ms | last %.3f / %d | L%d %.0f%%",
                    far.sliceBudgetMs(),
                    far.serverTickMs(),
                    far.clientFrameMs(),
                    far.lastSliceMs(),
                    far.lastSliceSamples(),
                    far.currentLodLevel(),
                    far.currentTileProgressPercent()
            ));
        } else {
            lines.add("Far WORLDGEN: unavailable (singleplayer test path)");
        }

        lines.add("Frame total c/s/d: " + metrics.tilesCulled() + "/"
                + metrics.submissions() + "/" + metrics.tilesDrawn());
        lines.add("Geometry CPU: " + formatMs(metrics.drawMs())
                + " | target: " + EverviewClient.TARGET_DISTANCE_BLOCKS);

        int x = 6;
        int y = 6;
        int pad = 4;
        int lineHeight = client.font.lineHeight + 2;
        int width = 0;

        for (String line : lines) {
            width = Math.max(width, client.font.width(line));
        }

        int panelWidth = width + pad * 2;
        int panelHeight = lines.size() * lineHeight + pad * 2;

        graphics.fill(x, y, x + panelWidth, y + panelHeight, 0xA0000000);

        int textY = y + pad;
        for (int i = 0; i < lines.size(); i++) {
            int color = i == 0 ? 0xFF77F59A : 0xFFFFFFFF;
            graphics.text(client.font, lines.get(i), x + pad, textY, color, true);
            textY += lineHeight;
        }
    }

    private static String formatMs(double ms) {
        return String.format("%.3f ms", ms);
    }

    private static String formatDuration(double seconds) {
        int wholeMinutes = (int) (seconds / 60.0);
        double remainingSeconds = seconds - wholeMinutes * 60.0;

        if (wholeMinutes > 0) {
            return String.format("%d:%04.1f", wholeMinutes, remainingSeconds);
        }

        return String.format("%.1fs", remainingSeconds);
    }

    private static String yesNo(boolean value) {
        return value ? "ON" : "OFF";
    }
}
