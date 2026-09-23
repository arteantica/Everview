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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Render-thread-owned persistent GPU storage for generated LOD tiles.
 *
 * Tile geometry is uploaded once as POSITION_COLOR data in tile-local X/Z.
 * M3.7.4.3 groups L1/L2 quads into chunk/section-sized draw batches at upload
 * time. Vanilla ownership is decided live at draw time, so camera motion or
 * vanilla chunk streaming never destructively edits/rebuilds an LOD buffer.
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

            if (existing != null
                    && existing.source() == tile
                    && !existing.vertexBuffer().isClosed()) {
                continue;
            }

            long started = System.nanoTime();
            GpuTile uploaded = upload(tile);
            uploadNanosThisFrame += System.nanoTime() - started;
            uploadsThisFrame++;
            uploadsRemaining--;

            if (existing != null) {
                residentBytes -= existing.bytes();
                existing.close();
            }

            TILES.put(key, uploaded);
            residentBytes += uploaded.bytes();
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

    private static GpuTile upload(WorldgenSurfaceTile tile) {
        VertexFormat format = DefaultVertexFormat.POSITION_COLOR;
        int vertexCount = tile.vertexCount();
        int bytes = Math.multiplyExact(format.getVertexSize(), vertexCount);

        int[] vertices = tile.vertices();
        int[] colors = tile.colors();
        int originX = tile.minX();
        int originZ = tile.minZ();

        Map<BatchKey, List<Integer>> groupedQuads = new LinkedHashMap<>();

        for (int quadOffset = 0; quadOffset < vertices.length; quadOffset += 12) {
            BatchKey key = tile.lodLevel() <= 2
                    ? classifyBatch(vertices, quadOffset)
                    : BatchKey.ALWAYS;
            groupedQuads.computeIfAbsent(
                    key,
                    ignored -> new ArrayList<>()
            ).add(quadOffset);
        }

        List<DrawBatch> drawBatches = new ArrayList<>(groupedQuads.size());

        try (ByteBufferBuilder byteBuffer = ByteBufferBuilder.exactlySized(bytes)) {
            BufferBuilder builder = new BufferBuilder(
                    byteBuffer,
                    PrimitiveTopology.QUADS,
                    format
            );

            int firstIndex = 0;

            for (Map.Entry<BatchKey, List<Integer>> entry : groupedQuads.entrySet()) {
                BatchKey key = entry.getKey();
                List<Integer> quadOffsets = entry.getValue();

                for (int quadOffset : quadOffsets) {
                    int firstVertex = quadOffset / 3;

                    for (int v = 0; v < 4; v++) {
                        int i = quadOffset + v * 3;
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

                int indexCount = quadOffsets.size() * 6;
                drawBatches.add(new DrawBatch(
                        firstIndex,
                        indexCount,
                        key.vanillaSensitive(),
                        key.chunkAX(),
                        key.chunkAZ(),
                        key.sectionY(),
                        key.boundary(),
                        key.chunkBX(),
                        key.chunkBZ()
                ));
                firstIndex += indexCount;
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
                        List.copyOf(drawBatches)
                );
            }
        }
    }

    private static BatchKey classifyBatch(int[] vertices, int quadOffset) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (int v = 0; v < 4; v++) {
            int i = quadOffset + v * 3;
            minX = Math.min(minX, vertices[i]);
            maxX = Math.max(maxX, vertices[i]);
            minY = Math.min(minY, vertices[i + 1]);
            maxY = Math.max(maxY, vertices[i + 1]);
            minZ = Math.min(minZ, vertices[i + 2]);
            maxZ = Math.max(maxZ, vertices[i + 2]);
        }

        // Horizontal surface face. Near-ring cell sizes all divide 16, so a
        // surface cell belongs cleanly to one vanilla chunk column.
        if (minY == maxY && minX < maxX && minZ < maxZ) {
            int sampleX = minX + Math.max(0, (maxX - minX - 1) / 2);
            int sampleZ = minZ + Math.max(0, (maxZ - minZ - 1) / 2);
            int chunkX = Math.floorDiv(sampleX, 16);
            int chunkZ = Math.floorDiv(sampleZ, 16);
            int sectionY = Math.floorDiv(minY - 1, 16);

            return BatchKey.single(chunkX, chunkZ, sectionY);
        }

        // Vertical X wall. If it lies exactly on a vanilla chunk boundary,
        // remember both adjacent chunk columns. At draw time the wall is hidden
        // when EITHER vanilla side is actually renderer-visible, which prevents
        // the thin LOD walls seen inside vanilla chunks.
        if (minX == maxX && minZ < maxZ) {
            int sampleZ = minZ + Math.max(0, (maxZ - minZ - 1) / 2);
            int chunkZ = Math.floorDiv(sampleZ, 16);
            int sectionY = Math.floorDiv(maxY - 1, 16);

            if (Math.floorMod(minX, 16) == 0) {
                int rightChunkX = Math.floorDiv(minX, 16);
                return BatchKey.boundary(
                        rightChunkX - 1,
                        chunkZ,
                        rightChunkX,
                        chunkZ,
                        sectionY
                );
            }

            return BatchKey.single(
                    Math.floorDiv(minX, 16),
                    chunkZ,
                    sectionY
            );
        }

        // Vertical Z wall, same rule as X.
        if (minZ == maxZ && minX < maxX) {
            int sampleX = minX + Math.max(0, (maxX - minX - 1) / 2);
            int chunkX = Math.floorDiv(sampleX, 16);
            int sectionY = Math.floorDiv(maxY - 1, 16);

            if (Math.floorMod(minZ, 16) == 0) {
                int southChunkZ = Math.floorDiv(minZ, 16);
                return BatchKey.boundary(
                        chunkX,
                        southChunkZ - 1,
                        chunkX,
                        southChunkZ,
                        sectionY
                );
            }

            return BatchKey.single(
                    chunkX,
                    Math.floorDiv(minZ, 16),
                    sectionY
            );
        }

        // Defensive fallback for any unexpected near-ring quad shape.
        return BatchKey.ALWAYS;
    }

    private record BatchKey(
            boolean vanillaSensitive,
            int chunkAX,
            int chunkAZ,
            int sectionY,
            boolean boundary,
            int chunkBX,
            int chunkBZ
    ) {
        private static final BatchKey ALWAYS =
                new BatchKey(false, 0, 0, 0, false, 0, 0);

        private static BatchKey single(
                int chunkX,
                int chunkZ,
                int sectionY
        ) {
            return new BatchKey(
                    true,
                    chunkX,
                    chunkZ,
                    sectionY,
                    false,
                    0,
                    0
            );
        }

        private static BatchKey boundary(
                int chunkAX,
                int chunkAZ,
                int chunkBX,
                int chunkBZ,
                int sectionY
        ) {
            return new BatchKey(
                    true,
                    chunkAX,
                    chunkAZ,
                    sectionY,
                    true,
                    chunkBX,
                    chunkBZ
            );
        }
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

    public record DrawBatch(
            int firstIndex,
            int indexCount,
            boolean vanillaSensitive,
            int chunkAX,
            int chunkAZ,
            int sectionY,
            boolean boundary,
            int chunkBX,
            int chunkBZ
    ) {
    }

    public record GpuTile(
            WorldgenSurfaceTile source,
            GpuBuffer vertexBuffer,
            int indexCount,
            long bytes,
            List<DrawBatch> drawBatches
    ) implements AutoCloseable {
        @Override
        public void close() {
            if (!vertexBuffer.isClosed()) {
                vertexBuffer.close();
            }
        }
    }
}
