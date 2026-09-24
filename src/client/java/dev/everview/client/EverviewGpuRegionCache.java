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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M9.5 spatial residency + region-buffer architecture.
 *
 * Residency is deliberately independent of camera yaw/pitch. The camera frustum
 * is a draw-time concern only; generated quality is admitted by spatial
 * distance, existing residency (hysteresis), movement guard bands, and bounded
 * memory. The selected hierarchy stores only the best available representation
 * for each 128-block coverage cell, then falls back to coarser levels where
 * fine coverage is unavailable or the memory budget requires it.
 *
 * GPU geometry is allocated per 2x2 logical-tile region instead of per tile.
 * Renderer-side multi-draw can therefore submit several independently culled
 * logical tiles with one bound vertex buffer / transform / GL multi-draw call.
 */
public final class EverviewGpuRegionCache {
    private static final int REGION_TILE_SPAN = 2;
    private static final int COVERAGE_CELL_BLOCKS = 128;

    private static final long TARGET_GPU_BYTES =
            1_024L * 1024L * 1024L;
    private static final long MAX_GPU_BYTES =
            1_152L * 1024L * 1024L;
    private static final int TARGET_RESIDENT_TILES = 2_400;
    private static final int MAX_RESIDENT_TILES = 2_700;

    private static final int POSITION_REBUILD_QUANTUM_BLOCKS = 64;
    private static final int RESIDENCY_HYSTERESIS_BLOCKS = 256;
    private static final int MOVEMENT_GUARD_BLOCKS = 384;
    private static final int L1_SPATIAL_QUALITY_RADIUS_BLOCKS = 2_048;
    private static final int L1_MIN_PROTECTED_RADIUS_BLOCKS = 768;

    private static final int MAX_REGION_REBUILDS_PER_FRAME = 2;
    private static final long TARGET_REGION_UPLOAD_BYTES_PER_FRAME =
            64L * 1024L * 1024L;

    private static final Map<RegionKey, GpuRegion> REGIONS =
            new LinkedHashMap<>();
    private static final Map<LodTileKey, GpuTile> TILES =
            new HashMap<>();
    private static final Set<LodTileKey> RESIDENCY_WANTED =
            new HashSet<>();

    private static ClientLevel lastLevel;
    private static WorldgenSurfaceSnapshot lastSnapshotReference;
    private static long lastSnapshotFingerprint = Long.MIN_VALUE;
    private static int lastPositionCellX = Integer.MIN_VALUE;
    private static int lastPositionCellZ = Integer.MIN_VALUE;

    private static long residentBytes;
    private static long residencyRevision;

    private static int regionRebuildsThisFrame;
    private static int tileUploadsThisFrame;
    private static long uploadNanosThisFrame;
    private static long prepareNanosThisFrame;
    private static int staleRegionsThisFrame;
    private static int hardEvictionsThisFrame;
    private static int residencySelectionRebuildsThisFrame;
    private static int degradedFineTilesThisFrame;
    private static boolean residencyComplete;

    private EverviewGpuRegionCache() {
    }

