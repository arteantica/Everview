package dev.everview.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Current vanilla/Fabric opaque backend.
 *
 * It intentionally consumes only POSITION_COLOR today. The source tile still
 * retains material IDs, so an Iris/backend-specific uploader can later build a
 * richer vertex stream (UVs, normals, material metadata) from the same tile.
 */
public final class VanillaOpaqueTerrainBackend implements EverviewTerrainBackend {
    private static final Vector4f COLOR_MODULATOR =
            new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

    @Override
    public String id() {
        return "vanilla-opaque";
    }

    @Override
    public void prepareFrame(ClientLevel level, WorldgenSurfaceSnapshot snapshot) {
        EverviewGpuTileCache.prepareFrame(level, snapshot);
    }

    @Override
    public void beginOpaquePass(RenderPass renderPass) {
        renderPass.setPipeline(RenderSystem.getCompiledPipeline(EverviewGpuPipeline.TERRAIN));
        RenderSystem.bindDefaultUniforms(renderPass);
    }

    @Override
    public boolean drawTile(
            RenderPass renderPass,
            WorldgenSurfaceTile tile,
            Matrix4f modelView
    ) {
        EverviewGpuTileCache.GpuTile gpuTile = EverviewGpuTileCache.getResident(tile);
        if (gpuTile == null) {
            return false;
        }

        RenderSystem.AutoStorageIndexBuffer quadIndices =
                RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = quadIndices.getBuffer(gpuTile.indexCount());

        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(
                        modelView,
                        COLOR_MODULATOR,
                        MODEL_OFFSET,
                        TEXTURE_MATRIX
                );

        renderPass.setVertexBuffer(0, gpuTile.vertexBuffer().slice());
        renderPass.setIndexBuffer(indexBuffer, quadIndices.type());
        renderPass.setUniform("DynamicTransforms", dynamicTransforms);
        renderPass.drawIndexed(gpuTile.indexCount(), 1, 0, 0, 0);
        return true;
    }

    @Override
    public Stats stats() {
        EverviewGpuTileCache.Stats gpu = EverviewGpuTileCache.stats();
        return new Stats(
                gpu.bufferCount(),
                gpu.residentBytes(),
                gpu.uploadsThisFrame(),
                gpu.uploadMs()
        );
    }

    @Override
    public void clear() {
        EverviewGpuTileCache.clear();
    }
}
