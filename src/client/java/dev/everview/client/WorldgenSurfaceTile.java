package dev.everview.client;

/**
 * Coarse surface tile sampled directly from the integrated server's chunk
 * generator without loading or generating the corresponding chunks.
 */
public record WorldgenSurfaceTile(
        int lodLevel,
        int tileX,
        int tileZ,
        int tileSize,
        int sampleSpacing,
        int[] vertices,
        int[] colors,
        byte[] materials,
        int cellCount,
        int minY,
        int maxY,
        int seaLevel,
        long generationNanos
) {
    public WorldgenSurfaceTile {
        int vertexCount = vertices.length / 3;

        if (colors.length != vertexCount) {
            throw new IllegalArgumentException("colors must match vertex count");
        }

        if (materials.length != vertexCount) {
            throw new IllegalArgumentException("materials must match vertex count");
        }
    }

    public int vertexCount() {
        return vertices.length / 3;
    }

    public int minX() {
        return tileX * tileSize;
    }

    public int minZ() {
        return tileZ * tileSize;
    }

    public int maxX() {
        return minX() + tileSize;
    }

    public int maxZ() {
        return minZ() + tileSize;
    }

    public double generationMs() {
        return generationNanos / 1_000_000.0;
    }
}
