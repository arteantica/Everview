package dev.everview.client;

import dev.everview.core.DrawableCoverage;
import dev.everview.core.LodTileKey;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** Joins neighboring drawable surfaces at their actual heights during the ownership transaction. */
public final class TerrainStitches {
    public record Prepared(int originX, int originZ, ByteBuffer vertices, int indexCount) {}
    record Quad(boolean xEdge, float constant, float start, float end,
                        float a0, float a1, float b0, float b1, int color) {}

    static Prepared build(List<EverviewGpuRegionCache.GpuRegion> residents, DrawableCoverage coverage, double x, double z) {
        Map<LodTileKey,EverviewGpuRegionCache.GpuTile> tiles = new HashMap<>();
        for (var region : residents) for (var tile : region.tileViews()) {
            var s = tile.source(); tiles.put(new LodTileKey(s.lodLevel(),s.tileX(),s.tileZ()),tile);
        }
        List<Quad> quads = new ArrayList<>();
        for (var tile : tiles.values()) {
            int level = tile.source().lodLevel();
            for (var entry : tile.edges().profiles().entrySet()) {
                var edge = entry.getKey();
                if ((edge.side() & 1) == 0 || coverage.levelAt(edge.cellX(),edge.cellZ()) != level) continue;
                var otherEdge = edge.opposite();
                int otherLevel = coverage.levelAt(otherEdge.cellX(),otherEdge.cellZ());
                if (otherLevel == 0) continue; // A missing neighbor is a coverage gap, never filled by a fake surface.
                int size = otherLevel <= 2 ? 128 : 128 << (otherLevel - 2);
                var other = tiles.get(new LodTileKey(otherLevel,Math.floorDiv(otherEdge.cellX()*128,size),Math.floorDiv(otherEdge.cellZ()*128,size)));
                if (other == null || other == tile) continue;
                // Equal lattices share their sampled boundaries; exact columns already have real side faces.
                if (level == otherLevel && tile.source().sampleSpacing() == other.source().sampleSpacing()) continue;
                List<TerrainEdges.Segment> opposite = other.edges().profiles().get(otherEdge);
                if (opposite == null) continue;
                join(edge, entry.getValue(), opposite, quads);
            }
        }
        int originX = (int)Math.floor(x / 128) * 128, originZ = (int)Math.floor(z / 128) * 128;
        ByteBuffer vertices = ByteBuffer.allocateDirect(Math.multiplyExact(quads.size(),64)).order(ByteOrder.nativeOrder());
        for (Quad q : quads) {
            vertex(vertices,q,q.start,q.a0,originX,originZ); vertex(vertices,q,q.end,q.a1,originX,originZ);
            vertex(vertices,q,q.end,q.b1,originX,originZ); vertex(vertices,q,q.start,q.b0,originX,originZ);
        }
        vertices.flip(); return new Prepared(originX,originZ,vertices,quads.size()*6);
    }

    static void join(TerrainEdges.Edge edge, List<TerrainEdges.Segment> a, List<TerrainEdges.Segment> b, List<Quad> out) {
        int i = 0, j = 0;
        while (i < a.size() && j < b.size()) {
            var sa = a.get(i); var sb = b.get(j);
            float start = Math.max(sa.start(),sb.start()), end = Math.min(sa.end(),sb.end());
            if (end > start) {
                float a0=sa.height(start), a1=sa.height(end), b0=sb.height(start), b1=sb.height(end);
                float d0=a0-b0, d1=a1-b1;
                if (Math.abs(d0)>0.001f || Math.abs(d1)>0.001f) {
                    boolean xEdge = edge.side() == 1;
                    float constant = (xEdge ? edge.cellX()+1 : edge.cellZ()+1)*128;
                    int color = a0+a1 >= b0+b1 ? sa.color() : sb.color();
                    if (d0*d1 < 0) {
                        float split = start + (end-start)*d0/(d0-d1);
                        float y = sa.height(split);
                        out.add(new Quad(xEdge,constant,start,split,a0,y,b0,y,color));
                        out.add(new Quad(xEdge,constant,split,end,y,a1,y,b1,color));
                    } else out.add(new Quad(xEdge,constant,start,end,a0,a1,b0,b1,color));
                }
            }
            if (sa.end() <= sb.end()) i++;
            if (sb.end() <= sa.end()) j++;
        }
    }

    private static void vertex(ByteBuffer out, Quad q, float along, float y, int originX, int originZ) {
        out.putFloat((q.xEdge?q.constant:along)-originX).putFloat(y).putFloat((q.xEdge?along:q.constant)-originZ);
        out.put((byte)(q.color>>16)).put((byte)(q.color>>8)).put((byte)q.color).put((byte)255);
    }
}
