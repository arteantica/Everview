package dev.everview.terrain;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Bounded world-scoped source pages shared by every LOD. Clean, unpinned pages
 * are the only eviction candidates. Generation waits for background writeback at
 * the RAM limit; neither cache scans nor disk writes run in the admission path. */
public final class TerrainSourceStore implements AutoCloseable {
    public interface Loader extends AutoCloseable {
        int[] batch(int x,int z);int column(int x,int z);
        @Override default void close() {}
    }
    public record Appearance(int color,byte material) {}
    public interface AppearanceLoader {Appearance sample();}
    public final LongAdder hits=new LongAdder(),misses=new LongAdder(),batchColumns=new LongAdder(),
            readNanos=new LongAdder(),writeNanos=new LongAdder(),diskHits=new LongAdder(),writtenPages=new LongAdder(),
            waitNanos=new LongAdder(),lookupNanos=new LongAdder(),errors=new LongAdder(),budgetWaits=new LongAdder(),
            pressureNanos=new LongAdder(),pressureWaits=new LongAdder(),evictionVisits=new LongAdder();
    public final LongAdder readBytes,writeBytes,regionOpens,regionBatches;
    private final Map<Long,Page> pages=new HashMap<>();
    private final LinkedHashSet<Page> clean=new LinkedHashSet<>();
    private final ConcurrentLinkedQueue<Page> dirty=new ConcurrentLinkedQueue<>();
    private final AtomicInteger dirtyCount=new AtomicInteger();
    private final AtomicBoolean scheduled=new AtomicBoolean();
    private final Object flushLock=new Object();
    private final int maxPages;
    private final SourceRegionStore disk;
    private final ScheduledExecutorService writer;
    private final boolean background;
    private final CompletableFuture<Void> closeFuture=new CompletableFuture<>();
    private volatile boolean closed;
    private volatile IOException lastFailure;
    private static final class Page {
        final int x,z;final SourcePageData data=new SourcePageData();
        boolean loaded;volatile boolean queued;int pins;long revision;
        SourceRegionStore.Stamp stamp=SourceRegionStore.Stamp.EMPTY;
        Page(int x,int z){this.x=x;this.z=z;}
    }
    private record Snapshot(Page page,long revision,SourceRegionStore.Pending pending) {}
    public TerrainSourceStore(Path directory,int maxPages){this(directory,maxPages,FileChannel::open,true);}
    /** Actual file-open injection and deterministic manual drains for persistence tests. */
    TerrainSourceStore(Path directory,int maxPages,SourceRegionStore.Opener opener,boolean background){
        this.maxPages=Math.max(16,maxPages);this.background=background;
        disk=directory==null?null:new SourceRegionStore(directory,opener);
        readBytes=disk==null?new LongAdder():disk.readBytes;writeBytes=disk==null?new LongAdder():disk.writeBytes;
        regionOpens=disk==null?new LongAdder():disk.opens;regionBatches=disk==null?new LongAdder():disk.batches;
        writer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"Everview-SourceIO");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;});
    }
    public long residentBytes(){synchronized(pages){return pages.size()*2560L;}}
    public int pendingWrites(){return dirtyCount.get();} // Includes detached in-flight snapshots.
    public int cachedRegions(){return disk==null?0:disk.cachedRegions();}
    int peakRegions(){return disk==null?0:disk.peakRegions();}
    private static long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
    private Page pin(int x,int z){
        long k=key(x>>4,z>>4),deadline=System.nanoTime()+5_000_000_000L;
        for(;;){
            long lookup=System.nanoTime();
            synchronized(pages){
                if(closed)throw new CancellationException("terrain source world closed");
                Page p=pages.get(k);
                if(p!=null){clean.remove(p);p.pins++;lookupNanos.add(System.nanoTime()-lookup);return p;}
                if(pages.size()>=maxPages && !clean.isEmpty()){
                    var it=clean.iterator();Page victim=it.next();it.remove();evictionVisits.increment();
                    if(victim.pins!=0||victim.queued)throw new IllegalStateException("dirty/pinned source eviction");
                    pages.remove(key(victim.x>>4,victim.z>>4));
                }
                if(pages.size()<maxPages){
                    p=new Page(x&~15,z&~15);p.pins=1;pages.put(k,p);lookupNanos.add(System.nanoTime()-lookup);return p;
                }
                lookupNanos.add(System.nanoTime()-lookup);
                if(lastFailure!=null||System.nanoTime()>=deadline){budgetWaits.increment();throw new CancellationException("terrain source budget: failed/slow background writeback");}
                pressureWaits.increment();requestDrain(0);long wait=System.nanoTime();
                try{pages.wait(100);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new CancellationException("source admission interrupted");}
                finally{pressureNanos.add(System.nanoTime()-wait);}
            }
        }
    }
    private void release(Page p){synchronized(pages){p.pins--;if(p.pins==0&&!p.queued)clean.add(p);pages.notifyAll();}}
    private void load(Page p){
        if(p.loaded)return;long start=System.nanoTime();
        try{if(disk!=null){var read=disk.read(p.x,p.z);p.stamp=read.stamp();if(read.data()!=null){p.data.mergeMissing(read.data());diskHits.increment();}}}
        catch(IOException ex){errors.increment();}
        p.loaded=true;readNanos.add(System.nanoTime()-start);
    }
    /** Called only with the page lock; pinning already removed its clean candidate. */
    private void mark(Page p){
        if(closed)throw new CancellationException("terrain source world closed during fill");
        p.revision++;
        if(disk!=null&&!p.queued){p.queued=true;dirtyCount.incrementAndGet();dirty.add(p);requestDrain(dirtyCount.get()>=256?0:20);}
    }
    public int sample(int x,int z,boolean dense,Loader loader){
        Page p=pin(x,z);long wait=System.nanoTime();
        try{synchronized(p){
            waitNanos.add(System.nanoTime()-wait);load(p);int i=(z&15)*16+(x&15);
            if(p.data.valid.get(i)){hits.increment();return p.data.heights[i];}
            misses.increment();
            if(dense){int[] batch=loader.batch(p.x,p.z);if(batch.length!=256)throw new IllegalStateException("source batch must be 16x16");
                System.arraycopy(batch,0,p.data.heights,0,256);p.data.valid.set(0,256);batchColumns.add(256);
            }else{p.data.heights[i]=loader.column(x,z);p.data.valid.set(i);batchColumns.increment();}
            mark(p);return p.data.heights[i];
        }}finally{release(p);}
    }
    public Appearance appearance(int x,int z,AppearanceLoader loader){
        Page p=pin(x,z);long wait=System.nanoTime();
        try{synchronized(p){waitNanos.add(System.nanoTime()-wait);load(p);int i=(z&15)*16+(x&15);
            if(!p.data.appearance.get(i)){var a=loader.sample();p.data.colors[i]=a.color;p.data.materials[i]=a.material;p.data.appearance.set(i);mark(p);}
            return new Appearance(p.data.colors[i],p.data.materials[i]);
        }}finally{release(p);}
    }
    private void requestDrain(long delay){
        if(!background||closed||!scheduled.compareAndSet(false,true))return;
        try{writer.schedule(()->{
            try{flushUntil(512,System.nanoTime()+50_000_000L);}finally{
                scheduled.set(false);if(dirtyCount.get()>0&&!closed)requestDrain(lastFailure!=null?500:0);
            }
        },delay,TimeUnit.MILLISECONDS);}catch(RejectedExecutionException ex){scheduled.set(false);}
    }
    public void flush(int limit){flushUntil(limit,Long.MAX_VALUE);}
    private void flushUntil(int limit,long deadline){
        synchronized(flushLock){
            int remaining=limit;
            while(remaining>0 && System.nanoTime()<deadline){
                var groups=new LinkedHashMap<Long,List<Snapshot>>();int count=0;
                while(count<Math.min(512,remaining)){
                    Page p=dirty.poll();if(p==null)break;
                    synchronized(p){
                        if(!p.queued)throw new IllegalStateException("duplicate source writeback");
                        var pending=new SourceRegionStore.Pending(p.x,p.z,p.data.encode(),p.stamp);
                        groups.computeIfAbsent(key(p.x>>8,p.z>>8),ignored->new ArrayList<>()).add(new Snapshot(p,p.revision,pending));
                    }
                    count++;
                }
                if(count==0)return;remaining-=count;
                var unacked=new LinkedHashSet<Snapshot>();for(var group:groups.values())unacked.addAll(group);
                try{
                    for(var group:groups.values()){
                        long start=System.nanoTime();var saved=disk.write(group.stream().map(Snapshot::pending).toList());
                        writeNanos.add(System.nanoTime()-start);
                        for(int i=0;i<group.size();i++){
                            var snapshot=group.get(i);Page p=snapshot.page;
                            synchronized(p){
                                p.data.mergeMissing(saved.get(i).data());p.stamp=saved.get(i).stamp();
                                if(p.revision==snapshot.revision){p.queued=false;dirtyCount.decrementAndGet();}
                                else dirty.add(p); // Edits during I/O get a fresh snapshot, never a lost acknowledgement.
                            }
                            synchronized(pages){if(p.pins==0&&!p.queued&&pages.get(key(p.x>>4,p.z>>4))==p)clean.add(p);pages.notifyAll();}
                            writtenPages.increment();unacked.remove(snapshot);
                        }
                    }
                    lastFailure=null;
                }catch(IOException ex){
                    lastFailure=ex;errors.increment();for(var snapshot:unacked)dirty.add(snapshot.page);
                    synchronized(pages){pages.notifyAll();}return;
                }
            }
        }
    }
    /** Nonblocking on the render thread; completion represents final writes and handle retirement. */
    public CompletableFuture<Void> closeAsync(){
        synchronized(pages){if(closed)return closeFuture;closed=true;pages.notifyAll();}
        writer.execute(()->{
            Exception failure=null;
            try{
                synchronized(pages){while(pages.values().stream().anyMatch(p->p.pins!=0))pages.wait(100);}
                flush(Integer.MAX_VALUE);
                if(dirtyCount.get()!=0)throw new IOException("unsaved terrain source pages",lastFailure);
            }catch(Exception ex){failure=ex;}
            finally{if(disk!=null)try{disk.close();}catch(IOException ex){errors.increment();if(failure==null)failure=ex;}}
            if(failure==null)closeFuture.complete(null);else closeFuture.completeExceptionally(failure);
        });
        writer.shutdown();return closeFuture;
    }
    @Override public void close(){closeAsync();}
}
