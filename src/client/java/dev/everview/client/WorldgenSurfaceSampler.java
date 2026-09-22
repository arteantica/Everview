package dev.everview.client;

import dev.everview.core.LodTileKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * First true distant-terrain source for Everview.
 *
 * In singleplayer this asks the integrated server's ChunkGenerator for
 * getBaseHeight(). That samples the world's terrain generator directly and does
 * not load or generate the corresponding chunks. Work is executed on the
 * integrated server thread, one 128-block tile at a time, so custom generators
 * do not need to be assumed thread-safe.
 *
 * This milestone intentionally samples only the generator surface: no trees,
 * structures, player builds, or cave openings yet.
 */
public final class WorldgenSurfaceSampler {
    public static final int SAMPLE_SPACING = 16;
    public static final int TILE_SIZE = 128;
    public static final int MIN_INNER_RADIUS = 384;
    public static final int OUTER_RADIUS = 1_024;

    private static final int LOD_LEVEL = 1;
    private static final int CACHE_LIMIT = 384;

    private static final Map<LodTileKey, WorldgenSurfaceTile> CACHE =
            new LinkedHashMap<>(256, 0.75F, true);
    private static final ConcurrentLinkedQueue<CompletedTile> COMPLETED =
            new ConcurrentLinkedQueue<>();

    private static volatile WorldgenSurfaceSnapshot snapshot = WorldgenSurfaceSnapshot.EMPTY;
    private static volatile long activeTaskId;
    private static volatile LodTileKey inFlightKey;

    private static ClientLevel lastClientLevel;
    private static MinecraftServer lastServer;
    private static int lastCenterTileX = Integer.MIN_VALUE;
    private static int lastCenterTileZ = Integer.MIN_VALUE;
    private static int lastInnerRadius = Integer.MIN_VALUE;
    private static long epoch;
    private static long nextTaskId;
    private static double lastGenerationMs;
    private static int generatedTileCount;
    private static List<LodTileKey> wantedKeys = List.of();

    private WorldgenSurfaceSampler() {
    }

    public static WorldgenSurfaceSnapshot snapshot() {
        return snapshot;
    }

    public static void tick(Minecraft client) {
        ClientLevel clientLevel = client.level;
        MinecraftServer server = client.getSingleplayerServer();

        if (clientLevel == null || client.player == null || server == null) {
            if (lastClientLevel != null || lastServer != null) {
                reset();
            }
            snapshot = WorldgenSurfaceSnapshot.EMPTY;
            return;
        }

        if (clientLevel != lastClientLevel || server != lastServer) {
            reset();
            lastClientLevel = clientLevel;
            lastServer = server;
        }

        boolean changed = drainCompleted();

        int centerX = client.player.getBlockX();
        int centerZ = client.player.getBlockZ();
        int centerTileX = Math.floorDiv(centerX, TILE_SIZE);
        int centerTileZ = Math.floorDiv(centerZ, TILE_SIZE);

        int vanillaRadius = client.options.getEffectiveRenderDistance() * 16;
        int innerRadius = Math.max(MIN_INNER_RADIUS, vanillaRadius + 64);
        innerRadius = Math.min(innerRadius, OUTER_RADIUS - TILE_SIZE);

        if (centerTileX != lastCenterTileX
                || centerTileZ != lastCenterTileZ
                || innerRadius != lastInnerRadius) {
            lastCenterTileX = centerTileX;
            lastCenterTileZ = centerTileZ;
            lastInnerRadius = innerRadius;
            wantedKeys = buildWantedKeys(centerX, centerZ, innerRadius);
            changed = true;
        }

        if (activeTaskId == 0L) {
            LodTileKey next = findNextMissing();
            if (next != null) {
                schedule(server, clientLevel.dimension(), next);
                changed = true;
            }
        }

        if (changed) {
            rebuildSnapshot(innerRadius);
        } else if (snapshot.taskInFlight() != (activeTaskId != 0L)) {
            rebuildSnapshot(innerRadius);
        }
    }

    private static void reset() {
        epoch++;
        CACHE.clear();
        COMPLETED.clear();
        wantedKeys = List.of();
        lastClientLevel = null;
        lastServer = null;
        lastCenterTileX = Integer.MIN_VALUE;
        lastCenterTileZ = Integer.MIN_VALUE;
        lastInnerRadius = Integer.MIN_VALUE;
        lastGenerationMs = 0.0;
        generatedTileCount = 0;

        // Invalidate an old task without letting its finally block clear a newer one.
        activeTaskId = 0L;
        inFlightKey = null;
    }

