#version 460
// Camera pass of Neon City RT. With RT (built with -DRT): reflections on wet streets, glass, paint and
// chrome are ray queries into the city, and the strongest lights cast ray-traced shadows.
// Without RT it falls back to environment reflections and unshadowed lights (development only).
#ifdef RT
#extension GL_EXT_ray_query : require
#endif
#include "common.glsl"
#include "lights.glsl"
#include "material.glsl"

layout(location = 0) in vec3 vWorld;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in vec3 vColor;
layout(location = 3) in vec4 vExtra;
layout(location = 4) in vec3 vRoom;
layout(location = 5) in vec3 vRoomView;
layout(location = 6) flat in int vClass;
layout(location = 7) flat in int vChan;
layout(location = 8) flat in float vRand;

layout(push_constant) uniform Push { int actor; int instanced; float seed; int pad; } pc;
layout(location = 0) out vec4 oColor;

void main() {
    vec3 P = vWorld;
    int k = vClass;
    bool ground = k == C_ASPHALT || k == C_PAVING || k == C_PAINT;
    vec3 N = normalize(vNormal);
    vec3 V = normalize(uCamPos - P);
    if (dot(N, V) < 0.0) N = -N;
    int maxLights = int(F.params.y);
    vec3 col;
    // every branch only chooses its reflection rays; they are traced once, below
    vec3 rayDir[2];
    int rays = 0;
    float reflMix = 0.0;      // how much of the result is reflection
    vec3 after = vec3(0.0);   // added on top of the reflection (lights on glass, window emission)

    if (isEmissive(k)) {
        col = emission(k, vChan, vColor, P, N, pc.actor, vExtra, length(fwidth(vColor.rg * vec2(160.0, 80.0))));
        col += envColor(reflect(-V, N)) * 0.04;
    } else if (k == C_GLASS || k == C_SHOPGLASS) {
        bool shop = k == C_SHOPGLASS;
        vec3 inside = interior(vRoom, normalize(vRoomView), shop ? 0.5 : vColor.b, shop, vColor, vRand, pc.instanced == 1);
        col = inside;
        rayDir[0] = reflect(-V, N);
        rays = 1;
        reflMix = 0.04 + 0.96 * pow(1.0 - max(dot(N, V), 0.0), 5.0);
        after = shadeLights(P, N, V, vec3(0.0), vec3(0.04), 0.07, maxLights, false);
    } else {
        vec3 albedo = vColor;
        float rough, metal;
        surfaceParams(k, vChan, pc.actor, vExtra, albedo, rough, metal);
        vec3 emit = vec3(0.0);
        float wet = 0.0;
        if (k == C_TOWER) {
            float glassMask;
            emit = towerWindows(P, N, length(fwidth(vec2(dot(P.xy, vec2(0.55)), P.z / 3.5))) * 2.0, glassMask);
            rough = mix(0.5, 0.08, glassMask);
            albedo *= 1.0 - 0.7 * glassMask;
        }
        if (k == C_CONCRETE || k == C_BRICK || k == C_TILE || k == C_WOOD) {
            vec3 t = normalize(vec3(N.y, -N.x, 0.0) + vec3(1e-4, 0.0, 0.0));
            vec2 uv = abs(N.z) > 0.7 ? P.xy : vec2(dot(P, t), P.z);
            float px = length(fwidth(uv));
            if (k == C_BRICK) {
                float row = floor(uv.y / 0.16);
                float bx = uv.x / 0.32 + 0.5 * mod(row, 2.0);
                float mortar = max(step(0.86, fract(uv.y / 0.16)), step(0.93, fract(bx)));
                float tone = 0.8 + 0.4 * hash12(vec2(floor(bx), row));
                albedo *= mix(1.0, mix(tone, 1.7, mortar), resolved(3.0, px));
            } else if (k == C_TILE) {
                vec2 tg = fract(uv / 0.2);
                float grout = max(step(0.92, tg.x), step(0.92, tg.y));
                albedo *= mix(1.0, mix(0.9 + 0.2 * hash12(floor(uv / 0.2)), 0.55, grout), resolved(2.5, px));
                rough = 0.3;
            }
            albedo *= 0.7 + 0.6 * fbm(uv * vec2(0.35, 0.18), 3);
            float streak = smoothstep(0.45, 0.8, vnoise(vec2(uv.x * 2.5, uv.y * 0.12 + uTime * 0.05)));
            wet = streak * (1.0 - abs(N.z)) * resolved(1.0, px) + 0.35;
            rough = mix(rough, 0.18, wet * 0.6);
            albedo *= 1.0 - 0.35 * wet;
        }

        if (ground) {
            float big = fbm(P.xy * (k == C_PAVING ? 0.3 : 0.16), 4);
            float puddle = smoothstep(0.5, 0.6, big);
            if (k == C_PAVING) puddle *= 0.7;
            vec2 slope = rippleSlope(P.xy) * puddle;
            float px = length(fwidth(P.xy));
            slope += (vec2(vnoise(P.xy * 7.0), vnoise(P.xy * 7.0 + 31.0)) - 0.5) * 0.18 * (1.0 - puddle) * resolved(7.0, px);
            N = normalize(vec3(-slope, 1.0));
            rough = mix(k == C_PAVING ? 0.4 : 0.3, 0.03, puddle);
            albedo *= mix(0.75, 0.35, puddle) * (0.8 + 0.4 * fbm(P.xy * 0.9, 2));
            wet = 0.7 + 0.3 * puddle;
            // two rays fanned up and down the street smear lights into the long streaks of wet asphalt
            vec3 R = reflect(-V, N);
            vec3 up = normalize(cross(cross(R, vec3(0.0, 0.0, 1.0)), R) + vec3(1e-4));
            float spread = rough * 0.35;
            rayDir[0] = normalize(R + up * spread);
            rayDir[1] = normalize(R - up * spread * 0.6);
            rays = 2;
            float Fg = 0.02 + 0.98 * pow(1.0 - max(dot(N, V), 0.0), 5.0);
            reflMix = mix(0.25, 1.0, puddle) * Fg;
        } else if (k == C_CARPAINT || k == C_CARGLASS || k == C_CHROME || (k == C_METAL && metal > 0.0)) {
            float Fc = 0.04 + 0.96 * pow(1.0 - max(dot(N, V), 0.0), 5.0);
            rayDir[0] = reflect(-V, N);
            rays = 1;
            reflMix = metal > 0.5 ? 0.7 : Fc * (k == C_CARPAINT ? 1.4 : 1.0);
        }

        vec3 diff = albedo * (1.0 - metal);
        vec3 f0 = mix(vec3(0.04 + 0.02 * wet), albedo, metal);
        col = shadeLights(P, N, V, diff, f0, rough, maxLights, true);
        col += cityGlow(diff, N);
        float Fe = 0.04 + 0.96 * pow(1.0 - max(dot(N, V), 0.0), 5.0);
        float gloss = (1.0 - rough) * (1.0 - rough);
        if (rays == 0) col += envColor(reflect(-V, N)) * mix(Fe * gloss, 0.7, metal);
        after = emit;
    }
    if (rays > 0) {
        vec3 refl = vec3(0.0);
        for (int i = 0; i < rays; i++) refl += traceReflection(P + N * 0.03, rayDir[i]);
        col = mix(col, refl / float(rays), clamp(reflMix, 0.0, 1.0));
    }
    col = applyHaze(col + after, P);
    oColor = vec4(safeHdr(col), 1.0);
}
