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
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Budgeted progressive distant-worldgen sampler.
 *
 * Progressive distant-worldgen sampler.
 *
 * M2.6 keeps one bounded integrated-server task per client tick, but raises the
 * initial-fill budget from the deliberately conservative 1.5 ms used in M2.1
 * to 4.0 ms. This should cut first-fill time dramatically while still keeping
 * Everview well below Minecraft's 50 ms server-tick budget.
 */
public final class WorldgenSurfaceSampler {
    public static final int MIN_INNER_RADIUS = 256;
    public static final int HANDOFF_OVERLAP_BLOCKS = 32;
    public static final int MAX_OUTER_RADIUS = 16_384;

    public static final long MIN_SLICE_BUDGET_NANOS = 1_000_000L;
    public static final long BASE_SLICE_BUDGET_NANOS = 4_000_000L;
    public static final long MAX_SLICE_BUDGET_NANOS = 6_000_000L;
    public static final int MAX_SAMPLES_PER_SLICE = 48;

    private static final int CACHE_LIMIT = 1_536;

    private static final Map<LodTileKey, WorldgenSurfaceTile> CACHE =
            new LinkedHashMap<>(512, 0.75F, true);
    private static final ConcurrentLinkedQueue<CompletedTile> COMPLETED =
            new ConcurrentLinkedQueue<>();

    private static volatile WorldgenSurfaceSnapshot snapshot = WorldgenSurfaceSnapshot.EMPTY;
    private static volatile long activeSliceId;
    private static volatile double lastSliceMs;
    private static volatile int lastSliceSamples;
    private static volatile long adaptiveSliceBudgetNanos = BASE_SLICE_BUDGET_NANOS;

    private static double serverTickMs;
    private static double clientFrameMs;

    private static CompletableFuture<WorldgenDiskCache.LoadResult> diskLoadFuture;
    private static CompletableFuture<WorldgenDiskCache.SaveResult> diskSaveFuture;
    private static Path diskCachePath;
    private static long diskCacheSeed;
    private static String diskCacheDimension = "";
    private static boolean diskLoadReady;
    private static boolean cacheDirty;
    private static int diskLoadedTiles;
    private static double diskLoadMs;
    private static int diskSavedTiles;
    private static double diskSaveMs;
    private static double diskFileMiB;
    private static String diskCacheStatus = "OFF";

    private static ClientLevel lastClientLevel;
    private static MinecraftServer lastServer;
    private static int lastAnchorX = Integer.MIN_VALUE;
    private static int lastAnchorZ = Integer.MIN_VALUE;
    private static int lastInnerRadius = Integer.MIN_VALUE;

