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
- [ ] benchmark coalesced handoff CPU cost at full 1b residency
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
