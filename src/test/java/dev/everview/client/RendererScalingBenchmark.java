package dev.everview.client;

import java.util.*;

/** Headless production ownership/command benchmark, not a GPU/FPS measurement. */
public final class RendererScalingBenchmark {
    public static void main(String[] args) {
        var all=new ArrayList<WorldgenSurfaceTile>();
        for(int level=6;level>=1;level--){
            int size=level<=2?128:128<<(level-2);
            int radius=level>=3?size*8:2048;
            for(int z=-radius/size;z<radius/size;z++)for(int x=-radius/size;x<radius/size;x++)
                all.add(new WorldgenSurfaceTile(level,x,z,size,4,WorldgenTileStage.ADAPTIVE_DETAIL,
                        new int[12],new int[4],new byte[4],1,60,80,63,0));
        }
        System.out.println("Synthetic 16K six-level working set; production CPU transaction/command path; no GPU or FPS claim.");
        for(int percent:new int[]{25,50,100}){
            var residents=regions(all.subList(0,all.size()*percent/100));
            EverviewRenderState state=EverviewRenderState.EMPTY;
            for(int i=0;i<3;i++)state=EverviewRenderState.build(residents,state,0,0);
            long start=System.nanoTime();
            for(int i=0;i<5;i++){
                var next=EverviewRenderState.build(residents,state,0,0);
                for(var region:residents)if(next.cached().get(region)!=state.cached().get(region))
                    throw new AssertionError("Unchanged resident region rebuilt native commands");
                state=next;
            }
            double ms=(System.nanoTime()-start)/5e6;
            int commands=state.regions().stream().mapToInt(p->p.commands().ranges().size()).sum();
            if(percent==100&&state.missingCells()!=0)throw new AssertionError("Full working set has a coverage gap");
            System.out.printf(Locale.ROOT,"%d%% tiles: %d resident regions, %d drawable regions, %,d cells, %d commands, 100%% unchanged commands reused, %.3f ms worker transaction, %d missing cells.%n",
                    percent,residents.size(),state.regions().size(),state.coverageCells(),commands,ms,state.missingCells());
        }
    }

    private static List<EverviewGpuRegionCache.GpuRegion> regions(List<WorldgenSurfaceTile> tiles){
        var groups=new LinkedHashMap<EverviewGpuRegionCache.RegionKey,List<WorldgenSurfaceTile>>();
        for(var t:tiles)groups.computeIfAbsent(new EverviewGpuRegionCache.RegionKey(t.lodLevel(),Math.floorDiv(t.tileX(),2),Math.floorDiv(t.tileZ(),2)),k->new ArrayList<>()).add(t);
        var result=new ArrayList<EverviewGpuRegionCache.GpuRegion>();
        for(var entry:groups.entrySet()){
            var key=entry.getKey();int size=entry.getValue().getFirst().tileSize();
            int ox=Math.floorDiv(entry.getValue().getFirst().minX(),size*2)*size*2;
            int oz=Math.floorDiv(entry.getValue().getFirst().minZ(),size*2)*size*2;
            int count=entry.getValue().size()*(size/128)*(size/128)*6;
            var region=new EverviewGpuRegionCache.GpuRegion(key,null,count,count*64L/6,ox,oz,entry.getValue(),new ArrayList<>());
            int first=0;
            for(var t:entry.getValue()){
                int begin=first;var batches=new ArrayList<EverviewGpuTileCache.DrawBatch>();
                for(int z=t.minZ();z<t.maxZ();z+=128)for(int x=t.minX();x<t.maxX();x+=128){
                    batches.add(new EverviewGpuTileCache.DrawBatch(first,6,false,true,x/16,z/16,4,false,0,0,true,Math.floorDiv(x,128),Math.floorDiv(z,128)));first+=6;
                }
                region.tileViews().add(new EverviewGpuRegionCache.GpuTile(t,region,begin,first-begin,batches,
                        new TerrainSurfaceData(new byte[0],new int[0]),new TerrainEdges(Map.of(),0)));
            }
            result.add(region);
        }
        return result;
    }
}
