package dev.everview.terrain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

final class TerrainArchitectureTest {
    @TempDir Path directory;
    private static TerrainSourceStore.Loader loader(AtomicInteger calls) {
        return new TerrainSourceStore.Loader(){public int[] batch(int x,int z){calls.incrementAndGet();int[] a=new int[256];for(int dz=0;dz<16;dz++)for(int dx=0;dx<16;dx++)a[dz*16+dx]=column(x+dx,z+dz);return a;}
            public int column(int x,int z){int floor=40+Math.floorMod(x+z,20);return TerrainNoiseBatch.pack(floor,63);}};
    }
    @Test void neighboringAndOverlappingLodsShareOneBatch() throws Exception {
        AtomicInteger calls=new AtomicInteger();var loader=loader(calls);
        try(var store=new TerrainSourceStore(directory,16);var pool=Executors.newFixedThreadPool(8)){
            List<Future<?>> work=new ArrayList<>();
            for(int t=0;t<8;t++)work.add(pool.submit(()->{for(int z=-16;z<0;z++)for(int x=-16;x<0;x++)assertEquals(loader.column(x,z),store.sample(x,z,true,loader));}));
            for(var f:work)f.get();assertEquals(1,calls.get());assertEquals(2047,store.hits.sum());
            store.flush(100);
        }
    }
    @Test void diskReopenPreservesSeabedWaterDepthAndSolidMaterial() throws Exception {
        var l=loader(new AtomicInteger());int value;
        try(var store=new TerrainSourceStore(directory,16)){
            value=store.sample(-17,31,true,l);store.appearance(-17,31,()->new TerrainSourceStore.Appearance(0xaabbcc,(byte)7));store.flush(100);
        }
        try(var store=new TerrainSourceStore(directory,16)){
            var never=new TerrainSourceStore.Loader(){public int[] batch(int x,int z){throw new AssertionError("regenerated cached source");}public int column(int x,int z){throw new AssertionError();}};
            assertEquals(value,store.sample(-17,31,true,never));
            assertTrue(TerrainNoiseBatch.surface(value)-TerrainNoiseBatch.floor(value)>0);
            assertEquals(new TerrainSourceStore.Appearance(0xaabbcc,(byte)7),store.appearance(-17,31,()->{throw new AssertionError();}));
            assertEquals(1,store.diskHits.sum());
        }
    }
    @Test void movementEvictsPagesWithinBoundAndReusesDisk() {
        var l=loader(new AtomicInteger());
        try(var store=new TerrainSourceStore(directory,16)){
            for(int i=0;i<400;i++){store.sample(i*16,-16,true,l);store.flush(8);}
            assertTrue(store.residentBytes()<=18*2560L,"resident source pages exceeded bounded working set");
            assertEquals(l.column(0,-16),store.sample(0,-16,true,l));assertTrue(store.diskHits.sum()>0);
        }
    }
    @Test void failedDiskWritesCannotGrowTheSourceCacheWithoutBound() throws Exception {
        var unwritable=java.nio.file.Files.createTempFile("everview-source-file", ".tmp");
        try(var store=new TerrainSourceStore(unwritable,16)) {
            var loader=new TerrainSourceStore.Loader(){
                public int[] batch(int x,int z){return new int[256];}
                public int column(int x,int z){return 0;}
            };
            for(int i=0;i<16;i++)store.sample(i*16,0,false,loader);
            assertThrows(java.util.concurrent.CancellationException.class,()->store.sample(256,0,false,loader));
            assertEquals(16*2560L,store.residentBytes());
            assertTrue(store.errors.sum()>0);
            assertEquals(1,store.budgetWaits.sum());
        } finally {java.nio.file.Files.deleteIfExists(unwritable);}
    }

