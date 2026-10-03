package dev.everview.client;

import dev.everview.core.DrawableCoverage;
import dev.everview.terrain.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class SteppedCoverageTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){MinecraftTerrainFixture.bootstrap();}
    @Test void everyCoverageLevelHasExactlyOneLevelTopPerSampleFootprint()throws Exception {
        for(int level=1;level<=6;level++){
            int size=level<=2?128:128<<(level-2),spacing=level<=2?4:1<<(level-2);
            var input=new SteppedTerrainFixture(level,size,spacing,-1,-1,(x,z)->70+Math.floorMod(x/spacing+z/spacing,5), (x,z)->0);
            var tile=input.build();int[] v=tile.vertices(),visited=new int[input.cells*input.cells];int tops=0,walls=0;
            for(int q=0;q<v.length;q+=12){
                int x0=v[q],z0=v[q+2],x1=v[q+6],z1=v[q+8];
                if(x1>x0&&z1>z0){
                    tops++;for(int corner=1;corner<4;corner++)assertEquals(v[q+1],v[q+corner*3+1],"sloped far top at L"+level);
                    for(int z=z0;z<z1;z+=spacing)for(int x=x0;x<x1;x+=spacing){
                        int gx=(x-input.originX)/spacing,gz=(z-input.originZ)/spacing;
                        assertEquals(input.heights[gz*(input.cells+1)+gx],v[q+1],"sample was averaged/rounded");visited[gz*input.cells+gx]++;
                    }
                }else{
                    walls++;assertTrue(Arrays.stream(new int[]{v[q],v[q+3],v[q+6],v[q+9]}).distinct().count()==1
                            ||Arrays.stream(new int[]{v[q+2],v[q+5],v[q+8],v[q+11]}).distinct().count()==1,"nonvertical wall");
                }
            }
            for(int visits:visited)assertEquals(1,visits,"hole/overlapping top");assertTrue(tops>0);assertTrue(walls>0);
            assertTrue(tile.cellCount()<=3*input.cells*input.cells,"coverage geometry no longer bounded by source lattice");
        }
    }
    @Test void waterKeepsItsFluidHeightAndWetFootprintAtEveryDistance()throws Exception {
        for(int level=2;level<=6;level++){
            int size=level<=2?128:128<<(level-2),spacing=level<=2?2:1<<(level-2);
            var input=new SteppedTerrainFixture(level,size,spacing,0,0,(x,z)->x<size/2?40:80,(x,z)->x<size/2?63:0);
            var tile=input.build();int wetTops=0,dryTops=0;int[] v=tile.vertices();byte[] m=tile.materials();
            for(int q=0;q<v.length;q+=12){
                if(v[q+6]<=v[q]||v[q+8]<=v[q+2])continue;
                if(m[q/3]==MinecraftSurfacePalette.MATERIAL_WATER){wetTops++;assertTrue(v[q+6]<=size/2);assertEquals(63,v[q+1]);}
                else{dryTops++;assertTrue(v[q]>=size/2);assertEquals(80,v[q+1]);}
                for(int c=1;c<4;c++)assertEquals(v[q+1],v[q+c*3+1]);
            }
            assertTrue(wetTops>0&&dryTops>0);
        }
        for(int distance:new int[]{2000,8000,16384})assertEquals(1,DetailPolicy.quantum(distance,900,false));
    }
    @Test void internalOwnershipBoundaryHasOneSeamAndNoDuplicateMeshWall()throws Exception {
        var input=new SteppedTerrainFixture(3,256,8,-1,-1,(x,z)->x< -128?70:83,(x,z)->0);
        var tile=input.build();int[] v=tile.vertices();
        for(int q=0;q<v.length;q+=12)assertFalse(v[q]==-128&&v[q+3]==-128&&v[q+6]==-128&&v[q+9]==-128,"mesh duplicated transactional seam");
        var region=input.region(tile);var coverage=new DrawableCoverage();coverage.add(3,-256,-256,256);
        var seam=TerrainStitches.build(List.of(region),coverage,-256,-256);
        assertEquals(12,seam.indexCount(),"one wall for each of two ownership cells");
        assertEquals(128,seam.vertices().remaining());
        for(int vtx=0;vtx<8;vtx++)assertEquals(128,seam.vertices().getFloat(vtx*16));
    }
    @Test void equalCoverageNeighborsNowJoinButEqualHeightProducesNoWall()throws Exception {
        for(boolean stepped:new boolean[]{true,false}){
            var left=new SteppedTerrainFixture(2,128,4,-1,-1,(x,z)->70,(x,z)->0);
            var right=new SteppedTerrainFixture(2,128,4,0,-1,(x,z)->stepped?80:70,(x,z)->0);
            var coverage=new DrawableCoverage();coverage.add(2,-128,-128,128);coverage.add(2,0,-128,128);
            var seam=TerrainStitches.build(List.of(left.region(left.build()),right.region(right.build())),coverage,0,0);
            assertEquals(stepped?6:0,seam.indexCount(),"same-level COVERAGE skip left a boundary hole");
        }
    }
    @Test void coarseToFineReplacementPublishesItsOwnSeamsAndReusesUnchangedCommands()throws Exception {
        var parent=new SteppedTerrainFixture(3,256,2,0,0,(x,z)->70,(x,z)->0);
        var child=new SteppedTerrainFixture(2,128,1,0,0,(x,z)->80,(x,z)->0);
        var coarse=parent.region(parent.build());var fine=child.region(child.build());
        var initial=EverviewRenderState.build(List.of(coarse),EverviewRenderState.EMPTY,0,0);
        assertEquals(0,initial.stitches().indexCount());
        var replaced=EverviewRenderState.build(List.of(coarse,fine),initial,0,0);
        assertEquals(2,replaced.coverage().levelAt(0,0));assertEquals(3,replaced.coverage().levelAt(1,0));
        assertEquals(12,replaced.stitches().indexCount(),"two joins around replaced fine footprint");
        assertEquals(0,initial.stitches().indexCount(),"old published transaction was mutated");
        var same=EverviewRenderState.build(List.of(coarse,fine),replaced,0,0);
        assertSame(replaced.cached().get(coarse),same.cached().get(coarse));assertSame(replaced.cached().get(fine),same.cached().get(fine));
        var restored=EverviewRenderState.build(List.of(coarse),same,0,0);assertEquals(0,restored.stitches().indexCount());
        assertEquals(3,restored.coverage().levelAt(0,0));
    }
    @Test void compatibleOldExactL1MeshRemainsWarmWhileFarTrianglesRetire()throws Exception {
        var input=new SteppedTerrainFixture(1,128,1,0,0,(x,z)->80+x/16,(x,z)->0);var columns=input.build();
        var exact=new WorldgenSurfaceTile(1,0,0,128,1,WorldgenTileStage.EXACT_APPEARANCE,columns.vertices(),columns.colors(),columns.materials(),columns.cellCount(),80,88,63,0);
        var far=new SteppedTerrainFixture(6,2048,16,0,0,(x,z)->80,(x,z)->0).build();
        WorldgenDiskCache.save(directory,123,"minecraft:overworld",List.of(exact,far));
        try(var files=Files.list(directory)){
            for(var shard:files.filter(p->p.toString().endsWith(".evr.gz")).toList())oldMeshVersion(shard);
        }
        var loaded=WorldgenDiskCache.load(directory,123,"minecraft:overworld").tiles();
        assertEquals(1,loaded.size());assertEquals(WorldgenTileStage.EXACT_APPEARANCE,loaded.getFirst().stage());
        assertArrayEquals(exact.vertices(),loaded.getFirst().vertices());
        WorldgenDiskCache.save(directory,123,"minecraft:overworld",List.of(far));
        assertEquals(2,WorldgenDiskCache.load(directory,123,"minecraft:overworld").tiles().size());
    }
    private static void oldMeshVersion(Path shard)throws IOException {
        byte[] raw;try(var in=new java.util.zip.GZIPInputStream(Files.newInputStream(shard))){raw=in.readAllBytes();}
        java.nio.ByteBuffer.wrap(raw).putInt(4,35);
        try(var out=new java.util.zip.GZIPOutputStream(Files.newOutputStream(shard))){out.write(raw);}
    }
    @Test void oldTriangleMeshCacheIsRejectedWhileSourcePagesAreRetained()throws Exception {
        Path sourcePath=directory.resolve("source-v1-123");
        var loader=new TerrainSourceStore.Loader(){public int column(int x,int z){return 123;}public int[] batch(int x,int z){return new int[256];}};
        var source=new TerrainSourceStore(sourcePath,16);source.sample(0,0,false,loader);source.closeAsync().get();
        var input=new SteppedTerrainFixture(6,2048,16,0,0,(x,z)->70,(x,z)->0);var tile=input.build();
        var saved=WorldgenDiskCache.save(directory,123,"minecraft:overworld",List.of(tile));assertEquals(1,saved.tileCount());
        assertEquals(1,WorldgenDiskCache.load(directory,123,"minecraft:overworld").tiles().size());
        Path shard;try(var files=Files.list(directory)){shard=files.filter(p->p.toString().endsWith(".evr.gz")).findFirst().orElseThrow();}
        oldMeshVersion(shard);
        assertTrue(WorldgenDiskCache.load(directory,123,"minecraft:overworld").tiles().isEmpty());
        try(var retained=new TerrainSourceStore(sourcePath,16)){
            assertEquals(123,retained.sample(0,0,false,new TerrainSourceStore.Loader(){public int column(int x,int z){throw new AssertionError("cache version cleared source");}public int[] batch(int x,int z){throw new AssertionError();}}));
        }
    }
}
