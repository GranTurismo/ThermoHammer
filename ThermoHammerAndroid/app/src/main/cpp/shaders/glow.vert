#version 460
// A soft halo around every light — rain mist glowing near the lamp.
#include "common.glsl"
layout(std430, set = 0, binding = 1) readonly buffer LightBuf { vec4 lightData[]; };
layout(location = 0) out vec2 vQ;
layout(location = 1) out vec3 vCol;
const vec2 CORNERS[6] = vec2[6](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0),
                                vec2(-1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0));
void main() {
    uint id = uint(gl_InstanceIndex);
    vec4 l0 = lightData[id * 4u];
    vec4 l1 = lightData[id * 4u + 1u];
    float g = groupValueOf(int(lightData[id * 4u + 3u].x));
    vec3 toCam = uCamPos - l0.xyz;
    float dist = length(toCam);
    float size = 0.5 + l0.w * 0.09 + dist * 0.004;
    if (l1.w > 0.5) {
        vec4 l2 = lightData[id * 4u + 2u];
        float facing = smoothstep(l2.w - 0.3, 1.0, dot(l2.xyz, toCam / max(dist, 0.01)));
        g *= max(l2.z < -0.5 ? 0.35 : 0.0, facing);
    }
    vec3 c = l1.rgb * g * (0.022 / (1.0 + dist * 0.015)) * (1.0 - smoothstep(110.0, 160.0, dist));
    vec2 q = CORNERS[gl_VertexIndex];
    vec3 w = l0.xyz + toCam / max(dist, 0.01) * min(0.6, dist * 0.5) + (F.camRight.xyz * q.x + F.camUp.xyz * q.y) * size;
    vQ = q;
    vCol = c;
    gl_Position = dist < 1.0 ? vec4(0.0, 0.0, 2.0, 1.0) : F.viewProj * vec4(w, 1.0);
}
