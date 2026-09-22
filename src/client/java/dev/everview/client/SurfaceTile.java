package dev.everview.client;

/**
 * One cached M1.2 terrain tile.
 *
 * Geometry is built independently of the player's position so the same tile can be
 * reused while the camera moves. "complete" means every sampled column had a loaded
 * client chunk; fluids may still intentionally create holes.
 */
public record SurfaceTile(
        int tileX,
        int tileZ,
        int[] vertices,
        int cellCount,
        int minY,
        int maxY,
        boolean complete
) {
    public int vertexCount() {
        return vertices.length / 3;
    }

    public boolean isEmpty() {
        return cellCount == 0 || vertices.length == 0;
    }

    public int minX() {
        return tileX * LoadedSurfaceSampler.TILE_SIZE;
    }

    public int minZ() {
        return tileZ * LoadedSurfaceSampler.TILE_SIZE;
    }

    public int maxX() {
        return minX() + LoadedSurfaceSampler.TILE_SIZE;
    }

    public int maxZ() {
        return minZ() + LoadedSurfaceSampler.TILE_SIZE;
    }
}
