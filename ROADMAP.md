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
- [x] per-ring visibility/cull/submission diagnostics
- [ ] adaptive generation budget based on server/client frame pressure
- [ ] far-plane extension beyond vanilla camera depth
- [ ] compact surface tile format
- [ ] disk persistence
- [ ] multiplayer/server data path

## M3 - GPU architecture
- [ ] persistent GPU buffers
- [ ] batched/indirect terrain submission
- [x] frustum culling
- [ ] horizon/occlusion culling
- [ ] screen-space error based detail selection
- [ ] seam stitching / geomorphing

## M4 - visual parity
- [ ] biome tint
- [ ] water
- [ ] snow/material classification
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
- [ ] 65,536 blocks
- [ ] 262,144 blocks
- [ ] world-scale horizon mode
- [ ] fixed RAM/VRAM budgets
- [ ] standardized comparison scenes and frame-time captures
