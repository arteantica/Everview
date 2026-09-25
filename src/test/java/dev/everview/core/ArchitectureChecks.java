package dev.everview.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** CPU-only regressions; also runnable without Minecraft, Gradle, or a graphics context. */
public final class ArchitectureChecks {
    private record Quad(int x0, int z0, int x1, int z1, int a, int b, int c, int d, byte material) {
        double area() { return (x1 - x0) * (double) (z1 - z0); }
        double height(double x, double z) {
            double u = (x - x0) / (x1 - x0), v = (z - z0) / (z1 - z0);
            return v >= u ? a + (b - a) * v + (c - b) * u : a + (d - a) * u + (c - d) * v;
        }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static int[] field(int cells, int height) {
        int[] result = new int[(cells + 1) * (cells + 1)]; Arrays.fill(result, height); return result;
    }
    private static List<Quad> mesh(int cells, int spacing, int[] height, double error) {
        byte[] materials = new byte[height.length]; int[] colors = new int[height.length];
        for (int i = 0; i < height.length; i++) materials[i] = height[i] < 63 ? (byte) 1 : 0;
        List<Quad> quads = new ArrayList<>();
        new AdaptiveSurfaceMesh(cells, spacing, 63, height, materials, colors, error,
                (x0,z0,x1,z1,a,b,c,d,m,rgb) -> quads.add(new Quad(x0,z0,x1,z1,a,b,c,d,m))).build();
        return quads;
    }

    public static void flatTerrain() {
        for (int y : new int[]{20, 80}) {
            List<Quad> quads = mesh(128, 16, field(128, y), 4);
            require(quads.size() == 256, "flat surface should use 256 ownership-aligned quads, not 16384");
            require(quads.stream().mapToDouble(Quad::area).sum() == 2048.0 * 2048, "flat footprint changed");
            for (Quad q : quads) require(q.a == Math.max(63, y) && q.b == q.a && q.c == q.a && q.d == q.a, "flat elevation changed");
        }
    }

    public static void peaksAndValleys() {
        int[] height = field(32, 90);
        height[13 * 33 + 11] = 211;
        height[21 * 33 + 25] = 65;
        List<Quad> quads = mesh(32, 16, height, 2);
        verifyError(quads, height, 32, 16, 2);
        require(quads.stream().anyMatch(q -> Math.max(Math.max(q.a,q.b),Math.max(q.c,q.d)) == 211), "sampled isolated peak lost");
        require(quads.stream().anyMatch(q -> Math.min(Math.min(q.a,q.b),Math.min(q.c,q.d)) == 65), "sampled valley lost");
        require(quads.size() < 1024, "feature preservation should still simplify the flat surroundings");
    }

    public static void saddleAndRidge() {
        int[] heights = field(32, 150);
        for (int z = 0; z <= 32; z++) for (int x = 0; x <= 32; x++) {
            heights[z * 33 + x] = 150 + (x - 16) * (z - 16) / 4 + (x == 11 ? 35 : 0);
        }
        verifyError(mesh(32, 8, heights, 2), heights, 32, 8, 2);
    }

    public static void islandAndInlet() {
        int[] island = field(4, 35); island[2 * 5 + 2] = 95;
        List<Quad> islandMesh = mesh(4, 16, island, 4);
        require(islandMesh.stream().filter(q -> q.material == 0).mapToDouble(Quad::area).sum() == 256,
                "one land sample must keep its 16x16 footprint inside water");
        verifyClassification(islandMesh, island, 4, 16);
        int[] lake = field(4, 85); lake[2 * 5 + 2] = 35;
        List<Quad> lakeMesh = mesh(4, 16, lake, 4);
        require(lakeMesh.stream().filter(q -> q.material == 1).mapToDouble(Quad::area).sum() == 256,
                "one wet sample must keep its 16x16 footprint inside land");
        verifyClassification(lakeMesh, lake, 4, 16);
    }

    public static void narrowChannel() {
        int[] channel = field(16, 80);
        for (int z = 0; z <= 16; z++) channel[z * 17 + 7] = 30;
        List<Quad> quads = mesh(16, 8, channel, 4);
        require(quads.stream().filter(q -> q.material == 1).mapToDouble(Quad::area).sum() == 128 * 8,
                "sampled channel was swallowed or expanded by material voting");
        verifyClassification(quads, channel, 16, 8);
    }

