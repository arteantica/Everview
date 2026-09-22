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
 * M1 deliberately uses Fabric's submit-node path instead of immediate OpenGL calls.
 * That keeps Everview aligned with Minecraft's modern renderer and gives us a clean
 * seam to replace the temporary debug geometry with persistent GPU buffers later.
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

        context.submitNodeCollector().submitCustomGeometry(
                context.poseStack(),
                RenderTypes.debugQuads(),
                (poseState, consumer) -> drawSnapshot(poseState.pose(), consumer, snapshot, camera)
        );
    }

    private static void drawSnapshot(
            Matrix4fc pose,
            VertexConsumer consumer,
            SurfaceSnapshot snapshot,
            Camera camera
    ) {
        var cameraPos = camera.position();
        double cameraX = cameraPos.x();
        double cameraY = cameraPos.y();
        double cameraZ = cameraPos.z();

        int[] vertices = snapshot.vertices();
        int minY = snapshot.minY();
        int maxY = Math.max(minY + 1, snapshot.maxY());

        for (int i = 0; i < vertices.length; i += 3) {
            int worldX = vertices[i];
            int worldY = vertices[i + 1];
            int worldZ = vertices[i + 2];

            float x = (float) (worldX - cameraX);
            float y = (float) (worldY + 0.18D - cameraY);
            float z = (float) (worldZ - cameraZ);

            float t = (worldY - minY) / (float) (maxY - minY);
            t = Math.max(0.0F, Math.min(1.0F, t));

            int red = Math.round(45.0F + 175.0F * t);
            int green = Math.round(145.0F + 90.0F * t);
            int blue = Math.round(60.0F + 175.0F * t);

            consumer.addVertex(pose, x, y, z)
                    .setColor(red, green, blue, 150);
        }
    }
}
