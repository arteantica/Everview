package dev.everview.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * M4 unified hierarchical ownership renderer.
 *
 * M5.2 render-ready column ownership: vanilla owns a chunk column only after
 * Minecraft's renderer reports actual compiled-and-visible terrain in that
 * column. A merely loaded client chunk is not enough to retire Everview, so
 * LOD remains underneath while vanilla catches up during high-speed travel.
 * L1/L2/L3 all use the same ownership mask. L2 no longer disappears as an all-or-nothing
 * 128-block tile; each section batch retires as its corresponding 32-block L1
 * tile becomes GPU-resident. L3 keeps its per-L2-region fallback. All masks
 * coalesce adjacent visible ranges before submission.
 *
 * Important 26.3 detail: LevelRenderEvents.AFTER_OPAQUE_TERRAIN fires while
 * Minecraft's opaque terrain RenderPass is still open. Everview therefore
 * draws into that existing pass instead of trying to create a nested pass.
 */
public final class EverviewRenderer {
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

    // Keep finer rings slightly above coarser overlapping rings. The offset is
    // intentionally sub-block so it closes transition cracks without making
    // distant terrain visibly sink at ring boundaries.
    private static final double BASE_TERRAIN_BIAS = 0.22D;
    private static final double RING_LAYER_BIAS = 0.06D;
    private static final long RECENT_COMPILE_HINT_NANOS = 1_500_000_000L;
    private static final double VANILLA_OWNERSHIP_MARGIN_BLOCKS = 64.0D;

    private static int lastVanillaOwnedBatches;
    private static int lastFinerOwnedBatches;
    private static int lastVisibleLodBatches;
    private static int lastLoadedWaitingBatches;

    private static final Map<SectionKey, Long> RECENTLY_COMPILED_SECTIONS =
            new HashMap<>();
    private static ClientLevel compileHintLevel;

    private EverviewRenderer() {
    }

    /**
     * GPU uploads happen during COLLECT_SUBMITS, before the opaque terrain
     * RenderPass begins. The actual draw hook is a small LevelRenderer mixin
     * that receives Minecraft's already-open opaque RenderPass.
     */
    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            Minecraft client = Minecraft.getInstance();

            if (client.level == null || client.player == null) {
                return;
            }

