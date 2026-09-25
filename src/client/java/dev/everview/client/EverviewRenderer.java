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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M4 unified hierarchical ownership renderer.
 *
 * M5.5 global safety floor: vanilla handoff is gated by actual renderer
 * readiness AND camera-to-surface 3D reach. The LOD stack is a nested fallback
 * hierarchy, so a coarse floor remains available anywhere a finer level is not
 * resident. This prevents both high-altitude center holes and far-ring gaps.
 * L1/L2/L3 all use the same ownership mask. L2 no longer disappears as an all-or-nothing
 * 128-block tile; each section batch retires as its corresponding 32-block L1
 * tile becomes GPU-resident. L3 keeps its per-L2-region fallback. All masks
 * coalesce adjacent visible ranges before submission.
 *
 * Important 26.3 detail: LevelRenderEvents.AFTER_OPAQUE_TERRAIN fires while
 * Minecraft's opaque terrain RenderPass is still open. Everview therefore
 * draws into that existing pass instead of trying to create a nested pass.
 */
public final class EverviewRenderer {
    public static boolean renderEnabled = true;
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

    // Nested safety floors intentionally overlap while finer coverage streams.
    // Sub-block separation was not enough once the camera was high/far: depth
    // precision made the overlapping floors fight, producing the drought-like
    // cracks and scattered square pixels seen in M5.5. Keep each coarser layer
    // meaningfully below the finer one instead.
    private static final double BASE_TERRAIN_BIAS = 0.35D;
    private static final double RING_LAYER_BIAS = 1.25D;
    private static final long RECENT_COMPILE_HINT_NANOS = 1_500_000_000L;
    private static final long VANILLA_HANDOFF_GRACE_NANOS = 250_000_000L;
    private static final long VANILLA_VISIBLE_STABILITY_NANOS = 350_000_000L;
    private static final long VANILLA_HANDOFF_STATE_TTL_NANOS = 5_000_000_000L;
    private static final int VANILLA_EDGE_NEIGHBORHOOD_BLOCKS = 160;
    private static final double VANILLA_OWNERSHIP_MARGIN_BLOCKS = 64.0D;
    private static final double VANILLA_3D_HANDOFF_MARGIN_BLOCKS = 96.0D;
    private static final double MAX_VANILLA_VERTICAL_HANDOFF_BLOCKS = 512.0D;
    private static final int[] SURFACE_PROBE_X = {8, 2, 13, 2, 13};
    private static final int[] SURFACE_PROBE_Z = {8, 2, 2, 13, 13};
    private static final int L4_UNDERLAY_REGION_SIZE = 256;
    private static final int L5_UNDERLAY_REGION_SIZE = 512;
    private static final int L6_UNDERLAY_REGION_SIZE = 1_024;

    private static int lastVanillaOwnedBatches;
    private static int lastFinerOwnedBatches;
    private static int lastVisibleLodBatches;
    private static int lastLoadedWaitingBatches;

    private static final Map<SectionKey, Long> RECENTLY_COMPILED_SECTIONS =
            new HashMap<>();
    private static final Map<ChunkKey, VanillaHandoffState>
            VANILLA_HANDOFF_STATES = new HashMap<>();
    private static ClientLevel compileHintLevel;
    private static final Map<EverviewGpuRegionCache.GpuRegion, HandoffPlan> HANDOFF_PLANS = new IdentityHashMap<>();
    private static final Map<SectionKey, Boolean> FRAME_VISIBILITY = new HashMap<>();
    private static final Map<ChunkKey, ColumnOwnershipResult> FRAME_COLUMNS = new HashMap<>();
    private static final Map<ChunkKey, Long> COMPILED_COLUMNS = new HashMap<>();
    private static EverviewRenderState previousState = EverviewRenderState.EMPTY;
    private static long lastPrune;

    private EverviewRenderer() {
    }

