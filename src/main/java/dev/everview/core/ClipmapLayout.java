package dev.everview.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Generates exponentially coarser LOD rings. The key design target is that
 * increasing distance should add rings rather than multiplying fine geometry.
 */
public final class ClipmapLayout {
    private final int maxDistanceBlocks;
    private final int firstLodRadius;
    private final int tileSamples;
    private final List<LodRing> rings;

    public ClipmapLayout(int maxDistanceBlocks, int firstLodRadius, int tileSamples) {
        if (maxDistanceBlocks < firstLodRadius) {
            throw new IllegalArgumentException("maxDistanceBlocks must be >= firstLodRadius");
        }
        if (firstLodRadius < 32 || tileSamples < 8) {
            throw new IllegalArgumentException("invalid clipmap parameters");
        }
        this.maxDistanceBlocks = maxDistanceBlocks;
        this.firstLodRadius = firstLodRadius;
        this.tileSamples = tileSamples;
        this.rings = Collections.unmodifiableList(build());
    }

    private List<LodRing> build() {
        List<LodRing> out = new ArrayList<>();
        int level = 0;
        int inner = firstLodRadius;
        int outer = firstLodRadius * 2;
        int spacing = 1;

        while (inner < maxDistanceBlocks) {
            outer = Math.min(outer, maxDistanceBlocks);
            int tileSize = Math.multiplyExact(tileSamples, spacing);
            out.add(new LodRing(level, inner, outer, spacing, tileSize));

            if (outer >= maxDistanceBlocks) {
                break;
            }

            level++;
            inner = outer;
            outer = Math.min(maxDistanceBlocks, outer * 2);
            spacing = Math.min(1 << Math.min(level, 20), 1 << 20);
        }

        return out;
    }

    public int maxDistanceBlocks() {
        return maxDistanceBlocks;
    }

    public List<LodRing> rings() {
        return rings;
    }
}
