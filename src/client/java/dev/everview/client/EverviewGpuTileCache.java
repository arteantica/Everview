package dev.everview.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.everview.core.LodTileKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Render-thread-owned persistent GPU storage for generated LOD tiles.
 *
 * Tile geometry is uploaded as POSITION_COLOR data in tile-local X/Z
 * coordinates. M3.7.4 additionally clips L1/L2 quads against a chunk-aligned
 * vanilla ownership square. Only boundary tiles are re-uploaded when that
 * ownership square changes; fully distant tiles remain persistent.
 */
public final class EverviewGpuTileCache {
    private static final int MAX_GPU_TILES = 3_072;
    private static final int MAX_UPLOADS_PER_FRAME = 8;

    private static final Map<LodTileKey, GpuTile> TILES =
            new LinkedHashMap<>(256, 0.75F, true);

    private static ClientLevel lastLevel;
    private static int uploadsRemaining;
    private static int uploadsThisFrame;
    private static long uploadNanosThisFrame;
    private static long residentBytes;
    private static VanillaOwnership currentOwnership = VanillaOwnership.NONE;

    private EverviewGpuTileCache() {
    }

    /**
     * Called from COLLECT_SUBMITS, before Minecraft opens the opaque terrain
     * RenderPass. Buffer creation/upload is intentionally kept out of the
     * active terrain pass.
     */
    public static void prepareFrame(
            ClientLevel level,
            WorldgenSurfaceSnapshot snapshot
    ) {
        if (level != lastLevel) {
            clear();
            lastLevel = level;
        }

        uploadsRemaining = MAX_UPLOADS_PER_FRAME;
        uploadsThisFrame = 0;
        uploadNanosThisFrame = 0L;
        currentOwnership = VanillaOwnership.from(Minecraft.getInstance());

        for (WorldgenSurfaceTile tile : snapshot.tiles()) {
            if (uploadsRemaining <= 0) {
                break;
            }

            LodTileKey key = new LodTileKey(
                    tile.lodLevel(),
                    tile.tileX(),
                    tile.tileZ()
            );
            GpuTile existing = TILES.get(key);
            ClipState clip = clipState(tile, currentOwnership);

            // Vanilla owns this entire tile. Keep the CPU/worldgen tile cached,
            // but do not keep a GPU surface that could bleed through vanilla.
            if (clip.fullyOwned()) {
                if (existing != null) {
                    residentBytes -= existing.bytes();
                    existing.close();
                    TILES.remove(key);
                }
                continue;
            }

            if (existing != null
                    && existing.source() == tile
                    && existing.clip().equals(clip)
                    && !existing.vertexBuffer().isClosed()) {
                continue;
            }

            long started = System.nanoTime();
            GpuTile uploaded = upload(tile, clip);
            uploadNanosThisFrame += System.nanoTime() - started;
            uploadsThisFrame++;
            uploadsRemaining--;

            if (existing != null) {
                residentBytes -= existing.bytes();
                existing.close();
                TILES.remove(key);
            }

            if (uploaded != null) {
                TILES.put(key, uploaded);
                residentBytes += uploaded.bytes();
            }
        }

        trim();
    }

    public static GpuTile getResident(WorldgenSurfaceTile tile) {
        LodTileKey key = new LodTileKey(tile.lodLevel(), tile.tileX(), tile.tileZ());
        GpuTile existing = TILES.get(key);

        ClipState expectedClip = clipState(tile, currentOwnership);

        if (expectedClip.fullyOwned()
                || existing == null
                || existing.source() != tile
                || !existing.clip().equals(expectedClip)
                || existing.vertexBuffer().isClosed()) {
            return null;
        }

        return existing;
    }

    public static Stats stats() {
        return new Stats(
                TILES.size(),
                residentBytes,
                uploadsThisFrame,
                uploadNanosThisFrame / 1_000_000.0
        );
    }

    public static void clear() {
        for (GpuTile tile : TILES.values()) {
            tile.close();
        }

        TILES.clear();
        residentBytes = 0L;
        currentOwnership = VanillaOwnership.NONE;
        uploadsRemaining = 0;
        uploadsThisFrame = 0;
        uploadNanosThisFrame = 0L;
    }

    private static void trim() {
        Iterator<Map.Entry<LodTileKey, GpuTile>> iterator = TILES.entrySet().iterator();

        while (TILES.size() > MAX_GPU_TILES && iterator.hasNext()) {
            GpuTile tile = iterator.next().getValue();
            residentBytes -= tile.bytes();
            tile.close();
            iterator.remove();
        }
    }

