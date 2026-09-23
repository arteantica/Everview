package dev.everview.mixin.client;

import dev.everview.client.EverviewRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 26.3 moved the section-mesh update callback out of LevelRenderer.
 * RenderSection.updateUploadTime() runs at the GPU upload handoff, which is the
 * earliest stable point for Everview to begin yielding top faces to vanilla.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionMixin {
    @Shadow
    public abstract BlockPos getRenderOrigin();

    @Inject(
            method = "updateUploadTime",
            at = @At("TAIL")
    )
    private void everview$noteSectionUpload(CallbackInfo ci) {
        EverviewRenderer.noteRecentlyCompiledSection(
                this.getRenderOrigin()
        );
    }

}
