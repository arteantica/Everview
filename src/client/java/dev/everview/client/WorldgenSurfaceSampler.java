package dev.everview.client;

import dev.everview.core.LodTileKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Budgeted progressive distant-worldgen sampler.
 *
 * Progressive distant-worldgen sampler.
 *
 * M5 keeps coverage and refinement as separate lanes. Two independent exact
 * L1 tiles can now use the two height workers concurrently instead of both
 * workers serializing one tile at a time.
 *
 * M3.11 splits exact L1 into geometry-first and appearance-second passes.
 * Missing 1-block heights are generated first while temporary material/color is
 * borrowed from nearby cached samples. Once exact geometry is resident, a
 * lower-priority appearance pass fills the missing biome/palette samples and
 * rebuilds the same 1b tile at full fidelity.
 */
public final class WorldgenSurfaceSampler {
    public static final int MIN_INNER_RADIUS = 256;
    public static final int HANDOFF_OVERLAP_BLOCKS = 64;
    public static final int MAX_OUTER_RADIUS = 16_384;

    public static final long MIN_SLICE_BUDGET_NANOS = 1_000_000L;
    public static final long BASE_SLICE_BUDGET_NANOS = 6_000_000L;
    public static final long NORMAL_MAX_SLICE_BUDGET_NANOS = 12_000_000L;
    public static final long MAX_SLICE_BUDGET_NANOS = 16_000_000L;
    public static final int MAX_SAMPLES_PER_SLICE = 128;
    // M9.2 keeps the same total background-thread envelope as M9.1, but
    // reallocates it toward exact 1b height production: 6 exact + 2 coverage
    // + 1 appearance workers on a 16-thread desktop. Exact render tiles are
    // internally split across several workers so the nearest 128b tile can
    // complete much sooner without increasing total background concurrency.
    private static final int EXACT_HEIGHT_WORKERS = Math.max(
            2,
            Math.min(
                    6,
                    Runtime.getRuntime().availableProcessors() * 3 / 8
            )
    );
    private static final int COVERAGE_HEIGHT_WORKERS = Math.max(
            1,
            Math.min(
                    2,
                    Runtime.getRuntime().availableProcessors() / 8
            )
    );
    private static final int MAX_DETACHED_EXACT_JOBS = Math.max(
            1,
            EXACT_HEIGHT_WORKERS / 2
    );
    private static final int MAX_DETACHED_COVERAGE_JOBS = Math.max(
            1,
            COVERAGE_HEIGHT_WORKERS
    );
    private static final int APPEARANCE_WORKERS = 1;
    private static final int MAX_DETACHED_APPEARANCE_JOBS =
            APPEARANCE_WORKERS;

    private static final int CACHE_LIMIT = 6_144;
    private static final long MAX_CPU_MESH_BYTES =
            1_300L * 1024L * 1024L;
    private static final int NEAR_RING_MAX_LEVEL = 2;
    private static final int EMERGENCY_UNDERLAY_LEVEL = 3;
    private static final int GLOBAL_SAFETY_FLOOR_LEVEL = 6;
    private static final int EMERGENCY_FALLBACK_INNER_BLOCKS = 0;
    private static final int EMERGENCY_UNDERLAY_OUTER_BLOCKS = 2_048;
    private static final double PREDICTION_START_BLOCKS_PER_SECOND = 12.0;
    private static final double HIGH_SPEED_BLOCKS_PER_SECOND = 64.0;
    private static final double VELOCITY_SMOOTHING = 0.35;
    private static final double PREDICTION_SECONDS = 4.0;
    private static final int MAX_PREDICTIVE_LEAD_BLOCKS = 3_072;
    private static final int PREDICTIVE_ANCHOR_QUANTUM = 64;
    private static final int REMAINING_COVERAGE_BURST = 8;
    private static final int INITIAL_COVERAGE_CYCLE = 7;
    private static final int EXACT_GEOMETRY_BURST = 2;
    private static final int MAX_PROVISIONAL_EXACT_TILES = 128;
    private static final int FOCUSED_L5_SAMPLE_SPACING = 4;
    private static final int FOCUSED_L6_SAMPLE_SPACING = 8;
    // Keep expensive high-density far refinement tightly centered on the
    // current view instead of refining most of a hemisphere.
    private static final double FAR_FOCUS_DOT_THRESHOLD = 0.90D;
    private static final int APPEARANCE_SERVICE_THRESHOLD = 4;
    private static final int APPEARANCE_COVERAGE_BURST = 4;
    private static final int SHARED_HEIGHT_CACHE_LIMIT = 1_250_000;
    private static final int SHARED_BIOME_CACHE_LIMIT = 350_000;
    private static final int L1_PREFETCH_BLOCKS = 256;
    private static final int L2_PREFETCH_BLOCKS = 256;
    private static final int L1_BOOTSTRAP_SPACING = 4;
    private static final int L1_INTERMEDIATE_SPACING = 2;
    private static final int L1_EXACT_SPACING = 1;
    private static final int L1_FINE_GRID_SAMPLES = 129;
    private static final int L1_SAMPLE_CACHE_LIMIT = 640;
    // M9 keeps a wide all-direction exact core and extends true 1-block terrain
    // through the current view all the way to the outer L1 boundary.
    private static final int L1_EXACT_BAND_BLOCKS = 896;
    private static final int L1_INTERMEDIATE_BAND_BLOCKS = 1_280;
    private static final double L1_FOCUS_DOT_THRESHOLD = 0.35D;
    private static final int L1_OUTER_RADIUS_BLOCKS = 2_048;

    // Persist useful work while streaming instead of waiting for the complete
    // 16K initial fill. This is the warm-rejoin contract for M9.
    private static final long DISK_AUTOSAVE_INTERVAL_NANOS =
            4_000_000_000L;
    private static final int DISK_AUTOSAVE_MIN_DIRTY_TILES = 8;
    private static final int VIEW_SECTOR_COUNT = 16;
    private static final double VIEW_SECTOR_DEGREES = 360.0 / VIEW_SECTOR_COUNT;
    private static final int COVERAGE_FRONTIER_BUCKET_BLOCKS = 64;

    private static final Map<LodTileKey, WorldgenSurfaceTile> CACHE =
            new LinkedHashMap<>(512, 0.75F, true);
    // Account for the size at insertion, even if a worker compacts a tile
    // between a cache replacement and the compaction completion notification.
    private static final Map<WorldgenSurfaceTile, Long> ACCOUNTED_MESH_BYTES =
            new IdentityHashMap<>();
    private static long cacheResidentBytes;
    private static final Map<LodTileKey, L1SampleGrid> L1_SAMPLE_CACHE =
            new LinkedHashMap<>(512, 0.75F, true);
    private static final ConcurrentHashMap<Long, Integer>
            SHARED_HEIGHT_CACHE = new ConcurrentHashMap<>(262_144);
    private static final AtomicLong SHARED_HEIGHT_HITS = new AtomicLong();
    private static final AtomicLong SHARED_HEIGHT_MISSES = new AtomicLong();
    private static final ConcurrentHashMap<Long, Holder<Biome>>
            SHARED_BIOME_CACHE = new ConcurrentHashMap<>(65_536);
    private static final AtomicLong SHARED_BIOME_HITS = new AtomicLong();
    private static final AtomicLong SHARED_BIOME_MISSES = new AtomicLong();
    private static final ConcurrentLinkedQueue<CompletedTile> COMPLETED =
            new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<CompactedTile>
            COMPLETED_COMPACTIONS = new ConcurrentLinkedQueue<>();
    private static final ExecutorService MESH_COMPACTION_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Everview-MeshCompaction");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
    private static final ExecutorService EXACT_HEIGHT_EXECUTOR =
            Executors.newFixedThreadPool(
                    EXACT_HEIGHT_WORKERS,
                    runnable -> {
                        Thread thread = new Thread(
                                runnable,
                                "Everview-ExactHeight"
                        );
                        thread.setDaemon(true);
                        return thread;
                    }
            );
    private static final ExecutorService COVERAGE_HEIGHT_EXECUTOR =
            Executors.newFixedThreadPool(
                    COVERAGE_HEIGHT_WORKERS,
                    runnable -> {
                        Thread thread = new Thread(
                                runnable,
                                "Everview-CoverageHeight"
                        );
                        thread.setDaemon(true);
                        return thread;
                    }
            );
    private static final ExecutorService APPEARANCE_EXECUTOR =
            Executors.newFixedThreadPool(
                    APPEARANCE_WORKERS,
                    runnable -> {
                        Thread thread = new Thread(
                                runnable,
                                "Everview-Appearance"
                        );
                        thread.setDaemon(true);
                        return thread;
                    }
            );

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
    private static final Map<LodTileKey, WorldgenSurfaceTile>
            DISK_DIRTY_TILES = new LinkedHashMap<>();
    private static List<WorldgenSurfaceTile> diskSaveBatch = List.of();
    private static long lastDiskSaveStartedNanos;
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
    private static int lastRefineReusedSamples;
    private static int lastRefineGeneratedSamples;
    private static int lastAppearanceGeneratedSamples;
    private static int lastProvisionalAppearanceSamples;
    private static long totalRefineReusedSamples;
    private static long totalRefineGeneratedSamples;
    private static long totalAppearanceGeneratedSamples;

    private static volatile long epoch;
    private static long nextSliceId;
    private static double lastGenerationMs;
    private static int generatedTileCount;
    private static long initialFillStartedNanos;
    private static long initialFillCompletedNanos;
    private static int balancedCoverageStep;
    private static int initialCoverageCycleStep;
    private static int exactGeometryBurstStep;
    private static int appearanceCoverageStep;

    private static List<WorldgenLodRing> activeRings = List.of();
    private static List<WantedTile> wantedTiles = List.of();
    private static Set<LodTileKey> wantedKeys = Set.of();
    private static GenerationJob currentJob;
    private static final Map<LodTileKey, CompletableFuture<Void>>
            DETACHED_EXACT_JOBS = new ConcurrentHashMap<>();
    private static final Map<LodTileKey, GenerationJob>
            DETACHED_COVERAGE_JOBS = new ConcurrentHashMap<>();
    private static final Map<LodTileKey, CompletableFuture<Void>>
            DETACHED_APPEARANCE_JOBS = new ConcurrentHashMap<>();
    private static int detachedCoverageLaunchStep;
    private static int detachedFarRefineLevelStep;

    private WorldgenSurfaceSampler() {
    }

    public static WorldgenSurfaceSnapshot snapshot() {
        return snapshot;
    }

    public static int coverageWorkerCount() {
        return COVERAGE_HEIGHT_WORKERS;
    }

    public static int exactWorkersPerTileCurrent() {
        return exactWorkersPerTile();
    }

    public static int exactWorkerBudgetCurrent() {
        return activeExactWorkerBudget();
    }

    public static double cpuTileCacheMiB() {
        return cacheResidentBytes / (1024.0 * 1024.0);
    }

    public static int exactWorkerCount() {
        return EXACT_HEIGHT_WORKERS;
    }

    public static int detachedCoverageJobsActive() {
        return DETACHED_COVERAGE_JOBS.size();
    }

    public static int detachedCoverageJobsMax() {
        return MAX_DETACHED_COVERAGE_JOBS;
    }

    public static int detachedAppearanceJobsActive() {
        return DETACHED_APPEARANCE_JOBS.size();
    }

    public static int detachedAppearanceJobsMax() {
        return MAX_DETACHED_APPEARANCE_JOBS;
    }

    public static int maxProvisionalExactTiles() {
        return MAX_PROVISIONAL_EXACT_TILES;
    }

    private static int activeExactWorkerBudget() {
        if (clientFrameMs <= 0.0 || clientFrameMs < 5.5) {
            return EXACT_HEIGHT_WORKERS;
        }
        if (clientFrameMs < 8.0) {
            return Math.min(4, EXACT_HEIGHT_WORKERS);
        }
        if (clientFrameMs < 12.0) {
            return Math.min(2, EXACT_HEIGHT_WORKERS);
        }
        return 1;
    }

    private static int exactWorkersPerTile() {
        int budget = activeExactWorkerBudget();
        if (budget >= 6) {
            return 3;
        }
        if (budget >= 4) {
            return 2;
        }
        return 1;
    }

    private static int activeExactJobBudget() {
        int workersPerTile = exactWorkersPerTile();
        return Math.max(
                1,
                Math.min(
                        MAX_DETACHED_EXACT_JOBS,
                        activeExactWorkerBudget() / workersPerTile
                )
        );
    }

    private static int activeCoverageJobBudget() {
        if (clientFrameMs >= 12.0) {
            return 1;
        }
        return MAX_DETACHED_COVERAGE_JOBS;
    }

    private static int activeAppearanceJobBudget() {
        return MAX_DETACHED_APPEARANCE_JOBS;
    }

    public static SharedHeightCacheStatus sharedHeightCacheStatus() {
        long hits = SHARED_HEIGHT_HITS.get();
        long misses = SHARED_HEIGHT_MISSES.get();
        long total = hits + misses;
        double hitPercent = total == 0L
                ? 0.0
                : hits * 100.0 / total;

        return new SharedHeightCacheStatus(
                SHARED_HEIGHT_CACHE.size(),
                hits,
                misses,
                hitPercent
        );
    }

    public static SharedBiomeCacheStatus sharedBiomeCacheStatus() {
        long hits = SHARED_BIOME_HITS.get();
        long misses = SHARED_BIOME_MISSES.get();
        long total = hits + misses;
        double hitPercent = total == 0L
                ? 0.0
                : hits * 100.0 / total;

        return new SharedBiomeCacheStatus(
                SHARED_BIOME_CACHE.size(),
                hits,
                misses,
                hitPercent
        );
    }

    public static StreamingStatus streamingStatus() {
        int predictiveDesired = 0;
        int predictiveCovered = 0;
        int emergencyDesired = 0;
        int emergencyCovered = 0;
        int globalFloorDesired = 0;
        int globalFloorCovered = 0;
        int outwardFrontierBlocks = -1;
        boolean nearCoverageComplete = true;

        for (WantedTile wanted : wantedTiles) {
            boolean covered = CACHE.containsKey(wanted.key());

            if (wanted.predictive()) {
                predictiveDesired++;
                if (covered) {
                    predictiveCovered++;
                }
            }

            if (isEmergencyUnderlayWanted(wanted)) {
                emergencyDesired++;
                if (covered) {
                    emergencyCovered++;
                }
            }

            if (isGlobalSafetyFloorWanted(wanted)) {
                globalFloorDesired++;
                if (covered) {
                    globalFloorCovered++;
                }
            }

            if (!wanted.prefetch() && !covered) {
                if (outwardFrontierBlocks < 0) {
                    outwardFrontierBlocks = wanted.frontierDistanceBlocks();
                }
                if (wanted.ring().lodLevel() <= NEAR_RING_MAX_LEVEL) {
                    nearCoverageComplete = false;
                }
            }
        }

        return new StreamingStatus(
                movementSpeedBlocksPerSecond,
                predictiveLeadBlocks,
                highSpeedCoverageMode,
                predictiveDesired,
                predictiveCovered,
                emergencyDesired,
                emergencyCovered,
                globalFloorDesired,
                globalFloorCovered,
                outwardFrontierBlocks,
                nearCoverageComplete,
                staleJobsCancelled
        );
    }

