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
 * Budgeted direct-worldgen sampler for Everview.
 *
 * M2.0 proved that ChunkGenerator#getBaseHeight can give us seed-derived terrain
 * beyond loaded chunks without intentionally loading/generating those chunks.
 *
 * M2.1 changes the scheduling model: a 128-block tile is no longer sampled in
 * one server task. Sampling is split across many small server-thread slices with
 * a soft CPU budget. Only one slice can be queued at a time, so Everview cannot
 * build up an unbounded backlog when the integrated server is busy.
 */
public final class WorldgenSurfaceSampler {
    public static final int SAMPLE_SPACING = 16;
    public static final int TILE_SIZE = 128;
    public static final int MIN_INNER_RADIUS = 384;
    public static final int OUTER_RADIUS = 1_024;

    /**
     * Soft budget per integrated-server task. One individual generator sample can
     * still exceed this, so the budget is measured and exposed in the HUD rather
     * than treated as a hard realtime guarantee.
     */
    public static final long SLICE_BUDGET_NANOS = 1_500_000L;
    public static final int MAX_SAMPLES_PER_SLICE = 12;

    private static final int LOD_LEVEL = 1;
    private static final int CACHE_LIMIT = 384;

    private static final Map<LodTileKey, WorldgenSurfaceTile> CACHE =
            new LinkedHashMap<>(256, 0.75F, true);
    private static final ConcurrentLinkedQueue<CompletedTile> COMPLETED =
            new ConcurrentLinkedQueue<>();

    private static volatile WorldgenSurfaceSnapshot snapshot = WorldgenSurfaceSnapshot.EMPTY;
    private static volatile long activeSliceId;
    private static volatile double lastSliceMs;
    private static volatile int lastSliceSamples;

    private static ClientLevel lastClientLevel;
    private static MinecraftServer lastServer;
    private static int lastCenterTileX = Integer.MIN_VALUE;
    private static int lastCenterTileZ = Integer.MIN_VALUE;
    private static int lastInnerRadius = Integer.MIN_VALUE;
    private static long epoch;
    private static long nextSliceId;
    private static double lastGenerationMs;
    private static int generatedTileCount;
    private static List<LodTileKey> wantedKeys = List.of();
    private static GenerationJob currentJob;

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

