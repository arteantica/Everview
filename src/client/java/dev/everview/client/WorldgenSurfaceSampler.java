package dev.everview.client;

import dev.everview.terrain.*;

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

import java.lang.ref.WeakReference;
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

    // M9.5 keeps logical tile metadata broad, but raw/compact CPU mesh
    // residency is bounded independently from the generated-world extent.
    // Geometry is compacted eagerly after publication; the disk cache remains
    // the durable backing store.
    private static final int CACHE_LIMIT = 4_096;
    private static final long MAX_CPU_MESH_BYTES =
            896L * 1024L * 1024L;
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
    // Near quality is a spatial target, not a camera-facing target. Within
    // this radius every direction requests true 1b L1; view direction only
    // affects scheduling priority beyond it.
    private static final int L1_SPATIAL_EXACT_RADIUS_BLOCKS = 1_536;
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
    private static volatile ConcurrentHashMap<Long, Holder<Biome>>
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
                        thread.setPriority(Thread.MIN_PRIORITY);
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
                        thread.setPriority(Thread.MIN_PRIORITY);
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
                        thread.setPriority(Thread.MIN_PRIORITY);
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
    private static volatile TerrainSourceStore terrainSource;
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

    public static String generationQueues() {
        var a=(java.util.concurrent.ThreadPoolExecutor)EXACT_HEIGHT_EXECUTOR;
        var b=(java.util.concurrent.ThreadPoolExecutor)COVERAGE_HEIGHT_EXECUTOR;
        return "Workers active " + (a.getActiveCount()+b.getActiveCount()) + "/" + (EXACT_HEIGHT_WORKERS+COVERAGE_HEIGHT_WORKERS)
                + " | queued " + (a.getQueue().size()+b.getQueue().size()) + " | complete " + COMPLETED.size();
    }
    public static TerrainSourceStore terrainSource() { return terrainSource; }

    public static SharedHeightCacheStatus sharedHeightCacheStatus() {
        TerrainSourceStore source=terrainSource;
        long hits = source==null?0:source.hits.sum();
        long misses = source==null?0:source.misses.sum();
        long total = hits + misses;
        double hitPercent = total == 0L
                ? 0.0
                : hits * 100.0 / total;

        return new SharedHeightCacheStatus(
                terrainSource==null?0:(int)terrainSource.batchColumns.sum(),
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

        long integrationStarted = System.nanoTime();
        pollDiskIo();
        drainCompletedCompactions();
        updateAdaptiveBudget(client, server);
        drainCompleted();
        EverviewFrameProfiler.generationIntegration = System.nanoTime() - integrationStarted;

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
            if (!keys.equals(wantedKeys)) wantedKeys = keys;

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
        scheduleDetail(server, clientLevel.dimension());
        maybeScheduleDiskSave();
    }

    private static final ExecutorService DETAIL_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t=new Thread(r,"Everview-AdaptiveDetail");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;
    });
    private static CompletableFuture<Void> detailFuture;
    private static LodTileKey detailKey;
    private static long nextDetailPoll;
    private static volatile long detailCompleted, detailRejected;
    private static final Map<LodTileKey,Long> DETAIL_RETRY = new HashMap<>();
    private static volatile ConcurrentHashMap<LodTileKey,Integer> DETAIL_TARGET = new ConcurrentHashMap<>();
    public static String detailStatus() {return "detail " + detailCompleted + " ready / " + detailRejected + " budget-limited patches | " + (detailFuture==null?"idle":"working");}

    private static void scheduleDetail(MinecraftServer server,ResourceKey<Level> dimension) {
        long now=System.nanoTime();
        if(now<nextDetailPoll)return;nextDetailPoll=now+250_000_000L;
        if(detailFuture!=null){
            if(!detailFuture.isDone())return;
            try{detailFuture.join();}catch(RuntimeException ex){if(!(ex.getCause() instanceof java.util.concurrent.CancellationException))EverviewClient.LOGGER.warn("Everview detail task failed",ex);}
            detailFuture=null;detailKey=null;
        }
        if(highSpeedCoverageMode || terrainSource==null || !diskLoadReady)return;
        // First establish the far floor. Fine jobs share its source pages once it exists.
        if(snapshot.completionPercent()<65)return;
        Minecraft client=Minecraft.getInstance();if(client.player==null)return;
        double cx=client.player.getX(),cz=client.player.getZ();
        double focal=client.getWindow().getHeight()/(2.0*Math.tan(Math.toRadians(client.options.fov().get())*.5));
        WantedTile chosen=null;WorldgenSurfaceTile base=null;
        for(WantedTile wanted:wantedTiles){
            if(wanted.ring().lodLevel()<2||wanted.prefetch()||DETACHED_COVERAGE_JOBS.containsKey(wanted.key())
                    ||(currentJob!=null&&currentJob.key.equals(wanted.key()))||DETAIL_RETRY.getOrDefault(wanted.key(),0L)>now)continue;
            var tile=CACHE.get(wanted.key());if(tile==null||tile.sampleSpacing()>wanted.targetSpacing())continue;
            int target=DetailPolicy.spacing(wanted.frontierDistanceBlocks(),focal,false,0);
            if(tile.stage()==WorldgenTileStage.ADAPTIVE_DETAIL
                    && (tile.sampleSpacing()<=target || DETAIL_TARGET.getOrDefault(wanted.key(),Integer.MAX_VALUE)<=target))continue;
            // Don't refine a completely hidden fallback. Revisit it if it becomes exposed.
            var coverage=EverviewGpuRegionCache.renderState().coverage();boolean exposed=false;
            for(int z=tile.minZ();z<tile.maxZ()&&!exposed;z+=128)for(int x=tile.minX();x<tile.maxX();x+=128)
                if(!coverage.finerOwns(tile.lodLevel(),Math.floorDiv(x,128),Math.floorDiv(z,128))){exposed=true;break;}
            if(!exposed)continue;
            chosen=wanted;base=tile;break;
        }
        if(chosen==null)return;
        WantedTile wanted=chosen;WorldgenSurfaceTile previous=base;TerrainSourceStore source=terrainSource;long taskEpoch=epoch;
        detailKey=wanted.key();DETAIL_RETRY.put(detailKey,now+30_000_000_000L);
        var targets=DETAIL_TARGET;int requestedTarget=DetailPolicy.spacing(wanted.frontierDistanceBlocks(),focal,false,0);
        var launch=new CompletableFuture<Void>();detailFuture=launch;
        server.execute(()->{
            ServerLevel level=server.getLevel(dimension);
            if(level==null||taskEpoch!=epoch){launch.complete(null);return;}
            var generator=level.getChunkSource().getGenerator();var random=level.getChunkSource().randomState();
            var loader=heightLoader(level,generator,random);
            DETAIL_EXECUTOR.execute(()->{
                long start=System.nanoTime();
                try{
                    var resolver=generator.getBiomeSource().createResolver(random.createClimateSampler(
                            net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED));
                    MeshBuilder mesh=new MeshBuilder(4096);int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE,worst=1;
                    int patchBudget=260_000/((previous.tileSize()/128)*(previous.tileSize()/128));
                    for(int pz=previous.minZ();pz<previous.maxZ();pz+=128)for(int px=previous.minX();px<previous.maxX();px+=128){
                        if(taskEpoch!=epoch)throw new java.util.concurrent.CancellationException();
                        int lo=Integer.MAX_VALUE,hi=Integer.MIN_VALUE;boolean anyWet=false,anyDry=false;
                        // Coarse probes choose a refinement target; they are not called an error certificate.
                        for(int z=0;z<=128;z+=32)for(int x=0;x<=128;x+=32){int p=source.sample(px+x,pz+z,false,loader);int h=TerrainNoiseBatch.floor(p);lo=Math.min(lo,h);hi=Math.max(hi,h);anyWet|=TerrainNoiseBatch.wet(p);anyDry|=!TerrainNoiseBatch.wet(p);}
                        double distance=Math.hypot(px+64-cx,pz+64-cz);boolean shore=anyWet&&anyDry;
                        int spacing=DetailPolicy.spacing(distance,focal,shore,hi-lo);
                        int cells=128/spacing,stride=cells+1,total=stride*stride;
                        int[] h=new int[total],colors=new int[total];byte[] materials=new byte[total];boolean[] wet=new boolean[total];
                        for(int z=0;z<=cells;z++)for(int x=0;x<=cells;x++){
                            int wx=px+x*spacing,wz=pz+z*spacing,i=z*stride+x;
                            int packed=source.sample(wx,wz,true,loader);h[i]=TerrainNoiseBatch.surface(packed);wet[i]=TerrainNoiseBatch.wet(packed);
                            min=Math.min(min,h[i]);max=Math.max(max,h[i]);
                            int floor=TerrainNoiseBatch.floor(packed);
                            var appearance=source.appearance(wx,wz,()->{
                                long begin=System.nanoTime();var biome=resolver.getNoiseBiome(wx>>2,floor>>2,wz>>2);GenerationProfile.biomeNanos.add(System.nanoTime()-begin);GenerationProfile.biomeCalls.increment();
                                var value=MinecraftSurfacePalette.sampleSolid(biome,wx,floor,wz,level.getSeaLevel());
                                return new TerrainSourceStore.Appearance(value.rgb(),value.material());
                            });
                            colors[i]=appearance.color();materials[i]=appearance.material();
                            if(wet[i]&&materials[i]!=MinecraftSurfacePalette.MATERIAL_ICE){materials[i]=MinecraftSurfacePalette.MATERIAL_WATER;colors[i]=0x3B6E98;}
                            else if(!wet[i]&&(materials[i]==MinecraftSurfacePalette.MATERIAL_WATER||materials[i]==MinecraftSurfacePalette.MATERIAL_ICE)){materials[i]=MinecraftSurfacePalette.MATERIAL_GRASS;colors[i]=0x6F9D50;}
                        }
                        int ox=px,oz=pz;int quantum=DetailPolicy.quantum(distance,focal,shore);
                        long meshStart=System.nanoTime();
                        int actual=emitBudgetedDetailPatch(mesh,ox,oz,spacing,h,colors,materials,wet,quantum,patchBudget);
                        GenerationProfile.meshNanos.add(System.nanoTime()-meshStart);
                        worst=Math.max(worst,actual);if(actual>spacing)detailRejected++;
                        (actual==1?GenerationProfile.detailCells1:actual==2?GenerationProfile.detailCells2:
                                actual==4?GenerationProfile.detailCells4:GenerationProfile.detailCellsCoarse).increment();
                    }
                    MeshData result=mesh.finish();long elapsed=System.nanoTime()-start;
                    GenerationProfile.meshQuads.add(result.quadCount());GenerationProfile.meshes.increment();
                    var tile=new WorldgenSurfaceTile(previous.lodLevel(),previous.tileX(),previous.tileZ(),previous.tileSize(),worst,
                            WorldgenTileStage.ADAPTIVE_DETAIL,result.vertices(),result.colors(),result.materials(),result.quadCount(),min-1,max+1,level.getSeaLevel(),elapsed);
                    if(taskEpoch!=epoch)throw new java.util.concurrent.CancellationException();
                    COMPLETED.add(new CompletedTile(taskEpoch,wanted.key(),tile,0,0,0,0));targets.put(wanted.key(),requestedTarget);detailCompleted++;
                    launch.complete(null);
                }catch(Throwable ex){launch.completeExceptionally(ex);}
                finally{GenerationProfile.workerNanos.add(System.nanoTime()-start);GenerationProfile.workerTasks.increment();}
            });
        });
    }
    private static int emitBudgetedDetailPatch(MeshBuilder mesh,int ox,int oz,int sourceSpacing,
            int[] heights,int[] colors,byte[] materials,boolean[] wet,int quantum,int budget) {
        int savedQuads=mesh.quadCount,savedInts=mesh.vertexInts,savedVertices=mesh.vertexCount;
        int sourceStride=128/sourceSpacing+1;
        for(int spacing=sourceSpacing;spacing<=16;spacing*=2){
            int cells=128/spacing,stride=cells+1,factor=spacing/sourceSpacing;
            int[] h=heights,c=colors;byte[] m=materials;boolean[] w=wet;
            if(factor>1){
                int count=stride*stride;h=new int[count];c=new int[count];m=new byte[count];w=new boolean[count];
                for(int z=0;z<=cells;z++)for(int x=0;x<=cells;x++){
                    int from=z*factor*sourceStride+x*factor,to=z*stride+x;
                    h[to]=heights[from];c[to]=colors[from];m[to]=materials[from];w[to]=wet[from];
                }
            }
            try {
                new BlockSurfaceMesh(cells,spacing,h,c,m,w,quantum,(x0,y0,z0,x1,y1,z1,x2,y2,z2,x3,y3,z3,color,material)->{
                    if(mesh.quadCount-savedQuads>=budget)throw new DetailBudgetException();
                    mesh.addQuad(ox+x0,y0,oz+z0,ox+x1,y1,oz+z1,ox+x2,y2,oz+z2,ox+x3,y3,oz+z3,color,material);
                }).boundaryWalls(false).build();
                return spacing;
            } catch(DetailBudgetException limited){
                mesh.quadCount=savedQuads;mesh.vertexInts=savedInts;mesh.vertexCount=savedVertices;
            }
        }
        throw new IllegalStateException("16b patch exceeded reserved geometry budget");
    }
    private static final class DetailBudgetException extends RuntimeException {}

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
        terrainSource = new TerrainSourceStore(diskCachePath.resolve("source-v1-" + diskCacheSeed), 32_768);
        GenerationProfile.reset();
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
        detailFuture = null; detailKey = null; DETAIL_RETRY.clear(); DETAIL_TARGET=new ConcurrentHashMap<>(); detailCompleted=detailRejected=0;
        if (terrainSource != null) { terrainSource.close(); terrainSource = null; }
        meshContentRevision++;
        CACHE.clear();
        ACCOUNTED_MESH_BYTES.clear();
        cacheResidentBytes = 0L;
        L1_SAMPLE_CACHE.clear();
        SHARED_HEIGHT_CACHE.clear();
        SHARED_HEIGHT_HITS.set(0L);
        SHARED_HEIGHT_MISSES.set(0L);
        SHARED_BIOME_CACHE = new ConcurrentHashMap<>(65_536);
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

            var existing=CACHE.get(completed.key());
            if(existing!=null && existing.stage()==WorldgenTileStage.ADAPTIVE_DETAIL
                    && completed.tile().stage()!=WorldgenTileStage.ADAPTIVE_DETAIL){
                if(currentJob!=null&&currentJob.key.equals(completed.key()))currentJob=null;
                continue;
            }
            putCacheTile(completed.key(), completed.tile());
            GenerationProfile.integrated.increment();
            lastGenerationMs = completed.tile().generationMs();
            generatedTileCount++;
            if (completed.tile().stage().diskSafe()) {
                DISK_DIRTY_TILES.put(
                        completed.key(),
                        completed.tile()
                );
                // Do not retain a full raw int[] mesh merely because the tile
                // remains logically wanted. Region uploads can expand the
                // compact representation on demand while persistent storage
                // remains the durable source of truth.
                scheduleMeshCompaction(
                        List.of(completed.tile())
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

                        int spatialExactOuter = Math.min(
                                ring.outerRadiusBlocks(),
                                L1_SPATIAL_EXACT_RADIUS_BLOCKS
                        );
                        boolean inSpatialExact = tileIntersectsAnnulus(
                                tileX,
                                tileZ,
                                centerX,
                                centerZ,
                                ring.innerRadiusBlocks(),
                                spatialExactOuter,
                                tileSize
                        );

                        if (inSpatialExact) {
                            // M9.5: stationary camera rotation cannot change
                            // the desired quality of nearby generated terrain.
                            targetSpacing = L1_EXACT_SPACING;
                        } else if (!visibleNow) {
                            targetSpacing = L1_BOOTSTRAP_SPACING;
                        } else {
                            int exactOuter = Math.min(
                                    ring.outerRadiusBlocks(),
                                    Math.max(
                                            spatialExactOuter,
                                            ring.innerRadiusBlocks()
                                                    + L1_EXACT_BAND_BLOCKS
                                    )
                            );
                            int intermediateOuter = Math.min(
                                    ring.outerRadiusBlocks(),
                                    Math.max(
                                            exactOuter,
                                            ring.innerRadiusBlocks()
                                                    + L1_INTERMEDIATE_BAND_BLOCKS
                                    )
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

                            if (inFocusedExact) {
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
                    job.water[sampleIndex] = job.sampleGrid.water[fineIndex];
                    job.fluidHeights[sampleIndex] = job.sampleGrid.fluidHeights[fineIndex];
                    job.minY = Math.min(job.minY, y);
                    job.maxY = Math.max(job.maxY, Math.max(y,job.fluidHeights[sampleIndex]));
                    job.reusedSamples++;

                    if (!grid.appearanceSampled[fineIndex]) {
                        int worldX = job.originX + gx;
                        int worldZ = job.originZ + gz;
                        biomes[sampleIndex] = sampleBiomeCached(
                                job,
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

            var appearance = sampleSourceAppearance(job,
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
                job.water[sampleIndex] = job.sampleGrid.water[fineIndex];
                    job.fluidHeights[sampleIndex] = job.sampleGrid.fluidHeights[fineIndex];
                job.minY = Math.min(job.minY, y);
                job.maxY = Math.max(job.maxY, Math.max(y,job.fluidHeights[sampleIndex]));
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
            int packed = result.heights()[i];
            int y = TerrainNoiseBatch.floor(packed);
            job.water[sampleIndex] = TerrainNoiseBatch.wet(packed);
            job.fluidHeights[sampleIndex] = TerrainNoiseBatch.surface(packed);
            int gx = sampleIndex % job.samplesAcross;
            int gz = sampleIndex / job.samplesAcross;
            int fineIndex = job.fineGridIndex(gx, gz);

            job.heights[sampleIndex] = y;
            if (fineIndex >= 0) {
                job.sampleGrid.heights[fineIndex] = y;
                job.sampleGrid.water[fineIndex] = job.water[sampleIndex];
                job.sampleGrid.fluidHeights[fineIndex] = job.fluidHeights[sampleIndex];
                job.sampleGrid.heightSampled[fineIndex] = true;
            }
            job.minY = Math.min(job.minY, y);
            job.maxY = Math.max(job.maxY, Math.max(y,job.fluidHeights[sampleIndex]));
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
        if(job.assemblyPending)return;

        ServerChunkCache chunks = level.getChunkSource();
        var generator = chunks.getGenerator();
        var randomState = chunks.randomState();

        // M6.4: all L2-L6 height coverage uses the dedicated worker pool, not
        // just emergency high-speed coverage. getBaseHeight was the dominant
        // reason a settled world could take 10+ minutes. M9.7 also assembles
        // appearance and geometry on these workers after height completion.
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
                int packed = sampleHeightCached(
                        level,
                        generator,
                        randomState,
                        worldX,
                        worldZ,
                        job.sampleSpacing, job
                );

                int y = TerrainNoiseBatch.floor(packed);
                job.water[sampleIndex] = TerrainNoiseBatch.wet(packed);
            job.fluidHeights[sampleIndex] = TerrainNoiseBatch.surface(packed);
                var biome = sampleBiomeCached(
                                job,
                                level,
                                worldX,
                                y,
                                worldZ
                        );
                var appearance = sampleSourceAppearance(job,
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
                    job.water[sampleIndex] = grid.water[fineIndex];
                    job.fluidHeights[sampleIndex] = grid.fluidHeights[fineIndex];
                    job.reusedSamples++;
                } else {
                    int packed = sampleHeightCached(
                        level,
                        generator,
                        randomState,
                        worldX,
                        worldZ,
                        job.sampleSpacing, job
                );
                    y = TerrainNoiseBatch.floor(packed);
                    job.water[sampleIndex] = TerrainNoiseBatch.wet(packed);
            job.fluidHeights[sampleIndex] = TerrainNoiseBatch.surface(packed);
                    grid.heights[fineIndex] = y;
                    grid.water[fineIndex] = job.water[sampleIndex];
                    grid.fluidHeights[fineIndex] = job.fluidHeights[sampleIndex];
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
                                job,
                                level,
                                worldX,
                                y,
                                worldZ
                        );
                        var appearance = sampleSourceAppearance(job,
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
                                job,
                                level,
                                worldX,
                                y,
                                worldZ
                        );
                    var appearance = sampleSourceAppearance(job,
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
            job.maxY = Math.max(job.maxY, Math.max(y,job.fluidHeights[sampleIndex]));
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

        if(job.assemblyPending)return;
        job.assemblyPending=true;
        COVERAGE_HEIGHT_EXECUTOR.execute(()->{try{
            if(taskEpoch!=epoch)return;
        for (int i = 0; i < result.sampleIndices().length; i++) {
            int sampleIndex = result.sampleIndices()[i];
            int packed = result.heights()[i];
            int y = TerrainNoiseBatch.floor(packed);
            job.water[sampleIndex] = TerrainNoiseBatch.wet(packed);
            job.fluidHeights[sampleIndex] = TerrainNoiseBatch.surface(packed);
            job.heights[sampleIndex] = y;

            if (job.sampleGrid != null) {
                int gx = sampleIndex % job.samplesAcross;
                int gz = sampleIndex / job.samplesAcross;
                int fineIndex = job.fineGridIndex(gx, gz);
                if (fineIndex >= 0) {
                    job.sampleGrid.heights[fineIndex] = y;
                    job.sampleGrid.water[fineIndex] = job.water[sampleIndex];
                job.sampleGrid.fluidHeights[fineIndex] = job.fluidHeights[sampleIndex];
                    job.sampleGrid.heightSampled[fineIndex] = true;
                }
            }

            job.minY = Math.min(job.minY, y);
            job.maxY = Math.max(job.maxY, Math.max(y,job.fluidHeights[sampleIndex]));
            job.generatedSamples++;
        }

        // Only immutable generator/biome inputs are queried here, never loaded chunks.
        for (int sampleIndex = 0;
                sampleIndex < job.totalSamples;
                sampleIndex++) {
            int gx = sampleIndex % job.samplesAcross;
            int gz = sampleIndex / job.samplesAcross;
            int worldX = job.originX + gx * job.sampleSpacing;
            int worldZ = job.originZ + gz * job.sampleSpacing;
            int y = job.heights[sampleIndex];

            var biome = sampleBiomeCached(
                                job,
                                level,
                                worldX,
                                y,
                                worldZ
                        );
            var appearance = sampleSourceAppearance(job,
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
        }catch(Throwable error){job.failed=true;EverviewClient.LOGGER.warn("Everview coverage assembly failed",error);}
        });
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
                    job.water[sampleIndex] = job.sampleGrid.water[fineIndex];
                    job.fluidHeights[sampleIndex] = job.sampleGrid.fluidHeights[fineIndex];
                    job.minY = Math.min(job.minY, y);
                    job.maxY = Math.max(job.maxY, Math.max(y,job.fluidHeights[sampleIndex]));
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
            int packed = result.heights()[i];
            int y = TerrainNoiseBatch.floor(packed);
            job.water[sampleIndex] = TerrainNoiseBatch.wet(packed);
            job.fluidHeights[sampleIndex] = TerrainNoiseBatch.surface(packed);
            int gx = sampleIndex % job.samplesAcross;
            int gz = sampleIndex / job.samplesAcross;
            int fineIndex = job.fineGridIndex(gx, gz);

            job.heights[sampleIndex] = y;
            if (fineIndex >= 0) {
                job.sampleGrid.heights[fineIndex] = y;
                job.sampleGrid.water[fineIndex] = job.water[sampleIndex];
                job.sampleGrid.fluidHeights[fineIndex] = job.fluidHeights[sampleIndex];
                job.sampleGrid.heightSampled[fineIndex] = true;
            }
            job.minY = Math.min(job.minY, y);
            job.maxY = Math.max(job.maxY, Math.max(y,job.fluidHeights[sampleIndex]));
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
                                job,
                                level,
                                worldX,
                                y,
                                worldZ
                        );
            var appearance = sampleSourceAppearance(job,
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

            long enqueued = System.nanoTime();
            futures.add(CompletableFuture.supplyAsync(
                    () -> { long began=System.nanoTime(); GenerationProfile.queueNanos.add(began-enqueued);
                        try { return computeHeightPart(
                            level,
                            generator,
                            randomState,
                            job,
                            sampleIndices,
                            start,
                            end
                    ); } finally {GenerationProfile.workerNanos.add(System.nanoTime()-began);GenerationProfile.workerTasks.increment();} },
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

    private static MinecraftSurfacePalette.SampleAppearance sampleSourceAppearance(GenerationJob job,Holder<Biome> biome,int x,int y,int z,int sea) {
        var a=job.source.appearance(x,z,()->{
            var solid=MinecraftSurfacePalette.sampleSolid(biome,x,y,z,sea);
            return new TerrainSourceStore.Appearance(solid.rgb(),solid.material());
        });
        int index=((z-job.originZ)/job.sampleSpacing)*job.samplesAcross+(x-job.originX)/job.sampleSpacing;
        if(index>=0&&index<job.water.length&&job.water[index])
            return MinecraftSurfacePalette.sample(biome,x,y,z,sea);
        return new MinecraftSurfacePalette.SampleAppearance(a.color(),a.material());
    }

    private static Holder<Biome> sampleBiomeCached(
            GenerationJob job,
            ServerLevel level,
            int worldX,
            int worldY,
            int worldZ
    ) {
        int quartX = worldX >> 2;
        int quartY = worldY >> 2;
        int quartZ = worldZ >> 2;
        long key = packQuartBiome(quartX, quartY, quartZ);

        Holder<Biome> cached = job.biomeCache.get(key);
        if (cached != null) {
            SHARED_BIOME_HITS.incrementAndGet();
            return cached;
        }

        long biomeStart=System.nanoTime();
        if(job.biomeResolver==null){
            var chunks=level.getChunkSource();
            job.biomeResolver=chunks.getGenerator().getBiomeSource().createResolver(
                    chunks.randomState().createClimateSampler(net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED));
        }
        Holder<Biome> biome = job.biomeResolver.getNoiseBiome(quartX,quartY,quartZ);
        GenerationProfile.biomeNanos.add(System.nanoTime()-biomeStart);GenerationProfile.biomeCalls.increment();
        SHARED_BIOME_MISSES.incrementAndGet();

        if (job.biomeCache.size() < SHARED_BIOME_CACHE_LIMIT) {
            Holder<Biome> raced =
                    job.biomeCache.putIfAbsent(key, biome);
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
            int sampleSpacing, GenerationJob job
    ) {
        TerrainSourceStore source = job.source;
        TerrainSourceStore.Loader loader = heightLoader(level, generator, randomState);
        return source.sample(worldX, worldZ, sampleSpacing <= 4, loader);
    }

    private static TerrainSourceStore.Loader heightLoader(ServerLevel level,
            net.minecraft.world.level.chunk.ChunkGenerator generator,
            net.minecraft.world.level.levelgen.RandomState randomState) {
        return new TerrainSourceStore.Loader() {
            public int[] batch(int x, int z) {
                long start=System.nanoTime();
                try {
                    if(generator instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator noise)
                        return TerrainNoiseBatch.sample(noise,randomState,level,x,z,16);
                    int[] result=new int[256];
                    for(int dz=0;dz<16;dz++)for(int dx=0;dx<16;dx++)result[dz*16+dx]=column(x+dx,z+dz);
                    return result;
                } finally {GenerationProfile.noiseNanos.add(System.nanoTime()-start);GenerationProfile.noiseColumns.add(256);}
            }
            public int column(int x,int z) {
                long start=System.nanoTime();
                try {
                    if(generator instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator noise)
                        return TerrainNoiseBatch.sample(noise,randomState,level,x,z,1)[0];
                    int floor=generator.getBaseHeight(x,z,Heightmap.Types.OCEAN_FLOOR_WG,level,randomState);
                    int surface=generator.getBaseHeight(x,z,Heightmap.Types.WORLD_SURFACE_WG,level,randomState);
                    return TerrainNoiseBatch.pack(floor,surface);
                } finally {GenerationProfile.noiseNanos.add(System.nanoTime()-start);GenerationProfile.noiseColumns.increment();}
            }
        };
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
        TerrainSourceStore.Loader loader=heightLoader(level,generator,randomState);

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

            heights[i] = job.source.sample(worldX, worldZ, job.sampleSpacing <= 4, loader);
        }

        return new HeightPart(start, heights);
    }

    private static MeshData buildMesh(GenerationJob job, int seaLevel) {
        long start=System.nanoTime();
        try { MeshData mesh=buildMeshInternal(job,seaLevel); GenerationProfile.meshQuads.add(mesh.quadCount()); return mesh; }
        finally {GenerationProfile.meshNanos.add(System.nanoTime()-start);GenerationProfile.meshes.increment();}
    }

    private static MeshData buildMeshInternal(GenerationJob job, int seaLevel) {
        // Heights are solid-surface samples. Fluid coverage never comes from a nearby
        // borrowed biome/material, and never from a coarse-cell majority vote.
        for (int i = 0; i < job.totalSamples; i++) {
            boolean wet = job.water[i];
            byte material = job.sampleMaterials[i];
            boolean waterMaterial = material == MinecraftSurfacePalette.MATERIAL_WATER
                    || material == MinecraftSurfacePalette.MATERIAL_ICE;
            if (wet && !waterMaterial) {
                job.sampleMaterials[i] = MinecraftSurfacePalette.MATERIAL_WATER;
                job.sampleColors[i] = 0x3B6E98;
            } else if (!wet && waterMaterial) {
                job.sampleMaterials[i] = MinecraftSurfacePalette.MATERIAL_GRASS;
                job.sampleColors[i] = 0x6F9D50;
            }
        }
        if (job.ring.lodLevel() == 1 && job.sampleSpacing == L1_EXACT_SPACING) {
            return buildBlockColumnMesh(job, seaLevel);
        }
        return buildAdaptiveMesh(job, seaLevel);
    }

    private static MeshData buildAdaptiveMesh(GenerationJob job, int seaLevel) {
        MeshBuilder mesh = new MeshBuilder(Math.max(16, job.cellCount / 2));
        double error = switch (job.ring.lodLevel()) {
            case 1, 2, 3 -> 0.5;
            case 4 -> 1.0;
            case 5 -> 2.0;
            default -> 4.0;
        };
        new dev.everview.core.AdaptiveSurfaceMesh(job.cellsAcross, job.sampleSpacing, seaLevel,
                job.heights, job.sampleMaterials, job.sampleColors, job.water, job.fluidHeights, error,
                (x0, z0, x1, z1, y00, y01, y11, y10, material, color) -> {
                    // No height quantization or averaged plateaus. The simplified
                    // triangles retain all sampled features within their error bound.
                    double dx = ((y10 + y11) - (y00 + y01)) * .5 / Math.max(1, x1 - x0);
                    double dz = ((y01 + y11) - (y00 + y10)) * .5 / Math.max(1, z1 - z0);
                    double normal = Math.sqrt(dx * dx + 1 + dz * dz);
                    float shade = material == MinecraftSurfacePalette.MATERIAL_WATER ? .96f
                            : (float) (.74 + Math.max(0, (dx * .45 + .86 + dz * .24) / normal) * .28);
                    int lit = MinecraftSurfacePalette.applyLighting(color, shade);
                    mesh.addQuad(job.originX + x0, y00, job.originZ + z0,
                            job.originX + x0, y01, job.originZ + z1,
                            job.originX + x1, y11, job.originZ + z1,
                            job.originX + x1, y10, job.originZ + z0, lit, material);
                }).build();
        return mesh.finish();
    }

    /**
     * Exact L1 is represented as Minecraft-like surface columns instead of
     * four-corner averaged plateaus. Each cell takes the height/material/color
     * sampled at its own block coordinate. East and south tile edges use the
     * extra sample row/column already present in the generation job, so adjacent
     * exact tiles meet deterministically without deep crack-hiding skirts.
     */
    private static MeshData buildBlockColumnMesh(GenerationJob job, int seaLevel) {
        int[] displayed=job.heights.clone();
        for(int i=0;i<displayed.length;i++)if(job.water[i])displayed[i]=job.fluidHeights[i];
        MeshBuilder mesh=new MeshBuilder(Math.max(16,job.cellCount/4));
        new BlockSurfaceMesh(job.cellsAcross,job.sampleSpacing,displayed,job.sampleColors,job.sampleMaterials,job.water,1,
            (x0,y0,z0,x1,y1,z1,x2,y2,z2,x3,y3,z3,color,material)->mesh.addQuad(
                x0+job.originX,y0,z0+job.originZ,x1+job.originX,y1,z1+job.originZ,
                x2+job.originX,y2,z2+job.originZ,x3+job.originX,y3,z3+job.originZ,color,material)).boundaryWalls(false).build();
        return mesh.finish();
    }

    private static int exactColumnHeight(
            GenerationJob job,
            int sample,
            byte material,
            int seaLevel
    ) {
        return material == MinecraftSurfacePalette.MATERIAL_WATER
                || material == MinecraftSurfacePalette.MATERIAL_ICE
                ? job.fluidHeights[sample]
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

    private static void putCacheTile(
            LodTileKey key,
            WorldgenSurfaceTile tile
    ) {
        meshContentRevision++;
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
        // Large warm loads can include meshes evicted immediately by the
        // cache budget. A compaction task must not retain that whole load
        // (several GiB of geometry) until the last tile has been processed.
        List<WeakReference<WorldgenSurfaceTile>> pending =
                new ArrayList<>(tiles.size());
        for (WorldgenSurfaceTile tile : tiles) {
            pending.add(new WeakReference<>(tile));
        }
        long scheduledEpoch = epoch;
        MESH_COMPACTION_EXECUTOR.execute(() -> {
            for (WeakReference<WorldgenSurfaceTile> reference : pending) {
                if (scheduledEpoch != epoch) {
                    return;
                }
                WorldgenSurfaceTile tile = reference.get();
                if (tile == null) {
                    continue;
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

    private static long meshContentRevision;
    private static long snapshotMeshRevision = Long.MIN_VALUE;
    private static Set<LodTileKey> snapshotWantedKeys;
    private static List<WorldgenSurfaceTile> publishedTiles = List.of();
    private static Map<Integer, Integer> publishedReady = Map.of();
    private static Map<Integer, Integer> publishedDesired = Map.of();

    private static void rebuildSnapshot() {
        if (meshContentRevision != snapshotMeshRevision || snapshotWantedKeys != wantedKeys) {
            List<WorldgenSurfaceTile> active = new ArrayList<>();
            Map<Integer, Integer> desired = new HashMap<>(), ready = new HashMap<>();
            for (WantedTile wanted : wantedTiles) {
                int level = wanted.ring().lodLevel();
                desired.merge(level, 1, Integer::sum);
                WorldgenSurfaceTile tile = CACHE.get(wanted.key());
                if (tile != null) { active.add(tile); ready.merge(level, 1, Integer::sum); }
            }
            publishedTiles = List.copyOf(active);
            publishedDesired = desired; publishedReady = ready;
            snapshotMeshRevision = meshContentRevision; snapshotWantedKeys = wantedKeys;
        }
        List<WorldgenSurfaceTile> active = publishedTiles;
        Map<Integer, Integer> desiredByLevel = publishedDesired, readyByLevel = publishedReady;

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
        private final TerrainSourceStore source = terrainSource;
        private final java.util.concurrent.ConcurrentMap<Long, Holder<Biome>> biomeCache = SHARED_BIOME_CACHE;
        private net.minecraft.world.level.biome.BiomeResolver biomeResolver;
        private volatile boolean assemblyPending;
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
        private final boolean[] water;
        private final int[] fluidHeights;
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
            this.water = new boolean[totalSamples];
            this.fluidHeights = new int[totalSamples];
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
        private final boolean[] water;
        private final int[] fluidHeights;
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
            this.water = new boolean[total];
            this.fluidHeights = new int[total];
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
