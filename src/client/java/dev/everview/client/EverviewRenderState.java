package dev.everview.client;

import dev.everview.core.DrawableCoverage;
import net.minecraft.world.phys.AABB;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.BitSet;
import java.util.IdentityHashMap;

/** Immutable upload/ownership/command transaction. Built on a worker, published on the render thread. */
public record EverviewRenderState(List<RegionCommands> regions, int coverageCells, int maskedBatches,
                                 int missingCells, DrawableCoverage coverage,
                                 Map<EverviewGpuRegionCache.GpuRegion, RegionCommands> cached, TerrainStitches.Prepared stitches) {
    public static final EverviewRenderState EMPTY = new EverviewRenderState(List.of(), 0, 0, 0, new DrawableCoverage(), Map.of(), null);

    public static EverviewRenderState build(List<EverviewGpuRegionCache.GpuRegion> residents,
                                            EverviewRenderState previous, double x, double z) {
        long started = System.nanoTime();
        DrawableCoverage coverage = new DrawableCoverage();
        for (var region : residents) for (var tile : region.tileViews()) {
            var source = tile.source();
            coverage.add(source.lodLevel(), source.minX(), source.minZ(), source.tileSize());
        }
        List<RegionCommands> regions = new ArrayList<>();
        int masked = 0, reused = 0, rebuilt = 0;
        Map<EverviewGpuRegionCache.GpuRegion, RegionCommands> cached = new IdentityHashMap<>();
        for (var region : residents) {
            BitSet mask = coverage.finerMask(region.lodLevel(), region.originX(), region.originZ(),
                    region.tileViews().getFirst().source().tileSize() * 2);
            RegionCommands old = previous.cached().get(region);
            if (old != null && old.mask().equals(mask)) {
                reused++;
                cached.put(region, old); masked += old.masked();
                if (!old.commands().ranges().isEmpty()) regions.add(old);
                continue;
            }
            rebuilt++;
            int regionMasked = 0;
            List<TileCommands> tiles = new ArrayList<>();
            List<Range> ranges = new ArrayList<>();
            AABB bounds = null;
            for (var tile : region.tileViews()) {
                var source = tile.source();
                boolean allFiner = true, anyFiner = false;
                for (int cz = Math.floorDiv(source.minZ(), 128); cz < Math.floorDiv(source.maxZ(), 128); cz++) {
                    for (int cx = Math.floorDiv(source.minX(), 128); cx < Math.floorDiv(source.maxX(), 128); cx++) {
                        boolean finer = coverage.finerOwns(source.lodLevel(), cx, cz);
                        allFiner &= finer; anyFiner |= finer;
                    }
                }
                if (allFiner) { regionMasked += tile.drawBatches().size(); continue; }
                List<EverviewGpuTileCache.DrawBatch> visible;
                List<Range> tileRanges = new ArrayList<>();
                if (!anyFiner) {
                    visible = tile.drawBatches(); append(tileRanges, tile.firstIndex(), tile.indexCount());
                } else {
                    visible = new ArrayList<>();
                for (var batch : tile.drawBatches()) {
                    int cellX = source.lodLevel() <= 2 ? Math.floorDiv(source.minX(), 128) : batch.regionTileX();
                    int cellZ = source.lodLevel() <= 2 ? Math.floorDiv(source.minZ(), 128) : batch.regionTileZ();
                    if (coverage.finerOwns(source.lodLevel(), cellX, cellZ)) {
                        regionMasked++;
                    } else {
                        visible.add(batch);
                        append(tileRanges, batch.firstIndex(), batch.indexCount());
                    }
                }
                }
                if (tileRanges.isEmpty()) continue;
                AABB tileBounds = new AABB(source.minX(), source.minY() - 8.0, source.minZ(),
                        source.maxX(), Math.max(source.maxY(), source.seaLevel()) + 8.0, source.maxZ());
                bounds = bounds == null ? tileBounds : bounds.minmax(tileBounds);
                tiles.add(new TileCommands(tile, tileBounds, List.copyOf(visible), List.copyOf(tileRanges)));
                ranges.addAll(tileRanges);
            }
            RegionCommands commands = new RegionCommands(region, bounds, List.copyOf(tiles), NativeCommands.build(ranges), mask, regionMasked);
            cached.put(region, commands); masked += regionMasked;
            if (!ranges.isEmpty()) regions.add(commands);
        }
        var stitches = TerrainStitches.build(residents, coverage, x, z);
        int missing = coverage.missingInDisk(x, z, 16_384);
        EverviewFrameProfiler.reusedRegions = reused;
        EverviewFrameProfiler.rebuiltRegions = rebuilt;
        EverviewFrameProfiler.seamIndices = stitches.indexCount();
        EverviewFrameProfiler.ownershipWorker = System.nanoTime() - started;
        return new EverviewRenderState(List.copyOf(regions), coverage.cells(), masked,
                missing, coverage, Map.copyOf(cached), stitches);
    }

    public static void append(List<Range> ranges, int first, int count) {
        if (count == 0) return;
        if (!ranges.isEmpty()) {
            Range last = ranges.getLast();
            if (last.first() + last.count() == first) {
                ranges.set(ranges.size() - 1, new Range(last.first(), last.count() + count));
                return;
            }
        }
        ranges.add(new Range(first, count));
    }

    public record Range(int first, int count) {}
    public record TileCommands(EverviewGpuRegionCache.GpuTile tile, AABB bounds,
                               List<EverviewGpuTileCache.DrawBatch> batches, List<Range> ranges) {}
    public record RegionCommands(EverviewGpuRegionCache.GpuRegion region, AABB bounds,
                                 List<TileCommands> tiles, NativeCommands commands, BitSet mask, int masked) {}

    /** Both index widths are retained; the shared sequential index buffer can grow later. */
    public record NativeCommands(List<Range> ranges, PointerBuffer offsets16, PointerBuffer offsets32,
                                 IntBuffer counts, IntBuffer baseVertices, int quads) {
        public static NativeCommands build(List<Range> input) {
            List<Range> ordered = new ArrayList<>(input);
            ordered.sort(Comparator.comparingInt(Range::first));
            List<Range> merged = new ArrayList<>();
            for (Range range : ordered) append(merged, range.first(), range.count());
            int n = merged.size();
            // Some native allocators reject a zero-capacity pointer buffer.
            PointerBuffer offsets16 = BufferUtils.createPointerBuffer(Math.max(1, n));
            PointerBuffer offsets32 = BufferUtils.createPointerBuffer(Math.max(1, n));
            IntBuffer counts = BufferUtils.createIntBuffer(Math.max(1, n));
            IntBuffer bases = BufferUtils.createIntBuffer(Math.max(1, n));
            int quads = 0;
            for (int i = 0; i < n; i++) {
                Range range = merged.get(i);
                offsets16.put(i, range.first() * 2L);
                offsets32.put(i, range.first() * 4L);
                counts.put(i, range.count());
                quads += range.count() / 6;
            }
            return new NativeCommands(List.copyOf(merged), offsets16, offsets32, counts, bases, quads);
        }
    }
}
