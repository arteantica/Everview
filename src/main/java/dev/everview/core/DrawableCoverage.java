package dev.everview.core;

import java.util.HashMap;
import java.util.Map;
import java.util.BitSet;

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
    public int levelAt(int cellX, int cellZ) { return finest.getOrDefault(key(cellX, cellZ), 0); }

    public BitSet finerMask(int level, int minX, int minZ, int size) {
        BitSet mask = new BitSet();
        int index = 0;
        for (int z = Math.floorDiv(minZ, CELL_SIZE); z <= Math.floorDiv(minZ + size - 1, CELL_SIZE); z++) {
            for (int x = Math.floorDiv(minX, CELL_SIZE); x <= Math.floorDiv(minX + size - 1, CELL_SIZE); x++) {
                if (finerOwns(level, x, z)) mask.set(index);
                index++;
            }
        }
        return mask;
    }

    public int missingInDisk(double centerX, double centerZ, double radius) {
        int missing = 0;
        int minX = (int) Math.floor((centerX - radius) / CELL_SIZE);
        int maxX = (int) Math.floor((centerX + radius) / CELL_SIZE);
        int minZ = (int) Math.floor((centerZ - radius) / CELL_SIZE);
        int maxZ = (int) Math.floor((centerZ + radius) / CELL_SIZE);
        for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
            double dx = Math.max(x * (double) CELL_SIZE - centerX, Math.max(0, centerX - (x + 1.0) * CELL_SIZE));
            double dz = Math.max(z * (double) CELL_SIZE - centerZ, Math.max(0, centerZ - (z + 1.0) * CELL_SIZE));
            if (dx * dx + dz * dz < radius * radius && !finest.containsKey(key(x, z))) missing++;
        }
        return missing;
    }

    public static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffff_ffffL);
    }
}
