// Surface materials of the vertex classes, shared by the camera pass (scene.frag) and the shading of
// reflection-ray hits. Ported from NeonCityShaders.OBJECT_FS. Needs common.glsl + lights.glsl.
#ifndef NRT_MATERIAL
#define NRT_MATERIAL

layout(std430, set = 0, binding = 4) readonly buffer VertBuf { uint vtx[]; };      // 5 words per vertex
layout(std430, set = 0, binding = 5) readonly buffer IdxBuf { uint idxData[]; };
layout(std430, set = 0, binding = 6) readonly buffer MeshBuf { uvec4 meshInfo[]; };  // first vertex, first index
layout(std430, set = 0, binding = 7) readonly buffer ExtraBuf { vec4 instExtra[]; }; // per TLAS instance

const float TIER[4] = float[4](1.0, 2.5, 5.0, 9.0);

float groupValue(int ch, vec3 p) {
    int g = ch & 31;
    if (g >= 9 && g <= 12) {
        float s = dot(p, vec3(0.7, 0.7, 0.3)) * 0.9 - uTime * (1.6 + 0.4 * float(g - 9));
        return 0.2 + 0.8 * smoothstep(0.32, 0.12, abs(fract(s) - 0.5));
    }
    return groupValueOf(g);
}

// LED billboard content; `fw` = pixel-grid footprint (fades the grid where it would alias)
vec3 screenContent(vec2 uv, float seed, float fw) {
    float t = uTime * 0.6 + seed * 20.0;
    float scene = floor(mod(t / 4.0 + seed * 3.0, 3.0));
    vec3 c;
    if (scene < 0.5) {
        c = mix(vec3(1.0, 0.1, 0.45), vec3(0.15, 0.25, 1.0), uv.x + 0.25 * sin(t + uv.y * 3.0));
        float r = length((uv - vec2(0.3, 0.55)) * vec2(2.0, 1.0));
        c = mix(c, vec3(1.0, 0.95, 0.8), smoothstep(0.24, 0.2, r) * (0.7 + 0.3 * sin(t * 3.0)));
        c *= 0.75 + 0.25 * step(0.5, fract(uv.y * 6.0 - t));
    } else if (scene < 1.5) {
        float row = floor(uv.y * 5.0);
        float bar = step(0.35, hash12(vec2(floor(uv.x * 9.0 + t * 2.0 * (1.0 + row * 0.3)), row)));
        c = mix(vec3(0.02, 0.05, 0.12), vec3(0.2, 0.9, 1.0), bar * step(0.2, fract(uv.y * 5.0)));
        c += vec3(1.0, 0.5, 0.05) * step(0.82, uv.y);
    } else {
        float v = sin(uv.x * 9.0 + t * 2.0) + sin(uv.y * 7.0 - t * 1.3) + sin((uv.x + uv.y) * 6.0 + t);
        c = 0.5 + 0.5 * cos(vec3(0.0, 2.1, 4.2) + v * 1.4 + t * 0.4);
    }
    vec2 px = fract(uv * vec2(160.0, 80.0));
    float grid = smoothstep(0.0, 0.18, px.x) * smoothstep(0.0, 0.18, px.y);
    return c * mix(grid, 0.72, clamp(fw * 1.2, 0.0, 1.0));
}

