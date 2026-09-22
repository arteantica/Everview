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
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Budgeted progressive distant-worldgen sampler.
 *
 * M2.2 adds multiple rings while preserving M2.1's one-slice-at-a-time server
 * budget. Each ring doubles tile size and sample spacing, so each tile still
 * contains an 8x8 quad grid even as coverage expands exponentially.
 */
public final class WorldgenSurfaceSampler {
    public static final int MIN_INNER_RADIUS = 384;
    public static final int MAX_OUTER_RADIUS = 4_096;

    public static final long SLICE_BUDGET_NANOS = 1_500_000L;
    public static final int MAX_SAMPLES_PER_SLICE = 12;

    private static final int CACHE_LIMIT = 768;

    private static final Map<LodTileKey, WorldgenSurfaceTile> CACHE =
            new LinkedHashMap<>(512, 0.75F, true);
    private static final ConcurrentLinkedQueue<CompletedTile> COMPLETED =
            new ConcurrentLinkedQueue<>();

    private static volatile WorldgenSurfaceSnapshot snapshot = WorldgenSurfaceSnapshot.EMPTY;
    private static volatile long activeSliceId;
    private static volatile double lastSliceMs;
    private static volatile int lastSliceSamples;

    private static ClientLevel lastClientLevel;
    private static MinecraftServer lastServer;
    private static int lastAnchorX = Integer.MIN_VALUE;
    private static int lastAnchorZ = Integer.MIN_VALUE;
    private static int lastInnerRadius = Integer.MIN_VALUE;

    private static long epoch;
    private static long nextSliceId;
    private static double lastGenerationMs;
    private static int generatedTileCount;

    private static List<WorldgenLodRing> activeRings = List.of();
    private static List<WantedTile> wantedTiles = List.of();
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

        int vanillaRadius = client.options.getEffectiveRenderDistance() * 16;
        int innerRadius = Math.max(MIN_INNER_RADIUS, vanillaRadius + 64);
        innerRadius = Math.min(innerRadius, 896);

        // The coarsest ring moves in 512-block tile steps. Rebuild desired sets
        // only when the camera crosses that grid, while per-quad radius clipping
        // in the renderer keeps the circular handoff centered on the camera.
        int anchorX = Math.floorDiv(centerX, 128) * 128;
        int anchorZ = Math.floorDiv(centerZ, 128) * 128;

        if (anchorX != lastAnchorX
                || anchorZ != lastAnchorZ
                || innerRadius != lastInnerRadius) {
            lastAnchorX = anchorX;
            lastAnchorZ = anchorZ;
            lastInnerRadius = innerRadius;
            activeRings = createRings(innerRadius);
            wantedTiles = buildWantedTiles(centerX, centerZ, activeRings);
        }

        if (currentJob != null
                && activeSliceId == 0L
                && !containsWantedKey(currentJob.key)) {
            currentJob = null;
        }

        if (currentJob != null && currentJob.failed && activeSliceId == 0L) {
            currentJob = null;
        }

        if (currentJob == null && activeSliceId == 0L) {
            WantedTile next = findNextMissing();
            if (next != null) {
                currentJob = new GenerationJob(next.key(), next.ring());
            }
        }

        if (currentJob != null && activeSliceId == 0L) {
            scheduleSlice(server, clientLevel.dimension(), currentJob);
        }

