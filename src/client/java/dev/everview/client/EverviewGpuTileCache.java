package dev.everview.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.everview.core.LodTileKey;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Render-thread-owned persistent GPU storage for generated LOD tiles.
 *
 * Tile geometry is uploaded once as POSITION_COLOR data in tile-local X/Z.
 * M5 partitions every near-ring quad through the 16x16 vanilla chunk grid.
 * L3 therefore carries both vanilla chunk-column ownership and its 128x128 L2
 * fallback-region ownership, so no emergency surface can leak through vanilla.
 */
public final class EverviewGpuTileCache {
    private static final int MAX_GPU_TILES = 4_096;
    private static final int TARGET_GPU_TILES = 3_840;
    // Tile count alone is not a safe residency bound once exact 1b meshes
    // become large. M8 could climb past ~700 MiB while still below 900 tiles,
    // so M9 also enforces an explicit byte budget.
    private static final long TARGET_GPU_BYTES =
            1_900L * 1024L * 1024L;
    private static final long MAX_GPU_BYTES =
            2_300L * 1024L * 1024L;
    private static final int MAX_UPLOADS_PER_FRAME = 12;
    private static final int PRUNE_RING_MARGIN_BLOCKS = 128;
    // M9.1 separates "what is currently in the frustum" from "what should
    // remain turn-stable". A full 360-degree near belt plus the L3 safety
    // surface stays resident so a 180/360 turn never reveals empty sky while
    // view-specific L1/L2 uploads catch up.
    private static final int RESIDENCY_NEAR_RADIUS_BLOCKS = 1_024;
    private static final int TURN_STABLE_L3_RADIUS_BLOCKS = 2_304;
    private static final long VIEW_RESIDENCY_HOLD_NANOS =
            60_000_000_000L;
    private static final double RESIDENCY_FRUSTUM_MARGIN_BLOCKS = 256.0D;
    private static final int VIEW_YAW_QUANTUM_DEGREES = 12;
    private static final int VIEW_PITCH_QUANTUM_DEGREES = 10;
    private static final int L3_UNDERLAY_REGION_SIZE = 128;
    private static final int L4_UNDERLAY_REGION_SIZE = 256;
    private static final int L5_UNDERLAY_REGION_SIZE = 512;
    private static final int L6_UNDERLAY_REGION_SIZE = 1_024;

    // Insertion order is deliberate. M7.3 no longer relies on accidental
    // access-order LRU behavior; ownership discovery touches every resident
    // tile and used to make access order meaningless.
    private static final Map<LodTileKey, GpuTile> TILES =
            new LinkedHashMap<>(256, 0.75F, false);

    private static ClientLevel lastLevel;
    private static int uploadsRemaining;
    private static int uploadsThisFrame;
    private static long uploadNanosThisFrame;
    private static int staleRemovedThisFrame;
    private static int coveredPrunedThisFrame;
    private static int forcedEvictionsThisFrame;
    private static int suppressionRebuildsThisFrame;
    private static int residencySelectionRebuildsThisFrame;
    private static int offscreenEvictionsThisFrame;
    private static long prepareNanosThisFrame;
    private static long residentBytes;
    private static long residencyRevision;

    private static final Set<LodTileKey> ACTIVE_KEYS = new HashSet<>();
    private static final Set<LodTileKey> RESIDENCY_WANTED =
            new HashSet<>();
    private static final Set<LodTileKey> RESIDENCY_PINNED =
            new HashSet<>();
    private static final List<WorldgenSurfaceTile> UPLOAD_ORDER =
            new ArrayList<>();
    private static final Set<LodTileKey> SUPPRESSED_COARSE =
            new HashSet<>();
    private static final Map<LodTileKey, Long> LAST_VIEW_WANTED_NANOS =
            new HashMap<>();
    private static final Map<LodTileKey, Long> LAST_ACTIVE_NANOS =
            new HashMap<>();
    private static WorldgenSurfaceSnapshot lastSnapshotReference;
    private static long lastSnapshotFingerprint = Long.MIN_VALUE;
    private static int lastPruneCellX = Integer.MIN_VALUE;
    private static int lastPruneCellZ = Integer.MIN_VALUE;
    private static int lastViewYawSector = Integer.MIN_VALUE;
    private static int lastViewPitchSector = Integer.MIN_VALUE;
    private static boolean residencySelectionDirty = true;
    private static boolean suppressionDirty = true;
    private static boolean residencyComplete;

    private EverviewGpuTileCache() {
    }

    /**
     * Called from COLLECT_SUBMITS, before Minecraft opens the opaque terrain
     * RenderPass. Buffer creation/upload is intentionally kept out of the
     * active terrain pass.
     */
    public static void prepareFrame(
            ClientLevel level,
            WorldgenSurfaceSnapshot snapshot,
            Camera camera
    ) {
        long prepareStarted = System.nanoTime();

        if (level != lastLevel) {
            clear();
            lastLevel = level;
        }

        uploadsRemaining = MAX_UPLOADS_PER_FRAME;
        uploadsThisFrame = 0;
        uploadNanosThisFrame = 0L;
        staleRemovedThisFrame = 0;
        coveredPrunedThisFrame = 0;
        forcedEvictionsThisFrame = 0;
        suppressionRebuildsThisFrame = 0;
        residencySelectionRebuildsThisFrame = 0;
        offscreenEvictionsThisFrame = 0;

        var cameraPos = camera.position();
        double cameraX = cameraPos.x();
        double cameraZ = cameraPos.z();

        int pruneCellX = Math.floorDiv(
                (int) Math.floor(cameraX),
                PRUNE_RING_MARGIN_BLOCKS
        );
        int pruneCellZ = Math.floorDiv(
                (int) Math.floor(cameraZ),
                PRUNE_RING_MARGIN_BLOCKS
        );
        int viewYawSector = Math.floorMod(
                (int) Math.floor(
                        (camera.yRot()
                                + VIEW_YAW_QUANTUM_DEGREES * 0.5F)
                                / VIEW_YAW_QUANTUM_DEGREES
                ),
                Math.max(1, 360 / VIEW_YAW_QUANTUM_DEGREES)
        );
        int viewPitchSector = (int) Math.floor(
                (camera.xRot()
                        + 90.0F
                        + VIEW_PITCH_QUANTUM_DEGREES * 0.5F)
                        / VIEW_PITCH_QUANTUM_DEGREES
        );

        boolean newSnapshotObject = snapshot != lastSnapshotReference;
        if (newSnapshotObject) {
            long fingerprint = snapshotFingerprint(snapshot);

            if (fingerprint != lastSnapshotFingerprint) {
                rebuildActiveKeys(snapshot);
                removeStaleResidents();
                lastSnapshotFingerprint = fingerprint;
                residencySelectionDirty = true;
                suppressionDirty = true;
                residencyComplete = false;
            }

            lastSnapshotReference = snapshot;
        }

        if (pruneCellX != lastPruneCellX
                || pruneCellZ != lastPruneCellZ) {
            lastPruneCellX = pruneCellX;
            lastPruneCellZ = pruneCellZ;
            residencySelectionDirty = true;
            suppressionDirty = true;
            residencyComplete = false;
        }

        if (viewYawSector != lastViewYawSector
                || viewPitchSector != lastViewPitchSector) {
            lastViewYawSector = viewYawSector;
            lastViewPitchSector = viewPitchSector;
            residencySelectionDirty = true;
            residencyComplete = false;
        }

        if (residencySelectionDirty) {
            rebuildResidencyWanted(snapshot, camera);
            residencySelectionDirty = false;
            residencySelectionRebuildsThisFrame++;

            offscreenEvictionsThisFrame += trimOffscreenToTarget();
            if (offscreenEvictionsThisFrame > 0) {
                suppressionDirty = true;
            }
        }

        if (suppressionDirty) {
            SUPPRESSED_COARSE.clear();
            SUPPRESSED_COARSE.addAll(findCoveredCoarseKeys(
                    snapshot,
                    cameraX,
                    cameraZ
            ));
            coveredPrunedThisFrame += pruneCoveredCoarse(
                    SUPPRESSED_COARSE
            );
            suppressionDirty = false;
            suppressionRebuildsThisFrame++;
            residencyComplete = false;
        }

        if (!residencyComplete) {
            boolean sawMissing = false;

            for (WorldgenSurfaceTile tile : UPLOAD_ORDER) {
                LodTileKey key = new LodTileKey(
                        tile.lodLevel(),
                        tile.tileX(),
                        tile.tileZ()
                );

                if (!RESIDENCY_WANTED.contains(key)
                        || SUPPRESSED_COARSE.contains(key)) {
                    continue;
                }

                GpuTile existing = TILES.get(key);
                if (existing != null
                        && existing.source() == tile
                        && !existing.vertexBuffer().isClosed()) {
                    continue;
                }

                // The selection reserves room for estimated ownership splits.
                // Do not upload a lower-priority mesh merely to force it back
                // out of the cache on this very frame.
                if (existing == null && !RESIDENCY_PINNED.contains(key)
                        && residentBytes >= TARGET_GPU_BYTES) {
                    continue;
                }
                sawMissing = true;
                if (uploadsRemaining <= 0) {
                    break;
                }

                long started = System.nanoTime();
                GpuTile uploaded = upload(tile);
                uploadNanosThisFrame += System.nanoTime() - started;
                uploadsThisFrame++;
                uploadsRemaining--;

                if (existing != null) {
                    removeResident(existing);
                }

                TILES.put(key, uploaded);
                residencyRevision++;
                residentBytes += uploaded.bytes();
            }

            if (uploadsThisFrame > 0
                    || staleRemovedThisFrame > 0) {
                suppressionDirty = true;
            }

            residencyComplete = !sawMissing;
        }

        offscreenEvictionsThisFrame += trimOffscreenToTarget();
        trimHardLimit();

        if (offscreenEvictionsThisFrame > 0
                || forcedEvictionsThisFrame > 0) {
            suppressionDirty = true;
            residencyComplete = false;
        }

        prepareNanosThisFrame = System.nanoTime() - prepareStarted;
    }

