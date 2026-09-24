package dev.everview.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Compact telemetry by default; F8 expands the full development panel.
 */
public final class EverviewDebugHud {
    private static final Identifier HUD_ID =
            Identifier.fromNamespaceAndPath(EverviewClient.MOD_ID, "debug_hud");

    private static final KeyMapping TOGGLE_KEY = new KeyMapping(
            "key.everview.debug_hud",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_F8,
            KeyMapping.Category.MISC
    );

    private static boolean expanded;

    private EverviewDebugHud() {
    }

    public static void register() {
        KeyMappingHelper.registerKeyMapping(TOGGLE_KEY);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (TOGGLE_KEY.consumeClick()) {
                expanded = !expanded;
            }
        });
        HudElementRegistry.addLast(HUD_ID, EverviewDebugHud::extractRenderState);
    }

    private static void extractRenderState(
            GuiGraphicsExtractor graphics,
            DeltaTracker deltaTracker
    ) {
        Minecraft client = Minecraft.getInstance();
        if (client.font == null
                || client.level == null
                || client.gui.hud.isHidden()) {
            return;
        }

        EverviewMetrics.Snapshot metrics = EverviewMetrics.snapshot();
        WorldgenSurfaceSnapshot far = WorldgenSurfaceSampler.snapshot();
        List<String> lines = expanded
                ? expandedLines(metrics, far)
                : compactLines(metrics, far);

        drawPanel(graphics, client, lines);
    }

    private static List<String> compactLines(
            EverviewMetrics.Snapshot metrics,
            WorldgenSurfaceSnapshot far
    ) {
        List<String> lines = new ArrayList<>();
        lines.add("Everview M8.0 | F8 details");

        if (!far.available()) {
            lines.add("LOD worldgen unavailable");
            return lines;
        }

        WorldgenSurfaceSampler.L1ViewStatus front =
                WorldgenSurfaceSampler.l1ViewStatus();
        WorldgenSurfaceSampler.StreamingStatus stream =
                WorldgenSurfaceSampler.streamingStatus();

        double tilesPerSecond = far.initialFillSeconds() > 0.0
                ? far.readyTileCount() / far.initialFillSeconds()
                : 0.0;

        lines.add(String.format(
                "Cover %.0f%% | L1 1b %d/%d | %.1f tiles/s",
                far.completionPercent(),
                front.exact(),
                front.desired(),
                tilesPerSecond
        ));
        WorldgenSurfaceSampler.RefinementReuseStatus reuse =
                WorldgenSurfaceSampler.refinementReuseStatus();
        lines.add(String.format(
                "Gen %.1f ms | exact %d/%d | geometry %.3f ms | %s",
                far.sliceBudgetMs(),
                reuse.exactJobsActive(),
                WorldgenSurfaceSampler.exactWorkerCount(),
                metrics.drawMs(),
                stream.highSpeedCoverageMode() ? "FAST COVERAGE" : "NORMAL"
        ));

        return lines;
    }

    private static List<String> expandedLines(
            EverviewMetrics.Snapshot metrics,
            WorldgenSurfaceSnapshot far
    ) {
        boolean sodium = FabricLoader.getInstance().isModLoaded("sodium");
        boolean iris = FabricLoader.getInstance().isModLoaded("iris");

        List<String> lines = new ArrayList<>();
        lines.add("Everview M8.0 DEV | STEPPED TERRAIN + RENDER BUDGET");
        lines.add("F8 compact | 26.3 Fabric | Sodium "
                + yesNo(sodium) + " | Iris " + yesNo(iris));
        lines.add("Renderer: 64b L1 tiles | persistent 360 cache | stepped focused far mesh | seam shield");
        lines.add("Handoff: 64b overlap | 350ms visible-stability gate | chunk fade forced OFF");
        lines.add("LOD targets: L1 1b | L2 2b | L3 2b | L4 4b | L5 8b | L6 16b");
        lines.add("First-visible: L1 4b | L2 4b | L3 4b | L4 8b | L5 8b | L6 16b");
        lines.add("View focus: narrow cone | L5 -> 4b stepped | L6 -> 8b stepped | GPU 3840 / 4096");
        lines.add(String.format(
                "Camera far: vanilla %.0f -> Everview %.0f | ring target %d",
                EverviewFarPlane.vanillaDepthFar(),
                EverviewFarPlane.extendedDepthFar(),
                WorldgenSurfaceSampler.MAX_OUTER_RADIUS
        ));

        if (far.available()) {
            for (WorldgenRingStatus status : far.rings()) {
                WorldgenLodRing ring = status.ring();
                EverviewMetrics.RingRenderStats render =
                        metrics.ring(ring.lodLevel());

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

            int refinedTiles = 0;
            int nearDesired = 0;
            int nearCovered = 0;
            int nearRefined = 0;
            int l1Desired = 0;
            int l1Covered = 0;
            int l1Intermediate = 0;
            int l1ExactGeometry = 0;
            int l1ExactAppearance = 0;

            for (WorldgenRingStatus status : far.rings()) {
                if (status.ring().lodLevel() <= 2) {
                    nearDesired += status.desiredTileCount();
                    nearCovered += status.readyTileCount();
                }
                if (status.ring().lodLevel() == 1) {
                    l1Desired = status.desiredTileCount();
                    l1Covered = status.readyTileCount();
                }
            }

            for (WorldgenSurfaceTile tile : far.tiles()) {
                WorldgenLodRing targetRing =
                        far.ringForLevel(tile.lodLevel());
                if (targetRing != null
                        && tile.sampleSpacing()
                                <= targetRing.sampleSpacing()) {
                    refinedTiles++;
                    if (tile.lodLevel() <= 2) {
                        nearRefined++;
                    }
                }

                if (tile.lodLevel() == 1) {
                    if (tile.sampleSpacing() <= 2) {
                        l1Intermediate++;
                    }
                    if (tile.stage().exactGeometry()) {
                        l1ExactGeometry++;
                    }
                    if (tile.stage().exactAppearance()) {
                        l1ExactAppearance++;
                    }
                }
            }

            double refinePercent = far.desiredTileCount() > 0
                    ? refinedTiles * 100.0 / far.desiredTileCount()
                    : 0.0;
            double nearCoveragePercent = nearDesired > 0
                    ? nearCovered * 100.0 / nearDesired
                    : 0.0;
            double nearRefinePercent = nearDesired > 0
                    ? nearRefined * 100.0 / nearDesired
                    : 0.0;

            lines.add(String.format(
                    "Coverage: %d/%d (%.0f%%) | refine %d/%d (%.0f%%) | job %s",
                    far.readyTileCount(),
                    far.desiredTileCount(),
                    far.completionPercent(),
                    refinedTiles,
                    far.desiredTileCount(),
                    refinePercent,
                    far.taskInFlight() ? "ON" : "OFF"
            ));
            lines.add(String.format(
                    "Near L1/L2: cover %d/%d (%.0f%%) | refine %d/%d (%.0f%%)",
                    nearCovered,
                    nearDesired,
                    nearCoveragePercent,
                    nearRefined,
                    nearDesired,
                    nearRefinePercent
            ));
            lines.add(String.format(
                    "L1 actual: cover %d/%d | <=2b %d/%d | geom %d/%d | final %d/%d",
                    l1Covered,
                    l1Desired,
                    l1Intermediate,
                    l1Desired,
                    l1ExactGeometry,
                    l1Desired,
                    l1ExactAppearance,
                    l1Desired
            ));

            WorldgenSurfaceSampler.L1ViewStatus front =
                    WorldgenSurfaceSampler.l1ViewStatus();
            lines.add(String.format(
                    "L1 front: cover %d/%d | <=2b %d/%d | 1b %d/%d",
                    front.covered(),
                    front.desired(),
                    front.intermediate(),
                    front.desired(),
                    front.exact(),
                    front.desired()
            ));

            WorldgenSurfaceSampler.StreamingStatus stream =
                    WorldgenSurfaceSampler.streamingStatus();
            String frontier = stream.outwardFrontierBlocks() < 0
                    ? "DONE"
                    : stream.outwardFrontierBlocks() + "b";

            lines.add(String.format(
                    "Motion: %.1f b/s | lead %db | ahead %d/%d | L3 %d/%d | L6 %d/%d | %s | frontier %s | %s | stale %d",
                    stream.speedBlocksPerSecond(),
                    stream.predictiveLeadBlocks(),
                    stream.predictiveCovered(),
                    stream.predictiveDesired(),
                    stream.emergencyCovered(),
                    stream.emergencyDesired(),
                    stream.globalFloorCovered(),
                    stream.globalFloorDesired(),
                    stream.highSpeedCoverageMode() ? "COVERAGE" : "NORMAL",
                    frontier,
                    stream.nearCoverageComplete() ? "SOLID" : "GAP",
                    stream.staleJobsCancelled()
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

            WorldgenSurfaceSampler.RefinementReuseStatus reuse =
                    WorldgenSurfaceSampler.refinementReuseStatus();
            int lastTotalSamples =
                    reuse.lastReusedSamples()
                            + reuse.lastGeneratedSamples();
            double reusePercent = lastTotalSamples > 0
                    ? reuse.lastReusedSamples()
                            * 100.0 / lastTotalSamples
                    : 0.0;

            lines.add(String.format(
                    "L1 samples: height reuse %d/%d (%.0f%%) | appearance %d | borrowed %d | grids %d",
                    reuse.lastReusedSamples(),
                    lastTotalSamples,
                    reusePercent,
                    reuse.lastAppearanceGeneratedSamples(),
                    reuse.lastProvisionalAppearanceSamples(),
                    reuse.cachedL1Grids()
            ));
            lines.add(String.format(
                    "L1 appearance: %d/%d exact | provisional %d/%d | biome samples %d",
                    reuse.appearanceReadyTiles(),
                    reuse.appearanceDesiredTiles(),
                    reuse.provisionalExactTiles(),
                    WorldgenSurfaceSampler.maxProvisionalExactTiles(),
                    reuse.totalAppearanceGeneratedSamples()
            ));
            lines.add("Height workers: exact "
                    + reuse.exactJobsActive()
                    + "/"
                    + WorldgenSurfaceSampler.exactWorkerCount()
                    + " | coverage jobs "
                    + WorldgenSurfaceSampler.detachedCoverageJobsActive()
                    + "/"
                    + WorldgenSurfaceSampler.detachedCoverageJobsMax()
                    + " | workers "
                    + WorldgenSurfaceSampler.coverageWorkerCount()
                    + (reuse.serverExactFallback()
                            ? " | SERVER FALLBACK"
                            : ""));

            WorldgenSurfaceSampler.SharedHeightCacheStatus shared =
                    WorldgenSurfaceSampler.sharedHeightCacheStatus();
            lines.add(String.format(
                    "Shared heights: %,d entries | %,d hits / %,d misses | %.1f%% reuse",
                    shared.entries(),
                    shared.hits(),
                    shared.misses(),
                    shared.hitPercent()
            ));

            WorldgenSurfaceSampler.SharedBiomeCacheStatus biomes =
                    WorldgenSurfaceSampler.sharedBiomeCacheStatus();
            lines.add(String.format(
                    "Appearance jobs: %d/%d | biome cache %,d | %.1f%% reuse",
                    WorldgenSurfaceSampler.detachedAppearanceJobsActive(),
                    WorldgenSurfaceSampler.detachedAppearanceJobsMax(),
                    biomes.entries(),
                    biomes.hitPercent()
            ));
        } else {
            lines.add("Far WORLDGEN: unavailable (singleplayer test path)");
        }

        EverviewRenderer.OwnershipStats ownership =
                EverviewRenderer.ownershipStats();
        lines.add(String.format(
                "Ownership: vanilla %d | finer %d | LOD visible %d | waiting %d",
                ownership.vanillaOwnedBatches(),
                ownership.finerOwnedBatches(),
                ownership.visibleLodBatches(),
                ownership.loadedWaitingBatches()
        ));

        EverviewGpuTileCache.Stats gpu = EverviewGpuTileCache.stats();
        lines.add(String.format(
                "GPU tiles: %d | %.2f MiB | upload %d / %.3f ms | prepare %.3f ms",
                gpu.bufferCount(),
                gpu.residentMiB(),
                gpu.uploadsThisFrame(),
                gpu.uploadMs(),
                gpu.prepareMs()
        ));
        lines.add(String.format(
                "Residency: wanted %d | suppressed %d | view rebuild %d | hierarchy %d | %s",
                gpu.residencyWantedTiles(),
                gpu.suppressedCoarseTiles(),
                gpu.residencySelectionRebuildsThisFrame(),
                gpu.suppressionRebuildsThisFrame(),
                gpu.residencyComplete() ? "SETTLED" : "STREAMING"
        ));
        lines.add(String.format(
                "GPU churn: prune %d | stale %d | offscreen %d | hard-evict %d",
                gpu.coveredPrunedThisFrame(),
                gpu.staleRemovedThisFrame(),
                gpu.offscreenEvictionsThisFrame(),
                gpu.forcedEvictionsThisFrame()
        ));
        lines.add("Frame c/s/d: " + metrics.tilesCulled() + "/"
                + metrics.submissions() + "/" + metrics.tilesDrawn());
        lines.add("Draw calls: " + metrics.drawCalls()
                + " | handoff " + metrics.handoffDrawCalls()
                + " | fast " + metrics.fastTileDrawCalls());
        lines.add("Geometry CPU: " + formatMs(metrics.drawMs())
                + " | target: " + EverviewClient.TARGET_DISTANCE_BLOCKS);

        return lines;
    }

    private static void drawPanel(
            GuiGraphicsExtractor graphics,
            Minecraft client,
            List<String> lines
    ) {
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
            graphics.text(
                    client.font,
                    lines.get(i),
                    x + pad,
                    textY,
                    color,
                    true
            );
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
            return String.format(
                    "%d:%04.1f",
                    wholeMinutes,
                    remainingSeconds
            );
        }

        return String.format("%.1fs", remainingSeconds);
    }

    private static String yesNo(boolean value) {
        return value ? "ON" : "OFF";
    }
}
