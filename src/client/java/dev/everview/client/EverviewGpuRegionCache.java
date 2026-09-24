package dev.everview.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import dev.everview.core.LodTileKey;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.*;

/** Persistent spatial residency. Visibility has no authority over lifetime or quality. */
public final class EverviewGpuRegionCache {
    private static final int REGION_TILE_SPAN = 2;
    private static final int COVERAGE_CELL_BLOCKS = 128;
    private static final long TARGET_GPU_BYTES = 1_024L * 1024 * 1024;
    private static final long MAX_GPU_BYTES = 1_152L * 1024 * 1024;
    private static final int TARGET_RESIDENT_TILES = 2_400;
    private static final int MAX_RESIDENT_TILES = 2_700;
    private static final int POSITION_REBUILD_QUANTUM_BLOCKS = 64;
    private static final int RESIDENCY_HYSTERESIS_BLOCKS = 256;
    private static final int MOVEMENT_GUARD_BLOCKS = 384;
    private static final int L1_SPATIAL_QUALITY_RADIUS_BLOCKS = 2_048;
    private static final int L1_MIN_PROTECTED_RADIUS_BLOCKS = 768;
    private static final long UPLOAD_BYTES_PER_FRAME = 32L * 1024 * 1024;
    private static final long PREP_BYTES_LIMIT = 128L * 1024 * 1024;
    private static final ExecutorService PLANNER = worker("Everview-Spatial", 1);
    private static final ExecutorService PACKER = worker("Everview-RegionPack", 2);
    private static final Map<RegionKey, GpuRegion> REGIONS = new LinkedHashMap<>();
    private static final Map<LodTileKey, GpuTile> TILES = new HashMap<>();
    private static final Map<RegionKey, PendingRegion> PENDING = new LinkedHashMap<>();
    private static final List<GpuRegion> RETIRED = new ArrayList<>();
    private static final ArrayDeque<DesiredRegion> DIRTY = new ArrayDeque<>();
    private static Map<RegionKey, DesiredRegion> desiredRegions = Map.of();
    private static List<DesiredRegion> desiredOrder = List.of();
    private static EverviewRenderState renderState = EverviewRenderState.EMPTY;
    private static CompletableFuture<Selection> selectionFuture;
    private static CompletableFuture<EverviewRenderState> ownershipFuture;
    private static List<WorldgenSurfaceTile> lastSources;
    private static List<WorldgenSurfaceTile> requestedSources;
    private static ClientLevel lastLevel;
    private static int positionX = Integer.MIN_VALUE, positionZ = Integer.MIN_VALUE;
    private static int requestedX, requestedZ;
    private static long residentBytes, residencyRevision;
    private static int regionRebuildsThisFrame, tileUploadsThisFrame, staleRegionsThisFrame;
    private static int residencySelectionRebuildsThisFrame, degradedFineTilesThisFrame, wantedTiles;
    private static long uploadNanosThisFrame, prepareNanosThisFrame;
    private static boolean residencyComplete, retirementDirty;

