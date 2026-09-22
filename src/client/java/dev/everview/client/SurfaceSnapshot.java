package dev.everview.client;

import java.util.List;

/**
 * Immutable CPU-side snapshot used by the M1.1 tiled smoke-test renderer.
 */
public record SurfaceSnapshot(
        List<SurfaceTile> tiles,
        int cellCount,
        int vertexCount,
        int minY,
        int maxY,
        int radiusBlocks,
        int sampleSpacing,
        long buildNanos
) {
    public static final SurfaceSnapshot EMPTY =
            new SurfaceSnapshot(List.of(), 0, 0, 0, 1, 0, 0, 0L);

    public SurfaceSnapshot {
        tiles = List.copyOf(tiles);
    }

    public boolean isEmpty() {
        return tiles.isEmpty() || cellCount == 0;
    }

    public double buildMs() {
        return buildNanos / 1_000_000.0;
    }
}
