package dev.everview.terrain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

final class SourcePressureTest {
    @TempDir Path directory;
    static final TerrainSourceStore.Loader LOADER=new TerrainSourceStore.Loader(){
        public int column(int x,int z){return TerrainNoiseBatch.pack(40+Math.floorMod(x+z,20),63);}
        public int[] batch(int x,int z){int[] a=new int[256];for(int i=0;i<256;i++)a[i]=column(x+i%16,z+i/16);return a;}
    };
    private static final TerrainSourceStore.Loader NEVER=new TerrainSourceStore.Loader(){
        public int column(int x,int z){throw new AssertionError("saved source regenerated");}
        public int[] batch(int x,int z){throw new AssertionError("saved source regenerated");}
    };
    @Test void eightWorkersDrainBeyondRamBoundWithoutWorkerWrites()throws Exception {
        AtomicInteger workerWrites=new AtomicInteger();
        var store=new TerrainSourceStore(directory,64,(p,options)->{
            if(Arrays.asList(options).contains(StandardOpenOption.WRITE)&&!Thread.currentThread().getName().equals("Everview-SourceIO"))workerWrites.incrementAndGet();
            return FileChannel.open(p,options);
        },true);
        try(var pool=Executors.newFixedThreadPool(8)){
            var jobs=new ArrayList<Future<?>>();
            for(int t=0;t<8;t++){final int lane=t;jobs.add(pool.submit(()->{
                for(int n=lane;n<2048;n+=8){int x=(n%64)*16-512,z=(n/64)*16-256;
                    assertEquals(LOADER.column(x,z),store.sample(x,z,false,LOADER));
                    store.appearance(x,z,()->new TerrainSourceStore.Appearance(0x123456,(byte)3));
                    assertTrue(store.residentBytes()<=64*2560L);
                }
            }));}
            for(var job:jobs)job.get(30,TimeUnit.SECONDS);
        }
        store.closeAsync().get(10,TimeUnit.SECONDS);
        assertEquals(0,store.pendingWrites());assertEquals(0,store.errors.sum());assertEquals(0,workerWrites.get());
        assertTrue(store.evictionVisits.sum()<4096,"eviction scanned beyond candidate count");assertTrue(store.peakRegions()<=64);
        try(var reopened=new TerrainSourceStore(directory,64)){
            for(int n=0;n<2048;n++){int x=(n%64)*16-512,z=(n/64)*16-256;
                assertEquals(LOADER.column(x,z),reopened.sample(x,z,false,NEVER));
                assertEquals(new TerrainSourceStore.Appearance(0x123456,(byte)3),reopened.appearance(x,z,()->{throw new AssertionError("lost material");}));
            }
        }
    }
    @Test void editDuringDetachedWriteIsNotAcknowledgedAsClean()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var store=new TerrainSourceStore(directory,16,(p,options)->{
            if(Arrays.asList(options).contains(StandardOpenOption.WRITE)){
                entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("blocked test writer");}
                catch(InterruptedException ex){throw new InterruptedIOException();}
            }
            return FileChannel.open(p,options);
        },false);
        try(var pool=Executors.newSingleThreadExecutor()){
            store.sample(-16,0,false,LOADER);Future<?> write=pool.submit(()->store.flush(1));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            // This would block if compression/file opening still held the live page lock.
            store.sample(-15,0,false,LOADER);store.appearance(-15,0,()->new TerrainSourceStore.Appearance(0xabcdef,(byte)5));
            assertEquals(1,store.pendingWrites(),"in-flight page absent from pressure count");
            release.countDown();write.get(5,TimeUnit.SECONDS);
            assertEquals(1,store.pendingWrites(),"old snapshot cleared a newer revision");
        }finally{release.countDown();store.closeAsync().get(5,TimeUnit.SECONDS);}
        try(var reopened=new TerrainSourceStore(directory,16)){
            for(int x=-16;x<=-15;x++)assertEquals(LOADER.column(x,0),reopened.sample(x,0,false,NEVER));
            assertEquals(new TerrainSourceStore.Appearance(0xabcdef,(byte)5),reopened.appearance(-15,0,()->{throw new AssertionError();}));
        }
    }
    @Test void overlappingWorldStoresMergeColumnsAndRefreshCachedHeaders()throws Exception {
        var old=new TerrainSourceStore(directory,16,FileChannel::open,false);
        var replacement=new TerrainSourceStore(directory,16,FileChannel::open,false);
        old.sample(-1,-1,false,LOADER);replacement.sample(-2,-1,false,LOADER);
        replacement.appearance(-2,-1,()->new TerrainSourceStore.Appearance(0x778899,(byte)4));
        replacement.flush(10);old.closeAsync().get(5,TimeUnit.SECONDS);
        // Replacement still has an old page snapshot: new writes must retain old world's added column.
        replacement.sample(-3,-1,false,LOADER);replacement.closeAsync().get(5,TimeUnit.SECONDS);
        try(var reopened=new TerrainSourceStore(directory,16)){
            for(int x=-3;x<=-1;x++)assertEquals(LOADER.column(x,-1),reopened.sample(x,-1,false,NEVER));
            assertEquals(new TerrainSourceStore.Appearance(0x778899,(byte)4),reopened.appearance(-2,-1,()->{throw new AssertionError();}));
        }
    }
    @Test void legacyCacheIsReadWithoutRegenerationAndHandlesAreBounded()throws Exception {
        try(var old=new LegacySourceBaseline(directory,128)){
            var loader=new LegacySourceBaseline.Loader(){public int column(int x,int z){return LOADER.column(x,z);}public int[] batch(int x,int z){return LOADER.batch(x,z);}};
            for(int i=0;i<96;i++)old.sample(i*256,-256,false,loader);
            old.flush(200);
        }
        var store=new TerrainSourceStore(directory,16);
        for(int i=0;i<96;i++)assertEquals(LOADER.column(i*256,-256),store.sample(i*256,-256,false,NEVER));
        assertEquals(64,store.peakRegions());store.closeAsync().get(5,TimeUnit.SECONDS);
        assertEquals(0,store.cachedRegions());
    }
    @Test void compactionInvalidatesOtherStoresOpenFileAndKeepsEveryColumn()throws Exception {
        // Build a valid indexed legacy region just over the compaction threshold.
        SourcePageData first=new SourcePageData();first.valid.set(0);first.heights[0]=LOADER.column(0,0);
        byte[] payload=SourcePageData.compress(first.encode());var crc=new java.util.zip.CRC32();crc.update(payload);
        Files.createDirectories(directory);Path region=directory.resolve("0_0.evs");
        try(var file=FileChannel.open(region,StandardOpenOption.CREATE,StandardOpenOption.WRITE)){
            file.write(java.nio.ByteBuffer.wrap(payload),SourceRegionStore.HEADER);
            var header=java.nio.ByteBuffer.allocate(4096).putLong(4096).putInt(payload.length).putInt((int)crc.getValue());header.position(0);file.write(header,0);
            file.write(java.nio.ByteBuffer.wrap(new byte[1]),SourceRegionStore.COMPACT_BYTES);
        }
        var observer=new TerrainSourceStore(directory,16,FileChannel::open,false);
        var writer=new TerrainSourceStore(directory,16,FileChannel::open,false);
        observer.sample(0,0,false,NEVER);writer.sample(1,0,false,LOADER);writer.sample(16,0,false,LOADER);writer.flush(10);
        assertTrue(Files.size(region)<10000,"region log not compacted");
        assertEquals(LOADER.column(16,0),observer.sample(16,0,false,NEVER),"cached reader retained pre-compaction inode/header");
        observer.sample(2,0,false,LOADER);observer.flush(10);
        observer.closeAsync().get(5,TimeUnit.SECONDS);writer.closeAsync().get(5,TimeUnit.SECONDS);
        try(var reopened=new TerrainSourceStore(directory,16)){
            for(int x=0;x<3;x++)assertEquals(LOADER.column(x,0),reopened.sample(x,0,false,NEVER));
        }
    }
    @Test void failedRegionBatchRequeuesOnlyUnacknowledgedPages()throws Exception {
        AtomicBoolean fail=new AtomicBoolean(true);
        var store=new TerrainSourceStore(directory,64,(p,options)->{
            if(p.getFileName().toString().equals("1_0.evs")&&Arrays.asList(options).contains(StandardOpenOption.WRITE)&&fail.get())throw new IOException("injected second-region failure");
            return FileChannel.open(p,options);
        },false);
        store.sample(0,0,false,LOADER);store.sample(256,0,false,LOADER);store.flush(10);
        assertEquals(1,store.pendingWrites());assertEquals(1,store.writtenPages.sum());assertEquals(1,store.errors.sum());
        fail.set(false);store.flush(10);assertEquals(0,store.pendingWrites());assertEquals(2,store.writtenPages.sum());
        store.closeAsync().get(5,TimeUnit.SECONDS);
        try(var reopened=new TerrainSourceStore(directory,16)){
            for(int x:new int[]{0,256})assertEquals(LOADER.column(x,0),reopened.sample(x,0,false,NEVER));
        }
    }
    @Test void damagedLegacyPayloadRegeneratesAndPersistsAValidReplacement()throws Exception {
        var store=new TerrainSourceStore(directory,16,FileChannel::open,false);
        store.sample(0,0,false,LOADER);store.closeAsync().get(5,TimeUnit.SECONDS);
        Path file=directory.resolve("0_0.evs");
        try(var channel=FileChannel.open(file,StandardOpenOption.WRITE)){
            channel.write(java.nio.ByteBuffer.wrap(new byte[]{0}),4096); // Invalid CRC/compressed record.
        }
        var repaired=new TerrainSourceStore(directory,16,FileChannel::open,false);
        assertEquals(LOADER.column(0,0),repaired.sample(0,0,false,LOADER));assertEquals(1,repaired.errors.sum());
        repaired.closeAsync().get(5,TimeUnit.SECONDS);
        try(var reopened=new TerrainSourceStore(directory,16)){
            assertEquals(LOADER.column(0,0),reopened.sample(0,0,false,NEVER));assertEquals(0,reopened.errors.sum());
        }
    }
    @Test void closeCancelsAdmissionAndWaitsForActiveFill()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var store=new TerrainSourceStore(directory,16,FileChannel::open,false);
        for(int i=0;i<15;i++)store.sample(i*16,0,false,LOADER);
        try(var pool=Executors.newFixedThreadPool(2)){
            var fill=pool.submit(()->store.sample(240,0,false,new TerrainSourceStore.Loader(){
                public int[] batch(int x,int z){throw new AssertionError();}
                public int column(int x,int z){entered.countDown();try{release.await();}catch(InterruptedException ex){throw new CancellationException();}return 1;}
            }));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var waiter=pool.submit(()->store.sample(256,0,false,LOADER));
            long deadline=System.nanoTime()+2_000_000_000L;while(store.pressureWaits.sum()==0&&System.nanoTime()<deadline)Thread.onSpinWait();
            assertTrue(store.pressureWaits.sum()>0);var closed=store.closeAsync();assertFalse(closed.isDone());
            assertInstanceOf(CancellationException.class,assertThrows(ExecutionException.class,()->waiter.get(2,TimeUnit.SECONDS)).getCause());
            release.countDown();assertInstanceOf(CancellationException.class,assertThrows(ExecutionException.class,()->fill.get(2,TimeUnit.SECONDS)).getCause());
            closed.get(5,TimeUnit.SECONDS);assertEquals(0,store.pendingWrites());
        }finally{release.countDown();store.close();}
    }
}
