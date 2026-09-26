package dev.everview.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;

/**
 * Opaque position/color pipeline for persistent Everview terrain.
 *
 * The old alpha path used Minecraft's DEBUG_QUADS pipeline. In 26.3 that
 * pipeline is explicitly translucent and does not write depth, which is useful
 * for debug overlays but wrong for terrain. Everview terrain now writes depth
 * like ordinary opaque world geometry.
 */
public final class EverviewGpuPipeline {
    public static final RenderPipeline TERRAIN = RenderPipeline.builder()
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withLocation(Identifier.fromNamespaceAndPath(
                    EverviewClient.MOD_ID,
                    "pipeline/persistent_lod_terrain"
            ))
            .withBindGroupLayout(com.mojang.renderpearl.api.pipeline.BindGroupLayout.builder()
                    .withUniform("EverviewOwnership", com.mojang.renderpearl.api.pipeline.UniformType.UNIFORM_BUFFER).build())
            .withVertexShader("everview:core/terrain")
            .withFragmentShader("everview:core/terrain")
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withCull(false)
            .build();

    private EverviewGpuPipeline() {
    }
}
