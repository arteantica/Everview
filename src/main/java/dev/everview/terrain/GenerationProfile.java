package dev.everview.terrain;

import java.util.concurrent.atomic.LongAdder;

/** Aggregated generation-only counters; rendered at the existing 4 Hz HUD refresh. */
public final class GenerationProfile {
    public static final LongAdder noiseNanos=new LongAdder(), noiseColumns=new LongAdder(),
            biomeNanos=new LongAdder(), biomeCalls=new LongAdder(), materialNanos=new LongAdder(),
            meshNanos=new LongAdder(), meshes=new LongAdder(), meshQuads=new LongAdder(),
            queueNanos=new LongAdder(), workerNanos=new LongAdder(), workerTasks=new LongAdder(),
            integrated=new LongAdder(), detailCells1=new LongAdder(), detailCells2=new LongAdder(), detailCells4=new LongAdder(), detailCellsCoarse=new LongAdder();
    public static volatile long started=System.nanoTime();
    private GenerationProfile(){}
    public static void reset(){for(var c:new LongAdder[]{noiseNanos,noiseColumns,biomeNanos,biomeCalls,materialNanos,meshNanos,meshes,meshQuads,queueNanos,workerNanos,workerTasks,integrated,detailCells1,detailCells2,detailCells4,detailCellsCoarse})c.reset();started=System.nanoTime();}
}
