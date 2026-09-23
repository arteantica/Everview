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
 * M3.7.4.4 splits tall L1/L2 vertical faces at 16-block section boundaries,
 * then groups the resulting pieces into chunk/section-sized draw batches.
 * Vanilla ownership stays live at draw time, so no visibility change ever
 * destructively edits or rebuilds an LOD tile.
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
        int[] vertices = tile.vertices();
        int[] colors = tile.colors();
        int originX = tile.minX();
        int originZ = tile.minZ();

        Map<BatchKey, List<QuadPiece>> groupedQuads = new LinkedHashMap<>();
        int emittedQuadCount = 0;

        for (int quadOffset = 0; quadOffset < vertices.length; quadOffset += 12) {
            List<QuadPiece> pieces = tile.lodLevel() <= 2
                    ? splitNearQuadBySection(vertices, colors, quadOffset)
                    : List.of(copyQuad(vertices, colors, quadOffset));

            emittedQuadCount += pieces.size();

            for (QuadPiece piece : pieces) {
                BatchKey key = tile.lodLevel() <= 2
                        ? classifyBatch(piece.vertices(), 0)
                        : BatchKey.ALWAYS;

                groupedQuads.computeIfAbsent(
                        key,
                        ignored -> new ArrayList<>()
                ).add(piece);
            }
        }

        int vertexCount = emittedQuadCount * 4;
        int bytes = Math.multiplyExact(format.getVertexSize(), vertexCount);
        List<DrawBatch> drawBatches = new ArrayList<>(groupedQuads.size());

        try (ByteBufferBuilder byteBuffer = ByteBufferBuilder.exactlySized(bytes)) {
            BufferBuilder builder = new BufferBuilder(
                    byteBuffer,
                    PrimitiveTopology.QUADS,
                    format
            );

            int firstIndex = 0;

            for (Map.Entry<BatchKey, List<QuadPiece>> entry : groupedQuads.entrySet()) {
                BatchKey key = entry.getKey();
                List<QuadPiece> pieces = entry.getValue();

                for (QuadPiece piece : pieces) {
                    int[] quadVertices = piece.vertices();
                    int[] quadColors = piece.colors();

                    for (int v = 0; v < 4; v++) {
                        int i = v * 3;
                        int rgb = quadColors[v];

                        builder.addVertex(
                                        quadVertices[i] - originX,
                                        quadVertices[i + 1],
                                        quadVertices[i + 2] - originZ
                                )
                                .setColor(
                                        (rgb >> 16) & 0xFF,
                                        (rgb >> 8) & 0xFF,
                                        rgb & 0xFF,
                                        255
                                );
                    }
                }

                int indexCount = pieces.size() * 6;
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

    private static List<QuadPiece> splitNearQuadBySection(
            int[] vertices,
            int[] colors,
            int quadOffset
    ) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (int v = 0; v < 4; v++) {
            int y = vertices[quadOffset + v * 3 + 1];
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }

        // Horizontal faces already belong to one vertical section.
        if (minY == maxY) {
            return List.of(copyQuad(vertices, colors, quadOffset));
        }

        // Only axis-aligned vertical faces need splitting. Defensive fallback
        // keeps any unexpected quad intact instead of changing its geometry.
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

        if (minX != maxX && minZ != maxZ) {
            return List.of(copyQuad(vertices, colors, quadOffset));
        }

        List<QuadPiece> pieces = new ArrayList<>();
        int sliceBottom = minY;

        while (sliceBottom < maxY) {
            int sectionTop = (Math.floorDiv(sliceBottom, 16) + 1) * 16;
            int sliceTop = Math.min(maxY, sectionTop);

            int[] pieceVertices = new int[12];
            int[] pieceColors = new int[4];

            for (int v = 0; v < 4; v++) {
                int source = quadOffset + v * 3;
                int target = v * 3;
                int sourceY = vertices[source + 1];

                pieceVertices[target] = vertices[source];
                pieceVertices[target + 1] =
                        sourceY == minY ? sliceBottom : sliceTop;
                pieceVertices[target + 2] = vertices[source + 2];
                pieceColors[v] = colors[quadOffset / 3 + v];
            }

            pieces.add(new QuadPiece(pieceVertices, pieceColors));
            sliceBottom = sliceTop;
        }

        return pieces;
    }

    private static QuadPiece copyQuad(
            int[] vertices,
            int[] colors,
            int quadOffset
    ) {
        int[] pieceVertices = new int[12];
        int[] pieceColors = new int[4];

        System.arraycopy(vertices, quadOffset, pieceVertices, 0, 12);
        System.arraycopy(colors, quadOffset / 3, pieceColors, 0, 4);

        return new QuadPiece(pieceVertices, pieceColors);
    }

    private record QuadPiece(
            int[] vertices,
            int[] colors
    ) {
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
