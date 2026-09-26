package dev.everview.terrain;

import java.io.*;
import java.util.zip.CRC32;

/** Optional independent spatial layer envelope. Surface pages do NOT allocate these.
 * producer identifies the generator/data-pack semantics; codecVersion belongs to that layer.
 * A vertical coordinate permits volumes/multiple surfaces without changing the surface cache.
 * Callers activate these pages only for visibility-relevant detail. */
public record TerrainLayerPage(String layer,String producer,int codecVersion,int pageX,int pageY,int pageZ,byte[] payload) {
    private static final int MAGIC=0x45564C59, ENVELOPE_VERSION=1, MAX_PAYLOAD=4*1024*1024;
    public TerrainLayerPage {
        if(layer==null||producer==null||layer.length()>128||producer.length()>512||codecVersion<1||payload==null||payload.length>MAX_PAYLOAD)
            throw new IllegalArgumentException("invalid optional terrain layer");
        payload=payload.clone();
    }
    @Override public byte[] payload(){return payload.clone();}
    public void write(DataOutput out)throws IOException{
        CRC32 crc=new CRC32();crc.update(payload);
        out.writeInt(MAGIC);out.writeInt(ENVELOPE_VERSION);out.writeUTF(layer);out.writeUTF(producer);
        out.writeInt(codecVersion);out.writeInt(pageX);out.writeInt(pageY);out.writeInt(pageZ);
        out.writeInt(payload.length);out.writeInt((int)crc.getValue());out.write(payload);
    }
    public static TerrainLayerPage read(DataInput in)throws IOException{
        if(in.readInt()!=MAGIC||in.readInt()!=ENVELOPE_VERSION)throw new IOException("unsupported layer envelope");
        String layer=in.readUTF(),producer=in.readUTF();int codec=in.readInt(),x=in.readInt(),y=in.readInt(),z=in.readInt();
        int size=in.readInt(),expected=in.readInt();if(size<0||size>MAX_PAYLOAD)throw new IOException("invalid layer size");
        byte[] payload=new byte[size];in.readFully(payload);CRC32 crc=new CRC32();crc.update(payload);
        if((int)crc.getValue()!=expected)throw new IOException("corrupt layer payload");
        return new TerrainLayerPage(layer,producer,codec,x,y,z,payload);
    }
}
