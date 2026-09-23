package dev.everview.client;

import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;

/**
 * Backend boundary between Everview's LOD/worldgen data and a concrete render
 * implementation.
 *
 * Keep this interface free of Iris classes. Shader integrations should live in
 * separate backend implementations so shader-pack API changes cannot infect
 * terrain generation, cache formats or LOD selection.
 */
public interface EverviewTerrainBackend {
    String id();

    void prepareFrame(ClientLevel level, WorldgenSurfaceSnapshot snapshot);

    void beginOpaquePass(RenderPass renderPass);

    /**
     * Draw one tile using a backend-specific GPU representation.
     *
     * @return true when a resident tile was actually submitted.
     */
    boolean drawTile(RenderPass renderPass, WorldgenSurfaceTile tile, Matrix4f modelView);

    Stats stats();

    void clear();

    record Stats(
            int bufferCount,
            long residentBytes,
            int uploadsThisFrame,
            double uploadMs
    ) {
        public double residentMiB() {
            return residentBytes / (1024.0 * 1024.0);
        }
    }
}