    private static ExecutorService worker(String name, int count) {
        return Executors.newFixedThreadPool(count, r -> {
            Thread thread = new Thread(r, name);
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    }

    private EverviewGpuRegionCache() {}

    public static void prepareFrame(ClientLevel level, WorldgenSurfaceSnapshot snapshot, Camera camera) {
        long started = System.nanoTime();
        EverviewFrameProfiler.begin();
        if (level != lastLevel) { clear(); lastLevel = level; }
        regionRebuildsThisFrame = tileUploadsThisFrame = staleRegionsThisFrame = 0;
        residencySelectionRebuildsThisFrame = 0;
        uploadNanosThisFrame = 0;
        double x = camera.position().x(), z = camera.position().z();
        int cellX = Math.floorDiv((int) Math.floor(x), POSITION_REBUILD_QUANTUM_BLOCKS);
        int cellZ = Math.floorDiv((int) Math.floor(z), POSITION_REBUILD_QUANTUM_BLOCKS);

        long integrationStarted = System.nanoTime();
        if (ownershipFuture != null && ownershipFuture.isDone()) {
            try {
                // Old commands/buffers stay usable until this one atomic publication.
                renderState = ownershipFuture.join();
                for (GpuRegion old : RETIRED) { residentBytes -= old.bytes(); old.close(); }
                RETIRED.clear();
                residencyRevision++;
                EverviewFrameProfiler.ownershipBuilds++;
                ownershipFuture = null;
            } catch (CompletionException error) {
                EverviewClient.LOGGER.error("Everview ownership transaction failed; retaining last drawable state", error);
                // Retry the same transaction. Never close buffers referenced by the old state.
                List<GpuRegion> retry = List.copyOf(REGIONS.values());
                ownershipFuture = CompletableFuture.supplyAsync(() -> EverviewRenderState.build(retry), PLANNER);
            }
        }
        EverviewFrameProfiler.integration = System.nanoTime() - integrationStarted;

        long residencyStarted = System.nanoTime();
        if (selectionFuture != null && selectionFuture.isDone()) {
            try {
                Selection selection = selectionFuture.join();
                desiredRegions = selection.regions();
                desiredOrder = selection.ordered();
                wantedTiles = selection.wanted();
                degradedFineTilesThisFrame = selection.degraded();
                lastSources = requestedSources;
                positionX = requestedX; positionZ = requestedZ;
                refreshDirty();
                residencySelectionRebuildsThisFrame++;
            } catch (CompletionException error) {
                EverviewClient.LOGGER.warn("Everview spatial plan failed; retaining current coverage", error);
            }
            selectionFuture = null;
        }
        // No pressure-triggered retry loop: budget is applied once inside each immutable plan.
        if (selectionFuture == null && (snapshot.tiles() != lastSources || cellX != positionX || cellZ != positionZ)) {
            requestedSources = snapshot.tiles(); requestedX = cellX; requestedZ = cellZ;
            Set<LodTileKey> resident = Set.copyOf(TILES.keySet());
            selectionFuture = CompletableFuture.supplyAsync(() -> select(snapshot, x, z, resident), PLANNER);
        }
        EverviewFrameProfiler.residency = System.nanoTime() - residencyStarted;

        if (ownershipFuture == null) {
            boolean changed = retireSafeRegions(x, z);
            // Poll at most four prepared jobs. No scans of all source tiles on a settled frame.
            long uploaded = 0;
            var pendingIterator = PENDING.entrySet().iterator();
            while (pendingIterator.hasNext()) {
                PendingRegion pending = pendingIterator.next().getValue();
                if (!pending.future().isDone()) continue;
                DesiredRegion desired = desiredRegions.get(pending.desired().key());
                if (!sameSources(pending.desired(), desired)) { pendingIterator.remove(); continue; }
                PreparedRegionCpu prepared;
                try { prepared = pending.future().join(); }
                catch (CompletionException error) {
                    pendingIterator.remove(); DIRTY.addLast(desired);
                    EverviewClient.LOGGER.warn("Everview region preparation failed", error);
                    continue;
                }
                GpuRegion old = REGIONS.get(desired.key());
                // Also protect tiles removed from a shrinking region, not just whole regions.
                if (old != null && !canReplace(old, desired, x, z)) continue;
                if (residentBytes + prepared.vertices().remaining() > MAX_GPU_BYTES
                        || TILES.size() + desired.tiles().size() - (old == null ? 0 : old.tileViews().size()) > MAX_RESIDENT_TILES) {
                    EverviewFrameProfiler.deferredUploads++;
                    continue;
                }
                if (uploaded > 0 && uploaded + prepared.vertices().remaining() > UPLOAD_BYTES_PER_FRAME) break;
                long uploadStarted = System.nanoTime();
                GpuRegion replacement = uploadPreparedRegion(prepared);
                uploadNanosThisFrame += System.nanoTime() - uploadStarted;
                replaceRegion(replacement);
                uploaded += replacement.bytes();
                tileUploadsThisFrame += replacement.tileViews().size();
                regionRebuildsThisFrame++;
                pendingIterator.remove();
                changed = true;
                // Bound driver work. CPU packing and command construction already happened on workers.
                if (regionRebuildsThisFrame >= 1) break;
            }
            if (changed) {
                List<GpuRegion> transaction = List.copyOf(REGIONS.values());
                ownershipFuture = CompletableFuture.supplyAsync(() -> EverviewRenderState.build(transaction), PLANNER);
            }
        }
        long pendingBytes = 0;
        for (PendingRegion pending : PENDING.values()) pendingBytes += pending.desired().estimatedBytes();
        while (PENDING.size() < 4 && !DIRTY.isEmpty()) {
            DesiredRegion desired = DIRTY.peekFirst();
            if (!PENDING.isEmpty() && pendingBytes + desired.estimatedBytes() > PREP_BYTES_LIMIT) break;
            DIRTY.removeFirst();
            if (PENDING.containsKey(desired.key())) continue;
            PENDING.put(desired.key(), new PendingRegion(desired,
                    CompletableFuture.supplyAsync(() -> prepareRegionCpu(desired), PACKER)));
            pendingBytes += desired.estimatedBytes();
        }
        residencyComplete = selectionFuture == null && ownershipFuture == null && DIRTY.isEmpty() && PENDING.isEmpty();
        EverviewFrameProfiler.upload = uploadNanosThisFrame;
        prepareNanosThisFrame = System.nanoTime() - started;
        EverviewFrameProfiler.prepareTotal = prepareNanosThisFrame;
    }

    private static void refreshDirty() {
        retirementDirty = true;
        DIRTY.clear();
        PENDING.entrySet().removeIf(e -> {
            if (sameSources(e.getValue().desired(), desiredRegions.get(e.getKey()))) return false;
            e.getValue().future().cancel(false); return true;
        });
        for (DesiredRegion desired : desiredOrder) {
            GpuRegion existing = REGIONS.get(desired.key());
            if ((existing == null || !existing.matches(desired.tiles())) && !PENDING.containsKey(desired.key())) DIRTY.add(desired);
        }
    }

    private static boolean sameSources(DesiredRegion a, DesiredRegion b) {
        if (b == null || a.tiles().size() != b.tiles().size()) return false;
        for (int i = 0; i < a.tiles().size(); i++) if (a.tiles().get(i) != b.tiles().get(i)) return false;
        return true;
    }

    private static boolean canReplace(GpuRegion old, DesiredRegion replacement, double x, double z) {
        for (GpuTile tile : old.tileViews()) {
            boolean retained = false;
            for (WorldgenSurfaceTile source : replacement.tiles()) {
                if (keyOf(source).equals(keyOf(tile.source()))) { retained = true; break; }
            }
            if (!retained && !hasDrawableParentOrOutside(tile.source(), x, z)) return false;
        }
        return true;
    }

    private static boolean hasDrawableParentOrOutside(WorldgenSurfaceTile tile, double x, double z) {
        // Leave a full base-tile guard beyond the intended 16K disk. Eviction is position-only.
        if (nearestDistance(tile, x, z) > 16_384 + 2_048 + RESIDENCY_HYSTERESIS_BLOCKS) return true;
        for (int level = tile.lodLevel() + 1; level <= 6; level++) {
            int size = level <= 2 ? 128 : 128 << (level - 2);
            GpuTile parent = TILES.get(new LodTileKey(level, Math.floorDiv(tile.minX(), size), Math.floorDiv(tile.minZ(), size)));
            if (parent != null && !parent.region().vertexBuffer().isClosed()) return true;
        }
        return false;
    }

    private static boolean retireSafeRegions(double x, double z) {
        // Retirement is necessary only after a plan/upload event, or while a transaction is blocked.
        if (!retirementDirty) return false;
        retirementDirty = false;
        boolean changed = false;
        var iterator = REGIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (desiredRegions.containsKey(entry.getKey())) continue;
            GpuRegion region = entry.getValue();
            boolean safe = true;
            for (GpuTile tile : region.tileViews()) {
                if (!hasDrawableParentOrOutside(tile.source(), x, z)) { safe = false; break; }
            }
            if (!safe) continue;
            iterator.remove(); removeRegionTileViews(region); RETIRED.add(region);
            staleRegionsThisFrame++; changed = true;
        }
        return changed;
    }

    private static PreparedRegionCpu prepareRegionCpu(DesiredRegion desired) {
        long started = System.nanoTime();
        int size = desired.tiles().getFirst().tileSize();
        int originX = desired.key().regionX() * REGION_TILE_SPAN * size;
        int originZ = desired.key().regionZ() * REGION_TILE_SPAN * size;
        List<EverviewGpuTileCache.PreparedGeometry> geometries = new ArrayList<>();
        int indices = 0, vertices = 0;
        for (WorldgenSurfaceTile tile : desired.tiles()) {
            var geometry = EverviewGpuTileCache.prepareRegionGeometry(tile, originX, originZ, indices);
            geometries.add(geometry); indices += geometry.indexCount(); vertices += geometry.colors().length;
        }
        ByteBuffer packed = ByteBuffer.allocateDirect(Math.multiplyExact(vertices, 16)).order(ByteOrder.nativeOrder());
        List<PreparedTile> tiles = new ArrayList<>();
        int first = 0;
        for (var geometry : geometries) {
            int[] xyz = geometry.vertices(), rgb = geometry.colors();
            for (int v = 0; v < rgb.length; v++) {
                packed.putFloat(xyz[v * 3]).putFloat(xyz[v * 3 + 1]).putFloat(xyz[v * 3 + 2]);
                int color = rgb[v];
                packed.put((byte) (color >> 16)).put((byte) (color >> 8)).put((byte) color).put((byte) 255);
            }
            tiles.add(new PreparedTile(geometry.source(), first, geometry.indexCount(), geometry.drawBatches(),
                    TerrainSurfaceData.from(geometry)));
            first += geometry.indexCount();
        }
        packed.flip();
        EverviewFrameProfiler.packingWorker = System.nanoTime() - started;
        return new PreparedRegionCpu(desired, originX, originZ, List.copyOf(tiles), indices, packed);
    }

    private static GpuRegion uploadPreparedRegion(PreparedRegionCpu prepared) {
        var key = prepared.desired().key();
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Everview region " + key,
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_VERTEX, prepared.vertices());
        GpuRegion region = new GpuRegion(key, buffer, prepared.indices(), prepared.vertices().limit(),
                prepared.originX(), prepared.originZ(), prepared.desired().tiles(), new ArrayList<>());
        for (PreparedTile tile : prepared.tiles()) region.tileViews().add(new GpuTile(tile.source(), region,
                tile.first(), tile.count(), tile.batches(), tile.surfaceData()));
        return region;
    }

