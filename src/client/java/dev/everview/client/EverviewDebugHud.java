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

    private static final KeyMapping GENERATION_TOGGLE=new KeyMapping("key.everview.generation_hud",InputConstants.Type.KEYBOARD,InputConstants.KEY_F7,KeyMapping.Category.MISC);
    private static boolean generationView;
    private static boolean expanded;
    private static long refreshedAt;
    private static List<String> cachedLines = List.of();
    private static int cachedWidth;
    private static final KeyMapping RENDER_TOGGLE = new KeyMapping("key.everview.render_toggle",
            InputConstants.Type.KEYBOARD, InputConstants.KEY_F9, KeyMapping.Category.MISC);

    private EverviewDebugHud() {
    }

    public static void register() {
        KeyMappingHelper.registerKeyMapping(TOGGLE_KEY);
        KeyMappingHelper.registerKeyMapping(GENERATION_TOGGLE);
        KeyMappingHelper.registerKeyMapping(RENDER_TOGGLE);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while(GENERATION_TOGGLE.consumeClick()){generationView=!generationView;refreshedAt=0;}
            while (TOGGLE_KEY.consumeClick()) {
                generationView=false;
                expanded = !expanded; refreshedAt = 0;
            }
            while (RENDER_TOGGLE.consumeClick()) {
                EverviewRenderer.renderEnabled = !EverviewRenderer.renderEnabled; refreshedAt = 0;
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

        long started = System.nanoTime();
        // Diagnostics used to rescan the hierarchy and format dozens of lines every frame.
        if (started - refreshedAt >= 250_000_000L) {
            EverviewMetrics.Snapshot metrics = EverviewMetrics.snapshot();
            WorldgenSurfaceSnapshot far = WorldgenSurfaceSampler.snapshot();
            cachedLines = generationView ? GenerationDiagnostics.lines(far) : expanded ? expandedLines(metrics, far) : compactLines(metrics, far);
            cachedWidth = 0;
            for (String line : cachedLines) cachedWidth = Math.max(cachedWidth, client.font.width(line));
            refreshedAt = started;
        }
        drawPanel(graphics, client, cachedLines);
        EverviewFrameProfiler.hud = System.nanoTime() - started;
    }

    private static List<String> compactLines(
            EverviewMetrics.Snapshot metrics,
            WorldgenSurfaceSnapshot far
    ) {
        List<String> lines = new ArrayList<>();
        lines.add("Everview M9.7 | F8 details | F9 LOD " + (EverviewRenderer.renderEnabled ? "ON" : "OFF (A/B)"));

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
                "Gen %.1f ms | exact %d/%d | LOD CPU avg %.3f ms | %s",
                far.sliceBudgetMs(),
                reuse.exactJobsActive(),
                WorldgenSurfaceSampler.exactWorkerCount(),
                EverviewFrameProfiler.meanMs(),
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
        lines.add("Everview M9.7 DEV | SHARED SOURCE + ADAPTIVE BLOCK DETAIL");
        lines.add("F8 compact | 26.3 Fabric | Sodium "
                + yesNo(sodium) + " | Iris " + yesNo(iris));
        lines.add("Renderer: persistent region commands | transactional ownership | 360 spatial residency");
        lines.add("Handoff: final vanilla mask for all terrain/water/seams | resident fallback retained");
        lines.add("F7 generation metrics | adaptive 1b/2b feature detail beyond L1");
        lines.add("First-visible: L1 4b -> 1b | L2 4b | L3 4b | L4 8b | L5 8b | L6 16b");
        lines.add("Adaptive error: L3 0.5b | L4 1b | L5 2b | L6 4b | GPU target/hard 1024/1152 MiB");
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
                    stream.nearCoverageComplete() ? "NEAR READY" : "NEAR PENDING",
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
            lines.add("Height workers: exact tiles "
                    + reuse.exactJobsActive()
                    + " | exact workers "
                    + WorldgenSurfaceSampler.exactWorkerBudgetCurrent()
                    + "/"
                    + WorldgenSurfaceSampler.exactWorkerCount()
                    + " | "
                    + WorldgenSurfaceSampler.exactWorkersPerTileCurrent()
                    + "/tile | coverage "
                    + WorldgenSurfaceSampler.detachedCoverageJobsActive()
                    + "/"
                    + WorldgenSurfaceSampler.detachedCoverageJobsMax()
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

        EverviewGpuRegionCache.Stats gpu =
                EverviewGpuRegionCache.stats();
        Runtime runtime = Runtime.getRuntime();
        double heapUsedMiB = (runtime.totalMemory() - runtime.freeMemory())
                / (1024.0 * 1024.0);
        double heapMaxMiB = runtime.maxMemory() / (1024.0 * 1024.0);
        lines.add(String.format(
                "Memory: heap %.0f/%.0f MiB | CPU LOD %.1f MiB | GPU LOD %.1f MiB",
                heapUsedMiB,
                heapMaxMiB,
                WorldgenSurfaceSampler.cpuTileCacheMiB(),
                gpu.residentMiB()
        ));
        lines.add(String.format(
                "GPU regions: %d | tiles %d | %.2f MiB | rebuild %d (%d tiles) / %.3f ms | prepare %.3f ms",
                gpu.regionCount(),
                gpu.tileCount(),
                gpu.residentMiB(),
                gpu.regionRebuildsThisFrame(),
                gpu.tileUploadsThisFrame(),
                gpu.uploadMs(),
                gpu.prepareMs()
        ));
        lines.add(String.format("Commands: reused %d regions | rebuilt %d | seam indices %d",
                EverviewFrameProfiler.reusedRegions, EverviewFrameProfiler.rebuiltRegions, EverviewFrameProfiler.seamIndices));
        lines.add(String.format("CPU metadata %.1f MiB | seam GPU %.1f MiB | GPU includes in-flight retired buffers",
                gpu.cpuMetadataMiB(), gpu.seamGpuMiB()));
        lines.add(String.format(
                "Spatial residency: wanted %d | degraded %d | rebuild %d | %s",
                gpu.residencyWantedTiles(),
                gpu.degradedFineTilesThisFrame(),
                gpu.residencySelectionRebuildsThisFrame(),
                gpu.residencyComplete() ? "SETTLED" : "STREAMING"
        ));
        lines.add(String.format(
                "GPU churn: stale-regions %d | hard-evict %d | yaw/pitch eviction OFF",
                gpu.staleRegionsThisFrame(),
                gpu.hardEvictionsThisFrame()
        ));
        lines.add("Frame c/s/d: " + metrics.tilesCulled() + "/"
                + metrics.submissions() + "/" + metrics.tilesDrawn());
        lines.add("Draw calls: " + metrics.drawCalls()
                + " | handoff " + metrics.handoffDrawCalls()
                + " | fast " + metrics.fastTileDrawCalls());
        lines.add(String.format("LOD CPU: %.3f ms | 240-frame mean %.3f | HUD %.3f | F9 A/B %s",
                EverviewFrameProfiler.ms(EverviewFrameProfiler.prepareTotal + EverviewFrameProfiler.drawTotal),
                EverviewFrameProfiler.meanMs(), EverviewFrameProfiler.ms(EverviewFrameProfiler.hud),
                EverviewRenderer.renderEnabled ? "ON" : "OFF"));
        lines.add(String.format("CPU ms: cull %.3f | residency %.3f | ownership %.3f | commands %.3f",
                EverviewFrameProfiler.ms(EverviewFrameProfiler.visibility), EverviewFrameProfiler.ms(EverviewFrameProfiler.residency),
                EverviewFrameProfiler.ms(EverviewFrameProfiler.ownership), EverviewFrameProfiler.ms(EverviewFrameProfiler.commands)));
        lines.add(String.format("CPU ms: upload %.3f | integrate %.3f | submit %.3f | tick integration %.3f",
                EverviewFrameProfiler.ms(EverviewFrameProfiler.upload), EverviewFrameProfiler.ms(EverviewFrameProfiler.integration),
                EverviewFrameProfiler.ms(EverviewFrameProfiler.submission), EverviewFrameProfiler.ms(EverviewFrameProfiler.generationIntegration)));
        lines.add(String.format("Workers ms (last job): spatial %.2f | ownership %.2f | pack %.2f | budget deferred %d",
                EverviewFrameProfiler.ms(EverviewFrameProfiler.selectionWorker), EverviewFrameProfiler.ms(EverviewFrameProfiler.ownershipWorker),
                EverviewFrameProfiler.ms(EverviewFrameProfiler.packingWorker), EverviewFrameProfiler.deferredUploads));
        lines.add("Drawable 128b cells: " + EverviewGpuRegionCache.renderState().coverageCells()
                + " | missing in 16K disk at last commit: " + EverviewGpuRegionCache.renderState().missingCells());

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
        int panelWidth = cachedWidth + pad * 2;
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
