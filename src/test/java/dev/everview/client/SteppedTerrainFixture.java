package dev.everview.client;

import dev.everview.core.LodTileKey;
import java.lang.reflect.*;
import java.util.*;

/** Supplies immutable lattice data to the production coverage builder, without worldgen. */
final class SteppedTerrainFixture {
    private static final Class<?> JOB=Arrays.stream(WorldgenSurfaceSampler.class.getDeclaredClasses())
            .filter(c->c.getSimpleName().equals("GenerationJob")).findFirst().orElseThrow();
    private static final Method BUILD;
    static {try{BUILD=WorldgenSurfaceSampler.class.getDeclaredMethod("buildMesh",JOB,int.class);BUILD.setAccessible(true);}catch(Exception ex){throw new ExceptionInInitializerError(ex);}}
    @FunctionalInterface interface Height {int at(int x,int z);}
    final Object job;final int level,size,spacing,originX,originZ,cells;
    final int[] heights,colors,fluid;final byte[] materials;final boolean[] wet;
    SteppedTerrainFixture(int level,int size,int spacing,int tx,int tz,Height terrain,Height water)throws Exception {
        this.level=level;this.size=size;this.spacing=spacing;originX=tx*size;originZ=tz*size;cells=size/spacing;
        var ctor=JOB.getDeclaredConstructors()[0];ctor.setAccessible(true);
        job=ctor.newInstance(new LodTileKey(level,tx,tz),new WorldgenLodRing(level,0,16384,size,spacing),spacing,false,null,false);
        heights=(int[])field("heights");colors=(int[])field("sampleColors");fluid=(int[])field("fluidHeights");materials=(byte[])field("sampleMaterials");wet=(boolean[])field("water");
        for(int z=0;z<=cells;z++)for(int x=0;x<=cells;x++){
            int i=z*(cells+1)+x,wx=originX+x*spacing,wz=originZ+z*spacing;
            heights[i]=terrain.at(wx,wz);fluid[i]=water.at(wx,wz);wet[i]=fluid[i]>heights[i];
            materials[i]=wet[i]?MinecraftSurfacePalette.MATERIAL_WATER:MinecraftSurfacePalette.MATERIAL_GRASS;
            colors[i]=wet[i]?0x3B6E98:0x6F9D50;
        }
    }
    private Object field(String name)throws Exception {var f=JOB.getDeclaredField(name);f.setAccessible(true);return f.get(job);}
    WorldgenSurfaceTile build()throws Exception {
        Object mesh=BUILD.invoke(null,job,63);Class<?> type=mesh.getClass();
        int[] xyz=(int[])value(mesh,type,"vertices"),rgb=(int[])value(mesh,type,"colors");byte[] material=(byte[])value(mesh,type,"materials");
        return new WorldgenSurfaceTile(level,originX/size,originZ/size,size,spacing,WorldgenTileStage.COVERAGE,
                xyz,rgb,material,xyz.length/12,-64,320,63,0);
    }
    private static Object value(Object record,Class<?> type,String name)throws Exception {var m=type.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(record);}
    EverviewGpuRegionCache.GpuRegion region(WorldgenSurfaceTile tile) {
        var prepared=EverviewGpuTileCache.prepareRegionGeometry(tile,originX,originZ,0);
        var region=new EverviewGpuRegionCache.GpuRegion(new EverviewGpuRegionCache.RegionKey(level,originX/size,originZ/size),
                null,prepared.indexCount(),prepared.colors().length*16L,originX,originZ,List.of(tile),new ArrayList<>());
        region.tileViews().add(new EverviewGpuRegionCache.GpuTile(tile,region,0,prepared.indexCount(),prepared.drawBatches(),
                new TerrainSurfaceData(prepared.materials(),prepared.colors()),TerrainEdges.from(prepared,originX,originZ)));
        return region;
    }
}