// A room behind the glass, ray-traced in the window's frame. Returns emitted light.
vec3 interior(vec3 q, vec3 v, float style, bool shop, vec3 shopColor, float rnd, bool instanced) {
    float roomW = shop ? 6.0 : style > 0.7 ? 4.2 : 3.4;
    float h = shop ? 3.4 : 3.0;
    float base = shop ? 0.15 : instanced ? (style > 0.9 ? 0.0 : -0.9) : 4.8;
    float cx = floor(q.x / roomW + 0.5);
    float cz = shop ? 0.0 : floor((q.z - base) / 3.2);
    float x0 = (cx - 0.5) * roomW;
    float z0 = base + cz * 3.2;
    float id = hash13(vec3(cx, cz, rnd * 97.0));
    float depth = (shop ? 7.0 : 4.0) + 2.5 * hash11(id * 13.1);
    vec3 iv = 1.0 / (v + vec3(1e-5));
    float tx = max((x0 - q.x) * iv.x, (x0 + roomW - q.x) * iv.x);
    float tz = max((z0 - q.z) * iv.z, (z0 + h - q.z) * iv.z);
    float ty = -depth * iv.y;
    float t = clamp(min(min(tx, tz), ty), 0.0, 60.0);
    vec3 hp = q + v * t;
    float rx = (hp.x - x0) / roomW;
    float rz = (hp.z - z0) / h;
    float ry = (q.y - hp.y) / depth;

    bool lit = shop || id > (style > 0.7 ? 0.66 : 0.48);
    bool tv = !shop && id > 0.48 && id < 0.56;
    vec3 lamp = shop ? shopColor : style > 0.7 ? vec3(0.80, 0.90, 1.0) : mix(vec3(1.0, 0.62, 0.32), vec3(1.0, 0.80, 0.55), hash11(id * 7.0));
    vec3 wall = mix(vec3(0.55, 0.50, 0.42), vec3(0.42, 0.48, 0.52), hash11(id * 3.3));
    vec3 c;
    if (t == tz) {
        c = hp.z > z0 + 0.5 * h ? vec3(0.75) : (shop ? vec3(0.5, 0.48, 0.45) : vec3(0.32, 0.20, 0.12));
        if (hp.z > z0 + 0.5 * h && (style > 0.7 || shop)) c += step(0.7, fract(ry * 3.0)) * step(0.3, fract(rx * 2.0)) * 2.0;
    } else if (t == tx) {
        c = wall * (0.8 + 0.2 * step(0.15, rz));
        if (shop) c = mix(c, shopColor * 0.9, step(0.15, rz) * step(rz, 0.85) * step(0.5, fract(ry * 6.0)));
    } else {
        c = wall;
        if (shop) {
            float shelf = step(0.45, fract(rz * 5.0)) * step(0.12, rz) * step(rz, 0.85);
            c = mix(c, mix(shopColor, vec3(1.0), hash12(floor(vec2(rx * 14.0, rz * 5.0)))), shelf);
        } else {
            c *= 1.0 - 0.6 * step(rz, 0.3) * step(0.2, rx) * step(rx, 0.8);
            if (hash11(id * 5.1) > 0.5) c = mix(c, vec3(0.08, 0.06, 0.05), step(abs(rx - 0.5), 0.12) * step(abs(rz - 0.62), 0.12));
        }
    }
    vec3 lp = vec3(x0 + 0.5 * roomW, q.y - depth * 0.45, z0 + h - 0.3);
    float fall = 1.6 / (1.0 + 0.12 * dot(hp - lp, hp - lp));
    vec3 e = lit ? c * lamp * fall * (shop ? 1.8 : 1.0) : c * vec3(0.006, 0.006, 0.01);
    if (tv) e += vec3(0.25, 0.45, 1.0) * (0.25 + 0.2 * sin(uTime * 7.0 + id * 40.0) * sin(uTime * 2.3)) * smoothstep(0.5, 0.0, ry);
    if (!shop && hash11(id * 9.7) > 0.62) {
        float slats = 0.55 + 0.45 * step(0.25, fract(q.z * 9.0));
        vec3 cloth = lit ? lamp * (0.18 + 0.2 * hash11(id * 3.9)) * slats : vec3(0.008) * slats;
        e = mix(e, cloth, 0.85);
    }
    return e;
}

vec3 towerWindows(vec3 p, vec3 n, float px, out float glassMask) {
    vec3 t = normalize(vec3(n.y, -n.x, 0.0) + vec3(1e-4, 0.0, 0.0));
    vec2 uv = vec2(dot(p, t) / 1.8, p.z / 3.5);
    vec2 f = fract(uv);
    float pxs = px / 2.0;
    float fade = clamp(pxs * 1.5 - 0.3, 0.0, 1.0);
    float win = step(abs(f.x - 0.5), 0.32) * step(abs(f.y - 0.55), 0.3);
    win = mix(win, 0.38, fade);
    float r = hash12(floor(uv) + floor(n.xy * 3.0) * 17.0);
    float lit = mix(step(0.62, r), 0.38, fade);
    glassMask = win;
    return win * lit * mix(vec3(1.0, 0.66, 0.36), vec3(0.75, 0.88, 1.0), step(0.86, r)) * 1.4;
}

