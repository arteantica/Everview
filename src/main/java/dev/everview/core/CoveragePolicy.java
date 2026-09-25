package dev.everview.core;

import java.util.function.Predicate;

/** Shared by GPU admission/retirement and deterministic transition tests. */
public final class CoveragePolicy {
    private CoveragePolicy() {}

    public static boolean mayRetire(int level, int minX, int minZ, boolean outsideRetention,
                                    Predicate<LodTileKey> uploadedAndValid) {
        if (outsideRetention) return true;
        for (int parent = level + 1; parent <= 6; parent++) {
            int size = parent <= 2 ? 128 : 128 << (parent - 2);
            if (uploadedAndValid.test(new LodTileKey(parent, Math.floorDiv(minX, size), Math.floorDiv(minZ, size)))) return true;
        }
        return false;
    }

    public static boolean mayUpload(long allLiveBytes, long newBytes, long hardLimit) {
        // allLiveBytes includes retired buffers until their last command state is replaced.
        return newBytes >= 0 && allLiveBytes >= 0 && allLiveBytes <= hardLimit - newBytes;
    }
}
