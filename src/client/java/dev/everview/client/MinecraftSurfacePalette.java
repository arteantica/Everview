package dev.everview.client;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * First visual-fidelity palette for distant Everview terrain.
 *
 * This intentionally stays cheap: biome-derived grass tint plus a small set of
 * Minecraft-like surface classes inferred from biome identity, elevation, and
 * slope. Exact surface-rule/block reconstruction is a later milestone.
 */
public final class MinecraftSurfacePalette {
    public static final byte MATERIAL_GRASS = 0;
    public static final byte MATERIAL_WATER = 1;
    public static final byte MATERIAL_SAND = 2;
    public static final byte MATERIAL_STONE = 3;
    public static final byte MATERIAL_SNOW = 4;
    public static final byte MATERIAL_TERRACOTTA = 5;
    public static final byte MATERIAL_DIRT = 6;
    public static final byte MATERIAL_GRAVEL = 7;
    public static final byte MATERIAL_PODZOL = 8;
    public static final byte MATERIAL_MUD = 9;
    public static final byte MATERIAL_ICE = 10;
    public static final int MATERIAL_COUNT = 11;

    private static final int WATER = 0x3B6E98;
    private static final int SWAMP_WATER = 0x4D6256;
    private static final int FROZEN_WATER = 0x7897B4;
    private static final int SAND = 0xCFC28A;
    private static final int STONE = 0x777A7A;
    private static final int SNOW = 0xE7EBEC;
    private static final int TERRACOTTA = 0xA85F43;
    private static final int DIRT = 0x866043;
    private static final int GRAVEL = 0x837E76;
    private static final int PODZOL = 0x6B4A2D;
    private static final int MUD = 0x443B36;
    private static final int ICE = 0xA7D7F2;
    private static final int FALLBACK_GRASS = 0x6F9D50;

    // Biome identity/classification is invariant for the lifetime of a holder.
    // The old path re-ran unwrapKey/lowercase/string contains checks for every
    // worldgen sample, which became measurable during high-speed coverage.
    private static final Map<Holder<Biome>, BiomeProfile> BIOME_PROFILES =
            new ConcurrentHashMap<>();

    private MinecraftSurfacePalette() {
    }

    public static SampleAppearance sample(
            Holder<Biome> biomeHolder,
            int worldX,
            int worldY,
            int worldZ,
            int seaLevel
    ) {
        BiomeProfile profile = BIOME_PROFILES.computeIfAbsent(
                biomeHolder,
                MinecraftSurfacePalette::classifyBiome
        );

        if (worldY < seaLevel
                || (worldY <= seaLevel + 1 && profile.waterBiome())) {
            if (profile.frozen()
                    && worldY >= seaLevel - 1
                    && profile.waterBiome()) {
                return new SampleAppearance(ICE, MATERIAL_ICE);
            }
            if (profile.swamp()) {
                return new SampleAppearance(SWAMP_WATER, MATERIAL_WATER);
            }
            if (profile.frozen()) {
                return new SampleAppearance(FROZEN_WATER, MATERIAL_WATER);
            }
            return new SampleAppearance(WATER, MATERIAL_WATER);
        }

        if (profile.badlands()) {
            return new SampleAppearance(
                    terracottaColor(worldX, worldY, worldZ),
                    MATERIAL_TERRACOTTA
            );
        }

        if (profile.sandy()) {
            return new SampleAppearance(SAND, MATERIAL_SAND);
        }

        if (profile.mangrove() && worldY <= seaLevel + 5) {
            return new SampleAppearance(MUD, MATERIAL_MUD);
        }

        if (profile.gravelly()) {
            return new SampleAppearance(GRAVEL, MATERIAL_GRAVEL);
        }

        if (profile.podzol()) {
            return new SampleAppearance(PODZOL, MATERIAL_PODZOL);
        }

        // Keep snow tied to cold / peak biomes instead of globally whitening
        // every tall mountain.
        if ((profile.frozen() && worldY >= seaLevel + 18)
                || (profile.highSnowPeak() && worldY >= seaLevel + 78)) {
            return new SampleAppearance(SNOW, MATERIAL_SNOW);
        }

        if (profile.stony() && worldY >= seaLevel + 35) {
            return new SampleAppearance(STONE, MATERIAL_STONE);
        }

        int grass;
        try {
            grass = biomeHolder.value().getGrassColor(worldX, worldZ) & 0xFFFFFF;
        } catch (RuntimeException exception) {
            grass = FALLBACK_GRASS;
        }

        // Vanilla grass tints can look overly saturated on an untextured LOD
        // sheet. Pull them slightly toward a muted natural green while keeping
        // biome differences visible.
        grass = blend(grass, 0x6B854B, 0.20F);
        grass = applyLighting(grass, 0.94F);

        return new SampleAppearance(grass, MATERIAL_GRASS);
    }

