#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 ownershipPosition;
void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    // Region-relative data stays small; ModelOffset is relative to the mask origin.
    ownershipPosition = Position.xz + ModelOffset.xz;
}
