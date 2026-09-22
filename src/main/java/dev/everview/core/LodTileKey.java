package dev.everview.core;

/** Stable address for a tile at one clipmap level. */
public record LodTileKey(int level, int tileX, int tileZ) {
}
