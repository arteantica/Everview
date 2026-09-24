package dev.everview.client;

/**
 * Persistent primitive metadata alongside the position/color buffer. An additive shader
 * stream can upload it without worldgen or remeshing: stable material ID, wet/ice class,
 * face normal, plus the region origin and draw offsets already held by the render state.
 * Current opaque rendering does not claim Iris shader-pack integration.
 */
public record TerrainSurfaceData(byte[] materials, int[] normalAndClass) {
    static TerrainSurfaceData from(EverviewGpuTileCache.PreparedGeometry geometry) {
        int quads = geometry.indexCount() / 6;
        byte[] materials = new byte[quads];
        int[] normal = new int[quads];
        int[] v = geometry.vertices();
        for (int q = 0; q < quads; q++) {
            int k = q * 12;
            materials[q] = geometry.materials()[q * 4];
            double ax = v[k + 3] - v[k], ay = v[k + 4] - v[k + 1], az = v[k + 5] - v[k + 2];
            double bx = v[k + 6] - v[k], by = v[k + 7] - v[k + 1], bz = v[k + 8] - v[k + 2];
            double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            double length = Math.max(1, Math.sqrt(nx * nx + ny * ny + nz * nz));
            int flags = materials[q] == MinecraftSurfacePalette.MATERIAL_WATER ? 1
                    : materials[q] == MinecraftSurfacePalette.MATERIAL_ICE ? 2 : 0;
            normal[q] = (Math.round((float) (nx / length * 127)) & 255)
                    | ((Math.round((float) (ny / length * 127)) & 255) << 8)
                    | ((Math.round((float) (nz / length * 127)) & 255) << 16) | (flags << 24);
        }
        return new TerrainSurfaceData(materials, normal);
    }
    public long bytes() { return materials.length + normalAndClass.length * 4L; }
}
