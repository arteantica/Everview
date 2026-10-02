package dev.everview.terrain;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.zip.*;

/** The unchanged source-v1 page format. Detached payloads never reference live page arrays. */
final class SourcePageData {
    static final int MAGIC=0x45565331, SIZE=2372;
    final int[] heights=new int[256], colors=new int[256];
    final byte[] materials=new byte[256];
    final BitSet valid=new BitSet(256), appearance=new BitSet(256);
    byte[] encode() {
        var out=ByteBuffer.allocate(SIZE);out.putInt(MAGIC);putBits(out,valid);
        for(int v:heights)out.putInt(v);putBits(out,appearance);
        for(int i=0;i<256;i++)out.putInt(colors[i]).put(materials[i]);
        return out.array();
    }
    static SourcePageData decode(byte[] raw)throws IOException {
        if(raw.length!=SIZE)throw new IOException("source page length");
        var in=ByteBuffer.wrap(raw);if(in.getInt()!=MAGIC)throw new IOException("source version");
        var p=new SourcePageData();getBits(in,p.valid);
        for(int i=0;i<256;i++)p.heights[i]=in.getInt();getBits(in,p.appearance);
        for(int i=0;i<256;i++){p.colors[i]=in.getInt();p.materials[i]=in.get();}
        return p;
    }
    /** Union new columns without overwriting edits made after a write snapshot. */
    void mergeMissing(SourcePageData other) {
        for(int i=other.valid.nextSetBit(0);i>=0;i=other.valid.nextSetBit(i+1))
            if(!valid.get(i)){heights[i]=other.heights[i];valid.set(i);}
        for(int i=other.appearance.nextSetBit(0);i>=0;i=other.appearance.nextSetBit(i+1))
            if(!appearance.get(i)){colors[i]=other.colors[i];materials[i]=other.materials[i];appearance.set(i);}
    }
    static byte[] compress(byte[] raw)throws IOException {
        var bytes=new ByteArrayOutputStream(2048);var deflater=new Deflater(Deflater.BEST_SPEED);
        try(var out=new DeflaterOutputStream(bytes,deflater)){out.write(raw);}finally{deflater.end();}
        return bytes.toByteArray();
    }
    static SourcePageData inflate(byte[] data)throws IOException {
        try(var in=new InflaterInputStream(new ByteArrayInputStream(data))){
            // Bound corrupt/decompression-bomb pages independently of their compressed length.
            return decode(in.readNBytes(SIZE+1));
        }
    }
    private static void putBits(ByteBuffer out,BitSet bits){long[] a=bits.toLongArray();for(int i=0;i<4;i++)out.putLong(i<a.length?a[i]:0);}
    private static void getBits(ByteBuffer in,BitSet bits){for(int w=0;w<4;w++){long v=in.getLong();for(int b=0;b<64;b++)if((v&(1L<<b))!=0)bits.set(w*64+b);}}
}