    public static void prepareFrame(
            ClientLevel level,
            WorldgenSurfaceSnapshot snapshot,
            Camera camera
    ) {
        long started = System.nanoTime();

        if (level != lastLevel) {
            clear();
            lastLevel = level;
        }

        regionRebuildsThisFrame = 0;
        tileUploadsThisFrame = 0;
        uploadNanosThisFrame = 0L;
        staleRegionsThisFrame = 0;
        hardEvictionsThisFrame = 0;
        residencySelectionRebuildsThisFrame = 0;
        degradedFineTilesThisFrame = 0;

        double cameraX = camera.position().x();
        double cameraZ = camera.position().z();

        int positionCellX = Math.floorDiv(
                (int) Math.floor(cameraX),
                POSITION_REBUILD_QUANTUM_BLOCKS
        );
        int positionCellZ = Math.floorDiv(
                (int) Math.floor(cameraZ),
                POSITION_REBUILD_QUANTUM_BLOCKS
        );

        long fingerprint = snapshotFingerprint(snapshot);
        boolean snapshotChanged =
                snapshot != lastSnapshotReference
                        && fingerprint != lastSnapshotFingerprint;
        boolean moved = positionCellX != lastPositionCellX
                || positionCellZ != lastPositionCellZ;

        if (snapshotChanged || moved || residentBytes > TARGET_GPU_BYTES) {
            rebuildSpatialResidency(snapshot, cameraX, cameraZ);
            residencySelectionRebuildsThisFrame++;
            lastSnapshotFingerprint = fingerprint;
            lastPositionCellX = positionCellX;
            lastPositionCellZ = positionCellZ;
        }
        lastSnapshotReference = snapshot;

        Map<RegionKey, DesiredRegion> desiredRegions =
                buildDesiredRegions(snapshot);

        List<DesiredRegion> dirty = new ArrayList<>();
        for (DesiredRegion desired : desiredRegions.values()) {
            GpuRegion existing = REGIONS.get(desired.key());
            if (existing == null || !existing.matches(desired.tiles())) {
                dirty.add(desired);
            }
        }

        dirty.sort(Comparator
                .comparingInt(DesiredRegion::priorityLevel)
                .thenComparingDouble(region ->
                        region.distanceTo(cameraX, cameraZ)));

        long uploadBudget = TARGET_REGION_UPLOAD_BYTES_PER_FRAME;
        for (DesiredRegion desired : dirty) {
            if (regionRebuildsThisFrame >= MAX_REGION_REBUILDS_PER_FRAME) {
                break;
            }

            long estimate = desired.estimatedBytes();
            if (regionRebuildsThisFrame > 0
                    && estimate > uploadBudget) {
                continue;
            }

            long uploadStarted = System.nanoTime();
            GpuRegion replacement = buildRegion(desired);
            uploadNanosThisFrame += System.nanoTime() - uploadStarted;
            regionRebuildsThisFrame++;
            tileUploadsThisFrame += desired.tiles().size();
            uploadBudget = Math.max(0L, uploadBudget - replacement.bytes());

            replaceRegion(replacement);
        }

        // Never retire the previous spatial representation before its
        // replacement is actually resident. This is the region-level handoff
        // equivalent of the vanilla/LOD overlap rule: fine arrives first,
        // then stale fallback leaves. It prevents movement or a refinement
        // publication from exposing a transient hole.
        boolean wantedResident = allWantedSourcesResident(snapshot);
        if (wantedResident) {
            removeUndesiredRegions(desiredRegions.keySet());
        }

        trimHardLimit(cameraX, cameraZ);
        residencyComplete = allWantedSourcesResident(snapshot);

        prepareNanosThisFrame = System.nanoTime() - started;
    }

    /**
     * Spatial selection only. There is intentionally no frustum, yaw, pitch,
     * or "recently viewed" input here.
     */
    private static void rebuildSpatialResidency(
            WorldgenSurfaceSnapshot snapshot,
            double cameraX,
            double cameraZ
    ) {
        RESIDENCY_WANTED.clear();

        Map<Long, WorldgenSurfaceTile> bestByCell = new HashMap<>();
        Map<LodTileKey, WorldgenSurfaceTile> sourceByKey = new HashMap<>();

        for (WorldgenSurfaceTile tile : snapshot.tiles()) {
            LodTileKey key = keyOf(tile);
            sourceByKey.put(key, tile);

            WorldgenLodRing ring = snapshot.ringForLevel(tile.lodLevel());
            if (ring == null) {
                continue;
            }

            boolean alreadyResident = TILES.containsKey(key);
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

                if (!selected.remove(fine)) {
                    continue;
                }
                estimate -= estimatedGpuBytes(fine);

                WorldgenSurfaceTile fallback = findFallback(
                        fine,
                        snapshot,
                        sourceByKey,
                        cameraX,
                        cameraZ
                );
                if (fallback != null && selected.add(fallback)) {
                    estimate += estimatedGpuBytes(fallback);
                }

                degradedFineTilesThisFrame++;
            }
        }

