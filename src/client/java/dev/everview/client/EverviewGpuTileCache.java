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
import net.minecraft.core.BlockPos;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Render-thread-owned persistent GPU storage for generated LOD tiles.
 *
 * Tile geometry is uploaded as POSITION_COLOR data in tile-local X/Z
 * coordinates. M3.7.4.2 clips near LOD faces only where LevelRenderer says
 * the corresponding vanilla chunk SECTION is both compiled and visible.
 * That tracks the terrain renderer itself instead of chunk residency.
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
            Minecraft client = Minecraft.getInstance();
            long vanillaVisibilitySignature =
                    vanillaVisibilitySignature(client, tile);

            if (existing != null
                    && existing.source() == tile
                    && existing.vanillaVisibilitySignature()
                            == vanillaVisibilitySignature
                    && !existing.vertexBuffer().isClosed()) {
                continue;
            }

            long started = System.nanoTime();
            GpuTile uploaded = upload(
                    tile,
                    client,
                    vanillaVisibilitySignature
            );
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
            Minecraft client,
            long vanillaVisibilitySignature
    ) {
        VertexFormat format = DefaultVertexFormat.POSITION_COLOR;
        int[] vertices = tile.vertices();
        int[] colors = tile.colors();
        boolean clipAgainstVanilla =
                vanillaVisibilitySignature != 0L && tile.lodLevel() <= 2;
        BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

        int keptQuads = 0;
        for (int i = 0; i < vertices.length; i += 12) {
            if (!clipAgainstVanilla
                    || !quadOwnedByVisibleVanilla(
                            client,
                            vertices,
                            i,
                            scratch
                    )) {
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
                if (clipAgainstVanilla
                        && quadOwnedByVisibleVanilla(
                                client,
                                vertices,
                                quad,
                                scratch
                        )) {
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
                        vanillaVisibilitySignature
                );
            }
        }
    }

    private static long vanillaVisibilitySignature(
            Minecraft client,
            WorldgenSurfaceTile tile
    ) {
        if (client.player == null
                || client.level == null
                || tile.lodLevel() > 2) {
            return 0L;
        }

        // Visibility cannot exist meaningfully far beyond the configured
        // vanilla distance. This is only an optimization gate; ownership still
        // comes exclusively from LevelRenderer below.
        int guardBlocks =
                (client.options.getEffectiveRenderDistance() + 2) * 16;
        double nearestX = Math.max(
                tile.minX(),
                Math.min(client.player.getX(), tile.maxX())
        );
        double nearestZ = Math.max(
                tile.minZ(),
                Math.min(client.player.getZ(), tile.maxZ())
        );
        double dx = nearestX - client.player.getX();
        double dz = nearestZ - client.player.getZ();

        if (dx * dx + dz * dz
                > (double) guardBlocks * guardBlocks) {
            return 0L;
        }

        int minChunkX = Math.floorDiv(tile.minX() - 1, 16);
        int maxChunkX = Math.floorDiv(tile.maxX(), 16);
        int minChunkZ = Math.floorDiv(tile.minZ() - 1, 16);
        int maxChunkZ = Math.floorDiv(tile.maxZ(), 16);
        int minSectionY = Math.floorDiv(tile.minY() - 1, 16);
        int maxSectionY = Math.floorDiv(tile.maxY(), 16);

        long hash = 0xcbf29ce484222325L;
        boolean anyVisible = false;
        BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

        for (int sectionY = minSectionY;
             sectionY <= maxSectionY;
             sectionY++) {
            int blockY = sectionY * 16 + 8;

            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                int blockZ = chunkZ * 16 + 8;

                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                    int blockX = chunkX * 16 + 8;
                    boolean visible = vanillaSectionVisible(
                            client,
                            blockX,
                            blockY,
                            blockZ,
                            scratch
                    );
                    anyVisible |= visible;

                    long value = ((long) chunkX << 42)
                            ^ ((long) sectionY << 21)
                            ^ (chunkZ & 0x1F_FFFFL)
                            ^ (visible ? 0x9E3779B97F4A7C15L : 0L);
                    hash ^= value;
                    hash *= 0x100000001b3L;
                }
            }
        }

        return anyVisible ? hash : 0L;
    }

    private static boolean quadOwnedByVisibleVanilla(
            Minecraft client,
            int[] vertices,
            int quadOffset,
            BlockPos.MutableBlockPos scratch
    ) {
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

        // Top/horizontal face: remove it only if every vanilla section touched
        // by the face is compiled AND currently visible in the terrain renderer.
        if (minY == maxY && minX < maxX && minZ < maxZ) {
            return allVisibleSections(
                    client,
                    minX,
                    maxX - 1,
                    minY - 1,
                    minY - 1,
                    minZ,
                    maxZ - 1,
                    scratch
            );
        }

        // Vertical wall on an X boundary: require visible vanilla on both sides
        // for the full vertical span. Keeping the wall otherwise guarantees a
        // fallback seam while vanilla is still compiling/fading/culled.
        if (minX == maxX && minZ < maxZ) {
            return allVisibleSections(
                    client,
                    minX - 1,
                    minX - 1,
                    minY,
                    maxY - 1,
                    minZ,
                    maxZ - 1,
                    scratch
            ) && allVisibleSections(
                    client,
                    minX,
                    minX,
                    minY,
                    maxY - 1,
                    minZ,
                    maxZ - 1,
                    scratch
            );
        }

        // Same ownership rule for a Z-boundary wall.
        if (minZ == maxZ && minX < maxX) {
            return allVisibleSections(
                    client,
                    minX,
                    maxX - 1,
                    minY,
                    maxY - 1,
                    minZ - 1,
                    minZ - 1,
                    scratch
            ) && allVisibleSections(
                    client,
                    minX,
                    maxX - 1,
                    minY,
                    maxY - 1,
                    minZ,
                    minZ,
                    scratch
            );
        }

        return false;
    }

    private static boolean allVisibleSections(
            Minecraft client,
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ,
            BlockPos.MutableBlockPos scratch
    ) {
        int minChunkX = Math.floorDiv(minX, 16);
        int maxChunkX = Math.floorDiv(maxX, 16);
        int minChunkZ = Math.floorDiv(minZ, 16);
        int maxChunkZ = Math.floorDiv(maxZ, 16);
        int minSectionY = Math.floorDiv(minY, 16);
        int maxSectionY = Math.floorDiv(maxY, 16);

        for (int sectionY = minSectionY;
             sectionY <= maxSectionY;
             sectionY++) {
            int blockY = sectionY * 16 + 8;

            for (int chunkZ = minChunkZ;
                 chunkZ <= maxChunkZ;
                 chunkZ++) {
                int blockZ = chunkZ * 16 + 8;

                for (int chunkX = minChunkX;
                     chunkX <= maxChunkX;
                     chunkX++) {
                    int blockX = chunkX * 16 + 8;

                    if (!vanillaSectionVisible(
                            client,
                            blockX,
                            blockY,
                            blockZ,
                            scratch
                    )) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private static boolean vanillaSectionVisible(
            Minecraft client,
            int blockX,
            int blockY,
            int blockZ,
            BlockPos.MutableBlockPos scratch
    ) {
        scratch.set(blockX, blockY, blockZ);

        // 26.3 LevelRenderer owns the exact condition we care about:
        // compiled AND visible in the terrain renderer. A zero fade duration
        // asks for binary ownership; Everview stays until vanilla is ready.
        return client.levelRenderer.isSectionCompiledAndVisible(
                scratch,
                0L
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

    public record GpuTile(
            WorldgenSurfaceTile source,
            GpuBuffer vertexBuffer,
            int indexCount,
            long bytes,
            long vanillaVisibilitySignature
    ) implements AutoCloseable {
        @Override
        public void close() {
            if (!vertexBuffer.isClosed()) {
                vertexBuffer.close();
            }
        }
    }
}
