package dev.everview.client;

/**
 * HUD/diagnostic state for one progressive LOD ring.
 */
public record WorldgenRingStatus(
        WorldgenLodRing ring,
        int desiredTileCount,
        int readyTileCount
) {
    public double completionPercent() {
        if (desiredTileCount <= 0) {
            return 0.0;
        }
        return readyTileCount * 100.0 / desiredTileCount;
    }
}