            WorldgenSurfaceSnapshot snapshot = WorldgenSurfaceSampler.snapshot();
            if (!snapshot.tiles().isEmpty()) {
                EverviewGpuTileCache.prepareFrame(client.level, snapshot);
            }
        });
    }

    public static void drawPersistentTerrain(RenderPass renderPass) {
        Minecraft client = Minecraft.getInstance();
        Camera camera = client.gameRenderer.mainCamera();

        if (client.level == null || client.player == null || !camera.isInitialized()) {
            return;
        }

        if (client.level != compileHintLevel) {
            RECENTLY_COMPILED_SECTIONS.clear();
            compileHintLevel = client.level;
        }
        pruneCompileHints();

        WorldgenSurfaceSnapshot snapshot = WorldgenSurfaceSampler.snapshot();
        if (snapshot.tiles().isEmpty()) {
            return;
        }

        EverviewMetrics.beginRenderFrame();

        var cameraPos = camera.position();
        double cameraX = cameraPos.x();
        double cameraY = cameraPos.y();
        double cameraZ = cameraPos.z();
        var frustum = camera.getCullFrustum();

        renderPass.setPipeline(
                RenderSystem.getCompiledPipeline(EverviewGpuPipeline.TERRAIN)
        );
        RenderSystem.bindDefaultUniforms(renderPass);

        RenderSystem.AutoStorageIndexBuffer quadIndices =
                RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);

        WorldgenLodRing l1Ring = snapshot.ringForLevel(1);
        WorldgenLodRing l2Ring = snapshot.ringForLevel(2);
        Set<Long> residentL1Tiles = new HashSet<>();
        Set<Long> residentL2Tiles = new HashSet<>();
        Map<SectionKey, Boolean> vanillaVisibility = new HashMap<>();
        Map<ChunkKey, ColumnOwnershipResult> vanillaColumns = new HashMap<>();
        int vanillaOwnedBatches = 0;
        int finerOwnedBatches = 0;
        int visibleLodBatches = 0;
        int loadedWaitingBatches = 0;
        double vanillaRadius =
                client.options.getEffectiveRenderDistance() * 16.0D;

        for (WorldgenSurfaceTile tile : snapshot.tiles()) {
            if (EverviewGpuTileCache.getResident(tile) == null) {
                continue;
            }

            if (tile.lodLevel() == 1) {
                residentL1Tiles.add(packTile(tile.tileX(), tile.tileZ()));
            } else if (tile.lodLevel() == 2) {
                residentL2Tiles.add(packTile(tile.tileX(), tile.tileZ()));
            }
        }

        for (WorldgenSurfaceTile tile : snapshot.tiles()) {
            WorldgenLodRing ring = snapshot.ringForLevel(tile.lodLevel());
            if (ring == null
                    || !tileBelongsToRing(
                            tile,
                            ring,
                            cameraX,
                            cameraZ
                    )) {
                continue;
            }

            AABB bounds = new AABB(
                    tile.minX(),
                    tile.minY() - 4.0,
                    tile.minZ(),
                    tile.maxX(),
                    tile.maxY() + 4.0,
                    tile.maxZ()
            );

            if (!frustum.isVisible(bounds)) {
                EverviewMetrics.recordCulledTile(tile.lodLevel());
                continue;
            }

            EverviewGpuTileCache.GpuTile gpuTile =
                    EverviewGpuTileCache.getResident(tile);
            if (gpuTile == null) {
                continue;
            }

            long started = System.nanoTime();
            GpuBuffer indexBuffer =
                    quadIndices.getBuffer(gpuTile.indexCount());

            Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
            double verticalBias = BASE_TERRAIN_BIAS
                    + Math.max(0, tile.lodLevel() - 1)
                    * RING_LAYER_BIAS;
            modelView.translate(
                    (float) (tile.minX() - cameraX),
                    (float) (-cameraY - verticalBias),
                    (float) (tile.minZ() - cameraZ)
            );

            GpuBufferSlice dynamicTransforms =
                    RenderSystem.getDynamicUniforms().writeTransform(
                            modelView,
                            COLOR_MODULATOR,
                            MODEL_OFFSET,
                            TEXTURE_MATRIX
                    );

            renderPass.setVertexBuffer(
                    0,
                    gpuTile.vertexBuffer().slice()
            );
            renderPass.setIndexBuffer(
                    indexBuffer,
                    quadIndices.type()
            );
            renderPass.setUniform(
                    "DynamicTransforms",
                    dynamicTransforms
            );

            boolean vanillaOwnership = tile.lodLevel() <= 3
                    && tileIntersectsVanillaOwnershipArea(
                            tile,
                            cameraX,
                            cameraZ,
                            vanillaRadius
                    );

            boolean finerLodMask = false;
            if (tile.lodLevel() == 2 && l1Ring != null) {
                finerLodMask = hasCoveredL2Batch(
                        gpuTile,
                        l1Ring,
                        residentL1Tiles,
                        cameraX,
                        cameraZ
                );
            } else if (tile.lodLevel() == 3 && l2Ring != null) {
                finerLodMask = hasCoveredUnderlayRegion(
                        gpuTile,
                        l2Ring,
                        residentL2Tiles,
                        cameraX,
                        cameraZ
                );
            }

            boolean drewAny = false;
            int drawnQuads = 0;

            if (!vanillaOwnership && !finerLodMask) {
                EverviewMetrics.recordSubmission(tile.lodLevel());
                renderPass.drawIndexed(
                        gpuTile.indexCount(),
                        1,
                        0,
                        0,
                        0
                );
                EverviewMetrics.recordDrawCall(false);
                drewAny = true;
                drawnQuads = gpuTile.indexCount() / 6;
            } else {
                int rangeFirstIndex = -1;
                int rangeIndexCount = 0;
                boolean rangeTouchesVanillaHandoff = false;

                for (EverviewGpuTileCache.DrawBatch batch
                        : gpuTile.drawBatches()) {
                    boolean ownedByFinerLod = false;

                    if (tile.lodLevel() == 2 && l1Ring != null) {
                        ownedByFinerLod = l1OwnsL2Batch(
                                batch,
                                l1Ring,
                                residentL1Tiles,
                                cameraX,
                                cameraZ
                        );
                    } else if (tile.lodLevel() == 3
                            && l2Ring != null) {
                        ownedByFinerLod = batch.underlayRegion()
                                && finerRingOwnsRegion(
                                        batch.regionTileX(),
                                        batch.regionTileZ(),
                                        l2Ring,
                                        residentL2Tiles,
                                        cameraX,
                                        cameraZ
                                );
                    }

                    ColumnOwnershipResult vanillaResult =
                            vanillaOwnership && batch.vanillaSensitive()
                                    ? vanillaOwnsBatch(
                                            client,
                                            batch,
                                            vanillaVisibility,
                                            vanillaColumns
                                    )
                                    : ColumnOwnershipResult.NOT_OWNED;
                    boolean ownedByVanilla = vanillaResult.owned();

                    if (ownedByFinerLod) {
                        finerOwnedBatches++;
                    }
                    if (ownedByVanilla) {
                        vanillaOwnedBatches++;
                    }
                    if (vanillaResult.loadedWaiting()) {
                        loadedWaitingBatches++;
                    }

                    if (ownedByFinerLod || ownedByVanilla) {
                        if (rangeIndexCount > 0) {
                            renderPass.drawIndexed(
                                    rangeIndexCount,
                                    1,
                                    rangeFirstIndex,
                                    0,
                                    0
                            );
                            EverviewMetrics.recordDrawCall(
                                    rangeTouchesVanillaHandoff
                            );
                            rangeFirstIndex = -1;
                            rangeIndexCount = 0;
                            rangeTouchesVanillaHandoff = false;
                        }
                        continue;
                    }

                    if (!drewAny) {
                        EverviewMetrics.recordSubmission(
                                tile.lodLevel()
                        );
                        drewAny = true;
                    }

                    if (rangeIndexCount == 0) {
                        rangeFirstIndex = batch.firstIndex();
                        rangeIndexCount = batch.indexCount();
                        rangeTouchesVanillaHandoff =
                                vanillaOwnership
                                        && batch.vanillaSensitive();
                    } else if (rangeFirstIndex + rangeIndexCount
                            == batch.firstIndex()) {
                        rangeIndexCount += batch.indexCount();
                        rangeTouchesVanillaHandoff |=
                                vanillaOwnership
                                        && batch.vanillaSensitive();
                    } else {
                        renderPass.drawIndexed(
                                rangeIndexCount,
                                1,
                                rangeFirstIndex,
                                0,
                                0
                        );
                        EverviewMetrics.recordDrawCall(
                                rangeTouchesVanillaHandoff
                        );
                        rangeFirstIndex = batch.firstIndex();
                        rangeIndexCount = batch.indexCount();
                        rangeTouchesVanillaHandoff =
                                vanillaOwnership
                                        && batch.vanillaSensitive();
                    }

                    drawnQuads += batch.indexCount() / 6;
                    visibleLodBatches++;
                }

                if (rangeIndexCount > 0) {
                    renderPass.drawIndexed(
                            rangeIndexCount,
                            1,
                            rangeFirstIndex,
                            0,
                            0
                    );
                    EverviewMetrics.recordDrawCall(
                            rangeTouchesVanillaHandoff
                    );
                }
            }

            if (!drewAny) {
                continue;
            }

            double centerX =
                    (tile.minX() + tile.maxX()) * 0.5;
            double centerZ =
                    (tile.minZ() + tile.maxZ()) * 0.5;
            double distance = Math.hypot(
                    centerX - cameraX,
                    centerZ - cameraZ
            );

            EverviewMetrics.recordTileDraw(
                    tile.lodLevel(),
                    System.nanoTime() - started,
                    drawnQuads,
                    distance
            );
        }

        lastVanillaOwnedBatches = vanillaOwnedBatches;
        lastFinerOwnedBatches = finerOwnedBatches;
        lastVisibleLodBatches = visibleLodBatches;
        lastLoadedWaitingBatches = loadedWaitingBatches;
    }

    public static OwnershipStats ownershipStats() {
        return new OwnershipStats(
                lastVanillaOwnedBatches,
                lastFinerOwnedBatches,
                lastVisibleLodBatches,
                lastLoadedWaitingBatches
        );
    }

    private static boolean tileIntersectsVanillaOwnershipArea(
            WorldgenSurfaceTile tile,
            double cameraX,
            double cameraZ,
            double vanillaRadius
    ) {
        double minX = tile.minX();
        double minZ = tile.minZ();
        double maxX = tile.maxX();
        double maxZ = tile.maxZ();

        double nearestX = Math.max(minX, Math.min(cameraX, maxX));
        double nearestZ = Math.max(minZ, Math.min(cameraZ, maxZ));
        double nearestDistance = Math.hypot(
                nearestX - cameraX,
                nearestZ - cameraZ
        );

        // M4.2: not a thin transition band anymore. Every near LOD tile that
        // reaches into the vanilla render radius must evaluate its
        // vanilla-sensitive batches against renderer-visible terrain.
        return nearestDistance
                <= vanillaRadius + VANILLA_OWNERSHIP_MARGIN_BLOCKS;
    }

    public static void noteRecentlyCompiledSection(BlockPos origin) {
        int chunkX = Math.floorDiv(origin.getX(), 16);
        int sectionY = Math.floorDiv(origin.getY(), 16);
        int chunkZ = Math.floorDiv(origin.getZ(), 16);

        RECENTLY_COMPILED_SECTIONS.put(
                new SectionKey(chunkX, sectionY, chunkZ),
                System.nanoTime()
        );
    }

    private static void pruneCompileHints() {
        if (RECENTLY_COMPILED_SECTIONS.isEmpty()) {
            return;
        }

        long now = System.nanoTime();
        RECENTLY_COMPILED_SECTIONS.entrySet().removeIf(
                entry -> now - entry.getValue() > RECENT_COMPILE_HINT_NANOS
        );
    }

    private static boolean recentlyCompiledSection(
            int chunkX,
            int sectionY,
            int chunkZ
    ) {
        Long compiledAt = RECENTLY_COMPILED_SECTIONS.get(
                new SectionKey(chunkX, sectionY, chunkZ)
        );

        return compiledAt != null
                && System.nanoTime() - compiledAt <= RECENT_COMPILE_HINT_NANOS;
    }

    private static ColumnOwnershipResult vanillaOwnsBatch(
            Minecraft client,
            EverviewGpuTileCache.DrawBatch batch,
            Map<SectionKey, Boolean> visibility,
            Map<ChunkKey, ColumnOwnershipResult> columns
    ) {
        ColumnOwnershipResult aOwned = vanillaChunkColumnOwned(
                client,
                batch.chunkAX(),
                batch.sectionY(),
                batch.chunkAZ(),
                visibility,
                columns
        );

        if (!batch.boundary()) {
            return aOwned;
        }

        ColumnOwnershipResult bOwned = vanillaChunkColumnOwned(
                client,
                batch.chunkBX(),
                batch.sectionY(),
                batch.chunkBZ(),
                visibility,
                columns
        );

        return new ColumnOwnershipResult(
                aOwned.owned() || bOwned.owned(),
                aOwned.loadedWaiting() || bOwned.loadedWaiting()
        );
    }

    private static ColumnOwnershipResult vanillaChunkColumnOwned(
            Minecraft client,
            int chunkX,
            int hintSectionY,
            int chunkZ,
            Map<SectionKey, Boolean> visibility,
            Map<ChunkKey, ColumnOwnershipResult> columns
    ) {
        ChunkKey key = new ChunkKey(chunkX, chunkZ);
        ColumnOwnershipResult cached = columns.get(key);
        if (cached != null) {
            return cached;
        }

        // M5.2: client chunk presence is not a handoff signal. At high travel
        // speed Minecraft can have the chunk loaded before its terrain is
        // actually compiled, uploaded and visible. Retiring Everview at that
        // point exposes a sky/white hole inside the nominal vanilla radius.
        boolean visible = vanillaSurfaceColumnVisible(
                client,
                chunkX,
                hintSectionY,
                chunkZ,
                visibility
        );
        boolean loaded = client.level != null
                && client.level.hasChunk(chunkX, chunkZ);

        ColumnOwnershipResult result = visible
                ? new ColumnOwnershipResult(true, false)
                : new ColumnOwnershipResult(false, loaded);
        columns.put(key, result);
        return result;
    }

    private static boolean vanillaSurfaceColumnVisible(
            Minecraft client,
            int chunkX,
            int sectionY,
            int chunkZ,
            Map<SectionKey, Boolean> visibility
    ) {
        // Fast local test around the LOD surface first.
        for (int offset = -2; offset <= 2; offset++) {
            if (vanillaSectionVisible(
                    client,
                    chunkX,
                    sectionY + offset,
                    chunkZ,
                    visibility
            )) {
                return true;
            }
        }

        // M5.1/M5.2: ownership belongs to the chunk column, not to the guessed LOD
        // surface section. Scan the rest of a generous vertical column so
        // oceans, cliffs, overhangs and large height mismatches cannot leave
        // an already-rendered vanilla column exposed to Everview.
        for (int offset = -24; offset <= 24; offset++) {
            if (offset >= -2 && offset <= 2) {
                continue;
            }

            if (vanillaSectionVisible(
                    client,
                    chunkX,
                    sectionY + offset,
                    chunkZ,
                    visibility
            )) {
                return true;
            }
        }

        // Recent compile hints are only a fallback signal; they never override
        // the requirement that some section in this column is renderer-ready.
        boolean recentlyUploaded = false;
        for (int offset = -24; offset <= 24; offset++) {
            if (recentlyCompiledSection(
                    chunkX,
                    sectionY + offset,
                    chunkZ
            )) {
                recentlyUploaded = true;
                break;
            }
        }

        if (!recentlyUploaded) {
            return false;
        }

        for (int offset = -24; offset <= 24; offset++) {
            if (vanillaSectionVisible(
                    client,
                    chunkX,
                    sectionY + offset,
                    chunkZ,
                    visibility
            )) {
                return true;
            }
        }

        return false;
    }

    private static boolean vanillaSectionVisible(
            Minecraft client,
            int chunkX,
            int sectionY,
            int chunkZ,
            Map<SectionKey, Boolean> visibility
    ) {
        SectionKey key = new SectionKey(chunkX, sectionY, chunkZ);
        Boolean cached = visibility.get(key);

        if (cached != null) {
            return cached;
        }

        boolean visible = client.levelRenderer.isSectionCompiledAndVisible(
                new BlockPos(
                        chunkX * 16 + 8,
                        sectionY * 16 + 8,
                        chunkZ * 16 + 8
                ),
                0L
        );
        visibility.put(key, visible);
        return visible;
    }

    public record OwnershipStats(
            int vanillaOwnedBatches,
            int finerOwnedBatches,
            int visibleLodBatches,
            int loadedWaitingBatches
    ) {
    }

    private record ColumnOwnershipResult(
            boolean owned,
            boolean loadedWaiting
    ) {
        private static final ColumnOwnershipResult NOT_OWNED =
                new ColumnOwnershipResult(false, false);
    }

    private record ChunkKey(
            int chunkX,
            int chunkZ
    ) {
    }

    private record SectionKey(
            int chunkX,
            int sectionY,
            int chunkZ
    ) {
    }

    private static boolean hasCoveredL2Batch(
            EverviewGpuTileCache.GpuTile gpuTile,
            WorldgenLodRing l1Ring,
            Set<Long> residentL1Tiles,
            double cameraX,
            double cameraZ
    ) {
        for (EverviewGpuTileCache.DrawBatch batch : gpuTile.drawBatches()) {
            if (l1OwnsL2Batch(
                    batch,
                    l1Ring,
                    residentL1Tiles,
                    cameraX,
                    cameraZ
            )) {
                return true;
            }
        }

        return false;
    }

    private static boolean l1OwnsL2Batch(
            EverviewGpuTileCache.DrawBatch batch,
            WorldgenLodRing l1Ring,
            Set<Long> residentL1Tiles,
            double cameraX,
            double cameraZ
    ) {
        if (!batch.vanillaSensitive()) {
            return false;
        }

        boolean aOwned = finerRingOwnsChunk(
                batch.chunkAX(),
                batch.chunkAZ(),
                l1Ring,
                residentL1Tiles,
                cameraX,
                cameraZ
        );

        if (!batch.boundary()) {
            return aOwned;
        }

        boolean bOwned = finerRingOwnsChunk(
                batch.chunkBX(),
                batch.chunkBZ(),
                l1Ring,
                residentL1Tiles,
                cameraX,
                cameraZ
        );

        // A wall exactly on a finer-tile boundary stays as fallback until both
        // sides are owned. This favors overlap over ever exposing a seam.
        return aOwned && bOwned;
    }

    private static boolean finerRingOwnsChunk(
            int chunkX,
            int chunkZ,
            WorldgenLodRing finerRing,
            Set<Long> residentFinerTiles,
            double cameraX,
            double cameraZ
    ) {
        int blockX = chunkX * 16 + 8;
        int blockZ = chunkZ * 16 + 8;
        int tileX = Math.floorDiv(blockX, finerRing.tileSize());
        int tileZ = Math.floorDiv(blockZ, finerRing.tileSize());

        return finerRingOwnsRegion(
                tileX,
                tileZ,
                finerRing,
                residentFinerTiles,
                cameraX,
                cameraZ
        );
    }

    private static boolean hasCoveredUnderlayRegion(
            EverviewGpuTileCache.GpuTile gpuTile,
            WorldgenLodRing finerRing,
            Set<Long> residentFinerTiles,
            double cameraX,
            double cameraZ
    ) {
        for (EverviewGpuTileCache.DrawBatch batch : gpuTile.drawBatches()) {
            if (batch.underlayRegion()
                    && finerRingOwnsRegion(
                            batch.regionTileX(),
                            batch.regionTileZ(),
                            finerRing,
                            residentFinerTiles,
                            cameraX,
                            cameraZ
                    )) {
                return true;
            }
        }

        return false;
    }

    private static boolean finerRingOwnsRegion(
            int tileX,
            int tileZ,
            WorldgenLodRing finerRing,
            Set<Long> residentFinerTiles,
            double cameraX,
            double cameraZ
    ) {
        return virtualFinerTileBelongsToRing(
                        tileX,
                        tileZ,
                        finerRing,
                        cameraX,
                        cameraZ
                )
                && residentFinerTiles.contains(packTile(tileX, tileZ));
    }

    private static boolean virtualFinerTileBelongsToRing(
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

        double centerX = (minX + maxX) * 0.5;
        double centerZ = (minZ + maxZ) * 0.5;
        double centerDistance = Math.hypot(
                centerX - cameraX,
                centerZ - cameraZ
        );

        double nearestX = Math.max(minX, Math.min(cameraX, maxX));
        double nearestZ = Math.max(minZ, Math.min(cameraZ, maxZ));
        double nearestDistance = Math.hypot(
                nearestX - cameraX,
                nearestZ - cameraZ
        );

        return centerDistance >= ring.innerRadiusBlocks()
                && nearestDistance <= ring.outerRadiusBlocks();
    }

    private static long packTile(int tileX, int tileZ) {
        return ((long) tileX << 32) ^ (tileZ & 0xFFFF_FFFFL);
    }

    /**
     * M3.5.2 keeps center ownership only at the vanilla -> L1 inner boundary,
     * where it already proved stable in M3.3.2. Every LOD-to-LOD boundary uses
     * tile/annulus intersection instead. The sampler already generates those
     * intersecting boundary tiles; rendering them removes the empty wedges that
     * center-only ownership can leave when neighboring rings use different tile
     * sizes.
     */
    private static boolean tileBelongsToRing(
            WorldgenSurfaceTile tile,
            WorldgenLodRing ring,
            double cameraX,
            double cameraZ
    ) {
        double minX = tile.minX();
        double minZ = tile.minZ();
        double maxX = tile.maxX();
        double maxZ = tile.maxZ();

        double nearestX = Math.max(minX, Math.min(cameraX, maxX));
        double nearestZ = Math.max(minZ, Math.min(cameraZ, maxZ));
        double nearestDistance = Math.hypot(
                nearestX - cameraX,
                nearestZ - cameraZ
        );

        double farthestDx = Math.max(
                Math.abs(minX - cameraX),
                Math.abs(maxX - cameraX)
        );
        double farthestDz = Math.max(
                Math.abs(minZ - cameraZ),
                Math.abs(maxZ - cameraZ)
        );
        double farthestDistance = Math.hypot(farthestDx, farthestDz);

        if (ring.lodLevel() == 1) {
            double centerX = (minX + maxX) * 0.5;
            double centerZ = (minZ + maxZ) * 0.5;
            double centerDistance = Math.hypot(
                    centerX - cameraX,
                    centerZ - cameraZ
            );

            // Preserve the stable M3.3.2 vanilla handoff: L1 does not move
            // farther inward than center ownership allows. Its outer edge may
            // overlap L2, however, so there is always terrain under that seam.
            return centerDistance >= ring.innerRadiusBlocks()
                    && nearestDistance <= ring.outerRadiusBlocks();
        }

        return nearestDistance <= ring.outerRadiusBlocks()
                && farthestDistance >= ring.innerRadiusBlocks();
    }
}
