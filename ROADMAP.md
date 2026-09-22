# Everview renderer roadmap

## M0 - bootstrap
- [x] NeoForge 1.21.1 client project
- [x] clipmap layout
- [x] world render hook
- [x] debug overlay
- [x] generation-priority primitive

## M1 - first visible terrain
- [ ] custom GPU vertex/index buffers
- [ ] surface tiles from already-loaded chunks
- [ ] camera-relative coordinates to avoid far-distance precision loss
- [ ] ring skirts / crack suppression
- [ ] depth-correct draw before translucent world geometry

## M2 - distant generation
- [ ] off-thread surface sampler
- [ ] direct worldgen sampling without loading full client chunks
- [ ] bounded worker queue
- [ ] tile cache and eviction
- [ ] disk persistence

## M3 - optimization
- [ ] frustum culling
- [ ] horizon/occlusion culling
- [ ] indirect/batched draws
- [ ] persistent mapped buffers where supported
- [ ] screen-space error based detail selection

## M4 - visual parity
- [ ] biome tint
- [ ] water surface
- [ ] snow/material classification
- [ ] structures
- [ ] trees
- [ ] fog and atmospheric handoff

## M5 - shaders
- [ ] Iris detection and safe fallback
- [ ] depth/fog integration
- [ ] LOD material metadata
- [ ] simplified distant shadow caster path
