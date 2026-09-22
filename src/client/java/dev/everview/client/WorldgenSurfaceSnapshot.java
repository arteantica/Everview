package dev.everview.client;

import java.util.List;

/**
 * Immutable client-side view of all active distant-worldgen LOD rings.
 */
public record WorldgenSurfaceSnapshot(
        List<WorldgenSurfaceTile> tiles,
        List<WorldgenRingStatus> rings,
        int desiredTileCount,
        int cacheSize,
        boolean available,
        boolean taskInFlight,
        double lastTileGenerationMs,
        int generatedTileCount,
        double sliceBudgetMs,
        double lastSliceMs,
        int lastSliceSamples,
        double currentTileProgressPercent,
        int currentLodLevel,
        double initialFillSeconds,
        boolean initialFillComplete,
        double serverTickMs,
        double clientFrameMs,
        int diskLoadedTiles,
        double diskLoadMs,
        int diskSavedTiles,
        double diskSaveMs,
        double diskFileMiB,
        String diskCacheStatus,
        boolean diskIoInFlight
) {
    public static final WorldgenSurfaceSnapshot EMPTY =
            new WorldgenSurfaceSnapshot(
                    List.of(),
                    List.of(),
                    0,
                    0,
                    false,
                    false,
                    0.0,
                    0,
                    0.0,
                    0.0,
                    0,
                    0.0,
                    0,
                    0.0,
                    false,
                    0.0,
                    0.0,
                    0,
                    0.0,
                    0,
                    0.0,
                    0.0,
                    "OFF",
                    false
            );

    public WorldgenSurfaceSnapshot {
        tiles = List.copyOf(tiles);
        rings = List.copyOf(rings);
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

    public WorldgenLodRing ringForLevel(int lodLevel) {
        for (WorldgenRingStatus status : rings) {
            if (status.ring().lodLevel() == lodLevel) {
                return status.ring();
            }
        }
        return null;
    }
}
