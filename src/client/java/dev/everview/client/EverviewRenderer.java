package dev.everview.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/**
 * Persistent-GPU distant terrain renderer.
 *
 * M3.5 keeps visibility/ownership policy here while delegating concrete GPU
 * uploads, vertex formats, pipelines and draw submission to a terrain backend.
 * That boundary is intentional: a future Iris/shader backend can consume the
 * same generated tiles without coupling the worldgen/LOD code to shader APIs.
 */
public final class EverviewRenderer {
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
                EverviewRenderBackends.active().prepareFrame(client.level, snapshot);
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
        EverviewTerrainBackend backend = EverviewRenderBackends.active();

        backend.beginOpaquePass(renderPass);

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

            Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
            modelView.translate(
                    (float) (tile.minX() - cameraX),
                    (float) (-cameraY - 0.22D),
                    (float) (tile.minZ() - cameraZ)
            );

            long started = System.nanoTime();
            if (!backend.drawTile(renderPass, tile, modelView)) {
                continue;
            }

            EverviewMetrics.recordSubmission(tile.lodLevel());

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
        double centerDistance = Math.hypot(centerX - cameraX, centerZ - cameraZ);

        // Center ownership is the stable M3.3.2 handoff: 32-block L1 tiles may
        // overlap slightly under vanilla terrain, with depth deciding the winner.
        return centerDistance >= ring.innerRadiusBlocks()
                && centerDistance <= ring.outerRadiusBlocks();
    }
}
