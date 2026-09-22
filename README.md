<img width="1920" height="1080" alt="2026-09-22_16 53 55" src="https://github.com/user-attachments/assets/1bf74b18-5f6d-40b2-8ad5-d279aa9eda62" />
# Everview

Everview is an experimental client-side terrain LOD renderer for Minecraft Java.

## Development baseline

- Minecraft **26.3**
- **Fabric**
- Fabric Loader **0.19.5**
- Fabric API **0.161.0+26.3**
- Loom **1.17**
- Java **25**
- Gradle **9.6**

Everview is being built on the newest renderer generation rather than targeting an older Minecraft version just to match another LOD mod. Worldgen packs such as JJThunder are test workloads, not architectural dependencies.

## Current milestone: M0 bootstrap

The current code establishes the version-independent LOD core:

- camera-centered exponential clipmap planning;
- 65,536-block initial target radius;
- LOD ring metadata;
- stable tile addressing;
- generation priority primitives;
- Fabric 26.3 client bootstrap.

We are intentionally keeping the first renderer hook out of this migration commit. Minecraft 26.x changed the rendering pipeline substantially, so the next step is to integrate Everview with the 26.3 render graph cleanly instead of carrying over the temporary NeoForge 1.21.1 path.

## Architecture target

1. Vanilla/Sodium owns nearby chunks.
2. Everview starts outside the handoff radius.
3. Near LOD stores block-derived surface geometry.
4. Mid/far LOD uses progressively coarser surface tiles.
5. Extreme distance uses compact height/material data rather than full chunks.
6. Generation, caching and GPU submission remain independent subsystems.
7. Sodium and Iris are compatibility targets, not hard requirements.

## Initial performance target

- 65,536-block radius (4,096 chunks)
- stable memory working set as distance grows
- low CPU submission overhead
- bounded asynchronous generation
- no synchronous far-chunk generation on the render thread

## Build

Install JDK 25, then:

```bash
./gradlew build
```

On Windows:

```bat
gradlew.bat build
```

The mod JAR is written to `build/libs/`.
