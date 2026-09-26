package dev.everview.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.everview.terrain.ColumnOwnershipMask;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Shared final ownership gate for every terrain level, water and seam draw. */
public final class VanillaOwnershipBuffer {
    public static final ColumnOwnershipMask mask=new ColumnOwnershipMask();
    private static GpuBuffer buffer;
    private static final int[] uploaded=new int[ColumnOwnershipMask.WORDS];
    private static final ByteBuffer staging=ByteBuffer.allocateDirect(ColumnOwnershipMask.WORDS*4).order(ByteOrder.nativeOrder());
    public static long prepareNanos;public static int updates;
    private VanillaOwnershipBuffer(){}
    public static void upload(){
        if(buffer!=null&&Arrays.equals(uploaded,mask.words()))return;
        staging.clear();for(int v:mask.words())staging.putInt(v);staging.flip();
        GpuBuffer next=RenderSystem.getDevice().createBuffer(()->"Everview final vanilla ownership",GpuBuffer.USAGE_UNIFORM,staging);
        if(buffer!=null)buffer.close();buffer=next;
        System.arraycopy(mask.words(),0,uploaded,0,uploaded.length);updates++;
    }
    public static void bind(RenderPass pass){if(buffer!=null)pass.setUniform("EverviewOwnership",buffer.slice());}
    public static void clear(){if(buffer!=null){buffer.close();buffer=null;}Arrays.fill(uploaded,0);}
}
