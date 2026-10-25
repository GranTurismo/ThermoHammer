#version 460
// Rain streaks wrapped in a box ahead of the camera, lit where they cross a lamp's beam.
#include "common.glsl"
#include "lights.glsl"
layout(location = 0) out vec2 vQ;
layout(location = 1) out vec3 vLight;
const vec3 BOX = vec3(36.0, 36.0, 24.0);
const vec2 CORNERS[6] = vec2[6](vec2(-1.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 1.0),
                                vec2(-1.0, 0.0), vec2(1.0, 1.0), vec2(-1.0, 1.0));
void main() {
    float id = float(gl_InstanceIndex);
    vec3 seed = vec3(hash11(id * 0.1371 + 0.3), hash11(id * 0.7193 + 3.1), hash11(id * 1.3711 + 7.7));
    float speed = 8.0 + 3.0 * hash11(id * 2.17);
    vec3 fall = vec3(1.1, 0.45, -speed);
    vec3 centre = uCamPos + vec3(F.camFwd.xy, 0.0) * (BOX.x * 0.36);
    vec3 p = seed * BOX + fall * uTime;
    p = centre + mod(p - centre + BOX * 0.5, BOX) - BOX * 0.5;
    vec3 axis = normalize(fall);
    float dist = length(uCamPos - p);
    float len = 0.04 * speed * (1.0 + 0.5 * hash11(id * 5.3));
    vec3 side = normalize(cross(axis, uCamPos - p)) * (0.004 + dist * 0.0009);
    vec2 q = CORNERS[gl_VertexIndex];
    vQ = q;
    vec3 rd = normalize(p - uCamPos);
    vLight = (scatterLights(p, rd, 6, false, false) * 0.35 + HAZE * 0.6) * smoothstep(0.8, 2.5, dist) * (1.0 - smoothstep(24.0, 34.0, dist));
    gl_Position = F.viewProj * vec4(p + side * q.x + axis * (q.y * len), 1.0);
}
