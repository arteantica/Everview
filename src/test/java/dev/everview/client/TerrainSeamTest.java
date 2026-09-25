package dev.everview.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class TerrainSeamTest {
    @Test void profilesUseTheActualGeneratedSurfaceHeights() {
        int[] v = {0, 72, 0, 0, 80, 128, 128, 64, 128, 128, 60, 0};
        int[] c = {0x008800, 0x008800, 0x008800, 0x008800};
        var geometry = new EverviewGpuTileCache.PreparedGeometry(null, v, c, new byte[4], 6, List.of());
        TerrainEdges edges = TerrainEdges.from(geometry, 0, 0);
        assertEquals(new TerrainEdges.Segment(0, 128, 60, 64, 0x008800, (byte)0),
                edges.profiles().get(new TerrainEdges.Edge(0, 0, 1)).getFirst());
        assertEquals(72, edges.profiles().get(new TerrainEdges.Edge(0, 0, 2)).getFirst().y0());
    }

    @Test void crossingProfilesSplitWithoutTwistingTheSeam() {
        var first = new TerrainEdges.Segment(0, 128, 100, 60, 0x00ff00, (byte)0);
        var second = new TerrainEdges.Segment(0, 128, 60, 100, 0x00aa00, (byte)0);
        List<TerrainStitches.Quad> quads = new ArrayList<>();
        TerrainStitches.join(new TerrainEdges.Edge(0, 0, 1), List.of(first), List.of(second), quads);
        assertEquals(2, quads.size());
        assertEquals(0f, quads.getFirst().start());
        assertEquals(64f, quads.getFirst().end());
        assertEquals(quads.getFirst().end(), quads.getLast().start());
        assertEquals(128f, quads.getLast().end());
        assertEquals(quads.getFirst().a1(), quads.getFirst().b1(), 0.001f);
        assertEquals(quads.getLast().a0(), quads.getLast().b0(), 0.001f);
    }

    @Test void equalOrAbsentNeighborGeneratesNoFakeSurface() {
        var a = new TerrainEdges.Segment(0, 128, 64, 64, 0xffffff, (byte)0);
        List<TerrainStitches.Quad> quads = new ArrayList<>();
        TerrainStitches.join(new TerrainEdges.Edge(0, 0, 1), List.of(a), List.of(a), quads);
        assertTrue(quads.isEmpty());
        TerrainStitches.join(new TerrainEdges.Edge(0, 0, 1), List.of(a), List.of(), quads);
        assertTrue(quads.isEmpty());
    }
}
