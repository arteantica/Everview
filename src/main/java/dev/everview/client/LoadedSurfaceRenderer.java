package dev.everview.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * M1 smoke-test renderer.
 *
 * It intentionally samples only chunks already present in the client's chunk cache.
 * This proves Everview's world render path without causing chunk loads or touching
 * distant generation yet. The immediate BufferBuilder path is temporary; persistent
 * GPU buffers replace it once the visual path is validated.
 */
public final class LoadedSurfaceRenderer {
    private static final int SAMPLE_SPACING = 8;
    private static final int RADIUS_BLOCKS = 160;
    private static final int INNER_SKIP_BLOCKS = 24;
    private static final int INVALID_Y = Integer.MIN_VALUE;

    private LoadedSurfaceRenderer() {
    }

    public static RenderStats render(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return RenderStats.EMPTY;
        }

        var camera = event.getCamera();
        var cameraPos = camera.getPosition();

        int originX = Mth.floor(cameraPos.x);
        int originZ = Mth.floor(cameraPos.z);
        int centerX = Math.floorDiv(originX, SAMPLE_SPACING) * SAMPLE_SPACING;
        int centerZ = Math.floorDiv(originZ, SAMPLE_SPACING) * SAMPLE_SPACING;

        int samplesAcross = (RADIUS_BLOCKS * 2 / SAMPLE_SPACING) + 1;
        int[][] heights = new int[samplesAcross][samplesAcross];
        int validSamples = 0;

        for (int gz = 0; gz < samplesAcross; gz++) {
            int worldZ = centerZ - RADIUS_BLOCKS + gz * SAMPLE_SPACING;
            for (int gx = 0; gx < samplesAcross; gx++) {
                int worldX = centerX - RADIUS_BLOCKS + gx * SAMPLE_SPACING;
                int y = sampleSurface(level, worldX, worldZ);
                heights[gz][gx] = y;
                if (y != INVALID_Y) {
                    validSamples++;
                }
            }
        }

        var poseStack = event.getPoseStack();
        poseStack.pushPose();
        // Keep X/Z vertex coordinates close to zero to preserve precision at huge world coordinates.
        poseStack.translate(originX - cameraPos.x, -cameraPos.y, originZ - cameraPos.z);
        Matrix4f matrix = poseStack.last().pose();

        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder buffer = Tesselator.getInstance()
                .begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        int triangles = 0;
        int innerSkipSq = INNER_SKIP_BLOCKS * INNER_SKIP_BLOCKS;

        for (int gz = 0; gz < samplesAcross - 1; gz++) {
            int z0 = centerZ - RADIUS_BLOCKS + gz * SAMPLE_SPACING;
            int z1 = z0 + SAMPLE_SPACING;

            for (int gx = 0; gx < samplesAcross - 1; gx++) {
                int x0 = centerX - RADIUS_BLOCKS + gx * SAMPLE_SPACING;
                int x1 = x0 + SAMPLE_SPACING;

                double cellCenterX = (x0 + x1) * 0.5 - cameraPos.x;
                double cellCenterZ = (z0 + z1) * 0.5 - cameraPos.z;
                if (cellCenterX * cellCenterX + cellCenterZ * cellCenterZ < innerSkipSq) {
                    continue;
                }

                int y00 = heights[gz][gx];
                int y10 = heights[gz][gx + 1];
                int y01 = heights[gz + 1][gx];
                int y11 = heights[gz + 1][gx + 1];

                if (y00 == INVALID_Y || y10 == INVALID_Y || y01 == INVALID_Y || y11 == INVALID_Y) {
                    continue;
                }

                addVertex(buffer, matrix, x0 - originX, y00 + 0.06f, z0 - originZ, level, y00);
                addVertex(buffer, matrix, x0 - originX, y01 + 0.06f, z1 - originZ, level, y01);
                addVertex(buffer, matrix, x1 - originX, y10 + 0.06f, z0 - originZ, level, y10);

                addVertex(buffer, matrix, x1 - originX, y10 + 0.06f, z0 - originZ, level, y10);
                addVertex(buffer, matrix, x0 - originX, y01 + 0.06f, z1 - originZ, level, y01);
                addVertex(buffer, matrix, x1 - originX, y11 + 0.06f, z1 - originZ, level, y11);

                triangles += 2;
            }
        }

        MeshData mesh = buffer.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        poseStack.popPose();

        return new RenderStats(validSamples, triangles);
    }

    private static int sampleSurface(ClientLevel level, int worldX, int worldZ) {
        int chunkX = Math.floorDiv(worldX, 16);
        int chunkZ = Math.floorDiv(worldZ, 16);
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            return INVALID_Y;
        }

        int localX = Math.floorMod(worldX, 16);
        int localZ = Math.floorMod(worldZ, 16);
        return chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ);
    }

    private static void addVertex(
            BufferBuilder buffer,
            Matrix4f matrix,
            float x,
            float y,
            float z,
            ClientLevel level,
            int surfaceY
    ) {
        float minY = level.getMinBuildHeight();
        float range = Math.max(1.0f, level.getMaxBuildHeight() - minY);
        float normalized = Mth.clamp((surfaceY - minY) / range, 0.0f, 1.0f);

        // Temporary diagnostic palette: green lowlands -> gray highlands -> white peaks.
        float r = Mth.lerp(normalized, 0.18f, 0.90f);
        float g = Mth.lerp(normalized, 0.55f, 0.92f);
        float b = Mth.lerp(normalized, 0.22f, 0.95f);

        buffer.addVertex(matrix, x, y, z).setColor(r, g, b, 0.72f);
    }

    public record RenderStats(int validSamples, int triangles) {
        public static final RenderStats EMPTY = new RenderStats(0, 0);
    }
}
