package dev.everview.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.joml.Matrix4fc;

/**
 * 26.3 render-graph bridge.
 *
 * M1.1 keeps the visible debug surface but now submits logical 64-block terrain tiles
 * independently. This gives us the exact seam needed for per-tile culling and persistent
 * GPU buffers in the next milestone.
 */
public final class EverviewRenderer {
    private EverviewRenderer() {
    }

    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(EverviewRenderer::collect);
    }

    private static void collect(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        Camera camera = client.gameRenderer.mainCamera();

        if (client.level == null || client.player == null || !camera.isInitialized()) {
            return;
        }

        SurfaceSnapshot snapshot = LoadedSurfaceSampler.snapshot();
        if (snapshot.isEmpty()) {
            return;
        }

        EverviewMetrics.beginRenderFrame(snapshot.tiles().size());

        for (SurfaceTile tile : snapshot.tiles()) {
            context.submitNodeCollector().submitCustomGeometry(
                    context.poseStack(),
                    RenderTypes.debugQuads(),
                    (poseState, consumer) -> {
                        long start = System.nanoTime();
                        drawTile(poseState.pose(), consumer, tile, snapshot, camera);
                        EverviewMetrics.recordTileDraw(System.nanoTime() - start);
                    }
            );
        }
    }

    private static void drawTile(
            Matrix4fc pose,
            VertexConsumer consumer,
            SurfaceTile tile,
            SurfaceSnapshot snapshot,
            Camera camera
    ) {
        var cameraPos = camera.position();
        double cameraX = cameraPos.x();
        double cameraY = cameraPos.y();
        double cameraZ = cameraPos.z();

        int globalMinY = snapshot.minY();
        int globalMaxY = Math.max(globalMinY + 1, snapshot.maxY());

        // Tiny alternating tint makes the logical tile boundaries visible during M1.1.
        float tileTint = ((tile.tileX() + tile.tileZ()) & 1) == 0 ? 1.0F : 0.90F;

        int[] vertices = tile.vertices();
        for (int i = 0; i < vertices.length; i += 3) {
            int worldX = vertices[i];
            int worldY = vertices[i + 1];
            int worldZ = vertices[i + 2];

            float x = (float) (worldX - cameraX);
            float y = (float) (worldY + 0.16D - cameraY);
            float z = (float) (worldZ - cameraZ);

            float t = (worldY - globalMinY) / (float) (globalMaxY - globalMinY);
            t = Math.max(0.0F, Math.min(1.0F, t));

            int red = clampColor((45.0F + 175.0F * t) * tileTint);
            int green = clampColor((145.0F + 90.0F * t) * tileTint);
            int blue = clampColor((60.0F + 175.0F * t) * tileTint);

            consumer.addVertex(pose, x, y, z)
                    .setColor(red, green, blue, 145);
        }
    }

    private static int clampColor(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }
}
