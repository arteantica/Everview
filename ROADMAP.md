# Everview roadmap

## M0 - 26.3 Fabric foundation
- [x] Minecraft 26.3 / Fabric baseline
- [x] Java 25 toolchain
- [x] clipmap layout
- [x] tile addressing
- [x] generation-priority primitive
- [x] CI build
- [ ] renderer-backend interface

## M1 - first visible terrain
- [x] 26.3 render-graph integration
- [x] diagnostic terrain mesh
- [x] camera-relative coordinates
- [x] loaded-chunk surface sampler
- [x] logical 64x64 terrain tiles
- [x] live debug HUD and timings
- [x] reject fluid surface samples
- [x] ignore tree canopies for ground sampling
- [x] persistent CPU tile cache
- [x] rebuild only when entering new tile regions
- [x] per-tile frustum culling
- [ ] depth/fog handoff validation
- [ ] persistent GPU tile buffers

## M2 - distant generation
- [x] direct singleplayer ChunkGenerator height sampling without loading distant chunks
- [x] first true worldgen LOD ring beyond the vanilla render radius
- [x] split distant generation into small server-thread slices
- [x] soft per-slice CPU budget with bounded queued work
- [x] multiple progressive LOD rings to 4,096 blocks
- [x] distance-ladder diagnostic rings to 16,384 blocks
- [x] per-ring visibility/cull/submission diagnostics
- [x] 4 ms bounded initial-fill generation throughput test
- [x] adaptive generation budget based on server/client frame pressure
- [x] experimental camera/frustum depth extension to 20,480 blocks
- [x] validate 16,384-block L5 visibility through extended projection
- [ ] compact surface tile format
- [x] compressed per-world/per-dimension disk persistence
- [ ] multiplayer/server data path

## M3 - GPU architecture
- [x] first persistent GPU tile-buffer path
- [ ] batched/indirect terrain submission
- [ ] exact GPU-side ring clipping / clipmap ownership
- [x] frustum culling
- [ ] horizon/occlusion culling
- [ ] screen-space error based detail selection
- [ ] seam stitching / geomorphing

