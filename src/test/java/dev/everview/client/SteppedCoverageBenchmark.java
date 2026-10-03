package dev.everview.client;

import dev.everview.core.AdaptiveSurfaceMesh;
import dev.everview.terrain.*;
import java.util.*;

/** Identical prefilled sources: frozen M9.7.4 coverage algorithm vs production
 * stepped coverage plus actual ownership packing. Measures CPU/geometry, not FPS. */
public final class SteppedCoverageBenchmark {
    public static void main(String[] args)throws Exception {
        MinecraftTerrainFixture.bootstrap();long sourceColumns=GenerationProfile.noiseColumns.sum();
        System.out.println("Prefilled lattices, production meshing and ownership packing; synthetic terrains; no worldgen, GPU or FPS claim.");
        var cases=new ArrayList<Case>();
        for(String kind:List.of("flat","terraces","rough","coast"))for(int level=2;level<=6;level++){
            int size=level==2?128:128<<(level-2),spacing=level==2?2:1<<(level-2);
            var input=new SteppedTerrainFixture(level,size,spacing,-1,-1,(x,z)->switch(kind){
                case "flat"->80;
                case "terraces"->80+Math.floorDiv(Math.floorMod(x,128),32)*4;
                case "coast"->x< -size/2?40:80+Math.floorMod(z/spacing,8);
                default->80+(int)Math.round(25*Math.sin(x/80.0)+16*Math.cos(z/48.0));
            },(x,z)->kind.equals("coast")&&x< -size/2?63:0);
            cases.add(new Case(kind,input));
        }
        // Warm both implementations across every topology before timing either one.
        for(int warm=0;warm<5;warm++)for(var sample:cases){legacy(sample.input);sample.input.build();}
        for(var sample:cases){
            var input=sample.input;String kind=sample.kind;int level=input.level,spacing=input.spacing;
            double[] oldTime=new double[7],newTime=new double[7],packTime=new double[7];WorldgenSurfaceTile old=null,next=null;EverviewGpuTileCache.PreparedGeometry packed=null;
            for(int trial=0;trial<7;trial++){
                long begin;
                if((trial&1)==0){begin=System.nanoTime();old=legacy(input);oldTime[trial]=(System.nanoTime()-begin)/1e6;begin=System.nanoTime();next=input.build();newTime[trial]=(System.nanoTime()-begin)/1e6;}
                else{begin=System.nanoTime();next=input.build();newTime[trial]=(System.nanoTime()-begin)/1e6;begin=System.nanoTime();old=legacy(input);oldTime[trial]=(System.nanoTime()-begin)/1e6;}
                begin=System.nanoTime();packed=EverviewGpuTileCache.prepareRegionGeometry(next,input.originX,input.originZ,0);packTime[trial]=(System.nanoTime()-begin)/1e6;
            }
            Arrays.sort(oldTime);Arrays.sort(newTime);Arrays.sort(packTime);
            if(next.cellCount()>3*input.cells*input.cells)throw new AssertionError("unbounded source mesh");
            if(kind.equals("flat")&&level>=4&&next.cellCount()!=old.cellCount())throw new AssertionError("flat far terrain no longer collapses");
            int[] v=next.vertices();for(int q=0;q<v.length;q+=12)if(v[q+6]>v[q]&&v[q+8]>v[q+2])for(int c=1;c<4;c++)if(v[q+1]!=v[q+c*3+1])throw new AssertionError("sloped terrain top");
            System.out.printf(Locale.ROOT,"%s L%d (%db): old %.3f ms / %,d quads; stepped %.3f ms / %,d quads / %.2f MiB CPU; ownership pack %.3f ms / %.2f MiB vertices / %d batches.%n",
                    kind,level,spacing,oldTime[3],old.cellCount(),newTime[3],next.cellCount(),next.residentMeshBytes()/1048576.0,packTime[3],packed.colors().length*16/1048576.0,packed.drawBatches().size());
        }
        if(GenerationProfile.noiseColumns.sum()!=sourceColumns)throw new AssertionError("remeshing triggered source generation");
        System.out.println("PASS: every stepped top level, <=3 quads/source cell, flat far compression retained, no added source/noise requests.");
    }
    private record Case(String kind,SteppedTerrainFixture input) {}
    /** Frozen old production buildAdaptiveMesh, including the same triangle lighting. */
    private static WorldgenSurfaceTile legacy(SteppedTerrainFixture in){
        Mesh mesh=new Mesh(Math.max(16,in.cells*in.cells/2));double error=switch(in.level){case 2,3->.5;case 4->1;case 5->2;default->4;};
        new AdaptiveSurfaceMesh(in.cells,in.spacing,63,in.heights,in.materials,in.colors,in.wet,in.fluid,error,
                (x0,z0,x1,z1,y00,y01,y11,y10,material,color)->{
                    double dx=((y10+y11)-(y00+y01))*.5/Math.max(1,x1-x0),dz=((y01+y11)-(y00+y10))*.5/Math.max(1,z1-z0);
                    double normal=Math.sqrt(dx*dx+1+dz*dz);float shade=material==MinecraftSurfacePalette.MATERIAL_WATER?.96f:(float)(.74+Math.max(0,(dx*.45+.86+dz*.24)/normal)*.28);
                    mesh.add(in.originX+x0,y00,in.originZ+z0,in.originX+x0,y01,in.originZ+z1,in.originX+x1,y11,in.originZ+z1,in.originX+x1,y10,in.originZ+z0,MinecraftSurfacePalette.applyLighting(color,shade),material);
                }).build();
        return new WorldgenSurfaceTile(in.level,in.originX/in.size,in.originZ/in.size,in.size,in.spacing,WorldgenTileStage.COVERAGE,
                Arrays.copyOf(mesh.xyz,mesh.quads*12),Arrays.copyOf(mesh.rgb,mesh.quads*4),Arrays.copyOf(mesh.mat,mesh.quads*4),mesh.quads,-64,320,63,0);
    }
    private static final class Mesh {
        int[] xyz,rgb;byte[] mat;int quads;
        Mesh(int count){xyz=new int[count*12];rgb=new int[count*4];mat=new byte[count*4];}
        void add(int x0,int y0,int z0,int x1,int y1,int z1,int x2,int y2,int z2,int x3,int y3,int z3,int color,byte material){
            if((quads+1)*12>xyz.length){xyz=Arrays.copyOf(xyz,xyz.length*2);rgb=Arrays.copyOf(rgb,rgb.length*2);mat=Arrays.copyOf(mat,mat.length*2);}
            int p=quads*12;xyz[p]=x0;xyz[p+1]=y0;xyz[p+2]=z0;xyz[p+3]=x1;xyz[p+4]=y1;xyz[p+5]=z1;xyz[p+6]=x2;xyz[p+7]=y2;xyz[p+8]=z2;xyz[p+9]=x3;xyz[p+10]=y3;xyz[p+11]=z3;
            Arrays.fill(rgb,quads*4,quads*4+4,color);Arrays.fill(mat,quads*4,quads*4+4,material);quads++;
        }
    }
}
