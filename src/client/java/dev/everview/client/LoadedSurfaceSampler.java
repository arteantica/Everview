package dev.everview.client;

import dev.everview.core.LodTileKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M1.2 loaded-chunk sampler with a persistent CPU tile cache.
 *
 * The key change from M1.1 is that tiles are no longer rebuilt whenever the player
 * moves a few blocks. A tile is built once, kept in an LRU cache, and reused until
 * it is evicted. The active tile set changes only when the player crosses a 64-block
 * tile boundary (or when an incomplete tile is retried after nearby chunks load).
 */
public final class LoadedSurfaceSampler {
    public static final int SAMPLE_SPACING = 4;
    public static final int SAMPLE_RADIUS = 256;
    public static final int TILE_SIZE = 64;
    public static final int INNER_SKIP_RADIUS = 48;

    private static final int CACHE_LIMIT = 256;
    private static final int INCOMPLETE_RETRY_TICKS = 10;

    private static final int MISSING_Y = Integer.MIN_VALUE;
    private static final int SKIP_Y = Integer.MIN_VALUE + 1;

    private static final Map<LodTileKey, SurfaceTile> CACHE =
            new LinkedHashMap<>(128, 0.75F, true);

    private static volatile SurfaceSnapshot snapshot = SurfaceSnapshot.EMPTY;

    private static ClientLevel lastLevel;
    private static int lastCenterTileX = Integer.MIN_VALUE;
    private static int lastCenterTileZ = Integer.MIN_VALUE;
    private static int tickCounter;
    private static int nextIncompleteRetryTick;
    private static boolean hasIncompleteActiveTiles;

    private LoadedSurfaceSampler() {
    }

    public static SurfaceSnapshot snapshot() {
        return snapshot;
    }

    public static void tick(Minecraft client) {
        tickCounter++;

        ClientLevel level = client.level;
        if (level == null || client.player == null) {
            reset();
            return;
        }

        if (level != lastLevel) {
            CACHE.clear();
            lastLevel = level;
            lastCenterTileX = Integer.MIN_VALUE;
            lastCenterTileZ = Integer.MIN_VALUE;
            hasIncompleteActiveTiles = false;
        }

        int playerX = client.player.getBlockX();
        int playerZ = client.player.getBlockZ();
        int centerTileX = Math.floorDiv(playerX, TILE_SIZE);
        int centerTileZ = Math.floorDiv(playerZ, TILE_SIZE);

        boolean crossedTileBoundary =
                centerTileX != lastCenterTileX || centerTileZ != lastCenterTileZ;
        boolean retryIncomplete =
                hasIncompleteActiveTiles && tickCounter >= nextIncompleteRetryTick;

        if (!crossedTileBoundary && !retryIncomplete) {
            return;
        }

        lastCenterTileX = centerTileX;
        lastCenterTileZ = centerTileZ;

        SurfaceSnapshot built = buildActiveSnapshot(level, playerX, playerZ);
        snapshot = built;
        hasIncompleteActiveTiles = built.incompleteTiles() > 0;
        nextIncompleteRetryTick = tickCounter + INCOMPLETE_RETRY_TICKS;
        EverviewMetrics.recordSnapshot(built);
    }

    private static void reset() {
        snapshot = SurfaceSnapshot.EMPTY;
        CACHE.clear();
        lastLevel = null;
        lastCenterTileX = Integer.MIN_VALUE;
        lastCenterTileZ = Integer.MIN_VALUE;
        hasIncompleteActiveTiles = false;
    }

