package dev.everview.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.HashSet;
import java.util.Set;

/**
 * M3.7.4.1 persistent-GPU distant terrain renderer. Near LOD buffers are
 * clipped only against vanilla chunks confirmed loaded by the client.
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

            EverviewMetrics.recordSubmission(tile.lodLevel());
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
            renderPass.drawIndexed(gpuTile.indexCount(), 1, 0, 0, 0);

            double centerX = (tile.minX() + tile.maxX()) * 0.5;
            double centerZ = (tile.minZ() + tile.maxZ()) * 0.5;
            double distance = Math.hypot(centerX - cameraX, centerZ - cameraZ);

            EverviewMetrics.recordTileDraw(
                    tile.lodLevel(),
                    System.nanoTime() - started,
                    tile.vertices().length / 12,
                    distance
            );
        }
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
