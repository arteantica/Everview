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
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            LoadedSurfaceSampler.tick(client);
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
