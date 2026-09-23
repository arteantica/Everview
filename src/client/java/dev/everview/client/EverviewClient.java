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
        // Select the rendering backend before any render hooks are registered.
        // M3.5 deliberately keeps terrain generation independent from the
        // concrete GPU/shader path so an Iris backend can be added later
        // without rewriting the LOD mesher or disk cache.
        EverviewRenderBackends.initialize();
        EverviewRenderer.register();
        EverviewDebugHud.register();

        // M2 focuses on the first true beyond-loaded-chunks worldgen ring.
        // The old M1 near debug heightfield remains in the codebase as a
        // diagnostic tool, but is deliberately disabled because it can cover
        // cave mouths/overhangs and is not the near-LOD architecture we want.
        ClientTickEvents.END_CLIENT_TICK.register(WorldgenSurfaceSampler::tick);

        LOGGER.info(
                "Everview {} bootstrapped for Minecraft 26.3 Fabric: {} LOD rings, target {} blocks",
                "0.0.1-alpha",
                CLIPMAP.rings().size(),
                TARGET_DISTANCE_BLOCKS
        );
    }
}
