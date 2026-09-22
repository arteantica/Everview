package dev.everview.client;

/**
 * Render/update telemetry used by the alpha HUD.
 */
public final class EverviewMetrics {
    private static volatile int activeTiles;
    private static volatile int cells;
    private static volatile int vertices;
    private static volatile int newTilesBuilt;
    private static volatile int cacheHits;
    private static volatile int cacheSize;
    private static volatile int evictions;
    private static volatile int incompleteTiles;

    private static volatile int submissions;
    private static volatile int tilesDrawn;
    private static volatile int tilesCulled;
    private static volatile double updateMs;
    private static volatile double drawMs;

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
        tilesDrawn = 0;
        tilesCulled = 0;
        frameDrawNanos = 0L;
        drawMs = 0.0;
    }

    public static void recordCulledTile() {
        tilesCulled++;
    }

    public static void recordSubmission() {
        submissions++;
    }

    public static void recordTileDraw(long nanos) {
        frameDrawNanos += nanos;
        tilesDrawn++;
        drawMs = frameDrawNanos / 1_000_000.0;
    }

    public static Snapshot snapshot() {
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
                tilesDrawn,
                tilesCulled,
                updateMs,
                drawMs
        );
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
            int tilesDrawn,
            int tilesCulled,
            double updateMs,
            double drawMs
    ) {
    }
}