## M4 - visual parity
- [x] first biome-derived grass tint pass
- [x] first water color pass
- [x] first sand/stone/snow/material classification pass
- [x] first slope-based terrain lighting pass
- [x] naturalized palette + tighter snow classification
- [x] vanilla-to-LOD overlap handoff pass
- [x] retain material IDs per distant surface vertex
- [x] first procedural material breakup pass
- [x] ring-aware material detail falloff
- [x] bake material breakup into cached tile colors
- [x] terraced L1 plateau geometry
- [x] vertical cliff faces between near-LOD cells
- [x] flat water/coastline classification for L1
- [x] denser L1 sampling: 16 -> 8 blocks after M3.2 performance validation
- [x] split near ring: 4-block ultra-near + 8-block near after M3.2.1 benchmark
- [x] validate 2-block ultra-near L1 after persistent-GPU benchmark
- [x] progressive 2x-spacing bootstrap tiles before exact refinement
- [x] adaptive initial-stream generation budget up to 12 ms
- [x] near-first exact refinement after 70% L1/L2 bootstrap coverage
- [x] weighted 2:1 near-refine / remaining-coverage scheduler
- [x] conservative AABB overlap pass across LOD-ring seams
- [x] L1/L2 roaming guard-band prefetch + visible-hole priority
- [x] persistent overlapping L2 fallback underlay beneath the full L1 band
- [x] prioritize missing visible L2 fallback tiles before all refinement
- [x] suppress interior L2 underlay draws once resident L1 coverage is complete
- [x] exact 1-block horizontal L1 refinement
- [x] staged L1 streaming: 4b bootstrap -> 2b intermediate -> 1b low-priority exact
- [x] distance-tiered L1 targets: inner 64b at 1-block, next 64b at 2-block, outer L1 at 4-block
- [x] coverage-first scheduler: visible coarse field + near guard before any detail refinement
- [ ] benchmark coverage-first distance-tiered L1 generation / VRAM / roaming cost
- [x] isolate M3.6.3 scheduler with fresh v15 disk cache benchmark
- [x] camera-facing L1 generation priority with rear-half L2 fallback
- [x] rebuild L1 priority on 22.5-degree view sectors
- [x] interleave deferred coverage and front L1 refinement at 3:1
- [x] lighten deferred-coverage weighting to 8:1 for faster full-field completion
- [x] defer exact L2 refinement until coverage/front L1 quality are complete
- [x] exact L1 one-column-per-block geometry (no four-corner height averaging)
- [x] deterministic east/south exact-tile edge walls without deep skirts
- [x] layered grass-column side walls: shallow dirt over stone
- [x] early exact-L1 refinement: interleave 2 intermediate upgrades per 1 exact upgrade
- [x] exact-band-first refinement: complete nearest inner-64b tiles to 1b before wider 2b work
- [x] contiguous radial L1 ownership: tile intersection promotes the full inner belt to 1b
- [x] live renderer-visible handoff batches: persistent L1/L2 geometry, no destructive clipping
- [x] suppress handoff boundary walls when either adjacent vanilla section is renderer-visible
- [x] split tall near-LOD vertical faces at vanilla 16-block section boundaries
- [x] surface-column handoff ownership with +/-1 renderer-visible Y-section tolerance
- [x] early top-face handoff from vanilla RenderSection upload completion with expiring fallback-safe hints
- [x] velocity-aware L1/L2 predictive prefetch anchored ahead of sustained motion
- [x] high-speed coverage-only scheduling with predictive L2 safety carpet
- [x] abandon stale in-progress refinement after movement priority shifts
- [x] expose motion speed / lead / ahead-coverage / stale-cancel telemetry
- [x] restrict expensive live section ownership to a narrow vanilla-boundary band
- [x] one-draw-per-tile fast path outside the vanilla handoff zone
- [x] expose real draw-call / handoff-batch telemetry
- [x] coalesce adjacent visible handoff batches into contiguous indexed draw ranges
- [x] gate upload-time top-face ownership on renderer-visible terrain in the same chunk column
- [x] replace interleaved ring coverage with a nearest-to-farthest outward frontier
- [x] make visible L1/L2 holes absolute priority over prediction, far rings, and refinement
- [x] expose outward-frontier distance and near-field continuity telemetry
- [x] overlap L3 beneath the full near field as a 64b-bootstrap emergency safety floor
- [x] suppress emergency L3 tiles once the overlapping L2 region is fully resident
- [x] split L3 GPU geometry into 128x128 L2 ownership regions
- [x] retire each emergency underlay region independently as matching L2 becomes resident
- [x] retain L1 worldgen samples on a persistent 1-block hierarchy across 4b/2b/1b passes
- [x] preserve partial refinement samples when a moving player cancels a detail job
- [x] allow exact-target L1 tiles to refine directly from 4b to 1b
- [x] expose L1 sample-reuse telemetry in the debug HUD
- [x] split exact L1 refinement into geometry-first and appearance-second passes
- [x] borrow nearest cached material/color while missing 1b heights are generated
- [x] defer per-block biome/palette sampling until exact geometry is already resident
- [x] expose exact-appearance tile progress and biome-sample telemetry
- [x] prioritize current/predictive emergency underlay before L2/L1 detail coverage
- [ ] benchmark emergency-underlay continuity at x7 FlySpeed and normal elytra speeds
- [ ] benchmark fallback-safe handoff CPU cost at full 1b residency
- [ ] benchmark velocity prefetch at elytra and FlySpeed travel rates
- [ ] block-faithful 2-block intermediate geometry
- [ ] exact GPU-side ring clipping + final seam stitching / geomorphing
- [ ] screen-space error driven detail selection
- [ ] texture-atlas / UV material rendering
- [ ] structures
- [ ] trees
- [ ] fog and atmosphere
- [ ] near voxel-derived LOD for cave mouths/overhangs (issue #5)

## M5 - compatibility
- [ ] Sodium path
- [ ] Iris detection and safe fallback
- [ ] shader depth/fog integration
- [ ] simplified distant shadows
- [ ] shader-facing LOD metadata

## Benchmark goals
- [x] first generator-derived terrain beyond loaded chunks
- [x] 4,096-block progressive-ring smoke test
- [x] 16,384-block distance-ladder smoke test
- [ ] 65,536 blocks
- [ ] 262,144 blocks
- [ ] world-scale horizon mode
- [ ] fixed RAM/VRAM budgets
- [ ] standardized comparison scenes and frame-time captures


## M4 unified LOD engine
- [x] explicit tile lifecycle: coverage / exact geometry / exact appearance
- [x] invalidate M3 cache format and persist only disk-safe tile states
- [x] unified hierarchical ownership: vanilla > L1 > L2 > L3
- [x] stage-priority scheduler: coverage > exact geometry > exact appearance
- [x] cap provisional exact-geometry backlog so borrowed appearance cannot spread indefinitely
- [x] high-throughput exact L1 generation via two-worker height pipeline
- [x] remove whole-tile coarse fallback ownership in favor of finer per-region retirement
- [x] CI integration pass before first M4 test jar
- [ ] in-game M4 stress validation: stationary / normal flight / x7 FlySpeed / reload

- [x] automatic server-thread fallback if async exact-height sampling is rejected


## M4.1 dual-lane streaming
- [x] detach exact L1 height refinement from the outward coverage job
- [x] keep server coverage streaming while two exact-height workers refine nearest L1
- [x] cap detached provisional geometry and force appearance catch-up at the backlog limit
- [x] keep detached exact tiles out of duplicate refinement selection


## M4.2 absolute vanilla ownership
- [x] evaluate vanilla ownership across the full renderer-overlap area instead of only the outer handoff band
- [x] block the one-draw L1/L2 fast path wherever vanilla may already own visible chunk sections
- [x] widen surface-section ownership tolerance for water and terrain section-boundary mismatches
- [x] preserve LOD fallback where vanilla is not actually renderer-visible


## M5 hard chunk-column ownership
- [x] branch from M4.2 instead of stacking another handoff micro-patch
- [x] apply vanilla ownership to L1, L2, and emergency L3
- [x] split L3 geometry through 16x16 vanilla chunk ownership cells
- [x] preserve L3's independent 128x128 L2 fallback ownership metadata
- [x] deep vanilla region: loaded chunk column wins immediately
- [x] outer vanilla fringe: renderer-visible test retains no-hole fallback
- [x] expose vanilla/finer/visible ownership batch counts in HUD
- [ ] stress-test land, ocean, coast, x7 flight, and rapid direction reversal

- [x] convert detached exact generation from two workers on one tile to two independent tile jobs
- [x] cancel detached exact work immediately when high-speed coverage mode takes priority
- [x] keep sample-cache entries pinned while either exact worker owns them


## M5.1 chunk-split surface ownership
- [x] classify sloped smooth terrain tops as vanilla-sensitive surfaces
- [x] split sloped L3 surfaces on every 16x16 vanilla chunk boundary
- [x] bilinearly preserve height and color across split smooth-surface pieces
- [x] scan the whole vertical chunk column at the handoff fringe instead of only +/-2 LOD sections
- [x] eliminate the BatchKey.ALWAYS escape path for normal sloped terrain tops


## M5.2 render-ready column handoff
- [x] remove the deep-radius "loaded chunk wins" ownership shortcut
- [x] require actual renderer-visible vanilla terrain before retiring Everview in every chunk column
- [x] keep Everview underneath loaded-but-not-rendered vanilla during high-speed travel
- [x] cache full per-frame column ownership state instead of only a boolean claim
- [x] expose loaded-but-waiting fallback batches in the debug HUD
- [ ] stress-test x12 FlySpeed handoff, land/ocean transitions, and rapid direction reversal


## M5.3 continuous fallback floor
- [x] extend the cheap L3 emergency surface inward beneath the vanilla radius
- [x] make unloaded vanilla columns an immediate zero-probe LOD fallback path
- [x] replace per-column +/-24 renderer scans with loaded-chunk surface-height probes
- [x] retain upload hints only as a cheap unusual-cliff/overhang fallback
- [x] defer L1 bootstrap generation at high travel speed and spend coverage bandwidth on L3/L2
- [x] raise high-speed coverage generation headroom to 16 ms only while server/frame timing is healthy
- [ ] stress-test stationary handoff, x7, x12, land/ocean transitions, and rapid reversal


## M5.4 flight coverage lane
- [x] extend the emergency L3 floor from the camera column to the full 2K radius
- [x] close the high-altitude downward-view center hole
- [x] treat high-speed L1/L2 as deferred detail instead of blocking coarse continuity
- [x] prioritize L3-L6 bootstrap coverage during high-speed travel
- [x] reuse the two height workers for parallel coarse coverage generation
- [x] keep biome/material appearance and mesh assembly on the server lane
- [x] cache immutable biome classification used by the surface palette
- [ ] benchmark x15 coverage continuity, far-ring holes, generation tiles/s, and geometry CPU


## M5.5 global safety floor
- [x] gate vanilla ownership by camera-to-surface 3D reach, not stale renderer visibility alone
- [x] stop high-altitude compiled vanilla sections from punching a circular hole below the camera
- [x] convert L4/L5/L6 from disjoint annuli into nested fallback disks
- [x] make L6 a 0-16K coarse safety floor generated before all finer coverage
- [x] attach immediate-finer ownership regions to L4/L5/L6 GPU batches
- [x] retire coarse regions only when their immediate finer disk is actually resident
- [x] expose global L6 safety-floor readiness in the HUD
- [ ] validate straight-down high-altitude flight, x15 horizontal flight, and 16K far coverage


## M5.6 moving-camera stability
- [x] add a 512-block vertical safety gate before vanilla may retire LOD
- [x] keep high-altitude compiled vanilla from reopening the center hole while moving
- [x] prioritize current/predictive L3 before newly exposed far-edge L6 floor work
- [x] increase predictive lead horizon to 3.0 seconds / 1536 blocks
- [x] generate newly exposed L3 directly at 32-block spacing instead of a 64-block bootstrap
- [x] increase inter-LOD depth separation to suppress far-distance z-fighting / drought cracks
- [ ] validate stationary high-altitude view, x15 motion, direction reversal, and pixel/crack cleanup


## M6.0 Minecraft surface fidelity
- [x] fix coarse async height workers to multiply grid coordinates by sample spacing
- [x] stop stretching a tiny corner height sample across entire L3-L6 tiles
- [x] raise far-level target detail to L3 16b / L4 32b / L5 64b / L6 128b
- [x] replace L3-L6 per-vertex color gradients with flat material facets
- [x] quantize distant vertical silhouette progressively by LOD instead of using one identical smooth look
- [x] add gravel, podzol, mud, ice and layered badlands surface classes
- [x] reduce broad procedural color blobs and use tighter material breakup
- [x] invalidate M5 disk geometry with cache v21
- [ ] add true block-atlas texturing / surface-rule reconstruction for near LODs
- [ ] add vegetation/trees as a separate distant feature layer
- [ ] validate L3-L6 visual distinction, shorelines, mountains, badlands and frozen biomes


## M6.1 progressive fidelity cascade
- [x] make L4 and L5 real normal-speed streaming stages instead of starving at 0 while L3/L6 dominate
- [x] settle far detail at L3 8b / L4 16b / L5 32b / L6 64b
- [x] keep first-fill bootstrap at 16b / 32b / 64b / 128b for coverage speed
- [x] add an explicit far-fidelity refinement lane before exact L1 appearance monopolizes idle work
- [x] preserve M5.6 high-speed safety scheduling
- [x] disable vanilla WORLD distance fog by routing the terrain fog uniform to FogMode.NONE
- [x] invalidate M6.0 disk tiles with cache v22
- [ ] validate that L4/L5 populate during initial load and green L3 islands no longer jump directly over L6
- [ ] validate clear 16K horizon with world fog disabled
- [ ] begin block-atlas/UV surface texturing after hierarchy quality is stable


## M6.2 quality-first lighting
- [x] make the entire visible L1 annulus a 1-block target instead of only the first 64 blocks
- [x] prioritize L1 coverage before L2 at normal speed while retaining L2-first high-speed safety
- [x] run exact 1-block L1 quality before far-ring refinement
- [x] raise settled hierarchy to L1 1b / L2 2b / L3 4b / L4 8b / L5 16b / L6 32b
- [x] fill the global L6 horizon before normal-speed middle-distance polish
- [x] apply live sky-light, rain and thunder modulation to persistent LOD colors
- [x] disable atmospheric fog directly in FogRenderer rather than one renderer call site
- [x] preserve underwater/lava fog while removing normal world-distance fog
- [x] invalidate prior far geometry with disk cache v23
- [ ] validate night / sunrise / rain lighting against adjacent vanilla terrain
- [ ] benchmark the denser 2/4/8/16/32b hierarchy
- [ ] begin block-atlas UV surface rendering after this priority/density baseline is stable


## M6.3 vanilla shield
- [x] stream L3 from startup instead of starving it behind near coverage and the L6 floor
- [x] interleave startup at 4 near : 2 L3 shield : 1 L6 horizon
- [x] let resident L3 directly retire overlapping L4/L5/L6 regions without waiting for the intermediate chain
- [x] stop very coarse fallback terrain from bleeding through caves, rivers and vegetation gaps near vanilla
- [x] force newly compiled vanilla sections to zero fade duration so they appear opaque immediately
- [ ] validate cave mouths, rivers, trees, newly generated chunks and high-speed handoff


## M6.4 streaming core + HUD
- [x] compact HUD by default with F8 toggle to the full telemetry panel
- [x] force the vanilla/Sodium chunk fade option to zero while Everview is active
- [x] remove unsupported direct fade-method injections; enforce the supported vanilla/Sodium chunk-fade option at zero
- [x] widen vanilla/LOD overlap from 32b to 64b
- [x] keep LOD under freshly uploaded vanilla for a 120ms opaque handoff grace window
- [x] widen the exact L1 band from ~192b to roughly 500b beyond the vanilla edge
- [x] move all L2-L6 height coverage to a dedicated multi-worker executor
- [x] scale coverage workers up to 8 logical workers based on available processors
- [x] make L4-L6 first-fill 4x coarser, then refine 2x -> target instead of blocking on near-final quality
- [x] invalidate M6.3 cache with v24 so cold-start throughput is measurable
- [ ] benchmark cold-start tiles/s and time to first complete 16K horizon
- [ ] add cross-LOD shared height sample cache to eliminate duplicate worldgen calls
- [ ] move material/mesh finishing off the server lane after thread-safety validation


## M6.5 saturated detail pipeline
- [x] raise detached exact L1 concurrency from 2 to up to 4 independent tile workers
- [x] allow a larger provisional exact-geometry lead so exact workers do not stall behind appearance debt
- [x] service exact-appearance debt continuously during coverage instead of waiting for the far cascade to finish
- [x] add a shared deterministic world-column height cache reused across L1-L6 and refinement passes
- [x] bound shared height storage at 1.25M columns
- [x] expose exact-worker saturation and shared-height reuse in compact/full HUD telemetry
- [x] invalidate M6.4 cache with v25 for a clean cold-start benchmark
- [ ] benchmark exact workers staying active during the first two minutes
- [ ] benchmark shared-height hit rate as L3/L6 bootstrap refines
- [ ] detach multiple L2-L6 tile-height jobs concurrently instead of parallelizing only one coverage tile at a time
- [ ] move appearance/mesh finishing into its own bounded pipeline after thread-safety validation


## M6.6 multi-tile streaming + seam shield
- [x] detach up to six L2-L6 tile-height jobs concurrently instead of parallelizing only one tile
- [x] use one coverage worker per detached tile so the pool advances several world regions simultaneously
- [x] reserve in-flight coverage keys so the normal scheduler does not duplicate detached work
- [x] promote completed detached height jobs back to the server lane only for biome/material/mesh finishing
- [x] keep L1 bootstrap/appearance production available while coarse height jobs run independently
- [x] avoid global height-cache insertion for odd exact-L1 columns that no coarser LOD can reuse
- [x] require a stable 3x3 vanilla surface neighborhood before retiring LOD near the moving vanilla edge
- [x] extend fresh-upload overlap grace from 120ms to 250ms
- [x] invalidate cache with v26 for cold-start comparison
- [ ] benchmark 30s / 60s / 120s coverage against M6.4 and M6.5
- [ ] validate no sky slits while moving sideways along the vanilla/LOD boundary


## M6.7 far fidelity stream
- [x] halve settled far spacing to L3 2b / L4 4b / L5 8b / L6 16b
- [x] make first-visible far geometry twice as dense as M6.6 through the denser targets
- [x] give L2, L3, L4, L5 and L6 independent detached coverage phases instead of letting L5 wait behind L4
- [x] reserve one detached streaming phase for live far refinement while coverage is still progressing
- [x] allow detached coverage workers to refine an existing coarse L3-L6 tile without removing its current visible mesh
- [x] round-robin far refinement across L3-L6 so the horizon improves everywhere instead of finishing one whole ring first
- [x] invalidate cache with v27 for a clean visual/timing comparison
- [ ] compare 45s screenshot against M6.6 for L5 population and far silhouette quality
- [ ] benchmark settled far refinement cost before increasing density again
- [ ] begin actual block-atlas/UV surface rendering once this geometry ladder is acceptable


## M6.8 dense bootstrap + L1 feed
- [x] reduce L4-L6 first-visible bootstrap from 4x target to 2x target
- [x] make first-visible far spacing L3 4b / L4 8b / L5 16b / L6 32b
- [x] expand detached tile concurrency to all eight coverage workers when available
- [x] reserve two of eight detached producer phases for visible foreground L1 bootstrap
- [x] generate L1 bootstrap heights off-thread instead of waiting entirely on the server lane
- [x] publish detached L1 height and appearance anchors into the fine sample grid so exact workers can immediately refine them
- [x] prioritize ready L1 bootstrap jobs when returning detached work to the server finishing lane
- [x] preserve one phase for live far refinement plus one phase each for L2-L6 coverage
- [x] invalidate cache with v28 for cold-start comparison
- [ ] verify exact workers remain fed during the first minute instead of falling to 0/4
- [ ] compare far visual quality at 45-60 seconds against M6.7