    private static void rebuildResidencyWanted(
            WorldgenSurfaceSnapshot snapshot,
            Camera camera
    ) {
        RESIDENCY_WANTED.clear();
        RESIDENCY_PINNED.clear();
        UPLOAD_ORDER.clear();

        var cameraPos = camera.position();
        double cameraX = cameraPos.x();
        double cameraZ = cameraPos.z();
        var frustum = camera.getCullFrustum();
        long now = System.nanoTime();

        List<ResidencyCandidate> candidates = new ArrayList<>(snapshot.tiles().size());
        for (WorldgenSurfaceTile tile : snapshot.tiles()) {
            LodTileKey key = new LodTileKey(
                    tile.lodLevel(),
                    tile.tileX(),
                    tile.tileZ()
            );

            double nearestX = Math.max(
                    tile.minX(),
                    Math.min(cameraX, tile.maxX())
            );
            double nearestZ = Math.max(
                    tile.minZ(),
                    Math.min(cameraZ, tile.maxZ())
            );
            double nearest = Math.hypot(
                    nearestX - cameraX,
                    nearestZ - cameraZ
            );

            // Keep the immediate LOD world resident independent of camera yaw.
            // L3 is deliberately the permanent 360-degree safety surface.
            boolean pinned = nearest <= RESIDENCY_NEAR_RADIUS_BLOCKS
                    || tile.lodLevel() == 3
                            && nearest <= TURN_STABLE_L3_RADIUS_BLOCKS
                    || tile.lodLevel() == 6;

            AABB bounds = new AABB(
                    tile.minX(),
                    tile.minY() - 8.0,
                    tile.minZ(),
                    tile.maxX(),
                    tile.maxY() + 8.0,
                    tile.maxZ()
            ).inflate(
                    RESIDENCY_FRUSTUM_MARGIN_BLOCKS,
                    64.0D,
                    RESIDENCY_FRUSTUM_MARGIN_BLOCKS
            );

            boolean visible = frustum.isVisible(bounds);
            if (visible && tile.lodLevel() <= 2) {
                LAST_VIEW_WANTED_NANOS.put(key, now);
            }

            Long lastWanted = LAST_VIEW_WANTED_NANOS.get(key);
            boolean recent = tile.lodLevel() <= 2
                    && lastWanted != null
                    && now - lastWanted <= VIEW_RESIDENCY_HOLD_NANOS;
            if (pinned || visible || recent) {
                // Admit the global safety floor, then the turn shield,
                // before any fine detail. A dense exact scene can exceed
                // the target even with all near tiles alone, so "pinned"
                // means protected only after actual budget admission.
                int priority = tile.lodLevel() == 6 ? 0
                        : tile.lodLevel() == 3 && pinned ? 1
                        : pinned ? 2
                        : visible && tile.lodLevel() >= 3 ? 3
                        : visible ? 4 : 5;
                // Within a tier, preserve already uploaded tiles before
                // allocating a fresh direction. This prevents 180-degree
                // turns from continuously evicting each other's exact meshes.
                GpuTile resident = TILES.get(key);
                int existingRank = resident == null ? 1 : 0;
                long estimatedBytes = resident == null
                        ? Math.max(64L * 1024L,
                                tile.vertexCount() * 32L)
                        : resident.bytes();
                candidates.add(new ResidencyCandidate(
                        tile, key, pinned, priority, existingRank,
                        nearest, estimatedBytes
                ));
            }
        }

        candidates.sort(Comparator
                .comparingInt(ResidencyCandidate::priority)
                .thenComparingInt(ResidencyCandidate::existingRank)
                .thenComparingDouble(ResidencyCandidate::distance));
        long selectedBytes = 0L;
        for (ResidencyCandidate candidate : candidates) {
            if ((!UPLOAD_ORDER.isEmpty()
                    && selectedBytes + candidate.estimatedBytes()
                            > TARGET_GPU_BYTES)
                    || UPLOAD_ORDER.size() >= TARGET_GPU_TILES) {
                continue;
            }
            selectedBytes += candidate.estimatedBytes();
            RESIDENCY_WANTED.add(candidate.key());
            UPLOAD_ORDER.add(candidate.tile());
            if (candidate.pinned()) {
                RESIDENCY_PINNED.add(candidate.key());
            }
        }

        LAST_VIEW_WANTED_NANOS.entrySet().removeIf(entry ->
                now - entry.getValue()
                                > VIEW_RESIDENCY_HOLD_NANOS
                                        * 2L
        );
    }

