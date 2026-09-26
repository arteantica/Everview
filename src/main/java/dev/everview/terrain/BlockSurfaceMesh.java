package dev.everview.terrain;

/** Greedy, ownership-aligned horizontal terraces and real vertical faces.
 * Exact at quantum 1. Quantum 2 bounds sampled vertical displacement to one block.
 * Never merges differing materials, water states or tint; never emits sloped tops. */
public final class BlockSurfaceMesh {
    @FunctionalInterface public interface Sink {
        void quad(int x0,int y0,int z0,int x1,int y1,int z1,int x2,int y2,int z2,int x3,int y3,int z3,int color,byte material);
    }
    private final int cells,stride,spacing,quantum;private final int[] heights,colors;private final byte[] materials;
    private final boolean[] wet;private final Sink sink;private boolean boundaryWalls=true;private int quads;
    public BlockSurfaceMesh(int cells,int spacing,int[] heights,int[] colors,byte[] materials,boolean[] wet,int quantum,Sink sink){
        if(cells<1||spacing<1||heights.length!=(cells+1)*(cells+1)||colors.length!=heights.length||materials.length!=heights.length||wet.length!=heights.length)
            throw new IllegalArgumentException("matching column lattice required");
        this.cells=cells;this.stride=cells+1;this.spacing=spacing;this.heights=heights;this.colors=colors;this.materials=materials;this.wet=wet;this.quantum=quantum;this.sink=sink;
    }
    public BlockSurfaceMesh boundaryWalls(boolean enabled){boundaryWalls=enabled;return this;}
    private int height(int i){return wet[i]||quantum==1?heights[i]:Math.floorDiv(heights[i]+quantum/2,quantum)*quantum;}
    private boolean same(int a,int b){return height(a)==height(b)&&materials[a]==materials[b]&&colors[a]==colors[b]&&wet[a]==wet[b];}
    public int build(){
        boolean[] used=new boolean[cells*cells];int maxSpan=Math.max(1,128/spacing);
        for(int z=0;z<cells;z++)for(int x=0;x<cells;x++){
            int index=z*stride+x;if(used[z*cells+x])continue;
            int width=1,depth=1,limX=Math.min(cells,((x/maxSpan)+1)*maxSpan),limZ=Math.min(cells,((z/maxSpan)+1)*maxSpan);
            while(x+width<limX&&!used[z*cells+x+width]&&same(index,index+width))width++;
            outer:while(z+depth<limZ){for(int dx=0;dx<width;dx++)if(used[(z+depth)*cells+x+dx]||!same(index,(z+depth)*stride+x+dx))break outer;depth++;}
            for(int dz=0;dz<depth;dz++)java.util.Arrays.fill(used,(z+dz)*cells+x,(z+dz)*cells+x+width,true);
            int a=x*spacing,b=z*spacing,c=(x+width)*spacing,d=(z+depth)*spacing,y=height(index);
            emit(a,y,b,a,y,d,c,y,d,c,y,b,colors[index],materials[index]);
        }
        // Each discontinuity has exactly one owner: west/north columns.
        for(int axis=0;axis<2;axis++)for(int cross=1;cross<=(boundaryWalls?cells:cells-1);cross++){
            int along=0;
            while(along<cells){
                int a=axis==0?along*stride+cross-1:(cross-1)*stride+along;
                int b=a+(axis==0?1:stride),ha=height(a),hb=height(b);
                if(ha==hb){along++;continue;}
                int high=ha>hb?a:b, end=along+1,limit=Math.min(cells,((along/maxSpan)+1)*maxSpan);
                while(end<limit){int na=axis==0?end*stride+cross-1:(cross-1)*stride+end;int nb=na+(axis==0?1:stride),nh=height(na)>height(nb)?na:nb;
                    if(height(na)!=ha||height(nb)!=hb||materials[nh]!=materials[high]||colors[nh]!=colors[high]||wet[nh]!=wet[high])break;end++;}
                int low=Math.min(ha,hb),top=Math.max(ha,hb),c=cross*spacing,s=along*spacing,e=end*spacing;
                int color=shade(colors[high],axis==0?.82:.72);
                if(axis==0)emit(c,low,s,c,low,e,c,top,e,c,top,s,color,materials[high]);
                else emit(s,low,c,e,low,c,e,top,c,s,top,c,color,materials[high]);
                along=end;
            }
        }
        return quads;
    }
    private static int shade(int c,double f){return ((int)(((c>>16)&255)*f)<<16)|((int)(((c>>8)&255)*f)<<8)|(int)((c&255)*f);}
    private void emit(int a,int b,int c,int d,int e,int f,int g,int h,int i,int j,int k,int l,int color,byte material){sink.quad(a,b,c,d,e,f,g,h,i,j,k,l,color,material);quads++;}
}