    private static void replaceRegion(GpuRegion replacement) {
        GpuRegion old = REGIONS.put(replacement.key(), replacement);
        if (old != null) { removeRegionTileViews(old); RETIRED.add(old); }
        for (GpuTile tile : replacement.tileViews()) TILES.put(keyOf(tile.source()), tile);
        residentBytes += replacement.bytes();
        retirementDirty = true;
    }

    private static void removeRegionTileViews(GpuRegion region) {
        for (GpuTile tile : region.tileViews()) TILES.remove(keyOf(tile.source()), tile);
    }

    public static GpuTile getResident(WorldgenSurfaceTile tile) { return TILES.get(keyOf(tile)); }
    public static long residencyRevision() { return residencyRevision; }
    public static EverviewRenderState renderState() { return renderState; }
    public static Stats stats() {
        return new Stats(REGIONS.size(), TILES.size(), residentBytes, regionRebuildsThisFrame,
                tileUploadsThisFrame, uploadNanosThisFrame / 1e6, prepareNanosThisFrame / 1e6,
                staleRegionsThisFrame, 0, residencySelectionRebuildsThisFrame, degradedFineTilesThisFrame,
                wantedTiles, residencyComplete);
    }

    public static void clear() {
        renderState = EverviewRenderState.EMPTY;
        for (GpuRegion region : REGIONS.values()) region.close();
        for (GpuRegion region : RETIRED) region.close();
        for (PendingRegion pending : PENDING.values()) pending.future().cancel(false);
        if (selectionFuture != null) selectionFuture.cancel(false);
        if (ownershipFuture != null) ownershipFuture.cancel(false);
        REGIONS.clear(); TILES.clear(); RETIRED.clear(); PENDING.clear(); DIRTY.clear();
        desiredRegions = Map.of(); desiredOrder = List.of();
        // Workers own immutable CPU data only; abandoned jobs cannot publish or touch GPU resources.
        selectionFuture = null; ownershipFuture = null;
        lastSources = requestedSources = null; lastLevel = null;
        positionX = positionZ = Integer.MIN_VALUE;
        residentBytes = 0; residencyRevision++; residencyComplete = false;
    }

