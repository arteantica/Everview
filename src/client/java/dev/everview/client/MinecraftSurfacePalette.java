package dev.everview.client;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import java.util.Locale;

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

    private static final int WATER = 0x3F76E4;
    private static final int SWAMP_WATER = 0x4C6559;
    private static final int FROZEN_WATER = 0x7FA7D8;
    private static final int SAND = 0xD8CF9A;
    private static final int STONE = 0x878787;
    private static final int SNOW = 0xF4F7F7;
    private static final int TERRACOTTA = 0xB66A45;
    private static final int FALLBACK_GRASS = 0x79C05A;

    private MinecraftSurfacePalette() {
    }

    public static SampleAppearance sample(
            Holder<Biome> biomeHolder,
            int worldX,
            int worldY,
            int worldZ,
            int seaLevel
    ) {
        String path = biomeHolder.unwrapKey()
                .map(key -> key.identifier().getPath().toLowerCase(Locale.ROOT))
                .orElse("");

        boolean frozen = containsAny(path,
                "frozen", "snowy", "ice_spikes", "grove");
        boolean waterBiome = containsAny(path,
                "ocean", "river");
        boolean swamp = path.contains("swamp");
        boolean desert = containsAny(path,
                "desert", "beach");
        boolean badlands = path.contains("badlands");
        boolean stony = containsAny(path,
                "stony", "windswept_gravelly", "jagged_peaks", "frozen_peaks");

        if (worldY < seaLevel || (worldY <= seaLevel + 1 && waterBiome)) {
            if (frozen) {
                return new SampleAppearance(FROZEN_WATER, MATERIAL_WATER);
            }
            if (swamp) {
                return new SampleAppearance(SWAMP_WATER, MATERIAL_WATER);
            }
            return new SampleAppearance(WATER, MATERIAL_WATER);
        }

        if (badlands) {
            return new SampleAppearance(TERRACOTTA, MATERIAL_TERRACOTTA);
        }

        if (desert) {
            return new SampleAppearance(SAND, MATERIAL_SAND);
        }

        // High cold terrain gets a snow cap even before exact surface rules are
        // represented. The threshold is intentionally conservative.
        if (frozen && worldY >= seaLevel + 28 || worldY >= seaLevel + 150) {
            return new SampleAppearance(SNOW, MATERIAL_SNOW);
        }

        if (stony && worldY >= seaLevel + 35) {
            return new SampleAppearance(STONE, MATERIAL_STONE);
        }

        int grass;
        try {
            grass = biomeHolder.value().getGrassColor(worldX, worldZ) & 0xFFFFFF;
        } catch (RuntimeException exception) {
            grass = FALLBACK_GRASS;
        }

        return new SampleAppearance(grass, MATERIAL_GRASS);
    }

    public static int stoneColor() {
        return STONE;
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

    public record SampleAppearance(int rgb, byte material) {
    }
}
