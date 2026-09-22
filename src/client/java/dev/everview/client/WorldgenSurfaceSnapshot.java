package dev.everview.client;

import java.util.List;

/**
 * Immutable client-side view of the distant-worldgen LOD ring.
 */
public record WorldgenSurfaceSnapshot(
        List<WorldgenSurfaceTile> tiles,
        int desiredTileCount,
        int cacheSize,
        int innerRadiusBlocks,
        int outerRadiusBlocks,
        int sampleSpacing,
        boolean available,
        boolean taskInFlight,
        double lastTileGenerationMs,
        int generatedTileCount,
        double sliceBudgetMs,
        double lastSliceMs,
        int lastSliceSamples,
        double currentTileProgressPercent
) {
    public static final WorldgenSurfaceSnapshot EMPTY =
            new WorldgenSurfaceSnapshot(
                    List.of(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    false,
                    false,
                    0.0,
                    0,
                    0.0,
                    0.0,
                    0,
                    0.0
            );

    public WorldgenSurfaceSnapshot {
        tiles = List.copyOf(tiles);
    }

    public int readyTileCount() {
        return tiles.size();
    }

    public double completionPercent() {
        if (desiredTileCount <= 0) {
            return 0.0;
        }
        return readyTileCount() * 100.0 / desiredTileCount;
    }
}