        rebuildSnapshot();
    }

    private static List<WorldgenLodRing> createRings(int innerRadius) {
        return List.of(
                new WorldgenLodRing(1, innerRadius, 1_024, 128, 16),
                new WorldgenLodRing(2, 1_024, 2_048, 256, 32),
                new WorldgenLodRing(3, 2_048, 4_096, 512, 64)
        );
    }

    private static void reset() {
        epoch++;
        CACHE.clear();
        COMPLETED.clear();
        activeRings = List.of();
        wantedTiles = List.of();
        lastClientLevel = null;
        lastServer = null;
        lastAnchorX = Integer.MIN_VALUE;
        lastAnchorZ = Integer.MIN_VALUE;
        lastInnerRadius = Integer.MIN_VALUE;
        lastGenerationMs = 0.0;
        lastSliceMs = 0.0;
        lastSliceSamples = 0;
        generatedTileCount = 0;
        currentJob = null;
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

    /**
     * Build a nearest-first list inside each ring, then interleave the three
     * lists. This makes coarse horizon coverage appear early instead of forcing
     * the entire 1K ring to finish before 2K/4K generation starts.
     */
    private static List<WantedTile> buildWantedTiles(
            int centerX,
            int centerZ,
            List<WorldgenLodRing> rings
    ) {
        List<List<WantedTile>> perRing = new ArrayList<>();

        for (WorldgenLodRing ring : rings) {
            List<WantedTile> entries = buildRingWantedTiles(centerX, centerZ, ring);
            entries.sort(Comparator.comparingLong(entry ->
                    tileCenterDistanceSq(entry.key(), entry.ring(), centerX, centerZ)));
            perRing.add(entries);
        }

        List<WantedTile> interleaved = new ArrayList<>();
        int index = 0;
        boolean added;

        do {
            added = false;
            for (List<WantedTile> ringEntries : perRing) {
                if (index < ringEntries.size()) {
                    interleaved.add(ringEntries.get(index));
                    added = true;
                }
            }
            index++;
        } while (added);

        return List.copyOf(interleaved);
    }

    private static List<WantedTile> buildRingWantedTiles(
            int centerX,
            int centerZ,
            WorldgenLodRing ring
    ) {
        int tileSize = ring.tileSize();
        int minTileX = Math.floorDiv(centerX - ring.outerRadiusBlocks(), tileSize);
        int maxTileX = Math.floorDiv(centerX + ring.outerRadiusBlocks(), tileSize);
        int minTileZ = Math.floorDiv(centerZ - ring.outerRadiusBlocks(), tileSize);
        int maxTileZ = Math.floorDiv(centerZ + ring.outerRadiusBlocks(), tileSize);

        List<WantedTile> entries = new ArrayList<>();

        for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
            for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                if (tileIntersectsAnnulus(
                        tileX,
                        tileZ,
                        centerX,
                        centerZ,
                        ring.innerRadiusBlocks(),
                        ring.outerRadiusBlocks(),
                        tileSize
                )) {
                    LodTileKey key = new LodTileKey(ring.lodLevel(), tileX, tileZ);
                    entries.add(new WantedTile(key, ring));
                }
            }
        }

        return entries;
    }

    private static boolean tileIntersectsAnnulus(
            int tileX,
            int tileZ,
            int centerX,
            int centerZ,
            int innerRadius,
            int outerRadius,
            int tileSize
    ) {
        int minX = tileX * tileSize;
        int minZ = tileZ * tileSize;
        int maxX = minX + tileSize;
        int maxZ = minZ + tileSize;

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

    private static long tileCenterDistanceSq(
            LodTileKey key,
            WorldgenLodRing ring,
            int centerX,
            int centerZ
    ) {
        long tileCenterX = (long) key.tileX() * ring.tileSize() + ring.tileSize() / 2L;
        long tileCenterZ = (long) key.tileZ() * ring.tileSize() + ring.tileSize() / 2L;
        long dx = tileCenterX - centerX;
        long dz = tileCenterZ - centerZ;
        return dx * dx + dz * dz;
    }

    private static boolean containsWantedKey(LodTileKey key) {
        for (WantedTile wanted : wantedTiles) {
            if (wanted.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static WantedTile findNextMissing() {
        LodTileKey pending = currentJob == null ? null : currentJob.key;

        for (WantedTile wanted : wantedTiles) {
            if (wanted.key().equals(pending)) {
                continue;
            }
            if (!CACHE.containsKey(wanted.key())) {
                return wanted;
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
                            "Everview L{} worldgen slice failed at tile {}, {}",
                            job.ring.lodLevel(),
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
            int worldX = job.originX + gx * job.ring.sampleSpacing();
            int worldZ = job.originZ + gz * job.ring.sampleSpacing();

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
                    job.ring.lodLevel(),
                    job.key.tileX(),
                    job.key.tileZ(),
                    job.ring.tileSize(),
                    job.ring.sampleSpacing(),
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
        int spacing = job.ring.sampleSpacing();

        for (int gz = 0; gz < job.cellsAcross; gz++) {
            int z0 = job.originZ + gz * spacing;
            int z1 = z0 + spacing;

            for (int gx = 0; gx < job.cellsAcross; gx++) {
                int x0 = job.originX + gx * spacing;
                int x1 = x0 + spacing;

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
            if (containsWantedKey(key)) {
                continue;
            }
            iterator.remove();
        }
    }

    private static void rebuildSnapshot() {
        List<WorldgenSurfaceTile> active = new ArrayList<>();
        Map<Integer, Integer> desiredByLevel = new HashMap<>();
        Map<Integer, Integer> readyByLevel = new HashMap<>();

        for (WantedTile wanted : wantedTiles) {
            int level = wanted.ring().lodLevel();
            desiredByLevel.merge(level, 1, Integer::sum);

            WorldgenSurfaceTile tile = CACHE.get(wanted.key());
            if (tile != null) {
                active.add(tile);
                readyByLevel.merge(level, 1, Integer::sum);
            }
        }

        List<WorldgenRingStatus> ringStatuses = new ArrayList<>();
        for (WorldgenLodRing ring : activeRings) {
            ringStatuses.add(new WorldgenRingStatus(
                    ring,
                    desiredByLevel.getOrDefault(ring.lodLevel(), 0),
                    readyByLevel.getOrDefault(ring.lodLevel(), 0)
            ));
        }

        double progress = currentJob == null ? 0.0 : currentJob.progressPercent();
        int currentLevel = currentJob == null ? 0 : currentJob.ring.lodLevel();

        snapshot = new WorldgenSurfaceSnapshot(
                active,
                ringStatuses,
                wantedTiles.size(),
                CACHE.size(),
                true,
                currentJob != null || activeSliceId != 0L,
                lastGenerationMs,
                generatedTileCount,
                SLICE_BUDGET_NANOS / 1_000_000.0,
                lastSliceMs,
                lastSliceSamples,
                progress,
                currentLevel
        );
    }

    private static final class GenerationJob {
        private final LodTileKey key;
        private final WorldgenLodRing ring;
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

        private GenerationJob(LodTileKey key, WorldgenLodRing ring) {
            this.key = key;
            this.ring = ring;
            this.originX = key.tileX() * ring.tileSize();
            this.originZ = key.tileZ() * ring.tileSize();
            this.samplesAcross = ring.samplesAcross();
            this.cellsAcross = ring.cellsAcross();
            this.totalSamples = samplesAcross * samplesAcross;
            this.cellCount = cellsAcross * cellsAcross;
            this.heights = new int[totalSamples];
        }

        private double progressPercent() {
            return nextSample * 100.0 / totalSamples;
        }
    }

    private record WantedTile(
            LodTileKey key,
            WorldgenLodRing ring
    ) {
    }

    private record CompletedTile(
            long epoch,
            LodTileKey key,
            WorldgenSurfaceTile tile
    ) {
    }
}
