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
 * The near debug surface still comes from loaded chunks. The far ring now comes
 * from direct generator height queries on the integrated server and can therefore
 * exist beyond Minecraft's loaded client chunks.
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

        SurfaceSnapshot near = LoadedSurfaceSampler.snapshot();
        WorldgenSurfaceSnapshot far = WorldgenSurfaceSampler.snapshot();

        if (near.isEmpty() && far.tiles().isEmpty()) {
            return;
        }

        EverviewMetrics.beginRenderFrame();

        if (!near.isEmpty()) {
            submitNearTiles(context, camera, near);
        }

        if (!far.tiles().isEmpty()) {
            submitWorldgenTiles(context, camera, far);
        }
    }

    private static void submitNearTiles(
            LevelRenderContext context,
            Camera camera,
            SurfaceSnapshot snapshot
    ) {
        var frustum = camera.getCullFrustum();

        for (SurfaceTile tile : snapshot.tiles()) {
            AABB bounds = new AABB(
                    tile.minX(),
                    tile.minY() - 2.0,
                    tile.minZ(),
                    tile.maxX(),
                    tile.maxY() + 2.0,
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
                        drawNearTile(poseState.pose(), consumer, tile, snapshot, camera);
                        EverviewMetrics.recordTileDraw(System.nanoTime() - start);
                    }
            );
        }
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

    private static void drawNearTile(
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
        double innerSkipSq =
                (double) LoadedSurfaceSampler.INNER_SKIP_RADIUS * LoadedSurfaceSampler.INNER_SKIP_RADIUS;

        float tileTint = ((tile.tileX() + tile.tileZ()) & 1) == 0 ? 1.0F : 0.90F;

        int[] vertices = tile.vertices();

        for (int i = 0; i < vertices.length; i += 12) {
            double quadCenterX = (vertices[i] + vertices[i + 6]) * 0.5;
            double quadCenterZ = (vertices[i + 2] + vertices[i + 8]) * 0.5;
            double dx = quadCenterX - cameraX;
            double dz = quadCenterZ - cameraZ;

            if (dx * dx + dz * dz < innerSkipSq) {
                continue;
            }

            drawNearVertex(pose, consumer, vertices, i, cameraX, cameraY, cameraZ,
                    globalMinY, globalMaxY, tileTint);
            drawNearVertex(pose, consumer, vertices, i + 3, cameraX, cameraY, cameraZ,
                    globalMinY, globalMaxY, tileTint);
            drawNearVertex(pose, consumer, vertices, i + 6, cameraX, cameraY, cameraZ,
                    globalMinY, globalMaxY, tileTint);
            drawNearVertex(pose, consumer, vertices, i + 9, cameraX, cameraY, cameraZ,
                    globalMinY, globalMaxY, tileTint);
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

    private static void drawNearVertex(
            Matrix4fc pose,
            VertexConsumer consumer,
            int[] vertices,
            int index,
            double cameraX,
            double cameraY,
            double cameraZ,
            int globalMinY,
            int globalMaxY,
            float tileTint
    ) {
        int worldX = vertices[index];
        int worldY = vertices[index + 1];
        int worldZ = vertices[index + 2];

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
            // Temporary ocean classification for the smoke test.
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
