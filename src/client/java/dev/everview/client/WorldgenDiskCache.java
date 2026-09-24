package dev.everview.client;

import dev.everview.core.LodTileKey;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * M9.1 regional persistent cache.
 *
 * Earlier builds rewrote one ever-growing gzip snapshot every autosave. Once
 * exact L1 became dense, one save could spend several seconds compressing the
 * entire world and continuously steal CPU from rendering/worldgen. M9.1 shards
 * the cache into small 512-block regions and only rewrites shards touched by
 * newly published tiles.
 */
public final class WorldgenDiskCache {
    private static final int MAGIC = 0x45564C31; // EVL1
    private static final int VERSION = 32;
    private static final int SHARD_BLOCKS = 512;
    private static final int MAX_TILES_PER_SHARD = 4_096;
    private static final int MAX_VERTEX_INTS = 4_000_000;

    private WorldgenDiskCache() {
    }

    public static Path pathFor(MinecraftServer server, ResourceKey<Level> dimension) {
        String dimensionId = dimension.identifier().toString()
                .replace(':', '_')
                .replace('/', '_')
                .replace('\\', '_');

        return server.getWorldPath(LevelResource.ROOT)
                .resolve("everview")
                .resolve("lod-cache-v32")
                .resolve(dimensionId);
    }

    public static long seedFor(MinecraftServer server, ResourceKey<Level> dimension) {
        var level = server.getLevel(dimension);
        return level == null ? 0L : level.getSeed();
    }

    public static String dimensionId(ResourceKey<Level> dimension) {
        return dimension.identifier().toString();
    }

    public static LoadResult load(
            Path directory,
            long expectedSeed,
            String expectedDimension
    ) {
        long started = System.nanoTime();

        if (!Files.isDirectory(directory)) {
            return new LoadResult(List.of(), false, elapsedMs(started), "MISS");
        }

        List<WorldgenSurfaceTile> tiles = new ArrayList<>();
        int failedShards = 0;

        try (var stream = Files.list(directory)) {
            List<Path> shards = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".evr.gz"))
                    .sorted()
                    .toList();

            if (shards.isEmpty()) {
                return new LoadResult(List.of(), false, elapsedMs(started), "MISS");
            }