    public static int stoneColor() {
        return STONE;
    }

    public static int dirtColor() {
        return DIRT;
    }

    public static int gravelColor() {
        return GRAVEL;
    }

    public static int podzolColor() {
        return PODZOL;
    }

    public static int mudColor() {
        return MUD;
    }

    public static int iceColor() {
        return ICE;
    }

    private static int terracottaColor(
            int worldX,
            int worldY,
            int worldZ
    ) {
        int offset = Math.floorMod(
                (worldX >> 5) * 3 + (worldZ >> 5) * 5,
                11
        );
        int band = Math.floorMod(worldY + offset, 18);

        if (band == 2 || band == 3) {
            return 0xC77857;
        }
        if (band == 8) {
            return 0xD1A36A;
        }
        if (band == 13 || band == 14) {
            return 0x8F4F3A;
        }
        return TERRACOTTA;
    }

    public static int applyLighting(int rgb, float shade) {
        shade = Math.max(0.45F, Math.min(1.15F, shade));

        int red = Math.round(((rgb >> 16) & 0xFF) * shade);
        int green = Math.round(((rgb >> 8) & 0xFF) * shade);
        int blue = Math.round((rgb & 0xFF) * shade);

        return (clamp(red) << 16) | (clamp(green) << 8) | clamp(blue);
    }

    public static int blend(int from, int to, float amount) {
        amount = Math.max(0.0F, Math.min(1.0F, amount));
        float inverse = 1.0F - amount;

        int red = Math.round(((from >> 16) & 0xFF) * inverse + ((to >> 16) & 0xFF) * amount);
        int green = Math.round(((from >> 8) & 0xFF) * inverse + ((to >> 8) & 0xFF) * amount);
        int blue = Math.round((from & 0xFF) * inverse + (to & 0xFF) * amount);

        return (clamp(red) << 16) | (clamp(green) << 8) | clamp(blue);
    }

    private static BiomeProfile classifyBiome(
            Holder<Biome> biomeHolder
    ) {
        String path = biomeHolder.unwrapKey()
                .map(key -> key.identifier().getPath().toLowerCase(Locale.ROOT))
                .orElse("");

        boolean frozen = containsAny(
                path,
                "frozen",
                "snowy",
                "ice_spikes",
                "grove"
        );
        boolean stony = containsAny(
                path,
                "stony",
                "windswept_gravelly",
                "jagged_peaks",
                "frozen_peaks"
        );

        return new BiomeProfile(
                frozen,
                containsAny(path, "frozen_peaks", "jagged_peaks"),
                containsAny(path, "ocean", "river"),
                path.contains("swamp"),
                path.contains("desert")
                        || (path.contains("beach")
                                && !path.contains("snowy")
                                && !path.contains("stony")),
                path.contains("badlands"),
                stony,
                containsAny(
                        path,
                        "stony_shore",
                        "windswept_gravelly",
                        "gravelly"
                ),
                containsAny(
                        path,
                        "old_growth_pine_taiga",
                        "old_growth_spruce_taiga"
                ),
                path.contains("mangrove")
        );
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private record BiomeProfile(
            boolean frozen,
            boolean highSnowPeak,
            boolean waterBiome,
            boolean swamp,
            boolean sandy,
            boolean badlands,
            boolean stony,
            boolean gravelly,
            boolean podzol,
            boolean mangrove
    ) {
    }

    public record SampleAppearance(int rgb, byte material) {
    }
}
