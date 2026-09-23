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
