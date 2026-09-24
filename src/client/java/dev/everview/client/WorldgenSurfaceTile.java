package dev.everview.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Stable metadata with a compactable CPU mesh. GPU uploads and disk writes
 * obtain a temporary geometry view; renderer ownership needs only metadata.
 * The v32 disk format and the identity of a published tile do not change when
 * its mesh is compacted, so snapshot and GPU residency remain stable.
 */
public final class WorldgenSurfaceTile {
    private final int lodLevel;
    private final int tileX;
    private final int tileZ;
    private final int tileSize;
    private final int sampleSpacing;
    private final WorldgenTileStage stage;
    private final int cellCount;
    private final int minY;
    private final int maxY;
    private final int seaLevel;
    private final long generationNanos;
    private final int vertexCount;
    private int[] vertices;
    private int[] colors;
    private byte[] materials;
    private byte[] compressedGeometry;

    public WorldgenSurfaceTile(
            int lodLevel, int tileX, int tileZ, int tileSize,
            int sampleSpacing, WorldgenTileStage stage,
            int[] vertices, int[] colors, byte[] materials,
            int cellCount, int minY, int maxY, int seaLevel,
            long generationNanos
    ) {
        if (stage == null || vertices == null || colors == null
                || materials == null || vertices.length % 3 != 0
                || vertices.length / 3 != colors.length
                || colors.length != materials.length) {
            throw new IllegalArgumentException("invalid tile mesh");
        }
        this.lodLevel = lodLevel;
        this.tileX = tileX;
        this.tileZ = tileZ;
        this.tileSize = tileSize;
        this.sampleSpacing = sampleSpacing;
        this.stage = stage;
        this.vertices = vertices;
        this.colors = colors;
        this.materials = materials;
        this.vertexCount = colors.length;
        this.cellCount = cellCount;
        this.minY = minY;
        this.maxY = maxY;
        this.seaLevel = seaLevel;
        this.generationNanos = generationNanos;
    }

    public int lodLevel() { return lodLevel; }
    public int tileX() { return tileX; }
    public int tileZ() { return tileZ; }
    public int tileSize() { return tileSize; }
    public int sampleSpacing() { return sampleSpacing; }
    public WorldgenTileStage stage() { return stage; }
    public int cellCount() { return cellCount; }
    public int minY() { return minY; }
    public int maxY() { return maxY; }
    public int seaLevel() { return seaLevel; }
    public long generationNanos() { return generationNanos; }
    public int vertexCount() { return vertexCount; }

    public synchronized long residentMeshBytes() {
        return compressedGeometry != null
                ? compressedGeometry.length
                : (long) vertices.length * Integer.BYTES
                        + (long) colors.length * Integer.BYTES
                        + materials.length;
    }

    /** Called off the render thread only after persistence has succeeded. */
    public synchronized long compactGeometry() {
        if (compressedGeometry != null) {
            return 0L;
        }
        long originalBytes = residentMeshBytes();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            // M9.5 treats the CPU copy as a compact backing representation,
            // not the primary render representation. A single low-priority
            // worker pays moderate compression once so large exact meshes do
            // not consume multiple GiB of Java heap while GPU regions own the
            // hot render copy.
            Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
            try (DataOutputStream output = new DataOutputStream(
                    new DeflaterOutputStream(bytes, deflater))) {
                for (int vertex : vertices) {
                    output.writeInt(vertex);
                }
                for (int color : colors) {
                    output.writeInt(color);
                }
                output.write(materials);
            } finally {
                deflater.end();
            }
            byte[] compressed = bytes.toByteArray();
            // Keep compact storage whenever it produces a meaningful saving.
            // M9.4 rejected 25-30% savings and therefore retained many large
            // raw arrays even after persistence.
            if (compressed.length >= originalBytes * 95L / 100L) {
                return 0L;
            }
            compressedGeometry = compressed;
            vertices = null;
            colors = null;
            materials = null;
            return originalBytes - compressed.length;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not compact LOD mesh", exception);
        }
    }

    public synchronized Geometry geometry() {
        if (compressedGeometry == null) {
            return new Geometry(vertices, colors, materials);
        }
        try (DataInputStream input = new DataInputStream(
                new InflaterInputStream(
                        new ByteArrayInputStream(compressedGeometry)))) {
            int[] restoredVertices = new int[vertexCount * 3];
            int[] restoredColors = new int[vertexCount];
            byte[] restoredMaterials = new byte[vertexCount];
            for (int i = 0; i < restoredVertices.length; i++) {
                restoredVertices[i] = input.readInt();
            }
            for (int i = 0; i < restoredColors.length; i++) {
                restoredColors[i] = input.readInt();
            }
            input.readFully(restoredMaterials);
            if (input.read() != -1) {
                throw new IOException("trailing LOD mesh data");
            }
            return new Geometry(
                    restoredVertices, restoredColors, restoredMaterials);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not expand LOD mesh", exception);
        }
    }

    public int[] vertices() { return geometry().vertices(); }
    public int[] colors() { return geometry().colors(); }
    public byte[] materials() { return geometry().materials(); }

    public int minX() { return tileX * tileSize; }
    public int minZ() { return tileZ * tileSize; }
    public int maxX() { return minX() + tileSize; }
    public int maxZ() { return minZ() + tileSize; }
    public double generationMs() { return generationNanos / 1_000_000.0; }

    public record Geometry(int[] vertices, int[] colors, byte[] materials) {
    }
}
