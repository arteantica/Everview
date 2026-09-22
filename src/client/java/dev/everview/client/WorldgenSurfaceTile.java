package dev.everview.client;

/**
 * Coarse surface tile sampled directly from the integrated server's chunk
 * generator without loading or generating the corresponding chunks.
 */
public record WorldgenSurfaceTile(
        int tileX,
        int tileZ,
        int[] vertices,
        int cellCount,
        int minY,
        int maxY,
        int seaLevel,
        long generationNanos
) {
    public int vertexCount() {
        return vertices.length / 3;
    }

    public int minX() {
        return tileX * WorldgenSurfaceSampler.TILE_SIZE;
    }

    public int minZ() {
        return tileZ * WorldgenSurfaceSampler.TILE_SIZE;
    }

    public int maxX() {
        return minX() + WorldgenSurfaceSampler.TILE_SIZE;
    }

    public int maxZ() {
        return minZ() + WorldgenSurfaceSampler.TILE_SIZE;
    }

    public double generationMs() {
        return generationNanos / 1_000_000.0;
    }
}
