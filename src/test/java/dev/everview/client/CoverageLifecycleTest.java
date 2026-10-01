package dev.everview.client;

import dev.everview.terrain.*;
import dev.everview.core.LodTileKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.*;
import org.junit.jupiter.api.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise the production sampler's worker-to-client publication lifecycle.
 * Only height results are supplied: real appearance, meshing and integration run. */
class CoverageLifecycleTest {
    private static final Class<?> SAMPLER=WorldgenSurfaceSampler.class;
    private static final Class<?> JOB=nested("GenerationJob"), HEIGHTS=nested("HeightBatchResult");
    private TerrainSourceStore source;
    private ExecutorService workers;
    private BiomeResolver resolver;
    private long epoch;

    private static Class<?> nested(String name) {
        return Arrays.stream(SAMPLER.getDeclaredClasses()).filter(c->c.getSimpleName().equals(name)).findFirst().orElseThrow();
    }
    private static Object get(Object instance,String name) throws Exception {
        Field f=(instance==null?SAMPLER:JOB).getDeclaredField(name); f.setAccessible(true);return f.get(instance);
    }
    private static void set(Object instance,String name,Object value) throws Exception {
        Field f=(instance==null?SAMPLER:JOB).getDeclaredField(name);f.setAccessible(true);f.set(instance,value);
    }
    @SuppressWarnings("unchecked") private static Map<Object,Object> jobs() throws Exception {return (Map<Object,Object>)get(null,"DETACHED_COVERAGE_JOBS");}
    @SuppressWarnings("unchecked") private static Map<Object,WorldgenSurfaceTile> cache() throws Exception {return (Map<Object,WorldgenSurfaceTile>)get(null,"CACHE");}
    private static void drain() throws Exception {Method m=SAMPLER.getDeclaredMethod("drainCompleted");m.setAccessible(true);m.invoke(null);}

    @BeforeEach void setup() throws Exception {
        MinecraftTerrainFixture.bootstrap();
        var biome=VanillaRegistries.createWorldLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        resolver=(x,y,z)->biome;
        epoch=(long)get(null,"epoch")+1;set(null,"epoch",epoch);
        source=new TerrainSourceStore(null,256);set(null,"terrainSource",source);
        workers=Executors.newFixedThreadPool(2);
    }
    @AfterEach void cleanup() throws Exception {
        set(null,"epoch",epoch+1);set(null,"currentJob",null);
        workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));
        jobs().clear();cache().clear();((Queue<?>)get(null,"COMPLETED")).clear();
        ((Map<?,?>)get(null,"DISK_DIRTY_TILES")).clear();
        ((Map<?,?>)get(null,"ACCOUNTED_MESH_BYTES")).clear();set(null,"cacheResidentBytes",0L);
        source.close();set(null,"terrainSource",null);
    }
    private Object job(int x,int floor) throws Exception {
        Constructor<?> ctor=JOB.getDeclaredConstructors()[0];ctor.setAccessible(true);
        Object job=ctor.newInstance(new LodTileKey(2,x,0),new WorldgenLodRing(2,0,2048,128,16),16,false,null,false);
        set(job,"detachedCoverage",true);set(job,"biomeResolver",resolver);set(job,"asyncStartedNanos",System.nanoTime());
        int count=(int)get(job,"totalSamples");int[] indices=new int[count],heights=new int[count];
        for(int i=0;i<count;i++){indices[i]=i;heights[i]=TerrainNoiseBatch.pack(floor,(i%2==0 && floor<63)?63:floor);}
        Constructor<?> result=HEIGHTS.getDeclaredConstructors()[0];result.setAccessible(true);
        set(job,"asyncHeightFuture",CompletableFuture.completedFuture(result.newInstance(indices,heights)));
        jobs().put(get(job,"key"),job);return job;
    }
    private void assemble(Object job) throws Exception {
        Method m=SAMPLER.getDeclaredMethod("chainCoverageAssembly",JOB,int.class,long.class,ExecutorService.class);m.setAccessible(true);
        m.invoke(null,job,63,epoch,workers);((CompletableFuture<?>)get(job,"asyncCoverageFuture")).get(5,TimeUnit.SECONDS);
    }
    @Test void multipleCoverageTilesPublishWhileTheCurrentLaneRemainsBlocked() throws Exception {
        Object blocked=job(99,80);jobs().remove(get(blocked,"key"));set(blocked,"detachedCoverage",false);
        set(blocked,"asyncHeightFuture",new CompletableFuture<>());set(null,"currentJob",blocked);
        Object a=job(-1,40),b=job(1,70);
        assemble(a);assemble(b);
        assertSame(blocked,get(null,"currentJob"));assertEquals(2,((Queue<?>)get(null,"COMPLETED")).size());
        assertEquals(2,jobs().size(),"producer slots stay bounded until integration");
        drain();assertEquals(2,cache().size());assertTrue(jobs().isEmpty());assertSame(blocked,get(null,"currentJob"));
        assertEquals(40,cache().get(get(a,"key")).minY());
    }
    @Test void obsoleteCompletionCannotPublishOrRemoveAReplacementWithTheSameKey() throws Exception {
        Object old=job(0,40);assemble(old);
        Object replacement=job(0,100);drain();
        assertTrue(cache().isEmpty());assertSame(replacement,jobs().get(get(replacement,"key")));
        assemble(replacement);drain();
        assertEquals(100,cache().get(get(replacement,"key")).minY());assertTrue(jobs().isEmpty());
    }
    @Test void cancellationAndWorldChangeRejectLateMeshPublication() throws Exception {
        Object cancelled=job(0,40);set(cancelled,"cancelled",true);assemble(cancelled);
        assertTrue(((Queue<?>)get(null,"COMPLETED")).isEmpty());
        Object late=job(1,40);assemble(late);set(null,"epoch",epoch+1);drain();
        assertTrue(cache().isEmpty());
    }
    @Test void idleExactCapacityCanServeCoverageButExactReservationsAndFramePressureLimitIt() throws Exception {
        double frame=(double)get(null,"clientFrameMs");
        @SuppressWarnings("unchecked") var exact=(Map<Object,Object>)get(null,"DETACHED_EXACT_JOBS");
        Method budget=SAMPLER.getDeclaredMethod("activeCoverageJobBudget");budget.setAccessible(true);
        Method lane=SAMPLER.getDeclaredMethod("coverageExecutor");lane.setAccessible(true);
        try {
            set(null,"clientFrameMs",0.0);
            int heightWorkers=(int)get(null,"EXACT_HEIGHT_WORKERS"),coverageWorkers=(int)get(null,"COVERAGE_HEIGHT_WORKERS");
            assertEquals(heightWorkers+coverageWorkers,budget.invoke(null));
            for(int i=0;i<coverageWorkers;i++)set(job(i,70),"coverageExecutor",get(null,"COVERAGE_HEIGHT_EXECUTOR"));
            assertSame(get(null,"EXACT_HEIGHT_EXECUTOR"),lane.invoke(null),"idle exact workers must be reachable by coverage");
            exact.put(new LodTileKey(1,100,0),new CompletableFuture<>());
            exact.put(new LodTileKey(1,101,0),new CompletableFuture<>());
            assertEquals(coverageWorkers,budget.invoke(null),"coverage must leave exact workers reserved");
            exact.clear();set(null,"clientFrameMs",15.0);
            assertEquals(1,budget.invoke(null),"frame pressure must throttle new coverage producers");
        } finally {exact.clear();set(null,"clientFrameMs",frame);}
    }
}
