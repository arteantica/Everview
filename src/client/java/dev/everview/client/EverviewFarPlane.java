package dev.everview.client;

/**
 * Shared state for Everview's experimental camera-depth extension.
 *
 * M2.5 extends both Minecraft's culling projection and world projection by
 * raising Camera.depthFar before Camera builds either matrix.
 */
public final class EverviewFarPlane {
    public static final float EXTRA_HEADROOM_BLOCKS = 4_096.0F;

    private static volatile float vanillaDepthFar;
    private static volatile float extendedDepthFar;

    private EverviewFarPlane() {
    }

    public static float requestedDepthFar() {
        return WorldgenSurfaceSampler.MAX_OUTER_RADIUS + EXTRA_HEADROOM_BLOCKS;
    }

    public static float extend(float vanilla) {
        float extended = Math.max(vanilla, requestedDepthFar());
        vanillaDepthFar = vanilla;
        extendedDepthFar = extended;
        return extended;
    }

    public static float vanillaDepthFar() {
        return vanillaDepthFar;
    }

    public static float extendedDepthFar() {
        return extendedDepthFar;
    }
}
