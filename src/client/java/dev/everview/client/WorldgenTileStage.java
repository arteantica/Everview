package dev.everview.client;

/**
 * Explicit lifecycle stage for a generated LOD tile.
 *
 * COVERAGE is a coarse/intermediate safety representation.
 * EXACT_GEOMETRY has 1-block terrain shape with provisional borrowed surface
 * appearance. M9 persists this stage as a warm-start render artifact: exact
 * geometry must survive a world rejoin even when appearance refinement has not
 * caught up yet.
 * EXACT_APPEARANCE is the fully refined 1-block L1 representation.
 */
public enum WorldgenTileStage {
    COVERAGE,
    EXACT_GEOMETRY,
    EXACT_APPEARANCE,
    ADAPTIVE_DETAIL;

    public boolean exactGeometry() {
        return this == EXACT_GEOMETRY || this == EXACT_APPEARANCE;
    }

    public boolean exactAppearance() {
        return this == EXACT_APPEARANCE;
    }

    public boolean diskSafe() {
        // M9: every published stage is safe to render after reload. Persisting
        // provisional exact geometry is preferable to regenerating thousands
        // of 1-block height samples after every world rejoin.
        return true;
    }
}