    public static void coverageTransitions() {
        DrawableCoverage parentOnly = new DrawableCoverage(); parentOnly.add(6, -2048, -2048, 2048);
        require(parentOnly.covers(-2048, -2048, 2048), "coarse floor incomplete");
        require(!parentOnly.finerOwns(6, -1, -1), "unready child hid its parent");
        DrawableCoverage oneReadyChild = new DrawableCoverage();
        oneReadyChild.add(6, -2048, -2048, 2048); oneReadyChild.add(1, -128, -128, 128);
        require(oneReadyChild.finerOwns(6, -1, -1), "ready child did not take ownership");
        require(!oneReadyChild.finerOwns(6, -2, -1), "one ready child hid an unready neighbor");
        require(oneReadyChild.covers(-2048, -2048, 2048), "refinement created a gap");
        // Reversion to the uploaded coarse floor is also a complete transaction.
        require(parentOnly.covers(-128, -128, 128), "degradation lost coverage");
    }

    public static void retirementAndBudget() {
        Set<LodTileKey> uploaded = new HashSet<>();
        require(!CoveragePolicy.mayRetire(1, -128, -128, false, uploaded::contains), "removed sole coverage before fallback upload");
        uploaded.add(new LodTileKey(6, -1, -1));
        require(CoveragePolicy.mayRetire(1, -128, -128, false, uploaded::contains), "ready parent should permit retirement");
        require(!CoveragePolicy.mayRetire(6, -2048, -2048, false, uploaded::contains), "base floor retired inside coverage");
        require(CoveragePolicy.mayRetire(6, -2048, -2048, true, uploaded::contains), "outside guard must be reclaimable");
        require(!CoveragePolicy.mayUpload(1100, 100, 1152), "old draw buffers were credited before release");
        require(CoveragePolicy.mayUpload(1000, 100, 1152), "bounded upload unnecessarily blocked");
        require(!CoveragePolicy.mayUpload(Long.MAX_VALUE - 1, 100, Long.MAX_VALUE), "budget overflow admitted allocation");
    }

    public static void coverageDiagnostic() {
        DrawableCoverage coverage = new DrawableCoverage();
        require(coverage.missingInDisk(0, 0, 200) > 0, "empty floor reports no gaps");
        coverage.add(6, -2048, -2048, 4096);
        require(coverage.missingInDisk(0, 0, 200) == 0, "complete disk reports gaps");
        var mask = coverage.finerMask(6, -2048, -2048, 4096);
        coverage.add(1, 128, 128, 128);
        require(!mask.equals(coverage.finerMask(6, -2048, -2048, 4096)), "exact ownership invalidation missed a child");
        require(coverage.missingInDisk(128, 128, 200) == 0, "movement inside generated floor creates false gaps");
    }

    private static void verifyError(List<Quad> quads, int[] heights, int cells, int spacing, double error) {
        for (int z = 0; z <= cells; z++) for (int x = 0; x <= cells; x++) {
            double px = x * spacing, pz = z * spacing; boolean found = false;
            for (Quad q : quads) if (px >= q.x0 && px <= q.x1 && pz >= q.z0 && pz <= q.z1) {
                require(Math.abs(q.height(px, pz) - heights[z * (cells + 1) + x]) <= error + 1e-6,
                        "triangle error exceeded at " + x + "," + z);
                found = true;
            }
            require(found, "sample left uncovered");
        }
    }

    private static void verifyClassification(List<Quad> quads, int[] heights, int cells, int spacing) {
        for (int z = 0; z < cells * spacing; z++) for (int x = 0; x < cells * spacing; x++) {
            int sx = (x + spacing / 2) / spacing, sz = (z + spacing / 2) / spacing;
            boolean wet = heights[sz * (cells + 1) + sx] < 63; int matches = 0;
            for (Quad q : quads) if (x >= q.x0 && x < q.x1 && z >= q.z0 && z < q.z1) {
                require((q.material == 1) == wet, "land/water footprint mismatch at " + x + "," + z);
                if (wet) require(q.a == 63 && q.b == 63 && q.c == 63 && q.d == 63, "sloping water");
                matches++;
            }
            require(matches == 1, "overlapping or missing surface footprint");
        }
    }

    public static void main(String[] args) {
        flatTerrain(); peaksAndValleys(); saddleAndRidge(); islandAndInlet(); narrowChannel();
        coverageTransitions(); retirementAndBudget(); coverageDiagnostic();
        System.out.println("8 architecture checks passed: adaptive error/extrema, sampled shoreline topology, coverage, admission.");
        int[] heights = field(128, 80); byte[] materials = new byte[heights.length]; int[] colors = new int[heights.length];
        AdaptiveSurfaceMesh.Sink discard = (x0,z0,x1,z1,a,b,c,d,m,rgb) -> {};
        int emitted = 0;
        for (int i = 0; i < 200; i++) emitted = new AdaptiveSurfaceMesh(128,16,63,heights,materials,colors,4,discard).build();
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) emitted = new AdaptiveSurfaceMesh(128,16,63,heights,materials,colors,4,discard).build();
        System.out.printf("Flat 2048b tile: %d vs 16384 quads; mesher mean %.3f ms (1000 runs; excludes worldgen/GPU).%n",
                emitted, (System.nanoTime() - start) / 1e9);
    }
}
