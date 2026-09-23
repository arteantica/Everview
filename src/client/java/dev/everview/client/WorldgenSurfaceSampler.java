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
import java.util.Arrays;
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
 * M3.8 adds velocity-aware near streaming. L1/L2 keep their normal camera
 * guards, while a second predictive guard is placed ahead of sustained motion.
 * At high speed the scheduler becomes coverage-only and abandons stale detail
 * refinement so coarse safety terrain reaches the travel direction first.
 */
public final class WorldgenSurfaceSampler {
    public static final int MIN_INNER_RADIUS = 256;
    public static final int HANDOFF_OVERLAP_BLOCKS = 32;
    public static final int MAX_OUTER_RADIUS = 16_384;

    public static final long MIN_SLICE_BUDGET_NANOS = 1_000_000L;
    public static final long BASE_SLICE_BUDGET_NANOS = 6_000_000L;
    public static final long MAX_SLICE_BUDGET_NANOS = 12_000_000L;
    public static final int MAX_SAMPLES_PER_SLICE = 128;

    private static final int CACHE_LIMIT = 4_096;
    private static final int NEAR_RING_MAX_LEVEL = 2;
    private static final double PREDICTION_START_BLOCKS_PER_SECOND = 12.0;
    private static final double HIGH_SPEED_BLOCKS_PER_SECOND = 64.0;
    private static final double VELOCITY_SMOOTHING = 0.35;
    private static final double PREDICTION_SECONDS = 1.5;
    private static final int MAX_PREDICTIVE_LEAD_BLOCKS = 768;
    private static final int PREDICTIVE_ANCHOR_QUANTUM = 64;
    private static final int REMAINING_COVERAGE_BURST = 8;
    private static final int L1_PREFETCH_BLOCKS = 64;
    private static final int L2_PREFETCH_BLOCKS = 128;
    private static final int L1_BOOTSTRAP_SPACING = 4;
    private static final int L1_INTERMEDIATE_SPACING = 2;
    private static final int L1_EXACT_SPACING = 1;
    private static final int L1_EXACT_BAND_BLOCKS = 64;
    private static final int L1_INTERMEDIATE_BAND_BLOCKS = 128;
    private static final int VIEW_SECTOR_COUNT = 16;
    private static final double VIEW_SECTOR_DEGREES = 360.0 / VIEW_SECTOR_COUNT;

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
    private static int lastViewSector = Integer.MIN_VALUE;
    private static int lastPredictiveAnchorX = Integer.MIN_VALUE;
    private static int lastPredictiveAnchorZ = Integer.MIN_VALUE;
    private static boolean lastPredictionActive;

    private static double lastPlayerX = Double.NaN;
    private static double lastPlayerZ = Double.NaN;
    private static double velocityXBlocksPerSecond;
    private static double velocityZBlocksPerSecond;
    private static double movementSpeedBlocksPerSecond;
    private static int predictiveLeadBlocks;
    private static boolean highSpeedCoverageMode;
    private static int staleJobsCancelled;

    private static long epoch;
    private static long nextSliceId;
    private static double lastGenerationMs;
    private static int generatedTileCount;
    private static long initialFillStartedNanos;
    private static long initialFillCompletedNanos;
    private static int balancedCoverageStep;

    private static List<WorldgenLodRing> activeRings = List.of();
    private static List<WantedTile> wantedTiles = List.of();
    private static GenerationJob currentJob;

    private WorldgenSurfaceSampler() {
    }

    public static WorldgenSurfaceSnapshot snapshot() {
        return snapshot;
    }

    public static StreamingStatus streamingStatus() {
        int predictiveDesired = 0;
        int predictiveCovered = 0;

        for (WantedTile wanted : wantedTiles) {
            if (!wanted.predictive()) {
                continue;
            }

            predictiveDesired++;
            if (CACHE.containsKey(wanted.key())) {
                predictiveCovered++;
            }
        }

        return new StreamingStatus(
                movementSpeedBlocksPerSecond,
                predictiveLeadBlocks,
                highSpeedCoverageMode,
                predictiveDesired,
                predictiveCovered,
                staleJobsCancelled
        );
    }

    public static L1ViewStatus l1ViewStatus() {
        int desired = 0;
        int covered = 0;
        int intermediate = 0;
        int exact = 0;

        for (WantedTile wanted : wantedTiles) {
            if (wanted.ring().lodLevel() != 1
                    || wanted.prefetch()
                    || !wanted.foreground()) {
                continue;
            }

            desired++;
            WorldgenSurfaceTile tile = CACHE.get(wanted.key());
            if (tile == null) {
                continue;
            }

            covered++;
            if (tile.sampleSpacing() <= L1_INTERMEDIATE_SPACING) {
                intermediate++;
            }
            if (tile.sampleSpacing() <= L1_EXACT_SPACING) {
                exact++;
            }
        }

        return new L1ViewStatus(desired, covered, intermediate, exact);
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

        float viewYaw = client.player.getYRot();
        double yawRadians = Math.toRadians(viewYaw);
        double viewForwardX = -Math.sin(yawRadians);
        double viewForwardZ = Math.cos(yawRadians);

        MotionPrediction motion = updateMotionPrediction(
                client,
                viewForwardX,
                viewForwardZ
        );
        double forwardX = motion.forwardX();
        double forwardZ = motion.forwardZ();

        int viewSector = directionSector(forwardX, forwardZ);
        int predictiveCenterX = centerX
                + (int) Math.round(forwardX * predictiveLeadBlocks);
        int predictiveCenterZ = centerZ
                + (int) Math.round(forwardZ * predictiveLeadBlocks);
        int predictiveAnchorX = Math.floorDiv(
                predictiveCenterX,
                PREDICTIVE_ANCHOR_QUANTUM
        ) * PREDICTIVE_ANCHOR_QUANTUM;
        int predictiveAnchorZ = Math.floorDiv(
                predictiveCenterZ,
                PREDICTIVE_ANCHOR_QUANTUM
        ) * PREDICTIVE_ANCHOR_QUANTUM;
        boolean predictionActive = predictiveLeadBlocks > 0;

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
        // The ultra-near ring now uses 32-block GPU tiles so the vanilla/LOD
        // boundary can follow the camera closely instead of advancing in
        // 128-block slabs.
        int anchorX = Math.floorDiv(centerX, 32) * 32;
        int anchorZ = Math.floorDiv(centerZ, 32) * 32;

        if (anchorX != lastAnchorX
                || anchorZ != lastAnchorZ
                || innerRadius != lastInnerRadius
                || viewSector != lastViewSector
                || predictionActive != lastPredictionActive
                || (predictionActive
                        && (predictiveAnchorX != lastPredictiveAnchorX
                        || predictiveAnchorZ != lastPredictiveAnchorZ))) {
            lastAnchorX = anchorX;
            lastAnchorZ = anchorZ;
            lastInnerRadius = innerRadius;
            lastViewSector = viewSector;
            lastPredictiveAnchorX = predictiveAnchorX;
            lastPredictiveAnchorZ = predictiveAnchorZ;
            lastPredictionActive = predictionActive;
            activeRings = createRings(innerRadius);
            wantedTiles = buildWantedTiles(
                    centerX,
                    centerZ,
                    predictiveAnchorX,
                    predictiveAnchorZ,
                    predictionActive,
                    activeRings,
                    forwardX,
                    forwardZ
            );

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
            staleJobsCancelled++;
        }

        if (currentJob != null
                && activeSliceId == 0L
                && highSpeedCoverageMode
                && currentJob.refinement) {
            currentJob = null;
            staleJobsCancelled++;
        }

        if (currentJob != null && currentJob.failed && activeSliceId == 0L) {
            currentJob = null;
        }

        if (currentJob == null && activeSliceId == 0L) {
            WantedTile next = findNextMissing();
            if (next != null) {
                WorldgenSurfaceTile existing = CACHE.get(next.key());

                int sampleSpacing = nextGenerationSpacing(next, existing);

                currentJob = new GenerationJob(
                        next.key(),
                        next.ring(),
                        sampleSpacing,
                        existing != null
                );
            }
        }

        if (currentJob != null && activeSliceId == 0L) {
            scheduleSlice(server, clientLevel.dimension(), currentJob);
        }

        rebuildSnapshot();
        maybeScheduleDiskSave();
    }

