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
 * M2.4 distance-ladder diagnostic renderer.
 *
 * Each progressive ring now has an intentionally obvious diagnostic palette and
 * independent cull/submit/draw/quad counters. This lets us distinguish "generated
 * successfully" from "actually surviving the camera/frustum render path".
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

            drawWorldgenVertex(pose, consumer, vertices, i, cameraX, cameraY, cameraZ, tile);
            drawWorldgenVertex(pose, consumer, vertices, i + 3, cameraX, cameraY, cameraZ, tile);
            drawWorldgenVertex(pose, consumer, vertices, i + 6, cameraX, cameraY, cameraZ, tile);
            drawWorldgenVertex(pose, consumer, vertices, i + 9, cameraX, cameraY, cameraZ, tile);
            emittedQuads++;
            maxDistanceSq = Math.max(maxDistanceSq, distanceSq);
        }

        return new DrawStats(emittedQuads, Math.sqrt(maxDistanceSq));
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

        float elevation = (worldY - tile.seaLevel()) / 160.0F;
        elevation = Math.max(0.0F, Math.min(1.0F, elevation));

        int red;
        int green;
        int blue;

        // Distance-ladder palette:
        // L1 green/cyan, L2 orange, L3 purple, L4 red, L5 yellow.
        // Water follows the same ring identity instead of sharing one blue.
        switch (tile.lodLevel()) {
            case 1 -> {
                if (worldY <= tile.seaLevel() + 1) {
                    red = 25;
                    green = 145;
                    blue = 220;
                } else {
                    red = clampColor(45.0F + 70.0F * elevation);
                    green = clampColor(175.0F + 70.0F * elevation);
                    blue = clampColor(75.0F + 100.0F * elevation);
                }
            }
            case 2 -> {
                if (worldY <= tile.seaLevel() + 1) {
                    red = 220;
                    green = 125;
                    blue = 30;
                } else {
                    red = clampColor(205.0F + 45.0F * elevation);
                    green = clampColor(125.0F + 90.0F * elevation);
                    blue = clampColor(35.0F + 80.0F * elevation);
                }
            }
            case 3 -> {
                if (worldY <= tile.seaLevel() + 1) {
                    red = 155;
                    green = 65;
                    blue = 220;
                } else {
                    red = clampColor(175.0F + 70.0F * elevation);
                    green = clampColor(55.0F + 70.0F * elevation);
                    blue = clampColor(180.0F + 70.0F * elevation);
                }
            }
            case 4 -> {
                if (worldY <= tile.seaLevel() + 1) {
                    red = 220;
                    green = 55;
                    blue = 75;
                } else {
                    red = clampColor(205.0F + 45.0F * elevation);
                    green = clampColor(45.0F + 65.0F * elevation);
                    blue = clampColor(45.0F + 55.0F * elevation);
                }
            }
            default -> {
                if (worldY <= tile.seaLevel() + 1) {
                    red = 225;
                    green = 210;
                    blue = 45;
                } else {
                    red = clampColor(215.0F + 40.0F * elevation);
                    green = clampColor(185.0F + 60.0F * elevation);
                    blue = clampColor(40.0F + 60.0F * elevation);
                }
            }
        }

        consumer.addVertex(pose, x, y, z)
                .setColor(red, green, blue, 190);
    }

    private record DrawStats(int emittedQuads, double maxQuadDistance) {
    }

    private static int clampColor(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }
}