            for (Path shard : shards) {
                ShardLoadResult loaded = loadShard(
                        shard,
                        expectedSeed,
                        expectedDimension
                );
                if (loaded.valid()) {
                    tiles.addAll(loaded.tiles());
                } else {
                    failedShards++;
                }
            }
        } catch (IOException exception) {
            EverviewClient.LOGGER.warn(
                    "Everview could not scan regional LOD cache {}",
                    directory,
                    exception
            );
            return new LoadResult(List.of(), false, elapsedMs(started), "ERROR");
        }

        String status;
        if (tiles.isEmpty()) {
            status = failedShards > 0 ? "SHARD_ERROR" : "MISS";
        } else {
            status = failedShards > 0 ? "PARTIAL_HIT" : "HIT";
        }

        return new LoadResult(
                List.copyOf(tiles),
                !tiles.isEmpty(),
                elapsedMs(started),
                status
        );
    }

    public static SaveResult save(
            Path directory,
            long seed,
            String dimension,
            List<WorldgenSurfaceTile> dirtyTiles
    ) {
        long started = System.nanoTime();

        if (dirtyTiles.isEmpty()) {
            return new SaveResult(0, 0L, elapsedMs(started), "IDLE");
        }

        Map<ShardKey, List<WorldgenSurfaceTile>> byShard = new HashMap<>();
        for (WorldgenSurfaceTile tile : dirtyTiles) {
            ShardKey shard = shardFor(tile);
            byShard.computeIfAbsent(shard, ignored -> new ArrayList<>())
                    .add(tile);
        }

        int writtenTiles = 0;
        long writtenBytes = 0L;

        try {
            Files.createDirectories(directory);

            for (Map.Entry<ShardKey, List<WorldgenSurfaceTile>> entry
                    : byShard.entrySet()) {
                ShardKey shard = entry.getKey();
                Path target = shardPath(directory, shard);

                Map<LodTileKey, WorldgenSurfaceTile> merged = new HashMap<>();
                if (Files.isRegularFile(target)) {
                    ShardLoadResult existing = loadShard(target, seed, dimension);
                    if (existing.valid()) {
                        for (WorldgenSurfaceTile tile : existing.tiles()) {
                            merged.put(keyOf(tile), tile);
                        }
                    }
                }

                for (WorldgenSurfaceTile tile : entry.getValue()) {
                    merged.put(keyOf(tile), tile);
                }

                writeShard(
                        target,
                        seed,
                        dimension,
                        new ArrayList<>(merged.values())
                );

                writtenTiles += entry.getValue().size();
                writtenBytes += Files.size(target);
            }

            return new SaveResult(
                    writtenTiles,
                    writtenBytes,
                    elapsedMs(started),
                    "SAVED"
            );
        } catch (IOException | RuntimeException exception) {
            EverviewClient.LOGGER.warn(
                    "Everview could not write regional LOD cache {}",
                    directory,
                    exception
            );
            return new SaveResult(
                    0,
                    writtenBytes,
                    elapsedMs(started),
                    "ERROR"
            );
        }
    }

    private static ShardLoadResult loadShard(
            Path path,
            long expectedSeed,
            String expectedDimension
    ) {
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(
                        new GZIPInputStream(Files.newInputStream(path))))) {

            int magic = input.readInt();
            int version = input.readInt();
            long seed = input.readLong();
            String dimension = input.readUTF();

            if (magic != MAGIC
                    || version != VERSION
                    || seed != expectedSeed
                    || !dimension.equals(expectedDimension)) {
                return new ShardLoadResult(List.of(), false);
            }

            int tileCount = input.readInt();
            if (tileCount < 0 || tileCount > MAX_TILES_PER_SHARD) {
                return new ShardLoadResult(List.of(), false);
            }

            List<WorldgenSurfaceTile> tiles = new ArrayList<>(tileCount);
            for (int i = 0; i < tileCount; i++) {
                WorldgenSurfaceTile tile = readTile(input);
                if (tile == null) {
                    return new ShardLoadResult(List.of(), false);
                }
                tiles.add(tile);
            }

            return new ShardLoadResult(List.copyOf(tiles), true);
        } catch (EOFException exception) {
            return new ShardLoadResult(List.of(), false);
        } catch (IOException | RuntimeException exception) {
            EverviewClient.LOGGER.warn(
                    "Everview could not read regional LOD shard {}",
                    path,
                    exception
            );
            return new ShardLoadResult(List.of(), false);
        }
    }

    private static WorldgenSurfaceTile readTile(
            DataInputStream input
    ) throws IOException {
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
            return null;
        }

        int[] vertices = new int[vertexLength];
        for (int vertex = 0; vertex < vertexLength; vertex++) {
            vertices[vertex] = input.readInt();
        }

        int colorLength = input.readInt();
        if (colorLength != vertexLength / 3
                || colorLength > MAX_VERTEX_INTS / 3) {
            return null;
        }

        int[] colors = new int[colorLength];
        for (int color = 0; color < colorLength; color++) {
            colors[color] = input.readInt();
        }

        int materialLength = input.readInt();
        if (materialLength != colorLength) {
            return null;
        }

        byte[] materials = new byte[materialLength];
        input.readFully(materials);

        return new WorldgenSurfaceTile(
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
        );
    }

    private static void writeShard(
            Path target,
            long seed,
            String dimension,
            List<WorldgenSurfaceTile> tiles
    ) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");

        try {
            try (DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(
                            new FastGzipOutputStream(
                                    Files.newOutputStream(temp)
                            )))) {

                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeLong(seed);
                output.writeUTF(dimension);
                output.writeInt(tiles.size());

                for (WorldgenSurfaceTile tile : tiles) {
                    writeTile(output, tile);
                }
            }

            try {
                Files.move(
                        temp,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temp,
                        target,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void writeTile(
            DataOutputStream output,
            WorldgenSurfaceTile tile
    ) throws IOException {
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

    private static ShardKey shardFor(WorldgenSurfaceTile tile) {
        return new ShardKey(
                Math.floorDiv(tile.minX(), SHARD_BLOCKS),
                Math.floorDiv(tile.minZ(), SHARD_BLOCKS)
        );
    }

    private static Path shardPath(Path directory, ShardKey shard) {
        return directory.resolve(
                "r." + shard.x() + "." + shard.z() + ".evr.gz"
        );
    }

    private static LodTileKey keyOf(WorldgenSurfaceTile tile) {
        return new LodTileKey(
                tile.lodLevel(),
                tile.tileX(),
                tile.tileZ()
        );
    }

    private static double elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000.0;
    }

    private static final class FastGzipOutputStream extends GZIPOutputStream {
        private FastGzipOutputStream(java.io.OutputStream output)
                throws IOException {
            super(output, 64 * 1024);
            def.setLevel(Deflater.BEST_SPEED);
        }
    }

    private record ShardKey(int x, int z) {
    }

    private record ShardLoadResult(
            List<WorldgenSurfaceTile> tiles,
            boolean valid
    ) {
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