    /**
     * GPU uploads happen during COLLECT_SUBMITS, before the opaque terrain
     * RenderPass begins. The actual draw hook is a small LevelRenderer mixin
     * that receives Minecraft's already-open opaque RenderPass.
     */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null) EverviewGpuRegionCache.clearIfInactive();
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
            RECENTLY_COMPILED_SECTIONS.clear(); COMPILED_COLUMNS.clear();
            VANILLA_HANDOFF_STATES.clear(); HANDOFF_PLANS.clear();
            compileHintLevel = client.level;
        }
        if (frameStarted - lastPrune > 250_000_000L) {
            pruneCompileHints(); pruneVanillaHandoffStates();
            COMPILED_COLUMNS.values().removeIf(time -> frameStarted - time > RECENT_COMPILE_HINT_NANOS);
            lastPrune = frameStarted;
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
        FRAME_VISIBILITY.clear(); FRAME_COLUMNS.clear();
        updateEnvironmentColorModulator(client, camera);
        double x = camera.position().x(), y = camera.position().y(), z = camera.position().z();
        double vanillaRadius = client.options.getEffectiveRenderDistance() * 16.0;
        var frustum = camera.getCullFrustum();
        EverviewTerrainBackend backend = EverviewTerrainBackend.active();
        renderPass.setPipeline(RenderSystem.getCompiledPipeline(backend.pipeline()));
        RenderSystem.bindDefaultUniforms(renderPass);
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
            renderPass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(modelView,COLOR_MODULATOR,MODEL_OFFSET,TEXTURE_MATRIX));
            backend.bindStitches(renderPass, stitches);
            renderPass.drawIndexed(stitches.indices(),1,0,0,0);
            EverviewMetrics.recordDrawCall(false);
            EverviewFrameProfiler.submission += System.nanoTime() - started;
        }
        EverviewMetrics.recordRenderCpuNanos(System.nanoTime() - frameStarted);
        EverviewFrameProfiler.finishDraw(frameStarted);
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
                        var result = vanillaOwnsBatch(client, batch, FRAME_VISIBILITY, FRAME_COLUMNS, x, y, z);
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

    public static OwnershipStats ownershipStats() {
        return new OwnershipStats(
                lastVanillaOwnedBatches,
                lastFinerOwnedBatches,
                lastVisibleLodBatches,
                lastLoadedWaitingBatches
        );
    }

    private static boolean tileIntersectsVanillaOwnershipArea(
            WorldgenSurfaceTile tile,
            double cameraX,
            double cameraZ,
            double vanillaRadius
    ) {
        double minX = tile.minX();
        double minZ = tile.minZ();
        double maxX = tile.maxX();
        double maxZ = tile.maxZ();

        double nearestX = Math.max(minX, Math.min(cameraX, maxX));
        double nearestZ = Math.max(minZ, Math.min(cameraZ, maxZ));
        double nearestDistance = Math.hypot(
                nearestX - cameraX,
                nearestZ - cameraZ
        );

        // M4.2: not a thin transition band anymore. Every near LOD tile that
        // reaches into the vanilla render radius must evaluate its
        // vanilla-sensitive batches against renderer-visible terrain.
        return nearestDistance
                <= vanillaRadius + VANILLA_OWNERSHIP_MARGIN_BLOCKS;
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
        int chunkX = Math.floorDiv(origin.getX(), 16);
        int sectionY = Math.floorDiv(origin.getY(), 16);
        int chunkZ = Math.floorDiv(origin.getZ(), 16);

        COMPILED_COLUMNS.put(new ChunkKey(chunkX, chunkZ), System.nanoTime());
        RECENTLY_COMPILED_SECTIONS.put(
                new SectionKey(chunkX, sectionY, chunkZ),
                System.nanoTime()
        );
    }

    private static void pruneCompileHints() {
        if (RECENTLY_COMPILED_SECTIONS.isEmpty()) {
            return;
        }

        long now = System.nanoTime();
        RECENTLY_COMPILED_SECTIONS.entrySet().removeIf(
                entry -> now - entry.getValue() > RECENT_COMPILE_HINT_NANOS
        );
    }

    private static boolean recentlyCompiledSection(
            int chunkX,
            int sectionY,
            int chunkZ
    ) {
        Long compiledAt = RECENTLY_COMPILED_SECTIONS.get(
                new SectionKey(chunkX, sectionY, chunkZ)
        );

        return compiledAt != null
                && System.nanoTime() - compiledAt <= RECENT_COMPILE_HINT_NANOS;
    }

    private static ColumnOwnershipResult vanillaOwnsBatch(
            Minecraft client,
            EverviewGpuTileCache.DrawBatch batch,
            Map<SectionKey, Boolean> visibility,
            Map<ChunkKey, ColumnOwnershipResult> columns,
            double cameraX,
            double cameraY,
            double cameraZ
    ) {
        ColumnOwnershipResult aOwned = vanillaChunkColumnOwned(
                client,
                batch.chunkAX(),
                batch.sectionY(),
                batch.chunkAZ(),
                visibility,
                columns,
                cameraX,
                cameraY,
                cameraZ
        );

        if (!batch.boundary()) {
            return aOwned;
        }

        ColumnOwnershipResult bOwned = vanillaChunkColumnOwned(
                client,
                batch.chunkBX(),
                batch.sectionY(),
                batch.chunkBZ(),
                visibility,
                columns,
                cameraX,
                cameraY,
                cameraZ
        );

        return new ColumnOwnershipResult(
                aOwned.owned() || bOwned.owned(),
                aOwned.loadedWaiting() || bOwned.loadedWaiting()
        );
    }

    private static ColumnOwnershipResult vanillaChunkColumnOwned(
            Minecraft client,
            int chunkX,
            int hintSectionY,
            int chunkZ,
            Map<SectionKey, Boolean> visibility,
            Map<ChunkKey, ColumnOwnershipResult> columns,
            double cameraX,
            double cameraY,
            double cameraZ
    ) {
        ChunkKey key = new ChunkKey(chunkX, chunkZ);
        ColumnOwnershipResult cached = columns.get(key);
        if (cached != null) {
            return cached;
        }

        // M5.3: absence is the cheapest and strongest fallback signal. Never
        // probe renderer sections for a chunk the client does not even have.
        // Everview simply remains visible until vanilla arrives.
        LevelChunk chunk = client.level == null
                ? null
                : client.level.getChunkSource().getChunkNow(chunkX, chunkZ);

        if (chunk == null) {
            VANILLA_HANDOFF_STATES.remove(key);
            columns.put(key, ColumnOwnershipResult.NOT_OWNED);
            return ColumnOwnershipResult.NOT_OWNED;
        }

        // Renderer-visible is not sufficient when the camera is far above the
        // terrain. Minecraft can retain compiled sections that sit beyond the
        // current camera far plane; yielding LOD to those stale-visible columns
        // creates the giant circular hole seen in straight-down flight tests.
        int centerSurfaceY = chunk.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                8,
                8
        );
        double chunkCenterX = chunkX * 16.0D + 8.0D;
        double chunkCenterZ = chunkZ * 16.0D + 8.0D;
        double dx = chunkCenterX - cameraX;
        double dy = centerSurfaceY - cameraY;
        double dz = chunkCenterZ - cameraZ;
        double vanillaReach = EverviewFarPlane.vanillaDepthFar()
                + VANILLA_3D_HANDOFF_MARGIN_BLOCKS;

        // M5.5 still allowed compiled vanilla sections to own the column while
        // the camera was hundreds of blocks above them. Minecraft's section
        // visibility flag can remain true even when that section is not part of
        // the useful current-camera terrain presentation. When flying high,
        // keep the LOD floor until the camera is vertically close enough that a
        // vanilla handoff is visually safe.
        if (Math.abs(dy) > MAX_VANILLA_VERTICAL_HANDOFF_BLOCKS
                || dx * dx + dy * dy + dz * dz
                        > vanillaReach * vanillaReach) {
            ColumnOwnershipResult result =
                    new ColumnOwnershipResult(false, true);
            columns.put(key, result);
            return result;
        }

        boolean visible = vanillaSurfaceColumnVisible(
                client,
                chunk,
                chunkX,
                hintSectionY,
                chunkZ,
                visibility
        );

        // A single renderer-visible chunk is not enough to retire the LOD at
        // the moving vanilla edge. The neighboring chunk can still be absent,
        // which leaves a thin sky slit between the two presentations. Erode
        // vanilla ownership by one chunk near the edge: only a locally stable
        // 3x3 surface neighborhood may remove Everview there.
        int vanillaRadius =
                client.options.getEffectiveRenderDistance() * 16;
        double horizontalDistance = Math.hypot(dx, dz);
        boolean nearVanillaEdge = horizontalDistance
                >= Math.max(
                        0.0D,
                        vanillaRadius - VANILLA_EDGE_NEIGHBORHOOD_BLOCKS
                );

        if (visible && nearVanillaEdge) {
            visible = vanillaNeighborhoodSurfaceReady(
                    client,
                    chunkX,
                    hintSectionY,
                    chunkZ,
                    visibility
            );
        }

        // Renderer visibility can become true slightly before the vanilla
        // presentation is visually stable. Never remove the LOD on the first
        // visible frame. Keep an opaque overlap for a short continuous window;
        // vanilla already rendered earlier in this same pass and wins depth.
        // This makes the transition vanilla-over-LOD -> vanilla-only instead
        // of LOD -> one-frame sky -> vanilla.
        long now = System.nanoTime();

        if (!visible) {
            VANILLA_HANDOFF_STATES.remove(key);
            ColumnOwnershipResult result =
                    new ColumnOwnershipResult(false, true);
            columns.put(key, result);
            return result;
        }

        VanillaHandoffState state = VANILLA_HANDOFF_STATES.get(key);
        if (state == null) {
            state = new VanillaHandoffState(now, now);
            VANILLA_HANDOFF_STATES.put(key, state);
        } else {
            state.lastSeenNanos = now;
        }

        boolean stable = now - state.firstVisibleNanos
                >= VANILLA_VISIBLE_STABILITY_NANOS;
        boolean grace = recentlyCompiledColumn(
                chunkX,
                chunkZ,
                VANILLA_HANDOFF_GRACE_NANOS
        );

        ColumnOwnershipResult result = stable && !grace
                ? new ColumnOwnershipResult(true, false)
                : new ColumnOwnershipResult(false, true);
        columns.put(key, result);
        return result;
    }

    private static boolean vanillaNeighborhoodSurfaceReady(
            Minecraft client,
            int chunkX,
            int hintSectionY,
            int chunkZ,
            Map<SectionKey, Boolean> visibility
    ) {
        if (client.level == null) {
            return false;
        }

        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                LevelChunk neighbor = client.level.getChunkSource()
                        .getChunkNow(chunkX + dx, chunkZ + dz);

                if (neighbor == null
                        || !vanillaSurfaceColumnVisible(
                                client,
                                neighbor,
                                chunkX + dx,
                                hintSectionY,
                                chunkZ + dz,
                                visibility
                        )) {
                    return false;
                }
            }
        }

        return true;
    }

    private static void pruneVanillaHandoffStates() {
        if (VANILLA_HANDOFF_STATES.isEmpty()) {
            return;
        }

        long now = System.nanoTime();
        VANILLA_HANDOFF_STATES.entrySet().removeIf(
                entry -> now - entry.getValue().lastSeenNanos
                        > VANILLA_HANDOFF_STATE_TTL_NANOS
        );
    }

    private static boolean recentlyCompiledColumn(
            int chunkX,
            int chunkZ,
            long maxAgeNanos
    ) {
        Long time = COMPILED_COLUMNS.get(new ChunkKey(chunkX, chunkZ));
        return time != null && System.nanoTime() - time <= maxAgeNanos;
    }

    private static boolean vanillaSurfaceColumnVisible(
            Minecraft client,
            LevelChunk chunk,
            int chunkX,
            int hintSectionY,
            int chunkZ,
            Map<SectionKey, Boolean> visibility
    ) {
        // First check the LOD surface neighborhood. This is the cheapest common
        // path when worldgen and the loaded chunk agree on surface height.
        for (int offset = -1; offset <= 1; offset++) {
            if (vanillaSectionVisible(
                    client,
                    chunkX,
                    hintSectionY + offset,
                    chunkZ,
                    visibility
            )) {
                return true;
            }
        }

        // M5.1 used a +/-24 section sweep for every candidate column. That was
        // robust but far too expensive once thousands of chunk-split batches
        // were active. M5.3 asks the loaded chunk's own heightmap where its
        // actual surface is, then probes only those renderer sections.
        for (int probe = 0; probe < SURFACE_PROBE_X.length; probe++) {
            int surfaceY = chunk.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    SURFACE_PROBE_X[probe],
                    SURFACE_PROBE_Z[probe]
            );
            int surfaceSectionY = Math.floorDiv(surfaceY - 1, 16);

            for (int offset = -1; offset <= 1; offset++) {
                if (vanillaSectionVisible(
                        client,
                        chunkX,
                        surfaceSectionY + offset,
                        chunkZ,
                        visibility
                )) {
                    return true;
                }
            }
        }

        // Upload hints cover unusual cliffs/overhangs that the five surface
        // probes may miss. Iterating the tiny hint map is cheap; ownership still
        // requires the hinted section to be actually visible this frame.
        for (Map.Entry<SectionKey, Long> entry
                : RECENTLY_COMPILED_SECTIONS.entrySet()) {
            SectionKey section = entry.getKey();
            if (section.chunkX() != chunkX || section.chunkZ() != chunkZ) {
                continue;
            }

            if (System.nanoTime() - entry.getValue()
                    > RECENT_COMPILE_HINT_NANOS) {
                continue;
            }

            if (vanillaSectionVisible(
                    client,
                    chunkX,
                    section.sectionY(),
                    chunkZ,
                    visibility
            )) {
                return true;
            }
        }

        return false;
    }

    private static boolean vanillaSectionVisible(
            Minecraft client,
            int chunkX,
            int sectionY,
            int chunkZ,
            Map<SectionKey, Boolean> visibility
    ) {
        SectionKey key = new SectionKey(chunkX, sectionY, chunkZ);
        Boolean cached = visibility.get(key);

        if (cached != null) {
            return cached;
        }

        boolean visible = client.levelRenderer.isSectionCompiledAndVisible(
                new BlockPos(
                        chunkX * 16 + 8,
                        sectionY * 16 + 8,
                        chunkZ * 16 + 8
                ),
                0L
        );
        visibility.put(key, visible);
        return visible;
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
        private static final ColumnOwnershipResult NOT_OWNED =
                new ColumnOwnershipResult(false, false);
    }

    private record ChunkKey(
            int chunkX,
            int chunkZ
    ) {
    }

    private static final class VanillaHandoffState {
        private final long firstVisibleNanos;
        private long lastSeenNanos;

        private VanillaHandoffState(
                long firstVisibleNanos,
                long lastSeenNanos
        ) {
            this.firstVisibleNanos = firstVisibleNanos;
            this.lastSeenNanos = lastSeenNanos;
        }
    }

    private record SectionKey(
            int chunkX,
            int sectionY,
            int chunkZ
    ) {
    }

}