    private static SurfaceSnapshot buildActiveSnapshot(ClientLevel level, int centerX, int centerZ) {
        long start = System.nanoTime();

        int minTileX = Math.floorDiv(centerX - SAMPLE_RADIUS, TILE_SIZE);
        int maxTileX = Math.floorDiv(centerX + SAMPLE_RADIUS, TILE_SIZE);
        int minTileZ = Math.floorDiv(centerZ - SAMPLE_RADIUS, TILE_SIZE);
        int maxTileZ = Math.floorDiv(centerZ + SAMPLE_RADIUS, TILE_SIZE);

        List<SurfaceTile> active = new ArrayList<>();
        int totalCells = 0;
        int totalVertices = 0;
        int globalMinY = Integer.MAX_VALUE;
        int globalMaxY = Integer.MIN_VALUE;
        int newTilesBuilt = 0;
        int cacheHits = 0;
        int incompleteTiles = 0;

        for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
            for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                if (!tileIntersectsRadius(tileX, tileZ, centerX, centerZ, SAMPLE_RADIUS)) {
                    continue;
                }

                LodTileKey key = new LodTileKey(0, tileX, tileZ);
                SurfaceTile tile = CACHE.get(key);

                if (tile != null) {
                    cacheHits++;
                } else {
                    tile = buildTile(level, tileX, tileZ);
                    newTilesBuilt++;

                    // Only complete tiles are persistent. A partially loaded tile is
                    // usable right now, but is retried later so holes can fill in.
                    if (tile.complete()) {
                        CACHE.put(key, tile);
                    }
                }

                if (!tile.complete()) {
                    incompleteTiles++;
                }

                if (tile.isEmpty()) {
                    continue;
                }

                active.add(tile);
                totalCells += tile.cellCount();
                totalVertices += tile.vertexCount();
                globalMinY = Math.min(globalMinY, tile.minY());
                globalMaxY = Math.max(globalMaxY, tile.maxY());
            }
        }

        int evictions = trimCache();

        long elapsed = System.nanoTime() - start;

        if (active.isEmpty()) {
            return new SurfaceSnapshot(
                    List.of(),
                    0,
                    0,
                    0,
                    1,
                    SAMPLE_RADIUS,
                    SAMPLE_SPACING,
                    elapsed,
                    newTilesBuilt,
                    cacheHits,
                    CACHE.size(),
                    evictions,
                    incompleteTiles
            );
        }

        return new SurfaceSnapshot(
                active,
                totalCells,
                totalVertices,
                globalMinY,
                globalMaxY,
                SAMPLE_RADIUS,
                SAMPLE_SPACING,
                elapsed,
                newTilesBuilt,
                cacheHits,
                CACHE.size(),
                evictions,
                incompleteTiles
        );
    }

    private static boolean tileIntersectsRadius(
            int tileX,
            int tileZ,
            int centerX,
            int centerZ,
            int radius
    ) {
        int minX = tileX * TILE_SIZE;
        int minZ = tileZ * TILE_SIZE;
        int maxX = minX + TILE_SIZE;
        int maxZ = minZ + TILE_SIZE;

        int nearestX = Math.max(minX, Math.min(centerX, maxX));
        int nearestZ = Math.max(minZ, Math.min(centerZ, maxZ));
        long dx = (long) nearestX - centerX;
        long dz = (long) nearestZ - centerZ;

        return dx * dx + dz * dz <= (long) radius * radius;
    }

    private static int trimCache() {
        int evicted = 0;
        Iterator<LodTileKey> iterator = CACHE.keySet().iterator();

        while (CACHE.size() > CACHE_LIMIT && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
            evicted++;
        }

        return evicted;
    }

    private static SurfaceTile buildTile(ClientLevel level, int tileX, int tileZ) {
        int originX = tileX * TILE_SIZE;
        int originZ = tileZ * TILE_SIZE;
        int samplesAcross = TILE_SIZE / SAMPLE_SPACING + 1;
        int[][] heights = new int[samplesAcross][samplesAcross];

        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        boolean complete = true;

        for (int gz = 0; gz < samplesAcross; gz++) {
            int worldZ = originZ + gz * SAMPLE_SPACING;

            for (int gx = 0; gx < samplesAcross; gx++) {
                int worldX = originX + gx * SAMPLE_SPACING;
                int y = sampleSolidSurface(level, worldX, worldZ);
                heights[gz][gx] = y;

                if (y == MISSING_Y) {
                    complete = false;
                } else if (isRenderable(y)) {
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        IntVertexBuffer vertices = new IntVertexBuffer(4_096);
        int cells = 0;

        for (int gz = 0; gz < samplesAcross - 1; gz++) {
            int z0 = originZ + gz * SAMPLE_SPACING;
            int z1 = z0 + SAMPLE_SPACING;

            for (int gx = 0; gx < samplesAcross - 1; gx++) {
                int x0 = originX + gx * SAMPLE_SPACING;
                int x1 = x0 + SAMPLE_SPACING;

                int y00 = heights[gz][gx];
                int y10 = heights[gz][gx + 1];
                int y01 = heights[gz + 1][gx];
                int y11 = heights[gz + 1][gx + 1];

                if (!isRenderable(y00) || !isRenderable(y10)
                        || !isRenderable(y01) || !isRenderable(y11)) {
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
            return new SurfaceTile(tileX, tileZ, new int[0], 0, 0, 1, complete);
        }

        return new SurfaceTile(
                tileX,
                tileZ,
                vertices.toArray(),
                cells,
                minY == Integer.MAX_VALUE ? level.getMinY() : minY,
                maxY == Integer.MIN_VALUE ? level.getMaxY() : maxY,
                complete
        );
    }

    private static boolean isRenderable(int y) {
        return y != MISSING_Y && y != SKIP_Y;
    }

    /**
     * Returns terrain surface height for already-loaded client chunks.
     *
     * MISSING_Y means the client does not have that chunk yet. SKIP_Y means the
     * column is intentionally omitted (currently fluids or vegetation-only fallbacks).
     */
    private static int sampleSolidSurface(ClientLevel level, int worldX, int worldZ) {
        int chunkX = Math.floorDiv(worldX, 16);
        int chunkZ = Math.floorDiv(worldZ, 16);

        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            return MISSING_Y;
        }

        int localX = Math.floorMod(worldX, 16);
        int localZ = Math.floorMod(worldZ, 16);
        int surfaceY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, localX, localZ);

        BlockPos.MutableBlockPos topBlock =
                new BlockPos.MutableBlockPos(worldX, surfaceY - 1, worldZ);

        for (int skipped = 0; skipped < 32 && topBlock.getY() >= level.getMinY(); skipped++) {
            var state = chunk.getBlockState(topBlock);

            if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) {
                topBlock.move(0, -1, 0);
                continue;
            }

            if (!state.getFluidState().isEmpty()) {
                return SKIP_Y;
            }

            return topBlock.getY() + 1;
        }

        return SKIP_Y;
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