    private static MotionPrediction updateMotionPrediction(
            Minecraft client,
            double viewForwardX,
            double viewForwardZ
    ) {
        double playerX = client.player.getX();
        double playerZ = client.player.getZ();

        if (Double.isFinite(lastPlayerX) && Double.isFinite(lastPlayerZ)) {
            double rawVelocityX = (playerX - lastPlayerX) * 20.0;
            double rawVelocityZ = (playerZ - lastPlayerZ) * 20.0;
            double rawSpeed = Math.hypot(rawVelocityX, rawVelocityZ);

            // Teleports should not ask the streamer to manufacture a giant
            // speculative corridor. Treat a >2048 b/s jump as a fresh anchor.
            if (rawSpeed <= 2_048.0) {
                velocityXBlocksPerSecond +=
                        (rawVelocityX - velocityXBlocksPerSecond)
                                * VELOCITY_SMOOTHING;
                velocityZBlocksPerSecond +=
                        (rawVelocityZ - velocityZBlocksPerSecond)
                                * VELOCITY_SMOOTHING;
            } else {
                velocityXBlocksPerSecond = 0.0;
                velocityZBlocksPerSecond = 0.0;
            }
        }

        lastPlayerX = playerX;
        lastPlayerZ = playerZ;

        movementSpeedBlocksPerSecond = Math.hypot(
                velocityXBlocksPerSecond,
                velocityZBlocksPerSecond
        );

        double forwardX = viewForwardX;
        double forwardZ = viewForwardZ;

        if (movementSpeedBlocksPerSecond
                >= PREDICTION_START_BLOCKS_PER_SECOND) {
            forwardX = velocityXBlocksPerSecond
                    / movementSpeedBlocksPerSecond;
            forwardZ = velocityZBlocksPerSecond
                    / movementSpeedBlocksPerSecond;

            int rawLead = (int) Math.round(
                    movementSpeedBlocksPerSecond * PREDICTION_SECONDS
            );
            predictiveLeadBlocks = Math.min(
                    MAX_PREDICTIVE_LEAD_BLOCKS,
                    Math.max(PREDICTIVE_ANCHOR_QUANTUM, rawLead)
            );
            predictiveLeadBlocks =
                    Math.max(
                            PREDICTIVE_ANCHOR_QUANTUM,
                            (predictiveLeadBlocks
                                    / PREDICTIVE_ANCHOR_QUANTUM)
                                    * PREDICTIVE_ANCHOR_QUANTUM
                    );
        } else {
            predictiveLeadBlocks = 0;
        }

        highSpeedCoverageMode = movementSpeedBlocksPerSecond
                >= HIGH_SPEED_BLOCKS_PER_SECOND;

        return new MotionPrediction(forwardX, forwardZ);
    }

    private static int directionSector(
            double forwardX,
            double forwardZ
    ) {
        double yawDegrees = Math.toDegrees(
                Math.atan2(-forwardX, forwardZ)
        );

        return Math.floorMod(
                (int) Math.floor(
                        (yawDegrees + VIEW_SECTOR_DEGREES * 0.5)
                                / VIEW_SECTOR_DEGREES
                ),
                VIEW_SECTOR_COUNT
        );
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

        // M3.5 uses the large amount of singleplayer tick headroom during
        // initial streaming, then backs off automatically as either the server
        // tick or client frame becomes busy.
        if (serverTickMs < 15.0 && (clientFrameMs <= 0.0 || clientFrameMs < 10.0)) {
            target = MAX_SLICE_BUDGET_NANOS;
        } else if (serverTickMs < 22.0 && (clientFrameMs <= 0.0 || clientFrameMs < 13.0)) {
            target = 10_000_000L;
        } else if (serverTickMs < 30.0 && (clientFrameMs <= 0.0 || clientFrameMs < 18.0)) {
            target = 8_000_000L;
        } else if (serverTickMs < 38.0 && (clientFrameMs <= 0.0 || clientFrameMs < 24.0)) {
            target = BASE_SLICE_BUDGET_NANOS;
        } else if (serverTickMs < 45.0) {
            target = 3_000_000L;
        } else {
            target = MIN_SLICE_BUDGET_NANOS;
        }

        long step = 500_000L;

        if (adaptiveSliceBudgetNanos < target) {
            adaptiveSliceBudgetNanos =
                    Math.min(target, adaptiveSliceBudgetNanos + step);
        } else if (adaptiveSliceBudgetNanos > target) {
            adaptiveSliceBudgetNanos =
                    Math.max(target, adaptiveSliceBudgetNanos - step);
        }
    }

