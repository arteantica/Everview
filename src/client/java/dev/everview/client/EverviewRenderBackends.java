package dev.everview.client;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Central backend selection point.
 *
 * M3.5 does not claim shader-pack support yet. It creates the architectural
 * seam needed for it: Iris can later supply a dedicated backend while the
 * vanilla opaque path remains a safe fallback and the LOD generator stays
 * unchanged.
 */
public final class EverviewRenderBackends {
    private static final EverviewTerrainBackend VANILLA =
            new VanillaOpaqueTerrainBackend();

    private static EverviewTerrainBackend active = VANILLA;
    private static boolean irisDetected;

    private EverviewRenderBackends() {
    }

    public static void initialize() {
        irisDetected = FabricLoader.getInstance().isModLoaded("iris");
        active = VANILLA;

        if (irisDetected) {
            EverviewClient.LOGGER.info(
                    "Iris detected. Everview is using the vanilla opaque fallback; "
                            + "the M3.5 backend boundary reserves a dedicated shader path."
            );
        }
    }

    public static EverviewTerrainBackend active() {
        return active;
    }

    public static EverviewTerrainBackend.Stats stats() {
        return active.stats();
    }

    public static String backendStatus() {
        if (irisDetected) {
            return active.id() + " | Iris detected / shader bridge reserved";
        }

        return active.id() + " | shader bridge reserved";
    }
}
