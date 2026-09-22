package dev.everview.client;

import java.util.List;

/**
 * Immutable active-set snapshot for the M1.2 tile-cache renderer.
 */
public record SurfaceSnapshot(
        List<SurfaceTile> tiles,
        int cellCount,
        int vertexCount,
        int minY,
        int maxY,
        int radiusBlocks,
        int sampleSpacing,
        long updateNanos,
        int newTilesBuilt,
        int cacheHits,
        int cacheSize,
        int evictions,
        int incompleteTiles
) {
    public static final SurfaceSnapshot EMPTY =
            new SurfaceSnapshot(List.of(), 0, 0, 0, 1, 0, 0, 0L, 0, 0, 0, 0, 0);

    public SurfaceSnapshot {
        tiles = List.copyOf(tiles);
    }

    public boolean isEmpty() {
        return tiles.isEmpty() || cellCount == 0;
    }

    public double updateMs() {
        return updateNanos / 1_000_000.0;
    }
}
