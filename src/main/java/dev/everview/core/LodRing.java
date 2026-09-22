package dev.everview.core;

/**
 * One camera-centered clipmap ring. Distances and spacing are in blocks.
 */
public record LodRing(
        int level,
        int innerRadius,
        int outerRadius,
        int sampleSpacing,
        int nominalTileSize
) {
    public long approximateSamplesAcrossDiameter() {
        long diameter = (long) outerRadius * 2L;
        return Math.max(1L, diameter / sampleSpacing);
    }
}
