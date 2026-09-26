package dev.everview.client;

import dev.everview.terrain.GenerationProfile;
import java.util.*;

/** Only evaluated at the existing 250 ms HUD refresh, never part of terrain traversal. */
public final class GenerationDiagnostics {
    private static long lastTime,lastColumns,lastTiles;private static double columnsPerSecond,tilesPerSecond;
    private GenerationDiagnostics(){}
    public static List<String> lines(WorldgenSurfaceSnapshot snapshot){
        var source=WorldgenSurfaceSampler.terrainSource();var out=new ArrayList<String>();long now=System.nanoTime();
        long columns=GenerationProfile.noiseColumns.sum(),tiles=GenerationProfile.integrated.sum();
        if(lastTime==0||now-lastTime>=1_000_000_000L){double seconds=(now-lastTime)/1e9;
            if(lastTime!=0&&columns>=lastColumns){columnsPerSecond=(columns-lastColumns)/seconds;tilesPerSecond=(tiles-lastTiles)/seconds;}
            lastTime=now;lastColumns=columns;lastTiles=tiles;
        }
        double seconds=Math.max(.001,(now-GenerationProfile.started)/1e9);
        out.add("Everview M9.7 | F7 generation/ownership | F8 renderer | F9 A/B");
        out.add(String.format(Locale.ROOT,"Coverage %.1f%% | LOD CPU mean %.3f ms | mask %.3f ms / %d vanilla columns",
                snapshot.completionPercent(),EverviewFrameProfiler.meanMs(),VanillaOwnershipBuffer.prepareNanos/1e6,VanillaOwnershipBuffer.mask.count()));
        out.add(String.format(Locale.ROOT,"Source %.0f new columns/s | %.2f integrated tiles/s | %,d total columns",columnsPerSecond,tilesPerSecond,columns));
        out.add(String.format(Locale.ROOT,"Sampling %.1f ms | biome %.1f ms (%,d queries) | material %.1f ms (cumulative worker time)",
                GenerationProfile.noiseNanos.sum()/1e6,GenerationProfile.biomeNanos.sum()/1e6,GenerationProfile.biomeCalls.sum(),GenerationProfile.materialNanos.sum()/1e6));
        out.add(String.format(Locale.ROOT,"Mesh %.1f ms / %,d meshes | height/detail busy %.2f workers avg | queue wait %.1f ms",
                GenerationProfile.meshNanos.sum()/1e6,GenerationProfile.meshes.sum(),GenerationProfile.workerNanos.sum()/1e9/seconds,GenerationProfile.queueNanos.sum()/1e6));
        out.add(WorldgenSurfaceSampler.generationQueues()+" | "+WorldgenSurfaceSampler.detailStatus());
        if(source!=null){long requests=source.hits.sum()+source.misses.sum();
            out.add(String.format(Locale.ROOT,"Source RAM ~%.1f MiB | %.1f%% point reuse | %,d disk pages reused | %d pending writes",
                    source.residentBytes()/1048576.0,requests==0?0:100.0*source.hits.sum()/requests,source.diskHits.sum(),source.pendingWrites()));
            out.add(String.format(Locale.ROOT,"Source I/O read %.1f ms / %.2f MiB | write %.1f ms / %.2f MiB | errors %,d | budget waits %,d",
                    source.readNanos.sum()/1e6,source.readBytes.sum()/1048576.0,source.writeNanos.sum()/1e6,source.writeBytes.sum()/1048576.0,source.errors.sum(),source.budgetWaits.sum()));
            out.add(String.format(Locale.ROOT,"Source lookup %.1f ms | page lock wait %.1f ms | dual solid/fluid heights | optional volume layers: inactive",
                    source.lookupNanos.sum()/1e6,source.waitNanos.sum()/1e6));
        }
        out.add(String.format(Locale.ROOT,"Refined 128b patches: 1b %,d | 2b %,d | 4b %,d | budget 8/16b %,d (processed patches)",
                GenerationProfile.detailCells1.sum(),GenerationProfile.detailCells2.sum(),GenerationProfile.detailCells4.sum(),GenerationProfile.detailCellsCoarse.sum()));
        for(int level=1;level<=6;level++){
            long vertices=0,bytes=0,area=0;int count=0,detail=0,regions=0,commands=0;long gpuVertices=0;
            for(var t:snapshot.tiles())if(t.lodLevel()==level){vertices+=t.vertexCount();bytes+=t.residentMeshBytes();area+=(long)t.tileSize()*t.tileSize();count++;if(t.stage()==WorldgenTileStage.ADAPTIVE_DETAIL)detail++;}
            for(var p:EverviewGpuRegionCache.renderState().regions())if(p.region().lodLevel()==level){regions++;commands+=p.commands().ranges().size();gpuVertices+=(long)p.region().indexCount()*2/3;}
            out.add(String.format(Locale.ROOT,"L%d %d tiles (%d adaptive) | %,d verts / %.1f MiB CPU | %.1fx vs 1b tops | GPU %d regions / %d commands / %,d verts/region",
                    level,count,detail,vertices,bytes/1048576.0,vertices==0?0:area*4.0/vertices,regions,commands,regions==0?0:gpuVertices/regions));
        }
        out.add("Seam indices "+EverviewFrameProfiler.seamIndices+" | GPU "+String.format(Locale.ROOT,"%.1f MiB",EverviewGpuRegionCache.stats().residentBytes()/1048576.0));
        out.add(EverviewRenderer.ownershipProbe());
        return out;
    }
}
