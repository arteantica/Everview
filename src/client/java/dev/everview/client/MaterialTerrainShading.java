package dev.everview.client;

/**
 * Cheap world-space material breakup for the first M3.1 terrain pass.
 *
 * The current renderer is still an untextured custom-geometry path, so this
 * produces Minecraft-like material variation without requiring a texture atlas
 * yet. Detail strength falls quickly with LOD distance to avoid noisy horizons.
 */
public final class MaterialTerrainShading {
    private MaterialTerrainShading() {
    }

    public static int apply(
            int baseRgb,
            byte material,
            int worldX,
            int worldY,
            int worldZ,
            int sampleSpacing
    ) {
        float strength = detailStrength(sampleSpacing);
        if (strength <= 0.0F) {
            return baseRgb;
        }

        int detailScale;
        if (sampleSpacing <= 1) {
            detailScale = 1;
        } else if (sampleSpacing <= 2) {
            detailScale = 2;
        } else if (sampleSpacing <= 8) {
            detailScale = 4;
        } else if (sampleSpacing <= 32) {
            detailScale = 8;
        } else if (sampleSpacing <= 64) {
            detailScale = 16;
        } else if (sampleSpacing <= 128) {
            detailScale = 32;
        } else {
            detailScale = 64;
        }
        int cellX = Math.floorDiv(worldX, detailScale);
        int cellZ = Math.floorDiv(worldZ, detailScale);

        float coarse = signedNoise(cellX, cellZ, material * 31 + sampleSpacing * 17);
        float fine = signedNoise(cellX * 3 + worldY, cellZ * 3 - worldY, 97 + material * 13);

        float brightness;

        switch (material) {
            case MinecraftSurfacePalette.MATERIAL_WATER -> {
                // Water should stay broad and smooth rather than looking speckled.
                brightness = 1.0F + coarse * strength * 0.18F;
                int water = scale(baseRgb, brightness);
                return MinecraftSurfacePalette.blend(
                        water,
                        0x274E76,
                        Math.max(0.0F, -coarse) * strength * 0.10F
                );
            }
            case MinecraftSurfacePalette.MATERIAL_SAND -> {
                brightness = 1.0F
                        + (coarse * 0.60F + fine * 0.40F)
                        * strength * 0.20F;
                return scale(baseRgb, brightness);
            }
            case MinecraftSurfacePalette.MATERIAL_STONE -> {
                brightness = 1.0F
                        + (coarse * 0.60F + fine * 0.40F)
                        * strength * 0.34F;

                if ((Math.floorDiv(
                        worldY,
                        Math.max(4, detailScale / 2)
                ) & 1) == 0) {
                    brightness -= strength * 0.035F;
                }

                return scale(baseRgb, brightness);
            }
            case MinecraftSurfacePalette.MATERIAL_DIRT -> {
                brightness = 1.0F
                        + (coarse * 0.65F + fine * 0.35F)
                        * strength * 0.22F;
                return scale(baseRgb, brightness);
            }
            case MinecraftSurfacePalette.MATERIAL_GRAVEL -> {
                brightness = 1.0F
                        + (coarse * 0.45F + fine * 0.55F)
                        * strength * 0.30F;
                return scale(baseRgb, brightness);
            }
            case MinecraftSurfacePalette.MATERIAL_PODZOL -> {
                brightness = 1.0F
                        + (coarse * 0.70F + fine * 0.30F)
                        * strength * 0.18F;
                return scale(baseRgb, brightness);
            }
            case MinecraftSurfacePalette.MATERIAL_MUD -> {
                brightness = 0.98F
                        + coarse * strength * 0.10F;
                return scale(baseRgb, brightness);
            }
            case MinecraftSurfacePalette.MATERIAL_ICE -> {
                brightness = 1.02F
                        + fine * strength * 0.08F;
                return MinecraftSurfacePalette.blend(
                        scale(baseRgb, brightness),
                        0xD5EEFA,
                        0.10F
                );
            }
            case MinecraftSurfacePalette.MATERIAL_SNOW -> {
                brightness = 1.0F + coarse * strength * 0.10F;
                return scale(baseRgb, brightness);
            }
            case MinecraftSurfacePalette.MATERIAL_TERRACOTTA -> {
                brightness = 1.0F + coarse * strength * 0.18F;
                return scale(baseRgb, brightness);
            }
            default -> {
                // Grass keeps biome tint, but the old 0.55 amplitude created
                // giant green blobs at coarse spacing. M6 uses much tighter,
                // texel-like breakup baked per facet.
                brightness = 1.0F
                        + (coarse * 0.58F + fine * 0.42F)
                        * strength * 0.24F;
                return scale(baseRgb, brightness);
            }
        }
    }

    private static float detailStrength(int sampleSpacing) {
        if (sampleSpacing <= 1) {
            return 0.68F;
        }
        if (sampleSpacing <= 2) {
            return 0.84F;
        }
        if (sampleSpacing <= 8) {
            return 1.00F;
        }
        if (sampleSpacing <= 16) {
            return 0.82F;
        }
        if (sampleSpacing <= 32) {
            return 0.66F;
        }
        if (sampleSpacing <= 64) {
            return 0.48F;
        }
        if (sampleSpacing <= 128) {
            return 0.30F;
        }
        return 0.16F;
    }

    private static float signedNoise(int x, int z, int salt) {
        int hash = x * 0x1f1f1f1f ^ z * 0x5f356495 ^ salt * 0x27d4eb2d;
        hash ^= hash >>> 15;
        hash *= 0x85ebca6b;
        hash ^= hash >>> 13;

        int normalized = hash & 0xFFFF;
        return normalized / 32767.5F - 1.0F;
    }

    private static int scale(int rgb, float factor) {
        factor = Math.max(0.72F, Math.min(1.18F, factor));

        int red = clamp(Math.round(((rgb >> 16) & 0xFF) * factor));
        int green = clamp(Math.round(((rgb >> 8) & 0xFF) * factor));
        int blue = clamp(Math.round((rgb & 0xFF) * factor));

        return (red << 16) | (green << 8) | blue;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
