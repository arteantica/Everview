package dev.everview.terrain;

import net.minecraft.SharedConstants;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;

/** Minecraft's base-height algorithm over a volume, sharing the noise/aquifer context.
 * No chunk generation, surface decoration, structures or loaded-world access. */
public final class TerrainNoiseBatch {
    private TerrainNoiseBatch() {}
    public static int pack(int floor, int surface) { return (floor & 0xffff) | (surface << 16); }
    public static int floor(int packed) { return (short) packed; }
    public static int surface(int packed) { return packed >> 16; }
    public static boolean wet(int packed) { return surface(packed) > floor(packed); }

    public static int[] sample(NoiseBasedChunkGenerator generator, RandomState state,
                               LevelHeightAccessor accessor, int x, int z, int size) {
        var settings = generator.generatorSettings().value();
        var noise = settings.noiseSettings().clampToHeightAccessor(accessor);
        int[] result = new int[size * size];
        if (noise.height() <= 0) { java.util.Arrays.fill(result, pack(accessor.getMinY(), accessor.getMinY())); return result; }
        var volume = new DensityVolume(size, noise.height(), size, x, noise.minY(), z);
        // Same global-fluid rule as NoiseBasedChunkGenerator.createFluidPicker in 26.3.
        var lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        var water = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
        var air = new Aquifer.FluidStatus(DimensionType.MIN_Y * 2, Blocks.AIR.defaultBlockState());
        Aquifer.FluidPicker fluids = (bx, by, bz) -> SharedConstants.DEBUG_DISABLE_FLUID_GENERATION
                ? air : by < Math.min(-54, settings.seaLevel()) ? lava : water;
        try (var context = new NoiseChunk(state, null, settings, fluids, Blender.empty(), volume);
             var densities = context.cachingSamplers().get(settings.noiseRouter().finalDensity()).sampleVolume(volume)) {
            var solid = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();
            var visible = Heightmap.Types.WORLD_SURFACE_WG.isOpaque();
            for (int dz = 0; dz < size; dz++) for (int dx = 0; dx < size; dx++) {
                int floor = accessor.getMinY(), surface = floor;
                boolean foundSurface = false;
                for (int dy = noise.height() - 1; dy >= 0; dy--) {
                    int y = volume.blockY(dy);
                    var block = context.aquifer().computeSubstance(x + dx, y, z + dz,
                            densities.get(volume.indexUnchecked(dx, dy, dz)));
                    if (block == null) block = settings.defaultBlock();
                    if (!foundSurface && visible.test(block)) { surface = y + 1; foundSurface = true; }
                    if (solid.test(block)) { floor = y + 1; break; }
                }
                result[dz * size + dx] = pack(floor, surface);
            }
        }
        return result;
    }
}
