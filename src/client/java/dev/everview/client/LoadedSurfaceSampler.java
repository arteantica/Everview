package dev.everview.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * M1.1 loaded-chunk sampler.
 *
 * This still never requests or generates a chunk. The important change from M1 is
 * that the surface is now partitioned into stable 64x64-block tiles, which is the
 * unit we will cache, cull and upload independently in later milestones.
 */
public final class LoadedSurfaceSampler {
    public static final int SAMPLE_SPACING = 4;
    public static final int SAMPLE_RADIUS = 256;
    public static final int TILE_SIZE = 64;
    public static final int INNER_SKIP_RADIUS = 48;

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

        SurfaceSnapshot built = build(level, centerX, centerZ);
        snapshot = built;
        EverviewMetrics.recordSnapshot(built);
    }

    private static SurfaceSnapshot build(ClientLevel level, int centerX, int centerZ) {
        long start = System.nanoTime();

        int minTileX = Math.floorDiv(centerX - SAMPLE_RADIUS, TILE_SIZE);
        int maxTileX = Math.floorDiv(centerX + SAMPLE_RADIUS - 1, TILE_SIZE);
        int minTileZ = Math.floorDiv(centerZ - SAMPLE_RADIUS, TILE_SIZE);
        int maxTileZ = Math.floorDiv(centerZ + SAMPLE_RADIUS - 1, TILE_SIZE);

        List<SurfaceTile> tiles = new ArrayList<>();
        int totalCells = 0;
        int totalVertices = 0;
        int globalMinY = Integer.MAX_VALUE;
        int globalMaxY = Integer.MIN_VALUE;

        for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
            for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                SurfaceTile tile = buildTile(level, centerX, centerZ, tileX, tileZ);
                if (tile.isEmpty()) {
                    continue;
                }

                tiles.add(tile);
                totalCells += tile.cellCount();
                totalVertices += tile.vertexCount();
                globalMinY = Math.min(globalMinY, tile.minY());
                globalMaxY = Math.max(globalMaxY, tile.maxY());
            }
        }

        long elapsed = System.nanoTime() - start;

        if (tiles.isEmpty()) {
            return new SurfaceSnapshot(
                    List.of(),
                    0,
                    0,
                    0,
                    1,
                    SAMPLE_RADIUS,
                    SAMPLE_SPACING,
                    elapsed
            );
        }

        return new SurfaceSnapshot(
                tiles,
                totalCells,
                totalVertices,
                globalMinY,
                globalMaxY,
                SAMPLE_RADIUS,
                SAMPLE_SPACING,
                elapsed
        );
    }

    private static SurfaceTile buildTile(
            ClientLevel level,
            int centerX,
            int centerZ,
            int tileX,
            int tileZ
    ) {
        int originX = tileX * TILE_SIZE;
        int originZ = tileZ * TILE_SIZE;
        int samplesAcross = TILE_SIZE / SAMPLE_SPACING + 1;
        int[][] heights = new int[samplesAcross][samplesAcross];

        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (int gz = 0; gz < samplesAcross; gz++) {
            int worldZ = originZ + gz * SAMPLE_SPACING;

            for (int gx = 0; gx < samplesAcross; gx++) {
                int worldX = originX + gx * SAMPLE_SPACING;
                int y = sampleSolidSurface(level, worldX, worldZ);
                heights[gz][gx] = y;

                if (y != INVALID_Y) {
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        IntVertexBuffer vertices = new IntVertexBuffer(4_096);
        int innerSkipSq = INNER_SKIP_RADIUS * INNER_SKIP_RADIUS;
        int maxRadiusSq = SAMPLE_RADIUS * SAMPLE_RADIUS;
        int cells = 0;

        for (int gz = 0; gz < samplesAcross - 1; gz++) {
            int z0 = originZ + gz * SAMPLE_SPACING;
            int z1 = z0 + SAMPLE_SPACING;

            for (int gx = 0; gx < samplesAcross - 1; gx++) {
                int x0 = originX + gx * SAMPLE_SPACING;
                int x1 = x0 + SAMPLE_SPACING;

                int relCenterX = ((x0 + x1) / 2) - centerX;
                int relCenterZ = ((z0 + z1) / 2) - centerZ;
                int distanceSq = relCenterX * relCenterX + relCenterZ * relCenterZ;

                if (distanceSq < innerSkipSq || distanceSq > maxRadiusSq) {
                    continue;
                }

                int y00 = heights[gz][gx];
                int y10 = heights[gz][gx + 1];
                int y01 = heights[gz + 1][gx];
                int y11 = heights[gz + 1][gx + 1];

                if (y00 == INVALID_Y || y10 == INVALID_Y || y01 == INVALID_Y || y11 == INVALID_Y) {
                    continue;
                }

                vertices.add(x0, y00, z0);
                vertices.add(x0, y01, z1);
                vertices.add(x1, y11, z1);
                vertices.add(x1, y10, z0);
                cells++;
            }
        }

        if (cells == 0) {
            return new SurfaceTile(tileX, tileZ, new int[0], 0, 0, 1);
        }

        return new SurfaceTile(
                tileX,
                tileZ,
                vertices.toArray(),
                cells,
                minY == Integer.MAX_VALUE ? level.getMinY() : minY,
                maxY == Integer.MIN_VALUE ? level.getMaxY() : maxY
        );
    }

    /**
     * Returns the solid surface height for already-loaded terrain. Water/lava samples
     * are rejected for this milestone, preventing the M1 "green tablecloth" over oceans.
     */
    private static int sampleSolidSurface(ClientLevel level, int worldX, int worldZ) {
        int chunkX = Math.floorDiv(worldX, 16);
        int chunkZ = Math.floorDiv(worldZ, 16);

        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            return INVALID_Y;
        }

        int localX = Math.floorMod(worldX, 16);
        int localZ = Math.floorMod(worldZ, 16);
        int surfaceY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, localX, localZ);

        // Heightmaps can still land on exposed tree logs/branches. Walk downward
        // through vegetation so the diagnostic mesh follows the terrain instead
        // of forming tents over tree canopies.
        BlockPos.MutableBlockPos topBlock =
                new BlockPos.MutableBlockPos(worldX, surfaceY - 1, worldZ);

        for (int skipped = 0; skipped < 32 && topBlock.getY() >= level.getMinY(); skipped++) {
            var state = chunk.getBlockState(topBlock);

            if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) {
                topBlock.move(0, -1, 0);
                continue;
            }

            if (!state.getFluidState().isEmpty()) {
                return INVALID_Y;
            }

            return topBlock.getY() + 1;
        }

        return INVALID_Y;
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
