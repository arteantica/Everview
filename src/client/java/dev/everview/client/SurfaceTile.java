package dev.everview.client;

/**
 * One temporary M1 terrain tile.
 *
 * The tile still stores CPU-side integer vertices. The next renderer milestone will
 * replace this with compact packed tile data and persistent GPU buffers.
 */
public record SurfaceTile(
        int tileX,
        int tileZ,
        int[] vertices,
        int cellCount,
        int minY,
        int maxY
) {
    public int vertexCount() {
        return vertices.length / 3;
    }

    public boolean isEmpty() {
        return cellCount == 0 || vertices.length == 0;
    }
}
