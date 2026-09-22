package dev.everview.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Arrays;

/**
 * Temporary M1 sampler that only reads chunks already resident in the client cache.
 *
 * It never requests or generates a chunk. This is intentionally not the final far-
 * terrain source; it exists so we can validate the 26.3 render path before adding
 * off-thread worldgen sampling and the persistent Everview cache.
 */
public final class LoadedSurfaceSampler {
    private static final int SAMPLE_SPACING = 8;
    private static final int SAMPLE_RADIUS = 192;
    private static final int INNER_SKIP_RADIUS = 40;
    private static final int INVALID_Y = Integer.MIN_VALUE;

    private static volatile SurfaceSnapshot snapshot = SurfaceSnapshot.EMPTY;

    private static ClientLevel lastLevel;
    private static int lastCenterX = Integer.MIN_VALUE;
    private static int lastCenterZ = Integer.MIN_VALUE;

    private LoadedSurfaceSampler() {
    }

    public static SurfaceSnapshot snapshot() {
        return snapshot;
    }

    public static void tick(Minecraft client) {
        ClientLevel level = client.level;
        if (level == null || client.player == null) {
            snapshot = SurfaceSnapshot.EMPTY;
            lastLevel = null;
            return;
        }

        int playerX = client.player.getBlockX();
        int playerZ = client.player.getBlockZ();
        int centerX = Math.floorDiv(playerX, SAMPLE_SPACING) * SAMPLE_SPACING;
        int centerZ = Math.floorDiv(playerZ, SAMPLE_SPACING) * SAMPLE_SPACING;

        if (level == lastLevel && centerX == lastCenterX && centerZ == lastCenterZ) {
            return;
        }

        lastLevel = level;
        lastCenterX = centerX;
        lastCenterZ = centerZ;
        snapshot = build(level, centerX, centerZ);
    }

    private static SurfaceSnapshot build(ClientLevel level, int centerX, int centerZ) {
        int samplesAcross = (SAMPLE_RADIUS * 2 / SAMPLE_SPACING) + 1;
        int[][] heights = new int[samplesAcross][samplesAcross];

        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (int gz = 0; gz < samplesAcross; gz++) {
            int worldZ = centerZ - SAMPLE_RADIUS + gz * SAMPLE_SPACING;

            for (int gx = 0; gx < samplesAcross; gx++) {
                int worldX = centerX - SAMPLE_RADIUS + gx * SAMPLE_SPACING;
                int y = sampleHeight(level, worldX, worldZ);
                heights[gz][gx] = y;

                if (y != INVALID_Y) {
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        IntVertexBuffer vertices = new IntVertexBuffer(16_384);
        int innerSkipSq = INNER_SKIP_RADIUS * INNER_SKIP_RADIUS;
        int cells = 0;

        for (int gz = 0; gz < samplesAcross - 1; gz++) {
            int z0 = centerZ - SAMPLE_RADIUS + gz * SAMPLE_SPACING;
            int z1 = z0 + SAMPLE_SPACING;

            for (int gx = 0; gx < samplesAcross - 1; gx++) {
                int x0 = centerX - SAMPLE_RADIUS + gx * SAMPLE_SPACING;
                int x1 = x0 + SAMPLE_SPACING;

                int relCenterX = ((x0 + x1) / 2) - centerX;
                int relCenterZ = ((z0 + z1) / 2) - centerZ;
                if (relCenterX * relCenterX + relCenterZ * relCenterZ < innerSkipSq) {
                    continue;
                }

                int y00 = heights[gz][gx];
                int y10 = heights[gz][gx + 1];
                int y01 = heights[gz + 1][gx];
                int y11 = heights[gz + 1][gx + 1];

                if (y00 == INVALID_Y || y10 == INVALID_Y || y01 == INVALID_Y || y11 == INVALID_Y) {
                    continue;
                }

                // debugQuads expects four vertices per cell. Winding is chosen so the
                // upper face is visible with normal culling.
                vertices.add(x0, y00, z0);
                vertices.add(x0, y01, z1);
                vertices.add(x1, y11, z1);
                vertices.add(x1, y10, z0);
                cells++;
            }
        }

        if (cells == 0) {
            return SurfaceSnapshot.EMPTY;
        }

        return new SurfaceSnapshot(
                vertices.toArray(),
                cells,
                minY == Integer.MAX_VALUE ? level.getMinY() : minY,
                maxY == Integer.MIN_VALUE ? level.getMaxY() : maxY
        );
    }

    private static int sampleHeight(ClientLevel level, int worldX, int worldZ) {
        int chunkX = Math.floorDiv(worldX, 16);
        int chunkZ = Math.floorDiv(worldZ, 16);

        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            return INVALID_Y;
        }

        int localX = Math.floorMod(worldX, 16);
        int localZ = Math.floorMod(worldZ, 16);
        return chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ);
    }

    private static final class IntVertexBuffer {
        private int[] data;
        private int size;

        private IntVertexBuffer(int initialCapacity) {
            data = new int[initialCapacity];
        }

        private void add(int x, int y, int z) {
            ensureCapacity(size + 3);
            data[size++] = x;
            data[size++] = y;
            data[size++] = z;
        }

        private void ensureCapacity(int wanted) {
            if (wanted <= data.length) {
                return;
            }

            int next = Math.max(wanted, data.length * 2);
            data = Arrays.copyOf(data, next);
        }

        private int[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }
}
