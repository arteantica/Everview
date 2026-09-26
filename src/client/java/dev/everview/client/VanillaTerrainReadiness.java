package dev.everview.client;

import net.minecraft.core.BlockPos;

/** GPU availability, independent of generation, camera direction and occlusion. */
public interface VanillaTerrainReadiness {
    boolean everview$terrainUploaded(BlockPos position);
}