    private static Selection select(
            WorldgenSurfaceSnapshot snapshot,
            double cameraX,
            double cameraZ, Set<LodTileKey> residentKeys
    ) {
        long started = System.nanoTime();
        int degraded = 0;

        Map<Long, WorldgenSurfaceTile> bestByCell = new HashMap<>();
        Map<LodTileKey, WorldgenSurfaceTile> sourceByKey = new HashMap<>();

        for (WorldgenSurfaceTile tile : snapshot.tiles()) {
            LodTileKey key = keyOf(tile);
            sourceByKey.put(key, tile);

            WorldgenLodRing ring = snapshot.ringForLevel(tile.lodLevel());
            if (ring == null) {
                continue;
            }

            boolean alreadyResident = residentKeys.contains(key);
            int hysteresis = alreadyResident
                    ? RESIDENCY_HYSTERESIS_BLOCKS : 0;

            int minCellX = Math.floorDiv(tile.minX(), COVERAGE_CELL_BLOCKS);
            int minCellZ = Math.floorDiv(tile.minZ(), COVERAGE_CELL_BLOCKS);
            int maxCellX = Math.floorDiv(
                    tile.maxX() - 1,
                    COVERAGE_CELL_BLOCKS
            );
            int maxCellZ = Math.floorDiv(
                    tile.maxZ() - 1,
                    COVERAGE_CELL_BLOCKS
            );

            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                    double cellCenterX =
                            cellX * (double) COVERAGE_CELL_BLOCKS
                                    + COVERAGE_CELL_BLOCKS * 0.5;
                    double cellCenterZ =
                            cellZ * (double) COVERAGE_CELL_BLOCKS
                                    + COVERAGE_CELL_BLOCKS * 0.5;
                    double distance = Math.hypot(
                            cellCenterX - cameraX,
                            cellCenterZ - cameraZ
                    );

                    if (!cellBelongsToSpatialTier(
                            tile,
                            ring,
                            distance,
                            hysteresis
                    )) {
                        continue;
                    }

                    long cell = packCell(cellX, cellZ);
                    WorldgenSurfaceTile previous = bestByCell.get(cell);
                    if (previous == null
                            || betterRepresentation(tile, previous)) {
                        bestByCell.put(cell, tile);
                    }
                }
            }
        }

        LinkedHashSet<WorldgenSurfaceTile> selected =
                new LinkedHashSet<>(bestByCell.values());

        // The outermost floor is a coverage reserve, independent of fine quality.
        // It remains uploaded even where children currently hide it.
        for (WorldgenSurfaceTile tile : snapshot.tiles()) {
            if (tile.lodLevel() == 6) selected.add(tile);
        }
        long estimate = estimatedSelectionBytes(selected);
        if (estimate > TARGET_GPU_BYTES
                || selected.size() > TARGET_RESIDENT_TILES) {
            List<WorldgenSurfaceTile> degradeable =
                    new ArrayList<>(selected);
            degradeable.sort(Comparator
                    .comparingDouble((WorldgenSurfaceTile tile) ->
                            nearestDistance(tile, cameraX, cameraZ))
                    .reversed()
                    .thenComparingInt(WorldgenSurfaceTile::lodLevel));

            for (WorldgenSurfaceTile fine : degradeable) {
                if (estimate <= TARGET_GPU_BYTES
                        && selected.size() <= TARGET_RESIDENT_TILES) {
                    break;
                }

                if (fine.lodLevel() >= 6) {
                    continue;
                }

                double nearest = nearestDistance(
                        fine,
                        cameraX,
                        cameraZ
                );
                if (fine.lodLevel() == 1
                        && nearest <= L1_MIN_PROTECTED_RADIUS_BLOCKS) {
                    continue;
                }

                WorldgenSurfaceTile fallback = findFallback(
                        fine,
                        snapshot,
                        sourceByKey,
                        cameraX,
                        cameraZ
                );
                if (fallback == null || !selected.remove(fine)) continue;
                estimate -= estimatedGpuBytes(fine);
                if (selected.add(fallback)) {
                    estimate += estimatedGpuBytes(fallback);
                }

                degraded++;
            }
        }

        Map<RegionKey, DesiredRegion> desired = buildDesiredRegions(selected);
        List<DesiredRegion> ordered = new ArrayList<>(desired.values());
        ordered.sort(Comparator.comparingInt((DesiredRegion r) -> r.key().lodLevel() == 6 ? 0 : 1)
                .thenComparingDouble(r -> r.distanceTo(cameraX, cameraZ))
                .thenComparingInt(r -> -r.key().lodLevel()));
        EverviewFrameProfiler.selectionWorker = System.nanoTime() - started;
        return new Selection(desired, List.copyOf(ordered), selected.size(), degraded);
    }

    private static boolean cellBelongsToSpatialTier(
            WorldgenSurfaceTile tile,
            WorldgenLodRing ring,
            double distance,
            int hysteresis
    ) {
        double inner = Math.max(
                0.0,
                ring.innerRadiusBlocks() - hysteresis
        );
        double outer = ring.outerRadiusBlocks()
                + MOVEMENT_GUARD_BLOCKS + hysteresis;

        if (distance < inner || distance > outer) {
            return false;
        }

        if (tile.lodLevel() == 1) {
            return distance <= L1_SPATIAL_QUALITY_RADIUS_BLOCKS
                    + hysteresis;
        }

        return true;
    }

    private static boolean betterRepresentation(
            WorldgenSurfaceTile candidate,
            WorldgenSurfaceTile previous
    ) {
        if (candidate.lodLevel() != previous.lodLevel()) {
            return candidate.lodLevel() < previous.lodLevel();
        }
        if (candidate.sampleSpacing() != previous.sampleSpacing()) {
            return candidate.sampleSpacing() < previous.sampleSpacing();
        }
        return candidate.stage().ordinal() > previous.stage().ordinal();
    }

    private static WorldgenSurfaceTile findFallback(
            WorldgenSurfaceTile fine,
            WorldgenSurfaceSnapshot snapshot,
            Map<LodTileKey, WorldgenSurfaceTile> sourceByKey,
            double cameraX,
            double cameraZ
    ) {
        int centerX = (fine.minX() + fine.maxX()) / 2;
        int centerZ = (fine.minZ() + fine.maxZ()) / 2;

        for (int level = fine.lodLevel() + 1; level <= 6; level++) {
            WorldgenLodRing ring = snapshot.ringForLevel(level);
            if (ring == null) {
                continue;
            }

            double distance = Math.hypot(
                    centerX - cameraX,
                    centerZ - cameraZ
            );
            if (distance < ring.innerRadiusBlocks()
                    - RESIDENCY_HYSTERESIS_BLOCKS
                    || distance > ring.outerRadiusBlocks()
                    + MOVEMENT_GUARD_BLOCKS) {
                continue;
            }

            int tileX = Math.floorDiv(centerX, ring.tileSize());
            int tileZ = Math.floorDiv(centerZ, ring.tileSize());
            WorldgenSurfaceTile fallback = sourceByKey.get(
                    new LodTileKey(level, tileX, tileZ)
            );
            if (fallback != null) {
                return fallback;
            }
        }

        return null;
    }

    private static long estimatedSelectionBytes(
            Set<WorldgenSurfaceTile> tiles
    ) {
        long bytes = 0L;
        for (WorldgenSurfaceTile tile : tiles) {
            bytes += estimatedGpuBytes(tile);
        }
        return bytes;
    }

    private static long estimatedGpuBytes(WorldgenSurfaceTile tile) {
        // POSITION_COLOR is compact, but near ownership splitting can add
        // vertices. A conservative factor prevents the admission phase from
        // recreating M9.4's ~1.9 GiB "selected" set.
        return Math.max(
                32L * 1024L,
                tile.vertexCount() * 24L
        );
    }

    private static Map<RegionKey, DesiredRegion> buildDesiredRegions(
            Set<WorldgenSurfaceTile> selected
    ) {
        Map<RegionKey, List<WorldgenSurfaceTile>> grouped =
                new LinkedHashMap<>();

        for (WorldgenSurfaceTile tile : selected) {
            RegionKey regionKey = regionKey(tile);
            grouped.computeIfAbsent(
                    regionKey,
                    ignored -> new ArrayList<>()
            ).add(tile);
        }

        Map<RegionKey, DesiredRegion> result = new LinkedHashMap<>();
        for (Map.Entry<RegionKey, List<WorldgenSurfaceTile>> entry
                : grouped.entrySet()) {
            List<WorldgenSurfaceTile> tiles = entry.getValue();
            tiles.sort(Comparator
                    .comparingInt(WorldgenSurfaceTile::tileZ)
                    .thenComparingInt(WorldgenSurfaceTile::tileX));

            long estimate = 0L;
            for (WorldgenSurfaceTile tile : tiles) {
                estimate += estimatedGpuBytes(tile);
            }

            result.put(entry.getKey(), new DesiredRegion(
                    entry.getKey(),
                    List.copyOf(tiles),
                    estimate
            ));
        }

        return result;
    }

    private static RegionKey regionKey(
            WorldgenSurfaceTile tile
    ) {
        return new RegionKey(
                tile.lodLevel(),
                Math.floorDiv(tile.tileX(), REGION_TILE_SPAN),
                Math.floorDiv(tile.tileZ(), REGION_TILE_SPAN)
        );
    }

    private static LodTileKey keyOf(
            WorldgenSurfaceTile tile
    ) {
        return new LodTileKey(
                tile.lodLevel(),
                tile.tileX(),
                tile.tileZ()
        );
    }

    private static long packCell(int x, int z) {
        return ((long) x << 32)
                ^ (z & 0xFFFF_FFFFL);
    }

    private static double nearestDistance(
            WorldgenSurfaceTile tile,
            double cameraX,
            double cameraZ
    ) {
        double nearestX = Math.max(
                tile.minX(),
                Math.min(cameraX, tile.maxX())
        );
        double nearestZ = Math.max(
                tile.minZ(),
                Math.min(cameraZ, tile.maxZ())
        );
        return Math.hypot(
                nearestX - cameraX,
                nearestZ - cameraZ
        );
    }

    public static final class GpuRegion implements AutoCloseable {
        private final RegionKey key;
        private final GpuBuffer vertexBuffer;
        private final int indexCount;
        private final long bytes;
        private final int originX;
        private final int originZ;
        private final List<WorldgenSurfaceTile> sources;
        private final List<GpuTile> tileViews;

        private GpuRegion(
                RegionKey key,
                GpuBuffer vertexBuffer,
                int indexCount,
                long bytes,
                int originX,
                int originZ,
                List<WorldgenSurfaceTile> sources,
                List<GpuTile> tileViews
        ) {
            this.key = key;
            this.vertexBuffer = vertexBuffer;
            this.indexCount = indexCount;
            this.bytes = bytes;
            this.originX = originX;
            this.originZ = originZ;
            this.sources = sources;
            this.tileViews = tileViews;
        }

        public RegionKey key() { return key; }
        public GpuBuffer vertexBuffer() { return vertexBuffer; }
        public int indexCount() { return indexCount; }
        public long bytes() { return bytes; }
        public int originX() { return originX; }
        public int originZ() { return originZ; }
        public int lodLevel() { return key.lodLevel(); }
        public List<GpuTile> tileViews() { return tileViews; }

        private boolean matches(List<WorldgenSurfaceTile> desired) {
            if (sources.size() != desired.size()) {
                return false;
            }
            for (int i = 0; i < sources.size(); i++) {
                if (sources.get(i) != desired.get(i)) {
                    return false;
                }
            }
            return !vertexBuffer.isClosed();
        }

        private double distanceTo(double cameraX, double cameraZ) {
            int tileSize = sources.isEmpty()
                    ? 128 : sources.getFirst().tileSize();
            double size = REGION_TILE_SPAN * (double) tileSize;
            double minX = originX;
            double minZ = originZ;
            double maxX = minX + size;
            double maxZ = minZ + size;
            double nearestX = Math.max(
                    minX,
                    Math.min(cameraX, maxX)
            );
            double nearestZ = Math.max(
                    minZ,
                    Math.min(cameraZ, maxZ)
            );
            return Math.hypot(
                    nearestX - cameraX,
                    nearestZ - cameraZ
            );
        }

        @Override
        public void close() {
            if (!vertexBuffer.isClosed()) {
                vertexBuffer.close();
            }
        }
    }

    public record GpuTile(
            WorldgenSurfaceTile source,
            GpuRegion region,
            int firstIndex,
            int indexCount,
            List<EverviewGpuTileCache.DrawBatch> drawBatches,
            TerrainSurfaceData surfaceData
    ) {
    }

    public record RegionKey(
            int lodLevel,
            int regionX,
            int regionZ
    ) {
    }

    public record Stats(
            int regionCount,
            int tileCount,
            long residentBytes,
            int regionRebuildsThisFrame,
            int tileUploadsThisFrame,
            double uploadMs,
            double prepareMs,
            int staleRegionsThisFrame,
            int hardEvictionsThisFrame,
            int residencySelectionRebuildsThisFrame,
            int degradedFineTilesThisFrame,
            int residencyWantedTiles,
            boolean residencyComplete
    ) {
        public double residentMiB() {
            return residentBytes / (1024.0 * 1024.0);
        }
    }

    private record Selection(Map<RegionKey, DesiredRegion> regions, List<DesiredRegion> ordered, int wanted, int degraded) {}
    private record PreparedTile(WorldgenSurfaceTile source, int first, int count,
                                List<EverviewGpuTileCache.DrawBatch> batches, TerrainSurfaceData surfaceData) {}
    private record PreparedRegionCpu(DesiredRegion desired, int originX, int originZ,
                                     List<PreparedTile> tiles, int indices, ByteBuffer vertices) {}
    private record PendingRegion(DesiredRegion desired, CompletableFuture<PreparedRegionCpu> future) {}
    private record DesiredRegion(
            RegionKey key,
            List<WorldgenSurfaceTile> tiles,
            long estimatedBytes
    ) {
        private int priorityLevel() {
            return key.lodLevel();
        }

        private double distanceTo(
                double cameraX,
                double cameraZ
        ) {
            if (tiles.isEmpty()) {
                return Double.MAX_VALUE;
            }
            double best = Double.MAX_VALUE;
            for (WorldgenSurfaceTile tile : tiles) {
                best = Math.min(
                        best,
                        nearestDistance(tile, cameraX, cameraZ)
                );
            }
            return best;
        }
    }
}
