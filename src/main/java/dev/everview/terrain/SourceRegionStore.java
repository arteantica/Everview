package dev.everview.terrain;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.zip.CRC32;

/** Bounded open-region/header cache and grouped append writes for the legacy source-v1 log.
 * Stripe revisions synchronize overlapping old/new world stores, including atomic compaction. */
final class SourceRegionStore implements AutoCloseable {
    static final int HEADER=4096, MAX_OPEN=64, COMPACT_BYTES=2*1024*1024;
    interface Opener {FileChannel open(Path path,OpenOption... options)throws IOException;}
    record Stamp(long offset,int length,int crc) {static final Stamp EMPTY=new Stamp(0,0,0);}
    record Read(SourcePageData data,Stamp stamp) {}
    record Pending(int x,int z,byte[] raw,Stamp base) {}
    record Saved(SourcePageData data,Stamp stamp) {}
    private static final class CorruptPage extends IOException {
        CorruptPage(String message){super(message);}
        CorruptPage(IOException cause){super("invalid compressed source page",cause);}
    }
    private static SourcePageData decodePayload(byte[] bytes)throws CorruptPage {
        try{return SourcePageData.inflate(bytes);}catch(IOException ex){throw new CorruptPage(ex);}
    }
    private static final class Stripe {long revision,incarnation;}
    private static final Stripe[] STRIPES=new Stripe[64];
    static {Arrays.setAll(STRIPES,i->new Stripe());}
    private static final class Region {
        final Path path;FileChannel channel;boolean writable;int refs;
        long revision=-1,incarnation=-1,size;byte[] header=new byte[HEADER];
        Region(Path path){this.path=path;}
    }
    private final Path directory;
    private final Opener opener;
    private final LinkedHashMap<Path,Region> cache=new LinkedHashMap<>(64,.75f,true);
    private boolean directoryReady;
    final LongAdder opens=new LongAdder(), readBytes=new LongAdder(),writeBytes=new LongAdder(),batches=new LongAdder(),compactions=new LongAdder();
    private int peakHandles;
    SourceRegionStore(Path directory,Opener opener){this.directory=directory.toAbsolutePath().normalize();this.opener=opener;}
    private Path path(int x,int z){return directory.resolve((x>>8)+"_"+(z>>8)+".evs");}
    private Stripe stripe(Path p){return STRIPES[p.hashCode()&63];}
    private static int slot(int x,int z){return ((z>>4)&15)*16+((x>>4)&15);}
    private Region acquire(Path path)throws IOException {
        Region evicted=null,r;
        synchronized(cache){
            r=cache.get(path);
            if(r==null){
                if(cache.size()>=MAX_OPEN){
                    var it=cache.values().iterator();
                    while(it.hasNext()){var candidate=it.next();if(candidate.refs==0){evicted=candidate;it.remove();break;}}
                    if(evicted==null)throw new IOException("source region handle budget exhausted");
                }
                r=new Region(path);cache.put(path,r);peakHandles=Math.max(peakHandles,cache.size());
            }
            r.refs++;
        }
        if(evicted!=null)try{closeChannel(evicted);}catch(IOException ex){release(r);throw ex;}
        return r;
    }
    private void release(Region r){synchronized(cache){r.refs--;}}
    int cachedRegions(){synchronized(cache){return cache.size();}}
    int peakRegions(){synchronized(cache){return peakHandles;}}
    private FileChannel open(Path p,OpenOption... options)throws IOException {opens.increment();return opener.open(p,options);}
    private static void closeChannel(Region r)throws IOException {if(r.channel!=null){r.channel.close();r.channel=null;}}
    private void prepare(Region r,Stripe stripe,boolean write)throws IOException {
        if(r.incarnation!=stripe.incarnation)closeChannel(r);
        if(write && !r.writable)closeChannel(r);
        if(r.channel==null){
            if(write){
                synchronized(this){if(!directoryReady){Files.createDirectories(directory);directoryReady=true;}}
                r.channel=open(r.path,StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE);r.writable=true;
            }else if(r.revision!=stripe.revision || r.incarnation!=stripe.incarnation){
                try{r.channel=open(r.path,StandardOpenOption.READ);r.writable=false;}
                catch(NoSuchFileException ex){r.header=new byte[HEADER];r.size=0;r.revision=stripe.revision;r.incarnation=stripe.incarnation;return;}
            }else return; // Negative lookup cached until a striped write creates/changes the region.
            r.revision=-1;
        }
        if(r.revision!=stripe.revision || r.incarnation!=stripe.incarnation){
            r.size=r.channel.size();r.header=new byte[HEADER];
            if(r.size>=HEADER && !readFully(r.channel,ByteBuffer.wrap(r.header),0))throw new EOFException("source header");
            r.revision=stripe.revision;r.incarnation=stripe.incarnation;
        }
    }
    private static Stamp stamp(Region r,int slot){var b=ByteBuffer.wrap(r.header,slot*16,16);return new Stamp(b.getLong(),b.getInt(),b.getInt());}
    private byte[] payload(Region r,Stamp s)throws IOException {
        if(s.offset==0&&s.length==0)return null;
        if(s.offset<HEADER||s.length<=0||s.length>4096||s.offset+s.length>r.size)throw new CorruptPage("source page range");
        byte[] data=new byte[s.length];if(!readFully(r.channel,ByteBuffer.wrap(data),s.offset))throw new CorruptPage("source page");
        var crc=new CRC32();crc.update(data);if((int)crc.getValue()!=s.crc)throw new CorruptPage("source page checksum");
        readBytes.add(data.length);return data;
    }
    Read read(int x,int z)throws IOException {
        Path p=path(x,z);Stripe s=stripe(p);
        synchronized(s){var r=acquire(p);try{prepare(r,s,false);Stamp at=stamp(r,slot(x,z));byte[] data=payload(r,at);return new Read(data==null?null:decodePayload(data),at);}finally{release(r);}}
    }
    /** One append and one complete index write per region, independent of page count. */
    List<Saved> write(List<Pending> group)throws IOException {
        if(group.isEmpty())return List.of();Path path=path(group.getFirst().x,group.getFirst().z);Stripe s=stripe(path);
        synchronized(s){var r=acquire(path);try{
            prepare(r,s,true);byte[] header=r.header.clone();long offset=Math.max(HEADER,r.size);
            var payloads=new ByteArrayOutputStream(group.size()*256);var saved=new ArrayList<Saved>(group.size());
            for(var pending:group){
                if(!path.equals(path(pending.x,pending.z)))throw new IllegalArgumentException("mixed source regions");
                var page=SourcePageData.decode(pending.raw);Stamp current=stamp(r,slot(pending.x,pending.z));
                if(!current.equals(pending.base)){
                    // An old world's final flush may race a new world's fill. Preserve both sets of columns.
                    try{byte[] prior=payload(r,current);if(prior!=null)page.mergeMissing(decodePayload(prior));}
                    catch(CorruptPage corrupt){/* Replace an invalid legacy page with the valid snapshot. */}
                }
                byte[] data=SourcePageData.compress(page.encode());var crc=new CRC32();crc.update(data);
                Stamp at=new Stamp(offset,data.length,(int)crc.getValue());
                ByteBuffer.wrap(header,slot(pending.x,pending.z)*16,16).putLong(at.offset).putInt(at.length).putInt(at.crc);
                payloads.write(data);offset+=data.length;saved.add(new Saved(page,at));
            }
            writeFully(r.channel,ByteBuffer.wrap(payloads.toByteArray()),Math.max(HEADER,r.size));
            writeFully(r.channel,ByteBuffer.wrap(header),0);
            r.header=header;r.size=offset;r.revision=++s.revision;batches.increment();writeBytes.add(payloads.size()+HEADER);
            if(offset>COMPACT_BYTES){
                // Saved stamps preceding compaction remain safe: the next write will compare/merge them.
                compact(r,s);
            }
            return saved;
        }catch(IOException ex){r.revision=-1;s.revision++;throw ex;}finally{release(r);}}
    }
    private void compact(Region r,Stripe stripe)throws IOException {
        byte[] header=new byte[HEADER];var data=new ByteArrayOutputStream();long offset=HEADER;
        for(int i=0;i<256;i++){
            Stamp at=stamp(r,i);byte[] bytes;
            try{bytes=payload(r,at);}catch(CorruptPage corrupt){continue;}
            if(bytes==null)continue;
            ByteBuffer.wrap(header,i*16,16).putLong(offset).putInt(bytes.length).putInt(at.crc);data.write(bytes);offset+=bytes.length;
        }
        Path temp=r.path.resolveSibling(r.path.getFileName()+".tmp");
        try(var dst=open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){
            writeFully(dst,ByteBuffer.wrap(data.toByteArray()),HEADER);writeFully(dst,ByteBuffer.wrap(header),0);
        }
        closeChannel(r);r.writable=false;
        try{Files.move(temp,r.path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(AtomicMoveNotSupportedException ex){Files.move(temp,r.path,StandardCopyOption.REPLACE_EXISTING);}
        stripe.incarnation++;stripe.revision++;r.revision=-1;compactions.increment();
    }
    private static boolean readFully(FileChannel file,ByteBuffer b,long at)throws IOException {while(b.hasRemaining()){int n=file.read(b,at);if(n<=0)return false;at+=n;}return true;}
    private static void writeFully(FileChannel file,ByteBuffer b,long at)throws IOException {while(b.hasRemaining()){int n=file.write(b,at);if(n<=0)throw new IOException("source write made no progress");at+=n;}}
    @Override public void close()throws IOException {synchronized(cache){IOException failure=null;for(var r:cache.values())try{closeChannel(r);}catch(IOException ex){failure=ex;}cache.clear();if(failure!=null)throw failure;}}
}
