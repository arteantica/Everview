package dev.everview.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4fc;

/**
 * M2 renderer.
 *
 * Only the true distant worldgen ring is visible now. The old loaded-chunk
 * heightfield from M1 is intentionally not submitted: it was useful to prove
 * renderer integration, but it produces false caps over cave mouths and
 * overhangs. Near terrain remains vanilla/Sodium until the voxel-derived near
 * LOD path replaces that diagnostic surface.
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

        WorldgenSurfaceSnapshot far = WorldgenSurfaceSampler.snapshot();
        if (far.tiles().isEmpty()) {
            return;
        }

        EverviewMetrics.beginRenderFrame();
        submitWorldgenTiles(context, camera, far);
    }

    private static void submitWorldgenTiles(
            LevelRenderContext context,
            Camera camera,
            WorldgenSurfaceSnapshot snapshot
    ) {
        var frustum = camera.getCullFrustum();

        for (WorldgenSurfaceTile tile : snapshot.tiles()) {
            AABB bounds = new AABB(
                    tile.minX(),
                    tile.minY() - 4.0,
                    tile.minZ(),
                    tile.maxX(),
                    tile.maxY() + 4.0,
                    tile.maxZ()
            );

            if (!frustum.isVisible(bounds)) {
                EverviewMetrics.recordCulledTile();
                continue;
            }

            EverviewMetrics.recordSubmission();

            context.submitNodeCollector().submitCustomGeometry(
                    context.poseStack(),
                    RenderTypes.debugQuads(),
                    (poseState, consumer) -> {
                        long start = System.nanoTime();
                        drawWorldgenTile(poseState.pose(), consumer, tile, snapshot, camera);
                        EverviewMetrics.recordTileDraw(System.nanoTime() - start);
                    }
            );
        }
    }

    private static void drawWorldgenTile(
            Matrix4fc pose,
            VertexConsumer consumer,
            WorldgenSurfaceTile tile,
            WorldgenSurfaceSnapshot snapshot,
            Camera camera
    ) {
        var cameraPos = camera.position();
        double cameraX = cameraPos.x();
        double cameraY = cameraPos.y();
        double cameraZ = cameraPos.z();

        double innerSq = (double) snapshot.innerRadiusBlocks() * snapshot.innerRadiusBlocks();
        double outerSq = (double) snapshot.outerRadiusBlocks() * snapshot.outerRadiusBlocks();

        int[] vertices = tile.vertices();

        for (int i = 0; i < vertices.length; i += 12) {
            double quadCenterX = (vertices[i] + vertices[i + 6]) * 0.5;
            double quadCenterZ = (vertices[i + 2] + vertices[i + 8]) * 0.5;
            double dx = quadCenterX - cameraX;
            double dz = quadCenterZ - cameraZ;
            double distanceSq = dx * dx + dz * dz;

            if (distanceSq < innerSq || distanceSq > outerSq) {
                continue;
            }

            drawWorldgenVertex(pose, consumer, vertices, i, cameraX, cameraY, cameraZ, tile);
            drawWorldgenVertex(pose, consumer, vertices, i + 3, cameraX, cameraY, cameraZ, tile);
            drawWorldgenVertex(pose, consumer, vertices, i + 6, cameraX, cameraY, cameraZ, tile);
            drawWorldgenVertex(pose, consumer, vertices, i + 9, cameraX, cameraY, cameraZ, tile);
        }
    }

    private static void drawWorldgenVertex(
            Matrix4fc pose,
            VertexConsumer consumer,
            int[] vertices,
            int index,
            double cameraX,
            double cameraY,
            double cameraZ,
            WorldgenSurfaceTile tile
    ) {
        int worldX = vertices[index];
        int worldY = vertices[index + 1];
        int worldZ = vertices[index + 2];

        float x = (float) (worldX - cameraX);
        float y = (float) (worldY + 0.10D - cameraY);
        float z = (float) (worldZ - cameraZ);

        int red;
        int green;
        int blue;

        if (worldY <= tile.seaLevel() + 1) {
            red = 45;
            green = 115;
            blue = 205;
        } else {
            float t = (worldY - tile.seaLevel()) / 140.0F;
            t = Math.max(0.0F, Math.min(1.0F, t));

            red = clampColor(70.0F + 165.0F * t);
            green = clampColor(155.0F + 75.0F * t);
            blue = clampColor(75.0F + 160.0F * t);
        }

        consumer.addVertex(pose, x, y, z)
                .setColor(red, green, blue, 175);
    }

    private static int clampColor(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }
}