vec2 rippleSlope(vec2 p) {
    vec2 g = vec2(0.0);
    for (int layer = 0; layer < 2; layer++) {
        vec2 q = p * 1.9 + float(layer) * vec2(0.37, 0.71);
        vec2 cell = floor(q);
        vec2 h = hash22(cell + float(layer) * 13.0);
        vec2 f = fract(q) - 0.25 - 0.5 * h;
        float life = fract(uTime * 1.2 + h.x * 7.0);
        float r = length(f);
        float ring = r - life * 0.45;
        float wave = sin(ring * 60.0) * smoothstep(0.08, 0.0, abs(ring)) * (1.0 - life) * (1.0 - life);
        g += f / max(r, 1e-3) * wave * 0.35;
    }
    return g;
}

bool isEmissive(int k) {
    return k == C_EMISSIVE || k == C_HEADLIGHT || k == C_TAILLIGHT || k == C_LANTERN || k == C_TRAINWIN || k == C_SCREEN;
}

vec3 emission(int k, int ch, vec3 color, vec3 P, vec3 N, int actor, vec4 extra, float fw) {
    float tier = TIER[(ch >> 5) & 3];
    vec3 base = color;
    float strength = k == C_HEADLIGHT ? 2.0 : k == C_TAILLIGHT ? 1.2 : k == C_TRAINWIN ? 0.45 : k == C_LANTERN ? 0.9 : 1.0;
    if (k == C_TAILLIGHT && actor == 1) strength *= 1.0 + 2.5 * extra.w;
    if (k == C_SCREEN) base = screenContent(color.rg, color.b, fw) * 0.35;
    if (k == C_LANTERN) base *= 0.6 + 0.4 * smoothstep(-0.2, 0.6, N.z + 0.3);
    return base * (tier * strength * groupValue(ch, P));
}

// Albedo / roughness / metalness of the lit (non-emissive, non-glass) classes.
void surfaceParams(int k, int ch, int actor, vec4 extra, inout vec3 albedo, out float rough, out float metal) {
    rough = 0.6;
    metal = 0.0;
    if (k == C_CARPAINT) { albedo *= actor == 1 && dot(extra.rgb, extra.rgb) > 0.0 ? extra.rgb : vec3(0.05); rough = 0.22; }
    else if (k == C_CARGLASS) { albedo = vec3(0.01); rough = 0.05; }
    else if (k == C_RUBBER) { rough = 0.55; }
    else if (k == C_CHROME) { metal = 1.0; rough = 0.18; }
    else if (k == C_METAL) { metal = 0.6; rough = 0.38; }
    else if (k == C_FABRIC) {
        int part = ch & 31;
        if (actor == 2 && part == 5) albedo = extra.rgb;
        else if (actor == 2 && part == 6) albedo = vec3(extra.w) * vec3(0.55, 0.5, 0.48) * 0.6;
        rough = part == 5 ? 0.25 : 0.8;
    }
    else if (k == C_SKIN) { rough = 0.5; }
    else if (k == C_FOLIAGE) { rough = 0.7; }
    else if (k == C_WOOD) { rough = 0.55; }
}

vec3 cityGlow(vec3 diff, vec3 N) {
    return diff * (vec3(0.018, 0.015, 0.022) + vec3(0.03, 0.02, 0.025) * max(N.z, 0.0));
}

// ── reflection rays ───────────────────────────────────────────────────────

