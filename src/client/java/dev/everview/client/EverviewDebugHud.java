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
        lines.add("Everview M2.0.1 | ACTIVE");
        lines.add("26.3 Fabric | Sodium " + yesNo(sodium) + " | Iris " + yesNo(iris));
        lines.add("Near debug heightfield: OFF | vanilla/Sodium handoff");

        if (far.available()) {
            lines.add("Far WORLDGEN: " + far.innerRadiusBlocks() + "-" + far.outerRadiusBlocks()
                    + " | spacing " + far.sampleSpacing());
            lines.add(String.format(
                    "Far tiles: %d/%d (%.0f%%) | cache %d | job %s",
                    far.readyTileCount(),
                    far.desiredTileCount(),
                    far.completionPercent(),
                    far.cacheSize(),
                    far.taskInFlight() ? "ON" : "OFF"
            ));
            lines.add("Last far tile: " + formatMs(far.lastTileGenerationMs())
                    + " | generated " + far.generatedTileCount());
        } else {
            lines.add("Far WORLDGEN: unavailable (singleplayer test path)");
        }

        lines.add("Cull: " + metrics.tilesCulled() + " | submits: " + metrics.submissions()
                + " | drawn: " + metrics.tilesDrawn());
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

    private static String yesNo(boolean value) {
        return value ? "ON" : "OFF";
    }
}
