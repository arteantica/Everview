package dev.everview.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Vector3fc;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** Persistent region submission with a shared live vanilla mask for terrain, water and seams.
 * Spatial residency and LOD ownership are independent of camera visibility. */
public final class EverviewRenderer {
    public static boolean renderEnabled = true;
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

    private static final double BASE_TERRAIN_BIAS = 0.0D;
    private static final double VANILLA_OWNERSHIP_MARGIN_BLOCKS = 64.0D;
    private static final int[] SURFACE_PROBE_X = {8, 2, 13, 2, 13};
    private static final int[] SURFACE_PROBE_Z = {8, 2, 2, 13, 13};
    private static final boolean SODIUM = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");

    private static int lastVanillaOwnedBatches;
    private static int lastFinerOwnedBatches;
    private static int lastVisibleLodBatches;
    private static int lastLoadedWaitingBatches;

    private static ClientLevel compileHintLevel;
    private static final Map<EverviewGpuRegionCache.GpuRegion, HandoffPlan> HANDOFF_PLANS = new IdentityHashMap<>();
    private static EverviewRenderState previousState = EverviewRenderState.EMPTY;

    private EverviewRenderer() {
    }

    /**
     * GPU uploads happen during COLLECT_SUBMITS, before the opaque terrain
     * RenderPass begins. The actual draw hook is a small LevelRenderer mixin
     * that receives Minecraft's already-open opaque RenderPass.
     */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null) { EverviewGpuRegionCache.clearIfInactive(); VanillaOwnershipBuffer.clear(); }
        });
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            Minecraft client = Minecraft.getInstance();

            if (client.level == null || client.player == null) {
                return;
            }

            if (!renderEnabled) { EverviewFrameProfiler.begin(); return; }
            WorldgenSurfaceSnapshot snapshot = WorldgenSurfaceSampler.snapshot();
            {
                Camera camera = client.gameRenderer.mainCamera();
                EverviewGpuRegionCache.prepareFrame(
                        client.level,
                        snapshot,
                        camera
                );
            }
        });
    }

    public static void drawPersistentTerrain(RenderPass renderPass) {
        long frameStarted = System.nanoTime();
        Minecraft client = Minecraft.getInstance();
        Camera camera = client.gameRenderer.mainCamera();
        if (client.level == null || client.player == null || !camera.isInitialized()) return;
        if (!renderEnabled) { EverviewMetrics.beginRenderFrame(); EverviewFrameProfiler.finishDraw(frameStarted); return; }
        if (client.level != compileHintLevel) {
            HANDOFF_PLANS.clear();
            compileHintLevel = client.level;
        }
        EverviewRenderState state = EverviewGpuRegionCache.renderState();
        if (state != previousState) {
            Set<EverviewGpuRegionCache.GpuRegion> live = new HashSet<>();
            for (var plan : state.regions()) live.add(plan.region());
            HANDOFF_PLANS.keySet().retainAll(live);
            previousState = state;
        }
        EverviewMetrics.beginRenderFrame();
        lastVanillaOwnedBatches = lastLoadedWaitingBatches = lastVisibleLodBatches = 0;
        lastFinerOwnedBatches = state.maskedBatches();
        // Ownership was prepared from this frame's vanilla drawable state before the opaque pass.
        updateEnvironmentColorModulator(client, camera);
        double x = camera.position().x(), y = camera.position().y(), z = camera.position().z();
        double vanillaRadius = client.options.getEffectiveRenderDistance() * 16.0;
        var frustum = camera.getCullFrustum();
        EverviewTerrainBackend backend = EverviewTerrainBackend.active();
        renderPass.setPipeline(RenderSystem.getCompiledPipeline(backend.pipeline()));
        RenderSystem.bindDefaultUniforms(renderPass);
        VanillaOwnershipBuffer.bind(renderPass);
        RenderSystem.AutoStorageIndexBuffer quadIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);

        for (var plan : state.regions()) {
            long stage = System.nanoTime();
            boolean visible = frustum.isVisible(plan.bounds());
            EverviewFrameProfiler.visibility += System.nanoTime() - stage;
            if (!visible) {
                for (var tile : plan.tiles()) EverviewMetrics.recordCulledTile(tile.tile().source().lodLevel());
                continue;
            }
            boolean handoff = plan.region().lodLevel() <= 3 && intersects(plan.bounds(), x, z, vanillaRadius + VANILLA_OWNERSHIP_MARGIN_BLOCKS);
            EverviewRenderState.NativeCommands commands = plan.commands();
            if (handoff) {
                stage = System.nanoTime();
                HandoffPlan dynamic = HANDOFF_PLANS.computeIfAbsent(plan.region(), ignored -> new HandoffPlan());
                long commandsBefore = EverviewFrameProfiler.commands;
                commands = dynamic.update(client, plan, x, y, z, vanillaRadius);
                EverviewFrameProfiler.ownership += System.nanoTime() - stage - (EverviewFrameProfiler.commands - commandsBefore);
            }
            if (commands.ranges().isEmpty()) continue;
            stage = System.nanoTime();
            var region = plan.region();
            GpuBuffer indexBuffer = quadIndices.getBuffer(region.indexCount());
            // Each region retains independent transform/index state; never reuse mutable draw data.
            Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
            modelView.translate((float) (region.originX() - x), (float) (-y - BASE_TERRAIN_BIAS), (float) (region.originZ() - z));
            MODEL_OFFSET.set(region.originX()-VanillaOwnershipBuffer.mask.originBlockX(),0,region.originZ()-VanillaOwnershipBuffer.mask.originBlockZ());
            GpuBufferSlice transform = RenderSystem.getDynamicUniforms().writeTransform(modelView, COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
            renderPass.setVertexBuffer(0, region.vertexBuffer().slice());
            renderPass.setIndexBuffer(indexBuffer, quadIndices.type());
            renderPass.setUniform("DynamicTransforms", transform);
            backend.bindRegion(renderPass, plan);
            if (commands.ranges().size() == 1) {
                var range = commands.ranges().getFirst();
                renderPass.drawIndexed(range.count(), 1, range.first(), 0, 0);
            } else {
                renderPass.multiDrawIndexed(quadIndices.type().bytes == 2 ? commands.offsets16() : commands.offsets32(),
                        commands.counts(), commands.baseVertices(), commands.ranges().size());
            }
            EverviewFrameProfiler.submission += System.nanoTime() - stage;
            EverviewMetrics.recordDrawCall(handoff);
            for (var tile : plan.tiles()) {
                var source = tile.tile().source();
                EverviewMetrics.recordSubmission(source.lodLevel());
                EverviewMetrics.recordTileDraw(source.lodLevel(), 0, 0,
                        Math.hypot((source.minX() + source.maxX()) * .5 - x, (source.minZ() + source.maxZ()) * .5 - z));
            }
            EverviewMetrics.recordSubmittedQuads(region.lodLevel(), commands.quads());
            lastVisibleLodBatches += commands.ranges().size();
        }
        var stitches = EverviewGpuRegionCache.stitches();
        if (stitches != null) {
            long started = System.nanoTime();
            GpuBuffer indices = quadIndices.getBuffer(stitches.indices());
            Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
            modelView.translate((float)(stitches.originX()-x), (float)(-y-BASE_TERRAIN_BIAS), (float)(stitches.originZ()-z));
            renderPass.setVertexBuffer(0, stitches.vertices().slice());
            renderPass.setIndexBuffer(indices, quadIndices.type());
            MODEL_OFFSET.set(stitches.originX()-VanillaOwnershipBuffer.mask.originBlockX(),0,stitches.originZ()-VanillaOwnershipBuffer.mask.originBlockZ());
            renderPass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(modelView,COLOR_MODULATOR,MODEL_OFFSET,TEXTURE_MATRIX));
            backend.bindStitches(renderPass, stitches);
            renderPass.drawIndexed(stitches.indices(),1,0,0,0);
            EverviewMetrics.recordDrawCall(false);
            EverviewFrameProfiler.submission += System.nanoTime() - started;
        }
        EverviewMetrics.recordRenderCpuNanos(System.nanoTime() - frameStarted);
        EverviewFrameProfiler.finishDraw(frameStarted);
    }

    private static final BlockPos.MutableBlockPos OWNERSHIP_PROBE = new BlockPos.MutableBlockPos();
    public static void prepareVanillaOwnership() {
        Minecraft client = Minecraft.getInstance();
        if (!renderEnabled || client.level == null || client.player == null) return;
        Camera camera = client.gameRenderer.mainCamera();
        long start=System.nanoTime();
        if(client.level!=compileHintLevel){HANDOFF_PLANS.clear();compileHintLevel=client.level;}
        double x=camera.position().x(),z=camera.position().z();
        int cx=Math.floorDiv((int)Math.floor(x),16),cz=Math.floorDiv((int)Math.floor(z),16);
        var mask=VanillaOwnershipBuffer.mask;mask.begin(cx,cz);
        int radius=Math.min(127,client.options.getEffectiveRenderDistance()+2);
        // No maps/record allocations per column. Cost is bounded by vanilla's local
        // draw distance, independent of the generated 16K source/mesh working set.
        for(int dz=-radius;dz<=radius;dz++)for(int dx=-radius;dx<=radius;dx++){
            int bx=cx+dx,bz=cz+dz;
            LevelChunk chunk=client.level.getChunkSource().getChunkNow(bx,bz);
            if(chunk==null)continue;
            boolean drawable=true;int previousSection=Integer.MIN_VALUE;
            for(int i=0;i<SURFACE_PROBE_X.length;i++){
                int sy=Math.floorDiv(chunk.getHeight(Heightmap.Types.WORLD_SURFACE,SURFACE_PROBE_X[i],SURFACE_PROBE_Z[i]),16);
                if(sy==previousSection)continue;previousSection=sy;
                OWNERSHIP_PROBE.set(bx*16+8,sy*16+8,bz*16+8);
                // Sodium 0.9.2's override reads installed region flags; its uploadResults
                // transaction has finished at this hook. Vanilla needs the actual GPU slices.
                boolean uploaded = SODIUM
                        ? client.levelRenderer.isSectionCompiledAndVisible(OWNERSHIP_PROBE,0L)
                        : ((VanillaTerrainReadiness)client.levelRenderer).everview$terrainUploaded(OWNERSHIP_PROBE);
                if(!uploaded){drawable=false;break;}
            }
            if(drawable)mask.own(bx,bz);
        }
        VanillaOwnershipBuffer.upload();VanillaOwnershipBuffer.prepareNanos=System.nanoTime()-start;
        EverviewFrameProfiler.prepareTotal+=VanillaOwnershipBuffer.prepareNanos;
    }

    private static boolean intersects(AABB bounds, double x, double z, double radius) {
        double dx = Math.max(bounds.minX - x, Math.max(0, x - bounds.maxX));
        double dz = Math.max(bounds.minZ - z, Math.max(0, z - bounds.maxZ));
        return dx * dx + dz * dz <= radius * radius;
    }

    /** Only vanilla's small, live handoff band needs readiness probes each frame. */
    private static final class HandoffPlan {
        private BitSet owned = new BitSet();
        private BitSet scratch = new BitSet();
        private EverviewRenderState.NativeCommands commands;
        private EverviewRenderState.RegionCommands previousPlan;

        private EverviewRenderState.NativeCommands update(Minecraft client, EverviewRenderState.RegionCommands plan,
                                                           double x, double y, double z, double radius) {
            if (previousPlan != plan) { commands = null; previousPlan = plan; }
            scratch.clear();
            int index = 0;
            for (var tile : plan.tiles()) {
                boolean near = intersects(tile.bounds(), x, z, radius + VANILLA_OWNERSHIP_MARGIN_BLOCKS);
                for (var batch : tile.batches()) {
                    if (near && batch.vanillaSensitive()) {
                        var result = vanillaOwnsBatch(batch);
                        if (result.owned()) { scratch.set(index); lastVanillaOwnedBatches++; }
                        if (result.loadedWaiting()) lastLoadedWaitingBatches++;
                    }
                    index++;
                }
            }
            if (commands == null || !scratch.equals(owned)) {
                long start = System.nanoTime();
                if (scratch.isEmpty()) commands = plan.commands();
                else {
                    List<EverviewRenderState.Range> ranges = new ArrayList<>();
                    index = 0;
                    for (var tile : plan.tiles()) for (var batch : tile.batches()) {
                        if (!scratch.get(index++)) EverviewRenderState.append(ranges, batch.firstIndex(), batch.indexCount());
                    }
                    commands = EverviewRenderState.NativeCommands.build(ranges);
                }
                BitSet swap = owned; owned = scratch; scratch = swap;
                EverviewFrameProfiler.commands += System.nanoTime() - start;
            }
            return commands;
        }
    }

    public static String ownershipProbe() {
        Minecraft client=Minecraft.getInstance();
        if(client.hitResult==null)return "Ownership probe: aim at terrain";
        var hit=client.hitResult.getLocation();int x=(int)Math.floor(hit.x),z=(int)Math.floor(hit.z);
        int level=EverviewGpuRegionCache.renderState().coverage().levelAt(Math.floorDiv(x,128),Math.floorDiv(z,128));
        String source="none";
        for(var p:EverviewGpuRegionCache.renderState().regions())for(var t:p.tiles())if(t.tile().source().lodLevel()==level){var v=t.tile().source();if(x>=v.minX()&&x<v.maxX()&&z>=v.minZ()&&z<v.maxZ())source="L"+level+" tile "+v.tileX()+","+v.tileZ()+" "+v.stage()+" buffer#"+Integer.toHexString(System.identityHashCode(p.region()))+" range "+t.tile().firstIndex()+"+"+t.tile().indexCount();}
        return "Aim chunk "+Math.floorDiv(x,16)+","+Math.floorDiv(z,16)+" owner "+(VanillaOwnershipBuffer.mask.owns(Math.floorDiv(x,16),Math.floorDiv(z,16))?"VANILLA":"LOD")+" | "+source;
    }

    public static OwnershipStats ownershipStats() {
        return new OwnershipStats(
                lastVanillaOwnedBatches,
                lastFinerOwnedBatches,
                lastVisibleLodBatches,
                lastLoadedWaitingBatches
        );
    }

    private static void updateEnvironmentColorModulator(
            Minecraft client,
            Camera camera
    ) {
        if (client.level == null) {
            COLOR_MODULATOR.set(1.0F, 1.0F, 1.0F, 1.0F);
            return;
        }

        float partialTick = client.getDeltaTracker()
                .getGameTimeDeltaPartialTick(false);

        float skyFactor = camera.attributeProbe().getValue(
                EnvironmentAttributes.SKY_LIGHT_FACTOR,
                partialTick
        );
        skyFactor = Math.max(0.0F, Math.min(1.0F, skyFactor));

        float rain = Math.max(
                0.0F,
                Math.min(1.0F, client.level.getRainLevel(partialTick))
        );
        float thunder = Math.max(
                0.0F,
                Math.min(1.0F, client.level.getThunderLevel(partialTick))
        );

        Vector3fc skyLightColor = camera.attributeProbe().getValue(
                EnvironmentAttributes.SKY_LIGHT_COLOR,
                partialTick
        );
        float sr = skyLightColor.x();
        float sg = skyLightColor.y();
        float sb = skyLightColor.z();

        float brightness = 0.24F + skyFactor * 0.76F;
        brightness *= 1.0F - rain * 0.18F;
        brightness *= 1.0F - thunder * 0.28F;

        float tintMix = 0.18F;
        float night = 1.0F - skyFactor;
        float r = brightness
                * ((1.0F - tintMix) + sr * tintMix)
                * (1.0F - night * 0.10F);
        float g = brightness
                * ((1.0F - tintMix) + sg * tintMix)
                * (1.0F - night * 0.06F);
        float b = brightness
                * ((1.0F - tintMix) + sb * tintMix);

        COLOR_MODULATOR.set(r, g, b, 1.0F);
    }

    public static void noteRecentlyCompiledSection(BlockPos origin) {
        // Readiness is queried from the live renderer each frame; upload notifications
        // alone are deliberately insufficient to grant final ownership.
    }

    private static ColumnOwnershipResult vanillaOwnsBatch(EverviewGpuTileCache.DrawBatch batch) {
        var mask=VanillaOwnershipBuffer.mask;
        boolean owned=mask.owns(batch.chunkAX(),batch.chunkAZ())
                || (batch.boundary()&&mask.owns(batch.chunkBX(),batch.chunkBZ()));
        return owned?ColumnOwnershipResult.OWNED:ColumnOwnershipResult.NOT_OWNED;
    }

    public record OwnershipStats(
            int vanillaOwnedBatches,
            int finerOwnedBatches,
            int visibleLodBatches,
            int loadedWaitingBatches
    ) {
    }

    private record ColumnOwnershipResult(
            boolean owned,
            boolean loadedWaiting
    ) {
        private static final ColumnOwnershipResult OWNED = new ColumnOwnershipResult(true,false);
        private static final ColumnOwnershipResult NOT_OWNED =
                new ColumnOwnershipResult(false, false);
    }

}