    private static int trimOffscreenToTarget() {
        if (TILES.size() <= TARGET_GPU_TILES
                && residentBytes <= TARGET_GPU_BYTES) {
            return 0;
        }

        int removed = 0;
        Iterator<Map.Entry<LodTileKey, GpuTile>> iterator =
                TILES.entrySet().iterator();

        while ((TILES.size() > TARGET_GPU_TILES
                        || residentBytes > TARGET_GPU_BYTES)
                && iterator.hasNext()) {
            Map.Entry<LodTileKey, GpuTile> entry = iterator.next();

            if (RESIDENCY_WANTED.contains(entry.getKey())) {
                continue;
            }

            removeResident(entry.getValue());
            iterator.remove();
            removed++;
        }

        return removed;
    }

    private static void trimHardLimit() {
        if (TILES.size() <= MAX_GPU_TILES
                && residentBytes <= MAX_GPU_BYTES) {
            return;
        }

        Iterator<Map.Entry<LodTileKey, GpuTile>> iterator =
                TILES.entrySet().iterator();

        while ((TILES.size() > MAX_GPU_TILES
                        || residentBytes > MAX_GPU_BYTES)
                && iterator.hasNext()) {
            Map.Entry<LodTileKey, GpuTile> entry = iterator.next();

            if (RESIDENCY_PINNED.contains(entry.getKey())) {
                continue;
            }

            removeResident(entry.getValue());
            iterator.remove();
            forcedEvictionsThisFrame++;
        }

        if (TILES.size() <= MAX_GPU_TILES
                && residentBytes <= MAX_GPU_BYTES) {
            return;
        }

        iterator = TILES.entrySet().iterator();
        while ((TILES.size() > MAX_GPU_TILES
                        || residentBytes > MAX_GPU_BYTES)
                && iterator.hasNext()) {
            Map.Entry<LodTileKey, GpuTile> entry = iterator.next();
            // If an unusually dense scene exceeds even the hard limit,
            // release near detail before touching the L3/L6 safety layers.
            int level = entry.getKey().level();
            if (level == 3 || level == 6) {
                continue;
            }
            removeResident(entry.getValue());
            iterator.remove();
            forcedEvictionsThisFrame++;
        }

        iterator = TILES.entrySet().iterator();
        while ((TILES.size() > MAX_GPU_TILES
                        || residentBytes > MAX_GPU_BYTES)
                && iterator.hasNext()) {
            Map.Entry<LodTileKey, GpuTile> entry = iterator.next();
            removeResident(entry.getValue());
            iterator.remove();
            forcedEvictionsThisFrame++;
        }
    }

    private static long snapshotFingerprint(
            WorldgenSurfaceSnapshot snapshot
    ) {
        long hash = 0xcbf29ce484222325L;

        for (WorldgenSurfaceTile tile : snapshot.tiles()) {
            hash ^= tile.lodLevel();
            hash *= 0x100000001b3L;
            hash ^= tile.tileX();
            hash *= 0x100000001b3L;
            hash ^= tile.tileZ();
            hash *= 0x100000001b3L;
            hash ^= System.identityHashCode(tile);
            hash *= 0x100000001b3L;
        }

        hash ^= snapshot.tiles().size();
        hash *= 0x100000001b3L;
        return hash;
    }

    private static void rebuildActiveKeys(
            WorldgenSurfaceSnapshot snapshot
    ) {
        ACTIVE_KEYS.clear();
        long now = System.nanoTime();

        for (WorldgenSurfaceTile tile : snapshot.tiles()) {
            LodTileKey key = new LodTileKey(
                    tile.lodLevel(),
                    tile.tileX(),
                    tile.tileZ()
            );
            ACTIVE_KEYS.add(key);
            LAST_ACTIVE_NANOS.put(key, now);
        }
        LAST_ACTIVE_NANOS.entrySet().removeIf(entry ->
                now - entry.getValue() > VIEW_RESIDENCY_HOLD_NANOS * 2L);
    }