// What a reflection ray sees where it lands: the same materials, lit by a few unshadowed lights.
vec3 shadeHit(uint inst, uint mesh, uint prim, vec2 bc, mat4x3 o2w, vec3 P, vec3 V) {
    uvec4 mi = meshInfo[mesh];
    uint i0 = idxData[mi.y + prim * 3u] + mi.x;
    uint i1 = idxData[mi.y + prim * 3u + 1u] + mi.x;
    uint i2 = idxData[mi.y + prim * 3u + 2u] + mi.x;
    vec4 n0 = unpackSnorm4x8(vtx[i0 * 5u + 3u]);
    vec4 c0 = unpackUnorm4x8(vtx[i0 * 5u + 4u]);
    vec4 n1 = unpackSnorm4x8(vtx[i1 * 5u + 3u]);
    vec4 c1 = unpackUnorm4x8(vtx[i1 * 5u + 4u]);
    vec4 n2 = unpackSnorm4x8(vtx[i2 * 5u + 3u]);
    vec4 c2 = unpackUnorm4x8(vtx[i2 * 5u + 4u]);
    float w0 = 1.0 - bc.x - bc.y;
    vec3 N = normalize(mat3(o2w) * (n0.xyz * w0 + n1.xyz * bc.x + n2.xyz * bc.y));
    if (dot(N, V) < 0.0) N = -N;
    vec3 raw = c0.rgb * w0 + c1.rgb * bc.x + c2.rgb * bc.y;
    int k = int(c0.a * 255.0 + 0.5);
    int ch = int(n0.w * 127.0 + 0.5);
    vec4 extra = instExtra[inst];
    int actor = dot(extra, extra) > 0.0 ? (k == C_FABRIC ? 2 : 1) : 0;
    float dist = length(P - uCamPos);
    vec3 col;
    if (isEmissive(k)) {
        col = emission(k, ch, k == C_SCREEN ? raw : pow(raw, vec3(2.2)), P, N, actor, extra, 1.0);
    } else if (k == C_GLASS || k == C_SHOPGLASS) {
        // a lit room or a dark pane, decided per window cell
        float lit = step(0.5, hash13(floor(P * vec3(0.3, 0.3, 0.31))));
        col = k == C_SHOPGLASS ? pow(raw, vec3(2.2)) * 1.2 : mix(vec3(0.004), vec3(0.6, 0.42, 0.25), lit);
        col += envColor(reflect(-V, N)) * 0.08;
    } else {
        vec3 albedo = pow(raw, vec3(2.2));
        float rough, metal;
        surfaceParams(k, ch, actor, extra, albedo, rough, metal);
        vec3 emit = vec3(0.0);
        if (k == C_TOWER) {
            float gm;
            emit = towerWindows(P, N, dist * 0.004, gm);
            albedo *= 1.0 - 0.7 * gm;
        }
        if (k == C_ASPHALT || k == C_PAVING || k == C_PAINT) albedo *= 0.5;
        vec3 diff = albedo * (1.0 - metal);
        col = shadeLights(P, N, V, diff, mix(vec3(0.04), albedo, metal), rough, 4, false) + cityGlow(diff, N) + emit;
    }
    return applyHaze(col, P);
}

/** Radiance arriving along a reflection ray from o in direction d. */
vec3 traceReflection(vec3 o, vec3 d) {
#ifdef RT
    rayQueryEXT rq;
    rayQueryInitializeEXT(rq, tlas, gl_RayFlagsOpaqueEXT, 0xFF, o, 0.03, d, 400.0);
    while (rayQueryProceedEXT(rq)) {}
    if (rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionTriangleEXT) {
        float t = rayQueryGetIntersectionTEXT(rq, true);
        return shadeHit(uint(rayQueryGetIntersectionInstanceIdEXT(rq, true)),
                        uint(rayQueryGetIntersectionInstanceCustomIndexEXT(rq, true)),
                        uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, true)),
                        rayQueryGetIntersectionBarycentricsEXT(rq, true),
                        rayQueryGetIntersectionObjectToWorldEXT(rq, true), o + d * t, -d);
    }
    return mix(skyColor(d), HAZE, exp(-max(d.z, 0.0) * 12.0) * 0.8);
#else
    return envColor(d);
#endif
}

#endif