        for (WorldgenSurfaceTile tile : selected) {
            RESIDENCY_WANTED.add(keyOf(tile));
        }
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
            WorldgenSurfaceSnapshot snapshot
    ) {
        Map<RegionKey, List<WorldgenSurfaceTile>> grouped =
                new LinkedHashMap<>();

        for (WorldgenSurfaceTile tile : snapshot.tiles()) {
            LodTileKey key = keyOf(tile);
            if (!RESIDENCY_WANTED.contains(key)) {
                continue;
            }

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

    private static void removeUndesiredRegions(
            Set<RegionKey> desired
    ) {
        List<RegionKey> remove = new ArrayList<>();
        for (RegionKey key : REGIONS.keySet()) {
            if (!desired.contains(key)) {
                remove.add(key);
            }
        }

        for (RegionKey key : remove) {
            GpuRegion region = REGIONS.remove(key);
            if (region == null) {
                continue;
            }
            removeRegionTileViews(region);
            residentBytes -= region.bytes();
            region.close();
            staleRegionsThisFrame++;
            residencyRevision++;
        }

        if (residentBytes < 0L) {
            residentBytes = 0L;
        }
    }

    private static GpuRegion buildRegion(
            DesiredRegion desired
    ) {
        RegionKey key = desired.key();
        int tileSize = desired.tiles().getFirst().tileSize();
        int originX = key.regionX()
                * REGION_TILE_SPAN * tileSize;
        int originZ = key.regionZ()
                * REGION_TILE_SPAN * tileSize;

        List<EverviewGpuTileCache.PreparedGeometry> prepared =
                new ArrayList<>(desired.tiles().size());
        int totalIndices = 0;
        int totalVertices = 0;

        for (WorldgenSurfaceTile tile : desired.tiles()) {
            EverviewGpuTileCache.PreparedGeometry geometry =
                    EverviewGpuTileCache.prepareRegionGeometry(
                            tile,
                            originX,
                            originZ,
                            totalIndices
                    );
            prepared.add(geometry);
            totalIndices += geometry.indexCount();
            totalVertices += geometry.vertices().length / 3;
        }

        VertexFormat format = DefaultVertexFormat.POSITION_COLOR;
        int bytes = Math.multiplyExact(
                format.getVertexSize(),
                totalVertices
        );

        List<GpuTile> tileViews = new ArrayList<>(prepared.size());

        try (ByteBufferBuilder byteBuffer =
                     ByteBufferBuilder.exactlySized(bytes)) {
            BufferBuilder builder = new BufferBuilder(
                    byteBuffer,
                    PrimitiveTopology.QUADS,
                    format
            );

            int firstIndex = 0;
            for (EverviewGpuTileCache.PreparedGeometry geometry
                    : prepared) {
                int[] vertices = geometry.vertices();
                int[] colors = geometry.colors();

                for (int vertex = 0;
                        vertex < colors.length;
                        vertex++) {
                    int i = vertex * 3;
                    int rgb = colors[vertex];
                    builder.addVertex(
                                    vertices[i],
                                    vertices[i + 1],
                                    vertices[i + 2]
                            )
                            .setColor(
                                    (rgb >> 16) & 0xFF,
                                    (rgb >> 8) & 0xFF,
                                    rgb & 0xFF,
                                    255
                            );
                }

                tileViews.add(new GpuTile(
                        geometry.source(),
                        null,
                        firstIndex,
                        geometry.indexCount(),
                        geometry.drawBatches()
                ));
                firstIndex += geometry.indexCount();
            }

            try (MeshData mesh = builder.buildOrThrow()) {
                GpuBuffer vertexBuffer =
                        RenderSystem.getDevice().createBuffer(
                                () -> "Everview L"
                                        + key.lodLevel()
                                        + " region "
                                        + key.regionX()
                                        + ","
                                        + key.regionZ(),
                                GpuBuffer.USAGE_COPY_DST
                                        | GpuBuffer.USAGE_VERTEX,
                                mesh.vertexBuffer()
                        );

                GpuRegion region = new GpuRegion(
                        key,
                        vertexBuffer,
                        mesh.drawState().indexCount(),
                        bytes,
                        originX,
                        originZ,
                        List.copyOf(desired.tiles()),
                        new ArrayList<>()
                );

                List<GpuTile> finalized =
                        new ArrayList<>(tileViews.size());
                for (GpuTile tile : tileViews) {
                    finalized.add(new GpuTile(
                            tile.source(),
                            region,
                            tile.firstIndex(),
                            tile.indexCount(),
                            tile.drawBatches()
                    ));
                }
                region.tileViews().addAll(finalized);
                return region;
            }
        }
    }

    private static void replaceRegion(GpuRegion replacement) {
        GpuRegion previous = REGIONS.put(
                replacement.key(),
                replacement
        );

        if (previous != null) {
            removeRegionTileViews(previous);
            residentBytes -= previous.bytes();
            previous.close();
        }

        for (GpuTile tile : replacement.tileViews()) {
            TILES.put(keyOf(tile.source()), tile);
        }

        residentBytes += replacement.bytes();
        residencyRevision++;
    }

    private static void removeRegionTileViews(GpuRegion region) {
        for (GpuTile tile : region.tileViews()) {
            LodTileKey key = keyOf(tile.source());
            if (TILES.get(key) == tile) {
                TILES.remove(key);
            }
        }
    }

    private static void trimHardLimit(
            double cameraX,
            double cameraZ
    ) {
        if (residentBytes <= MAX_GPU_BYTES
                && TILES.size() <= MAX_RESIDENT_TILES) {
            return;
        }

        List<GpuRegion> farthest =
                new ArrayList<>(REGIONS.values());
        farthest.sort(Comparator
                .comparingInt((GpuRegion region) ->
                        regionContainsWantedTile(region) ? 1 : 0)
                .thenComparing(
                        Comparator.comparingDouble(
                                (GpuRegion region) ->
                                        region.distanceTo(
                                                cameraX,
                                                cameraZ
                                        )
                        ).reversed()
                ));

        for (GpuRegion region : farthest) {
            if (residentBytes <= MAX_GPU_BYTES
                    && TILES.size() <= MAX_RESIDENT_TILES) {
                break;
            }

            boolean protectedNearL1 = false;
            if (region.key().lodLevel() == 1) {
                for (GpuTile tile : region.tileViews()) {
                    if (nearestDistance(
                            tile.source(),
                            cameraX,
                            cameraZ
                    ) <= L1_MIN_PROTECTED_RADIUS_BLOCKS) {
                        protectedNearL1 = true;
                        break;
                    }
                }
            }
            if (protectedNearL1) {
                continue;
            }

            REGIONS.remove(region.key());
            removeRegionTileViews(region);
            residentBytes -= region.bytes();
            region.close();
            hardEvictionsThisFrame++;
            residencyRevision++;
        }

        if (residentBytes < 0L) {
            residentBytes = 0L;
        }
    }

    private static boolean regionContainsWantedTile(
            GpuRegion region
    ) {
        for (GpuTile tile : region.tileViews()) {
            if (RESIDENCY_WANTED.contains(keyOf(tile.source()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean allWantedSourcesResident(
            WorldgenSurfaceSnapshot snapshot
    ) {
        for (WorldgenSurfaceTile tile : snapshot.tiles()) {
            LodTileKey key = keyOf(tile);
            if (!RESIDENCY_WANTED.contains(key)) {
                continue;
            }
            GpuTile resident = TILES.get(key);
            if (resident == null || resident.source() != tile
                    || resident.region().vertexBuffer().isClosed()) {
                return false;
            }
        }
        return true;
    }

    public static GpuTile getResident(WorldgenSurfaceTile tile) {
        GpuTile resident = TILES.get(keyOf(tile));
        if (resident == null
                || resident.region().vertexBuffer().isClosed()) {
            return null;
        }

        // Same-key older geometry remains a valid temporary fallback while
        // a refined region replacement is queued.
        return resident;
    }

    public static long residencyRevision() {
        return residencyRevision;
    }

    public static Stats stats() {
        return new Stats(
                REGIONS.size(),
                TILES.size(),
                residentBytes,
                regionRebuildsThisFrame,
                tileUploadsThisFrame,
                uploadNanosThisFrame / 1_000_000.0,
                prepareNanosThisFrame / 1_000_000.0,
                staleRegionsThisFrame,
                hardEvictionsThisFrame,
                residencySelectionRebuildsThisFrame,
                degradedFineTilesThisFrame,
                RESIDENCY_WANTED.size(),
                residencyComplete
        );
    }

    public static void clear() {
        for (GpuRegion region : REGIONS.values()) {
            region.close();
        }

        REGIONS.clear();
        TILES.clear();
        RESIDENCY_WANTED.clear();

        residentBytes = 0L;
        residencyRevision++;
        lastLevel = null;
        lastSnapshotReference = null;
        lastSnapshotFingerprint = Long.MIN_VALUE;
        lastPositionCellX = Integer.MIN_VALUE;
        lastPositionCellZ = Integer.MIN_VALUE;
        residencyComplete = false;
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
        return hash * 0x100000001b3L;
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
            List<EverviewGpuTileCache.DrawBatch> drawBatches
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
