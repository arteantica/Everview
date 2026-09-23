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
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Render-thread-owned persistent GPU storage for generated LOD tiles.
 *
 * Tile geometry is uploaded as POSITION_COLOR data in tile-local X/Z
 * coordinates. M3.7.4.1 clips near LOD faces only where the client confirms
 * that real vanilla chunks are currently loaded. Loaded-chunk state is hashed
 * per tile so only affected boundary buffers are rebuilt as vanilla streams.
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
            long vanillaClipSignature = vanillaClipSignature(level, tile);

            if (existing != null
                    && existing.source() == tile
                    && existing.vanillaClipSignature() == vanillaClipSignature
                    && !existing.vertexBuffer().isClosed()) {
                continue;
            }

            long started = System.nanoTime();
            GpuTile uploaded = upload(tile, level, vanillaClipSignature);
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

        if (existing == null
                || existing.source() != tile
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
            ClientLevel level,
            long vanillaClipSignature
    ) {
        VertexFormat format = DefaultVertexFormat.POSITION_COLOR;
        int[] vertices = tile.vertices();
        int[] colors = tile.colors();

        int keptQuads = 0;
        for (int i = 0; i < vertices.length; i += 12) {
            if (!quadOwnedByLoadedVanilla(level, tile, vertices, i)) {
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
                if (quadOwnedByLoadedVanilla(level, tile, vertices, quad)) {
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
                        vanillaClipSignature
                );
            }
        }
    }

    private static long vanillaClipSignature(
            ClientLevel level,
            WorldgenSurfaceTile tile
    ) {
        // M3.7.4.1 deliberately limits exact vanilla ownership clipping to
        // L1/L2. Their 1/2/4/8-block cells fit cleanly inside 16x16 vanilla
        // chunks. Coarser rings need geometric quad splitting before they can
        // be clipped without risking another hole regression.
        if (tile.lodLevel() > 2) {
            return 0L;
        }

        int minChunkX = Math.floorDiv(tile.minX() - 1, 16);
        int maxChunkX = Math.floorDiv(tile.maxX(), 16);
        int minChunkZ = Math.floorDiv(tile.minZ() - 1, 16);
        int maxChunkZ = Math.floorDiv(tile.maxZ(), 16);

        long hash = 0xcbf29ce484222325L;
        boolean anyLoaded = false;

        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                boolean loaded = vanillaChunkLoaded(level, chunkX, chunkZ);
                anyLoaded |= loaded;

                long value = ((long) chunkX << 32)
                        ^ (chunkZ & 0xFFFF_FFFFL)
                        ^ (loaded ? 0x9E3779B97F4A7C15L : 0L);
                hash ^= value;
                hash *= 0x100000001b3L;
            }
        }

        return anyLoaded ? hash : 0L;
    }

    private static boolean quadOwnedByLoadedVanilla(
            ClientLevel level,
            WorldgenSurfaceTile tile,
            int[] vertices,
            int quadOffset
    ) {
        if (tile.lodLevel() > 2) {
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

        // Horizontal/top quad: vanilla owns it only when every chunk touched
        // by its half-open X/Z footprint is actually loaded.
        if (minX < maxX && minZ < maxZ) {
            return allChunksLoaded(
                    level,
                    Math.floorDiv(minX, 16),
                    Math.floorDiv(maxX - 1, 16),
                    Math.floorDiv(minZ, 16),
                    Math.floorDiv(maxZ - 1, 16)
            );
        }

        // A vertical wall on a chunk boundary is removed only when vanilla
        // exists on BOTH sides. If either side is missing, keep the LOD wall as
        // a safety seam instead of opening a hole.
        if (minX == maxX && minZ < maxZ) {
            int leftChunkX = Math.floorDiv(minX - 1, 16);
            int rightChunkX = Math.floorDiv(minX, 16);
            int minChunkZ = Math.floorDiv(minZ, 16);
            int maxChunkZ = Math.floorDiv(maxZ - 1, 16);

            return allChunksLoaded(
                    level,
                    leftChunkX,
                    leftChunkX,
                    minChunkZ,
                    maxChunkZ
            ) && allChunksLoaded(
                    level,
                    rightChunkX,
                    rightChunkX,
                    minChunkZ,
                    maxChunkZ
            );
        }

        if (minZ == maxZ && minX < maxX) {
            int northChunkZ = Math.floorDiv(minZ - 1, 16);
            int southChunkZ = Math.floorDiv(minZ, 16);
            int minChunkX = Math.floorDiv(minX, 16);
            int maxChunkX = Math.floorDiv(maxX - 1, 16);

            return allChunksLoaded(
                    level,
                    minChunkX,
                    maxChunkX,
                    northChunkZ,
                    northChunkZ
            ) && allChunksLoaded(
                    level,
                    minChunkX,
                    maxChunkX,
                    southChunkZ,
                    southChunkZ
            );
        }

        return false;
    }

    private static boolean allChunksLoaded(
            ClientLevel level,
            int minChunkX,
            int maxChunkX,
            int minChunkZ,
            int maxChunkZ
    ) {
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                if (!vanillaChunkLoaded(level, chunkX, chunkZ)) {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean vanillaChunkLoaded(
            ClientLevel level,
            int chunkX,
            int chunkZ
    ) {
        // LevelReader.hasChunkAt(int, int) takes block X/Z coordinates.
        return level.hasChunkAt(chunkX * 16 + 8, chunkZ * 16 + 8);
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

    public record GpuTile(
            WorldgenSurfaceTile source,
            GpuBuffer vertexBuffer,
            int indexCount,
            long bytes,
            long vanillaClipSignature
    ) implements AutoCloseable {
        @Override
        public void close() {
            if (!vertexBuffer.isClosed()) {
                vertexBuffer.close();
            }
        }
    }
}
