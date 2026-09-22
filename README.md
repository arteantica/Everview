# Everview

Experimental client-side terrain LOD renderer for Minecraft Java 1.21.1 / NeoForge.

## Current milestone: 0.0.1-alpha bootstrap

This first commit deliberately does **not** fake a finished LOD renderer. It establishes:

- a client-only NeoForge 1.21.1 project;
- camera-centered exponential clipmap planning out to 65,536 blocks;
- tile addressing and generation-priority primitives;
- a world-render callback at `AFTER_SOLID_BLOCKS`;
- a live debug HUD with target distance, clipmap state and callback timing.

The next milestone replaces the placeholder render callback with a real GPU terrain path.

## Architecture target

1. Vanilla/Sodium owns nearby chunks.
2. Everview starts outside the handoff radius.
3. Near LOD stores block-derived surface geometry.
4. Mid/far LOD uses progressively coarser surface tiles.
5. Extreme distance uses height/material data rather than full chunks.
6. Async generation, disk cache and GPU culling are added before increasing quality.

## First performance target

- 65,536-block radius (4,096 chunks)
- stable memory working set
- low draw-call count
- no synchronous chunk generation on the render thread
- compatible rendering design for Sodium, then Iris

## Build

Requires JDK 21 and the NeoForge ModDevGradle dependencies.

The source archive intentionally does not bundle a Gradle wrapper binary yet. Once the project is in a repository/build environment, run the project Gradle build and the output JAR will be produced under `build/libs/`.
