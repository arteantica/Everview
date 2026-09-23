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
 * M3.8.2 persistent-GPU distant terrain renderer. Section-level ownership is
 * still confined to the vanilla boundary, but adjacent visible handoff batches
 * are now coalesced into contiguous index ranges before submission. This keeps
 * the exact same ownership decisions while drastically reducing CPU draw calls.
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
    private static final double LIVE_HANDOFF_MARGIN_BLOCKS = 64.0D;

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

        renderPass.setPipeline(RenderSystem.getCompiledPipeline(EverviewGpuPipeline.TERRAIN));
        RenderSystem.bindDefaultUniforms(renderPass);

        RenderSystem.AutoStorageIndexBuffer quadIndices =
                RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);

        WorldgenLodRing l1Ring = snapshot.ringForLevel(1);
        Set<Long> residentL1Tiles = new HashSet<>();
        Map<SectionKey, Boolean> vanillaVisibility = new HashMap<>();
        double vanillaRadius =
                client.options.getEffectiveRenderDistance() * 16.0D;

        if (l1Ring != null) {
            for (WorldgenSurfaceTile tile : snapshot.tiles()) {
                if (tile.lodLevel() == 1
                        && EverviewGpuTileCache.getResident(tile) != null) {
                    residentL1Tiles.add(packTile(tile.tileX(), tile.tileZ()));
                }
            }
        }

        for (WorldgenSurfaceTile tile : snapshot.tiles()) {
            WorldgenLodRing ring = snapshot.ringForLevel(tile.lodLevel());
            if (ring == null || !tileBelongsToRing(tile, ring, cameraX, cameraZ)) {
                continue;
            }

            // M3.5.5: L2 remains generated and GPU-resident as the roaming
            // safety net, but an interior L2 tile is not submitted when every
            // 32-block L1 tile above it is already resident. Boundary L2 tiles
            // still draw because they own terrain outside the L1 annulus.
            if (tile.lodLevel() == 2
                    && l1Ring != null
                    && fullyCoveredByResidentL1(
                            tile,
                            l1Ring,
                            residentL1Tiles,
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

            GpuBuffer indexBuffer = quadIndices.getBuffer(gpuTile.indexCount());

            Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
            double verticalBias = BASE_TERRAIN_BIAS
                    + Math.max(0, tile.lodLevel() - 1) * RING_LAYER_BIAS;
            modelView.translate(
                    (float) (tile.minX() - cameraX),
                    (float) (-cameraY - verticalBias),
                    (float) (tile.minZ() - cameraZ)
            );

            GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                    .writeTransform(
                            modelView,
                            COLOR_MODULATOR,
                            MODEL_OFFSET,
                            TEXTURE_MATRIX
                    );

            renderPass.setVertexBuffer(0, gpuTile.vertexBuffer().slice());
            renderPass.setIndexBuffer(indexBuffer, quadIndices.type());
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);

            boolean liveHandoff = tile.lodLevel() <= 2
                    && tileIntersectsLiveHandoffBand(
                            tile,
                            cameraX,
                            cameraZ,
                            vanillaRadius
                    );

            boolean drewAny = false;
            int drawnQuads = 0;

            if (!liveHandoff) {
                // Fast path: vanilla cannot overlap this tile, so do not walk
                // section batches or query renderer ownership at all.
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

                for (EverviewGpuTileCache.DrawBatch batch : gpuTile.drawBatches()) {
                    boolean ownedByVanilla = batch.vanillaSensitive()
                            && vanillaOwnsBatch(
                                    client,
                                    batch,
                                    vanillaVisibility
                            );

                    if (ownedByVanilla) {
                        if (rangeIndexCount > 0) {
                            renderPass.drawIndexed(
                                    rangeIndexCount,
                                    1,
                                    rangeFirstIndex,
                                    0,
                                    0
                            );
                            EverviewMetrics.recordDrawCall(true);
                            rangeFirstIndex = -1;
                            rangeIndexCount = 0;
                        }
                        continue;
                    }

                    if (!drewAny) {
                        EverviewMetrics.recordSubmission(tile.lodLevel());
                        drewAny = true;
                    }

                    // DrawBatch ranges are stored consecutively in the shared
                    // index stream. If the next visible batch directly follows
                    // the current range, fold it into the same draw call.
                    if (rangeIndexCount == 0) {
                        rangeFirstIndex = batch.firstIndex();
                        rangeIndexCount = batch.indexCount();
                    } else if (rangeFirstIndex + rangeIndexCount
                            == batch.firstIndex()) {
                        rangeIndexCount += batch.indexCount();
                    } else {
                        renderPass.drawIndexed(
                                rangeIndexCount,
                                1,
                                rangeFirstIndex,
                                0,
                                0
                        );
                        EverviewMetrics.recordDrawCall(true);
                        rangeFirstIndex = batch.firstIndex();
                        rangeIndexCount = batch.indexCount();
                    }

                    drawnQuads += batch.indexCount() / 6;
                }

                if (rangeIndexCount > 0) {
                    renderPass.drawIndexed(
                            rangeIndexCount,
                            1,
                            rangeFirstIndex,
                            0,
                            0
                    );
                    EverviewMetrics.recordDrawCall(true);
                }
            }

            if (!drewAny) {
                continue;
            }

            double centerX = (tile.minX() + tile.maxX()) * 0.5;
            double centerZ = (tile.minZ() + tile.maxZ()) * 0.5;
            double distance = Math.hypot(centerX - cameraX, centerZ - cameraZ);

            EverviewMetrics.recordTileDraw(
                    tile.lodLevel(),
                    System.nanoTime() - started,
                    drawnQuads,
                    distance
            );
        }
    }

    private static boolean tileIntersectsLiveHandoffBand(
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

        double farthestDx = Math.max(
                Math.abs(minX - cameraX),
                Math.abs(maxX - cameraX)
        );
        double farthestDz = Math.max(
                Math.abs(minZ - cameraZ),
                Math.abs(maxZ - cameraZ)
        );
        double farthestDistance = Math.hypot(farthestDx, farthestDz);

        double bandInner = Math.max(
                0.0D,
                vanillaRadius - LIVE_HANDOFF_MARGIN_BLOCKS
        );
        double bandOuter = vanillaRadius + LIVE_HANDOFF_MARGIN_BLOCKS;

        return nearestDistance <= bandOuter
                && farthestDistance >= bandInner;
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

    private static boolean vanillaOwnsBatch(
            Minecraft client,
            EverviewGpuTileCache.DrawBatch batch,
            Map<SectionKey, Boolean> visibility
    ) {
        if (batch.surface()) {
            return vanillaSurfaceColumnVisible(
                    client,
                    batch.chunkAX(),
                    batch.sectionY(),
                    batch.chunkAZ(),
                    visibility
            );
        }

        boolean aVisible = vanillaSectionVisible(
                client,
                batch.chunkAX(),
                batch.sectionY(),
                batch.chunkAZ(),
                visibility
        );

        if (!batch.boundary()) {
            return aVisible;
        }

        boolean bVisible = vanillaSectionVisible(
                client,
                batch.chunkBX(),
                batch.sectionY(),
                batch.chunkBZ(),
                visibility
        );

        // Boundary walls are the artifact we saw in M3.7.4.2. Once either
        // adjacent vanilla side is renderer-ready, the wall is no longer
        // needed as a safety face and is suppressed.
        return aVisible || bVisible;
    }

    private static boolean vanillaSurfaceColumnVisible(
            Minecraft client,
            int chunkX,
            int sectionY,
            int chunkZ,
            Map<SectionKey, Boolean> visibility
    ) {
        // LOD/worldgen surface height and the vanilla rendered surface can land
        // on opposite sides of a 16-block section boundary. Keep the proven
        // +/-1 visible-section rule first.
        for (int offset = -1; offset <= 1; offset++) {
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

        // M3.7.4.6.1: RenderSection.updateUploadTime() fires when the compiled
        // vanilla mesh reaches the upload handoff, slightly before the section
        // necessarily appears in the final visibility list. Use that exact
        // event as a short-lived early-ownership hint for TOP faces only. The
        // hint expires automatically, so if vanilla never becomes visible the
        // persistent Everview fallback returns instead of leaving a stale hole.
        for (int offset = -1; offset <= 1; offset++) {
            if (recentlyCompiledSection(
                    chunkX,
                    sectionY + offset,
                    chunkZ
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

    private record SectionKey(
            int chunkX,
            int sectionY,
            int chunkZ
    ) {
    }

    private static boolean fullyCoveredByResidentL1(
            WorldgenSurfaceTile coarseTile,
            WorldgenLodRing l1Ring,
            Set<Long> residentL1Tiles,
            double cameraX,
            double cameraZ
    ) {
        int l1TileSize = l1Ring.tileSize();
        int minTileX = Math.floorDiv(coarseTile.minX(), l1TileSize);
        int maxTileX = Math.floorDiv(coarseTile.maxX() - 1, l1TileSize);
        int minTileZ = Math.floorDiv(coarseTile.minZ(), l1TileSize);
        int maxTileZ = Math.floorDiv(coarseTile.maxZ() - 1, l1TileSize);

        boolean checkedAny = false;

        for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
            for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                if (!virtualL1TileBelongsToRing(
                        tileX,
                        tileZ,
                        l1Ring,
                        cameraX,
                        cameraZ
                )) {
                    // Part of this coarse tile is outside L1 ownership, so L2
                    // still has real terrain to provide there.
                    return false;
                }

                checkedAny = true;
                if (!residentL1Tiles.contains(packTile(tileX, tileZ))) {
                    return false;
                }
            }
        }

        return checkedAny;
    }

    private static boolean virtualL1TileBelongsToRing(
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
