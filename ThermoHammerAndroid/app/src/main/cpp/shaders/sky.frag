#version 460
#include "common.glsl"
layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 oColor;
void main() {
    vec2 ndc = vUV * 2.0 - 1.0;
    vec4 a = F.invViewProj * vec4(ndc, 0.0, 1.0);
    vec4 b = F.invViewProj * vec4(ndc, 1.0, 1.0);
    vec3 dir = normalize(b.xyz / b.w - a.xyz / a.w);
    vec3 col = mix(skyColor(dir), HAZE, exp(-max(dir.z, 0.0) * 12.0) * 0.8);
    oColor = vec4(col, 1.0);
}
