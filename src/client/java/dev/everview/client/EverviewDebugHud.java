package dev.everview.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

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
        SurfaceSnapshot surface = LoadedSurfaceSampler.snapshot();

        boolean sodium = FabricLoader.getInstance().isModLoaded("sodium");
        boolean iris = FabricLoader.getInstance().isModLoaded("iris");

        List<String> lines = List.of(
                "Everview M1.2 | ACTIVE",
                "26.3 Fabric | Sodium " + yesNo(sodium) + " | Iris " + yesNo(iris),
                "Radius: " + surface.radiusBlocks() + " | spacing: " + surface.sampleSpacing()
                        + " | tile: " + LoadedSurfaceSampler.TILE_SIZE,
                "Active: " + metrics.activeTiles() + " | cache: " + metrics.cacheSize()
                        + " | new: " + metrics.newTilesBuilt() + " | hits: " + metrics.cacheHits(),
                "Cull: " + metrics.tilesCulled() + " | submits: " + metrics.submissions()
                        + " | drawn: " + metrics.tilesDrawn(),
                "Quads: " + metrics.cells() + " | vertices: " + metrics.vertices()
                        + " | incomplete: " + metrics.incompleteTiles(),
                String.format("Tile update: %.3f ms | geometry: %.3f ms", metrics.updateMs(), metrics.drawMs()),
                "Evictions: " + metrics.evictions()
                        + " | target: " + EverviewClient.TARGET_DISTANCE_BLOCKS + " blocks"
        );

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

    private static String yesNo(boolean value) {
        return value ? "ON" : "OFF";
    }
}
