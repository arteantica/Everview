package dev.everview.client;

import dev.everview.core.ClipmapLayout;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EverviewClient implements ClientModInitializer {
    public static final String MOD_ID = "everview";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final int TARGET_DISTANCE_BLOCKS = 65_536;
    public static final ClipmapLayout CLIPMAP =
            new ClipmapLayout(TARGET_DISTANCE_BLOCKS, 256, 64);

    @Override
    public void onInitializeClient() {
        EverviewRenderer.register();
        EverviewDebugHud.register();

        // M2 focuses on the first true beyond-loaded-chunks worldgen ring.
        // The old M1 near debug heightfield remains in the codebase as a
        // diagnostic tool, but is deliberately disabled because it can cover
        // cave mouths/overhangs and is not the near-LOD architecture we want.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Sodium's chunk-fade option is bound to this vanilla option too.
            // Keep it at zero while Everview is active so vanilla terrain
            // appears immediately instead of alpha-fading over the LOD handoff.
            if (client.options.chunkSectionFadeInTime().get() != 0.0D) {
                client.options.chunkSectionFadeInTime().set(0.0D);
            }

            WorldgenSurfaceSampler.tick(client);
        });

        LOGGER.info(
                "Everview {} bootstrapped for Minecraft 26.3 Fabric: {} LOD rings, target {} blocks",
                "0.0.1-alpha",
                CLIPMAP.rings().size(),
                TARGET_DISTANCE_BLOCKS
        );
    }
}