    private static void removeStaleResidents() {
        Iterator<Map.Entry<LodTileKey, GpuTile>> iterator =
                TILES.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<LodTileKey, GpuTile> entry = iterator.next();
            if (ACTIVE_KEYS.contains(entry.getKey())
                    || System.nanoTime() - LAST_ACTIVE_NANOS.getOrDefault(
                            entry.getKey(), 0L) <= VIEW_RESIDENCY_HOLD_NANOS) {
                continue;
            }

            removeResident(entry.getValue());
            iterator.remove();
            staleRemovedThisFrame++;
        }
    }

    private static Set<LodTileKey> findCoveredCoarseKeys(
            WorldgenSurfaceSnapshot snapshot,
            double cameraX,
            double cameraZ
    ) {
        Map<Integer, Set<Long>> residentByLevel = new HashMap<>();

        for (WorldgenSurfaceTile tile : snapshot.tiles()) {
            LodTileKey key = new LodTileKey(
                    tile.lodLevel(),
                    tile.tileX(),
                    tile.tileZ()
            );
            GpuTile resident = TILES.get(key);

            if (resident != null
                    && !resident.vertexBuffer().isClosed()) {
                residentByLevel
                        .computeIfAbsent(
                                tile.lodLevel(),
                                ignored -> new HashSet<>()
                        )
                        .add(packTile(tile.tileX(), tile.tileZ()));
            }
        }

        Set<LodTileKey> covered = new HashSet<>();

        for (WorldgenSurfaceTile tile : snapshot.tiles()) {
            // M9.1: L3 is the 360-degree turn shield. Never suppress/prune it
            // merely because finer L1/L2 is resident in the current view.
            // Keeping L3 uploaded lets the renderer reveal a ready fallback
            // immediately during a 180/360 turn instead of showing sky while
            // view-specific fine meshes upload again.
            if (tile.lodLevel() <= 1 || tile.lodLevel() == 3
                    || RESIDENCY_PINNED.contains(new LodTileKey(
                            tile.lodLevel(), tile.tileX(), tile.tileZ()))) {
                continue;
            }

            if (isFullyCoveredByFiner(
                    tile,
                    snapshot,
                    residentByLevel,
                    cameraX,
                    cameraZ
            )) {
                covered.add(new LodTileKey(
                        tile.lodLevel(),
                        tile.tileX(),
                        tile.tileZ()
                ));
            }
        }

        return covered;
    }

    private static int pruneCoveredCoarse(
            Set<LodTileKey> covered
    ) {
        int removed = 0;
        Iterator<Map.Entry<LodTileKey, GpuTile>> iterator =
                TILES.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<LodTileKey, GpuTile> entry = iterator.next();

            if (!covered.contains(entry.getKey())
                    || RESIDENCY_WANTED.contains(entry.getKey())) {
                continue;
            }

            removeResident(entry.getValue());
            iterator.remove();
            removed++;
        }

        return removed;
    }

    private static boolean isFullyCoveredByFiner(
            WorldgenSurfaceTile coarse,
            WorldgenSurfaceSnapshot snapshot,
            Map<Integer, Set<Long>> residentByLevel,
            double cameraX,
            double cameraZ
    ) {
        int level = coarse.lodLevel();

        // L4-L6 may be retired directly by the L3 shield, mirroring the
        // renderer's ownership rule. This is the largest residency win once
        // coverage is nearly complete.
        if (level >= 4) {
            WorldgenLodRing l3 = snapshot.ringForLevel(3);
            Set<Long> l3Tiles = residentByLevel.get(3);
            if (l3 != null
                    && l3Tiles != null
                    && fullyCoveredByRing(
                            coarse,
                            l3,
                            l3Tiles,
                            cameraX,
                            cameraZ
                    )) {
                return true;
            }
        }

        WorldgenLodRing finer = snapshot.ringForLevel(level - 1);
        Set<Long> finerTiles = residentByLevel.get(level - 1);

        return finer != null
                && finerTiles != null
                && fullyCoveredByRing(
                        coarse,
                        finer,
                        finerTiles,
                        cameraX,
                        cameraZ
                );
    }

    private static boolean fullyCoveredByRing(
            WorldgenSurfaceTile coarse,
            WorldgenLodRing finer,
            Set<Long> residentFiner,
            double cameraX,
            double cameraZ
    ) {
        int size = finer.tileSize();
        int minTileX = Math.floorDiv(coarse.minX(), size);
        int minTileZ = Math.floorDiv(coarse.minZ(), size);
        int maxTileX = Math.floorDiv(coarse.maxX() - 1, size);
        int maxTileZ = Math.floorDiv(coarse.maxZ() - 1, size);

        for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
            for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                if (!stableRingOwnership(
                        tileX,
                        tileZ,
                        finer,
                        cameraX,
                        cameraZ
                )
                        || !residentFiner.contains(
                                packTile(tileX, tileZ)
                        )) {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean stableRingOwnership(
            int tileX,
            int tileZ,
            WorldgenLodRing ring,
            double cameraX,
            double cameraZ
    ) {
        double minX = tileX * (double) ring.tileSize();
        double minZ = tileZ * (double) ring.tileSize();
        double maxX = minX + ring.tileSize();
        double maxZ = minZ + ring.tileSize();

        double nearestX = Math.max(minX, Math.min(cameraX, maxX));
        double nearestZ = Math.max(minZ, Math.min(cameraZ, maxZ));
        double nearest = Math.hypot(
                nearestX - cameraX,
                nearestZ - cameraZ
        );

        double farthest = Math.max(
                Math.max(
                        Math.hypot(minX - cameraX, minZ - cameraZ),
                        Math.hypot(maxX - cameraX, minZ - cameraZ)
                ),
                Math.max(
                        Math.hypot(minX - cameraX, maxZ - cameraZ),
                        Math.hypot(maxX - cameraX, maxZ - cameraZ)
                )
        );

        double inner = ring.innerRadiusBlocks();
        double outer = ring.outerRadiusBlocks();

        // Leave a fallback belt at moving ring boundaries so residency pruning
        // never turns into visible pop/sky while the camera crosses a tier.
        boolean outsideInner = inner <= 0.0D
                || nearest
                        >= inner + PRUNE_RING_MARGIN_BLOCKS;
        boolean insideOuter = farthest
                <= Math.max(
                        0.0D,
                        outer - PRUNE_RING_MARGIN_BLOCKS
                );

        return outsideInner && insideOuter;
    }

    private static long packTile(int tileX, int tileZ) {
        return ((long) tileX << 32)
                ^ (tileZ & 0xFFFF_FFFFL);
    }

    private static void removeResident(GpuTile tile) {
        residentBytes -= tile.bytes();
        tile.close();
        residencyRevision++;
    }

    public static long residencyRevision() {
        return residencyRevision;
    }

    public static GpuTile getResident(WorldgenSurfaceTile tile) {
        LodTileKey key = new LodTileKey(tile.lodLevel(), tile.tileX(), tile.tileZ());
        GpuTile existing = TILES.get(key);

        if (existing == null
                || existing.vertexBuffer().isClosed()) {
            return null;
        }

        // M7.4 deliberately allows a same-key older GPU mesh to remain visible
        // while the current CPU tile waits for its view-aware replacement
        // upload. M7.3 treated source-identity mismatch as "not resident",
        // producing a visible unload/reload flash after a 180/360-degree turn.
        return existing;
    }

    public static Stats stats() {
        return new Stats(
                TILES.size(),
                residentBytes,
                uploadsThisFrame,
                uploadNanosThisFrame / 1_000_000.0,
                prepareNanosThisFrame / 1_000_000.0,
                staleRemovedThisFrame,
                coveredPrunedThisFrame,
                forcedEvictionsThisFrame,
                suppressionRebuildsThisFrame,
                residencySelectionRebuildsThisFrame,
                offscreenEvictionsThisFrame,
                SUPPRESSED_COARSE.size(),
                RESIDENCY_WANTED.size(),
                residencyComplete
        );
    }

    public static void clear() {
        for (GpuTile tile : TILES.values()) {
            tile.close();
        }

        TILES.clear();
        residencyRevision++;
        ACTIVE_KEYS.clear();
        RESIDENCY_WANTED.clear();
        RESIDENCY_PINNED.clear();
        UPLOAD_ORDER.clear();
        SUPPRESSED_COARSE.clear();
        LAST_VIEW_WANTED_NANOS.clear();
        LAST_ACTIVE_NANOS.clear();
        lastSnapshotReference = null;
        lastSnapshotFingerprint = Long.MIN_VALUE;
        lastPruneCellX = Integer.MIN_VALUE;
        lastPruneCellZ = Integer.MIN_VALUE;
        lastViewYawSector = Integer.MIN_VALUE;
        lastViewPitchSector = Integer.MIN_VALUE;
        residencySelectionDirty = true;
        suppressionDirty = true;
        residencyComplete = false;
        residentBytes = 0L;
        uploadsRemaining = 0;
        uploadsThisFrame = 0;
        uploadNanosThisFrame = 0L;
        prepareNanosThisFrame = 0L;
        suppressionRebuildsThisFrame = 0;
        residencySelectionRebuildsThisFrame = 0;
        offscreenEvictionsThisFrame = 0;
    }

    /**
     * M9.5 region batching reuses the exact M9.4 ownership-splitting path.
     * The returned vertices are relative to the caller's spatial region
     * origin, so several logical tiles can share one persistent GPU buffer
     * without changing terrain topology or vanilla/finer ownership metadata.
     */
    static PreparedGeometry prepareRegionGeometry(
            WorldgenSurfaceTile tile,
            int regionOriginX,
            int regionOriginZ,
            int firstIndexBase
    ) {
        WorldgenSurfaceTile.Geometry geometry = tile.geometry();
        int[] vertices = geometry.vertices();
        int[] colors = geometry.colors();

        Map<BatchKey, List<QuadPiece>> groupedQuads =
                new LinkedHashMap<>();
        int emittedQuadCount = 0;

        for (int quadOffset = 0;
                quadOffset < vertices.length;
                quadOffset += 12) {
            int regionSize = switch (tile.lodLevel()) {
                case 4 -> L4_UNDERLAY_REGION_SIZE;
                case 5 -> L5_UNDERLAY_REGION_SIZE;
                case 6 -> L6_UNDERLAY_REGION_SIZE;
                default -> 0;
            };

            List<QuadPiece> pieces;
            if (tile.lodLevel() <= 3) {
                pieces = splitQuadForOwnership(
                        vertices,
                        colors,
                        quadOffset
                );
            } else if (regionSize > 0) {
                pieces = splitQuadForUnderlayRegion(
                        vertices,
                        colors,
                        quadOffset,
                        regionSize
                );
            } else {
                pieces = List.of(copyQuad(
                        vertices,
                        colors,
                        quadOffset
                ));
            }

            emittedQuadCount += pieces.size();

            for (QuadPiece piece : pieces) {
                BatchKey key;

                if (tile.lodLevel() <= 2) {
                    key = classifyBatch(piece.vertices(), 0);
                } else if (tile.lodLevel() == 3) {
                    key = classifyUnderlayBatch(
                            piece.vertices(),
                            0,
                            L3_UNDERLAY_REGION_SIZE,
                            true
                    );
                } else if (tile.lodLevel() == 4) {
                    key = classifyUnderlayBatch(
                            piece.vertices(),
                            0,
                            L4_UNDERLAY_REGION_SIZE,
                            false
                    );
                } else if (tile.lodLevel() == 5) {
                    key = classifyUnderlayBatch(
                            piece.vertices(),
                            0,
                            L5_UNDERLAY_REGION_SIZE,
                            false
                    );
                } else if (tile.lodLevel() == 6) {
                    key = classifyUnderlayBatch(
                            piece.vertices(),
                            0,
                            L6_UNDERLAY_REGION_SIZE,
                            false
                    );
                } else {
                    key = BatchKey.ALWAYS;
                }

                groupedQuads.computeIfAbsent(
                        key,
                        ignored -> new ArrayList<>()
                ).add(piece);
            }
        }

        int[] outVertices = new int[emittedQuadCount * 12];
        int[] outColors = new int[emittedQuadCount * 4];
        List<DrawBatch> drawBatches =
                new ArrayList<>(groupedQuads.size());

        int vertexInt = 0;
        int vertex = 0;
        int firstIndex = firstIndexBase;

        for (Map.Entry<BatchKey, List<QuadPiece>> entry
                : groupedQuads.entrySet()) {
            BatchKey key = entry.getKey();
            List<QuadPiece> pieces = entry.getValue();

            for (QuadPiece piece : pieces) {
                int[] quadVertices = piece.vertices();
                int[] quadColors = piece.colors();

                for (int v = 0; v < 4; v++) {
                    int i = v * 3;
                    outVertices[vertexInt++] =
                            quadVertices[i] - regionOriginX;
                    outVertices[vertexInt++] =
                            quadVertices[i + 1];
                    outVertices[vertexInt++] =
                            quadVertices[i + 2] - regionOriginZ;
                    outColors[vertex++] = quadColors[v];
                }
            }

            int indexCount = pieces.size() * 6;
            drawBatches.add(new DrawBatch(
                    firstIndex,
                    indexCount,
                    key.vanillaSensitive(),
                    key.surface(),
                    key.chunkAX(),
                    key.chunkAZ(),
                    key.sectionY(),
                    key.boundary(),
                    key.chunkBX(),
                    key.chunkBZ(),
                    key.underlayRegion(),
                    key.regionTileX(),
                    key.regionTileZ()
            ));
            firstIndex += indexCount;
        }

        return new PreparedGeometry(
                tile,
                outVertices,
                outColors,
                emittedQuadCount * 6,
                List.copyOf(drawBatches)
        );
    }

    private static GpuTile upload(WorldgenSurfaceTile tile) {
        VertexFormat format = DefaultVertexFormat.POSITION_COLOR;
        WorldgenSurfaceTile.Geometry geometry = tile.geometry();
        int[] vertices = geometry.vertices();
        int[] colors = geometry.colors();
        int originX = tile.minX();
        int originZ = tile.minZ();

        Map<BatchKey, List<QuadPiece>> groupedQuads = new LinkedHashMap<>();
        int emittedQuadCount = 0;

        for (int quadOffset = 0; quadOffset < vertices.length; quadOffset += 12) {
            int regionSize = switch (tile.lodLevel()) {
                case 4 -> L4_UNDERLAY_REGION_SIZE;
                case 5 -> L5_UNDERLAY_REGION_SIZE;
                case 6 -> L6_UNDERLAY_REGION_SIZE;
                default -> 0;
            };

            List<QuadPiece> pieces;
            if (tile.lodLevel() <= 3) {
                pieces = splitQuadForOwnership(
                        vertices,
                        colors,
                        quadOffset
                );
            } else if (regionSize > 0) {
                pieces = splitQuadForUnderlayRegion(
                        vertices,
                        colors,
                        quadOffset,
                        regionSize
                );
            } else {
                pieces = List.of(copyQuad(
                        vertices,
                        colors,
                        quadOffset
                ));
            }

            emittedQuadCount += pieces.size();

            for (QuadPiece piece : pieces) {
                BatchKey key;

                if (tile.lodLevel() <= 2) {
                    key = classifyBatch(piece.vertices(), 0);
                } else if (tile.lodLevel() == 3) {
                    key = classifyUnderlayBatch(
                            piece.vertices(),
                            0,
                            L3_UNDERLAY_REGION_SIZE,
                            true
                    );
                } else if (tile.lodLevel() == 4) {
                    key = classifyUnderlayBatch(
                            piece.vertices(),
                            0,
                            L4_UNDERLAY_REGION_SIZE,
                            false
                    );
                } else if (tile.lodLevel() == 5) {
                    key = classifyUnderlayBatch(
                            piece.vertices(),
                            0,
                            L5_UNDERLAY_REGION_SIZE,
                            false
                    );
                } else if (tile.lodLevel() == 6) {
                    key = classifyUnderlayBatch(
                            piece.vertices(),
                            0,
                            L6_UNDERLAY_REGION_SIZE,
                            false
                    );
                } else {
                    key = BatchKey.ALWAYS;
                }

                groupedQuads.computeIfAbsent(
                        key,
                        ignored -> new ArrayList<>()
                ).add(piece);
            }
        }

        int vertexCount = emittedQuadCount * 4;
        int bytes = Math.multiplyExact(format.getVertexSize(), vertexCount);
        List<DrawBatch> drawBatches = new ArrayList<>(groupedQuads.size());

        try (ByteBufferBuilder byteBuffer = ByteBufferBuilder.exactlySized(bytes)) {
            BufferBuilder builder = new BufferBuilder(
                    byteBuffer,
                    PrimitiveTopology.QUADS,
                    format
            );

            int firstIndex = 0;

            for (Map.Entry<BatchKey, List<QuadPiece>> entry : groupedQuads.entrySet()) {
                BatchKey key = entry.getKey();
                List<QuadPiece> pieces = entry.getValue();

                for (QuadPiece piece : pieces) {
                    int[] quadVertices = piece.vertices();
                    int[] quadColors = piece.colors();

                    for (int v = 0; v < 4; v++) {
                        int i = v * 3;
                        int rgb = quadColors[v];

                        builder.addVertex(
                                        quadVertices[i] - originX,
                                        quadVertices[i + 1],
                                        quadVertices[i + 2] - originZ
                                )
                                .setColor(
                                        (rgb >> 16) & 0xFF,
                                        (rgb >> 8) & 0xFF,
                                        rgb & 0xFF,
                                        255
                                );
                    }
                }

                int indexCount = pieces.size() * 6;
                drawBatches.add(new DrawBatch(
                        firstIndex,
                        indexCount,
                        key.vanillaSensitive(),
                        key.surface(),
                        key.chunkAX(),
                        key.chunkAZ(),
                        key.sectionY(),
                        key.boundary(),
                        key.chunkBX(),
                        key.chunkBZ(),
                        key.underlayRegion(),
                        key.regionTileX(),
                        key.regionTileZ()
                ));
                firstIndex += indexCount;
            }

            try (MeshData mesh = builder.buildOrThrow()) {
                GpuBuffer vertexBuffer = RenderSystem.getDevice().createBuffer(
                        () -> "Everview LOD tile L" + tile.lodLevel()
                                + " " + tile.tileX() + "," + tile.tileZ(),
                        GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_VERTEX,
                        mesh.vertexBuffer()
                );

                return new GpuTile(
                        tile,
                        vertexBuffer,
                        mesh.drawState().indexCount(),
                        bytes,
                        List.copyOf(drawBatches)
                );
            }
        }
    }

    private static List<QuadPiece> splitQuadForOwnership(
            int[] vertices,
            int[] colors,
            int quadOffset
    ) {
        List<QuadPiece> sectionPieces =
                splitNearQuadBySection(vertices, colors, quadOffset);
        List<QuadPiece> ownedPieces = new ArrayList<>();

        for (QuadPiece piece : sectionPieces) {
            ownedPieces.addAll(splitPieceByChunkColumns(piece));
        }

        return ownedPieces;
    }

    private static List<QuadPiece> splitQuadForUnderlayRegion(
            int[] vertices,
            int[] colors,
            int quadOffset,
            int regionSize
    ) {
        return splitPieceByRegionGrid(
                copyQuad(vertices, colors, quadOffset),
                regionSize
        );
    }

    private static List<QuadPiece> splitPieceByRegionGrid(
            QuadPiece piece,
            int regionSize
    ) {
        int[] vertices = piece.vertices();
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (int v = 0; v < 4; v++) {
            int i = v * 3;
            minX = Math.min(minX, vertices[i]);
            maxX = Math.max(maxX, vertices[i]);
            minY = Math.min(minY, vertices[i + 1]);
            maxY = Math.max(maxY, vertices[i + 1]);
            minZ = Math.min(minZ, vertices[i + 2]);
            maxZ = Math.max(maxZ, vertices[i + 2]);
        }

        boolean surface = minX < maxX && minZ < maxZ;
        boolean xWall = minX == maxX
                && minY < maxY
                && minZ < maxZ;
        boolean zWall = minZ == maxZ
                && minY < maxY
                && minX < maxX;

        if (!surface && !xWall && !zWall) {
            return List.of(piece);
        }

        List<QuadPiece> result = new ArrayList<>();

        if (surface) {
            int x0 = minX;
            while (x0 < maxX) {
                int x1 = Math.min(
                        maxX,
                        nextGridBoundary(x0, regionSize)
                );
                int z0 = minZ;

                while (z0 < maxZ) {
                    int z1 = Math.min(
                            maxZ,
                            nextGridBoundary(z0, regionSize)
                    );
                    result.add(splitSurfacePiece(
                            piece,
                            minX,
                            maxX,
                            minZ,
                            maxZ,
                            x0,
                            x1,
                            z0,
                            z1
                    ));
                    z0 = z1;
                }

                x0 = x1;
            }

            return result;
        }

        if (xWall) {
            int z0 = minZ;
            while (z0 < maxZ) {
                int z1 = Math.min(
                        maxZ,
                        nextGridBoundary(z0, regionSize)
                );
                result.add(remapAxisAlignedPiece(
                        piece,
                        minX, maxX,
                        minY, maxY,
                        minZ, maxZ,
                        minX, maxX,
                        minY, maxY,
                        z0, z1
                ));
                z0 = z1;
            }

            return result;
        }

        int x0 = minX;
        while (x0 < maxX) {
            int x1 = Math.min(
                    maxX,
                    nextGridBoundary(x0, regionSize)
            );
            result.add(remapAxisAlignedPiece(
                    piece,
                    minX, maxX,
                    minY, maxY,
                    minZ, maxZ,
                    x0, x1,
                    minY, maxY,
                    minZ, maxZ
            ));
            x0 = x1;
        }

        return result;
    }

    private static int nextGridBoundary(
            int coordinate,
            int gridSize
    ) {
        return (Math.floorDiv(coordinate, gridSize) + 1)
                * gridSize;
    }

    private static List<QuadPiece> splitPieceByChunkColumns(
            QuadPiece piece
    ) {
        int[] vertices = piece.vertices();
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (int v = 0; v < 4; v++) {
            int i = v * 3;
            minX = Math.min(minX, vertices[i]);
            maxX = Math.max(maxX, vertices[i]);
            minY = Math.min(minY, vertices[i + 1]);
            maxY = Math.max(maxY, vertices[i + 1]);
            minZ = Math.min(minZ, vertices[i + 2]);
            maxZ = Math.max(maxZ, vertices[i + 2]);
        }

        // A terrain top is identified by spanning both horizontal axes.
        // Its four corner heights are allowed to differ. M4 treated those
        // sloped tops as "unexpected" and they escaped vanilla ownership.
        boolean surface = minX < maxX && minZ < maxZ;
        boolean xWall = minX == maxX
                && minY < maxY
                && minZ < maxZ;
        boolean zWall = minZ == maxZ
                && minY < maxY
                && minX < maxX;

        if (!surface && !xWall && !zWall) {
            return List.of(piece);
        }

        List<QuadPiece> result = new ArrayList<>();

        if (surface) {
            int x0 = minX;
            while (x0 < maxX) {
                int x1 = Math.min(maxX, nextChunkBoundary(x0));
                int z0 = minZ;

                while (z0 < maxZ) {
                    int z1 = Math.min(maxZ, nextChunkBoundary(z0));
                    result.add(splitSurfacePiece(
                            piece,
                            minX,
                            maxX,
                            minZ,
                            maxZ,
                            x0,
                            x1,
                            z0,
                            z1
                    ));
                    z0 = z1;
                }
                x0 = x1;
            }

            return result;
        }

        if (xWall) {
            int z0 = minZ;
            while (z0 < maxZ) {
                int z1 = Math.min(maxZ, nextChunkBoundary(z0));
                result.add(remapAxisAlignedPiece(
                        piece,
                        minX, maxX,
                        minY, maxY,
                        minZ, maxZ,
                        minX, maxX,
                        minY, maxY,
                        z0, z1
                ));
                z0 = z1;
            }

            return result;
        }

        int x0 = minX;
        while (x0 < maxX) {
            int x1 = Math.min(maxX, nextChunkBoundary(x0));
            result.add(remapAxisAlignedPiece(
                    piece,
                    minX, maxX,
                    minY, maxY,
                    minZ, maxZ,
                    x0, x1,
                    minY, maxY,
                    minZ, maxZ
            ));
            x0 = x1;
        }

        return result;
    }

    private static QuadPiece splitSurfacePiece(
            QuadPiece source,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            int x0,
            int x1,
            int z0,
            int z1
    ) {
        int[] vertices = source.vertices();
        int[] colors = source.colors();

        int y00 = surfaceCornerY(vertices, minX, minZ);
        int y01 = surfaceCornerY(vertices, minX, maxZ);
        int y11 = surfaceCornerY(vertices, maxX, maxZ);
        int y10 = surfaceCornerY(vertices, maxX, minZ);

        int c00 = surfaceCornerColor(vertices, colors, minX, minZ);
        int c01 = surfaceCornerColor(vertices, colors, minX, maxZ);
        int c11 = surfaceCornerColor(vertices, colors, maxX, maxZ);
        int c10 = surfaceCornerColor(vertices, colors, maxX, minZ);

        double tx0 = (x0 - minX) / (double) Math.max(1, maxX - minX);
        double tx1 = (x1 - minX) / (double) Math.max(1, maxX - minX);
        double tz0 = (z0 - minZ) / (double) Math.max(1, maxZ - minZ);
        double tz1 = (z1 - minZ) / (double) Math.max(1, maxZ - minZ);

        int[] outVertices = new int[] {
                x0, bilerpInt(y00, y10, y01, y11, tx0, tz0), z0,
                x0, bilerpInt(y00, y10, y01, y11, tx0, tz1), z1,
                x1, bilerpInt(y00, y10, y01, y11, tx1, tz1), z1,
                x1, bilerpInt(y00, y10, y01, y11, tx1, tz0), z0
        };

        int[] outColors = new int[] {
                bilerpColor(c00, c10, c01, c11, tx0, tz0),
                bilerpColor(c00, c10, c01, c11, tx0, tz1),
                bilerpColor(c00, c10, c01, c11, tx1, tz1),
                bilerpColor(c00, c10, c01, c11, tx1, tz0)
        };

        return new QuadPiece(outVertices, outColors);
    }

    private static int surfaceCornerY(
            int[] vertices,
            int x,
            int z
    ) {
        for (int v = 0; v < 4; v++) {
            int i = v * 3;
            if (vertices[i] == x && vertices[i + 2] == z) {
                return vertices[i + 1];
            }
        }

        throw new IllegalStateException(
                "Everview surface quad missing expected corner"
        );
    }

    private static int surfaceCornerColor(
            int[] vertices,
            int[] colors,
            int x,
            int z
    ) {
        for (int v = 0; v < 4; v++) {
            int i = v * 3;
            if (vertices[i] == x && vertices[i + 2] == z) {
                return colors[v];
            }
        }

        throw new IllegalStateException(
                "Everview surface quad missing expected color corner"
        );
    }

    private static int bilerpInt(
            int c00,
            int c10,
            int c01,
            int c11,
            double tx,
            double tz
    ) {
        double north = c00 + (c10 - c00) * tx;
        double south = c01 + (c11 - c01) * tx;
        return (int) Math.round(north + (south - north) * tz);
    }

    private static int bilerpColor(
            int c00,
            int c10,
            int c01,
            int c11,
            double tx,
            double tz
    ) {
        int r = bilerpInt(
                (c00 >> 16) & 0xFF,
                (c10 >> 16) & 0xFF,
                (c01 >> 16) & 0xFF,
                (c11 >> 16) & 0xFF,
                tx,
                tz
        );
        int g = bilerpInt(
                (c00 >> 8) & 0xFF,
                (c10 >> 8) & 0xFF,
                (c01 >> 8) & 0xFF,
                (c11 >> 8) & 0xFF,
                tx,
                tz
        );
        int b = bilerpInt(
                c00 & 0xFF,
                c10 & 0xFF,
                c01 & 0xFF,
                c11 & 0xFF,
                tx,
                tz
        );

        return (r << 16) | (g << 8) | b;
    }

    private static int nextChunkBoundary(int coordinate) {
        return (Math.floorDiv(coordinate, 16) + 1) * 16;
    }

    private static QuadPiece remapAxisAlignedPiece(
            QuadPiece source,
            int oldMinX,
            int oldMaxX,
            int oldMinY,
            int oldMaxY,
            int oldMinZ,
            int oldMaxZ,
            int newMinX,
            int newMaxX,
            int newMinY,
            int newMaxY,
            int newMinZ,
            int newMaxZ
    ) {
        int[] oldVertices = source.vertices();
        int[] newVertices = new int[12];
        int[] newColors = source.colors().clone();

        for (int v = 0; v < 4; v++) {
            int i = v * 3;
            int x = oldVertices[i];
            int y = oldVertices[i + 1];
            int z = oldVertices[i + 2];

            newVertices[i] = oldMinX == oldMaxX
                    ? oldMinX
                    : (x == oldMinX ? newMinX : newMaxX);
            newVertices[i + 1] = oldMinY == oldMaxY
                    ? oldMinY
                    : (y == oldMinY ? newMinY : newMaxY);
            newVertices[i + 2] = oldMinZ == oldMaxZ
                    ? oldMinZ
                    : (z == oldMinZ ? newMinZ : newMaxZ);
        }

        return new QuadPiece(newVertices, newColors);
    }

    private static List<QuadPiece> splitNearQuadBySection(
            int[] vertices,
            int[] colors,
            int quadOffset
    ) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (int v = 0; v < 4; v++) {
            int y = vertices[quadOffset + v * 3 + 1];
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }

        // Horizontal faces already belong to one vertical section.
        if (minY == maxY) {
            return List.of(copyQuad(vertices, colors, quadOffset));
        }

        // Only axis-aligned vertical faces need splitting. Defensive fallback
        // keeps any unexpected quad intact instead of changing its geometry.
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (int v = 0; v < 4; v++) {
            int i = quadOffset + v * 3;
            minX = Math.min(minX, vertices[i]);
            maxX = Math.max(maxX, vertices[i]);
            minZ = Math.min(minZ, vertices[i + 2]);
            maxZ = Math.max(maxZ, vertices[i + 2]);
        }

        if (minX != maxX && minZ != maxZ) {
            return List.of(copyQuad(vertices, colors, quadOffset));
        }

        List<QuadPiece> pieces = new ArrayList<>();
        int sliceBottom = minY;

        while (sliceBottom < maxY) {
            int sectionTop = (Math.floorDiv(sliceBottom, 16) + 1) * 16;
            int sliceTop = Math.min(maxY, sectionTop);

            int[] pieceVertices = new int[12];
            int[] pieceColors = new int[4];

            for (int v = 0; v < 4; v++) {
                int source = quadOffset + v * 3;
                int target = v * 3;
                int sourceY = vertices[source + 1];

                pieceVertices[target] = vertices[source];
                pieceVertices[target + 1] =
                        sourceY == minY ? sliceBottom : sliceTop;
                pieceVertices[target + 2] = vertices[source + 2];
                pieceColors[v] = colors[quadOffset / 3 + v];
            }

            pieces.add(new QuadPiece(pieceVertices, pieceColors));
            sliceBottom = sliceTop;
        }

        return pieces;
    }

    private static QuadPiece copyQuad(
            int[] vertices,
            int[] colors,
            int quadOffset
    ) {
        int[] pieceVertices = new int[12];
        int[] pieceColors = new int[4];

        System.arraycopy(vertices, quadOffset, pieceVertices, 0, 12);
        System.arraycopy(colors, quadOffset / 3, pieceColors, 0, 4);

        return new QuadPiece(pieceVertices, pieceColors);
    }

    private record QuadPiece(
            int[] vertices,
            int[] colors
    ) {
    }

    private static BatchKey classifyBatch(int[] vertices, int quadOffset) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (int v = 0; v < 4; v++) {
            int i = quadOffset + v * 3;
            minX = Math.min(minX, vertices[i]);
            maxX = Math.max(maxX, vertices[i]);
            minY = Math.min(minY, vertices[i + 1]);
            maxY = Math.max(maxY, vertices[i + 1]);
            minZ = Math.min(minZ, vertices[i + 2]);
            maxZ = Math.max(maxZ, vertices[i + 2]);
        }

        // Terrain top face. Heights may differ at all four corners for smooth
        // distant terrain. Ownership depends on the X/Z footprint, not flat Y.
        // splitQuadForOwnership() already constrains the footprint to one
        // vanilla chunk column before this classifier runs.
        if (minX < maxX && minZ < maxZ) {
            int sampleX = minX + Math.max(0, (maxX - minX - 1) / 2);
            int sampleZ = minZ + Math.max(0, (maxZ - minZ - 1) / 2);
            int chunkX = Math.floorDiv(sampleX, 16);
            int chunkZ = Math.floorDiv(sampleZ, 16);
            int sectionY = Math.floorDiv(
                    (minY + maxY) / 2 - 1,
                    16
            );

            return BatchKey.surface(chunkX, chunkZ, sectionY);
        }

        // Vertical X wall. If it lies exactly on a vanilla chunk boundary,
        // remember both adjacent chunk columns. At draw time the wall is hidden
        // when EITHER vanilla side is actually renderer-visible, which prevents
        // the thin LOD walls seen inside vanilla chunks.
        if (minX == maxX && minZ < maxZ) {
            int sampleZ = minZ + Math.max(0, (maxZ - minZ - 1) / 2);
            int chunkZ = Math.floorDiv(sampleZ, 16);
            int sectionY = Math.floorDiv(maxY - 1, 16);

            if (Math.floorMod(minX, 16) == 0) {
                int rightChunkX = Math.floorDiv(minX, 16);
                return BatchKey.boundary(
                        rightChunkX - 1,
                        chunkZ,
                        rightChunkX,
                        chunkZ,
                        sectionY
                );
            }

            return BatchKey.wall(
                    Math.floorDiv(minX, 16),
                    chunkZ,
                    sectionY
            );
        }

        // Vertical Z wall, same rule as X.
        if (minZ == maxZ && minX < maxX) {
            int sampleX = minX + Math.max(0, (maxX - minX - 1) / 2);
            int chunkX = Math.floorDiv(sampleX, 16);
            int sectionY = Math.floorDiv(maxY - 1, 16);

            if (Math.floorMod(minZ, 16) == 0) {
                int southChunkZ = Math.floorDiv(minZ, 16);
                return BatchKey.boundary(
                        chunkX,
                        southChunkZ - 1,
                        chunkX,
                        southChunkZ,
                        sectionY
                );
            }

            return BatchKey.wall(
                    chunkX,
                    Math.floorDiv(minZ, 16),
                    sectionY
            );
        }

        // Defensive fallback for any unexpected near-ring quad shape.
        return BatchKey.ALWAYS;
    }

    private static BatchKey classifyUnderlayBatch(
            int[] vertices,
            int quadOffset,
            int regionSize,
            boolean vanillaSensitive
    ) {
        BatchKey vanillaKey = vanillaSensitive
                ? classifyBatch(vertices, quadOffset)
                : BatchKey.ALWAYS;

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (int v = 0; v < 4; v++) {
            int i = quadOffset + v * 3;
            minX = Math.min(minX, vertices[i]);
            maxX = Math.max(maxX, vertices[i]);
            minZ = Math.min(minZ, vertices[i + 2]);
            maxZ = Math.max(maxZ, vertices[i + 2]);
        }

        int sampleX = minX == maxX
                ? minX - (Math.floorMod(minX, regionSize) == 0
                        ? 1 : 0)
                : minX + Math.max(0, (maxX - minX - 1) / 2);
        int sampleZ = minZ == maxZ
                ? minZ - (Math.floorMod(minZ, regionSize) == 0
                        ? 1 : 0)
                : minZ + Math.max(0, (maxZ - minZ - 1) / 2);

        return vanillaKey.withUnderlayRegion(
                Math.floorDiv(sampleX, regionSize),
                Math.floorDiv(sampleZ, regionSize)
        );
    }

    private record BatchKey(
            boolean vanillaSensitive,
            boolean surface,
            int chunkAX,
            int chunkAZ,
            int sectionY,
            boolean boundary,
            int chunkBX,
            int chunkBZ,
            boolean underlayRegion,
            int regionTileX,
            int regionTileZ
    ) {
        private static final BatchKey ALWAYS =
                new BatchKey(
                        false, false, 0, 0, 0, false, 0, 0,
                        false, 0, 0
                );

        private static BatchKey surface(
                int chunkX,
                int chunkZ,
                int sectionY
        ) {
            return new BatchKey(
                    true,
                    true,
                    chunkX,
                    chunkZ,
                    sectionY,
                    false,
                    0,
                    0,
                    false,
                    0,
                    0
            );
        }

        private static BatchKey wall(
                int chunkX,
                int chunkZ,
                int sectionY
        ) {
            return new BatchKey(
                    true,
                    false,
                    chunkX,
                    chunkZ,
                    sectionY,
                    false,
                    0,
                    0,
                    false,
                    0,
                    0
            );
        }

        private static BatchKey boundary(
                int chunkAX,
                int chunkAZ,
                int chunkBX,
                int chunkBZ,
                int sectionY
        ) {
            return new BatchKey(
                    true,
                    false,
                    chunkAX,
                    chunkAZ,
                    sectionY,
                    true,
                    chunkBX,
                    chunkBZ,
                    false,
                    0,
                    0
            );
        }

        private static BatchKey underlayRegion(
                int regionTileX,
                int regionTileZ
        ) {
            return new BatchKey(
                    false,
                    false,
                    0,
                    0,
                    0,
                    false,
                    0,
                    0,
                    true,
                    regionTileX,
                    regionTileZ
            );
        }

        private BatchKey withUnderlayRegion(
                int regionTileX,
                int regionTileZ
        ) {
            return new BatchKey(
                    vanillaSensitive,
                    surface,
                    chunkAX,
                    chunkAZ,
                    sectionY,
                    boundary,
                    chunkBX,
                    chunkBZ,
                    true,
                    regionTileX,
                    regionTileZ
            );
        }
    }

    public record Stats(
            int bufferCount,
            long residentBytes,
            int uploadsThisFrame,
            double uploadMs,
            double prepareMs,
            int staleRemovedThisFrame,
            int coveredPrunedThisFrame,
            int forcedEvictionsThisFrame,
            int suppressionRebuildsThisFrame,
            int residencySelectionRebuildsThisFrame,
            int offscreenEvictionsThisFrame,
            int suppressedCoarseTiles,
            int residencyWantedTiles,
            boolean residencyComplete
    ) {
        public double residentMiB() {
            return residentBytes / (1024.0 * 1024.0);
        }
    }

    public record DrawBatch(
            int firstIndex,
            int indexCount,
            boolean vanillaSensitive,
            boolean surface,
            int chunkAX,
            int chunkAZ,
            int sectionY,
            boolean boundary,
            int chunkBX,
            int chunkBZ,
            boolean underlayRegion,
            int regionTileX,
            int regionTileZ
    ) {
    }

    static record PreparedGeometry(
            WorldgenSurfaceTile source,
            int[] vertices,
            int[] colors,
            int indexCount,
            List<DrawBatch> drawBatches
    ) {
    }

    public record GpuTile(
            WorldgenSurfaceTile source,
            GpuBuffer vertexBuffer,
            int indexCount,
            long bytes,
            List<DrawBatch> drawBatches
    ) implements AutoCloseable {
        @Override
        public void close() {
            if (!vertexBuffer.isClosed()) {
                vertexBuffer.close();
            }
        }
    }

    private record ResidencyCandidate(
            WorldgenSurfaceTile tile,
            LodTileKey key,
            boolean pinned,
            int priority,
            int existingRank,
            double distance,
            long estimatedBytes
    ) {
    }
}
