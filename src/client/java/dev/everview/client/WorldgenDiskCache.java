package dev.everview.client;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Small binary cache for Everview's generated LOD surface tiles.
 *
 * The file lives inside the world save, is seed + dimension checked, and uses a
 * versioned header so stale formats can be ignored safely.
 */
public final class WorldgenDiskCache {
    private static final int MAGIC = 0x45564C31; // EVL1
    private static final int VERSION = 28;
    private static final int MAX_TILES = 100_000;
    private static final int MAX_VERTEX_INTS = 1_000_000;

    private WorldgenDiskCache() {
    }

    public static Path pathFor(MinecraftServer server, ResourceKey<Level> dimension) {
        String dimensionId = dimension.identifier().toString()
                .replace(':', '_')
                .replace('/', '_')
                .replace('\\', '_');

        return server.getWorldPath(LevelResource.ROOT)
                .resolve("everview")
                .resolve("lod-cache-v28")
                .resolve(dimensionId + ".evc.gz");
    }

    public static long seedFor(MinecraftServer server, ResourceKey<Level> dimension) {
        var level = server.getLevel(dimension);
        return level == null ? 0L : level.getSeed();
    }

    public static String dimensionId(ResourceKey<Level> dimension) {
        return dimension.identifier().toString();
    }

    public static LoadResult load(Path path, long expectedSeed, String expectedDimension) {
        long started = System.nanoTime();

        if (!Files.isRegularFile(path)) {
            return new LoadResult(List.of(), false, elapsedMs(started), "MISS");
        }

        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(
                        new GZIPInputStream(Files.newInputStream(path))))) {

            int magic = input.readInt();
            int version = input.readInt();
            long seed = input.readLong();
            String dimension = input.readUTF();

            if (magic != MAGIC || version != VERSION) {
                return new LoadResult(List.of(), false, elapsedMs(started), "VERSION_MISMATCH");
            }

            if (seed != expectedSeed || !dimension.equals(expectedDimension)) {
                return new LoadResult(List.of(), false, elapsedMs(started), "WORLD_MISMATCH");
            }

            int tileCount = input.readInt();
            if (tileCount < 0 || tileCount > MAX_TILES) {
                return new LoadResult(List.of(), false, elapsedMs(started), "INVALID_COUNT");
            }

            List<WorldgenSurfaceTile> tiles = new ArrayList<>(tileCount);

            for (int i = 0; i < tileCount; i++) {
                int lodLevel = input.readInt();
                int tileX = input.readInt();
                int tileZ = input.readInt();
                int tileSize = input.readInt();
                int sampleSpacing = input.readInt();
                int stageOrdinal = input.readInt();
                int cellCount = input.readInt();
                int minY = input.readInt();
                int maxY = input.readInt();
                int seaLevel = input.readInt();
                int vertexLength = input.readInt();

                if (lodLevel < 1
                        || tileSize <= 0
                        || sampleSpacing <= 0
                        || stageOrdinal < 0
                        || stageOrdinal >= WorldgenTileStage.values().length
                        || cellCount < 0
                        || vertexLength < 0
                        || vertexLength > MAX_VERTEX_INTS
                        || vertexLength % 3 != 0) {
                    return new LoadResult(List.of(), false, elapsedMs(started), "CORRUPT");
                }

                int[] vertices = new int[vertexLength];
                for (int vertex = 0; vertex < vertexLength; vertex++) {
                    vertices[vertex] = input.readInt();
                }

                int colorLength = input.readInt();
                if (colorLength != vertexLength / 3 || colorLength > MAX_VERTEX_INTS / 3) {
                    return new LoadResult(List.of(), false, elapsedMs(started), "CORRUPT");
                }

                int[] colors = new int[colorLength];
                for (int color = 0; color < colorLength; color++) {
                    colors[color] = input.readInt();
                }

                int materialLength = input.readInt();
                if (materialLength != colorLength) {
                    return new LoadResult(List.of(), false, elapsedMs(started), "CORRUPT");
                }

                byte[] materials = new byte[materialLength];
                input.readFully(materials);

                tiles.add(new WorldgenSurfaceTile(
                        lodLevel,
                        tileX,
                        tileZ,
                        tileSize,
                        sampleSpacing,
                        WorldgenTileStage.values()[stageOrdinal],
                        vertices,
                        colors,
                        materials,
                        cellCount,
                        minY,
                        maxY,
                        seaLevel,
                        0L
                ));
            }

            return new LoadResult(
                    List.copyOf(tiles),
                    true,
                    elapsedMs(started),
                    "HIT"
            );
        } catch (EOFException exception) {
            return new LoadResult(List.of(), false, elapsedMs(started), "TRUNCATED");
        } catch (IOException | RuntimeException exception) {
            EverviewClient.LOGGER.warn("Everview could not read LOD disk cache {}", path, exception);
            return new LoadResult(List.of(), false, elapsedMs(started), "ERROR");
        }
    }

    public static SaveResult save(
            Path path,
            long seed,
            String dimension,
            List<WorldgenSurfaceTile> tiles
    ) {
        long started = System.nanoTime();
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");

        try {
            Files.createDirectories(path.getParent());

            try (DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(
                            new GZIPOutputStream(Files.newOutputStream(temp))))) {

                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeLong(seed);
                output.writeUTF(dimension);
                output.writeInt(tiles.size());

                for (WorldgenSurfaceTile tile : tiles) {
                    output.writeInt(tile.lodLevel());
                    output.writeInt(tile.tileX());
                    output.writeInt(tile.tileZ());
                    output.writeInt(tile.tileSize());
                    output.writeInt(tile.sampleSpacing());
                    output.writeInt(tile.stage().ordinal());
                    output.writeInt(tile.cellCount());
                    output.writeInt(tile.minY());
                    output.writeInt(tile.maxY());
                    output.writeInt(tile.seaLevel());

                    int[] vertices = tile.vertices();
                    output.writeInt(vertices.length);
                    for (int vertex : vertices) {
                        output.writeInt(vertex);
                    }

                    int[] colors = tile.colors();
                    output.writeInt(colors.length);
                    for (int color : colors) {
                        output.writeInt(color);
                    }

                    byte[] materials = tile.materials();
                    output.writeInt(materials.length);
                    output.write(materials);
                }
            }

            try {
                Files.move(
                        temp,
                        path,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }

            return new SaveResult(
                    tiles.size(),
                    Files.size(path),
                    elapsedMs(started),
                    "SAVED"
            );
        } catch (IOException | RuntimeException exception) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }

            EverviewClient.LOGGER.warn("Everview could not write LOD disk cache {}", path, exception);
            return new SaveResult(0, 0L, elapsedMs(started), "ERROR");
        }
    }

    private static double elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000.0;
    }

    public record LoadResult(
            List<WorldgenSurfaceTile> tiles,
            boolean hit,
            double elapsedMs,
            String status
    ) {
    }

    public record SaveResult(
            int tileCount,
            long bytes,
            double elapsedMs,
            String status
    ) {
    }
}