    @Test void closedWorldCannotPopulateTheNextWorld() {
        var store=new TerrainSourceStore(directory,16);store.close();
        assertThrows(CancellationException.class,()->store.sample(0,0,true,loader(new AtomicInteger())));
    }
    @Test void flat128AreaCollapsesToOneTop() {
        int n=129*129;int[] h=new int[n],c=new int[n];Arrays.fill(h,70);Arrays.fill(c,0x668844);
        assertEquals(1,new BlockSurfaceMesh(128,1,h,c,new byte[n],new boolean[n],1,(a,b,d,e,f,g,i,j,k,l,m,o,color,mat)->{}).build());
    }
    @Test void fullResolution2048FlatTileRetainsM96GeometryBudget() {
        int n=2049*2049;int[] h=new int[n],c=new int[n];Arrays.fill(h,80);
        int quads=new BlockSurfaceMesh(2048,1,h,c,new byte[n],new boolean[n],1,(a,b,d,e,f,g,i,j,k,l,m,o,color,mat)->{}).build();
        assertEquals(256,quads); // 4,194,304 exact source cells, same geometry as M9.6's coarse grid.
    }
    @Test void blockMeshPreservesEveryColumnCliffRidgeAndWetFootprint() {
        int cells=32,stride=33,n=stride*stride;int[] h=new int[n],c=new int[n];byte[] mat=new byte[n];boolean[] wet=new boolean[n];
        for(int z=0;z<=cells;z++)for(int x=0;x<=cells;x++){int i=z*stride+x;h[i]=x==17?150:x<8?63:70+(x/3)*2;wet[i]=x<8;mat[i]=(byte)(wet[i]?1:0);c[i]=0x778899;}
        int[] visited=new int[cells*cells];AtomicInteger walls=new AtomicInteger();
        new BlockSurfaceMesh(cells,1,h,c,mat,wet,1,(x0,y0,z0,x1,y1,z1,x2,y2,z2,x3,y3,z3,color,m)->{
            if(x2>x0&&z2>z0){assertEquals(y0,y1);assertEquals(y0,y2);assertEquals(y0,y3);
                for(int z=z0;z<z2;z++)for(int x=x0;x<x2;x++){assertEquals(h[z*stride+x],y0);assertEquals(mat[z*stride+x],m);visited[z*cells+x]++;}}
            else walls.incrementAndGet();
        }).build();
        for(int v:visited)assertEquals(1,v,"holes or duplicate top coverage");assertTrue(walls.get()>0);
    }
    @Test void detailIsNotLimitedByRingAndShorelinesGetPriority() {
        assertEquals(1,DetailPolicy.spacing(3200,900,false,0));
        assertEquals(1,DetailPolicy.spacing(8000,900,true,24));
        assertEquals(2,DetailPolicy.spacing(16000,900,true,40));
        assertEquals(4,DetailPolicy.spacing(16000,900,false,0));
    }
    @Test void ownershipCyclesClearBitsImmediately() {
        ColumnOwnershipMask mask=new ColumnOwnershipMask();
        for(int i=0;i<100;i++){
            mask.begin(-3,2);mask.own(-7,4);assertTrue(mask.owns(-7,4));assertFalse(mask.owns(-8,4));
            int bit=(4-(2-128))*256+(-7-(-3-128));
            assertNotEquals(0,mask.words()[bit>>5]&(1<<(bit&31)),"shader bit layout differs");
            mask.begin(-3,2);assertFalse(mask.owns(-7,4),"unloaded vanilla retained ownership");
        }
    }
    @Test void optionalSubsurfaceLayerRoundTripsWithoutSurfaceAllocation() throws Exception {
        var page=new TerrainLayerPage("everview:visible_subsurface","noise26.3/test-seed",1,-4,-8,9,new byte[]{4,7,11});
        var bytes=new ByteArrayOutputStream();page.write(new DataOutputStream(bytes));
        var read=TerrainLayerPage.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(page.layer(),read.layer());assertEquals(-8,read.pageY());assertArrayEquals(page.payload(),read.payload());
        byte[] corrupt=bytes.toByteArray();corrupt[corrupt.length-1]^=1;
        assertThrows(IOException.class,()->TerrainLayerPage.read(new DataInputStream(new ByteArrayInputStream(corrupt))));
    }
}
