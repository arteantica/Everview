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

    private static NoiseChunk context(NoiseBasedChunkGenerator generator, RandomState state, DensityVolume volume) {
        var settings = generator.generatorSettings().value();
        // Same global-fluid rule as NoiseBasedChunkGenerator.createFluidPicker in 26.3.
        var lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        var water = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
        var air = new Aquifer.FluidStatus(DimensionType.MIN_Y * 2, Blocks.AIR.defaultBlockState());
        Aquifer.FluidPicker fluids = (bx, by, bz) -> SharedConstants.DEBUG_DISABLE_FLUID_GENERATION
                ? air : by < Math.min(-54, settings.seaLevel()) ? lava : water;
        return new NoiseChunk(state, null, settings, fluids, Blender.empty(), volume);
    }

    public static int[] sample(NoiseBasedChunkGenerator generator, RandomState state,
                               LevelHeightAccessor accessor, int x, int z, int size) {
        if (size <= 0) throw new IllegalArgumentException("positive volume size required");
        var settings = generator.generatorSettings().value();
        var noise = settings.noiseSettings().clampToHeightAccessor(accessor);
        int[] result = new int[size * size];
        if (noise.height() <= 0) { java.util.Arrays.fill(result, pack(accessor.getMinY(), accessor.getMinY())); return result; }
        var volume = new DensityVolume(size, noise.height(), size, x, noise.minY(), z);
        try (var context = context(generator, state, volume);
             var densities = context.cachingSamplers().get(settings.noiseRouter().finalDensity()).sampleVolume(volume)) {
            for (int dz = 0; dz < size; dz++) for (int dx = 0; dx < size; dx++)
                result[dz * size + dx] = scanColumn(context, densities, volume, settings, accessor, dx, dz);
        }
        return result;
    }

    private static int scanColumn(NoiseChunk context,
                                  net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer densities,
                                  DensityVolume volume, NoiseGeneratorSettings settings, LevelHeightAccessor accessor,
                                  int dx, int dz) {
        int floor = accessor.getMinY(), surface = floor;
        boolean foundSurface = false;
        var solid = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();
        var visible = Heightmap.Types.WORLD_SURFACE_WG.isOpaque();
        for (int dy = volume.sizeY() - 1; dy >= 0; dy--) {
            int y = volume.blockY(dy);
            var block = context.aquifer().computeSubstance(volume.blockX(dx), y, volume.blockZ(dz),
                    densities.get(volume.indexUnchecked(dx, dy, dz)));
            if (block == null) block = settings.defaultBlock();
            if (!foundSurface && visible.test(block)) { surface = y + 1; foundSurface = true; }
            if (solid.test(block)) { floor = y + 1; break; }
        }
        return pack(floor, surface);
    }

    /** Each job/worker owns and closes these bounded contexts. Only requested
     * columns are evaluated. Wide strided density volumes interpolate the gaps
     * densely and are deliberately avoided. The world source owns all results. */
    public static TerrainSourceStore.Loader loader(NoiseBasedChunkGenerator generator, RandomState state,
                                                   LevelHeightAccessor accessor, int spacing) {
        if (spacing <= 0) throw new IllegalArgumentException("positive sampling spacing required");
        var settings = generator.generatorSettings().value();
        var noise = settings.noiseSettings().clampToHeightAccessor(accessor);
        return new TerrainSourceStore.Loader() {
            private final java.util.LinkedHashMap<Long, NoiseChunk> contexts = spacing > 4 && spacing <= 16
                    ? new java.util.LinkedHashMap<>(32, .75f, true) : null;
            private int[] measured(int x, int z, int size) {
                long began = System.nanoTime();
                try { return sample(generator, state, accessor, x, z, size); }
                finally {
                    GenerationProfile.noiseNanos.add(System.nanoTime() - began);
                    GenerationProfile.noiseColumns.add((long) size * size);
                    GenerationProfile.noiseBatches.increment();
                }
            }
            public int[] batch(int x, int z) { return measured(x, z, 16); }
            public int column(int x, int z) {
                // At wide spacing, aquifer bounding-area setup costs more than
                // independent columns. Preserve the scalar path there.
                if (spacing <= 4 || spacing > 16 || noise.height() <= 0) return measured(x, z, 1)[0];
                long began = System.nanoTime();
                try {
                    int ox = Math.floorDiv(x, 64) * 64, oz = Math.floorDiv(z, 64) * 64;
                    long key = ((long) ox << 32) | (oz & 0xffffffffL);
                    var context = contexts.get(key);
                    if (context == null) {
                        if (contexts.size() == 32) contexts.remove(contexts.keySet().iterator().next()).close();
                        context = context(generator, state, new DensityVolume(64, noise.height(), 64, ox, noise.minY(), oz));
                        contexts.put(key, context);
                        GenerationProfile.noiseBatches.increment();
                    }
                    var volume = new DensityVolume(1, noise.height(), 1, x, noise.minY(), z);
                    try (var densities = context.cachingSamplers().get(settings.noiseRouter().finalDensity()).sampleVolume(volume)) {
                        return scanColumn(context, densities, volume, settings, accessor, 0, 0);
                    }
                } finally {
                    GenerationProfile.noiseNanos.add(System.nanoTime() - began);
                    GenerationProfile.noiseColumns.increment();
                    GenerationProfile.sharedContextColumns.increment();
                }
            }
            public void close() {
                if (contexts != null) { for (var context : contexts.values()) context.close(); contexts.clear(); }
            }
        };
    }
}
