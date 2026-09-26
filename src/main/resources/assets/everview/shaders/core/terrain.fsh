#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
layout(std140) uniform EverviewOwnership { uvec4 OwnedChunks[512]; };
layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 ownershipPosition;
layout(location = 0) out vec4 fragColor;
bool vanillaOwns(ivec2 p) {
    if (any(lessThan(p, ivec2(0))) || any(greaterThanEqual(p, ivec2(256)))) return false;
    int bit = p.y * 256 + p.x;
    uint word = OwnedChunks[bit >> 7][(bit >> 5) & 3];
    return (word & (1u << uint(bit & 31))) != 0u;
}
void main() {
    ivec2 cell = ivec2(floor(ownershipPosition / 16.0));
    // Vertical seams/walls exactly on a chunk boundary yield to either vanilla neighbor.
    vec2 edge = abs(ownershipPosition - vec2(cell) * 16.0);
    if (vanillaOwns(cell)
        || (edge.x < 0.001 && vanillaOwns(cell - ivec2(1,0)))
        || (edge.y < 0.001 && vanillaOwns(cell - ivec2(0,1)))) discard;
    fragColor = vertexColor * ColorModulator;
}
