package dev.everview.client;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/** Run directly as a smoke test for an active mesh compacting during upload. */
public final class WorldgenSurfaceTileRoundTripTest {
    @Test
    void compactedMeshSurvivesConcurrentReads() {
        main(new String[0]);
    }

    public static void main(String[] args) {
        int quads = 4096;
        int[] vertices = new int[quads * 12];
        int[] colors = new int[quads * 4];
        byte[] materials = new byte[quads * 4];
        for (int q = 0; q < quads; q++) {
            int x = q % 64;
            int z = q / 64;
            for (int corner = 0; corner < 4; corner++) {
                int index = q * 12 + corner * 3;
                vertices[index] = x + (corner & 1);
                vertices[index + 1] = 64 + (q % 16);
                vertices[index + 2] = z + (corner / 2);
                colors[q * 4 + corner] = 0x80A080 + (q % 32);
                materials[q * 4 + corner] = (byte) (q % 4);
            }
        }
        WorldgenSurfaceTile tile = new WorldgenSurfaceTile(
                1, -2, 3, 128, 1, WorldgenTileStage.EXACT_APPEARANCE,
                vertices, colors, materials, quads, 40, 90, 63, 10L
        );
        long originalSize = tile.residentMeshBytes();
        CompletableFuture<Long> compacted = CompletableFuture.supplyAsync(
                tile::compactGeometry);
        for (int pass = 0; pass < 32; pass++) {
            WorldgenSurfaceTile.Geometry view = tile.geometry();
            if (!Arrays.equals(vertices, view.vertices())
                    || !Arrays.equals(colors, view.colors())
                    || !Arrays.equals(materials, view.materials())) {
                throw new AssertionError("geometry changed during compaction");
            }
        }
        if (compacted.join() <= 0L
                || tile.residentMeshBytes() >= originalSize
                || tile.vertexCount() != quads * 4
                || tile.compactGeometry() != 0L) {
            throw new AssertionError("compacted mesh residency is incorrect");
        }
        System.out.println("Mesh compaction concurrent round-trip passed");
    }
}
