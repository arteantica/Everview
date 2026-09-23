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
 * M5 partitions every near-ring quad through the 16x16 vanilla chunk grid.
 * L3 therefore carries both vanilla chunk-column ownership and its 128x128 L2
 * fallback-region ownership, so no emergency surface can leak through vanilla.
 */
public final class EverviewGpuTileCache {
    private static final int MAX_GPU_TILES = 3_072;
    private static final int MAX_UPLOADS_PER_FRAME = 8;
    private static final int L3_UNDERLAY_REGION_SIZE = 128;

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
            List<QuadPiece> pieces = tile.lodLevel() <= 3
                    ? splitQuadForOwnership(vertices, colors, quadOffset)
                    : List.of(copyQuad(vertices, colors, quadOffset));

            emittedQuadCount += pieces.size();

            for (QuadPiece piece : pieces) {
                BatchKey key;

                if (tile.lodLevel() <= 2) {
                    key = classifyBatch(piece.vertices(), 0);
                } else if (tile.lodLevel() == 3) {
                    key = classifyUnderlayBatch(piece.vertices(), 0);
                } else {
                    key = BatchKey.ALWAYS;
                }

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
                        key.surface(),
                        key.chunkAX(),
                        key.chunkAZ(),
                        key.sectionY(),
                        key.boundary(),
                        key.chunkBX(),
                        key.chunkBZ(),
                        key.underlayRegion(),
                        key.regionTileX(),
                        key.regionTileZ()
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

    private static List<QuadPiece> splitQuadForOwnership(
            int[] vertices,
            int[] colors,
            int quadOffset
    ) {
        List<QuadPiece> sectionPieces =
                splitNearQuadBySection(vertices, colors, quadOffset);
        List<QuadPiece> ownedPieces = new ArrayList<>();

        for (QuadPiece piece : sectionPieces) {
            ownedPieces.addAll(splitPieceByChunkColumns(piece));
        }

        return ownedPieces;
    }

    private static List<QuadPiece> splitPieceByChunkColumns(
            QuadPiece piece
    ) {
        int[] vertices = piece.vertices();
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (int v = 0; v < 4; v++) {
            int i = v * 3;
            minX = Math.min(minX, vertices[i]);
            maxX = Math.max(maxX, vertices[i]);
            minY = Math.min(minY, vertices[i + 1]);
            maxY = Math.max(maxY, vertices[i + 1]);
            minZ = Math.min(minZ, vertices[i + 2]);
            maxZ = Math.max(maxZ, vertices[i + 2]);
        }

        // A terrain top is identified by spanning both horizontal axes.
        // Its four corner heights are allowed to differ. M4 treated those
        // sloped tops as "unexpected" and they escaped vanilla ownership.
        boolean surface = minX < maxX && minZ < maxZ;
        boolean xWall = minX == maxX
                && minY < maxY
                && minZ < maxZ;
        boolean zWall = minZ == maxZ
                && minY < maxY
                && minX < maxX;

        if (!surface && !xWall && !zWall) {
            return List.of(piece);
        }

        List<QuadPiece> result = new ArrayList<>();

        if (surface) {
            int x0 = minX;
            while (x0 < maxX) {
                int x1 = Math.min(maxX, nextChunkBoundary(x0));
                int z0 = minZ;

                while (z0 < maxZ) {
                    int z1 = Math.min(maxZ, nextChunkBoundary(z0));
                    result.add(splitSurfacePiece(
                            piece,
                            minX,
                            maxX,
                            minZ,
                            maxZ,
                            x0,
                            x1,
                            z0,
                            z1
                    ));
                    z0 = z1;
                }
                x0 = x1;
            }

            return result;
        }

        if (xWall) {
            int z0 = minZ;
            while (z0 < maxZ) {
                int z1 = Math.min(maxZ, nextChunkBoundary(z0));
                result.add(remapAxisAlignedPiece(
                        piece,
                        minX, maxX,
                        minY, maxY,
                        minZ, maxZ,
                        minX, maxX,
                        minY, maxY,
                        z0, z1
                ));
                z0 = z1;
            }

            return result;
        }

        int x0 = minX;
        while (x0 < maxX) {
            int x1 = Math.min(maxX, nextChunkBoundary(x0));
            result.add(remapAxisAlignedPiece(
                    piece,
                    minX, maxX,
                    minY, maxY,
                    minZ, maxZ,
                    x0, x1,
                    minY, maxY,
                    minZ, maxZ
            ));
            x0 = x1;
        }

        return result;
    }

    private static QuadPiece splitSurfacePiece(
            QuadPiece source,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            int x0,
            int x1,
            int z0,
            int z1
    ) {
        int[] vertices = source.vertices();
        int[] colors = source.colors();

        int y00 = surfaceCornerY(vertices, minX, minZ);
        int y01 = surfaceCornerY(vertices, minX, maxZ);
        int y11 = surfaceCornerY(vertices, maxX, maxZ);
        int y10 = surfaceCornerY(vertices, maxX, minZ);

        int c00 = surfaceCornerColor(vertices, colors, minX, minZ);
        int c01 = surfaceCornerColor(vertices, colors, minX, maxZ);
        int c11 = surfaceCornerColor(vertices, colors, maxX, maxZ);
        int c10 = surfaceCornerColor(vertices, colors, maxX, minZ);

        double tx0 = (x0 - minX) / (double) Math.max(1, maxX - minX);
        double tx1 = (x1 - minX) / (double) Math.max(1, maxX - minX);
        double tz0 = (z0 - minZ) / (double) Math.max(1, maxZ - minZ);
        double tz1 = (z1 - minZ) / (double) Math.max(1, maxZ - minZ);

        int[] outVertices = new int[] {
                x0, bilerpInt(y00, y10, y01, y11, tx0, tz0), z0,
                x0, bilerpInt(y00, y10, y01, y11, tx0, tz1), z1,
                x1, bilerpInt(y00, y10, y01, y11, tx1, tz1), z1,
                x1, bilerpInt(y00, y10, y01, y11, tx1, tz0), z0
        };

        int[] outColors = new int[] {
                bilerpColor(c00, c10, c01, c11, tx0, tz0),
                bilerpColor(c00, c10, c01, c11, tx0, tz1),
                bilerpColor(c00, c10, c01, c11, tx1, tz1),
                bilerpColor(c00, c10, c01, c11, tx1, tz0)
        };

        return new QuadPiece(outVertices, outColors);
    }

    private static int surfaceCornerY(
            int[] vertices,
            int x,
            int z
    ) {
        for (int v = 0; v < 4; v++) {
            int i = v * 3;
            if (vertices[i] == x && vertices[i + 2] == z) {
                return vertices[i + 1];
            }
        }

        throw new IllegalStateException(
                "Everview surface quad missing expected corner"
        );
    }

    private static int surfaceCornerColor(
            int[] vertices,
            int[] colors,
            int x,
            int z
    ) {
        for (int v = 0; v < 4; v++) {
            int i = v * 3;
            if (vertices[i] == x && vertices[i + 2] == z) {
                return colors[v];
            }
        }

        throw new IllegalStateException(
                "Everview surface quad missing expected color corner"
        );
    }

    private static int bilerpInt(
            int c00,
            int c10,
            int c01,
            int c11,
            double tx,
            double tz
    ) {
        double north = c00 + (c10 - c00) * tx;
        double south = c01 + (c11 - c01) * tx;
        return (int) Math.round(north + (south - north) * tz);
    }

    private static int bilerpColor(
            int c00,
            int c10,
            int c01,
            int c11,
            double tx,
            double tz
    ) {
        int r = bilerpInt(
                (c00 >> 16) & 0xFF,
                (c10 >> 16) & 0xFF,
                (c01 >> 16) & 0xFF,
                (c11 >> 16) & 0xFF,
                tx,
                tz
        );
        int g = bilerpInt(
                (c00 >> 8) & 0xFF,
                (c10 >> 8) & 0xFF,
                (c01 >> 8) & 0xFF,
                (c11 >> 8) & 0xFF,
                tx,
                tz
        );
        int b = bilerpInt(
                c00 & 0xFF,
                c10 & 0xFF,
                c01 & 0xFF,
                c11 & 0xFF,
                tx,
                tz
        );

        return (r << 16) | (g << 8) | b;
    }

    private static int nextChunkBoundary(int coordinate) {
        return (Math.floorDiv(coordinate, 16) + 1) * 16;
    }

    private static QuadPiece remapAxisAlignedPiece(
            QuadPiece source,
            int oldMinX,
            int oldMaxX,
            int oldMinY,
            int oldMaxY,
            int oldMinZ,
            int oldMaxZ,
            int newMinX,
            int newMaxX,
            int newMinY,
            int newMaxY,
            int newMinZ,
            int newMaxZ
    ) {
        int[] oldVertices = source.vertices();
        int[] newVertices = new int[12];
        int[] newColors = source.colors().clone();

        for (int v = 0; v < 4; v++) {
            int i = v * 3;
            int x = oldVertices[i];
            int y = oldVertices[i + 1];
            int z = oldVertices[i + 2];

            newVertices[i] = oldMinX == oldMaxX
                    ? oldMinX
                    : (x == oldMinX ? newMinX : newMaxX);
            newVertices[i + 1] = oldMinY == oldMaxY
                    ? oldMinY
                    : (y == oldMinY ? newMinY : newMaxY);
            newVertices[i + 2] = oldMinZ == oldMaxZ
                    ? oldMinZ
                    : (z == oldMinZ ? newMinZ : newMaxZ);
        }

        return new QuadPiece(newVertices, newColors);
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

        // Terrain top face. Heights may differ at all four corners for smooth
        // distant terrain. Ownership depends on the X/Z footprint, not flat Y.
        // splitQuadForOwnership() already constrains the footprint to one
        // vanilla chunk column before this classifier runs.
        if (minX < maxX && minZ < maxZ) {
            int sampleX = minX + Math.max(0, (maxX - minX - 1) / 2);
            int sampleZ = minZ + Math.max(0, (maxZ - minZ - 1) / 2);
            int chunkX = Math.floorDiv(sampleX, 16);
            int chunkZ = Math.floorDiv(sampleZ, 16);
            int sectionY = Math.floorDiv(
                    (minY + maxY) / 2 - 1,
                    16
            );

            return BatchKey.surface(chunkX, chunkZ, sectionY);
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

            return BatchKey.wall(
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

            return BatchKey.wall(
                    chunkX,
                    Math.floorDiv(minZ, 16),
                    sectionY
            );
        }

        // Defensive fallback for any unexpected near-ring quad shape.
        return BatchKey.ALWAYS;
    }

    private static BatchKey classifyUnderlayBatch(
            int[] vertices,
            int quadOffset
    ) {
        BatchKey vanillaKey = classifyBatch(vertices, quadOffset);

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

        int sampleX = minX == maxX
                ? minX - (Math.floorMod(minX, L3_UNDERLAY_REGION_SIZE) == 0
                        ? 1 : 0)
                : minX + Math.max(0, (maxX - minX - 1) / 2);
        int sampleZ = minZ == maxZ
                ? minZ - (Math.floorMod(minZ, L3_UNDERLAY_REGION_SIZE) == 0
                        ? 1 : 0)
                : minZ + Math.max(0, (maxZ - minZ - 1) / 2);

        return vanillaKey.withUnderlayRegion(
                Math.floorDiv(sampleX, L3_UNDERLAY_REGION_SIZE),
                Math.floorDiv(sampleZ, L3_UNDERLAY_REGION_SIZE)
        );
    }

    private record BatchKey(
            boolean vanillaSensitive,
            boolean surface,
            int chunkAX,
            int chunkAZ,
            int sectionY,
            boolean boundary,
            int chunkBX,
            int chunkBZ,
            boolean underlayRegion,
            int regionTileX,
            int regionTileZ
    ) {
        private static final BatchKey ALWAYS =
                new BatchKey(
                        false, false, 0, 0, 0, false, 0, 0,
                        false, 0, 0
                );

        private static BatchKey surface(
                int chunkX,
                int chunkZ,
                int sectionY
        ) {
            return new BatchKey(
                    true,
                    true,
                    chunkX,
                    chunkZ,
                    sectionY,
                    false,
                    0,
                    0,
                    false,
                    0,
                    0
            );
        }

        private static BatchKey wall(
                int chunkX,
                int chunkZ,
                int sectionY
        ) {
            return new BatchKey(
                    true,
                    false,
                    chunkX,
                    chunkZ,
                    sectionY,
                    false,
                    0,
                    0,
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
                    false,
                    chunkAX,
                    chunkAZ,
                    sectionY,
                    true,
                    chunkBX,
                    chunkBZ,
                    false,
                    0,
                    0
            );
        }

        private static BatchKey underlayRegion(
                int regionTileX,
                int regionTileZ
        ) {
            return new BatchKey(
                    false,
                    false,
                    0,
                    0,
                    0,
                    false,
                    0,
                    0,
                    true,
                    regionTileX,
                    regionTileZ
            );
        }

        private BatchKey withUnderlayRegion(
                int regionTileX,
                int regionTileZ
        ) {
            return new BatchKey(
                    vanillaSensitive,
                    surface,
                    chunkAX,
                    chunkAZ,
                    sectionY,
                    boundary,
                    chunkBX,
                    chunkBZ,
                    true,
                    regionTileX,
                    regionTileZ
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
            boolean surface,
            int chunkAX,
            int chunkAZ,
            int sectionY,
            boolean boundary,
            int chunkBX,
            int chunkBZ,
            boolean underlayRegion,
            int regionTileX,
            int regionTileZ
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