    private static List<WorldgenLodRing> createRings(int innerRadius) {
        List<WorldgenLodRing> rings = new ArrayList<>(6);

        // M3.6 pushes exact ultra-near sampling from 2 blocks to 1 block.
        // Progressive streaming still bootstraps these 32-block tiles at
        // 2-block spacing first, so coverage stays fast while exact detail
        // catches up behind the L2 safety layer.
        int ultraNearOuter = Math.min(
                1_024,
                Math.max(544, innerRadius + 192)
        );

        rings.add(new WorldgenLodRing(
                1,
                innerRadius,
                ultraNearOuter,
                32,
                1
        ));

        // L2 deliberately overlaps the entire L1 annulus. It remains the
        // persistent 8-block fallback surface underneath 1-block exact L1 so
        // roaming never depends on high-detail refinement finishing first.
        rings.add(new WorldgenLodRing(
                2,
                innerRadius,
                1_024,
                128,
                8
        ));

        rings.add(new WorldgenLodRing(3, 1_024, 2_048, 256, 32));
        rings.add(new WorldgenLodRing(4, 2_048, 4_096, 512, 64));
        rings.add(new WorldgenLodRing(5, 4_096, 8_192, 1_024, 128));
        rings.add(new WorldgenLodRing(6, 8_192, 16_384, 2_048, 256));

        return List.copyOf(rings);
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
        lastViewSector = Integer.MIN_VALUE;
        lastPredictiveAnchorX = Integer.MIN_VALUE;
        lastPredictiveAnchorZ = Integer.MIN_VALUE;
        lastPredictionActive = false;
        lastPlayerX = Double.NaN;
        lastPlayerZ = Double.NaN;
        velocityXBlocksPerSecond = 0.0;
        velocityZBlocksPerSecond = 0.0;
        movementSpeedBlocksPerSecond = 0.0;
        predictiveLeadBlocks = 0;
        highSpeedCoverageMode = false;
        staleJobsCancelled = 0;
        lastGenerationMs = 0.0;
        lastSliceMs = 0.0;
        lastSliceSamples = 0;
        generatedTileCount = 0;
        initialFillStartedNanos = 0L;
        initialFillCompletedNanos = 0L;
        balancedCoverageStep = 0;
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
            int predictiveCenterX,
            int predictiveCenterZ,
            boolean predictionActive,
            List<WorldgenLodRing> rings,
            double forwardX,
            double forwardZ
    ) {
        List<List<WantedTile>> perRing = new ArrayList<>();

        for (WorldgenLodRing ring : rings) {
            List<WantedTile> entries = buildRingWantedTiles(
                    centerX,
                    centerZ,
                    predictiveCenterX,
                    predictiveCenterZ,
                    predictionActive,
                    ring,
                    forwardX,
                    forwardZ
            );
            entries.sort(
                    Comparator.comparingInt(
                                    (WantedTile entry) -> entry.foreground() ? 0 : 1
                            )
                            .thenComparing(WantedTile::prefetch)
                            .thenComparingInt(entry -> entry.predictive() ? 0 : 1)
                            .thenComparingLong(entry ->
                                    tileCenterDistanceSq(
                                            entry.key(),
                                            entry.ring(),
                                            centerX,
                                            centerZ
                                    ))
            );
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
            int predictiveCenterX,
            int predictiveCenterZ,
            boolean predictionActive,
            WorldgenLodRing ring,
            double forwardX,
            double forwardZ
    ) {
        int tileSize = ring.tileSize();
        int prefetchBlocks = switch (ring.lodLevel()) {
            case 1 -> L1_PREFETCH_BLOCKS;
            case 2 -> L2_PREFETCH_BLOCKS;
            default -> 0;
        };

        int streamInnerRadius = Math.max(
                0,
                ring.innerRadiusBlocks() - prefetchBlocks
        );
        int streamOuterRadius = ring.outerRadiusBlocks() + prefetchBlocks;

        boolean predictiveRing = predictionActive
                && ring.lodLevel() <= NEAR_RING_MAX_LEVEL;

        int minX = centerX - streamOuterRadius;
        int maxX = centerX + streamOuterRadius;
        int minZ = centerZ - streamOuterRadius;
        int maxZ = centerZ + streamOuterRadius;

        if (predictiveRing) {
            minX = Math.min(minX, predictiveCenterX - streamOuterRadius);
            maxX = Math.max(maxX, predictiveCenterX + streamOuterRadius);
            minZ = Math.min(minZ, predictiveCenterZ - streamOuterRadius);
            maxZ = Math.max(maxZ, predictiveCenterZ + streamOuterRadius);
        }

        int minTileX = Math.floorDiv(minX, tileSize);
        int maxTileX = Math.floorDiv(maxX, tileSize);
        int minTileZ = Math.floorDiv(minZ, tileSize);
        int maxTileZ = Math.floorDiv(maxZ, tileSize);

        List<WantedTile> entries = new ArrayList<>();

        for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
            for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                boolean visibleNow = tileIntersectsAnnulus(
                        tileX,
                        tileZ,
                        centerX,
                        centerZ,
                        ring.innerRadiusBlocks(),
                        ring.outerRadiusBlocks(),
                        tileSize
                );

                boolean normalGuard = visibleNow || tileIntersectsAnnulus(
                        tileX,
                        tileZ,
                        centerX,
                        centerZ,
                        streamInnerRadius,
                        streamOuterRadius,
                        tileSize
                );

                boolean predictive = false;

                if (predictiveRing && !visibleNow) {
                    predictive = tileIntersectsAnnulus(
                            tileX,
                            tileZ,
                            predictiveCenterX,
                            predictiveCenterZ,
                            streamInnerRadius,
                            streamOuterRadius,
                            tileSize
                    );

                    // L2 is the full emergency safety carpet. L1 prediction is
                    // limited to the forward half of the future annulus so
                    // exact-sized tiles do not explode speculative work.
                    if (predictive && ring.lodLevel() == 1) {
                        double tileCenterX =
                                tileX * (double) tileSize + tileSize * 0.5;
                        double tileCenterZ =
                                tileZ * (double) tileSize + tileSize * 0.5;
                        double futureDx = tileCenterX - predictiveCenterX;
                        double futureDz = tileCenterZ - predictiveCenterZ;
                        predictive = futureDx * forwardX
                                + futureDz * forwardZ >= -tileSize;
                    }
                }

                boolean inStreamGuard = normalGuard || predictive;

                if (inStreamGuard) {
                    LodTileKey key = new LodTileKey(ring.lodLevel(), tileX, tileZ);
                    int targetSpacing = ring.sampleSpacing();
                    boolean foreground = true;

                    if (ring.lodLevel() == 1) {
                        double tileCenterX = tileX * (double) tileSize + tileSize * 0.5;
                        double tileCenterZ = tileZ * (double) tileSize + tileSize * 0.5;
                        double dx = tileCenterX - centerX;
                        double dz = tileCenterZ - centerZ;

                        // Front hemisphere is urgent. Rear L1 stays optional
                        // because the persistent L2 underlay already covers it.
                        foreground = dx * forwardX + dz * forwardZ >= 0.0;
                        if (!visibleNow) {
                            targetSpacing = L1_BOOTSTRAP_SPACING;
                        } else {
                            int exactOuter = Math.min(
                                    ring.outerRadiusBlocks(),
                                    ring.innerRadiusBlocks() + L1_EXACT_BAND_BLOCKS
                            );
                            int intermediateOuter = Math.min(
                                    ring.outerRadiusBlocks(),
                                    ring.innerRadiusBlocks() + L1_INTERMEDIATE_BAND_BLOCKS
                            );

                            // Use tile/annulus intersection, not tile-center
                            // distance. If any part of a visible 32x32 tile
                            // touches the exact belt, refine the whole tile to
                            // 1b. This deliberately adds up to one tile of
                            // overlap so a coarser island cannot sit between
                            // vanilla and already-exact terrain.
                            if (tileIntersectsAnnulus(
                                    tileX,
                                    tileZ,
                                    centerX,
                                    centerZ,
                                    ring.innerRadiusBlocks(),
                                    exactOuter,
                                    tileSize
                            )) {
                                targetSpacing = L1_EXACT_SPACING;
                            } else if (tileIntersectsAnnulus(
                                    tileX,
                                    tileZ,
                                    centerX,
                                    centerZ,
                                    exactOuter,
                                    intermediateOuter,
                                    tileSize
                            )) {
                                targetSpacing = L1_INTERMEDIATE_SPACING;
                            } else {
                                targetSpacing = L1_BOOTSTRAP_SPACING;
                            }
                        }
                    }

                    entries.add(new WantedTile(
                            key,
                            ring,
                            !visibleNow,
                            targetSpacing,
                            foreground || predictive,
                            predictive
                    ));
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

    private static int nextGenerationSpacing(
            WantedTile wanted,
            WorldgenSurfaceTile existing
    ) {
        WorldgenLodRing ring = wanted.ring();

        if (ring.lodLevel() == 1) {
            int target = wanted.targetSpacing();

            if (existing == null) {
                return L1_BOOTSTRAP_SPACING;
            }
            if (existing.sampleSpacing() <= target) {
                return existing.sampleSpacing();
            }
            if (existing.sampleSpacing() > L1_INTERMEDIATE_SPACING
                    && target <= L1_INTERMEDIATE_SPACING) {
                return L1_INTERMEDIATE_SPACING;
            }
            return target;
        }

        return existing == null
                ? Math.min(ring.tileSize(), ring.sampleSpacing() * 2)
                : ring.sampleSpacing();
    }

    private static WantedTile findNextMissing() {
        LodTileKey pending = currentJob == null ? null : currentJob.key;

        // Priority zero stays the coarse L2 safety net.
        WantedTile fallback = firstMissingFallbackCoverage(pending);
        if (fallback != null) {
            return fallback;
        }

        // Camera-facing L1 coarse coverage is still the first high-detail goal.
        WantedTile visibleCoverage = firstMissingForegroundVisibleCoverage(pending);
        if (visibleCoverage != null) {
            return visibleCoverage;
        }

        // At speed, build the future L2 safety carpet before spending time on
        // speculative detail. Then bootstrap future L1 in the travel direction.
        WantedTile predictiveFallback =
                firstMissingPredictiveFallbackCoverage(pending);
        if (predictiveFallback != null) {
            return predictiveFallback;
        }

        WantedTile predictiveNear =
                firstMissingPredictiveNearCoverage(pending);
        if (predictiveNear != null) {
            return predictiveNear;
        }

        // Keep the foreground roaming guard warm before quality refinement.
        WantedTile nearGuard = firstMissingForegroundNearGuardCoverage(pending);
        if (nearGuard != null) {
            return nearGuard;
        }

        WantedTile remainingCoverage = firstMissingCoverage(pending);

        // Extreme movement is coverage-only. Refining 4b -> 2b -> 1b while the
        // player is outrunning the field just spends budget behind the camera.
        if (highSpeedCoverageMode) {
            balancedCoverageStep = 0;
            return remainingCoverage;
        }

        // M3.7.2: concentrate quality where the vanilla handoff is visible.
        // firstExactBandRefinement() returns the nearest exact-band tile that
        // has not reached 1b yet. Because nextGenerationSpacing() advances it
        // 4b->2b->1b, the same tile is completed before the scheduler moves on.
        WantedTile frontQuality = firstExactBandRefinement(pending);
        if (frontQuality == null) {
            frontQuality = firstIntermediateNearRefinement(pending);
        }
        boolean hasFrontQuality = frontQuality != null;

        // Keep the proven M3.6.6 8:1 coverage/refinement balance unchanged
        // while total coverage is still incomplete.
        if (remainingCoverage != null && hasFrontQuality) {
            if (balancedCoverageStep < REMAINING_COVERAGE_BURST) {
                balancedCoverageStep++;
                return remainingCoverage;
            }

            balancedCoverageStep = 0;
            return frontQuality;
        }

        if (remainingCoverage != null) {
            balancedCoverageStep = 0;
            return remainingCoverage;
        }

        if (hasFrontQuality) {
            balancedCoverageStep = 0;
            return frontQuality;
        }

        // Exact L2 and other low-value refinement only happen after coverage
        // and camera-facing L1 quality no longer need generation time.
        return firstRefinement(pending, false);
    }

    private static WantedTile firstMissingFallbackCoverage(LodTileKey pending) {
        for (WantedTile wanted : wantedTiles) {
            if (wanted.key().equals(pending)
                    || wanted.ring().lodLevel() != 2
                    || wanted.prefetch()) {
                continue;
            }

            if (!CACHE.containsKey(wanted.key())) {
                return wanted;
            }
        }

        return null;
    }

    private static WantedTile firstMissingForegroundVisibleCoverage(
            LodTileKey pending
    ) {
        for (WantedTile wanted : wantedTiles) {
            if (wanted.key().equals(pending) || wanted.prefetch()) {
                continue;
            }
            if (wanted.ring().lodLevel() == 1 && !wanted.foreground()) {
                continue;
            }

            if (!CACHE.containsKey(wanted.key())) {
                return wanted;
            }
        }

        return null;
    }

    private static WantedTile firstMissingPredictiveFallbackCoverage(
            LodTileKey pending
    ) {
        for (WantedTile wanted : wantedTiles) {
            if (wanted.key().equals(pending)
                    || !wanted.predictive()
                    || wanted.ring().lodLevel() != 2) {
                continue;
            }

            if (!CACHE.containsKey(wanted.key())) {
                return wanted;
            }
        }

        return null;
    }

    private static WantedTile firstMissingPredictiveNearCoverage(
            LodTileKey pending
    ) {
        for (WantedTile wanted : wantedTiles) {
            if (wanted.key().equals(pending)
                    || !wanted.predictive()
                    || wanted.ring().lodLevel() > NEAR_RING_MAX_LEVEL) {
                continue;
            }

            if (!CACHE.containsKey(wanted.key())) {
                return wanted;
            }
        }

        return null;
    }

    private static WantedTile firstMissingForegroundNearGuardCoverage(
            LodTileKey pending
    ) {
        for (WantedTile wanted : wantedTiles) {
            if (wanted.key().equals(pending)
                    || !wanted.prefetch()
                    || wanted.predictive()
                    || wanted.ring().lodLevel() > NEAR_RING_MAX_LEVEL) {
                continue;
            }
            if (wanted.ring().lodLevel() == 1 && !wanted.foreground()) {
                continue;
            }

            if (!CACHE.containsKey(wanted.key())) {
                return wanted;
            }
        }

        return null;
    }

    private static WantedTile firstMissingCoverage(LodTileKey pending) {
        // Never leave a currently visible hole waiting behind refinement or
        // speculative work.
        for (WantedTile wanted : wantedTiles) {
            if (wanted.key().equals(pending) || wanted.prefetch()) {
                continue;
            }
            if (!CACHE.containsKey(wanted.key())) {
                return wanted;
            }
        }

        // Once the visible annuli are covered, spend spare streaming slots on
        // the L1/L2 guard band so the next camera-anchor shift is already warm.
        for (WantedTile wanted : wantedTiles) {
            if (wanted.key().equals(pending) || !wanted.prefetch()) {
                continue;
            }
            if (!CACHE.containsKey(wanted.key())) {
                return wanted;
            }
        }

        return null;
    }

    private static WantedTile firstExactBandRefinement(
            LodTileKey pending
    ) {
        for (WantedTile wanted : wantedTiles) {
            if (wanted.key().equals(pending)
                    || wanted.prefetch()
                    || wanted.ring().lodLevel() != 1
                    || !wanted.foreground()
                    || wanted.targetSpacing() != L1_EXACT_SPACING) {
                continue;
            }

            WorldgenSurfaceTile tile = CACHE.get(wanted.key());
            if (tile != null && tile.sampleSpacing() > L1_EXACT_SPACING) {
                return wanted;
            }
        }

        return null;
    }

    private static WantedTile firstIntermediateNearRefinement(
            LodTileKey pending
    ) {
        for (WantedTile wanted : wantedTiles) {
            if (wanted.key().equals(pending)
                    || wanted.prefetch()
                    || wanted.ring().lodLevel() != 1
                    || !wanted.foreground()) {
                continue;
            }

            WorldgenSurfaceTile tile = CACHE.get(wanted.key());
            if (tile != null
                    && wanted.targetSpacing() <= L1_INTERMEDIATE_SPACING
                    && tile.sampleSpacing() > L1_INTERMEDIATE_SPACING) {
                return wanted;
            }
        }

        return null;
    }

    private static WantedTile firstRefinement(
            LodTileKey pending,
            boolean nearOnly
    ) {
        for (WantedTile wanted : wantedTiles) {
            if (wanted.key().equals(pending)) {
                continue;
            }
            if (nearOnly && wanted.ring().lodLevel() > NEAR_RING_MAX_LEVEL) {
                continue;
            }

            WorldgenSurfaceTile tile = CACHE.get(wanted.key());
            int desiredSpacing = wanted.ring().lodLevel() == 1
                    ? wanted.targetSpacing()
                    : wanted.ring().sampleSpacing();

            if (tile != null
                    && tile.sampleSpacing() > desiredSpacing) {
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
            int worldX = job.originX + gx * job.sampleSpacing;
            int worldZ = job.originZ + gz * job.sampleSpacing;

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
            MeshData mesh = buildMesh(job, level.getSeaLevel());
            long meshElapsed = System.nanoTime() - meshStart;

            job.accumulatedNanos += meshElapsed;
            sliceElapsed += meshElapsed;

            WorldgenSurfaceTile tile = new WorldgenSurfaceTile(
                    job.ring.lodLevel(),
                    job.key.tileX(),
                    job.key.tileZ(),
                    job.ring.tileSize(),
                    job.sampleSpacing,
                    mesh.vertices(),
                    mesh.colors(),
                    mesh.materials(),
                    mesh.quadCount(),
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

    private static MeshData buildMesh(GenerationJob job, int seaLevel) {
        if (job.ring.lodLevel() == 1
                && job.sampleSpacing == L1_EXACT_SPACING) {
            return buildBlockColumnMesh(job, seaLevel);
        }

        if (job.sampleSpacing <= 8) {
            return buildTerracedMesh(job, seaLevel);
        }

        return buildSmoothMesh(job);
    }

    /**
     * Exact L1 is represented as Minecraft-like surface columns instead of
     * four-corner averaged plateaus. Each cell takes the height/material/color
     * sampled at its own block coordinate. East and south tile edges use the
     * extra sample row/column already present in the generation job, so adjacent
     * exact tiles meet deterministically without deep crack-hiding skirts.
     */
    private static MeshData buildBlockColumnMesh(GenerationJob job, int seaLevel) {
        int cells = job.cellsAcross;
        int[] topY = new int[job.cellCount];
        int[] topColor = new int[job.cellCount];
        byte[] topMaterial = new byte[job.cellCount];

        for (int gz = 0; gz < cells; gz++) {
            for (int gx = 0; gx < cells; gx++) {
                int cell = gz * cells + gx;
                int sample = gz * job.samplesAcross + gx;
                int x = job.originX + gx;
                int z = job.originZ + gz;

                byte material = job.sampleMaterials[sample];
                int y = exactColumnHeight(job, sample, material, seaLevel);
                int color = MaterialTerrainShading.apply(
                        MinecraftSurfacePalette.applyLighting(
                                job.sampleColors[sample],
                                material == MinecraftSurfacePalette.MATERIAL_WATER
                                        ? 0.97F
                                        : 1.00F
                        ),
                        material,
                        x,
                        y,
                        z,
                        L1_EXACT_SPACING
                );

                topY[cell] = y;
                topColor[cell] = color;
                topMaterial[cell] = material;
            }
        }

        MeshBuilder mesh = new MeshBuilder(job.cellCount * 4);

        // One top quad per sampled Minecraft column.
        for (int gz = 0; gz < cells; gz++) {
            int z0 = job.originZ + gz;
            int z1 = z0 + 1;

            for (int gx = 0; gx < cells; gx++) {
                int x0 = job.originX + gx;
                int x1 = x0 + 1;
                int cell = gz * cells + gx;

                mesh.addQuad(
                        x0, topY[cell], z0,
                        x0, topY[cell], z1,
                        x1, topY[cell], z1,
                        x1, topY[cell], z0,
                        topColor[cell],
                        topMaterial[cell]
                );
            }
        }

        // Shared east/west faces inside the tile.
        for (int gz = 0; gz < cells; gz++) {
            int z0 = job.originZ + gz;
            int z1 = z0 + 1;

            for (int gx = 0; gx < cells - 1; gx++) {
                int left = gz * cells + gx;
                int right = left + 1;
                addBlockBoundaryWall(
                        mesh,
                        job.originX + gx + 1, z0,
                        job.originX + gx + 1, z1,
                        topY[left], topMaterial[left], topColor[left],
                        topY[right], topMaterial[right], topColor[right],
                        0.82F
                );
            }

            // The extra x=max sample is the first column sample of the tile
            // immediately to the east. This makes exact-tile seams deterministic.
            int inside = gz * cells + cells - 1;
            int outsideSample = gz * job.samplesAcross + cells;
            byte outsideMaterial = job.sampleMaterials[outsideSample];
            int outsideY = exactColumnHeight(
                    job,
                    outsideSample,
                    outsideMaterial,
                    seaLevel
            );
            int outsideColor = exactSampleColor(
                    job,
                    outsideSample,
                    outsideMaterial,
                    job.originX + cells,
                    outsideY,
                    z0
            );
            int eastX = job.originX + cells;

            addBlockBoundaryWall(
                    mesh,
                    eastX, z0,
                    eastX, z1,
                    topY[inside], topMaterial[inside], topColor[inside],
                    outsideY, outsideMaterial, outsideColor,
                    0.82F
            );
        }

        // Shared north/south faces inside the tile.
        for (int gx = 0; gx < cells; gx++) {
            int x0 = job.originX + gx;
            int x1 = x0 + 1;

            for (int gz = 0; gz < cells - 1; gz++) {
                int north = gz * cells + gx;
                int south = north + cells;
                addBlockBoundaryWall(
                        mesh,
                        x0, job.originZ + gz + 1,
                        x1, job.originZ + gz + 1,
                        topY[north], topMaterial[north], topColor[north],
                        topY[south], topMaterial[south], topColor[south],
                        0.72F
                );
            }

            // Same ownership rule for the south tile edge.
            int inside = (cells - 1) * cells + gx;
            int outsideSample = cells * job.samplesAcross + gx;
            byte outsideMaterial = job.sampleMaterials[outsideSample];
            int outsideY = exactColumnHeight(
                    job,
                    outsideSample,
                    outsideMaterial,
                    seaLevel
            );
            int outsideColor = exactSampleColor(
                    job,
                    outsideSample,
                    outsideMaterial,
                    x0,
                    outsideY,
                    job.originZ + cells
            );
            int southZ = job.originZ + cells;

            addBlockBoundaryWall(
                    mesh,
                    x0, southZ,
                    x1, southZ,
                    topY[inside], topMaterial[inside], topColor[inside],
                    outsideY, outsideMaterial, outsideColor,
                    0.72F
            );
        }

        return mesh.finish();
    }

    private static int exactColumnHeight(
            GenerationJob job,
            int sample,
            byte material,
            int seaLevel
    ) {
        return material == MinecraftSurfacePalette.MATERIAL_WATER
                ? seaLevel
                : job.heights[sample];
    }

    private static int exactSampleColor(
            GenerationJob job,
            int sample,
            byte material,
            int x,
            int y,
            int z
    ) {
        return MaterialTerrainShading.apply(
                MinecraftSurfacePalette.applyLighting(
                        job.sampleColors[sample],
                        material == MinecraftSurfacePalette.MATERIAL_WATER
                                ? 0.97F
                                : 1.00F
                ),
                material,
                x,
                y,
                z,
                L1_EXACT_SPACING
        );
    }

    private static void addBlockBoundaryWall(
            MeshBuilder mesh,
            int x0,
            int z0,
            int x1,
            int z1,
            int aY,
            byte aMaterial,
            int aColor,
            int bY,
            byte bMaterial,
            int bColor,
            float directionalShade
    ) {
        if (aY == bY) {
            return;
        }

        boolean aHigh = aY > bY;
        int highY = Math.max(aY, bY);
        int lowY = Math.min(aY, bY);
        byte highMaterial = aHigh ? aMaterial : bMaterial;
        int highColor = aHigh ? aColor : bColor;

        addLayeredColumnWall(
                mesh,
                x0,
                z0,
                x1,
                z1,
                lowY,
                highY,
                highMaterial,
                highColor,
                directionalShade
        );
    }

    private static void addLayeredColumnWall(
            MeshBuilder mesh,
            int x0,
            int z0,
            int x1,
            int z1,
            int lowY,
            int highY,
            byte topMaterial,
            int topColor,
            float directionalShade
    ) {
        int height = highY - lowY;
        if (height <= 0) {
            return;
        }

        int worldX = (x0 + x1) / 2;
        int worldZ = (z0 + z1) / 2;

        if (topMaterial == MinecraftSurfacePalette.MATERIAL_GRASS) {
            int soilBottom = Math.max(lowY, highY - Math.min(3, height));

            if (soilBottom > lowY) {
                int stone = MaterialTerrainShading.apply(
                        MinecraftSurfacePalette.applyLighting(
                                MinecraftSurfacePalette.stoneColor(),
                                directionalShade
                        ),
                        MinecraftSurfacePalette.MATERIAL_STONE,
                        worldX,
                        lowY,
                        worldZ,
                        L1_EXACT_SPACING
                );
                mesh.addQuad(
                        x0, lowY, z0,
                        x1, lowY, z1,
                        x1, soilBottom, z1,
                        x0, soilBottom, z0,
                        stone,
                        MinecraftSurfacePalette.MATERIAL_STONE
                );
            }

            int dirt = MaterialTerrainShading.apply(
                    MinecraftSurfacePalette.applyLighting(
                            MinecraftSurfacePalette.dirtColor(),
                            directionalShade
                    ),
                    MinecraftSurfacePalette.MATERIAL_DIRT,
                    worldX,
                    soilBottom,
                    worldZ,
                    L1_EXACT_SPACING
            );
            mesh.addQuad(
                    x0, soilBottom, z0,
                    x1, soilBottom, z1,
                    x1, highY, z1,
                    x0, highY, z0,
                    dirt,
                    MinecraftSurfacePalette.MATERIAL_DIRT
            );
            return;
        }

        if (topMaterial == MinecraftSurfacePalette.MATERIAL_SNOW) {
            int snowBottom = Math.max(lowY, highY - 1);

            if (snowBottom > lowY) {
                int stone = MaterialTerrainShading.apply(
                        MinecraftSurfacePalette.applyLighting(
                                MinecraftSurfacePalette.stoneColor(),
                                directionalShade
                        ),
                        MinecraftSurfacePalette.MATERIAL_STONE,
                        worldX,
                        lowY,
                        worldZ,
                        L1_EXACT_SPACING
                );
                mesh.addQuad(
                        x0, lowY, z0,
                        x1, lowY, z1,
                        x1, snowBottom, z1,
                        x0, snowBottom, z0,
                        stone,
                        MinecraftSurfacePalette.MATERIAL_STONE
                );
            }

            int snow = MinecraftSurfacePalette.applyLighting(
                    topColor,
                    directionalShade
            );
            mesh.addQuad(
                    x0, snowBottom, z0,
                    x1, snowBottom, z1,
                    x1, highY, z1,
                    x0, highY, z0,
                    snow,
                    MinecraftSurfacePalette.MATERIAL_SNOW
            );
            return;
        }

        byte wallMaterial = topMaterial == MinecraftSurfacePalette.MATERIAL_WATER
                ? MinecraftSurfacePalette.MATERIAL_WATER
                : topMaterial;

        int wallColor = topMaterial == MinecraftSurfacePalette.MATERIAL_STONE
                ? MinecraftSurfacePalette.stoneColor()
                : topColor;
        wallColor = MinecraftSurfacePalette.applyLighting(
                wallColor,
                directionalShade
        );

        mesh.addQuad(
                x0, lowY, z0,
                x1, lowY, z1,
                x1, highY, z1,
                x0, highY, z0,
                wallColor,
                wallMaterial
        );
    }

    private static MeshData buildSmoothMesh(GenerationJob job) {
        int[] vertices = new int[job.cellCount * 12];
        int[] colors = new int[job.cellCount * 4];
        byte[] materials = new byte[job.cellCount * 4];
        int vertexOut = 0;
        int colorOut = 0;
        int spacing = job.sampleSpacing;

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

                byte m00 = displayMaterial(job, i00, steepness);
                byte m01 = displayMaterial(job, i01, steepness);
                byte m11 = displayMaterial(job, i11, steepness);
                byte m10 = displayMaterial(job, i10, steepness);

                int c00 = MaterialTerrainShading.apply(
                        shadeSample(job, i00, shade, steepness),
                        m00, x0, y00, z0, job.sampleSpacing
                );
                int c01 = MaterialTerrainShading.apply(
                        shadeSample(job, i01, shade, steepness),
                        m01, x0, y01, z1, job.sampleSpacing
                );
                int c11 = MaterialTerrainShading.apply(
                        shadeSample(job, i11, shade, steepness),
                        m11, x1, y11, z1, job.sampleSpacing
                );
                int c10 = MaterialTerrainShading.apply(
                        shadeSample(job, i10, shade, steepness),
                        m10, x1, y10, z0, job.sampleSpacing
                );

                vertices[vertexOut++] = x0;
                vertices[vertexOut++] = y00;
                vertices[vertexOut++] = z0;
                materials[colorOut] = m00;
                colors[colorOut++] = c00;

                vertices[vertexOut++] = x0;
                vertices[vertexOut++] = y01;
                vertices[vertexOut++] = z1;
                materials[colorOut] = m01;
                colors[colorOut++] = c01;

                vertices[vertexOut++] = x1;
                vertices[vertexOut++] = y11;
                vertices[vertexOut++] = z1;
                materials[colorOut] = m11;
                colors[colorOut++] = c11;

                vertices[vertexOut++] = x1;
                vertices[vertexOut++] = y10;
                vertices[vertexOut++] = z0;
                materials[colorOut] = m10;
                colors[colorOut++] = c10;
            }
        }

        return new MeshData(vertices, colors, materials, job.cellCount);
    }

    /**
     * Bootstrap/intermediate near rings retain the coarse terraced surface.
     * Exact 1-block L1 is handled separately by buildBlockColumnMesh(), while
     * 2/4/8-block tiles continue using representative plateaus and skirts.
     */
    private static MeshData buildTerracedMesh(GenerationJob job, int seaLevel) {
        int cells = job.cellsAcross;
        int spacing = job.sampleSpacing;
        int[] topY = new int[job.cellCount];
        int[] topColor = new int[job.cellCount];
        byte[] topMaterial = new byte[job.cellCount];

        for (int gz = 0; gz < cells; gz++) {
            for (int gx = 0; gx < cells; gx++) {
                int cell = gz * cells + gx;
                int i00 = gz * job.samplesAcross + gx;
                int i10 = i00 + 1;
                int i01 = (gz + 1) * job.samplesAcross + gx;
                int i11 = i01 + 1;

                byte material = dominantMaterial(job, i00, i10, i01, i11);
                topMaterial[cell] = material;

                int y = representativeHeight(
                        job,
                        material,
                        seaLevel,
                        i00,
                        i10,
                        i01,
                        i11
                );
                topY[cell] = y;

                int baseColor = representativeColor(
                        job,
                        material,
                        i00,
                        i10,
                        i01,
                        i11
                );

                int y00 = job.heights[i00];
                int y10 = job.heights[i10];
                int y01 = job.heights[i01];
                int y11 = job.heights[i11];

                float dx = ((y10 + y11) - (y00 + y01)) * 0.5F / spacing;
                float dz = ((y01 + y11) - (y00 + y10)) * 0.5F / spacing;
                float invLength = 1.0F / (float) Math.sqrt(dx * dx + 1.0F + dz * dz);
                float lightDot =
                        (-dx * invLength) * -0.45F
                                + invLength * 0.86F
                                + (-dz * invLength) * -0.24F;
                float shade = 0.76F + Math.max(0.0F, lightDot) * 0.26F;

                int x = job.originX + gx * spacing + spacing / 2;
                int z = job.originZ + gz * spacing + spacing / 2;
                int lit = MinecraftSurfacePalette.applyLighting(baseColor, shade);
                topColor[cell] = MaterialTerrainShading.apply(
                        lit,
                        material,
                        x,
                        y,
                        z,
                        job.sampleSpacing
                );
            }
        }

        MeshBuilder mesh = new MeshBuilder(job.cellCount * 3);

        // Flat top faces.
        for (int gz = 0; gz < cells; gz++) {
            int z0 = job.originZ + gz * spacing;
            int z1 = z0 + spacing;

            for (int gx = 0; gx < cells; gx++) {
                int x0 = job.originX + gx * spacing;
                int x1 = x0 + spacing;
                int cell = gz * cells + gx;
                int y = topY[cell];

                mesh.addQuad(
                        x0, y, z0,
                        x0, y, z1,
                        x1, y, z1,
                        x1, y, z0,
                        topColor[cell],
                        topMaterial[cell]
                );
            }
        }

        // Internal east/west boundaries. One face per shared edge.
        for (int gz = 0; gz < cells; gz++) {
            int z0 = job.originZ + gz * spacing;
            int z1 = z0 + spacing;

            for (int gx = 0; gx < cells - 1; gx++) {
                int left = gz * cells + gx;
                int right = left + 1;
                int leftY = topY[left];
                int rightY = topY[right];

                if (leftY == rightY) {
                    continue;
                }

                int high = leftY > rightY ? left : right;
                int highY = Math.max(leftY, rightY);
                int lowY = Math.min(leftY, rightY);
                int x = job.originX + (gx + 1) * spacing;

                int color = sideColor(
                        topColor[high],
                        topMaterial[high],
                        highY - lowY,
                        0.82F
                );

                mesh.addQuad(
                        x, lowY, z0,
                        x, lowY, z1,
                        x, highY, z1,
                        x, highY, z0,
                        color,
                        wallMaterial(topMaterial[high])
                );
            }
        }

        // Internal north/south boundaries.
        for (int gz = 0; gz < cells - 1; gz++) {
            int z = job.originZ + (gz + 1) * spacing;

            for (int gx = 0; gx < cells; gx++) {
                int north = gz * cells + gx;
                int south = north + cells;
                int northY = topY[north];
                int southY = topY[south];

                if (northY == southY) {
                    continue;
                }

                int high = northY > southY ? north : south;
                int highY = Math.max(northY, southY);
                int lowY = Math.min(northY, southY);
                int x0 = job.originX + gx * spacing;
                int x1 = x0 + spacing;

                int color = sideColor(
                        topColor[high],
                        topMaterial[high],
                        highY - lowY,
                        0.72F
                );

                mesh.addQuad(
                        x0, lowY, z,
                        x1, lowY, z,
                        x1, highY, z,
                        x0, highY, z,
                        color,
                        wallMaterial(topMaterial[high])
                );
            }
        }

        // Tile-edge skirts hide cracks where neighboring plateau averages differ.
        int skirtDepth = spacing * 2;

        for (int gz = 0; gz < cells; gz++) {
            int z0 = job.originZ + gz * spacing;
            int z1 = z0 + spacing;

            int west = gz * cells;
            addSkirt(mesh, job.originX, z0, job.originX, z1,
                    topY[west], skirtDepth, topColor[west], topMaterial[west], true);

            int east = gz * cells + cells - 1;
            int eastX = job.originX + cells * spacing;
            addSkirt(mesh, eastX, z1, eastX, z0,
                    topY[east], skirtDepth, topColor[east], topMaterial[east], true);
        }

        for (int gx = 0; gx < cells; gx++) {
            int x0 = job.originX + gx * spacing;
            int x1 = x0 + spacing;

            int north = gx;
            addSkirt(mesh, x1, job.originZ, x0, job.originZ,
                    topY[north], skirtDepth, topColor[north], topMaterial[north], false);

            int south = (cells - 1) * cells + gx;
            int southZ = job.originZ + cells * spacing;
            addSkirt(mesh, x0, southZ, x1, southZ,
                    topY[south], skirtDepth, topColor[south], topMaterial[south], false);
        }

        return mesh.finish();
    }

    private static byte dominantMaterial(
            GenerationJob job,
            int i00,
            int i10,
            int i01,
            int i11
    ) {
        int[] counts = new int[7];
        int[] indices = {i00, i10, i01, i11};

        for (int index : indices) {
            int material = Byte.toUnsignedInt(job.sampleMaterials[index]);
            if (material >= 0 && material < counts.length) {
                counts[material]++;
            }
        }

        // Two or more water corners make this a water cell. This keeps lakes and
        // oceans flat instead of interpolating blue ramps up their shorelines.
        if (counts[MinecraftSurfacePalette.MATERIAL_WATER] >= 2) {
            return MinecraftSurfacePalette.MATERIAL_WATER;
        }

        int bestMaterial = MinecraftSurfacePalette.MATERIAL_GRASS;
        int bestCount = -1;

        for (int material = 0; material < counts.length; material++) {
            if (material == MinecraftSurfacePalette.MATERIAL_WATER) {
                continue;
            }

            if (counts[material] > bestCount) {
                bestCount = counts[material];
                bestMaterial = material;
            }
        }

        return (byte) bestMaterial;
    }

    private static int representativeHeight(
            GenerationJob job,
            byte material,
            int seaLevel,
            int... indices
    ) {
        if (material == MinecraftSurfacePalette.MATERIAL_WATER) {
            return seaLevel;
        }

        int sum = 0;
        int count = 0;

        for (int index : indices) {
            if (job.sampleMaterials[index] == MinecraftSurfacePalette.MATERIAL_WATER) {
                continue;
            }

            sum += job.heights[index];
            count++;
        }

        if (count == 0) {
            return seaLevel;
        }

        int average = Math.round(sum / (float) count);

        // At near-ring sampling (8 blocks or finer), keep full one-block
        // vertical steps. Exact 1-block L1 now preserves the horizontal surface
        // footprint without smoothing away Minecraft's stepped silhouette.
        return job.sampleSpacing <= 8
                ? average
                : Math.round(average / 2.0F) * 2;
    }

    private static int representativeColor(
            GenerationJob job,
            byte material,
            int... indices
    ) {
        long red = 0;
        long green = 0;
        long blue = 0;
        int count = 0;

        for (int index : indices) {
            if (job.sampleMaterials[index] != material) {
                continue;
            }

            int rgb = job.sampleColors[index];
            red += (rgb >> 16) & 0xFF;
            green += (rgb >> 8) & 0xFF;
            blue += rgb & 0xFF;
            count++;
        }

        if (count == 0) {
            for (int index : indices) {
                int rgb = job.sampleColors[index];
                red += (rgb >> 16) & 0xFF;
                green += (rgb >> 8) & 0xFF;
                blue += rgb & 0xFF;
                count++;
            }
        }

        return ((int) (red / count) << 16)
                | ((int) (green / count) << 8)
                | (int) (blue / count);
    }

    private static int sideColor(
            int topColor,
            byte topMaterial,
            int heightDelta,
            float directionalShade
    ) {
        int target = switch (topMaterial) {
            case MinecraftSurfacePalette.MATERIAL_GRASS -> 0x65503A;
            case MinecraftSurfacePalette.MATERIAL_SAND -> 0xB7A66F;
            case MinecraftSurfacePalette.MATERIAL_TERRACOTTA -> 0x8F4F38;
            case MinecraftSurfacePalette.MATERIAL_SNOW -> 0x83888A;
            default -> MinecraftSurfacePalette.stoneColor();
        };

        float blend = Math.min(0.72F, 0.28F + heightDelta / 48.0F);
        int side = MinecraftSurfacePalette.blend(topColor, target, blend);
        return MinecraftSurfacePalette.applyLighting(side, directionalShade);
    }

    private static byte wallMaterial(byte topMaterial) {
        if (topMaterial == MinecraftSurfacePalette.MATERIAL_SAND
                || topMaterial == MinecraftSurfacePalette.MATERIAL_TERRACOTTA) {
            return topMaterial;
        }

        return MinecraftSurfacePalette.MATERIAL_STONE;
    }

    private static void addSkirt(
            MeshBuilder mesh,
            int x0,
            int z0,
            int x1,
            int z1,
            int topY,
            int depth,
            int topColor,
            byte material,
            boolean eastWest
    ) {
        int bottomY = topY - depth;
        int color = sideColor(
                topColor,
                material,
                depth,
                eastWest ? 0.72F : 0.66F
        );

        mesh.addQuad(
                x0, bottomY, z0,
                x1, bottomY, z1,
                x1, topY, z1,
                x0, topY, z0,
                color,
                wallMaterial(material)
        );
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
        private final int sampleSpacing;
        private final int samplesAcross;
        private final int cellsAcross;
        private final int totalSamples;
        private final int cellCount;
        private final int[] heights;
        private final int[] sampleColors;
        private final byte[] sampleMaterials;
        private final boolean refinement;

        private volatile int nextSample;
        private volatile boolean failed;
        private int minY = Integer.MAX_VALUE;
        private int maxY = Integer.MIN_VALUE;
        private long accumulatedNanos;

        private GenerationJob(
                LodTileKey key,
                WorldgenLodRing ring,
                int sampleSpacing,
                boolean refinement
        ) {
            if (sampleSpacing <= 0 || ring.tileSize() % sampleSpacing != 0) {
                throw new IllegalArgumentException(
                        "tile size must be divisible by generation spacing"
                );
            }

            this.key = key;
            this.ring = ring;
            this.originX = key.tileX() * ring.tileSize();
            this.originZ = key.tileZ() * ring.tileSize();
            this.sampleSpacing = sampleSpacing;
            this.refinement = refinement;
            this.samplesAcross = ring.tileSize() / sampleSpacing + 1;
            this.cellsAcross = samplesAcross - 1;
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
            byte[] materials,
            int quadCount
    ) {
    }

    private static final class MeshBuilder {
        private int[] vertices;
        private int[] colors;
        private byte[] materials;
        private int vertexInts;
        private int vertexCount;
        private int quadCount;

        private MeshBuilder(int estimatedQuads) {
            int quads = Math.max(8, estimatedQuads);
            this.vertices = new int[quads * 12];
            this.colors = new int[quads * 4];
            this.materials = new byte[quads * 4];
        }

        private void addQuad(
                int x0, int y0, int z0,
                int x1, int y1, int z1,
                int x2, int y2, int z2,
                int x3, int y3, int z3,
                int color,
                byte material
        ) {
            ensureCapacity(1);

            vertexInts = addVertex(x0, y0, z0, color, material, vertexInts);
            vertexInts = addVertex(x1, y1, z1, color, material, vertexInts);
            vertexInts = addVertex(x2, y2, z2, color, material, vertexInts);
            vertexInts = addVertex(x3, y3, z3, color, material, vertexInts);
            quadCount++;
        }

        private int addVertex(
                int x,
                int y,
                int z,
                int color,
                byte material,
                int out
        ) {
            vertices[out++] = x;
            vertices[out++] = y;
            vertices[out++] = z;
            colors[vertexCount] = color;
            materials[vertexCount] = material;
            vertexCount++;
            return out;
        }

        private void ensureCapacity(int moreQuads) {
            int requiredQuads = quadCount + moreQuads;
            int requiredVertexInts = requiredQuads * 12;
            int requiredVertices = requiredQuads * 4;

            if (requiredVertexInts > vertices.length) {
                int next = Math.max(requiredVertexInts, vertices.length * 2);
                vertices = Arrays.copyOf(vertices, next);
            }

            if (requiredVertices > colors.length) {
                int next = Math.max(requiredVertices, colors.length * 2);
                colors = Arrays.copyOf(colors, next);
                materials = Arrays.copyOf(materials, next);
            }
        }

        private MeshData finish() {
            return new MeshData(
                    Arrays.copyOf(vertices, vertexInts),
                    Arrays.copyOf(colors, vertexCount),
                    Arrays.copyOf(materials, vertexCount),
                    quadCount
            );
        }
    }

    public record L1ViewStatus(
            int desired,
            int covered,
            int intermediate,
            int exact
    ) {
    }

    public record StreamingStatus(
            double speedBlocksPerSecond,
            int predictiveLeadBlocks,
            boolean highSpeedCoverageMode,
            int predictiveDesired,
            int predictiveCovered,
            int staleJobsCancelled
    ) {
    }

    private record MotionPrediction(
            double forwardX,
            double forwardZ
    ) {
    }

    private record WantedTile(
            LodTileKey key,
            WorldgenLodRing ring,
            boolean prefetch,
            int targetSpacing,
            boolean foreground,
            boolean predictive
    ) {
    }

    private record CompletedTile(
            long epoch,
            LodTileKey key,
            WorldgenSurfaceTile tile
    ) {
    }
}
