package dev.everview.terrain;

import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.levelgen.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class SparseNoiseTest {
    private static NoiseBasedChunkGenerator generator;
    private static RandomState state;
    private static final LevelHeightAccessor HEIGHT = LevelHeightAccessor.create(-64, 384);

    @BeforeAll static void bootstrap() {
        MinecraftTerrainFixture.bootstrap();
        var registries = VanillaRegistries.createWorldLookup();
        var settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        generator = new NoiseBasedChunkGenerator(new FixedBiomeSource(
                registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS)), settings);
        state = RandomState.create(registries.lookupOrThrow(Registries.NOISE), 123456789L, settings.value());
    }

    private static int reference(int x, int z) {
        return TerrainNoiseBatch.pack(generator.getBaseHeight(x,z,Heightmap.Types.OCEAN_FLOOR_WG,HEIGHT,state),
                generator.getBaseHeight(x,z,Heightmap.Types.WORLD_SURFACE_WG,HEIGHT,state));
    }

    @Test void sharedContextFloorAndFluidEqualMinecraftAcrossPositiveAndNegativeCoordinates() {
        int wet=0, dry=0;
        for (int step : new int[]{8,16,32,64}) for (int[] origin : new int[][]{{-1024,-512},{128,256},{-16384,-16384},{512,-256}}) {
            try(var loader = TerrainNoiseBatch.loader(generator,state,HEIGHT,step)) {
            for (int z=0;z<8;z++) for (int x=0;x<8;x++) {
                int bx=origin[0]+x*step,bz=origin[1]+z*step,packed=loader.column(bx,bz);
                assertEquals(reference(bx,bz),packed,"stride "+step+" at "+bx+","+bz);
                if(TerrainNoiseBatch.wet(packed))wet++;else dry++;
            }
            }
        }
        assertTrue(wet>0 && dry>0,"reference corpus must exercise land and fluid columns: wet="+wet+", dry="+dry);
    }

    @Test void boundedContextsPreserveAlignmentAndCacheReuseAcrossPageAndTileEdges() {
        for (int step : new int[]{8,16,32,64}) {
            try(var loader=TerrainNoiseBatch.loader(generator,state,HEIGHT,step);
                var source=new TerrainSourceStore(null,1024)) {
                for(int z=-8;z<=8;z++)for(int x=-8;x<=8;x++)
                    assertEquals(reference(x*step,z*step),source.sample(x*step,z*step,false,loader));
                long queries=GenerationProfile.noiseColumns.sum();
                for(int z=-8;z<=8;z++)for(int x=-8;x<=8;x++)source.sample(x*step,z*step,false,loader);
                assertEquals(queries,GenerationProfile.noiseColumns.sum(),"resident source revisit must do no noise work");
                assertEquals(reference(-1,step+1),loader.column(-1,step+1),"unaligned request cannot read the wrong lattice point");
            }
        }
    }
    @Test void contextEvictionDoesNotLoseWorldSourceOrReuseClosedNoiseBuffers() throws Exception {
        try(var loader=TerrainNoiseBatch.loader(generator,state,HEIGHT,16);
            var source=new TerrainSourceStore(null,128)) {
            for(int i=0;i<40;i++) {
                int x=(i-20)*64;
                assertEquals(reference(x,-64),source.sample(x,-64,false,loader));
            }
            var field=loader.getClass().getDeclaredField("contexts");field.setAccessible(true);
            assertEquals(32,((java.util.Map<?,?>)field.get(loader)).size(),"worker noise contexts exceeded their bound");
            long queries=GenerationProfile.noiseColumns.sum();
            for(int i=0;i<40;i++)source.sample((i-20)*64,-64,false,loader);
            assertEquals(queries,GenerationProfile.noiseColumns.sum(),"context eviction cannot evict resident source data");
            assertEquals(reference(-1280,-64),loader.column(-1280,-64),"evicted context must reopen without stale pooled buffers");
        }
    }
}
