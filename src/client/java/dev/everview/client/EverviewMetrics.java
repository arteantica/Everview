package dev.everview.client;

/**
 * Tiny lock-free-ish render telemetry holder. All writers currently execute on the
 * client/render thread, while the HUD only reads snapshots of the values.
 */
public final class EverviewMetrics {
    private static volatile int tiles;
    private static volatile int cells;
    private static volatile int vertices;
    private static volatile int submissions;
    private static volatile int tilesDrawn;
    private static volatile double buildMs;
    private static volatile double drawMs;

    private static long frameDrawNanos;

    private EverviewMetrics() {
    }

    public static void recordSnapshot(SurfaceSnapshot snapshot) {
        tiles = snapshot.tiles().size();
        cells = snapshot.cellCount();
        vertices = snapshot.vertexCount();
        buildMs = snapshot.buildMs();
    }

    public static void beginRenderFrame(int expectedSubmissions) {
        submissions = expectedSubmissions;
        tilesDrawn = 0;
        frameDrawNanos = 0L;
        drawMs = 0.0;
    }

    public static void recordTileDraw(long nanos) {
        frameDrawNanos += nanos;
        tilesDrawn++;
        drawMs = frameDrawNanos / 1_000_000.0;
    }

    public static Snapshot snapshot() {
        return new Snapshot(tiles, cells, vertices, submissions, tilesDrawn, buildMs, drawMs);
    }

    public record Snapshot(
            int tiles,
            int cells,
            int vertices,
            int submissions,
            int tilesDrawn,
            double buildMs,
            double drawMs
    ) {
    }
}