    private static boolean drainCompleted() {
        boolean changed = false;
        CompletedTile completed;

        while ((completed = COMPLETED.poll()) != null) {
            if (completed.epoch() != epoch) {
                continue;
            }

            CACHE.put(completed.key(), completed.tile());
            lastGenerationMs = completed.tile().generationMs();
            generatedTileCount++;
            changed = true;
        }

        if (changed) {
            trimCache();
        }

        return changed;
    }

    private static List<LodTileKey> buildWantedKeys(
            int centerX,
            int centerZ,
            int innerRadius
    ) {
        int minTileX = Math.floorDiv(centerX - OUTER_RADIUS, TILE_SIZE);
        int maxTileX = Math.floorDiv(centerX + OUTER_RADIUS, TILE_SIZE);
        int minTileZ = Math.floorDiv(centerZ - OUTER_RADIUS, TILE_SIZE);
        int maxTileZ = Math.floorDiv(centerZ + OUTER_RADIUS, TILE_SIZE);

        List<LodTileKey> keys = new ArrayList<>();

        for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
            for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                if (tileIntersectsAnnulus(tileX, tileZ, centerX, centerZ, innerRadius, OUTER_RADIUS)) {
                    keys.add(new LodTileKey(LOD_LEVEL, tileX, tileZ));
                }
            }
        }

        keys.sort(Comparator.comparingLong(key -> tileCenterDistanceSq(key, centerX, centerZ)));
        return List.copyOf(keys);
    }

    private static boolean tileIntersectsAnnulus(
            int tileX,
            int tileZ,
            int centerX,
            int centerZ,
            int innerRadius,
            int outerRadius
    ) {
        int minX = tileX * TILE_SIZE;
        int minZ = tileZ * TILE_SIZE;
        int maxX = minX + TILE_SIZE;
        int maxZ = minZ + TILE_SIZE;

        int nearestX = Math.max(minX, Math.min(centerX, maxX));
        int nearestZ = Math.max(minZ, Math.min(centerZ, maxZ));
        long nearDx = (long) nearestX - centerX;
        long nearDz = (long) nearestZ - centerZ;
        long nearestSq = nearDx * nearDx + nearDz * nearDz;

        long farDx = Math.max(Math.abs((long) minX - centerX), Math.abs((long) maxX - centerX));
        long farDz = Math.max(Math.abs((long) minZ - centerZ), Math.abs((long) maxZ - centerZ));
        long farthestSq = farDx * farDx + farDz * farDz;

        long innerSq = (long) innerRadius * innerRadius;
        long outerSq = (long) outerRadius * outerRadius;

        return nearestSq <= outerSq && farthestSq >= innerSq;
    }

    private static long tileCenterDistanceSq(LodTileKey key, int centerX, int centerZ) {
        long tileCenterX = (long) key.tileX() * TILE_SIZE + TILE_SIZE / 2L;
        long tileCenterZ = (long) key.tileZ() * TILE_SIZE + TILE_SIZE / 2L;
        long dx = tileCenterX - centerX;
        long dz = tileCenterZ - centerZ;
        return dx * dx + dz * dz;
    }

    private static LodTileKey findNextMissing() {
        LodTileKey pending = inFlightKey;

        for (LodTileKey key : wantedKeys) {
            if (key.equals(pending)) {
                continue;
            }
            if (!CACHE.containsKey(key)) {
                return key;
            }
        }

        return null;
    }

    private static void schedule(
            MinecraftServer server,
            ResourceKey<Level> dimension,
            LodTileKey key
    ) {
        long taskId = ++nextTaskId;
        long taskEpoch = epoch;
        activeTaskId = taskId;
        inFlightKey = key;

        try {
            server.execute(() -> {
                try {
                    ServerLevel level = server.getLevel(dimension);
                    if (level == null || taskEpoch != epoch) {
                        return;
                    }

                    WorldgenSurfaceTile tile = buildTile(level, key.tileX(), key.tileZ());
                    COMPLETED.add(new CompletedTile(taskEpoch, key, tile));
                } catch (Throwable throwable) {
                    EverviewClient.LOGGER.warn(
                            "Everview distant worldgen tile failed at {}, {}",
                            key.tileX(),
                            key.tileZ(),
                            throwable
                    );
                } finally {
                    if (activeTaskId == taskId) {
                        activeTaskId = 0L;
                        inFlightKey = null;
                    }
                }
            });
        } catch (Throwable throwable) {
            if (activeTaskId == taskId) {
                activeTaskId = 0L;
                inFlightKey = null;
            }
            EverviewClient.LOGGER.warn("Everview could not schedule distant worldgen tile", throwable);
        }
    }

    private static WorldgenSurfaceTile buildTile(ServerLevel level, int tileX, int tileZ) {
        long start = System.nanoTime();

        int originX = tileX * TILE_SIZE;
        int originZ = tileZ * TILE_SIZE;
        int samplesAcross = TILE_SIZE / SAMPLE_SPACING + 1;
        int[] heights = new int[samplesAcross * samplesAcross];

        ServerChunkCache chunks = level.getChunkSource();
        var generator = chunks.getGenerator();
        var randomState = chunks.randomState();

        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (int gz = 0; gz < samplesAcross; gz++) {
            int worldZ = originZ + gz * SAMPLE_SPACING;

            for (int gx = 0; gx < samplesAcross; gx++) {
                int worldX = originX + gx * SAMPLE_SPACING;
                int y = generator.getBaseHeight(
                        worldX,
                        worldZ,
                        Heightmap.Types.WORLD_SURFACE_WG,
                        level,
                        randomState
                );

                y = Math.max(level.getMinY(), Math.min(level.getMaxY(), y));
                heights[gz * samplesAcross + gx] = y;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }

        int cellsAcross = samplesAcross - 1;
        int cellCount = cellsAcross * cellsAcross;
        int[] vertices = new int[cellCount * 12];
        int out = 0;

        for (int gz = 0; gz < cellsAcross; gz++) {
            int z0 = originZ + gz * SAMPLE_SPACING;
            int z1 = z0 + SAMPLE_SPACING;

            for (int gx = 0; gx < cellsAcross; gx++) {
                int x0 = originX + gx * SAMPLE_SPACING;
                int x1 = x0 + SAMPLE_SPACING;

                int y00 = heights[gz * samplesAcross + gx];
                int y10 = heights[gz * samplesAcross + gx + 1];
                int y01 = heights[(gz + 1) * samplesAcross + gx];
                int y11 = heights[(gz + 1) * samplesAcross + gx + 1];

                vertices[out++] = x0;
                vertices[out++] = y00;
                vertices[out++] = z0;

                vertices[out++] = x0;
                vertices[out++] = y01;
                vertices[out++] = z1;

                vertices[out++] = x1;
                vertices[out++] = y11;
                vertices[out++] = z1;

                vertices[out++] = x1;
                vertices[out++] = y10;
                vertices[out++] = z0;
            }
        }

        return new WorldgenSurfaceTile(
                tileX,
                tileZ,
                vertices,
                cellCount,
                minY,
                maxY,
                level.getSeaLevel(),
                System.nanoTime() - start
        );
    }

    private static void trimCache() {
        Iterator<LodTileKey> iterator = CACHE.keySet().iterator();

        while (CACHE.size() > CACHE_LIMIT && iterator.hasNext()) {
            LodTileKey key = iterator.next();
            if (wantedKeys.contains(key)) {
                continue;
            }
            iterator.remove();
        }
    }

    private static void rebuildSnapshot(int innerRadius) {
        List<WorldgenSurfaceTile> active = new ArrayList<>();

        for (LodTileKey key : wantedKeys) {
            WorldgenSurfaceTile tile = CACHE.get(key);
            if (tile != null) {
                active.add(tile);
            }
        }

        snapshot = new WorldgenSurfaceSnapshot(
                active,
                wantedKeys.size(),
                CACHE.size(),
                innerRadius,
                OUTER_RADIUS,
                SAMPLE_SPACING,
                true,
                activeTaskId != 0L,
                lastGenerationMs,
                generatedTileCount
        );
    }

    private record CompletedTile(
            long epoch,
            LodTileKey key,
            WorldgenSurfaceTile tile
    ) {
    }
}
