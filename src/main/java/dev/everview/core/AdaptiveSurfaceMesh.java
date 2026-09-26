package dev.everview.core;

/**
 * Error-bounded simplification of the sampled terrain, with independent wet/dry topology.
 * No extra worldgen samples are required. Error is relative to the input lattice, not
 * an assertion about unsampled sub-grid terrain. Flat areas collapse; extrema survive.
 */
public final class AdaptiveSurfaceMesh {
    public interface Sink {
        void quad(int x0, int z0, int x1, int z1, int y00, int y01, int y11, int y10, byte material, int color);
    }

    private final int cells, stride, spacing, sea, maxSpan;
    private final int[] heights, colors;
    private final byte[] materials;
    private final boolean[] wet;
    private final int[] waterHeights;
    private final double error;
    private final Sink sink;
    private int emitted;

    public AdaptiveSurfaceMesh(int cells, int spacing, int sea, int[] heights, byte[] materials,
                               int[] colors, double error, Sink sink) {
        this(cells, spacing, sea, heights, materials, colors, null, error, sink);
    }

    public AdaptiveSurfaceMesh(int cells, int spacing, int sea, int[] heights, byte[] materials,
                               int[] colors, boolean[] wet, double error, Sink sink) {
        this(cells,spacing,sea,heights,materials,colors,wet,null,error,sink);
    }
    public AdaptiveSurfaceMesh(int cells,int spacing,int sea,int[] heights,byte[] materials,
                               int[] colors,boolean[] wet,int[] waterHeights,double error,Sink sink) {
        if (cells < 1 || Integer.bitCount(cells) != 1 || spacing < 1
                || heights.length != (cells + 1) * (cells + 1)
                || materials.length != heights.length || colors.length != heights.length
                || (wet != null && wet.length != heights.length)
                || (waterHeights != null && waterHeights.length != heights.length)) {
            throw new IllegalArgumentException("power-of-two cell grid and matching samples required");
        }
        this.cells = cells; this.stride = cells + 1; this.spacing = spacing; this.sea = sea;
        this.heights = heights; this.materials = materials; this.colors = colors; this.wet = wet; this.waterHeights=waterHeights;
        this.error = error; this.sink = sink; this.maxSpan = Math.max(1, DrawableCoverage.CELL_SIZE / spacing);
    }

    public int build() { visit(0, 0, cells); return emitted; }

    private void visit(int x, int z, int span) {
        if (span == 1) { cell(x, z); return; }
        if (span <= maxSpan && mergeable(x, z, span)) { emit(x, z, span); return; }
        int half = span / 2;
        visit(x, z, half); visit(x + half, z, half);
        visit(x, z + half, half); visit(x + half, z + half, half);
    }

    private boolean mergeable(int x, int z, int span) {
        int a = at(x, z), b = at(x, z + span), c = at(x + span, z + span), d = at(x + span, z);
        byte material = materials[a];
        boolean wet = wet(a);
        int ha = height(a), hb = height(b), hc = height(c), hd = height(d);
        for (int dz = 0; dz <= span; dz++) for (int dx = 0; dx <= span; dx++) {
            int i = at(x + dx, z + dz);
            if (wet(i) != wet || materials[i] != material) return false;
            double u = dx / (double) span, v = dz / (double) span;
            // Match the GPU's two triangles, not a bilinear saddle approximation.
            double predicted = v >= u ? ha + (hb - ha) * v + (hc - hb) * u
                    : ha + (hd - ha) * u + (hc - hd) * v;
            boolean boundary = dx == 0 || dz == 0 || dx == span || dz == span;
            // Exact shared edges prevent T-junction cracks between unequal leaves.
            if (Math.abs(height(i) - predicted) > (boundary ? 0.001 : error)) return false;
        }
        return true;
    }

    private void emit(int x, int z, int span) {
        int a = at(x, z), b = at(x, z + span), c = at(x + span, z + span), d = at(x + span, z);
        sink.quad(x * spacing, z * spacing, (x + span) * spacing, (z + span) * spacing,
                height(a), height(b), height(c), height(d), materials[a], colors[at(x + span / 2, z + span / 2)]);
        emitted++;
    }

    private void cell(int x, int z) {
        int a = at(x, z), b = at(x, z + 1), c = at(x + 1, z + 1), d = at(x + 1, z);
        if (spacing == 1 || (wet(a) == wet(b) && wet(a) == wet(c) && wet(a) == wet(d)
                && materials[a] == materials[b] && materials[a] == materials[c] && materials[a] == materials[d])) {
            emit(x, z, 1); return;
        }
        // Four sample-owned footprints, never a material majority that expands an island
        // or erases a river/islet. Wet pieces remain level; land ends at the shared boundary.
        int x0 = x * spacing, x1 = (x + 1) * spacing, xm = (x0 + x1) / 2;
        int z0 = z * spacing, z1 = (z + 1) * spacing, zm = (z0 + z1) / 2;
        int ab = edge(a, b), bc = edge(b, c), cd = edge(c, d), da = edge(d, a);
        int center = wet(a) || wet(b) || wet(c) || wet(d) ? height(wet(a)?a:wet(b)?b:wet(c)?c:d)
                : Math.round((height(a) + height(b) + height(c) + height(d)) * .25f);
        piece(a, x0, z0, xm, zm, height(a), ab, center, da);
        piece(b, x0, zm, xm, z1, ab, height(b), bc, center);
        piece(c, xm, zm, x1, z1, center, bc, height(c), cd);
        piece(d, xm, z0, x1, zm, da, center, cd, height(d));
    }

    private void piece(int sample, int x0, int z0, int x1, int z1, int a, int b, int c, int d) {
        if (wet(sample)) a = b = c = d = height(sample);
        sink.quad(x0, z0, x1, z1, a, b, c, d, materials[sample], colors[sample]);
        emitted++;
    }

    private int edge(int a, int b) {
        return wet(a) != wet(b) ? height(wet(a)?a:b) : Math.round((height(a) + height(b)) * .5f);
    }
    private boolean wet(int i) { return wet == null ? heights[i] < sea : wet[i]; }
    private int height(int i) { return wet(i) ? (waterHeights==null?sea:waterHeights[i]) : heights[i]; }
    private int at(int x, int z) { return z * stride + x; }
}