        drainCompleted();

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
        }

        // If movement made the pending tile irrelevant, abandon it between slices.
        if (currentJob != null
                && activeSliceId == 0L
                && !wantedKeys.contains(currentJob.key)) {
            currentJob = null;
        }

        if (currentJob != null && currentJob.failed && activeSliceId == 0L) {
            currentJob = null;
        }

        if (currentJob == null && activeSliceId == 0L) {
            LodTileKey next = findNextMissing();
            if (next != null) {
                currentJob = new GenerationJob(next);
            }
        }

        if (currentJob != null && activeSliceId == 0L) {
            scheduleSlice(server, clientLevel.dimension(), currentJob);
        }

        rebuildSnapshot(innerRadius);
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
        lastSliceMs = 0.0;
        lastSliceSamples = 0;
        generatedTileCount = 0;
        currentJob = null;

        // An already queued slice may still finish; epoch makes its result stale.
        activeSliceId = 0L;
    }

    private static void drainCompleted() {
        CompletedTile completed;

        while ((completed = COMPLETED.poll()) != null) {
            if (completed.epoch() != epoch) {
                continue;
            }

            CACHE.put(completed.key(), completed.tile());
            lastGenerationMs = completed.tile().generationMs();
            generatedTileCount++;

            if (currentJob != null && currentJob.key.equals(completed.key())) {
                currentJob = null;
            }
        }

        trimCache();
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
        LodTileKey pending = currentJob == null ? null : currentJob.key;

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

    private static void scheduleSlice(
            MinecraftServer server,
            ResourceKey<Level> dimension,
            GenerationJob job
    ) {
        long sliceId = ++nextSliceId;
        long taskEpoch = epoch;
        activeSliceId = sliceId;

        try {
            server.execute(() -> {
                try {
                    ServerLevel level = server.getLevel(dimension);
                    if (level == null || taskEpoch != epoch || job != currentJob) {
                        return;
                    }

                    runSlice(level, job, taskEpoch);
                } catch (Throwable throwable) {
                    job.failed = true;
                    EverviewClient.LOGGER.warn(
                            "Everview budgeted worldgen slice failed at tile {}, {}",
                            job.key.tileX(),
                            job.key.tileZ(),
                            throwable
                    );
                } finally {
                    if (activeSliceId == sliceId) {
                        activeSliceId = 0L;
                    }
                }
            });
        } catch (Throwable throwable) {
            job.failed = true;
            if (activeSliceId == sliceId) {
                activeSliceId = 0L;
            }
            EverviewClient.LOGGER.warn("Everview could not schedule a worldgen slice", throwable);
        }
    }

    private static void runSlice(
            ServerLevel level,
            GenerationJob job,
            long taskEpoch
    ) {
        long sliceStart = System.nanoTime();

        ServerChunkCache chunks = level.getChunkSource();
        var generator = chunks.getGenerator();
        var randomState = chunks.randomState();

        int processed = 0;

        while (job.nextSample < job.totalSamples && processed < MAX_SAMPLES_PER_SLICE) {
            int sampleIndex = job.nextSample;
            int gx = sampleIndex % job.samplesAcross;
            int gz = sampleIndex / job.samplesAcross;
            int worldX = job.originX + gx * SAMPLE_SPACING;
            int worldZ = job.originZ + gz * SAMPLE_SPACING;

            int y = generator.getBaseHeight(
                    worldX,
                    worldZ,
                    Heightmap.Types.WORLD_SURFACE_WG,
                    level,
                    randomState
            );

            y = Math.max(level.getMinY(), Math.min(level.getMaxY(), y));
            job.heights[sampleIndex] = y;
            job.minY = Math.min(job.minY, y);
            job.maxY = Math.max(job.maxY, y);
            job.nextSample = sampleIndex + 1;
            processed++;

            if (System.nanoTime() - sliceStart >= SLICE_BUDGET_NANOS) {
                break;
            }
        }

        long samplingElapsed = System.nanoTime() - sliceStart;
        job.accumulatedNanos += samplingElapsed;

        long sliceElapsed = samplingElapsed;

        if (job.nextSample >= job.totalSamples && taskEpoch == epoch) {
            long meshStart = System.nanoTime();
            int[] vertices = buildVertices(job);
            long meshElapsed = System.nanoTime() - meshStart;

            job.accumulatedNanos += meshElapsed;
            sliceElapsed += meshElapsed;

            WorldgenSurfaceTile tile = new WorldgenSurfaceTile(
                    job.key.tileX(),
                    job.key.tileZ(),
                    vertices,
                    job.cellCount,
                    job.minY,
                    job.maxY,
                    level.getSeaLevel(),
                    job.accumulatedNanos
            );

            COMPLETED.add(new CompletedTile(taskEpoch, job.key, tile));
        }

        lastSliceSamples = processed;
        lastSliceMs = sliceElapsed / 1_000_000.0;
    }

    private static int[] buildVertices(GenerationJob job) {
        int[] vertices = new int[job.cellCount * 12];
        int out = 0;

        for (int gz = 0; gz < job.cellsAcross; gz++) {
            int z0 = job.originZ + gz * SAMPLE_SPACING;
            int z1 = z0 + SAMPLE_SPACING;

            for (int gx = 0; gx < job.cellsAcross; gx++) {
                int x0 = job.originX + gx * SAMPLE_SPACING;
                int x1 = x0 + SAMPLE_SPACING;

                int y00 = job.heights[gz * job.samplesAcross + gx];
                int y10 = job.heights[gz * job.samplesAcross + gx + 1];
                int y01 = job.heights[(gz + 1) * job.samplesAcross + gx];
                int y11 = job.heights[(gz + 1) * job.samplesAcross + gx + 1];

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

        return vertices;
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

        double progress = currentJob == null ? 0.0 : currentJob.progressPercent();

        snapshot = new WorldgenSurfaceSnapshot(
                active,
                wantedKeys.size(),
                CACHE.size(),
                innerRadius,
                OUTER_RADIUS,
                SAMPLE_SPACING,
                true,
                currentJob != null || activeSliceId != 0L,
                lastGenerationMs,
                generatedTileCount,
                SLICE_BUDGET_NANOS / 1_000_000.0,
                lastSliceMs,
                lastSliceSamples,
                progress
        );
    }

    private static final class GenerationJob {
        private final LodTileKey key;
        private final int originX;
        private final int originZ;
        private final int samplesAcross;
        private final int cellsAcross;
        private final int totalSamples;
        private final int cellCount;
        private final int[] heights;

        private volatile int nextSample;
        private volatile boolean failed;
        private int minY = Integer.MAX_VALUE;
        private int maxY = Integer.MIN_VALUE;
        private long accumulatedNanos;

        private GenerationJob(LodTileKey key) {
            this.key = key;
            this.originX = key.tileX() * TILE_SIZE;
            this.originZ = key.tileZ() * TILE_SIZE;
            this.samplesAcross = TILE_SIZE / SAMPLE_SPACING + 1;
            this.cellsAcross = samplesAcross - 1;
            this.totalSamples = samplesAcross * samplesAcross;
            this.cellCount = cellsAcross * cellsAcross;
            this.heights = new int[totalSamples];
        }

        private double progressPercent() {
            return nextSample * 100.0 / totalSamples;
        }
    }

    private record CompletedTile(
            long epoch,
            LodTileKey key,
            WorldgenSurfaceTile tile
    ) {
    }
}
