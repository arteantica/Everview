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

/**
 * M3.3 persistent-GPU distant terrain renderer.
 *
 * Important 26.3 detail: LevelRenderEvents.AFTER_OPAQUE_TERRAIN fires while
 * Minecraft's opaque terrain RenderPass is still open. Everview therefore
 * draws into that existing pass instead of trying to create a nested pass.
 */
public final class EverviewRenderer {
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

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

        for (WorldgenSurfaceTile tile : snapshot.tiles()) {
            WorldgenLodRing ring = snapshot.ringForLevel(tile.lodLevel());
            if (ring == null || !tileBelongsToRing(tile, ring, cameraX, cameraZ)) {
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
            modelView.translate(
                    (float) (tile.minX() - cameraX),
                    (float) (-cameraY - 0.22D),
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

    /**
     * Persistent buffers are whole-tile draws, so ring ownership is decided at
     * tile granularity instead of scanning every quad every frame.
     */
    private static boolean tileBelongsToRing(
            WorldgenSurfaceTile tile,
            WorldgenLodRing ring,
            double cameraX,
            double cameraZ
    ) {
        double centerX = (tile.minX() + tile.maxX()) * 0.5;
        double centerZ = (tile.minZ() + tile.maxZ()) * 0.5;
        double distance = Math.hypot(centerX - cameraX, centerZ - cameraZ);

        double inner = ring.innerRadiusBlocks();
        if (ring.lodLevel() == 1) {
            inner = Math.max(0.0, inner - tile.tileSize() * 0.5);
        }

        return distance >= inner && distance <= ring.outerRadiusBlocks();
    }
}
