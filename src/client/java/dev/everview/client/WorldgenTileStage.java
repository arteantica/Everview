package dev.everview.client;

/**
 * Explicit lifecycle stage for a generated LOD tile.
 *
 * COVERAGE is a coarse/intermediate safety representation.
 * EXACT_GEOMETRY has 1-block terrain shape but still uses provisional borrowed
 * surface appearance and therefore must not be persisted as a finished tile.
 * EXACT_APPEARANCE is the fully refined 1-block L1 representation.
 */
public enum WorldgenTileStage {
    COVERAGE,
    EXACT_GEOMETRY,
    EXACT_APPEARANCE;

    public boolean exactGeometry() {
        return this == EXACT_GEOMETRY || this == EXACT_APPEARANCE;
    }

    public boolean exactAppearance() {
        return this == EXACT_APPEARANCE;
    }

    public boolean diskSafe() {
        return this != EXACT_GEOMETRY;
    }
}
