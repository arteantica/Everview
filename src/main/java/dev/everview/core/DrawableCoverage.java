package dev.everview.core;

import java.util.HashMap;
import java.util.Map;

/** Spatial ownership is a property of uploaded geometry, never camera visibility. */
public final class DrawableCoverage {
    public static final int CELL_SIZE = 128;
    private final Map<Long, Integer> finest = new HashMap<>();

    public void add(int level, int minX, int minZ, int size) {
        for (int z = Math.floorDiv(minZ, CELL_SIZE);
                z <= Math.floorDiv(minZ + size - 1, CELL_SIZE); z++) {
            for (int x = Math.floorDiv(minX, CELL_SIZE);
                    x <= Math.floorDiv(minX + size - 1, CELL_SIZE); x++) {
                finest.merge(key(x, z), level, Math::min);
            }
        }
    }

    public boolean finerOwns(int level, int cellX, int cellZ) {
        return finest.getOrDefault(key(cellX, cellZ), Integer.MAX_VALUE) < level;
    }

    public boolean covers(int minX, int minZ, int size) {
        for (int z = Math.floorDiv(minZ, CELL_SIZE);
                z <= Math.floorDiv(minZ + size - 1, CELL_SIZE); z++) {
            for (int x = Math.floorDiv(minX, CELL_SIZE);
                    x <= Math.floorDiv(minX + size - 1, CELL_SIZE); x++) {
                if (!finest.containsKey(key(x, z))) return false;
            }
        }
        return true;
    }

    public int cells() { return finest.size(); }

    public static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffff_ffffL);
    }
}