    private static long epoch;
    private static long nextSliceId;
    private static double lastGenerationMs;
    private static int generatedTileCount;
    private static long initialFillStartedNanos;
    private static long initialFillCompletedNanos;

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
            startDiskLoad(server, clientLevel.dimension());
        }

        pollDiskIo();
        updateAdaptiveBudget(client, server);
        drainCompleted();

        int centerX = client.player.getBlockX();
        int centerZ = client.player.getBlockZ();

        int vanillaRadius = client.options.getEffectiveRenderDistance() * 16;
        int innerRadius = Math.max(
                MIN_INNER_RADIUS,
                vanillaRadius - HANDOFF_OVERLAP_BLOCKS
        );
        innerRadius = Math.min(innerRadius, 896);

        // M3.0.1 keeps only a narrow 32-block overlap under the edge of
        // vanilla terrain. This is enough to hide small unloaded-chunk/fog
        // holes without making the coarse LOD visibly take over too early.
        // The renderer biases LOD slightly downward so vanilla wins depth
        // wherever both surfaces exist.
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

            if (initialFillStartedNanos == 0L && !wantedTiles.isEmpty()) {
                initialFillStartedNanos = System.nanoTime();
            }
        }

        if (!diskLoadReady) {
            rebuildSnapshot();
            return;
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
        maybeScheduleDiskSave();
    }

    private static void startDiskLoad(
            MinecraftServer server,
            ResourceKey<Level> dimension
    ) {
        diskCachePath = WorldgenDiskCache.pathFor(server, dimension);
        diskCacheSeed = WorldgenDiskCache.seedFor(server, dimension);
        diskCacheDimension = WorldgenDiskCache.dimensionId(dimension);
        diskCacheStatus = "LOADING";
        diskLoadReady = false;

        Path path = diskCachePath;
        long seed = diskCacheSeed;
        String dimensionId = diskCacheDimension;

        diskLoadFuture = CompletableFuture.supplyAsync(
                () -> WorldgenDiskCache.load(path, seed, dimensionId)
        );
    }

    private static void pollDiskIo() {
        if (diskLoadFuture != null && diskLoadFuture.isDone()) {
            try {
                WorldgenDiskCache.LoadResult result = diskLoadFuture.join();

                for (WorldgenSurfaceTile tile : result.tiles()) {
                    CACHE.put(
                            new LodTileKey(tile.lodLevel(), tile.tileX(), tile.tileZ()),
                            tile
                    );
                }

                diskLoadedTiles = result.tiles().size();
                diskLoadMs = result.elapsedMs();
                diskCacheStatus = result.status();
                diskLoadReady = true;
                trimCache();
            } catch (RuntimeException exception) {
                diskCacheStatus = "LOAD_ERROR";
                diskLoadReady = true;
                EverviewClient.LOGGER.warn("Everview LOD cache load task failed", exception);
            } finally {
                diskLoadFuture = null;
            }
        }

        if (diskSaveFuture != null && diskSaveFuture.isDone()) {
            try {
                WorldgenDiskCache.SaveResult result = diskSaveFuture.join();
                diskSavedTiles = result.tileCount();
                diskSaveMs = result.elapsedMs();
                diskFileMiB = result.bytes() / (1024.0 * 1024.0);
                diskCacheStatus = result.status();

                if (!"SAVED".equals(result.status())) {
                    cacheDirty = true;
                }
            } catch (RuntimeException exception) {
                diskCacheStatus = "SAVE_ERROR";
                cacheDirty = true;
                EverviewClient.LOGGER.warn("Everview LOD cache save task failed", exception);
            } finally {
                diskSaveFuture = null;
            }
        }
    }

    private static void maybeScheduleDiskSave() {
        if (!cacheDirty
                || diskCachePath == null
                || diskSaveFuture != null
                || !snapshot.initialFillComplete()) {
            return;
        }

        List<WorldgenSurfaceTile> tiles = List.copyOf(CACHE.values());
        Path path = diskCachePath;
        long seed = diskCacheSeed;
        String dimension = diskCacheDimension;

        cacheDirty = false;
        diskCacheStatus = "SAVING";
        diskSaveFuture = CompletableFuture.supplyAsync(
                () -> WorldgenDiskCache.save(path, seed, dimension, tiles)
        );
    }

    private static void scheduleDetachedSaveIfDirty() {
        if (!cacheDirty || diskCachePath == null || CACHE.isEmpty() || diskSaveFuture != null) {
            return;
        }

        List<WorldgenSurfaceTile> tiles = List.copyOf(CACHE.values());
        Path path = diskCachePath;
        long seed = diskCacheSeed;
        String dimension = diskCacheDimension;

        CompletableFuture.runAsync(
                () -> WorldgenDiskCache.save(path, seed, dimension, tiles)
        );
    }

    private static void updateAdaptiveBudget(Minecraft client, MinecraftServer server) {
        serverTickMs = server.getAverageTickTimeNanos() / 1_000_000.0;
        clientFrameMs = client.getFrameTimeNs() / 1_000_000.0;

        long target;

        if (serverTickMs < 18.0 && (clientFrameMs <= 0.0 || clientFrameMs < 10.0)) {
            target = MAX_SLICE_BUDGET_NANOS;
        } else if (serverTickMs < 28.0 && (clientFrameMs <= 0.0 || clientFrameMs < 15.0)) {
            target = 5_000_000L;
        } else if (serverTickMs < 38.0 && (clientFrameMs <= 0.0 || clientFrameMs < 24.0)) {
            target = BASE_SLICE_BUDGET_NANOS;
        } else if (serverTickMs < 45.0) {
            target = 2_000_000L;
        } else {
            target = MIN_SLICE_BUDGET_NANOS;
        }

        long step = 250_000L;

        if (adaptiveSliceBudgetNanos < target) {
            adaptiveSliceBudgetNanos =
                    Math.min(target, adaptiveSliceBudgetNanos + step);
        } else if (adaptiveSliceBudgetNanos > target) {
            adaptiveSliceBudgetNanos =
                    Math.max(target, adaptiveSliceBudgetNanos - step);
        }
    }

    private static List<WorldgenLodRing> createRings(int innerRadius) {
        return List.of(
                new WorldgenLodRing(1, innerRadius, 1_024, 128, 16),
                new WorldgenLodRing(2, 1_024, 2_048, 256, 32),
                new WorldgenLodRing(3, 2_048, 4_096, 512, 64),
                new WorldgenLodRing(4, 4_096, 8_192, 1_024, 128),
                new WorldgenLodRing(5, 8_192, 16_384, 2_048, 256)
        );
    }

    private static void reset() {
        scheduleDetachedSaveIfDirty();
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
        initialFillStartedNanos = 0L;
        initialFillCompletedNanos = 0L;
        adaptiveSliceBudgetNanos = BASE_SLICE_BUDGET_NANOS;
        serverTickMs = 0.0;
        clientFrameMs = 0.0;
        diskLoadFuture = null;
        diskSaveFuture = null;
        diskCachePath = null;
        diskCacheSeed = 0L;
        diskCacheDimension = "";
        diskLoadReady = false;
        cacheDirty = false;
        diskLoadedTiles = 0;
        diskLoadMs = 0.0;
        diskSavedTiles = 0;
        diskSaveMs = 0.0;
        diskFileMiB = 0.0;
        diskCacheStatus = "OFF";
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
            cacheDirty = true;

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
        long sliceBudgetNanos = adaptiveSliceBudgetNanos;

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

            var biome = level.getNoiseBiome(worldX >> 2, y >> 2, worldZ >> 2);
            var appearance = MinecraftSurfacePalette.sample(
                    biome,
                    worldX,
                    y,
                    worldZ,
                    level.getSeaLevel()
            );
            job.sampleColors[sampleIndex] = appearance.rgb();
            job.sampleMaterials[sampleIndex] = appearance.material();

            job.minY = Math.min(job.minY, y);
            job.maxY = Math.max(job.maxY, y);
            job.nextSample = sampleIndex + 1;
            processed++;

            if (System.nanoTime() - sliceStart >= sliceBudgetNanos) {
                break;
            }
        }

        long samplingElapsed = System.nanoTime() - sliceStart;
        job.accumulatedNanos += samplingElapsed;
        long sliceElapsed = samplingElapsed;

        if (job.nextSample >= job.totalSamples && taskEpoch == epoch) {
            long meshStart = System.nanoTime();
            MeshData mesh = buildMesh(job);
            long meshElapsed = System.nanoTime() - meshStart;

            job.accumulatedNanos += meshElapsed;
            sliceElapsed += meshElapsed;

            WorldgenSurfaceTile tile = new WorldgenSurfaceTile(
                    job.ring.lodLevel(),
                    job.key.tileX(),
                    job.key.tileZ(),
                    job.ring.tileSize(),
                    job.ring.sampleSpacing(),
                    mesh.vertices(),
                    mesh.colors(),
                    mesh.materials(),
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

    private static MeshData buildMesh(GenerationJob job) {
        int[] vertices = new int[job.cellCount * 12];
        int[] colors = new int[job.cellCount * 4];
        byte[] materials = new byte[job.cellCount * 4];
        int vertexOut = 0;
        int colorOut = 0;
        int spacing = job.ring.sampleSpacing();

        for (int gz = 0; gz < job.cellsAcross; gz++) {
            int z0 = job.originZ + gz * spacing;
            int z1 = z0 + spacing;

            for (int gx = 0; gx < job.cellsAcross; gx++) {
                int x0 = job.originX + gx * spacing;
                int x1 = x0 + spacing;

                int i00 = gz * job.samplesAcross + gx;
                int i10 = i00 + 1;
                int i01 = (gz + 1) * job.samplesAcross + gx;
                int i11 = i01 + 1;

                int y00 = job.heights[i00];
                int y10 = job.heights[i10];
                int y01 = job.heights[i01];
                int y11 = job.heights[i11];

                float dx = ((y10 + y11) - (y00 + y01)) * 0.5F / spacing;
                float dz = ((y01 + y11) - (y00 + y10)) * 0.5F / spacing;
                float invLength = 1.0F / (float) Math.sqrt(dx * dx + 1.0F + dz * dz);

                float nx = -dx * invLength;
                float ny = invLength;
                float nz = -dz * invLength;

                // Fixed northwest/up light gives terrain readable shape before
                // shader-aware lighting is introduced.
                float lightDot = nx * -0.45F + ny * 0.86F + nz * -0.24F;
                float shade = 0.72F + Math.max(0.0F, lightDot) * 0.30F;

                float maxRise = Math.max(
                        Math.max(Math.abs(y10 - y00), Math.abs(y01 - y00)),
                        Math.max(Math.abs(y11 - y10), Math.abs(y11 - y01))
                );
                float steepness = Math.min(1.0F, maxRise / Math.max(1.0F, spacing * 0.95F));

                int c00 = shadeSample(job, i00, shade, steepness);
                int c01 = shadeSample(job, i01, shade, steepness);
                int c11 = shadeSample(job, i11, shade, steepness);
                int c10 = shadeSample(job, i10, shade, steepness);

                vertices[vertexOut++] = x0;
                vertices[vertexOut++] = y00;
                vertices[vertexOut++] = z0;
                materials[colorOut] = displayMaterial(job, i00, steepness);
                colors[colorOut++] = c00;

                vertices[vertexOut++] = x0;
                vertices[vertexOut++] = y01;
                vertices[vertexOut++] = z1;
                materials[colorOut] = displayMaterial(job, i01, steepness);
                colors[colorOut++] = c01;

                vertices[vertexOut++] = x1;
                vertices[vertexOut++] = y11;
                vertices[vertexOut++] = z1;
                materials[colorOut] = displayMaterial(job, i11, steepness);
                colors[colorOut++] = c11;

                vertices[vertexOut++] = x1;
                vertices[vertexOut++] = y10;
                vertices[vertexOut++] = z0;
                materials[colorOut] = displayMaterial(job, i10, steepness);
                colors[colorOut++] = c10;
            }
        }

        return new MeshData(vertices, colors, materials);
    }

    private static byte displayMaterial(
            GenerationJob job,
            int sampleIndex,
            float steepness
    ) {
        byte material = job.sampleMaterials[sampleIndex];

        // Once a grass slope becomes visually cliff-like, treat it as stone for
        // the renderer's material breakup rather than keeping grass texture noise.
        if (material == MinecraftSurfacePalette.MATERIAL_GRASS && steepness > 0.72F) {
            return MinecraftSurfacePalette.MATERIAL_STONE;
        }

        return material;
    }

    private static int shadeSample(
            GenerationJob job,
            int sampleIndex,
            float shade,
            float steepness
    ) {
        int color = job.sampleColors[sampleIndex];
        byte material = job.sampleMaterials[sampleIndex];

        if (material == MinecraftSurfacePalette.MATERIAL_GRASS && steepness > 0.42F) {
            float stoneBlend = Math.min(0.78F, (steepness - 0.42F) * 1.15F);
            color = MinecraftSurfacePalette.blend(
                    color,
                    MinecraftSurfacePalette.stoneColor(),
                    stoneBlend
            );
        }

        if (material == MinecraftSurfacePalette.MATERIAL_WATER) {
            shade = 0.92F + (shade - 0.72F) * 0.25F;
        }

        return MinecraftSurfacePalette.applyLighting(color, shade);
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

        if (initialFillCompletedNanos == 0L
                && initialFillStartedNanos != 0L
                && !wantedTiles.isEmpty()
                && active.size() == wantedTiles.size()) {
            initialFillCompletedNanos = System.nanoTime();
        }

        double progress = currentJob == null ? 0.0 : currentJob.progressPercent();
        int currentLevel = currentJob == null ? 0 : currentJob.ring.lodLevel();

        long fillEnd = initialFillCompletedNanos != 0L
                ? initialFillCompletedNanos
                : System.nanoTime();
        double initialFillSeconds = initialFillStartedNanos == 0L
                ? 0.0
                : (fillEnd - initialFillStartedNanos) / 1_000_000_000.0;

        snapshot = new WorldgenSurfaceSnapshot(
                active,
                ringStatuses,
                wantedTiles.size(),
                CACHE.size(),
                true,
                currentJob != null || activeSliceId != 0L,
                lastGenerationMs,
                generatedTileCount,
                adaptiveSliceBudgetNanos / 1_000_000.0,
                lastSliceMs,
                lastSliceSamples,
                progress,
                currentLevel,
                initialFillSeconds,
                initialFillCompletedNanos != 0L,
                serverTickMs,
                clientFrameMs,
                diskLoadedTiles,
                diskLoadMs,
                diskSavedTiles,
                diskSaveMs,
                diskFileMiB,
                diskCacheStatus,
                diskLoadFuture != null || diskSaveFuture != null
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
        private final int[] sampleColors;
        private final byte[] sampleMaterials;

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
            this.sampleColors = new int[totalSamples];
            this.sampleMaterials = new byte[totalSamples];
        }

        private double progressPercent() {
            return nextSample * 100.0 / totalSamples;
        }
    }

    private record MeshData(
            int[] vertices,
            int[] colors,
            byte[] materials
    ) {
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