    private static GpuTile upload(
            WorldgenSurfaceTile tile,
            ClipState clip
    ) {
        VertexFormat format = DefaultVertexFormat.POSITION_COLOR;
        int[] vertices = tile.vertices();
        int[] colors = tile.colors();

        int keptQuads = 0;
        for (int i = 0; i < vertices.length; i += 12) {
            if (!quadOwnedByVanilla(vertices, i, clip)) {
                keptQuads++;
            }
        }

        if (keptQuads == 0) {
            return null;
        }

        int vertexCount = keptQuads * 4;
        int bytes = Math.multiplyExact(format.getVertexSize(), vertexCount);

        try (ByteBufferBuilder byteBuffer = ByteBufferBuilder.exactlySized(bytes)) {
            BufferBuilder builder = new BufferBuilder(
                    byteBuffer,
                    PrimitiveTopology.QUADS,
                    format
            );

            int originX = tile.minX();
            int originZ = tile.minZ();

            for (int quad = 0; quad < vertices.length; quad += 12) {
                if (quadOwnedByVanilla(vertices, quad, clip)) {
                    continue;
                }

                int firstVertex = quad / 3;

                for (int v = 0; v < 4; v++) {
                    int i = quad + v * 3;
                    int rgb = colors[firstVertex + v];

                    builder.addVertex(
                                    vertices[i] - originX,
                                    vertices[i + 1],
                                    vertices[i + 2] - originZ
                            )
                            .setColor(
                                    (rgb >> 16) & 0xFF,
                                    (rgb >> 8) & 0xFF,
                                    rgb & 0xFF,
                                    255
                            );
                }
            }

            try (MeshData mesh = builder.buildOrThrow()) {
                GpuBuffer vertexBuffer = RenderSystem.getDevice().createBuffer(
                        () -> "Everview LOD tile L" + tile.lodLevel()
                                + " " + tile.tileX() + "," + tile.tileZ(),
                        GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_VERTEX,
                        mesh.vertexBuffer()
                );

                return new GpuTile(
                        tile,
                        vertexBuffer,
                        mesh.drawState().indexCount(),
                        bytes,
                        clip
                );
            }
        }
    }

    private static boolean quadOwnedByVanilla(
            int[] vertices,
            int quadOffset,
            ClipState clip
    ) {
        if (!clip.clipped()) {
            return false;
        }

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (int v = 0; v < 4; v++) {
            int i = quadOffset + v * 3;
            minX = Math.min(minX, vertices[i]);
            maxX = Math.max(maxX, vertices[i]);
            minZ = Math.min(minZ, vertices[i + 2]);
            maxZ = Math.max(maxZ, vertices[i + 2]);
        }

        boolean overlapsX = minX == maxX
                ? minX >= clip.minX() && minX < clip.maxX()
                : maxX > clip.minX() && minX < clip.maxX();
        boolean overlapsZ = minZ == maxZ
                ? minZ >= clip.minZ() && minZ < clip.maxZ()
                : maxZ > clip.minZ() && minZ < clip.maxZ();

        return overlapsX && overlapsZ;
    }

    private static ClipState clipState(
            WorldgenSurfaceTile tile,
            VanillaOwnership ownership
    ) {
        if (tile.lodLevel() > 2 || ownership == VanillaOwnership.NONE) {
            return ClipState.NONE;
        }

        int intersectionMinX = Math.max(tile.minX(), ownership.minX());
        int intersectionMaxX = Math.min(tile.maxX(), ownership.maxX());
        int intersectionMinZ = Math.max(tile.minZ(), ownership.minZ());
        int intersectionMaxZ = Math.min(tile.maxZ(), ownership.maxZ());

        if (intersectionMinX >= intersectionMaxX
                || intersectionMinZ >= intersectionMaxZ) {
            return ClipState.NONE;
        }

        if (intersectionMinX <= tile.minX()
                && intersectionMaxX >= tile.maxX()
                && intersectionMinZ <= tile.minZ()
                && intersectionMaxZ >= tile.maxZ()) {
            return ClipState.FULL;
        }

        return new ClipState(
                false,
                true,
                intersectionMinX,
                intersectionMaxX,
                intersectionMinZ,
                intersectionMaxZ
        );
    }

    public record Stats(
            int bufferCount,
            long residentBytes,
            int uploadsThisFrame,
            double uploadMs
    ) {
        public double residentMiB() {
            return residentBytes / (1024.0 * 1024.0);
        }
    }

    private record VanillaOwnership(
            int minX,
            int maxX,
            int minZ,
            int maxZ
    ) {
        private static final VanillaOwnership NONE =
                new VanillaOwnership(0, 0, 0, 0);

        private static VanillaOwnership from(Minecraft client) {
            if (client.player == null) {
                return NONE;
            }

            int chunkX = Math.floorDiv(client.player.getBlockX(), 16);
            int chunkZ = Math.floorDiv(client.player.getBlockZ(), 16);
            int radius = Math.max(1, client.options.getEffectiveRenderDistance());

            return new VanillaOwnership(
                    (chunkX - radius) * 16,
                    (chunkX + radius + 1) * 16,
                    (chunkZ - radius) * 16,
                    (chunkZ + radius + 1) * 16
            );
        }
    }

    private record ClipState(
            boolean fullyOwned,
            boolean clipped,
            int minX,
            int maxX,
            int minZ,
            int maxZ
    ) {
        private static final ClipState NONE =
                new ClipState(false, false, 0, 0, 0, 0);
        private static final ClipState FULL =
                new ClipState(true, true, 0, 0, 0, 0);
    }

    public record GpuTile(
            WorldgenSurfaceTile source,
            GpuBuffer vertexBuffer,
            int indexCount,
            long bytes,
            ClipState clip
    ) implements AutoCloseable {
        @Override
        public void close() {
            if (!vertexBuffer.isClosed()) {
                vertexBuffer.close();
            }
        }
    }
}
