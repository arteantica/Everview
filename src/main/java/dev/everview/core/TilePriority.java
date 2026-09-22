package dev.everview.core;

/**
 * Cheap prioritizer for asynchronous generation. Lower scores are processed first.
 * It biases tiles in front of the camera and close to the player.
 */
public final class TilePriority {
    private TilePriority() {
    }

    public static double score(
            double tileCenterX,
            double tileCenterZ,
            double cameraX,
            double cameraZ,
            double lookX,
            double lookZ,
            int lodLevel
    ) {
        double dx = tileCenterX - cameraX;
        double dz = tileCenterZ - cameraZ;
        double distance = Math.sqrt(dx * dx + dz * dz);

        double len = Math.max(1.0e-6, distance);
        double nx = dx / len;
        double nz = dz / len;
        double forward = Math.max(-1.0, Math.min(1.0, nx * lookX + nz * lookZ));

        double behindPenalty = (1.0 - forward) * 0.35 * distance;
        double coarseBonus = lodLevel * 16.0;
        return distance + behindPenalty - coarseBonus;
    }
}
