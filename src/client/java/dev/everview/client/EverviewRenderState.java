package dev.everview.client;

import dev.everview.core.DrawableCoverage;
import net.minecraft.world.phys.AABB;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Immutable upload/ownership/command transaction. Built on a worker, published on the render thread. */
public record EverviewRenderState(List<RegionCommands> regions, int coverageCells, int maskedBatches) {
    public static final EverviewRenderState EMPTY = new EverviewRenderState(List.of(), 0, 0);

    public static EverviewRenderState build(List<EverviewGpuRegionCache.GpuRegion> residents) {
        long started = System.nanoTime();
        DrawableCoverage coverage = new DrawableCoverage();
        for (var region : residents) for (var tile : region.tileViews()) {
            var source = tile.source();
            coverage.add(source.lodLevel(), source.minX(), source.minZ(), source.tileSize());
        }
        List<RegionCommands> regions = new ArrayList<>();
        int masked = 0;
        for (var region : residents) {
            List<TileCommands> tiles = new ArrayList<>();
            List<Range> ranges = new ArrayList<>();
            AABB bounds = null;
            for (var tile : region.tileViews()) {
                var source = tile.source();
                List<EverviewGpuTileCache.DrawBatch> visible = new ArrayList<>();
                List<Range> tileRanges = new ArrayList<>();
                for (var batch : tile.drawBatches()) {
                    int cellX = source.lodLevel() <= 2 ? Math.floorDiv(source.minX(), 128) : batch.regionTileX();
                    int cellZ = source.lodLevel() <= 2 ? Math.floorDiv(source.minZ(), 128) : batch.regionTileZ();
                    if (coverage.finerOwns(source.lodLevel(), cellX, cellZ)) {
                        masked++;
                    } else {
                        visible.add(batch);
                        append(tileRanges, batch.firstIndex(), batch.indexCount());
                    }
                }
                if (tileRanges.isEmpty()) continue;
                AABB tileBounds = new AABB(source.minX(), source.minY() - 8.0, source.minZ(),
                        source.maxX(), Math.max(source.maxY(), source.seaLevel()) + 8.0, source.maxZ());
                bounds = bounds == null ? tileBounds : bounds.minmax(tileBounds);
                tiles.add(new TileCommands(tile, tileBounds, List.copyOf(visible), List.copyOf(tileRanges)));
                ranges.addAll(tileRanges);
            }
            if (ranges.isEmpty()) continue;
            regions.add(new RegionCommands(region, bounds, List.copyOf(tiles), NativeCommands.build(ranges)));
        }
        EverviewFrameProfiler.ownershipWorker = System.nanoTime() - started;
        return new EverviewRenderState(List.copyOf(regions), coverage.cells(), masked);
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
                                 List<TileCommands> tiles, NativeCommands commands) {}

    /** Both index widths are retained; the shared sequential index buffer can grow later. */
    public record NativeCommands(List<Range> ranges, PointerBuffer offsets16, PointerBuffer offsets32,
                                 IntBuffer counts, IntBuffer baseVertices, int quads) {
        public static NativeCommands build(List<Range> input) {
            List<Range> ordered = new ArrayList<>(input);
            ordered.sort(Comparator.comparingInt(Range::first));
            List<Range> merged = new ArrayList<>();
            for (Range range : ordered) append(merged, range.first(), range.count());
            int n = merged.size();
            PointerBuffer offsets16 = BufferUtils.createPointerBuffer(n);
            PointerBuffer offsets32 = BufferUtils.createPointerBuffer(n);
            IntBuffer counts = BufferUtils.createIntBuffer(n);
            IntBuffer bases = BufferUtils.createIntBuffer(n);
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
