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
 * M3.1.1 baked-material distant terrain renderer.
 *
 * Material breakup is precomputed when a tile is generated/loaded from cache.
 * The hot render path now only submits baked vertex colors instead of hashing
 * every visible vertex every frame.
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
            WorldgenLodRing ring = snapshot.ringForLevel(tile.lodLevel());
            if (ring == null) {
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

            EverviewMetrics.recordSubmission(tile.lodLevel());

            context.submitNodeCollector().submitCustomGeometry(
                    context.poseStack(),
                    RenderTypes.debugQuads(),
                    (poseState, consumer) -> {
                        long start = System.nanoTime();
                        DrawStats drawStats = drawWorldgenTile(
                                poseState.pose(),
                                consumer,
                                tile,
                                ring,
                                camera
                        );
                        EverviewMetrics.recordTileDraw(
                                tile.lodLevel(),
                                System.nanoTime() - start,
                                drawStats.emittedQuads(),
                                drawStats.maxQuadDistance()
                        );
                    }
            );
        }
    }

    private static DrawStats drawWorldgenTile(
            Matrix4fc pose,
            VertexConsumer consumer,
            WorldgenSurfaceTile tile,
            WorldgenLodRing ring,
            Camera camera
    ) {
        var cameraPos = camera.position();
        double cameraX = cameraPos.x();
        double cameraY = cameraPos.y();
        double cameraZ = cameraPos.z();

        double innerSq = (double) ring.innerRadiusBlocks() * ring.innerRadiusBlocks();
        double outerSq = (double) ring.outerRadiusBlocks() * ring.outerRadiusBlocks();

        int emittedQuads = 0;
        double maxDistanceSq = 0.0;
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

            drawWorldgenVertex(
                    pose, consumer, vertices, tile.colors(),
                    i, cameraX, cameraY, cameraZ
            );
            drawWorldgenVertex(
                    pose, consumer, vertices, tile.colors(),
                    i + 3, cameraX, cameraY, cameraZ
            );
            drawWorldgenVertex(
                    pose, consumer, vertices, tile.colors(),
                    i + 6, cameraX, cameraY, cameraZ
            );
            drawWorldgenVertex(
                    pose, consumer, vertices, tile.colors(),
                    i + 9, cameraX, cameraY, cameraZ
            );
            emittedQuads++;
            maxDistanceSq = Math.max(maxDistanceSq, distanceSq);
        }

        return new DrawStats(emittedQuads, Math.sqrt(maxDistanceSq));
    }

    private static void drawWorldgenVertex(
            Matrix4fc pose,
            VertexConsumer consumer,
            int[] vertices,
            int[] colors,
            int index,
            double cameraX,
            double cameraY,
            double cameraZ
    ) {
        int worldX = vertices[index];
        int worldY = vertices[index + 1];
        int worldZ = vertices[index + 2];

        float x = (float) (worldX - cameraX);
        // Keep LOD a fraction below vanilla terrain during the overlap band so
        // real chunks win depth cleanly instead of z-fighting with the coarse mesh.
        float y = (float) (worldY - 0.22D - cameraY);
        float z = (float) (worldZ - cameraZ);

        int vertexIndex = index / 3;
        int rgb = colors[vertexIndex];
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;

        consumer.addVertex(pose, x, y, z)
                .setColor(red, green, blue, 255);
    }

    private record DrawStats(int emittedQuads, double maxQuadDistance) {
    }

}
