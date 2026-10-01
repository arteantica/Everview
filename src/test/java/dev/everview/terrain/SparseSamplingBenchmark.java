package dev.everview.terrain;

import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.levelgen.*;
import java.util.Arrays;

/** Previous production size-one noise path versus the actual shared-context
 * loader, including source locks, profiling, context closure and negative tile
 * boundaries. Alternate order; report four measured trials after four warmups. */
public final class SparseSamplingBenchmark {
    private record Trial(long nanos,long contexts,long columns,int[] heights) {}
    private static Trial trial(TerrainSourceStore.Loader loader,int ox,int oz,int step) {
        long contexts=GenerationProfile.noiseBatches.sum(),columns=GenerationProfile.noiseColumns.sum();
        int[] heights=new int[17*17];long began=System.nanoTime();
        try(loader;var source=new TerrainSourceStore(null,1024)) {
            for(int z=0;z<17;z++)for(int x=0;x<17;x++)heights[z*17+x]=source.sample(ox+x*step,oz+z*step,false,loader);
            loader.close();long elapsed=System.nanoTime()-began;
            long after=GenerationProfile.noiseColumns.sum();
            for(int z=0;z<17;z++)for(int x=0;x<17;x++)source.sample(ox+x*step,oz+z*step,false,loader);
            if(GenerationProfile.noiseColumns.sum()!=after)throw new AssertionError("resident revisit regenerated noise");
            return new Trial(elapsed,GenerationProfile.noiseBatches.sum()-contexts,after-columns,heights);
        }
    }
    public static void main(String[] args) {
        MinecraftTerrainFixture.bootstrap();
        var registries=VanillaRegistries.createWorldLookup();
        var settings=registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var generator=new NoiseBasedChunkGenerator(new FixedBiomeSource(
                registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS)),settings);
        var state=RandomState.create(registries.lookupOrThrow(Registries.NOISE),123456789L,settings.value());
        var height=LevelHeightAccessor.create(-64,384);
        for(int step:new int[]{8,16,32,64}) {
            long scalar=0,shared=0,contexts=0,generated=0;int count=0;
            for(int run=0;run<8;run++) {
                int ox=(run-4)*step*16,oz=(run-3)*step*16;
                var previous=new TerrainSourceStore.Loader(){
                    public int[] batch(int x,int z){return TerrainNoiseBatch.sample(generator,state,height,x,z,16);}
                    public int column(int x,int z){
                        long began=System.nanoTime();
                        try{return TerrainNoiseBatch.sample(generator,state,height,x,z,1)[0];}
                        finally{GenerationProfile.noiseNanos.add(System.nanoTime()-began);GenerationProfile.noiseColumns.increment();GenerationProfile.noiseBatches.increment();}
                    }
                };
                var next=TerrainNoiseBatch.loader(generator,state,height,step);
                Trial old,newer;
                if((run&1)==0){old=trial(previous,ox,oz,step);newer=trial(next,ox,oz,step);}
                else{newer=trial(next,ox,oz,step);old=trial(previous,ox,oz,step);}
                if(!Arrays.equals(old.heights,newer.heights))throw new AssertionError("sparse source mismatch at "+ox+","+oz);
                if(newer.columns!=289)throw new AssertionError("sparse production overfetch");
                if(run>=4){scalar+=old.nanos;shared+=newer.nanos;contexts+=newer.contexts;generated+=newer.columns;count+=289;}
            }
            System.out.printf("Sparse %db: %,d exact verified requests; M9.7.2 source %.1f ms vs shared-context source %.1f ms (%.2fx); %,d contexts / %,d generated columns (no overfetch); revisit zero noise.%n",
                    step,count,scalar/1e6,shared/1e6,(double)scalar/shared,contexts,generated);
        }
    }
}