    public static RefinementReuseStatus refinementReuseStatus() {
        int appearanceDesired = 0;
        int appearanceReady = 0;

        for (WantedTile wanted : wantedTiles) {
            if (wanted.ring().lodLevel() != 1
                    || wanted.prefetch()
                    || wanted.targetSpacing() != L1_EXACT_SPACING) {
                continue;
            }

            WorldgenSurfaceTile tile = CACHE.get(wanted.key());
            if (tile == null || !tile.stage().exactGeometry()) {
                continue;
            }

            appearanceDesired++;
            if (tile.stage().exactAppearance()) {
                appearanceReady++;
            }
        }

        return new RefinementReuseStatus(
                lastRefineReusedSamples,
                lastRefineGeneratedSamples,
                lastAppearanceGeneratedSamples,
                lastProvisionalAppearanceSamples,
                totalRefineReusedSamples,
                totalRefineGeneratedSamples,
                totalAppearanceGeneratedSamples,
                L1_SAMPLE_CACHE.size(),
                appearanceReady,
                appearanceDesired,
                provisionalExactTileCount(),
                DETACHED_EXACT_JOBS.size()
                        + ((currentJob != null
                                && currentJob.asyncHeightFuture != null
                                && !currentJob.asyncHeightFuture.isDone())
                                ? 1 : 0),
                currentJob != null && currentJob.asyncExactDisabled
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
        drainCompletedCompactions();
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
            Set<LodTileKey> keys = new HashSet<>(wantedTiles.size());
            for (WantedTile wanted : wantedTiles) {
                keys.add(wanted.key());
            }
            wantedKeys = keys;

            if (initialFillStartedNanos == 0L && !wantedTiles.isEmpty()) {
                initialFillStartedNanos = System.nanoTime();
            }
        }

        if (!diskLoadReady) {
            rebuildSnapshot();
            return;
        }

        pollDetachedExact();
        pollDetachedAppearance();

        if (!highSpeedCoverageMode) {
            int appearanceLaunches = 0;
            while (DETACHED_APPEARANCE_JOBS.size()
                    < activeAppearanceJobBudget()
                    && appearanceLaunches < 2) {
                WantedTile appearance =
                        firstExactAppearanceRefinement(null);

                if (appearance == null
                        || !startDetachedAppearance(
                                server,
                                clientLevel.dimension(),
                                appearance
                        )) {
                    break;
                }

                appearanceLaunches++;
            }
        }

        if (highSpeedCoverageMode && !DETACHED_EXACT_JOBS.isEmpty()) {
            staleJobsCancelled += cancelDetachedExactJobs();
        }

        if (!highSpeedCoverageMode
                && provisionalExactTileCount()
                        + DETACHED_EXACT_JOBS.size()
                        < MAX_PROVISIONAL_EXACT_TILES) {
            LodTileKey pending = currentJob == null
                    ? null
                    : currentJob.key;

            while (DETACHED_EXACT_JOBS.size()
                    < activeExactJobBudget()) {
                WantedTile detached =
                        firstExactBandRefinement(pending);

                if (detached == null) {
                    break;
                }

                if (!startDetachedExact(
                        server,
                        clientLevel.dimension(),
                        detached
                )) {
                    break;
                }
            }
        }

        // M6.6: L2-L6 no longer wait behind one coverage tile lifecycle.
        // Each detached job owns one complete tile height request and uses one
        // worker from the shared coverage pool, allowing several tiles to make
        // progress at the same time while the server lane keeps producing L1.
        if (diskLoadReady) {
            pollDetachedCoverage();

            while (DETACHED_COVERAGE_JOBS.size()
                    < activeCoverageJobBudget()) {
                WantedTile coverage =
                        firstDetachedCoverageCandidate();

                if (coverage == null
                        || !startDetachedCoverage(
                                server,
                                clientLevel.dimension(),
                                coverage
                        )) {
                    break;
                }
            }
        }

        if (currentJob != null
                && activeSliceId == 0L
                && !containsWantedKey(currentJob.key)) {
            cancelCurrentJob();
            staleJobsCancelled++;
        }

        if (currentJob != null
                && activeSliceId == 0L
                && highSpeedCoverageMode
                && currentJob.refinement) {
            cancelCurrentJob();
            staleJobsCancelled++;
        }

        if (currentJob != null && currentJob.failed && activeSliceId == 0L) {
            cancelCurrentJob();
        }

        if (currentJob == null && activeSliceId == 0L) {
            GenerationJob readyCoverage =
                    takeReadyDetachedCoverage();
            WantedTile next = readyCoverage == null
                    ? findNextMissing()
                    : null;

            if (readyCoverage != null) {
                currentJob = readyCoverage;
            } else if (next != null) {
                WorldgenSurfaceTile existing = CACHE.get(next.key());

                int sampleSpacing = nextGenerationSpacing(next, existing);

                L1SampleGrid sampleGrid = null;
                boolean appearanceOnly = false;
                if (next.ring().lodLevel() == 1) {
                    sampleGrid = L1_SAMPLE_CACHE.computeIfAbsent(
                            next.key(),
                            ignored -> new L1SampleGrid(next.ring().tileSize())
                    );
                    trimL1SampleCache();

                    appearanceOnly = existing != null
                            && existing.stage() == WorldgenTileStage.EXACT_GEOMETRY
                            && sampleGrid.requiresAppearanceRefinement
                            && next.targetSpacing() == L1_EXACT_SPACING;
                    if (appearanceOnly) {
                        sampleSpacing = L1_EXACT_SPACING;
                    }
                }

                currentJob = new GenerationJob(
                        next.key(),
                        next.ring(),
                        sampleSpacing,
                        existing != null,
                        sampleGrid,
                        appearanceOnly
                );
            }
        }

        if (currentJob != null
                && activeSliceId == 0L
                && (currentJob.asyncHeightFuture == null
                        || currentJob.asyncHeightFuture.isDone())) {
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
                    putCacheTile(
                            new LodTileKey(
                                    tile.lodLevel(),
                                    tile.tileX(),
                                    tile.tileZ()
                            ),
                            tile
                    );
                }

                diskLoadedTiles = result.tiles().size();
                diskLoadMs = result.elapsedMs();
                diskCacheStatus = result.status();
                diskLoadReady = true;
                trimCache();
                scheduleMeshCompaction(result.tiles());
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

                if ("SAVED".equals(result.status())) {
                    scheduleMeshCompaction(diskSaveBatch);
                }

                if (!"SAVED".equals(result.status())
                        && !"IDLE".equals(result.status())) {
                    for (WorldgenSurfaceTile tile : diskSaveBatch) {
                        DISK_DIRTY_TILES.put(
                                new LodTileKey(
                                        tile.lodLevel(),
                                        tile.tileX(),
                                        tile.tileZ()
                                ),
                                tile
                        );
                    }
                }
            } catch (RuntimeException exception) {
                diskCacheStatus = "SAVE_ERROR";
                for (WorldgenSurfaceTile tile : diskSaveBatch) {
                    DISK_DIRTY_TILES.put(
                            new LodTileKey(
                                    tile.lodLevel(),
                                    tile.tileX(),
                                    tile.tileZ()
                            ),
                            tile
                    );
                }
                EverviewClient.LOGGER.warn(
                        "Everview regional LOD cache save task failed",
                        exception
                );
            } finally {
                diskSaveFuture = null;
                diskSaveBatch = List.of();
            }
        }
    }

    private static void maybeScheduleDiskSave() {
        if (DISK_DIRTY_TILES.isEmpty()
                || diskCachePath == null
                || diskSaveFuture != null
                || !diskLoadReady) {
            return;
        }

        long now = System.nanoTime();
        boolean enoughNewTiles =
                DISK_DIRTY_TILES.size() >= DISK_AUTOSAVE_MIN_DIRTY_TILES;
        boolean intervalElapsed =
                lastDiskSaveStartedNanos == 0L
                        || now - lastDiskSaveStartedNanos
                                >= DISK_AUTOSAVE_INTERVAL_NANOS;

        if (!snapshot.initialFillComplete()
                && (!enoughNewTiles || !intervalElapsed)) {
            return;
        }

        diskSaveBatch = List.copyOf(DISK_DIRTY_TILES.values());
        DISK_DIRTY_TILES.clear();

        Path path = diskCachePath;
        long seed = diskCacheSeed;
        String dimension = diskCacheDimension;
        List<WorldgenSurfaceTile> batch = diskSaveBatch;

        lastDiskSaveStartedNanos = now;
        diskCacheStatus = "SAVING";
        diskSaveFuture = CompletableFuture.supplyAsync(
                () -> WorldgenDiskCache.save(
                        path,
                        seed,
                        dimension,
                        batch
                )
        );
    }

    private static void scheduleDetachedSaveIfDirty() {
        if (diskCachePath == null
                || (DISK_DIRTY_TILES.isEmpty()
                        && diskSaveFuture == null)) {
            return;
        }

        List<WorldgenSurfaceTile> pending =
                new ArrayList<>(DISK_DIRTY_TILES.values());
        List<WorldgenSurfaceTile> inFlightBatch =
                List.copyOf(diskSaveBatch);
        Path path = diskCachePath;
        long seed = diskCacheSeed;
        String dimension = diskCacheDimension;
        CompletableFuture<WorldgenDiskCache.SaveResult> inFlight =
                diskSaveFuture;

        DISK_DIRTY_TILES.clear();

        // Serialize the final world-exit write behind an in-flight regional
        // save. If that save failed, merge its batch back into the final write.
        CompletableFuture.runAsync(() -> {
            if (inFlight != null) {
                try {
                    WorldgenDiskCache.SaveResult result = inFlight.join();
                    if (!"SAVED".equals(result.status())
                            && !"IDLE".equals(result.status())) {
                        pending.addAll(inFlightBatch);
                    }
                } catch (RuntimeException exception) {
                    pending.addAll(inFlightBatch);
                }
            }

            if (!pending.isEmpty()) {
                WorldgenDiskCache.save(
                        path,
                        seed,
                        dimension,
                        pending
                );
            }
        });
    }

    private static void updateAdaptiveBudget(Minecraft client, MinecraftServer server) {
        serverTickMs = server.getAverageTickTimeNanos() / 1_000_000.0;
        clientFrameMs = client.getFrameTimeNs() / 1_000_000.0;

        long target;

        // M3.5 uses the large amount of singleplayer tick headroom during
        // initial streaming, then backs off automatically as either the server
        // tick or client frame becomes busy.
        if (highSpeedCoverageMode
                && serverTickMs < 20.0
                && (clientFrameMs <= 0.0 || clientFrameMs < 12.0)) {
            target = MAX_SLICE_BUDGET_NANOS;
        } else if (serverTickMs < 15.0
                && (clientFrameMs <= 0.0 || clientFrameMs < 10.0)) {
            target = NORMAL_MAX_SLICE_BUDGET_NANOS;
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

        // M9 moves L1 from a narrow transition ring to a real near-distance
        // product. A wide exact core surrounds the player and the current view
        // can refine true 1-block terrain all the way to ~2K.
        int ultraNearOuter = Math.min(
                L1_OUTER_RADIUS_BLOCKS,
                Math.max(1_536, innerRadius + 1_536)
        );

        rings.add(new WorldgenLodRing(
                1,
                innerRadius,
                ultraNearOuter,
                128,
                1
        ));

        // L2 overlaps the full L1 annulus, but M6.2 raises it from 8b to 2b.
        // This prevents an immediate cliff from exact 1b terrain to a visibly
        // coarse carpet the moment L1 ends.
        rings.add(new WorldgenLodRing(
                2,
                innerRadius,
                L1_OUTER_RADIUS_BLOCKS,
                128,
                2
        ));

        // M5.4: L3 is a true full-disk fallback floor from the camera column
        // all the way to 2K. This closes the last high-altitude hole directly
        // below the player and gives every missing/not-ready vanilla column a
        // terrain surface to reveal. Chunk-column ownership hides it the moment
        // vanilla is actually visible. New L3 tiles still bootstrap at 64b.
        int emergencyInnerRadius = Math.min(
                innerRadius,
                EMERGENCY_FALLBACK_INNER_BLOCKS
        );
        rings.add(new WorldgenLodRing(
                3,
                emergencyInnerRadius,
                2_048,
                256,
                2
        ));
        // M6.7 raises the settled visual ceiling again:
        // L2 2b -> L3 2b -> L4 4b -> L5 8b -> L6 16b.
        // The bootstrap path is still deliberately coarser, but because every
        // final target is halved, first-visible far terrain is also twice as
        // dense as M6.6.
        rings.add(new WorldgenLodRing(4, 0, 4_096, 512, 4));
        rings.add(new WorldgenLodRing(5, 0, 8_192, 1_024, 8));
        rings.add(new WorldgenLodRing(6, 0, 16_384, 2_048, 16));

        return List.copyOf(rings);
    }

    private static void cancelCurrentJob() {
        if (currentJob != null && currentJob.asyncHeightFuture != null) {
            currentJob.asyncHeightFuture.cancel(true);
        }
        currentJob = null;
    }

    private static void reset() {
        scheduleDetachedSaveIfDirty();
        epoch++;
        CACHE.clear();
        ACCOUNTED_MESH_BYTES.clear();
        cacheResidentBytes = 0L;
        L1_SAMPLE_CACHE.clear();
        SHARED_HEIGHT_CACHE.clear();
        SHARED_HEIGHT_HITS.set(0L);
        SHARED_HEIGHT_MISSES.set(0L);
        SHARED_BIOME_CACHE.clear();
        SHARED_BIOME_HITS.set(0L);
        SHARED_BIOME_MISSES.set(0L);
        COMPLETED.clear();
        COMPLETED_COMPACTIONS.clear();
        activeRings = List.of();
        wantedTiles = List.of();
        wantedKeys = Set.of();
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
        lastRefineReusedSamples = 0;
        lastRefineGeneratedSamples = 0;
        lastAppearanceGeneratedSamples = 0;
        lastProvisionalAppearanceSamples = 0;
        totalRefineReusedSamples = 0L;
        totalRefineGeneratedSamples = 0L;
        totalAppearanceGeneratedSamples = 0L;
        lastGenerationMs = 0.0;
        lastSliceMs = 0.0;
        lastSliceSamples = 0;
        generatedTileCount = 0;
        initialFillStartedNanos = 0L;
        initialFillCompletedNanos = 0L;
        balancedCoverageStep = 0;
        initialCoverageCycleStep = 0;
        exactGeometryBurstStep = 0;
        appearanceCoverageStep = 0;
        adaptiveSliceBudgetNanos = BASE_SLICE_BUDGET_NANOS;
        serverTickMs = 0.0;
        clientFrameMs = 0.0;
        diskLoadFuture = null;
        diskSaveFuture = null;
        diskCachePath = null;
        diskCacheSeed = 0L;
        diskCacheDimension = "";
        diskLoadReady = false;
        DISK_DIRTY_TILES.clear();
        diskSaveBatch = List.of();
        lastDiskSaveStartedNanos = 0L;
        diskLoadedTiles = 0;
        diskLoadMs = 0.0;
        diskSavedTiles = 0;
        diskSaveMs = 0.0;
        diskFileMiB = 0.0;
        diskCacheStatus = "OFF";
        cancelCurrentJob();
        cancelDetachedExactJobs();
        cancelDetachedCoverageJobs();
        cancelDetachedAppearanceJobs();
        detachedCoverageLaunchStep = 0;
        detachedFarRefineLevelStep = 0;
        activeSliceId = 0L;
    }

    private static void drainCompleted() {
        CompletedTile completed;

        while ((completed = COMPLETED.poll()) != null) {
            if (completed.epoch() != epoch) {
                continue;
            }

            putCacheTile(completed.key(), completed.tile());
            lastGenerationMs = completed.tile().generationMs();
            generatedTileCount++;
            if (completed.tile().stage().diskSafe()) {
                DISK_DIRTY_TILES.put(
                        completed.key(),
                        completed.tile()
                );
            }

            if (completed.tile().lodLevel() == 1) {
                lastRefineReusedSamples = completed.reusedSamples();
                lastRefineGeneratedSamples = completed.generatedSamples();
                lastAppearanceGeneratedSamples = completed.appearanceGeneratedSamples();
                lastProvisionalAppearanceSamples = completed.provisionalAppearanceSamples();
                totalRefineReusedSamples += completed.reusedSamples();
                totalRefineGeneratedSamples += completed.generatedSamples();
                totalAppearanceGeneratedSamples += completed.appearanceGeneratedSamples();
            }

            if (currentJob != null && currentJob.key.equals(completed.key())) {
                currentJob = null;
            }
        }

        trimCache();
    }

    /**
     * Build one global outward coverage order instead of interleaving rings.
     * Visible terrain is ordered by radial frontier from the player, in 64-block
     * buckets. Within a frontier bucket, coarse L2 safety terrain wins before L1
     * bootstrap and forward-facing tiles win before rear tiles. Predictive and
     * guard entries stay marked as prefetch so the scheduler can place them
     * between near coverage and distant horizon work.
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
        List<WantedTile> ordered = new ArrayList<>();

        for (WorldgenLodRing ring : rings) {
            ordered.addAll(buildRingWantedTiles(
                    centerX,
                    centerZ,
                    predictiveCenterX,
                    predictiveCenterZ,
                    predictionActive,
                    ring,
                    forwardX,
                    forwardZ
            ));
        }

        ordered.sort(
                Comparator.comparing(WantedTile::prefetch)
                        .thenComparingInt(entry ->
                                entry.frontierDistanceBlocks()
                                        / COVERAGE_FRONTIER_BUCKET_BLOCKS
                        )
                        .thenComparingInt(entry ->
                                coverageLevelRank(entry.ring().lodLevel())
                        )
                        .thenComparingInt(
                                entry -> entry.foreground() ? 0 : 1
                        )
                        .thenComparingInt(
                                entry -> entry.predictive() ? 0 : 1
                        )
                        .thenComparingInt(WantedTile::frontierDistanceBlocks)
                        .thenComparingLong(entry ->
                                tileCenterDistanceSq(
                                        entry.key(),
                                        entry.ring(),
                                        centerX,
                                        centerZ
                                ))
        );

        return List.copyOf(ordered);
    }

    private static int coverageLevelRank(int lodLevel) {
        if (lodLevel == 1) {
            return highSpeedCoverageMode ? 1 : 0;
        }
        if (lodLevel == 2) {
            return highSpeedCoverageMode ? 0 : 1;
        }
        return lodLevel + 1;
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
                && ring.lodLevel() <= EMERGENCY_UNDERLAY_LEVEL;
        int predictiveStreamOuterRadius =
                ring.lodLevel() == EMERGENCY_UNDERLAY_LEVEL
                        ? Math.min(
                                streamOuterRadius,
                                EMERGENCY_UNDERLAY_OUTER_BLOCKS
                        )
                        : streamOuterRadius;

        int minX = centerX - streamOuterRadius;
        int maxX = centerX + streamOuterRadius;
        int minZ = centerZ - streamOuterRadius;
        int maxZ = centerZ + streamOuterRadius;

        if (predictiveRing) {
            minX = Math.min(
                    minX,
                    predictiveCenterX - predictiveStreamOuterRadius
            );
            maxX = Math.max(
                    maxX,
                    predictiveCenterX + predictiveStreamOuterRadius
            );
            minZ = Math.min(
                    minZ,
                    predictiveCenterZ - predictiveStreamOuterRadius
            );
            maxZ = Math.max(
                    maxZ,
                    predictiveCenterZ + predictiveStreamOuterRadius
            );
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
                            predictiveStreamOuterRadius,
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

                        // Only the view-relevant sector gets the expensive
                        // extended exact lane. The all-direction exact core
                        // remains wide enough that turning never exposes a
                        // coarse wall directly outside vanilla.
                        double distance = Math.hypot(dx, dz);
                        double facing = distance <= 1.0D
                                ? 1.0D
                                : (dx * forwardX + dz * forwardZ) / distance;
                        foreground = facing >= L1_FOCUS_DOT_THRESHOLD;

                        if (!visibleNow) {
                            targetSpacing = L1_BOOTSTRAP_SPACING;
                        } else {
                            int exactOuter = Math.min(
                                    ring.outerRadiusBlocks(),
                                    ring.innerRadiusBlocks()
                                            + L1_EXACT_BAND_BLOCKS
                            );
                            int intermediateOuter = Math.min(
                                    ring.outerRadiusBlocks(),
                                    ring.innerRadiusBlocks()
                                            + L1_INTERMEDIATE_BAND_BLOCKS
                            );

                            boolean inExactCore = tileIntersectsAnnulus(
                                    tileX,
                                    tileZ,
                                    centerX,
                                    centerZ,
                                    ring.innerRadiusBlocks(),
                                    exactOuter,
                                    tileSize
                            );
                            boolean inFocusedExact = foreground
                                    && tileIntersectsAnnulus(
                                            tileX,
                                            tileZ,
                                            centerX,
                                            centerZ,
                                            exactOuter,
                                            ring.outerRadiusBlocks(),
                                            tileSize
                                    );

                            if (inExactCore || inFocusedExact) {
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
                    } else if (visibleNow && ring.lodLevel() >= 5) {
                        double tileCenterX =
                                tileX * (double) tileSize
                                        + tileSize * 0.5;
                        double tileCenterZ =
                                tileZ * (double) tileSize
                                        + tileSize * 0.5;
                        double dx = tileCenterX - centerX;
                        double dz = tileCenterZ - centerZ;
                        double distance = Math.hypot(dx, dz);
                        double facing = distance <= 1.0D
                                ? 1.0D
                                : (dx * forwardX + dz * forwardZ)
                                        / distance;

                        foreground = facing >= FAR_FOCUS_DOT_THRESHOLD;

                        if (foreground) {
                            targetSpacing = ring.lodLevel() == 5
                                    ? FOCUSED_L5_SAMPLE_SPACING
                                    : FOCUSED_L6_SAMPLE_SPACING;
                        }
                    }

                    entries.add(new WantedTile(
                            key,
                            ring,
                            !visibleNow,
                            targetSpacing,
                            foreground || predictive,
                            predictive,
                            tileNearestDistanceBlocks(
                                    tileX,
                                    tileZ,
                                    centerX,
                                    centerZ,
                                    tileSize
                            )
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

    private static int tileNearestDistanceBlocks(
            int tileX,
            int tileZ,
            int centerX,
            int centerZ,
            int tileSize
    ) {
        int minX = tileX * tileSize;
        int minZ = tileZ * tileSize;
        int maxX = minX + tileSize;
        int maxZ = minZ + tileSize;

        int nearestX = Math.max(minX, Math.min(centerX, maxX));
        int nearestZ = Math.max(minZ, Math.min(centerZ, maxZ));
        long dx = (long) nearestX - centerX;
        long dz = (long) nearestZ - centerZ;

        return (int) Math.floor(Math.sqrt(dx * dx + dz * dz));
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
        return wantedKeys.contains(key);
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

            // Exact-target tiles no longer spend a whole pass producing an
            // intermediate mesh that will immediately be replaced. The fixed
            // fine-sample cache preserves any 4b/2b points already known.
            if (target == L1_EXACT_SPACING) {
                return L1_EXACT_SPACING;
            }

            if (existing.sampleSpacing() > L1_INTERMEDIATE_SPACING
                    && target <= L1_INTERMEDIATE_SPACING) {
                return L1_INTERMEDIATE_SPACING;
            }
            return target;
        }

        if (existing == null) {
            // Coverage still appears at the stable all-direction ring target.
            // View-focus refinement is a second pass so turning never waits on
            // 4b/8b far generation before any terrain can appear.
            int bootstrapMultiplier = ring.lodLevel() >= 5 ? 1 : 2;
            return Math.min(
                    ring.tileSize(),
                    ring.sampleSpacing() * bootstrapMultiplier
            );
        }

        int target = wanted.targetSpacing();
        if (existing.sampleSpacing() > target) {
            return Math.max(
                    target,
                    existing.sampleSpacing() / 2
            );
        }

        return existing.sampleSpacing();
    }

    private static WantedTile findNextMissing() {
        LodTileKey pending = currentJob == null ? null : currentJob.key;

        // M6.9 exact appearance is detached from the server quality scheduler.
        // Dedicated appearance workers drain provisional 1b tiles continuously,
        // leaving this lane free to keep producing near and far geometry.

        // -2) Normal startup now builds three layers together:
        // 4 near tiles : 2 L3 shield tiles : 1 global L6 horizon tile.
        // M6.2 could leave L3 at 0 for several minutes, which meant the L6
        // emergency floor was the thing visible through caves/rivers/trees.
        WantedTile nearFirst = !highSpeedCoverageMode
                ? firstMissingCurrentNearCoverage(pending)
                : null;
        WantedTile emergencyUnderlay =
                firstMissingEmergencyUnderlayCoverage(pending);
        WantedTile globalFloor =
                firstMissingGlobalSafetyFloorCoverage(pending);

        if (!highSpeedCoverageMode
                && (nearFirst != null
                        || emergencyUnderlay != null
                        || globalFloor != null)) {
            for (int attempt = 0;
                    attempt < INITIAL_COVERAGE_CYCLE;
                    attempt++) {
                int phase = initialCoverageCycleStep;
                initialCoverageCycleStep =
                        (initialCoverageCycleStep + 1)
                                % INITIAL_COVERAGE_CYCLE;

                WantedTile candidate = phase < 4
                        ? nearFirst
                        : (phase < 6
                                ? emergencyUnderlay
                                : globalFloor);

                if (candidate != null) {
                    balancedCoverageStep = 0;
                    return candidate;
                }
            }
        }

        // -1) High-speed travel keeps the L3 shield as its first safety layer.
        if (highSpeedCoverageMode && emergencyUnderlay != null) {
            initialCoverageCycleStep = 0;
            balancedCoverageStep = 0;
            return emergencyUnderlay;
        }

        if (globalFloor != null) {
            initialCoverageCycleStep = 0;
            balancedCoverageStep = 0;
            return globalFloor;
        }

        if (emergencyUnderlay != null) {
            initialCoverageCycleStep = 0;
            balancedCoverageStep = 0;
            return emergencyUnderlay;
        }

        if (nearFirst != null) {
            initialCoverageCycleStep = 0;
            balancedCoverageStep = 0;
            return nearFirst;
        }

        // 0) Fill L4 then L5 once both the near field and horizon exist.
        if (!highSpeedCoverageMode) {
            WantedTile progressiveFar =
                    firstMissingProgressiveFarCoverage(pending);
            if (progressiveFar != null) {
                balancedCoverageStep = 0;
                return progressiveFar;
            }
        }

        // 1) Detached exact geometry is allowed to run beside coverage, but
        // once its provisional backlog reaches the cap the server lane spends
        // enough time on appearance to stop temporary green exact tiles from
        // spreading indefinitely.
        if (!highSpeedCoverageMode
                && provisionalExactTileCount()
                        >= MAX_PROVISIONAL_EXACT_TILES) {
            WantedTile appearance =
                    firstExactAppearanceRefinement(pending);
            if (appearance != null) {
                return appearance;
            }
        }

        // 2) High-speed mode reaches the near field only after safety coverage.
        // Normal mode already handled it at the top of the scheduler.
        WantedTile nearCoverage = highSpeedCoverageMode
                ? firstMissingCurrentNearCoverage(pending)
                : null;
        if (nearCoverage != null) {
            balancedCoverageStep = 0;
            return nearCoverage;
        }

        // 4) Once the immediate near field is continuous, warm the travel
        // corridor. L2 safety tiles sort before L1 bootstrap within each band.
        WantedTile predictiveCoverage =
                firstMissingPredictiveCoverage(pending);
        if (predictiveCoverage != null) {
            balancedCoverageStep = 0;
            return predictiveCoverage;
        }

        // 5) At extreme speed, keep the next near guard warm before caring
        // about distant horizon coverage or any refinement.
        WantedTile nearGuard =
                firstMissingNearGuardCoverage(pending);
        if (highSpeedCoverageMode && nearGuard != null) {
            balancedCoverageStep = 0;
            return nearGuard;
        }

        // 6) Remaining visible coverage now expands strictly outward through
        // L3/L4/L5/L6 instead of being interleaved inward/outward.
        WantedTile remainingVisible =
                firstMissingCurrentFarCoverage(pending);

        if (highSpeedCoverageMode) {
            if (remainingVisible != null) {
                balancedCoverageStep = 0;
                return remainingVisible;
            }

            balancedCoverageStep = 0;
            return nearGuard != null
                    ? nearGuard
                    : firstMissingOtherGuardCoverage(pending);
        }

        // 7) Normal-speed quality work is an explicit staged pipeline:
        // exact geometry is allowed to lead, but never by more than a small
        // bounded backlog before exact appearance is forced to catch up.
        WantedTile qualityWork = selectQualityWork(pending);

        WantedTile remainingCoverage = remainingVisible != null
                ? remainingVisible
                : nearGuard;
        if (remainingCoverage == null) {
            remainingCoverage = firstMissingOtherGuardCoverage(pending);
        }

        if (remainingCoverage != null && qualityWork != null) {
            if (balancedCoverageStep < REMAINING_COVERAGE_BURST) {
                balancedCoverageStep++;
                return remainingCoverage;
            }

            balancedCoverageStep = 0;
            return qualityWork;
        }

        if (remainingCoverage != null) {
            balancedCoverageStep = 0;
            return remainingCoverage;
        }

        if (qualityWork != null) {
            balancedCoverageStep = 0;
            return qualityWork;
        }

        return null;
    }

    private static boolean isTileMissing(LodTileKey key) {
        return !CACHE.containsKey(key)
                && !DETACHED_COVERAGE_JOBS.containsKey(key);
    }

    private static WantedTile firstDetachedCoverageCandidate() {
        LodTileKey pending = currentJob == null ? null : currentJob.key;

        // M9 reserves more producer slots for the visible L1 bootstrap so the
        // exact worker pool receives usable 4b appearance anchors immediately.
        // Coarse safety coverage still advances continuously in parallel.
        for (int attempt = 0; attempt < 8; attempt++) {
            int phase = detachedCoverageLaunchStep % 8;
            detachedCoverageLaunchStep =
                    (detachedCoverageLaunchStep + 1) % 8;

            WantedTile candidate = switch (phase) {
                // Feed exact L1 aggressively: half of detached coverage slots
                // create the 4b appearance anchors that exact workers require.
                case 0, 1, 2, 3 -> firstMissingL1BootstrapCoverage(pending);
                case 4, 5 -> firstMissingLevelCoverage(pending, 2);
                case 6 -> firstMissingLevelCoverage(pending, 3);
                default -> firstMissingLevelCoverage(pending, 6);
            };

            if (candidate != null
                    && candidate.ring().lodLevel() >= 1
                    && candidate.ring().lodLevel() <= 6) {
                return candidate;
            }
        }

        WantedTile l1 = firstMissingL1BootstrapCoverage(pending);
        if (l1 != null) {
            return l1;
        }

        WantedTile fallback = firstMissingCurrentFarCoverage(pending);
        if (fallback != null && fallback.ring().lodLevel() >= 2) {
            return fallback;
        }

        WantedTile refine = firstDetachedFarRefinementCandidate(pending);
        if (refine != null) {
            return refine;
        }

        return firstMissingFallbackCoverage(pending);
    }

    private static WantedTile firstMissingL1BootstrapCoverage(
            LodTileKey pending
    ) {
        for (WantedTile wanted : wantedTiles) {
            if (wanted.key().equals(pending)
                    || DETACHED_APPEARANCE_JOBS.containsKey(wanted.key())
                    || wanted.prefetch()
                    || wanted.ring().lodLevel() != 1
                    || !wanted.foreground()) {
                continue;
            }

            if (isTileMissing(wanted.key())) {
                return wanted;
            }
        }

        return null;
    }

    private static WantedTile firstMissingLevelCoverage(
            LodTileKey pending,
            int lodLevel
    ) {
        for (WantedTile wanted : wantedTiles) {
            if (wanted.key().equals(pending)
                    || wanted.prefetch()
                    || wanted.ring().lodLevel() != lodLevel) {
                continue;
            }

            if (isTileMissing(wanted.key())) {
                return wanted;
            }
        }

        return null;
    }

    private static WantedTile firstDetachedFarRefinementCandidate(
            LodTileKey pending
    ) {
        WantedTile focused = firstFocusedFarRefinement(pending);
        if (focused != null) {
            return focused;
        }

        for (int attempt = 0; attempt < 4; attempt++) {
            int level = 3 + (detachedFarRefineLevelStep % 4);
            detachedFarRefineLevelStep =
                    (detachedFarRefineLevelStep + 1) % 4;

            for (WantedTile wanted : wantedTiles) {
                if (wanted.key().equals(pending)
                        || wanted.prefetch()
                        || wanted.ring().lodLevel() != level
                        || DETACHED_COVERAGE_JOBS.containsKey(wanted.key())) {
                    continue;
                }

                WorldgenSurfaceTile tile = CACHE.get(wanted.key());
                if (tile != null
                        && tile.sampleSpacing()
                                > wanted.targetSpacing()) {
                    return wanted;
                }
            }
        }

        return null;
    }

    private static WantedTile firstMissingProgressiveFarCoverage(
            LodTileKey pending
    ) {
        // Fill L4 first (0-4K), then L5 (0-8K). L3 already exists as the
        // immediate safety surface and L6 remains the global emergency floor.
        for (int level = 4; level <= 5; level++) {
            for (WantedTile wanted : wantedTiles) {
                if (wanted.key().equals(pending)
                        || wanted.prefetch()
                        || wanted.ring().lodLevel() != level) {
                    continue;
                }

                if (isTileMissing(wanted.key())) {
                    return wanted;
                }
            }
        }

        return null;
    }

    private static boolean isGlobalSafetyFloorWanted(
            WantedTile wanted
    ) {
        return wanted.ring().lodLevel() == GLOBAL_SAFETY_FLOOR_LEVEL
                && !wanted.prefetch();
    }

    private static WantedTile firstMissingGlobalSafetyFloorCoverage(
            LodTileKey pending
    ) {
        for (WantedTile wanted : wantedTiles) {
            if (wanted.key().equals(pending)
                    || !isGlobalSafetyFloorWanted(wanted)) {
                continue;
            }

            if (isTileMissing(wanted.key())) {
                return wanted;
            }
        }

        return null;
    }

    private static boolean isEmergencyUnderlayWanted(
            WantedTile wanted
    ) {
        if (wanted.ring().lodLevel() != EMERGENCY_UNDERLAY_LEVEL) {
            return false;
        }

        if (wanted.predictive()) {
            return true;
        }

        return !wanted.prefetch()
                && wanted.frontierDistanceBlocks()
                <= EMERGENCY_UNDERLAY_OUTER_BLOCKS;
    }

    private static WantedTile firstMissingEmergencyUnderlayCoverage(
            LodTileKey pending
    ) {
        for (WantedTile wanted : wantedTiles) {
            if (wanted.key().equals(pending)
                    || !isEmergencyUnderlayWanted(wanted)) {
                continue;
            }

            if (isTileMissing(wanted.key())) {
                return wanted;
            }
        }

        return null;
    }

    private static WantedTile firstMissingCurrentNearCoverage(
            LodTileKey pending
    ) {
        for (WantedTile wanted : wantedTiles) {
            if ((highSpeedCoverageMode
                            && wanted.ring().lodLevel() <= NEAR_RING_MAX_LEVEL)
                    || wanted.key().equals(pending)
                    || wanted.prefetch()
                    || wanted.ring().lodLevel() > NEAR_RING_MAX_LEVEL) {
                continue;
            }

            if (isTileMissing(wanted.key())) {
                return wanted;
            }
        }

        return null;
    }

    private static WantedTile firstMissingPredictiveCoverage(
            LodTileKey pending
    ) {
        for (WantedTile wanted : wantedTiles) {
            if ((highSpeedCoverageMode
                            && wanted.ring().lodLevel() <= NEAR_RING_MAX_LEVEL)
                    || wanted.key().equals(pending)
                    || !wanted.predictive()
                    || wanted.ring().lodLevel() > NEAR_RING_MAX_LEVEL) {
                continue;
            }

            if (isTileMissing(wanted.key())) {
                return wanted;
            }
        }

        return null;
    }

    private static WantedTile firstMissingNearGuardCoverage(
            LodTileKey pending
    ) {
        for (WantedTile wanted : wantedTiles) {
            if ((highSpeedCoverageMode
                            && wanted.ring().lodLevel() <= NEAR_RING_MAX_LEVEL)
                    || wanted.key().equals(pending)
                    || !wanted.prefetch()
                    || wanted.predictive()
                    || wanted.ring().lodLevel() > NEAR_RING_MAX_LEVEL) {
                continue;
            }

            if (isTileMissing(wanted.key())) {
                return wanted;
            }
        }

        return null;
    }

    private static WantedTile firstMissingCurrentFarCoverage(
            LodTileKey pending
    ) {
        for (WantedTile wanted : wantedTiles) {
            if (wanted.key().equals(pending)
                    || wanted.prefetch()
                    || wanted.ring().lodLevel() <= NEAR_RING_MAX_LEVEL) {
                continue;
            }

            if (isTileMissing(wanted.key())) {
                return wanted;
            }
        }

        return null;
    }

    private static WantedTile firstMissingOtherGuardCoverage(
            LodTileKey pending
    ) {
        for (WantedTile wanted : wantedTiles) {
            if ((highSpeedCoverageMode
                            && wanted.ring().lodLevel() <= NEAR_RING_MAX_LEVEL)
                    || wanted.key().equals(pending)
                    || !wanted.prefetch()
                    || wanted.predictive()) {
                continue;
            }

            if (isTileMissing(wanted.key())) {
                return wanted;
            }
        }

        return null;
    }

    private static WantedTile firstMissingFallbackCoverage(LodTileKey pending) {
        for (WantedTile wanted : wantedTiles) {
            if (wanted.key().equals(pending)
                    || wanted.ring().lodLevel() != 2
                    || wanted.prefetch()) {
                continue;
            }

            if (isTileMissing(wanted.key())) {
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

            if (isTileMissing(wanted.key())) {
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

            if (isTileMissing(wanted.key())) {
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

            if (isTileMissing(wanted.key())) {
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

            if (isTileMissing(wanted.key())) {
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
            if (isTileMissing(wanted.key())) {
                return wanted;
            }
        }

        // Once the visible annuli are covered, spend spare streaming slots on
        // the L1/L2 guard band so the next camera-anchor shift is already warm.
        for (WantedTile wanted : wantedTiles) {
            if (wanted.key().equals(pending) || !wanted.prefetch()) {
                continue;
            }
            if (isTileMissing(wanted.key())) {
                return wanted;
            }
        }

        return null;
    }

    private static WantedTile selectQualityWork(
            LodTileKey pending
    ) {
        // Exact appearance is handled by DETACHED_APPEARANCE_JOBS. The server
        // quality lane stays focused on geometry and remaining refinement.
        WantedTile exactGeometry = firstExactBandRefinement(pending);
        if (exactGeometry != null) {
            return exactGeometry;
        }

        WantedTile intermediate =
                firstIntermediateNearRefinement(pending);
        if (intermediate != null) {
            return intermediate;
        }

        WantedTile focusedFar = firstFocusedFarRefinement(pending);
        if (focusedFar != null) {
            return focusedFar;
        }

        WantedTile farRefinement = firstFarFidelityRefinement(pending);
        if (farRefinement != null) {
            return farRefinement;
        }

        return firstRefinement(pending, false);
    }

    private static WantedTile firstFocusedFarRefinement(
            LodTileKey pending
    ) {
        // Sharpen only the distant terrain in the current forward view. Once a
        // tile reaches this quality it remains cached; repeated 360s therefore
        // progressively sharpen the world instead of globally quadrupling L6.
        for (int level = 6; level >= 5; level--) {
            for (WantedTile wanted : wantedTiles) {
                if (wanted.key().equals(pending)
                        || wanted.prefetch()
                        || !wanted.foreground()
                        || wanted.ring().lodLevel() != level
                        || DETACHED_COVERAGE_JOBS.containsKey(wanted.key())) {
                    continue;
                }

                WorldgenSurfaceTile tile = CACHE.get(wanted.key());
                if (tile != null
                        && tile.sampleSpacing()
                                > wanted.targetSpacing()) {
                    return wanted;
                }
            }
        }

        return null;
    }

    private static WantedTile firstFarFidelityRefinement(
            LodTileKey pending
    ) {
        for (int level = 3; level <= 6; level++) {
            for (WantedTile wanted : wantedTiles) {
                if (wanted.key().equals(pending)
                        || wanted.prefetch()
                        || wanted.ring().lodLevel() != level) {
                    continue;
                }

                WorldgenSurfaceTile tile = CACHE.get(wanted.key());
                if (tile != null
                        && tile.sampleSpacing()
                                > wanted.targetSpacing()) {
                    return wanted;
                }
            }
        }

        return null;
    }

    private static int provisionalExactTileCount() {
        int count = 0;

        for (WantedTile wanted : wantedTiles) {
            if (wanted.prefetch() || wanted.ring().lodLevel() != 1) {
                continue;
            }

            WorldgenSurfaceTile tile = CACHE.get(wanted.key());
            if (tile != null
                    && tile.stage() == WorldgenTileStage.EXACT_GEOMETRY) {
                count++;
            }
        }

        return count;
    }

    private static WantedTile firstExactBandRefinement(
            LodTileKey pending
    ) {
        for (WantedTile wanted : wantedTiles) {
            if (wanted.key().equals(pending)
                    || DETACHED_EXACT_JOBS.containsKey(wanted.key())
                    || wanted.prefetch()
                    || wanted.ring().lodLevel() != 1
                    || !wanted.foreground()
                    || wanted.targetSpacing() != L1_EXACT_SPACING) {
                continue;
            }

            WorldgenSurfaceTile tile = CACHE.get(wanted.key());
            L1SampleGrid grid = L1_SAMPLE_CACHE.get(wanted.key());
            if (tile != null
                    && tile.sampleSpacing() > L1_EXACT_SPACING
                    && grid != null
                    && grid.hasAnyAppearance()
                    && !DETACHED_COVERAGE_JOBS.containsKey(wanted.key())) {
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

    private static WantedTile firstExactAppearanceRefinement(
            LodTileKey pending
    ) {
        for (WantedTile wanted : wantedTiles) {
            if (wanted.key().equals(pending)
                    || wanted.prefetch()
                    || wanted.ring().lodLevel() != 1
                    || wanted.targetSpacing() != L1_EXACT_SPACING) {
                continue;
            }

            WorldgenSurfaceTile tile = CACHE.get(wanted.key());
            L1SampleGrid grid = L1_SAMPLE_CACHE.get(wanted.key());
            if (tile != null
                    && tile.stage() == WorldgenTileStage.EXACT_GEOMETRY
                    && grid != null
                    && grid.requiresAppearanceRefinement) {
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
            if (wanted.key().equals(pending)
                    || DETACHED_EXACT_JOBS.containsKey(wanted.key())) {
                continue;
            }
            if (nearOnly && wanted.ring().lodLevel() > NEAR_RING_MAX_LEVEL) {
                continue;
            }

            WorldgenSurfaceTile tile = CACHE.get(wanted.key());
            int desiredSpacing = wanted.targetSpacing();

            if (tile != null
                    && tile.sampleSpacing() > desiredSpacing) {
                return wanted;
            }
        }

        return null;
    }

    private static void pollDetachedCoverage() {
        Iterator<Map.Entry<LodTileKey, GenerationJob>> iterator =
                DETACHED_COVERAGE_JOBS.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<LodTileKey, GenerationJob> entry = iterator.next();
            GenerationJob job = entry.getValue();

            if (!containsWantedKey(entry.getKey())) {
                if (job.asyncHeightFuture != null) {
                    job.asyncHeightFuture.cancel(true);
                }
                DETACHED_COVERAGE_JOBS.remove(entry.getKey(), job);
                staleJobsCancelled++;
                continue;
            }

            if (job.failed) {
                DETACHED_COVERAGE_JOBS.remove(entry.getKey(), job);
            }
        }
    }

    private static boolean startDetachedCoverage(
            MinecraftServer server,
            ResourceKey<Level> dimension,
            WantedTile wanted
    ) {
        LodTileKey key = wanted.key();
        if (wanted.ring().lodLevel() < 1
                || DETACHED_COVERAGE_JOBS.containsKey(key)) {
            return false;
        }

        WorldgenSurfaceTile existing = CACHE.get(key);
        if (wanted.ring().lodLevel() == 1 && existing != null) {
            // Detached L1 lane is bootstrap-only. Exact refinement belongs to
            // the dedicated exact worker pool once this tile is published.
            return false;
        }
        if (existing != null
                && existing.sampleSpacing()
                        <= wanted.targetSpacing()) {
            return false;
        }

        int spacing = nextGenerationSpacing(wanted, existing);
        L1SampleGrid sampleGrid = null;
        if (wanted.ring().lodLevel() == 1) {
            sampleGrid = L1_SAMPLE_CACHE.computeIfAbsent(
                    key,
                    ignored -> new L1SampleGrid(wanted.ring().tileSize())
            );
            trimL1SampleCache();
        }

        GenerationJob job = new GenerationJob(
                key,
                wanted.ring(),
                spacing,
                existing != null,
                sampleGrid,
                false
        );
        job.asyncCoverageStarted = true;

        if (DETACHED_COVERAGE_JOBS.putIfAbsent(key, job) != null) {
            return false;
        }

        long taskEpoch = epoch;
        server.execute(() -> {
            ServerLevel level = server.getLevel(dimension);
            if (level == null
                    || taskEpoch != epoch
                    || DETACHED_COVERAGE_JOBS.get(key) != job) {
                DETACHED_COVERAGE_JOBS.remove(key, job);
                return;
            }

            try {
                ServerChunkCache chunks = level.getChunkSource();
                var generator = chunks.getGenerator();
                var randomState = chunks.randomState();

                int[] sampleIndices = new int[job.totalSamples];
                for (int i = 0; i < sampleIndices.length; i++) {
                    sampleIndices[i] = i;
                }

                job.asyncMissingSampleIndices = sampleIndices;
                job.asyncStartedNanos = System.nanoTime();
                job.asyncHeightFuture = submitHeightBatch(
                        level,
                        generator,
                        randomState,
                        job,
                        sampleIndices,
                        COVERAGE_HEIGHT_EXECUTOR,
                        1
                );
            } catch (Throwable throwable) {
                job.failed = true;
                EverviewClient.LOGGER.warn(
                        "Everview detached L{} coverage-height job failed at {}, {}",
                        job.ring.lodLevel(),
                        key.tileX(),
                        key.tileZ(),
                        throwable
                );
            }
        });

        return true;
    }

    private static GenerationJob takeReadyDetachedCoverage() {
        // Feed exact L1 first whenever a bootstrap tile's heights are ready.
        for (Map.Entry<LodTileKey, GenerationJob> entry
                : DETACHED_COVERAGE_JOBS.entrySet()) {
            GenerationJob job = entry.getValue();
            CompletableFuture<HeightBatchResult> future =
                    job.asyncHeightFuture;

            if (job.ring.lodLevel() != 1
                    || future == null
                    || !future.isDone()) {
                continue;
            }

            if (DETACHED_COVERAGE_JOBS.remove(entry.getKey(), job)) {
                return job;
            }
        }

        for (Map.Entry<LodTileKey, GenerationJob> entry
                : DETACHED_COVERAGE_JOBS.entrySet()) {
            GenerationJob job = entry.getValue();
            CompletableFuture<HeightBatchResult> future =
                    job.asyncHeightFuture;

            if (future == null || !future.isDone()) {
                continue;
            }

            if (DETACHED_COVERAGE_JOBS.remove(entry.getKey(), job)) {
                return job;
            }
        }

        return null;
    }

    private static int cancelDetachedCoverageJobs() {
        int cancelled = 0;

        for (GenerationJob job : DETACHED_COVERAGE_JOBS.values()) {
            if (job.asyncHeightFuture != null
                    && !job.asyncHeightFuture.isDone()
                    && job.asyncHeightFuture.cancel(true)) {
                cancelled++;
            }
        }

        DETACHED_COVERAGE_JOBS.clear();
        return cancelled;
    }

    private static void pollDetachedAppearance() {
        Iterator<Map.Entry<LodTileKey, CompletableFuture<Void>>> iterator =
                DETACHED_APPEARANCE_JOBS.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<LodTileKey, CompletableFuture<Void>> entry =
                    iterator.next();
            CompletableFuture<Void> future = entry.getValue();

            if (!future.isDone()) {
                continue;
            }

            try {
                future.join();
            } catch (RuntimeException exception) {
                EverviewClient.LOGGER.warn(
                        "Everview detached appearance job failed at {}",
                        entry.getKey(),
                        exception
                );
            }

            DETACHED_APPEARANCE_JOBS.remove(entry.getKey(), future);
        }
    }

    private static boolean startDetachedAppearance(
            MinecraftServer server,
            ResourceKey<Level> dimension,
            WantedTile wanted
    ) {
        LodTileKey key = wanted.key();
        if (DETACHED_APPEARANCE_JOBS.containsKey(key)
                || DETACHED_EXACT_JOBS.containsKey(key)) {
            return false;
        }

        WorldgenSurfaceTile existing = CACHE.get(key);
        L1SampleGrid grid = L1_SAMPLE_CACHE.get(key);
        if (existing == null
                || existing.stage() != WorldgenTileStage.EXACT_GEOMETRY
                || grid == null
                || !grid.requiresAppearanceRefinement) {
            return false;
        }

        CompletableFuture<Void> launcher = new CompletableFuture<>();
        if (DETACHED_APPEARANCE_JOBS.putIfAbsent(key, launcher) != null) {
            return false;
        }

        long taskEpoch = epoch;
        long started = System.nanoTime();

        server.execute(() -> {
            ServerLevel level = server.getLevel(dimension);
            if (level == null
                    || taskEpoch != epoch
                    || DETACHED_APPEARANCE_JOBS.get(key) != launcher) {
                DETACHED_APPEARANCE_JOBS.remove(key, launcher);
                launcher.complete(null);
                return;
            }

            try {
                GenerationJob job = new GenerationJob(
                        key,
                        wanted.ring(),
                        L1_EXACT_SPACING,
                        true,
                        grid,
                        true
                );

                @SuppressWarnings("unchecked")
                Holder<Biome>[] biomes =
                        (Holder<Biome>[]) new Holder<?>[job.totalSamples];

                for (int sampleIndex = 0;
                        sampleIndex < job.totalSamples;
                        sampleIndex++) {
                    int gx = sampleIndex % job.samplesAcross;
                    int gz = sampleIndex / job.samplesAcross;
                    int fineIndex = job.fineGridIndex(gx, gz);

                    if (fineIndex < 0
                            || !grid.heightSampled[fineIndex]) {
                        throw new IllegalStateException(
                                "Exact appearance missing L1 height anchor"
                        );
                    }

                    int y = grid.heights[fineIndex];
                    job.heights[sampleIndex] = y;
                    job.minY = Math.min(job.minY, y);
                    job.maxY = Math.max(job.maxY, y);
                    job.reusedSamples++;

                    if (!grid.appearanceSampled[fineIndex]) {
                        int worldX = job.originX + gx;
                        int worldZ = job.originZ + gz;
                        biomes[sampleIndex] = sampleBiomeCached(
                                level,
                                worldX,
                                y,
                                worldZ
                        );
                    }
                }

                int seaLevel = level.getSeaLevel();
                CompletableFuture.runAsync(
                        () -> finishDetachedAppearance(
                                job,
                                biomes,
                                seaLevel,
                                taskEpoch,
                                started
                        ),
                        APPEARANCE_EXECUTOR
                ).whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        launcher.completeExceptionally(throwable);
                    } else {
                        launcher.complete(null);
                    }
                });
            } catch (Throwable throwable) {
                launcher.completeExceptionally(throwable);
            }
        });

        return true;
    }

    private static void finishDetachedAppearance(
            GenerationJob job,
            Holder<Biome>[] biomes,
            int seaLevel,
            long taskEpoch,
            long started
    ) {
        if (taskEpoch != epoch
                || !DETACHED_APPEARANCE_JOBS.containsKey(job.key)) {
            return;
        }

        for (int sampleIndex = 0;
                sampleIndex < job.totalSamples;
                sampleIndex++) {
            int gx = sampleIndex % job.samplesAcross;
            int gz = sampleIndex / job.samplesAcross;
            int fineIndex = job.fineGridIndex(gx, gz);
            int worldX = job.originX + gx;
            int worldZ = job.originZ + gz;
            int y = job.heights[sampleIndex];

            if (fineIndex >= 0
                    && job.sampleGrid.appearanceSampled[fineIndex]) {
                job.sampleColors[sampleIndex] =
                        job.sampleGrid.colors[fineIndex];
                job.sampleMaterials[sampleIndex] =
                        job.sampleGrid.materials[fineIndex];
                continue;
            }

            Holder<Biome> biome = biomes[sampleIndex];
            if (biome == null) {
                throw new IllegalStateException(
                        "Exact appearance missing biome snapshot"
                );
            }

            var appearance = MinecraftSurfacePalette.sample(
                    biome,
                    worldX,
                    y,
                    worldZ,
                    seaLevel
            );

            job.sampleColors[sampleIndex] = appearance.rgb();
            job.sampleMaterials[sampleIndex] = appearance.material();

            if (fineIndex >= 0) {
                job.sampleGrid.colors[fineIndex] = appearance.rgb();
                job.sampleGrid.materials[fineIndex] =
                        appearance.material();
                job.sampleGrid.appearanceSampled[fineIndex] = true;
            }

            job.appearanceGeneratedSamples++;
        }

        job.sampleGrid.requiresAppearanceRefinement = false;
        job.nextSample = job.totalSamples;

        MeshData mesh = buildMesh(job, seaLevel);
        job.accumulatedNanos = System.nanoTime() - started;

        WorldgenSurfaceTile tile = new WorldgenSurfaceTile(
                job.ring.lodLevel(),
                job.key.tileX(),
                job.key.tileZ(),
                job.ring.tileSize(),
                job.sampleSpacing,
                WorldgenTileStage.EXACT_APPEARANCE,
                mesh.vertices(),
                mesh.colors(),
                mesh.materials(),
                mesh.quadCount(),
                job.minY,
                job.maxY,
                seaLevel,
                job.accumulatedNanos
        );

        COMPLETED.add(new CompletedTile(
                taskEpoch,
                job.key,
                tile,
                job.reusedSamples,
                0,
                job.appearanceGeneratedSamples,
                0
        ));
    }

    private static int cancelDetachedAppearanceJobs() {
        int cancelled = 0;

        for (CompletableFuture<Void> future
                : DETACHED_APPEARANCE_JOBS.values()) {
            if (!future.isDone() && future.cancel(true)) {
                cancelled++;
            }
        }

        DETACHED_APPEARANCE_JOBS.clear();
        return cancelled;
    }

    private static void pollDetachedExact() {
        Iterator<Map.Entry<LodTileKey, CompletableFuture<Void>>> iterator =
                DETACHED_EXACT_JOBS.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<LodTileKey, CompletableFuture<Void>> entry =
                    iterator.next();
            CompletableFuture<Void> future = entry.getValue();

            if (!future.isDone()) {
                continue;
            }

            try {
                future.join();
            } catch (RuntimeException exception) {
                EverviewClient.LOGGER.warn(
                        "Everview detached exact-height job failed at {}",
                        entry.getKey(),
                        exception
                );
            }

            DETACHED_EXACT_JOBS.remove(entry.getKey(), future);
        }
    }

    private static int cancelDetachedExactJobs() {
        int cancelled = 0;

        for (CompletableFuture<Void> future
                : DETACHED_EXACT_JOBS.values()) {
            if (!future.isDone() && future.cancel(true)) {
                cancelled++;
            }
        }

        DETACHED_EXACT_JOBS.clear();
        return cancelled;
    }

    private static boolean startDetachedExact(
            MinecraftServer server,
            ResourceKey<Level> dimension,
            WantedTile wanted
    ) {
        LodTileKey key = wanted.key();
        if (DETACHED_EXACT_JOBS.containsKey(key)) {
            return false;
        }

        WorldgenSurfaceTile existing = CACHE.get(key);
        if (existing == null
                || existing.sampleSpacing() <= L1_EXACT_SPACING) {
            return false;
        }

        L1SampleGrid grid = L1_SAMPLE_CACHE.get(key);
        if (grid == null || !grid.hasAnyAppearance()) {
            return false;
        }

        GenerationJob job = new GenerationJob(
                key,
                wanted.ring(),
                L1_EXACT_SPACING,
                true,
                grid,
                false
        );

        long taskEpoch = epoch;
        CompletableFuture<Void> launcher = new CompletableFuture<>();
        if (DETACHED_EXACT_JOBS.putIfAbsent(key, launcher) != null) {
            return false;
        }

        server.execute(() -> {
            ServerLevel level = server.getLevel(dimension);
            if (level == null
                    || taskEpoch != epoch
                    || !DETACHED_EXACT_JOBS.containsKey(key)) {
                launcher.complete(null);
                return;
            }

            try {
                launchDetachedExactWorker(
                        level,
                        job,
                        taskEpoch,
                        launcher
                );
            } catch (Throwable throwable) {
                launcher.completeExceptionally(throwable);
            }
        });

        return true;
    }

    private static void launchDetachedExactWorker(
            ServerLevel level,
            GenerationJob job,
            long taskEpoch,
            CompletableFuture<Void> launcher
    ) {
        ServerChunkCache chunks = level.getChunkSource();
        var generator = chunks.getGenerator();
        var randomState = chunks.randomState();

        int[] missingScratch = new int[job.totalSamples];
        int missingCount = 0;
        for (int sampleIndex = 0;
                sampleIndex < job.totalSamples;
                sampleIndex++) {
            int gx = sampleIndex % job.samplesAcross;
            int gz = sampleIndex / job.samplesAcross;
            int fineIndex = job.fineGridIndex(gx, gz);

            if (fineIndex >= 0
                    && job.sampleGrid.heightSampled[fineIndex]) {
                int y = job.sampleGrid.heights[fineIndex];
                job.heights[sampleIndex] = y;
                job.minY = Math.min(job.minY, y);
                job.maxY = Math.max(job.maxY, y);
                job.reusedSamples++;
            } else {
                missingScratch[missingCount++] = sampleIndex;
            }
        }

        int[] missingIndices = Arrays.copyOf(
                missingScratch,
                missingCount
        );
        long started = System.nanoTime();

        int workersForTile = exactWorkersPerTile();
        submitHeightBatch(
                level,
                generator,
                randomState,
                job,
                missingIndices,
                EXACT_HEIGHT_EXECUTOR,
                workersForTile
        ).thenAcceptAsync(
                result -> finishDetachedExact(
                        level,
                        job,
                        taskEpoch,
                        started,
                        missingIndices.length,
                        result
                ),
                EXACT_HEIGHT_EXECUTOR
        ).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                launcher.completeExceptionally(throwable);
            } else {
                launcher.complete(null);
            }
        });
    }

    private static void finishDetachedExact(
            ServerLevel level,
            GenerationJob job,
            long taskEpoch,
            long started,
            int expectedMissing,
            HeightBatchResult result
    ) {
        if (taskEpoch != epoch
                || !DETACHED_EXACT_JOBS.containsKey(job.key)
                || result.sampleIndices().length != expectedMissing) {
            return;
        }

        for (int i = 0; i < result.sampleIndices().length; i++) {
            int sampleIndex = result.sampleIndices()[i];
            int y = result.heights()[i];
            int gx = sampleIndex % job.samplesAcross;
            int gz = sampleIndex / job.samplesAcross;
            int fineIndex = job.fineGridIndex(gx, gz);

            job.heights[sampleIndex] = y;
            if (fineIndex >= 0) {
                job.sampleGrid.heights[fineIndex] = y;
                job.sampleGrid.heightSampled[fineIndex] = true;
            }
            job.minY = Math.min(job.minY, y);
            job.maxY = Math.max(job.maxY, y);
            job.generatedSamples++;
        }

        for (int sampleIndex = 0;
                sampleIndex < job.totalSamples;
                sampleIndex++) {
            int gx = sampleIndex % job.samplesAcross;
            int gz = sampleIndex / job.samplesAcross;
            int fineIndex = job.fineGridIndex(gx, gz);

            if (fineIndex >= 0
                    && job.sampleGrid.appearanceSampled[fineIndex]) {
                job.sampleColors[sampleIndex] =
                        job.sampleGrid.colors[fineIndex];
                job.sampleMaterials[sampleIndex] =
                        job.sampleGrid.materials[fineIndex];
                continue;
            }

            int borrowed =
                    job.sampleGrid.nearestAppearanceIndex(gx, gz);
            if (borrowed < 0) {
                throw new IllegalStateException(
                        "Exact L1 tile has no bootstrap appearance anchor"
                );
            }

            job.sampleColors[sampleIndex] =
                    job.sampleGrid.colors[borrowed];
            job.sampleMaterials[sampleIndex] =
                    job.sampleGrid.materials[borrowed];
            job.provisionalAppearanceSamples++;
        }

        job.sampleGrid.requiresAppearanceRefinement =
                job.provisionalAppearanceSamples > 0;
        job.nextSample = job.totalSamples;

        MeshData mesh = buildMesh(job, level.getSeaLevel());
        job.accumulatedNanos = System.nanoTime() - started;

        WorldgenSurfaceTile tile = new WorldgenSurfaceTile(
                job.ring.lodLevel(),
                job.key.tileX(),
                job.key.tileZ(),
                job.ring.tileSize(),
                job.sampleSpacing,
                job.sampleGrid.requiresAppearanceRefinement
                        ? WorldgenTileStage.EXACT_GEOMETRY
                        : WorldgenTileStage.EXACT_APPEARANCE,
                mesh.vertices(),
                mesh.colors(),
                mesh.materials(),
                mesh.quadCount(),
                job.minY,
                job.maxY,
                level.getSeaLevel(),
                job.accumulatedNanos
        );

        COMPLETED.add(new CompletedTile(
                taskEpoch,
                job.key,
                tile,
                job.reusedSamples,
                job.generatedSamples,
                0,
                job.provisionalAppearanceSamples
        ));
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

        // M6.4: all L2-L6 height coverage uses the dedicated worker pool, not
        // just emergency high-speed coverage. getBaseHeight was the dominant
        // reason a settled world could take 10+ minutes. Appearance and mesh
        // assembly stay on the server lane for now.
        if ((job.ring.lodLevel() >= 2
                        && job.sampleGrid == null)
                || job.asyncCoverageStarted) {
            runAsyncCoverageGeometry(
                    level,
                    generator,
                    randomState,
                    job,
                    taskEpoch,
                    sliceStart
            );
            return;
        }

        if (job.exactGeometryOnly
                && !job.asyncExactDisabled
                && job.sampleGrid != null
                && job.sampleGrid.hasAnyAppearance()) {
            runAsyncExactGeometry(
                    level,
                    generator,
                    randomState,
                    job,
                    taskEpoch,
                    sliceStart
            );
            return;
        }

        int processed = 0;
        long sliceBudgetNanos = adaptiveSliceBudgetNanos;

        while (job.nextSample < job.totalSamples
                && processed < MAX_SAMPLES_PER_SLICE) {
            int sampleIndex = job.nextSample;
            int gx = sampleIndex % job.samplesAcross;
            int gz = sampleIndex / job.samplesAcross;
            int worldX = job.originX + gx * job.sampleSpacing;
            int worldZ = job.originZ + gz * job.sampleSpacing;
            int fineIndex = job.fineGridIndex(gx, gz);

            if (job.sampleGrid == null || fineIndex < 0) {
                int y = sampleHeightCached(
                        level,
                        generator,
                        randomState,
                        worldX,
                        worldZ,
                        job.sampleSpacing
                );

                var biome = sampleBiomeCached(
                                level,
                                worldX,
                                y,
                                worldZ
                        );
                var appearance = MinecraftSurfacePalette.sample(
                        biome,
                        worldX,
                        y,
                        worldZ,
                        level.getSeaLevel()
                );

                job.heights[sampleIndex] = y;
                job.sampleColors[sampleIndex] = appearance.rgb();
                job.sampleMaterials[sampleIndex] = appearance.material();
                job.generatedSamples++;
                job.appearanceGeneratedSamples++;
                processed++;
            } else {
                L1SampleGrid grid = job.sampleGrid;
                int fineX = gx * job.sampleSpacing;
                int fineZ = gz * job.sampleSpacing;

                int y;
                if (grid.heightSampled[fineIndex]) {
                    y = grid.heights[fineIndex];
                    job.reusedSamples++;
                } else {
                    y = sampleHeightCached(
                        level,
                        generator,
                        randomState,
                        worldX,
                        worldZ,
                        job.sampleSpacing
                );
                    grid.heights[fineIndex] = y;
                    grid.heightSampled[fineIndex] = true;
                    job.generatedSamples++;
                    processed++;
                }
                job.heights[sampleIndex] = y;

                if (grid.appearanceSampled[fineIndex]) {
                    job.sampleColors[sampleIndex] = grid.colors[fineIndex];
                    job.sampleMaterials[sampleIndex] = grid.materials[fineIndex];
                } else if (job.exactGeometryOnly) {
                    int borrowed = grid.nearestAppearanceIndex(fineX, fineZ);
                    if (borrowed >= 0) {
                        job.sampleColors[sampleIndex] = grid.colors[borrowed];
                        job.sampleMaterials[sampleIndex] = grid.materials[borrowed];
                        grid.requiresAppearanceRefinement = true;
                        job.provisionalAppearanceSamples++;
                    } else {
                        var biome = sampleBiomeCached(
                                level,
                                worldX,
                                y,
                                worldZ
                        );
                        var appearance = MinecraftSurfacePalette.sample(
                                biome,
                                worldX,
                                y,
                                worldZ,
                                level.getSeaLevel()
                        );
                        grid.colors[fineIndex] = appearance.rgb();
                        grid.materials[fineIndex] = appearance.material();
                        grid.appearanceSampled[fineIndex] = true;
                        job.sampleColors[sampleIndex] = appearance.rgb();
                        job.sampleMaterials[sampleIndex] = appearance.material();
                        job.appearanceGeneratedSamples++;
                        processed++;
                    }
                } else {
                    var biome = sampleBiomeCached(
                                level,
                                worldX,
                                y,
                                worldZ
                        );
                    var appearance = MinecraftSurfacePalette.sample(
                            biome,
                            worldX,
                            y,
                            worldZ,
                            level.getSeaLevel()
                    );
                    grid.colors[fineIndex] = appearance.rgb();
                    grid.materials[fineIndex] = appearance.material();
                    grid.appearanceSampled[fineIndex] = true;
                    job.sampleColors[sampleIndex] = appearance.rgb();
                    job.sampleMaterials[sampleIndex] = appearance.material();
                    job.appearanceGeneratedSamples++;
                    processed++;
                }
            }

            int y = job.heights[sampleIndex];
            job.minY = Math.min(job.minY, y);
            job.maxY = Math.max(job.maxY, y);
            job.nextSample = sampleIndex + 1;

            if (System.nanoTime() - sliceStart >= sliceBudgetNanos) {
                break;
            }
        }

        long samplingElapsed = System.nanoTime() - sliceStart;
        job.accumulatedNanos += samplingElapsed;
        long sliceElapsed = samplingElapsed;

        if (job.nextSample >= job.totalSamples && taskEpoch == epoch) {
            if (job.appearanceOnly && job.sampleGrid != null) {
                job.sampleGrid.requiresAppearanceRefinement = false;
            }

            long meshStart = System.nanoTime();
            MeshData mesh = buildMesh(job, level.getSeaLevel());
            long meshElapsed = System.nanoTime() - meshStart;

            job.accumulatedNanos += meshElapsed;
            sliceElapsed += meshElapsed;

            WorldgenTileStage tileStage = WorldgenTileStage.COVERAGE;
            if (job.ring.lodLevel() == 1
                    && job.sampleSpacing == L1_EXACT_SPACING) {
                tileStage = job.sampleGrid != null
                        && job.sampleGrid.requiresAppearanceRefinement
                        ? WorldgenTileStage.EXACT_GEOMETRY
                        : WorldgenTileStage.EXACT_APPEARANCE;
            }

            WorldgenSurfaceTile tile = new WorldgenSurfaceTile(
                    job.ring.lodLevel(),
                    job.key.tileX(),
                    job.key.tileZ(),
                    job.ring.tileSize(),
                    job.sampleSpacing,
                    tileStage,
                    mesh.vertices(),
                    mesh.colors(),
                    mesh.materials(),
                    mesh.quadCount(),
                    job.minY,
                    job.maxY,
                    level.getSeaLevel(),
                    job.accumulatedNanos
            );

            COMPLETED.add(new CompletedTile(
                    taskEpoch,
                    job.key,
                    tile,
                    job.reusedSamples,
                    job.generatedSamples,
                    job.appearanceGeneratedSamples,
                    job.provisionalAppearanceSamples
            ));
        }

        lastSliceSamples = processed;
        lastSliceMs = sliceElapsed / 1_000_000.0;
    }

    private static void runAsyncCoverageGeometry(
            ServerLevel level,
            net.minecraft.world.level.chunk.ChunkGenerator generator,
            net.minecraft.world.level.levelgen.RandomState randomState,
            GenerationJob job,
            long taskEpoch,
            long sliceStart
    ) {
        if (job.asyncHeightFuture == null) {
            int[] sampleIndices = new int[job.totalSamples];
            for (int i = 0; i < sampleIndices.length; i++) {
                sampleIndices[i] = i;
            }

            job.asyncCoverageStarted = true;
            job.asyncMissingSampleIndices = sampleIndices;
            job.asyncStartedNanos = System.nanoTime();

            job.asyncHeightFuture = submitHeightBatch(
                    level,
                    generator,
                    randomState,
                    job,
                    sampleIndices,
                    COVERAGE_HEIGHT_EXECUTOR,
                    COVERAGE_HEIGHT_WORKERS
            );

            lastSliceSamples = 0;
            lastSliceMs = (System.nanoTime() - sliceStart) / 1_000_000.0;
            return;
        }

        if (!job.asyncHeightFuture.isDone()) {
            lastSliceSamples = 0;
            lastSliceMs = 0.0;
            return;
        }

        HeightBatchResult result;
        try {
            result = job.asyncHeightFuture.join();
        } catch (RuntimeException exception) {
            // Fall back to the proven server-thread path if a world generator
            // rejects asynchronous height access.
            job.asyncCoverageStarted = false;
            job.asyncHeightFuture = null;
            job.asyncMissingSampleIndices = new int[0];
            job.nextSample = 0;
            EverviewClient.LOGGER.warn(
                    "Everview async coverage heights failed at L{} {}, {}; "
                            + "falling back to server-thread coverage",
                    job.ring.lodLevel(),
                    job.key.tileX(),
                    job.key.tileZ(),
                    exception
            );
            return;
        }

        if (result.sampleIndices().length != job.totalSamples) {
            job.asyncCoverageStarted = false;
            job.asyncHeightFuture = null;
            job.asyncMissingSampleIndices = new int[0];
            job.nextSample = 0;
            return;
        }

        for (int i = 0; i < result.sampleIndices().length; i++) {
            int sampleIndex = result.sampleIndices()[i];
            int y = result.heights()[i];
            job.heights[sampleIndex] = y;

            if (job.sampleGrid != null) {
                int gx = sampleIndex % job.samplesAcross;
                int gz = sampleIndex / job.samplesAcross;
                int fineIndex = job.fineGridIndex(gx, gz);
                if (fineIndex >= 0) {
                    job.sampleGrid.heights[fineIndex] = y;
                    job.sampleGrid.heightSampled[fineIndex] = true;
                }
            }

            job.minY = Math.min(job.minY, y);
            job.maxY = Math.max(job.maxY, y);
            job.generatedSamples++;
        }

        // Appearance stays on the server lane. With cached biome classification
        // this is much cheaper than height generation and keeps thread-sensitive
        // biome color/material work out of the worker pool.
        for (int sampleIndex = 0;
                sampleIndex < job.totalSamples;
                sampleIndex++) {
            int gx = sampleIndex % job.samplesAcross;
            int gz = sampleIndex / job.samplesAcross;
            int worldX = job.originX + gx * job.sampleSpacing;
            int worldZ = job.originZ + gz * job.sampleSpacing;
            int y = job.heights[sampleIndex];

            var biome = sampleBiomeCached(
                                level,
                                worldX,
                                y,
                                worldZ
                        );
            var appearance = MinecraftSurfacePalette.sample(
                    biome,
                    worldX,
                    y,
                    worldZ,
                    level.getSeaLevel()
            );

            job.sampleColors[sampleIndex] = appearance.rgb();
            job.sampleMaterials[sampleIndex] = appearance.material();

            if (job.sampleGrid != null) {
                int fineIndex = job.fineGridIndex(gx, gz);
                if (fineIndex >= 0) {
                    job.sampleGrid.colors[fineIndex] = appearance.rgb();
                    job.sampleGrid.materials[fineIndex] =
                            appearance.material();
                    job.sampleGrid.appearanceSampled[fineIndex] = true;
                }
            }

            job.appearanceGeneratedSamples++;
        }

        job.nextSample = job.totalSamples;
        job.accumulatedNanos += System.nanoTime() - job.asyncStartedNanos;

        long meshStart = System.nanoTime();
        MeshData mesh = buildMesh(job, level.getSeaLevel());
        job.accumulatedNanos += System.nanoTime() - meshStart;

        WorldgenSurfaceTile tile = new WorldgenSurfaceTile(
                job.ring.lodLevel(),
                job.key.tileX(),
                job.key.tileZ(),
                job.ring.tileSize(),
                job.sampleSpacing,
                WorldgenTileStage.COVERAGE,
                mesh.vertices(),
                mesh.colors(),
                mesh.materials(),
                mesh.quadCount(),
                job.minY,
                job.maxY,
                level.getSeaLevel(),
                job.accumulatedNanos
        );

        COMPLETED.add(new CompletedTile(
                taskEpoch,
                job.key,
                tile,
                job.reusedSamples,
                job.generatedSamples,
                job.appearanceGeneratedSamples,
                job.provisionalAppearanceSamples
        ));

        lastSliceSamples = result.sampleIndices().length;
        lastSliceMs = (System.nanoTime() - sliceStart) / 1_000_000.0;
    }

    private static void runAsyncExactGeometry(
            ServerLevel level,
            net.minecraft.world.level.chunk.ChunkGenerator generator,
            net.minecraft.world.level.levelgen.RandomState randomState,
            GenerationJob job,
            long taskEpoch,
            long sliceStart
    ) {
        if (job.asyncHeightFuture == null) {
            int[] missingScratch = new int[job.totalSamples];
            int missingCount = 0;

            for (int sampleIndex = 0;
                    sampleIndex < job.totalSamples;
                    sampleIndex++) {
                int gx = sampleIndex % job.samplesAcross;
                int gz = sampleIndex / job.samplesAcross;
                int fineIndex = job.fineGridIndex(gx, gz);

                if (fineIndex >= 0
                        && job.sampleGrid.heightSampled[fineIndex]) {
                    int y = job.sampleGrid.heights[fineIndex];
                    job.heights[sampleIndex] = y;
                    job.minY = Math.min(job.minY, y);
                    job.maxY = Math.max(job.maxY, y);
                    job.reusedSamples++;
                } else {
                    missingScratch[missingCount++] = sampleIndex;
                }
            }

            int[] missingIndices = Arrays.copyOf(
                    missingScratch,
                    missingCount
            );
            job.asyncMissingSampleIndices = missingIndices;
            job.asyncStartedNanos = System.nanoTime();

            job.asyncHeightFuture = submitHeightBatch(
                    level,
                    generator,
                    randomState,
                    job,
                    missingIndices,
                    EXACT_HEIGHT_EXECUTOR,
                    exactWorkersPerTile()
            );

            lastSliceSamples = 0;
            lastSliceMs = (System.nanoTime() - sliceStart) / 1_000_000.0;
            return;
        }

        if (!job.asyncHeightFuture.isDone()) {
            lastSliceSamples = 0;
            lastSliceMs = 0.0;
            return;
        }

        HeightBatchResult result;
        try {
            result = job.asyncHeightFuture.join();
        } catch (RuntimeException exception) {
            job.asyncExactDisabled = true;
            job.asyncHeightFuture = null;
            job.nextSample = 0;
            EverviewClient.LOGGER.warn(
                    "Everview async exact-height batch failed at {}, {}; "
                            + "falling back to server-thread sampling",
                    job.key.tileX(),
                    job.key.tileZ(),
                    exception
            );
            return;
        }

        if (result.sampleIndices().length
                != job.asyncMissingSampleIndices.length) {
            job.asyncExactDisabled = true;
            job.asyncHeightFuture = null;
            job.nextSample = 0;
            EverviewClient.LOGGER.warn(
                    "Everview async exact-height batch was incomplete at {}, {}; "
                            + "falling back to server-thread sampling",
                    job.key.tileX(),
                    job.key.tileZ()
            );
            return;
        }

        if (taskEpoch != epoch || job != currentJob) {
            return;
        }

        for (int i = 0; i < result.sampleIndices().length; i++) {
            int sampleIndex = result.sampleIndices()[i];
            int y = result.heights()[i];
            int gx = sampleIndex % job.samplesAcross;
            int gz = sampleIndex / job.samplesAcross;
            int fineIndex = job.fineGridIndex(gx, gz);

            job.heights[sampleIndex] = y;
            if (fineIndex >= 0) {
                job.sampleGrid.heights[fineIndex] = y;
                job.sampleGrid.heightSampled[fineIndex] = true;
            }
            job.minY = Math.min(job.minY, y);
            job.maxY = Math.max(job.maxY, y);
            job.generatedSamples++;
        }

        // Fill temporary/exact appearance only after all heights are ready.
        // This loop performs no height worldgen calls.
        for (int sampleIndex = 0;
                sampleIndex < job.totalSamples;
                sampleIndex++) {
            int gx = sampleIndex % job.samplesAcross;
            int gz = sampleIndex / job.samplesAcross;
            int worldX = job.originX + gx * job.sampleSpacing;
            int worldZ = job.originZ + gz * job.sampleSpacing;
            int fineIndex = job.fineGridIndex(gx, gz);

            if (fineIndex >= 0
                    && job.sampleGrid.appearanceSampled[fineIndex]) {
                job.sampleColors[sampleIndex] =
                        job.sampleGrid.colors[fineIndex];
                job.sampleMaterials[sampleIndex] =
                        job.sampleGrid.materials[fineIndex];
                continue;
            }

            int borrowed = job.sampleGrid.nearestAppearanceIndex(gx, gz);
            if (borrowed >= 0) {
                job.sampleColors[sampleIndex] =
                        job.sampleGrid.colors[borrowed];
                job.sampleMaterials[sampleIndex] =
                        job.sampleGrid.materials[borrowed];
                job.sampleGrid.requiresAppearanceRefinement = true;
                job.provisionalAppearanceSamples++;
                continue;
            }

            int y = job.heights[sampleIndex];
            var biome = sampleBiomeCached(
                                level,
                                worldX,
                                y,
                                worldZ
                        );
            var appearance = MinecraftSurfacePalette.sample(
                    biome,
                    worldX,
                    y,
                    worldZ,
                    level.getSeaLevel()
            );
            job.sampleColors[sampleIndex] = appearance.rgb();
            job.sampleMaterials[sampleIndex] = appearance.material();
            if (fineIndex >= 0) {
                job.sampleGrid.colors[fineIndex] = appearance.rgb();
                job.sampleGrid.materials[fineIndex] =
                        appearance.material();
                job.sampleGrid.appearanceSampled[fineIndex] = true;
            }
            job.appearanceGeneratedSamples++;
        }

        job.nextSample = job.totalSamples;
        job.accumulatedNanos += System.nanoTime() - job.asyncStartedNanos;

        long meshStart = System.nanoTime();
        MeshData mesh = buildMesh(job, level.getSeaLevel());
        job.accumulatedNanos += System.nanoTime() - meshStart;

        WorldgenTileStage tileStage =
                job.sampleGrid.requiresAppearanceRefinement
                        ? WorldgenTileStage.EXACT_GEOMETRY
                        : WorldgenTileStage.EXACT_APPEARANCE;

        WorldgenSurfaceTile tile = new WorldgenSurfaceTile(
                job.ring.lodLevel(),
                job.key.tileX(),
                job.key.tileZ(),
                job.ring.tileSize(),
                job.sampleSpacing,
                tileStage,
                mesh.vertices(),
                mesh.colors(),
                mesh.materials(),
                mesh.quadCount(),
                job.minY,
                job.maxY,
                level.getSeaLevel(),
                job.accumulatedNanos
        );

        COMPLETED.add(new CompletedTile(
                taskEpoch,
                job.key,
                tile,
                job.reusedSamples,
                job.generatedSamples,
                job.appearanceGeneratedSamples,
                job.provisionalAppearanceSamples
        ));

        lastSliceSamples = result.sampleIndices().length;
        lastSliceMs = (System.nanoTime() - sliceStart) / 1_000_000.0;
    }

    private static CompletableFuture<HeightBatchResult> submitHeightBatch(
            ServerLevel level,
            net.minecraft.world.level.chunk.ChunkGenerator generator,
            net.minecraft.world.level.levelgen.RandomState randomState,
            GenerationJob job,
            int[] sampleIndices,
            ExecutorService executor,
            int requestedWorkers
    ) {
        int workers = Math.max(
                1,
                Math.min(requestedWorkers, sampleIndices.length)
        );
        List<CompletableFuture<HeightPart>> futures =
                new ArrayList<>(workers);

        for (int worker = 0; worker < workers; worker++) {
            int start = sampleIndices.length * worker / workers;
            int end = sampleIndices.length * (worker + 1) / workers;

            futures.add(CompletableFuture.supplyAsync(
                    () -> computeHeightPart(
                            level,
                            generator,
                            randomState,
                            job,
                            sampleIndices,
                            start,
                            end
                    ),
                    executor
            ));
        }

        CompletableFuture<?>[] all =
                futures.toArray(new CompletableFuture<?>[0]);

        return CompletableFuture.allOf(all).thenApply(ignored -> {
            int total = 0;
            HeightPart[] parts = new HeightPart[futures.size()];

            for (int i = 0; i < futures.size(); i++) {
                parts[i] = futures.get(i).join();
                total += parts[i].heights().length;
            }

            int[] indices = new int[total];
            int[] heights = new int[total];
            int offset = 0;

            for (HeightPart part : parts) {
                int count = part.heights().length;
                for (int i = 0; i < count; i++) {
                    indices[offset + i] =
                            sampleIndices[part.startOffset() + i];
                }
                System.arraycopy(
                        part.heights(),
                        0,
                        heights,
                        offset,
                        count
                );
                offset += count;
            }

            return new HeightBatchResult(indices, heights);
        });
    }

    private static Holder<Biome> sampleBiomeCached(
            ServerLevel level,
            int worldX,
            int worldY,
            int worldZ
    ) {
        int quartX = worldX >> 2;
        int quartY = worldY >> 2;
        int quartZ = worldZ >> 2;
        long key = packQuartBiome(quartX, quartY, quartZ);

        Holder<Biome> cached = SHARED_BIOME_CACHE.get(key);
        if (cached != null) {
            SHARED_BIOME_HITS.incrementAndGet();
            return cached;
        }

        Holder<Biome> biome = level.getNoiseBiome(
                quartX,
                quartY,
                quartZ
        );
        SHARED_BIOME_MISSES.incrementAndGet();

        if (SHARED_BIOME_CACHE.size() < SHARED_BIOME_CACHE_LIMIT) {
            Holder<Biome> raced =
                    SHARED_BIOME_CACHE.putIfAbsent(key, biome);
            if (raced != null) {
                return raced;
            }
        }

        return biome;
    }

    private static long packQuartBiome(int x, int y, int z) {
        return ((long) (x & 0x3FF_FFFF) << 38)
                | ((long) (z & 0x3FF_FFFF) << 12)
                | (long) (y & 0xFFF);
    }

    private static int sampleHeightCached(
            ServerLevel level,
            net.minecraft.world.level.chunk.ChunkGenerator generator,
            net.minecraft.world.level.levelgen.RandomState randomState,
            int worldX,
            int worldZ,
            int sampleSpacing
    ) {
        boolean reusableColumn = sampleSpacing > 1
                || (((worldX & 1) == 0) && ((worldZ & 1) == 0));
        long key = packWorldColumn(worldX, worldZ);

        if (reusableColumn) {
            Integer cached = SHARED_HEIGHT_CACHE.get(key);
            if (cached != null) {
                SHARED_HEIGHT_HITS.incrementAndGet();
                return cached;
            }
        }

        int y = generator.getBaseHeight(
                worldX,
                worldZ,
                Heightmap.Types.WORLD_SURFACE_WG,
                level,
                randomState
        );
        y = Math.max(level.getMinY(), Math.min(level.getMaxY(), y));
        if (reusableColumn) {
            SHARED_HEIGHT_MISSES.incrementAndGet();

            if (SHARED_HEIGHT_CACHE.size() < SHARED_HEIGHT_CACHE_LIMIT) {
                Integer raced = SHARED_HEIGHT_CACHE.putIfAbsent(key, y);
                if (raced != null) {
                    return raced;
                }
            }
        }

        return y;
    }

    private static long packWorldColumn(int worldX, int worldZ) {
        return ((long) worldX << 32)
                ^ (worldZ & 0xFFFF_FFFFL);
    }

    private static HeightPart computeHeightPart(
            ServerLevel level,
            net.minecraft.world.level.chunk.ChunkGenerator generator,
            net.minecraft.world.level.levelgen.RandomState randomState,
            GenerationJob job,
            int[] sampleIndices,
            int start,
            int end
    ) {
        int count = Math.max(0, end - start);
        int[] heights = new int[count];

        for (int i = 0; i < count; i++) {
            if (Thread.currentThread().isInterrupted()) {
                return new HeightPart(
                        start,
                        Arrays.copyOf(heights, i)
                );
            }

            int sampleIndex = sampleIndices[start + i];
            int gx = sampleIndex % job.samplesAcross;
            int gz = sampleIndex / job.samplesAcross;
            // M6.0: coarse async coverage must sample the real world-space
            // lattice. The old worker forgot sampleSpacing here, so a 2048b L6
            // tile could read heights from only its first ~8 blocks and then
            // stretch that tiny patch across the whole tile. That is the main
            // source of the repeated blobs/squares seen beyond L1/L2.
            int worldX = job.originX + gx * job.sampleSpacing;
            int worldZ = job.originZ + gz * job.sampleSpacing;

            heights[i] = sampleHeightCached(
                    level,
                    generator,
                    randomState,
                    worldX,
                    worldZ,
                    job.sampleSpacing
            );
        }

        return new HeightPart(start, heights);
    }

    private static MeshData buildMesh(GenerationJob job, int seaLevel) {
        if (job.ring.lodLevel() == 1
                && job.sampleSpacing == L1_EXACT_SPACING) {
            return buildBlockColumnMesh(job, seaLevel);
        }

        if (job.ring.lodLevel() >= 5
                && job.sampleSpacing < job.ring.sampleSpacing()) {
            // Focus-refined far terrain must stop looking like a smooth
            // heightfield. Horizontal plateaus plus vertical walls preserve a
            // Minecraft-like stepped silhouette when the user zooms in.
            return buildTerracedMesh(job, seaLevel);
        }

        if (job.ring.lodLevel() >= 3) {
            return buildMinecraftFacetedMesh(job, seaLevel);
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

    /**
     * M6.0 distant terrain renderer.
     *
     * L3-L6 keep the real sampled terrain silhouette but stop interpolating
     * biome colors across giant quads. Each coarse cell gets one Minecraft-like
     * material/color and an integer-quantized surface. The result reads as
     * distant Minecraft terrain instead of a continuous watercolor heightfield.
     */
    private static MeshData buildMinecraftFacetedMesh(
            GenerationJob job,
            int seaLevel
    ) {
        int cells = job.cellsAcross;
        int spacing = job.sampleSpacing;
        int verticalQuantum = switch (job.ring.lodLevel()) {
            case 3 -> 1;
            case 4 -> 2;
            case 5 -> 4;
            default -> 8;
        };

        MeshBuilder mesh = new MeshBuilder(job.cellCount);

        for (int gz = 0; gz < cells; gz++) {
            int z0 = job.originZ + gz * spacing;
            int z1 = z0 + spacing;

            for (int gx = 0; gx < cells; gx++) {
                int x0 = job.originX + gx * spacing;
                int x1 = x0 + spacing;

                int i00 = gz * job.samplesAcross + gx;
                int i10 = i00 + 1;
                int i01 = (gz + 1) * job.samplesAcross + gx;
                int i11 = i01 + 1;

                int raw00 = displaySampleHeight(job, i00, seaLevel);
                int raw10 = displaySampleHeight(job, i10, seaLevel);
                int raw01 = displaySampleHeight(job, i01, seaLevel);
                int raw11 = displaySampleHeight(job, i11, seaLevel);

                float dx = ((raw10 + raw11) - (raw00 + raw01))
                        * 0.5F / Math.max(1, spacing);
                float dz = ((raw01 + raw11) - (raw00 + raw10))
                        * 0.5F / Math.max(1, spacing);
                float maxRise = Math.max(
                        Math.max(Math.abs(raw10 - raw00), Math.abs(raw01 - raw00)),
                        Math.max(Math.abs(raw11 - raw10), Math.abs(raw11 - raw01))
                );
                float steepness = Math.min(
                        1.0F,
                        maxRise / Math.max(1.0F, spacing * 0.85F)
                );

                byte material = dominantMaterial(job, i00, i10, i01, i11);
                if (material == MinecraftSurfacePalette.MATERIAL_GRASS
                        && steepness > 0.55F) {
                    material = MinecraftSurfacePalette.MATERIAL_STONE;
                }

                int y00;
                int y10;
                int y01;
                int y11;

                if (material == MinecraftSurfacePalette.MATERIAL_WATER
                        || material == MinecraftSurfacePalette.MATERIAL_ICE) {
                    y00 = seaLevel;
                    y10 = seaLevel;
                    y01 = seaLevel;
                    y11 = seaLevel;
                } else {
                    y00 = quantizeHeight(raw00, verticalQuantum);
                    y10 = quantizeHeight(raw10, verticalQuantum);
                    y01 = quantizeHeight(raw01, verticalQuantum);
                    y11 = quantizeHeight(raw11, verticalQuantum);
                }

                float invLength = 1.0F
                        / (float) Math.sqrt(dx * dx + 1.0F + dz * dz);
                float nx = -dx * invLength;
                float ny = invLength;
                float nz = -dz * invLength;
                float lightDot =
                        nx * -0.45F + ny * 0.86F + nz * -0.24F;
                float shade = 0.74F
                        + Math.max(0.0F, lightDot) * 0.28F;

                int baseColor;
                if (material == MinecraftSurfacePalette.MATERIAL_STONE
                        && job.sampleMaterials[i00]
                                != MinecraftSurfacePalette.MATERIAL_STONE
                        && job.sampleMaterials[i10]
                                != MinecraftSurfacePalette.MATERIAL_STONE
                        && job.sampleMaterials[i01]
                                != MinecraftSurfacePalette.MATERIAL_STONE
                        && job.sampleMaterials[i11]
                                != MinecraftSurfacePalette.MATERIAL_STONE) {
                    baseColor = MinecraftSurfacePalette.stoneColor();
                } else {
                    baseColor = representativeColor(
                            job,
                            material,
                            i00,
                            i10,
                            i01,
                            i11
                    );
                }

                int centerX = x0 + spacing / 2;
                int centerZ = z0 + spacing / 2;
                int centerY = Math.round(
                        (y00 + y10 + y01 + y11) * 0.25F
                );

                int color = MaterialTerrainShading.apply(
                        MinecraftSurfacePalette.applyLighting(
                                baseColor,
                                material == MinecraftSurfacePalette.MATERIAL_WATER
                                        || material == MinecraftSurfacePalette.MATERIAL_ICE
                                        ? 0.96F
                                        : shade
                        ),
                        material,
                        centerX,
                        centerY,
                        centerZ,
                        spacing
                );

                // One flat material color per coarse facet. Geometry can slope
                // between real sampled heights, but color no longer turns a
                // 128/256-block cell into one giant interpolated gradient blob.
                mesh.addQuad(
                        x0, y00, z0,
                        x0, y01, z1,
                        x1, y11, z1,
                        x1, y10, z0,
                        color,
                        material
                );
            }
        }

        return mesh.finish();
    }

    private static int displaySampleHeight(
            GenerationJob job,
            int sampleIndex,
            int seaLevel
    ) {
        byte material = job.sampleMaterials[sampleIndex];
        return material == MinecraftSurfacePalette.MATERIAL_WATER
                || material == MinecraftSurfacePalette.MATERIAL_ICE
                ? seaLevel
                : job.heights[sampleIndex];
    }

    private static int quantizeHeight(int y, int quantum) {
        if (quantum <= 1) {
            return y;
        }

        return Math.round(y / (float) quantum) * quantum;
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
        int[] counts = new int[MinecraftSurfacePalette.MATERIAL_COUNT];
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
            case MinecraftSurfacePalette.MATERIAL_GRAVEL -> 0x726F69;
            case MinecraftSurfacePalette.MATERIAL_PODZOL -> 0x5A3E25;
            case MinecraftSurfacePalette.MATERIAL_MUD -> 0x3E3834;
            case MinecraftSurfacePalette.MATERIAL_ICE -> 0x8FB9D8;
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

    private static void putCacheTile(
            LodTileKey key,
            WorldgenSurfaceTile tile
    ) {
        WorldgenSurfaceTile previous = CACHE.put(key, tile);
        if (previous != null) {
            cacheResidentBytes -= ACCOUNTED_MESH_BYTES.remove(previous);
        }
        long bytes = estimatedTileBytes(tile);
        ACCOUNTED_MESH_BYTES.put(tile, bytes);
        cacheResidentBytes += bytes;
    }

    private static long estimatedTileBytes(WorldgenSurfaceTile tile) {
        return tile.residentMeshBytes();
    }

    private static void scheduleMeshCompaction(List<WorldgenSurfaceTile> tiles) {
        if (tiles.isEmpty()) {
            return;
        }
        long scheduledEpoch = epoch;
        MESH_COMPACTION_EXECUTOR.execute(() -> {
            for (WorldgenSurfaceTile tile : tiles) {
                if (scheduledEpoch != epoch) {
                    return;
                }
                try {
                    long saved = tile.compactGeometry();
                    if (saved > 0L) {
                        COMPLETED_COMPACTIONS.add(new CompactedTile(
                                scheduledEpoch,
                                new LodTileKey(tile.lodLevel(),
                                        tile.tileX(), tile.tileZ()),
                                tile, saved
                        ));
                    }
                } catch (RuntimeException exception) {
                    EverviewClient.LOGGER.warn(
                            "Everview could not compact a saved LOD tile",
                            exception
                    );
                }
            }
        });
    }

    private static void drainCompletedCompactions() {
        CompactedTile compacted;
        while ((compacted = COMPLETED_COMPACTIONS.poll()) != null) {
            if (compacted.epoch() == epoch
                    && CACHE.get(compacted.key()) == compacted.tile()) {
                long currentBytes = estimatedTileBytes(compacted.tile());
                Long previousBytes = ACCOUNTED_MESH_BYTES.put(
                        compacted.tile(), currentBytes);
                if (previousBytes != null) {
                    cacheResidentBytes += currentBytes - previousBytes;
                }
            }
        }
    }

    private static void trimCache() {
        Iterator<Map.Entry<LodTileKey, WorldgenSurfaceTile>> iterator =
                CACHE.entrySet().iterator();

        while ((CACHE.size() > CACHE_LIMIT
                        || cacheResidentBytes > MAX_CPU_MESH_BYTES)
                && iterator.hasNext()) {
            Map.Entry<LodTileKey, WorldgenSurfaceTile> entry =
                    iterator.next();
            if (containsWantedKey(entry.getKey())) {
                continue;
            }
            cacheResidentBytes -= ACCOUNTED_MESH_BYTES.remove(entry.getValue());
            iterator.remove();
        }

        if (cacheResidentBytes < 0L) {
            cacheResidentBytes = 0L;
        }
    }

    private static void trimL1SampleCache() {
        if (L1_SAMPLE_CACHE.size() <= L1_SAMPLE_CACHE_LIMIT) {
            return;
        }

        Iterator<Map.Entry<LodTileKey, L1SampleGrid>> iterator =
                L1_SAMPLE_CACHE.entrySet().iterator();

        while (L1_SAMPLE_CACHE.size() > L1_SAMPLE_CACHE_LIMIT
                && iterator.hasNext()) {
            Map.Entry<LodTileKey, L1SampleGrid> entry = iterator.next();

            if (currentJob != null && currentJob.key.equals(entry.getKey())) {
                continue;
            }
            if (DETACHED_EXACT_JOBS.containsKey(entry.getKey())
                    || DETACHED_APPEARANCE_JOBS.containsKey(entry.getKey())) {
                continue;
            }
            if (containsWantedKey(entry.getKey())) {
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
                currentJob != null
                        || activeSliceId != 0L
                        || !DETACHED_EXACT_JOBS.isEmpty()
                        || !DETACHED_COVERAGE_JOBS.isEmpty()
                        || !DETACHED_APPEARANCE_JOBS.isEmpty(),
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
        private final L1SampleGrid sampleGrid;
        private final boolean appearanceOnly;
        private final boolean exactGeometryOnly;

        private volatile int nextSample;
        private int reusedSamples;
        private int generatedSamples;
        private int appearanceGeneratedSamples;
        private int provisionalAppearanceSamples;
        private volatile CompletableFuture<HeightBatchResult> asyncHeightFuture;
        private int[] asyncMissingSampleIndices = new int[0];
        private long asyncStartedNanos;
        private boolean asyncExactDisabled;
        private boolean asyncCoverageStarted;
        private volatile boolean failed;
        private int minY = Integer.MAX_VALUE;
        private int maxY = Integer.MIN_VALUE;
        private long accumulatedNanos;

        private GenerationJob(
                LodTileKey key,
                WorldgenLodRing ring,
                int sampleSpacing,
                boolean refinement,
                L1SampleGrid sampleGrid,
                boolean appearanceOnly
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
            this.sampleGrid = sampleGrid;
            this.appearanceOnly = appearanceOnly;
            this.exactGeometryOnly = ring.lodLevel() == 1
                    && sampleSpacing == L1_EXACT_SPACING
                    && !appearanceOnly;
            this.samplesAcross = ring.tileSize() / sampleSpacing + 1;
            this.cellsAcross = samplesAcross - 1;
            this.totalSamples = samplesAcross * samplesAcross;
            this.cellCount = cellsAcross * cellsAcross;
            this.heights = new int[totalSamples];
            this.sampleColors = new int[totalSamples];
            this.sampleMaterials = new byte[totalSamples];
        }

        private int fineGridIndex(int gx, int gz) {
            if (sampleGrid == null) {
                return -1;
            }

            int fineX = gx * sampleSpacing;
            int fineZ = gz * sampleSpacing;

            if (fineX < 0 || fineZ < 0
                    || fineX >= sampleGrid.samplesAcross
                    || fineZ >= sampleGrid.samplesAcross) {
                return -1;
            }

            return fineZ * sampleGrid.samplesAcross + fineX;
        }

        private double progressPercent() {
            return nextSample * 100.0 / totalSamples;
        }
    }

    private static final class L1SampleGrid {
        private final int samplesAcross;
        private final int[] heights;
        private final int[] colors;
        private final byte[] materials;
        private final boolean[] heightSampled;
        private final boolean[] appearanceSampled;
        private boolean requiresAppearanceRefinement;

        private L1SampleGrid(int tileSize) {
            if (tileSize + 1 != L1_FINE_GRID_SAMPLES) {
                throw new IllegalArgumentException(
                        "L1 sample hierarchy expects 128-block tiles"
                );
            }

            this.samplesAcross = tileSize + 1;
            int total = samplesAcross * samplesAcross;
            this.heights = new int[total];
            this.colors = new int[total];
            this.materials = new byte[total];
            this.heightSampled = new boolean[total];
            this.appearanceSampled = new boolean[total];
        }

        private boolean hasAnyAppearance() {
            for (boolean sampled : appearanceSampled) {
                if (sampled) {
                    return true;
                }
            }
            return false;
        }

        private int nearestAppearanceIndex(int x, int z) {
            int clampedX = Math.max(0, Math.min(samplesAcross - 1, x));
            int clampedZ = Math.max(0, Math.min(samplesAcross - 1, z));
            int direct = clampedZ * samplesAcross + clampedX;
            if (appearanceSampled[direct]) {
                return direct;
            }

            for (int radius = 1; radius <= L1_BOOTSTRAP_SPACING; radius++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int remaining = radius - Math.abs(dz);
                    int testZ = clampedZ + dz;
                    if (testZ < 0 || testZ >= samplesAcross) {
                        continue;
                    }

                    int leftX = clampedX - remaining;
                    if (leftX >= 0) {
                        int index = testZ * samplesAcross + leftX;
                        if (appearanceSampled[index]) {
                            return index;
                        }
                    }

                    int rightX = clampedX + remaining;
                    if (rightX != leftX && rightX < samplesAcross) {
                        int index = testZ * samplesAcross + rightX;
                        if (appearanceSampled[index]) {
                            return index;
                        }
                    }
                }
            }

            return -1;
        }
    }

    private record HeightPart(
            int startOffset,
            int[] heights
    ) {
    }

    private record HeightBatchResult(
            int[] sampleIndices,
            int[] heights
    ) {
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

    public record RefinementReuseStatus(
            int lastReusedSamples,
            int lastGeneratedSamples,
            int lastAppearanceGeneratedSamples,
            int lastProvisionalAppearanceSamples,
            long totalReusedSamples,
            long totalGeneratedSamples,
            long totalAppearanceGeneratedSamples,
            int cachedL1Grids,
            int appearanceReadyTiles,
            int appearanceDesiredTiles,
            int provisionalExactTiles,
            int exactJobsActive,
            boolean serverExactFallback
    ) {
    }

    public record SharedHeightCacheStatus(
            int entries,
            long hits,
            long misses,
            double hitPercent
    ) {
    }

    public record SharedBiomeCacheStatus(
            int entries,
            long hits,
            long misses,
            double hitPercent
    ) {
    }

    public record StreamingStatus(
            double speedBlocksPerSecond,
            int predictiveLeadBlocks,
            boolean highSpeedCoverageMode,
            int predictiveDesired,
            int predictiveCovered,
            int emergencyDesired,
            int emergencyCovered,
            int globalFloorDesired,
            int globalFloorCovered,
            int outwardFrontierBlocks,
            boolean nearCoverageComplete,
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
            boolean predictive,
            int frontierDistanceBlocks
    ) {
    }

    private record CompletedTile(
            long epoch,
            LodTileKey key,
            WorldgenSurfaceTile tile,
            int reusedSamples,
            int generatedSamples,
            int appearanceGeneratedSamples,
            int provisionalAppearanceSamples
    ) {
    }

    private record CompactedTile(
            long epoch,
            LodTileKey key,
            WorldgenSurfaceTile tile,
            long savedBytes
    ) {
    }
}
