package dev.everview.client;

/**
 * Immutable CPU-side snapshot used by the temporary M1 renderer.
 *
 * Vertices are absolute integer world coordinates in xyz triplets. We convert them
 * to camera-relative floats only at submission time so large world coordinates do
 * not immediately destroy vertex precision.
 */
public record SurfaceSnapshot(
        int[] vertices,
        int cellCount,
        int minY,
        int maxY
) {
    public static final SurfaceSnapshot EMPTY =
            new SurfaceSnapshot(new int[0], 0, 0, 1);

    public boolean isEmpty() {
        return cellCount == 0 || vertices.length == 0;
    }

    public int vertexCount() {
        return vertices.length / 3;
    }
}
