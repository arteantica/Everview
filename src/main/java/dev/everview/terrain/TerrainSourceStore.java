package dev.everview.terrain;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;
import java.util.zip.*;

/** Bounded, world-scoped column data, independent of any LOD or prepared mesh.
 * 16x16 pages share one in-flight fill. Persistent 256x256 regions have an indexed
 * compressed page log; only dirty pages are written. Disk work never runs on rendering. */
public final class TerrainSourceStore implements AutoCloseable {
    public interface Loader { int[] batch(int x, int z); int column(int x, int z); }
    public record Appearance(int color, byte material) {}
    public interface AppearanceLoader { Appearance sample(); }
    public final LongAdder hits=new LongAdder(), misses=new LongAdder(), batchColumns=new LongAdder(),
            readNanos=new LongAdder(), writeNanos=new LongAdder(), readBytes=new LongAdder(), writeBytes=new LongAdder(),
            diskHits=new LongAdder(), waitNanos=new LongAdder(), lookupNanos=new LongAdder(), errors=new LongAdder(), budgetWaits=new LongAdder();
    private static final int HEADER=4096, MAGIC=0x45565331;
    private final LinkedHashMap<Long,Page> pages=new LinkedHashMap<>(256,.75f,true);
    private final ConcurrentLinkedQueue<Page> dirty=new ConcurrentLinkedQueue<>();
    private final int maxPages;
    private final Path directory;
    private static final Object[] DISK_LOCKS=new Object[64];
    static { Arrays.setAll(DISK_LOCKS,i->new Object()); }
    private final ScheduledExecutorService writer;
    private volatile boolean closed;
    private static final class Page {
        final int x,z; final int[] heights=new int[256], colors=new int[256]; final byte[] materials=new byte[256];
        final BitSet valid=new BitSet(256), appearance=new BitSet(256);
        boolean loaded, changed; volatile boolean queued; int pins;
        Page(int x,int z) {this.x=x;this.z=z;}
    }
    public TerrainSourceStore(Path directory,int maxPages) {
        this.directory=directory; this.maxPages=Math.max(16,maxPages);
        writer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"Everview-SourceIO");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;});
        writer.scheduleWithFixedDelay(()->flush(128),2,2,TimeUnit.SECONDS);
    }
    public synchronized long residentBytes() { synchronized(pages) {return pages.size()*2560L;} }
    public int pendingWrites() { return dirty.size(); }
    private static long key(int x,int z) {return ((long)x<<32)|(z&0xffffffffL);}
    private Page pin(int x,int z) {
        long start=System.nanoTime();
        long k=key(x>>4,z>>4);
        for (int attempt=0;attempt<2;attempt++) {
            synchronized(pages) {
                if(closed) throw new CancellationException("terrain source world closed");
                Page p=pages.get(k);
                if(p!=null){p.pins++;lookupNanos.add(System.nanoTime()-start);return p;}
                var it=pages.values().iterator();
                while(pages.size()>=maxPages && it.hasNext()) {
                    Page victim=it.next();
                    if(victim.pins==0 && !victim.queued)it.remove();
                }
                if(pages.size()<maxPages){
                    p=new Page(x&~15,z&~15);p.pins=1;pages.put(k,p);
                    lookupNanos.add(System.nanoTime()-start);return p;
                }
            }
            // Do not hold the index lock during disk I/O or acquire a page lock under it.
            flush(64);
        }
        budgetWaits.increment();
        throw new CancellationException("terrain source budget: pending writes/pinned pages");
    }
    private void release(Page p) {
        synchronized(pages) {
            p.pins--;
            if(pages.size()<=maxPages)return;
            var it=pages.values().iterator();
            int scanned=0;
            while(pages.size()>maxPages && it.hasNext() && scanned++<64) {
                Page victim=it.next();
                // Dirty pages remain in memory until saved: avoid a second instance loading stale data.
                if(victim.pins==0 && !victim.queued) it.remove();
            }
        }
        // Backpressure bounds dirty data too; performed by generation workers, never render.
        if(residentBytes()>maxPages*2560L) flush(64);
    }
    private void load(Page p) {
        if(p.loaded)return;
        long start=System.nanoTime();
        try {
            byte[] data=read(p.x,p.z);
            if(data!=null)try(var in=new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)))) {
                if(in.readInt()!=MAGIC)throw new IOException("source version");
                for(int i=0;i<4;i++)readBits(p.valid,i,in.readLong());
                for(int i=0;i<256;i++)p.heights[i]=in.readInt();
                for(int i=0;i<4;i++)readBits(p.appearance,i,in.readLong());
                for(int i=0;i<256;i++){p.colors[i]=in.readInt();p.materials[i]=in.readByte();}
                diskHits.increment();
            }
        }catch(IOException ex){p.valid.clear();p.appearance.clear();errors.increment();}
        p.loaded=true;readNanos.add(System.nanoTime()-start);
    }
    private static void readBits(BitSet bits,int word,long value) {for(int b=0;b<64;b++)if((value&(1L<<b))!=0)bits.set(word*64+b);}
    private void mark(Page p) {p.changed=true;if(!p.queued){p.queued=true;dirty.add(p);}}
    public int sample(int x,int z,boolean dense,Loader loader) {
        Page p=pin(x,z);long wait=System.nanoTime();
        try {synchronized(p) {
            waitNanos.add(System.nanoTime()-wait);load(p);int i=(z&15)*16+(x&15);
            if(p.valid.get(i)){hits.increment();return p.heights[i];}
            misses.increment();
            if(dense) {
                int[] batch=loader.batch(p.x,p.z);
                if(batch.length!=256)throw new IllegalStateException("source batch must be 16x16");
                System.arraycopy(batch,0,p.heights,0,256);p.valid.set(0,256);batchColumns.add(256);
            } else {p.heights[i]=loader.column(x,z);p.valid.set(i);batchColumns.increment();}
            mark(p);return p.heights[i];
        }}finally{release(p);}
    }
    public Appearance appearance(int x,int z,AppearanceLoader loader) {
        Page p=pin(x,z);
        try{synchronized(p){load(p);int i=(z&15)*16+(x&15);
            if(!p.appearance.get(i)){var a=loader.sample();p.colors[i]=a.color;p.materials[i]=a.material;p.appearance.set(i);mark(p);}
            return new Appearance(p.colors[i],p.materials[i]);
        }}finally{release(p);}
    }
    public void flush(int limit) {
        for(int n=0;n<limit;n++){
            Page p=dirty.poll();if(p==null)return;
            synchronized(p){
                try {if(p.changed){write(p);p.changed=false;}p.queued=false;}
                catch(IOException ex){errors.increment();dirty.add(p);return;}
            }
        }
    }
    private Path region(int x,int z){return directory.resolve((x>>8)+"_"+(z>>8)+".evs");}
    private Object lock(int x,int z){return DISK_LOCKS[(Objects.hash(directory,x>>8,z>>8))&63];}
    private int slot(int x,int z){return ((z>>4)&15)*16+((x>>4)&15);}
    private byte[] read(int x,int z)throws IOException {
        if(directory==null)return null;
        synchronized(lock(x,z)) {
            Path path=region(x,z);if(!Files.isRegularFile(path))return null;
            try(var file=FileChannel.open(path,StandardOpenOption.READ)){
                ByteBuffer entry=ByteBuffer.allocate(16);if(!readFully(file,entry,slot(x,z)*16L))return null;entry.flip();
                long offset=entry.getLong();int length=entry.getInt(),crc=entry.getInt();
                if(offset<HEADER || length<=0 || length>4096 || offset+length>file.size())return null;
                ByteBuffer data=ByteBuffer.allocate(length);if(!readFully(file,data,offset))return null;
                CRC32 checksum=new CRC32();checksum.update(data.array());if((int)checksum.getValue()!=crc)return null;
                readBytes.add(length);return data.array();
            }
        }
    }
    private static boolean readFully(FileChannel file,ByteBuffer b,long at)throws IOException{while(b.hasRemaining()){int n=file.read(b,at);if(n<=0)return false;at+=n;}return true;}
    private static void writeFully(FileChannel file,ByteBuffer b,long at)throws IOException{while(b.hasRemaining())at+=file.write(b,at);}
    private void write(Page p)throws IOException {
        if(directory==null)return;
        long start=System.nanoTime();var bytes=new ByteArrayOutputStream(2048);
        Deflater deflater=new Deflater(Deflater.BEST_SPEED);
        try(var out=new DataOutputStream(new DeflaterOutputStream(bytes,deflater))){
            out.writeInt(MAGIC);writeBits(out,p.valid);
            for(int v:p.heights)out.writeInt(v);writeBits(out,p.appearance);
            for(int i=0;i<256;i++){out.writeInt(p.colors[i]);out.writeByte(p.materials[i]);}
        } finally{deflater.end();}
        byte[] data=bytes.toByteArray();CRC32 crc=new CRC32();crc.update(data);
        synchronized(lock(p.x,p.z)) {
            Files.createDirectories(directory);Path path=region(p.x,p.z);
            try(var file=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.READ)){
                long offset=Math.max(HEADER,file.size());writeFully(file,ByteBuffer.wrap(data),offset);
                ByteBuffer entry=ByteBuffer.allocate(16).putLong(offset).putInt(data.length).putInt((int)crc.getValue());entry.flip();
                writeFully(file,entry,slot(p.x,p.z)*16L);writeBytes.add(data.length+16);
            }
            if(Files.size(path)>2*1024*1024)compact(path);
        }
        writeNanos.add(System.nanoTime()-start);
    }
    private static void writeBits(DataOutputStream out,BitSet bits)throws IOException{long[] a=bits.toLongArray();for(int i=0;i<4;i++)out.writeLong(i<a.length?a[i]:0);}
    private static void compact(Path path)throws IOException {
        Path temp=path.resolveSibling(path.getFileName()+".tmp");
        try(var src=FileChannel.open(path,StandardOpenOption.READ);var dst=FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){
            long offset=HEADER;
            for(int i=0;i<256;i++){
                ByteBuffer e=ByteBuffer.allocate(16);if(!readFully(src,e,i*16L))continue;e.flip();long old=e.getLong();int length=e.getInt(),crc=e.getInt();
                if(old<HEADER||length<=0||length>4096||old+length>src.size())continue;
                ByteBuffer data=ByteBuffer.allocate(length);if(!readFully(src,data,old))continue;data.flip();writeFully(dst,data,offset);
                e.clear();e.putLong(offset).putInt(length).putInt(crc).flip();writeFully(dst,e,i*16L);offset+=length;
            }
        }
        try{Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ex){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
    }
    @Override public void close(){closed=true;writer.execute(()->flush(Integer.MAX_VALUE));writer.shutdown();}
}
