package dev.everview.terrain;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual frozen M9.7.3 vs production source stores, real filesystem and identical
 * cheap source requests. The 2 ms case injects latency only at real file opens;
 * it models metadata latency, not Minecraft noise sampling or game FPS. */
public final class SourcePressureBenchmark {
    private static final int PAGES=4096,CACHE=512;
    private static final TerrainSourceStore.Loader LOADER=new TerrainSourceStore.Loader(){
        public int column(int x,int z){return TerrainNoiseBatch.pack(40+Math.floorMod(x+z,20),63);}
        public int[] batch(int x,int z){int[] a=new int[256];for(int i=0;i<256;i++)a[i]=column(x+i%16,z+i/16);return a;}
    };
    private record Result(double ms,long opens,long visits,long batches,long saved,long pressure,long peak) {}
    private interface Request {void sample(int x,int z);}
    private static void fill(Request request,int count)throws Exception {
        try(var pool=Executors.newFixedThreadPool(8)){
            var jobs=new ArrayList<Future<?>>();
            for(int lane=0;lane<8;lane++){int start=lane;jobs.add(pool.submit(()->{
                for(int n=start;n<count;n+=8)request.sample((n%64)*16-512,(n/64)*16-512);
            }));}
            for(var job:jobs)job.get(90,TimeUnit.SECONDS);
        }
    }
    private static Result run(Path directory,boolean legacy,long delay,int count)throws Exception {
        long start=System.nanoTime();Result result;
        if(legacy){
            var source=new LegacySourceBaseline(directory,CACHE);source.openDelayMillis=delay;
            var loader=new LegacySourceBaseline.Loader(){public int column(int x,int z){return LOADER.column(x,z);}public int[] batch(int x,int z){return LOADER.batch(x,z);}};
            try{
                fill((x,z)->{
                    if(source.sample(x,z,false,loader)!=LOADER.column(x,z))throw new AssertionError("legacy height");
                    source.appearance(x,z,()->new LegacySourceBaseline.Appearance(0x123456,(byte)3));
                    if(source.residentBytes()>CACHE*2560L)throw new AssertionError("legacy RAM bound");
                },count);
                source.flush(Integer.MAX_VALUE);
                if(source.pendingWrites()!=0||source.errors.sum()!=0)throw new AssertionError("legacy unfinished/error writes");
                result=new Result((System.nanoTime()-start)/1e6,source.fileOpens.sum(),source.evictionVisits.sum(),0,source.writtenPages.sum(),0,source.residentBytes());
            }finally{source.close();}
        }else{
            var source=new TerrainSourceStore(directory,CACHE,(path,options)->{
                if(delay>0)try{Thread.sleep(delay);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException();}
                return FileChannel.open(path,options);
            },true);
            try{
                fill((x,z)->{
                    if(source.sample(x,z,false,LOADER)!=LOADER.column(x,z))throw new AssertionError("production height");
                    source.appearance(x,z,()->new TerrainSourceStore.Appearance(0x123456,(byte)3));
                    if(source.residentBytes()>CACHE*2560L)throw new AssertionError("production RAM bound");
                },count);
                source.closeAsync().get(30,TimeUnit.SECONDS);
                if(source.pendingWrites()!=0||source.errors.sum()!=0)throw new AssertionError("production unfinished/error writes");
                result=new Result((System.nanoTime()-start)/1e6,source.regionOpens.sum(),source.evictionVisits.sum(),source.regionBatches.sum(),source.writtenPages.sum(),source.pressureNanos.sum(),source.residentBytes());
                if(source.peakRegions()>64||result.visits>count*2L)throw new AssertionError("unbounded handles/eviction work");
                if(count>=PAGES&&result.opens>=count/2)throw new AssertionError("regressed to per-page file opening");
            }finally{source.close();}
        }
        // Verify every saved height and appearance, using production decode for both versions.
        var reopened=new TerrainSourceStore(directory,64);
        try{
            var never=new TerrainSourceStore.Loader(){public int column(int x,int z){throw new AssertionError("regenerated after save");}public int[] batch(int x,int z){throw new AssertionError();}};
            for(int n=0;n<count;n++){int x=(n%64)*16-512,z=(n/64)*16-512;
                if(reopened.sample(x,z,false,never)!=LOADER.column(x,z))throw new AssertionError("source equality");
                if(!reopened.appearance(x,z,()->{throw new AssertionError("lost appearance");}).equals(new TerrainSourceStore.Appearance(0x123456,(byte)3)))throw new AssertionError("material equality");
            }
        }finally{reopened.closeAsync().get(10,TimeUnit.SECONDS);}
        return result;
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("everview-source-pressure-");
        try{
            System.out.printf(Locale.ROOT,"Source pressure: %d distinct 16b pages, cache %d pages (%.2f MiB payload), 8 workers; wall time includes final save; every saved point/material verified.%n",PAGES,CACHE,CACHE*2560/1048576.0);
            run(root.resolve("warm-old"),true,0,256);run(root.resolve("warm-new"),false,0,256);
            for(long delay:new long[]{0,2}){
                double[] oldTimes=new double[3],newTimes=new double[3];
                for(int trial=0;trial<3;trial++){
                    Result old,next;
                    if(trial%2==0){old=run(root.resolve(delay+"-"+trial+"-old"),true,delay,PAGES);next=run(root.resolve(delay+"-"+trial+"-new"),false,delay,PAGES);}
                    else{next=run(root.resolve(delay+"-"+trial+"-new"),false,delay,PAGES);old=run(root.resolve(delay+"-"+trial+"-old"),true,delay,PAGES);}
                    oldTimes[trial]=old.ms;newTimes[trial]=next.ms;
                    System.out.printf(Locale.ROOT,"open latency %d ms, trial %d: M9.7.3 %.1f ms / %,d opens / %,d eviction visits; M9.7.4 %.1f ms / %,d opens / %,d eviction visits / %,d region batches / %,d saved pages / %.1f ms admission wait; RAM <= %.2f MiB%n",delay,trial+1,old.ms,old.opens,old.visits,next.ms,next.opens,next.visits,next.batches,next.saved,next.pressure/1e6,next.peak/1048576.0);
                }
                Arrays.sort(oldTimes);Arrays.sort(newTimes);
                System.out.printf(Locale.ROOT,"open latency %d ms median: M9.7.3 %.1f ms, M9.7.4 %.1f ms, %.2fx (source/cache I/O benchmark only)%n",delay,oldTimes[1],newTimes[1],oldTimes[1]/newTimes[1]);
            }
        }finally{try(var paths=Files.walk(root)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
