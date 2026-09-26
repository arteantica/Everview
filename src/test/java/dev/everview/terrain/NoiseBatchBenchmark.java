package dev.everview.terrain;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.levelgen.*;

/** Runs actual Minecraft noise; no GPU or running game required. */
public final class NoiseBatchBenchmark {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var registries = VanillaRegistries.createWorldLookup();
        var settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var biomes = new FixedBiomeSource(registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS));
        var generator = new NoiseBasedChunkGenerator(biomes, settings);
        var state = RandomState.create(registries.lookupOrThrow(Registries.NOISE), 123456789L, settings.value());
        var accessor = LevelHeightAccessor.create(-64,384);
        long oldNanos=0, newNanos=0, m96Nanos=0; int comparisons=0;
        for (int run=0;run<12;run++) {
            int x = (run-4)*113, z = run*197-777;
            long start=System.nanoTime();
            int[] actual=TerrainNoiseBatch.sample(generator,state,accessor,x,z,16);
            long batch=System.nanoTime()-start;
            start=System.nanoTime();
            for(int dz=0;dz<16;dz++) for(int dx=0;dx<16;dx++) {
                int floor=generator.getBaseHeight(x+dx,z+dz,Heightmap.Types.OCEAN_FLOOR_WG,accessor,state);
                int top=generator.getBaseHeight(x+dx,z+dz,Heightmap.Types.WORLD_SURFACE_WG,accessor,state);
                if(actual[dz*16+dx]!=TerrainNoiseBatch.pack(floor,top))
                    throw new AssertionError("Batch differs at "+(x+dx)+","+(z+dz)+": "+actual[dz*16+dx]+" vs "+TerrainNoiseBatch.pack(floor,top));
                comparisons++;
            }
            long baseline=System.nanoTime()-start;
            start=System.nanoTime();
            for(int dz=0;dz<16;dz++)for(int dx=0;dx<16;dx++){
                int floor=generator.getBaseHeight(x+dx,z+dz,Heightmap.Types.OCEAN_FLOOR_WG,accessor,state);
                if(floor>=generator.getSeaLevel()-2&&floor<=generator.getSeaLevel()+2)
                    generator.getBaseHeight(x+dx,z+dz,Heightmap.Types.WORLD_SURFACE_WG,accessor,state);
            }
            if(run>=4) {oldNanos+=baseline;newNanos+=batch;m96Nanos+=System.nanoTime()-start;}
        }
        System.out.printf("Minecraft 26.3 exact floor+surface: %,d verified columns. Warm 2048 columns: scalar %.1f ms, batch %.1f ms, %.2fx speedup; scalar %.0f/s, batch %.0f/s.%n",
                comparisons,oldNanos/1e6,newNanos/1e6,(double)oldNanos/newNanos,2048e9/oldNanos,2048e9/newNanos);
        System.out.printf("M9.6 selective water queries: %.1f ms, %.0f columns/s; M9.7 exact dual heights %.1f ms, %.0f columns/s; %.2fx sampling speedup.%n",
                m96Nanos/1e6,2048e9/m96Nanos,newNanos/1e6,2048e9/newNanos,(double)m96Nanos/newNanos);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var loader=new TerrainSourceStore.Loader(){
            public int[] batch(int x,int z){calls.incrementAndGet();return TerrainNoiseBatch.sample(generator,state,accessor,x,z,16);}
            public int column(int x,int z){return TerrainNoiseBatch.sample(generator,state,accessor,x,z,1)[0];}
        };
        try(var source=new TerrainSourceStore(null,256)){
            long began=System.nanoTime();
            for(int step:new int[]{4,2,1})for(int z=0;z<=128;z+=step)for(int x=0;x<=128;x+=step)source.sample(x,z,true,loader);
            double ms=(System.nanoTime()-began)/1e6;int cold=calls.get();
            began=System.nanoTime();int n=129*129;int[] h=new int[n],colors=new int[n];byte[] materials=new byte[n];boolean[] wet=new boolean[n];
            for(int z=0;z<=128;z++)for(int x=0;x<=128;x++){int i=z*129+x,p=source.sample(x,z,true,loader);h[i]=TerrainNoiseBatch.surface(p);wet[i]=TerrainNoiseBatch.wet(p);materials[i]=(byte)(wet[i]?1:0);colors[i]=wet[i]?0x336699:0x668844;}
            double reuseMs=(System.nanoTime()-began)/1e6;began=System.nanoTime();
            int quads=new BlockSurfaceMesh(128,1,h,colors,materials,wet,1,(a,b,c,d,e,f,g,j,k,l,m,o,color,material)->{}).build();
            double meshMs=(System.nanoTime()-began)/1e6;
            if(calls.get()!=cold)throw new AssertionError("repeated LOD remeshing generated new noise");
            System.out.printf("128b source progressive 4/2/1b: %.1f ms, %d batches (%d generated columns), %.1f KiB RAM. Reuse: %.2f ms, zero new worldgen. Block mesh: %d quads vs 16384 dense tops, %.2f ms. Includes walls.%n",
                    ms,cold,cold*256,source.residentBytes()/1024.0,reuseMs,quads,meshMs);
        }
    }
}
