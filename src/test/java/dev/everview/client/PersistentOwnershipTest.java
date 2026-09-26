package dev.everview.client;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class PersistentOwnershipTest {
    private static EverviewGpuRegionCache.GpuRegion region(int level,int tileX,int tileZ) {
        int size=level<=2?128:256;
        var tile=new WorldgenSurfaceTile(level,tileX,tileZ,size,2,WorldgenTileStage.COVERAGE,
                new int[12],new int[4],new byte[4],1,60,80,63,1);
        var batches=new ArrayList<EverviewGpuTileCache.DrawBatch>();int index=0;
        for(int z=tile.minZ();z<tile.maxZ();z+=128)for(int x=tile.minX();x<tile.maxX();x+=128){
            batches.add(new EverviewGpuTileCache.DrawBatch(index,6,level<=3,true,x/16,z/16,4,false,0,0,true,Math.floorDiv(x,128),Math.floorDiv(z,128)));index+=6;
        }
        var region=new EverviewGpuRegionCache.GpuRegion(new EverviewGpuRegionCache.RegionKey(level,0,0),null,index,64,0,0,List.of(tile),new ArrayList<>());
        region.tileViews().add(new EverviewGpuRegionCache.GpuTile(tile,region,0,index,batches,new TerrainSurfaceData(new byte[0],new int[0]),new TerrainEdges(Map.of(),0)));
        return region;
    }
    @Test void reuseReplacementAndRetirementNeverSubmitBothOwners() {
        var parent=region(3,0,0);var child=region(2,0,0);
        var initial=EverviewRenderState.build(List.of(parent),EverviewRenderState.EMPTY,0,0);
        assertEquals(24,initial.cached().get(parent).commands().ranges().getFirst().count());
        var refined=EverviewRenderState.build(List.of(parent,child),initial,0,0);
        assertEquals(List.of(new EverviewRenderState.Range(6,18)),refined.cached().get(parent).commands().ranges());
        assertEquals(6,refined.cached().get(child).commands().ranges().getFirst().count());
        // In-flight state did not mutate commands currently used by the previous frame.
        assertEquals(24,initial.cached().get(parent).commands().ranges().getFirst().count());
        var unchanged=EverviewRenderState.build(List.of(parent,child),refined,0,0);
        assertSame(refined.cached().get(parent),unchanged.cached().get(parent));
        assertSame(refined.cached().get(child).commands(),unchanged.cached().get(child).commands());
        var replacement=region(2,0,0);
        var replaced=EverviewRenderState.build(List.of(parent,replacement),unchanged,0,0);
        assertFalse(replaced.cached().containsKey(child));assertSame(unchanged.cached().get(parent),replaced.cached().get(parent));
        var restored=EverviewRenderState.build(List.of(parent),replaced,0,0);
        assertEquals(24,restored.cached().get(parent).commands().ranges().getFirst().count());
    }
}
