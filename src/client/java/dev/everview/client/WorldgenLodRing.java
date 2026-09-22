package dev.everview.client;

/**
 * One progressive distant-worldgen LOD ring.
 */
public record WorldgenLodRing(
        int lodLevel,
        int innerRadiusBlocks,
        int outerRadiusBlocks,
        int tileSize,
        int sampleSpacing
) {
    public WorldgenLodRing {
        if (lodLevel < 1) {
            throw new IllegalArgumentException("lodLevel must be >= 1");
        }
        if (innerRadiusBlocks < 0 || outerRadiusBlocks <= innerRadiusBlocks) {
            throw new IllegalArgumentException("invalid LOD radii");
        }
        if (tileSize <= 0 || sampleSpacing <= 0 || tileSize % sampleSpacing != 0) {
            throw new IllegalArgumentException("tileSize must be divisible by sampleSpacing");
        }
    }

    public int samplesAcross() {
        return tileSize / sampleSpacing + 1;
    }

    public int cellsAcross() {
        return samplesAcross() - 1;
    }
}
