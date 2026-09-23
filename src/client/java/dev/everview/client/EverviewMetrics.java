package dev.everview.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Render/update telemetry used by the alpha HUD.
 */
public final class EverviewMetrics {
    private static final int MAX_DIAGNOSTIC_LOD = 6;

    private static volatile int activeTiles;
    private static volatile int cells;
    private static volatile int vertices;
    private static volatile int newTilesBuilt;
    private static volatile int cacheHits;
    private static volatile int cacheSize;
    private static volatile int evictions;
    private static volatile int incompleteTiles;

    private static volatile int submissions;
    private static volatile int drawCalls;
    private static volatile int handoffDrawCalls;
    private static volatile int fastTileDrawCalls;
    private static volatile int tilesDrawn;
    private static volatile int tilesCulled;
    private static volatile double updateMs;
    private static volatile double drawMs;

    private static final int[] ringSubmissions = new int[MAX_DIAGNOSTIC_LOD + 1];
    private static final int[] ringDrawn = new int[MAX_DIAGNOSTIC_LOD + 1];
    private static final int[] ringCulled = new int[MAX_DIAGNOSTIC_LOD + 1];
    private static final int[] ringEmittedQuads = new int[MAX_DIAGNOSTIC_LOD + 1];
    private static final double[] ringMaxQuadDistance = new double[MAX_DIAGNOSTIC_LOD + 1];

    private static long frameDrawNanos;

    private EverviewMetrics() {
    }

    public static void recordSnapshot(SurfaceSnapshot snapshot) {
        activeTiles = snapshot.tiles().size();
        cells = snapshot.cellCount();
        vertices = snapshot.vertexCount();
        newTilesBuilt = snapshot.newTilesBuilt();
        cacheHits = snapshot.cacheHits();
        cacheSize = snapshot.cacheSize();
        evictions = snapshot.evictions();
        incompleteTiles = snapshot.incompleteTiles();
        updateMs = snapshot.updateMs();
    }

    public static void beginRenderFrame() {
        submissions = 0;
        drawCalls = 0;
        handoffDrawCalls = 0;
        fastTileDrawCalls = 0;
        tilesDrawn = 0;
        tilesCulled = 0;
        frameDrawNanos = 0L;
        drawMs = 0.0;

        for (int level = 1; level <= MAX_DIAGNOSTIC_LOD; level++) {
            ringSubmissions[level] = 0;
            ringDrawn[level] = 0;
            ringCulled[level] = 0;
            ringEmittedQuads[level] = 0;
            ringMaxQuadDistance[level] = 0.0;
        }
    }

    public static void recordCulledTile() {
        tilesCulled++;
    }

    public static void recordCulledTile(int lodLevel) {
        recordCulledTile();
        if (validLod(lodLevel)) {
            ringCulled[lodLevel]++;
        }
    }

    public static void recordSubmission() {
        submissions++;
    }

    public static void recordSubmission(int lodLevel) {
        recordSubmission();
        if (validLod(lodLevel)) {
            ringSubmissions[lodLevel]++;
        }
    }

    public static void recordDrawCall(boolean handoffBatch) {
        drawCalls++;
        if (handoffBatch) {
            handoffDrawCalls++;
        } else {
            fastTileDrawCalls++;
        }
    }

    public static void recordTileDraw(long nanos) {
        frameDrawNanos += nanos;
        tilesDrawn++;
        drawMs = frameDrawNanos / 1_000_000.0;
    }

    public static void recordTileDraw(
            int lodLevel,
            long nanos,
            int emittedQuads,
            double maxQuadDistance
    ) {
        recordTileDraw(nanos);

        if (validLod(lodLevel)) {
            ringDrawn[lodLevel]++;
            ringEmittedQuads[lodLevel] += emittedQuads;
            ringMaxQuadDistance[lodLevel] =
                    Math.max(ringMaxQuadDistance[lodLevel], maxQuadDistance);
        }
    }

    public static Snapshot snapshot() {
        List<RingRenderStats> ringStats = new ArrayList<>(MAX_DIAGNOSTIC_LOD);

        for (int level = 1; level <= MAX_DIAGNOSTIC_LOD; level++) {
            ringStats.add(new RingRenderStats(
                    level,
                    ringCulled[level],
                    ringSubmissions[level],
                    ringDrawn[level],
                    ringEmittedQuads[level],
                    ringMaxQuadDistance[level]
            ));
        }

        return new Snapshot(
                activeTiles,
                cells,
                vertices,
                newTilesBuilt,
                cacheHits,
                cacheSize,
                evictions,
                incompleteTiles,
                submissions,
                drawCalls,
                handoffDrawCalls,
                fastTileDrawCalls,
                tilesDrawn,
                tilesCulled,
                updateMs,
                drawMs,
                ringStats
        );
    }

    private static boolean validLod(int lodLevel) {
        return lodLevel >= 1 && lodLevel <= MAX_DIAGNOSTIC_LOD;
    }

    public record RingRenderStats(
            int lodLevel,
            int culled,
            int submitted,
            int drawn,
            int emittedQuads,
            double maxQuadDistance
    ) {
    }

    public record Snapshot(
            int activeTiles,
            int cells,
            int vertices,
            int newTilesBuilt,
            int cacheHits,
            int cacheSize,
            int evictions,
            int incompleteTiles,
            int submissions,
            int drawCalls,
            int handoffDrawCalls,
            int fastTileDrawCalls,
            int tilesDrawn,
            int tilesCulled,
            double updateMs,
            double drawMs,
            List<RingRenderStats> ringRenderStats
    ) {
        public Snapshot {
            ringRenderStats = List.copyOf(ringRenderStats);
        }

        public RingRenderStats ring(int lodLevel) {
            for (RingRenderStats stats : ringRenderStats) {
                if (stats.lodLevel() == lodLevel) {
                    return stats;
                }
            }
            return new RingRenderStats(lodLevel, 0, 0, 0, 0, 0.0);
        }
    }
}
