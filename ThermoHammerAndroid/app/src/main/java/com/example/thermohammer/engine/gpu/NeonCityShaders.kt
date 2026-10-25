package com.example.thermohammer.engine.gpu

// ─────────────────────────────────────────────────────────────────────────────
// NEON CITY SHADERS — GLSL ES 3.00, GPU Test 2.
//
//   Scene passes write linear HDR light (RGBA16F; on GPUs without half-float
//   render targets, RGB10_A2 scaled by uHdrWrite and read back with uHdrRead).
//   Every lit surface walks the light list of its 4 m ground cell (GGX +
//   Lambert, one shadowed searchlight); windows ray-trace a room behind the
//   glass; the wet street reflects a mirrored render, smeared into vertical
//   streaks; fog is ray-marched through the same light grid.
// ─────────────────────────────────────────────────────────────────────────────

internal object NeonCityShaders {

    /** Uniforms, noise, sky and environment shared by every stage. */
    const val COMMON = """
uniform vec3 uCamPos;
uniform float uTime;
uniform float uGroups[32];
uniform float uHdrWrite;
uniform float uHdrRead;

const float PI = 3.14159265;
const vec3 HAZE = vec3(0.060, 0.042, 0.052);   // rain mist lit by the city
const float HAZE_START = 150.0;
const float HAZE_DENSITY = 0.0026;

// vertex classes (CLASSES in build_neon_city.py)
const int C_CONCRETE = 0;
const int C_BRICK = 1;
const int C_TILE = 2;
const int C_METAL = 3;
const int C_GLASS = 4;
const int C_SHOPGLASS = 5;
const int C_EMISSIVE = 6;
const int C_ASPHALT = 7;
const int C_PAVING = 8;
const int C_PAINT = 9;
const int C_CARPAINT = 10;
const int C_CARGLASS = 11;
const int C_RUBBER = 12;
const int C_CHROME = 13;
const int C_HEADLIGHT = 14;
const int C_TAILLIGHT = 15;
const int C_FABRIC = 16;
const int C_SKIN = 17;
const int C_FOLIAGE = 18;
const int C_SCREEN = 19;
const int C_TOWER = 20;
const int C_LANTERN = 21;
const int C_TRAINWIN = 22;
const int C_WOOD = 23;

float hash11(float n) { return fract(sin(n) * 43758.5453); }

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float hash13(vec3 p3) {
    p3 = fract(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return fract((p3.x + p3.y) * p3.z);
}

vec2 hash22(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash12(i), hash12(i + vec2(1.0, 0.0)), u.x),
               mix(hash12(i + vec2(0.0, 1.0)), hash12(i + vec2(1.0, 1.0)), u.x), u.y);
}

float vnoise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    vec3 u = f * f * (3.0 - 2.0 * f);
    float a = mix(mix(hash13(i), hash13(i + vec3(1.0, 0.0, 0.0)), u.x),
                  mix(hash13(i + vec3(0.0, 1.0, 0.0)), hash13(i + vec3(1.0, 1.0, 0.0)), u.x), u.y);
    float b = mix(mix(hash13(i + vec3(0.0, 0.0, 1.0)), hash13(i + vec3(1.0, 0.0, 1.0)), u.x),
                  mix(hash13(i + vec3(0.0, 1.0, 1.0)), hash13(i + vec3(1.0, 1.0, 1.0)), u.x), u.y);
    return mix(a, b, u.z);
}

float fbm(vec2 p, int octaves) {
    float s = 0.0;
    float a = 0.5;
    float norm = 0.0;
    for (int i = 0; i < octaves; i++) {
        s += a * vnoise(p);
        norm += a;
        p = p * 2.03 + vec2(17.1, 9.3);
        a *= 0.5;
    }
    return s / norm;
}

// Weight for detail of `freq` cycles/m at `px` m/pixel: 1 when resolved, 0 once it would alias.
float resolved(float freq, float px) { return clamp(2.0 - 4.0 * px * freq, 0.0, 1.0); }

// Low overcast lit orange-pink from below by the city; drifting rain clouds.
vec3 skyColor(vec3 d) {
    float h = max(d.z, 0.0);
    vec3 c = mix(vec3(0.20, 0.11, 0.12), vec3(0.022, 0.022, 0.038), pow(h, 0.45));
    if (d.z > 0.0) {
        vec2 uv = d.xy / (d.z + 0.12) * 0.8 + vec2(uTime * 0.006, uTime * 0.012);
        float cl = fbm(uv * 1.4, 5);
        c *= 0.45 + 1.1 * cl;
    }
    return c;
}

// Cheap surroundings for glossy surfaces: sky above, city lights along the horizon, wet street below.
vec3 envColor(vec3 r) {
    if (r.z >= 0.0) {
        vec3 c = skyColor(r);
        float band = exp(-r.z * 7.0);
        float a = atan(r.y, r.x);
        float lights = smoothstep(0.6, 0.95, vnoise(vec2(a * 46.0, r.z * 38.0)));
        vec3 tint = mix(vec3(1.0, 0.35, 0.75), vec3(0.3, 0.75, 1.0), vnoise(vec2(a * 9.0, 3.0)));
        c += band * (vec3(0.16, 0.10, 0.09) + lights * tint * 1.6);
        return c;
    }
    return vec3(0.010, 0.009, 0.012) + vec3(0.09, 0.05, 0.07) * exp(r.z * 5.0);
}

vec3 applyHaze(vec3 col, vec3 w) {
    float d = length(w - uCamPos);
    return mix(col, HAZE, 1.0 - exp(-max(d - HAZE_START, 0.0) * HAZE_DENSITY));
}

// Half-float targets overflow to inf past 65504, and one inf/NaN smears a block through the bloom.
vec3 safeHdr(vec3 c) {
    if (!(c.r < 1e20 && c.g < 1e20 && c.b < 1e20 && c.r >= 0.0 && c.g >= 0.0 && c.b >= 0.0)) return vec3(0.0);
    return min(c, vec3(3000.0));
}
"""

    /** The light grid: cell lists, light records, GGX shading, fog in-scatter, the searchlight shadow. */
    const val LIGHTS = """
uniform highp sampler2D uLights;
uniform highp usampler2D uCells;
uniform highp usampler2D uIndices;
uniform highp sampler2DShadow uSearchShadow;
uniform mat4 uSearchMat;
uniform int uMaxMoving;                  // lights shaded per fragment: leading moving ones …
uniform int uMaxStatic;                  // … then static ones (separate, so traffic never evicts a sign)

const vec2 GRID_ORIGIN = vec2(-256.0, -320.0);
const float GRID_CELL = 4.0;
const ivec2 GRID_SIZE = ivec2(128, 336);
const float LIGHT_SCALE = 70.0;

// (first index, count) of the lights listed for p's cell
// p's light list: (first index, count, how many of them lead as moving lights)
ivec3 cellRange(vec3 p) {
    ivec2 c = ivec2(floor((p.xy - GRID_ORIGIN) / GRID_CELL));
    if (c.x < 0 || c.y < 0 || c.x >= GRID_SIZE.x || c.y >= GRID_SIZE.y) return ivec3(0);
    uint v = texelFetch(uCells, c, 0).r;
    return ivec3(int(v >> 12u), int(v & 63u), int((v >> 6u) & 63u));
}

ivec2 lightTexel(int listIndex) {
    int id = int(texelFetch(uIndices, ivec2(listIndex & 2047, listIndex >> 11), 0).r);
    return ivec2((id & 255) << 2, id >> 8);
}

float searchShadow(vec3 p) {
    vec4 s = uSearchMat * vec4(p, 1.0);
    if (s.w <= 0.0) return 0.0;
    vec3 c = s.xyz / s.w;
    if (c.x <= 0.0 || c.x >= 1.0 || c.y <= 0.0 || c.y >= 1.0 || c.z >= 1.0) return 1.0;
    const float T = 0.7 / 1024.0;
    float z = c.z - 0.0006;
    return 0.25 * (texture(uSearchShadow, vec3(c.xy + vec2(-T, -T), z))
                 + texture(uSearchShadow, vec3(c.xy + vec2( T, -T), z))
                 + texture(uSearchShadow, vec3(c.xy + vec2(-T,  T), z))
                 + texture(uSearchShadow, vec3(c.xy + vec2( T,  T), z)));
}

// Distance + cone attenuation of one light at p (direction to the light in L).
float lightAtten(vec4 l0, vec4 l1, ivec2 b, vec3 p, out vec3 L) {
    vec3 lv = l0.xyz - p;
    float d2 = dot(lv, lv);
    float r2 = l0.w * l0.w;
    L = vec3(0.0, 0.0, 1.0);
    if (d2 >= r2) return 0.0;
    L = lv * inversesqrt(d2);
    float f = d2 / r2;
    float att = (1.0 - f) * (1.0 - f) / (d2 + 1.0);
    if (l1.w > 0.5) {
        vec4 l2 = texelFetch(uLights, b + ivec2(2, 0), 0);
        att *= smoothstep(l2.w, mix(l2.w, 1.0, 0.45), dot(-L, l2.xyz));
        if (l1.w > 1.5 && att > 0.0) att *= searchShadow(p);
    }
    return att * uGroups[int(texelFetch(uLights, b + ivec2(3, 0), 0).x)];
}

// Light reflected toward v by every light in p's cell: Lambert diffuse + GGX specular.
vec3 shadeLights(vec3 p, vec3 n, vec3 v, vec3 diff, vec3 f0, float rough) {
    ivec3 r = cellRange(p);
    int nm = min(r.z, uMaxMoving);
    int cnt = nm + min(r.y - r.z, uMaxStatic);
    float a = max(rough * rough, 0.012);                  // mirror puddles: sharpness comes from the reflection
    float a2 = a * a;
    float k = a * 0.5;
    float ndv = max(dot(n, v), 1e-3);
    float gv = ndv * (1.0 - k) + k;
    vec3 ps = p + n * 0.12;
    vec3 sum = vec3(0.0);
    for (int j = 0; j < cnt; j++) {
        ivec2 b = lightTexel(r.x + (j < nm ? j : r.z + j - nm));
        vec4 l0 = texelFetch(uLights, b, 0);
        vec4 l1 = texelFetch(uLights, b + ivec2(1, 0), 0);
        vec3 L;
        float att = lightAtten(l0, l1, b, ps, L);
        float ndl = dot(n, L);
        if (att <= 0.0 || ndl <= 0.0) continue;
        vec3 h = normalize(L + v);
        float ndh = max(dot(n, h), 0.0);
        float dd = ndh * ndh * (a2 - 1.0) + 1.0;
        float D = a2 / (PI * dd * dd);
        float vis = 0.25 / ((ndl * (1.0 - k) + k) * gv);
        vec3 F = f0 + (1.0 - f0) * pow(1.0 - max(dot(L, h), 0.0), 5.0);
        sum += l1.rgb * (att * ndl) * (diff * (1.0 / PI) + F * (D * vis));
    }
    return sum * LIGHT_SCALE;
}

float phaseHG(float c, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * PI * pow(1.0 + g2 - 2.0 * g * c, 1.5));
}

// Light scattered toward the camera by mist/rain at p (rd = view ray direction).
// movingOnly: just the cell's leading moving lights, so traffic never swaps static lights in and out
vec3 scatterLights(vec3 p, vec3 rd, int maxLights, bool movingOnly) {
    ivec3 r = cellRange(p);
    int cnt = min(movingOnly ? r.z : r.y, maxLights);
    vec3 sum = vec3(0.0);
    for (int i = 0; i < cnt; i++) {
        ivec2 b = lightTexel(r.x + i);
        vec4 l0 = texelFetch(uLights, b, 0);
        vec4 l1 = texelFetch(uLights, b + ivec2(1, 0), 0);
        vec3 L;
        float att = lightAtten(l0, l1, b, p, L);
        if (att <= 0.0) continue;
        sum += l1.rgb * att * phaseHG(dot(L, rd), 0.35);
    }
    return sum * LIGHT_SCALE;
}
"""

    // ── Objects: buildings, props, cars, people, trains ─────────────────────

    const val OBJECT_VS = """
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec4 aNormal;    // normal + animation channel / 127
layout(location = 2) in vec4 aColor;     // sRGB colour (or parameters) + class / 255
layout(location = 3) in vec4 aRow0;      // instance transform rows
layout(location = 4) in vec4 aRow1;
layout(location = 5) in vec4 aRow2;
layout(location = 6) in vec4 aExtra0;    // car: paint + brake · person: umbrella + coat shade
layout(location = 7) in vec4 aExtra1;    // person: walk phase, swing amplitude

uniform mat4 uViewProj;
uniform int uActor;                      // 0 static · 1 car · 2 person · 3 train/drone
uniform float uSeed;

out vec3 vWorld;
#ifndef DEPTH
out vec3 vNormal;
out vec3 vColor;
out vec4 vExtra;
out vec3 vRoom;                          // glass: position in the window's frame
out vec3 vRoomView;                      // glass: view ray in the window's frame
flat out int vClass;
flat out int vChan;
flat out float vRand;
#endif

void main() {
    int chan = int(aNormal.w * 127.0 + 0.5);
    vec3 p = aPos;
    vec3 n = aNormal.xyz;
    if (uActor == 2) {
        // walk cycle: legs swing about the hip, arms about the shoulder (channel 1-4)
        int part = chan & 31;
        float swing = aExtra1.y * sin(aExtra1.x);
        float ang = part == 1 ? swing : part == 2 ? -swing : part == 3 ? -0.8 * swing : part == 4 ? 0.8 * swing : 0.0;
        if (ang != 0.0) {
            float pivot = part <= 2 ? 0.93 : 1.41;
            float c = cos(ang);
            float s = sin(ang);
            vec2 yz = vec2(p.y, p.z - pivot);
            p.yz = vec2(c * yz.x - s * yz.y, s * yz.x + c * yz.y + pivot);
            n.yz = vec2(c * n.y - s * n.z, s * n.y + c * n.z);
        }
    }
    vec4 hp = vec4(p, 1.0);
    vec3 w = vec3(dot(aRow0, hp), dot(aRow1, hp), dot(aRow2, hp));
    vWorld = w;
    gl_Position = uViewProj * vec4(w, 1.0);
#ifndef DEPTH
    vec3 nw = normalize(vec3(dot(aRow0.xyz, n), dot(aRow1.xyz, n), dot(aRow2.xyz, n)));
    int cls = int(aColor.a * 255.0 + 0.5);
    vNormal = nw;
    vClass = cls;
    vChan = chan;
    vColor = cls == C_SCREEN ? aColor.rgb : pow(aColor.rgb, vec3(2.2));
    vExtra = aExtra0;
    vec3 origin = vec3(aRow0.w, aRow1.w, aRow2.w);
    vRand = hash13(origin * 0.37 + uSeed);
    // window frame: x along the facade, y out of it, z up (origin = window sill, or world for merged chunks)
    vec3 O = normalize(vec3(nw.xy, 0.0) + vec3(1e-5, 0.0, 0.0));
    vec3 T = vec3(O.y, -O.x, 0.0);
    vec3 d = w - origin;
    vRoom = vec3(dot(d, T), dot(d, O), d.z);
    vec3 vv = w - uCamPos;
    vRoomView = vec3(dot(vv, T), dot(vv, O), vv.z);
#endif
}
"""

    const val OBJECT_FS = """
in vec3 vWorld;
in vec3 vNormal;
in vec3 vColor;
in vec4 vExtra;
in vec3 vRoom;
in vec3 vRoomView;
flat in int vClass;
flat in int vChan;
flat in float vRand;

uniform int uActor;
uniform int uInstanced;
uniform mediump sampler2D uReflection;
uniform vec2 uInvViewport;
uniform int uReflTaps;
out vec4 oColor;

const float TIER[4] = float[4](1.0, 2.5, 5.0, 9.0);

float groupValue(int ch, vec3 p) {
    int g = ch & 31;
    if (g >= 9 && g <= 12) {                               // letter chase: a bright band runs along the sign
        float s = dot(p, vec3(0.7, 0.7, 0.3)) * 0.9 - uTime * (1.6 + 0.4 * float(g - 9));
        return 0.2 + 0.8 * smoothstep(0.32, 0.12, abs(fract(s) - 0.5));
    }
    return uGroups[g];
}

// LED billboard: animated ad content on a visible pixel grid.
vec3 screenContent(vec2 uv, float seed) {
    float t = uTime * 0.6 + seed * 20.0;
    float scene = floor(mod(t / 4.0 + seed * 3.0, 3.0));
    vec3 c;
    if (scene < 0.5) {                                     // brand gradient with a pulsing logo disc
        c = mix(vec3(1.0, 0.1, 0.45), vec3(0.15, 0.25, 1.0), uv.x + 0.25 * sin(t + uv.y * 3.0));
        float r = length((uv - vec2(0.3, 0.55)) * vec2(2.0, 1.0));
        c = mix(c, vec3(1.0, 0.95, 0.8), smoothstep(0.24, 0.2, r) * (0.7 + 0.3 * sin(t * 3.0)));
        c *= 0.75 + 0.25 * step(0.5, fract(uv.y * 6.0 - t));
    } else if (scene < 1.5) {                              // scrolling headline bars
        float row = floor(uv.y * 5.0);
        float bar = step(0.35, hash12(vec2(floor(uv.x * 9.0 + t * 2.0 * (1.0 + row * 0.3)), row)));
        c = mix(vec3(0.02, 0.05, 0.12), vec3(0.2, 0.9, 1.0), bar * step(0.2, fract(uv.y * 5.0)));
        c += vec3(1.0, 0.5, 0.05) * step(0.82, uv.y);
    } else {                                               // abstract motion (music video)
        float v = sin(uv.x * 9.0 + t * 2.0) + sin(uv.y * 7.0 - t * 1.3) + sin((uv.x + uv.y) * 6.0 + t);
        c = 0.5 + 0.5 * cos(vec3(0.0, 2.1, 4.2) + v * 1.4 + t * 0.4);
    }
    vec2 px = fract(uv * vec2(160.0, 80.0));
    float grid = smoothstep(0.0, 0.18, px.x) * smoothstep(0.0, 0.18, px.y);
    float fw = length(fwidth(uv * vec2(160.0, 80.0)));
    return c * mix(grid, 0.72, clamp(fw * 1.2, 0.0, 1.0));
}

// A room behind the glass, ray-traced in the window's frame. Returns emitted light.
vec3 interior(vec3 q, vec3 v, float style, bool shop) {
    float roomW = shop ? 6.0 : style > 0.7 ? 4.2 : 3.4;
    float h = shop ? 3.4 : 3.0;
    float base = shop ? 0.15 : uInstanced == 1 ? (style > 0.9 ? 0.0 : -0.9) : 4.8;
    float cx = floor(q.x / roomW + 0.5);
    float cz = shop ? 0.0 : floor((q.z - base) / 3.2);
    float x0 = (cx - 0.5) * roomW;
    float z0 = base + cz * 3.2;
    float id = hash13(vec3(cx, cz, vRand * 97.0));
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
    vec3 lamp = shop ? vColor : style > 0.7 ? vec3(0.80, 0.90, 1.0) : mix(vec3(1.0, 0.62, 0.32), vec3(1.0, 0.80, 0.55), hash11(id * 7.0));
    vec3 wall = mix(vec3(0.55, 0.50, 0.42), vec3(0.42, 0.48, 0.52), hash11(id * 3.3));
    vec3 c;
    if (t == tz) {
        c = hp.z > z0 + 0.5 * h ? vec3(0.75) : (shop ? vec3(0.5, 0.48, 0.45) : vec3(0.32, 0.20, 0.12));
        if (hp.z > z0 + 0.5 * h && (style > 0.7 || shop)) {    // fluorescent panels in the ceiling
            c += step(0.7, fract(ry * 3.0)) * step(0.3, fract(rx * 2.0)) * 2.0;
        }
    } else if (t == tx) {
        c = wall * (0.8 + 0.2 * step(0.15, rz));
        if (shop) c = mix(c, vColor * 0.9, step(0.15, rz) * step(rz, 0.85) * step(0.5, fract(ry * 6.0)));
    } else {
        c = wall;
        if (shop) {                                        // shelves of goods along the back
            float shelf = step(0.45, fract(rz * 5.0)) * step(0.12, rz) * step(rz, 0.85);
            c = mix(c, mix(vColor, vec3(1.0), hash12(floor(vec2(rx * 14.0, rz * 5.0)))), shelf);
        } else {
            c *= 1.0 - 0.6 * step(rz, 0.3) * step(0.2, rx) * step(rx, 0.8);   // sofa / desk
            if (hash11(id * 5.1) > 0.5) c = mix(c, vec3(0.08, 0.06, 0.05), step(abs(rx - 0.5), 0.12) * step(abs(rz - 0.62), 0.12));
        }
    }
    // the lamp hangs near the ceiling in the middle of the room
    vec3 lp = vec3(x0 + 0.5 * roomW, q.y - depth * 0.45, z0 + h - 0.3);
    float fall = 1.6 / (1.0 + 0.12 * dot(hp - lp, hp - lp));
    vec3 e = lit ? c * lamp * fall * (shop ? 1.8 : 1.0) : c * vec3(0.006, 0.006, 0.01);
    if (tv) e += vec3(0.25, 0.45, 1.0) * (0.25 + 0.2 * sin(uTime * 7.0 + id * 40.0) * sin(uTime * 2.3)) * smoothstep(0.5, 0.0, ry);
    if (!shop && hash11(id * 9.7) > 0.62) {                // blinds or curtains in front of the room
        float slats = 0.55 + 0.45 * step(0.25, fract(q.z * 9.0));
        vec3 cloth = lit ? lamp * (0.18 + 0.2 * hash11(id * 3.9)) * slats : vec3(0.008) * slats;
        e = mix(e, cloth, 0.85);
    }
    return e;
}

// Tower facades seen from afar: a grid of windows, some lit.
vec3 towerWindows(vec3 p, vec3 n, out float glassMask) {
    vec3 t = normalize(vec3(n.y, -n.x, 0.0) + vec3(1e-4, 0.0, 0.0));
    vec2 uv = vec2(dot(p, t) / 1.8, p.z / 3.5);
    vec2 cell = floor(uv);
    vec2 f = fract(uv);
    float px = length(fwidth(uv));
    float win = step(abs(f.x - 0.5), 0.32) * step(abs(f.y - 0.55), 0.3);
    win = mix(win, 0.38, clamp(px * 1.5 - 0.3, 0.0, 1.0));
    float r = hash12(cell + floor(n.xy * 3.0) * 17.0);
    float lit = step(0.62, r);
    lit = mix(lit, 0.38, clamp(px * 1.5 - 0.3, 0.0, 1.0));
    glassMask = win;
    vec3 warm = mix(vec3(1.0, 0.66, 0.36), vec3(0.75, 0.88, 1.0), step(0.86, r));
    return win * lit * warm * 1.4;
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

void main() {
    vec3 P = vWorld;
    int k = vClass;
    bool ground = k == C_ASPHALT || k == C_PAVING || k == C_PAINT;
#ifdef MIRROR
    if (P.z < -0.02 || (ground && P.z < 0.3)) discard;
#endif
    vec3 N = normalize(vNormal);
    vec3 V = normalize(uCamPos - P);
    if (dot(N, V) < 0.0) N = -N;
    vec3 col;

    if (k == C_EMISSIVE || k == C_HEADLIGHT || k == C_TAILLIGHT || k == C_LANTERN || k == C_TRAINWIN || k == C_SCREEN) {
        float tier = TIER[(vChan >> 5) & 3];
        vec3 base = vColor;
        float strength = k == C_HEADLIGHT ? 2.0 : k == C_TAILLIGHT ? 1.2 : k == C_TRAINWIN ? 0.45 : k == C_LANTERN ? 0.9 : 1.0;
        if (k == C_TAILLIGHT && uActor == 1) strength *= 1.0 + 2.5 * vExtra.w;
        if (k == C_SCREEN) base = screenContent(vColor.rg, vColor.b) * 0.35;
        if (k == C_LANTERN) base *= 0.6 + 0.4 * smoothstep(-0.2, 0.6, N.z + 0.3);   // paper glows brighter on top
        col = base * (tier * strength * groupValue(vChan, P));
        // a glossy wet film over every lamp and sign
        col += envColor(reflect(-V, N)) * 0.04;
    } else if (k == C_GLASS || k == C_SHOPGLASS) {
        bool shop = k == C_SHOPGLASS;
        vec3 inside = interior(vRoom, normalize(vRoomView), shop ? 0.5 : vColor.b, shop);
        float F = 0.04 + 0.96 * pow(1.0 - max(dot(N, V), 0.0), 5.0);
        col = inside * (1.0 - F) + envColor(reflect(-V, N)) * F;
        col += shadeLights(P, N, V, vec3(0.0), vec3(0.04), 0.07);
    } else {
        vec3 albedo = vColor;
        float rough = 0.6;
        float metal = 0.0;
        vec3 emit = vec3(0.0);
        float wet = 0.0;
        if (k == C_CARPAINT) { albedo = vColor * (uActor == 1 ? vExtra.rgb : vec3(0.05)); rough = 0.22; }
        else if (k == C_CARGLASS) { albedo = vec3(0.01); rough = 0.05; }
        else if (k == C_RUBBER) { rough = 0.55; }
        else if (k == C_CHROME) { metal = 1.0; rough = 0.18; }
        else if (k == C_METAL) { metal = 0.6; rough = 0.38; }
        else if (k == C_FABRIC) {
            int part = vChan & 31;
            if (uActor == 2 && part == 5) { albedo = vExtra.rgb; rough = 0.25; }     // wet umbrella
            else if (uActor == 2 && part == 6) albedo = vec3(vExtra.w) * vec3(0.55, 0.5, 0.48) * 0.6;
            rough = part == 5 ? 0.25 : 0.8;
        }
        else if (k == C_SKIN) { rough = 0.5; }
        else if (k == C_FOLIAGE) { rough = 0.7; }
        else if (k == C_WOOD) { rough = 0.55; }
        else if (k == C_TOWER) {
            float glassMask;
            emit = towerWindows(P, N, glassMask);
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
            float grime = fbm(uv * vec2(0.35, 0.18), 3);
            albedo *= 0.7 + 0.6 * grime;
            // rain runs down the walls in streaks: darker and glossier
            float streak = smoothstep(0.45, 0.8, vnoise(vec2(uv.x * 2.5, uv.y * 0.12 + uTime * 0.05)));
            wet = streak * (1.0 - abs(N.z)) * resolved(1.0, px) + 0.35;
            rough = mix(rough, 0.18, wet * 0.6);
            albedo *= 1.0 - 0.35 * wet;
        }

        vec3 refl = vec3(0.0);
        float reflAmount = 0.0;
        if (ground) {
            // wet street: standing water in puddles, ripples from the rain, mirror reflections
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
#ifndef MIRROR
            vec2 suv = gl_FragCoord.xy * uInvViewport + N.xy * 0.035;
            float spread = 0.004 + rough * 0.09;
            float lod = rough * 5.0;
            float wsum = 0.0;
            for (int i = 0; i < uReflTaps; i++) {
                float o = (float(i) + 0.5) / float(uReflTaps) * 2.0 - 1.0;
                float wt = exp(-o * o * 2.5);
                refl += textureLod(uReflection, vec2(suv.x, suv.y + o * spread), lod).rgb * wt;
                wsum += wt;
            }
            refl *= uHdrRead / wsum;
            float F = 0.02 + 0.98 * pow(1.0 - max(dot(N, V), 0.0), 5.0);
            reflAmount = mix(0.25, 1.0, puddle) * F;
#endif
        }

        vec3 diff = albedo * (1.0 - metal);
        vec3 f0 = mix(vec3(0.04 + 0.02 * wet), albedo, metal);
        col = shadeLights(P, N, V, diff, f0, rough);
        col += diff * (vec3(0.018, 0.015, 0.022) + vec3(0.03, 0.02, 0.025) * max(N.z, 0.0));   // city glow
        float Fe = 0.04 + 0.96 * pow(1.0 - max(dot(N, V), 0.0), 5.0);
        float gloss = (1.0 - rough) * (1.0 - rough);
        if (!ground) col += envColor(reflect(-V, N)) * mix(Fe * gloss, 0.7, metal) * (k == C_CARPAINT ? 1.6 : 1.0);
        col = mix(col, refl, reflAmount) + emit;
    }
    col = applyHaze(col, P);
    oColor = vec4(safeHdr(col) * uHdrWrite, 1.0);
}
"""

    const val DEPTH_FS = "void main() {}"

    // ── Sky ─────────────────────────────────────────────────────────────────

    const val FULLSCREEN_VS = """
out vec2 vUV;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUV = p;
    gl_Position = vec4(p * 2.0 - 1.0, 1.0, 1.0);
}
"""

    const val SKY_FS = """
uniform mat4 uInvViewProj;
in vec2 vUV;
out vec4 oColor;
void main() {
    vec2 ndc = vUV * 2.0 - 1.0;
    vec4 a = uInvViewProj * vec4(ndc, -1.0, 1.0);
    vec4 b = uInvViewProj * vec4(ndc, 1.0, 1.0);
    vec3 dir = normalize(b.xyz / b.w - a.xyz / a.w);
#ifdef MIRROR
    dir.z = -dir.z;
#endif
    vec3 col = mix(skyColor(dir), HAZE, exp(-max(dir.z, 0.0) * 12.0) * 0.8);
    oColor = vec4(col * uHdrWrite, 1.0);
}
"""

    // ── Rain, splashes, light halos, steam (additive / premultiplied) ───────

    const val RAIN_VS = """
uniform mat4 uViewProj;
uniform vec3 uCamFwd;
out vec2 vQ;
out vec3 vLight;

const vec3 BOX = vec3(36.0, 36.0, 24.0);
const vec2 CORNERS[6] = vec2[6](vec2(-1.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 1.0),
                                vec2(-1.0, 0.0), vec2(1.0, 1.0), vec2(-1.0, 1.0));

void main() {
    float id = float(gl_InstanceID);
    vec3 seed = vec3(hash11(id * 0.1371 + 0.3), hash11(id * 0.7193 + 3.1), hash11(id * 1.3711 + 7.7));
    float speed = 8.0 + 3.0 * hash11(id * 2.17);
    vec3 fall = vec3(1.1, 0.45, -speed);
    vec3 centre = uCamPos + vec3(uCamFwd.xy, 0.0) * (BOX.x * 0.36);
    vec3 p = seed * BOX + fall * uTime;
    p = centre + mod(p - centre + BOX * 0.5, BOX) - BOX * 0.5;
    vec3 axis = normalize(fall);
    float dist = length(uCamPos - p);
    float len = 0.04 * speed * (1.0 + 0.5 * hash11(id * 5.3));       // ~1/20 s exposure
    vec3 side = normalize(cross(axis, uCamPos - p)) * (0.004 + dist * 0.0009);
    vec2 q = CORNERS[gl_VertexID];
    vec3 w = p + side * q.x + axis * (q.y * len);
    vQ = q;
    // drops light up where they cross a lamp's beam, mostly when back-lit
    vec3 rd = normalize(p - uCamPos);
    vLight = (scatterLights(p, rd, 6, false) * 0.35 + HAZE * 0.6) * smoothstep(0.8, 2.5, dist) * (1.0 - smoothstep(24.0, 34.0, dist));
    gl_Position = uViewProj * vec4(w, 1.0);
}
"""

    const val RAIN_FS = """
in vec2 vQ;
in vec3 vLight;
out vec4 oColor;
void main() {
    float a = (1.0 - abs(vQ.x)) * smoothstep(0.0, 0.3, vQ.y) * smoothstep(1.0, 0.6, vQ.y);
    oColor = vec4(vLight * a * uHdrWrite, 0.0);
}
"""

    const val SPLASH_VS = """
uniform mat4 uViewProj;
uniform vec3 uCamFwd;
uniform vec3 uCamRight;
out vec2 vQ;
out float vLife;
out vec3 vLight;
const vec2 CORNERS[6] = vec2[6](vec2(-1.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 1.0),
                                vec2(-1.0, 0.0), vec2(1.0, 1.0), vec2(-1.0, 1.0));
void main() {
    float id = float(gl_InstanceID);
    float rate = 2.4 + hash11(id * 3.7);
    float cyc = uTime * rate + hash11(id * 1.9);
    float life = fract(cyc);
    float n = floor(cyc);
    vec2 r = vec2(hash11(id * 0.37 + n * 1.13), hash11(id * 0.91 + n * 2.71));
    vec2 ahead = uCamPos.xy + uCamFwd.xy * 14.0;
    vec2 xy = ahead + (r - 0.5) * vec2(30.0, 30.0);
    float z = abs(xy.x) > 7.0 ? 0.16 : 0.01;
    float size = 0.05 + 0.1 * life;
    vec2 q = CORNERS[gl_VertexID];
    vec3 w = vec3(xy, z) + uCamRight * (q.x * size) + vec3(0.0, 0.0, q.y * size * 0.7);
    vQ = q;
    vLife = life;
    float dist = length(vec3(xy, z) - uCamPos);
    vLight = (scatterLights(vec3(xy, z + 0.2), normalize(vec3(xy, z) - uCamPos), 4, false) * 0.25 + HAZE)
           * (1.0 - smoothstep(16.0, 24.0, dist));
    gl_Position = uViewProj * vec4(w, 1.0);
}
"""

    const val SPLASH_FS = """
in vec2 vQ;
in float vLife;
in vec3 vLight;
out vec4 oColor;
void main() {
    // a thin crown: an arc that rises and thins as the splash ages
    float r = length(vec2(vQ.x, vQ.y * 1.4));
    float ring = smoothstep(0.25, 0.0, abs(r - 0.75)) * step(0.0, vQ.y);
    float a = ring * (1.0 - vLife) * 0.9;
    oColor = vec4(vLight * a * uHdrWrite, 0.0);
}
"""

    /** Soft halo around each light, the glow of rain mist close to the lamp. */
    const val GLOW_VS = """
uniform highp sampler2D uLights;
uniform mat4 uViewProj;
uniform vec3 uCamRight;
uniform vec3 uCamUp;
out vec2 vQ;
out vec3 vCol;
const vec2 CORNERS[6] = vec2[6](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0),
                                vec2(-1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0));
void main() {
    int id = gl_InstanceID;
    ivec2 b = ivec2((id & 255) << 2, id >> 8);
    vec4 l0 = texelFetch(uLights, b, 0);
    vec4 l1 = texelFetch(uLights, b + ivec2(1, 0), 0);
    float g = uGroups[int(texelFetch(uLights, b + ivec2(3, 0), 0).x)];
    vec3 toCam = uCamPos - l0.xyz;
    float dist = length(toCam);
    float size = 0.5 + l0.w * 0.09 + dist * 0.004;
    if (l1.w > 0.5) {
        // a spot glares only into its beam; lamps aimed at the street keep a dim halo round the head
        vec4 l2 = texelFetch(uLights, b + ivec2(2, 0), 0);
        float facing = smoothstep(l2.w - 0.3, 1.0, dot(l2.xyz, toCam / max(dist, 0.01)));
        g *= max(l2.z < -0.5 ? 0.35 : 0.0, facing);
    }
    vec3 c = l1.rgb * g * (0.022 / (1.0 + dist * 0.015)) * (1.0 - smoothstep(110.0, 160.0, dist));
    vec2 q = CORNERS[gl_VertexID];
    vec3 w = l0.xyz + toCam / max(dist, 0.01) * min(0.6, dist * 0.5) + (uCamRight * q.x + uCamUp * q.y) * size;
    vQ = q;
    vCol = c;
    gl_Position = dist < 1.0 ? vec4(0.0, 0.0, 2.0, 1.0) : uViewProj * vec4(w, 1.0);
}
"""

    const val GLOW_FS = """
in vec2 vQ;
in vec3 vCol;
out vec4 oColor;
void main() {
    float r2 = dot(vQ, vQ);
    float a = exp(-r2 * 4.0) + 0.6 * exp(-r2 * 30.0);
    oColor = vec4(vCol * a * uHdrWrite, 0.0);
}
"""

    const val STEAM_VS = """
uniform mat4 uViewProj;
uniform vec3 uOrigin;
uniform vec3 uCamRight;
uniform vec3 uCamUp;
out vec2 vQ;
out float vAlpha;
out vec3 vLight;
out float vSeed;
const vec2 CORNERS[6] = vec2[6](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0),
                                vec2(-1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0));
void main() {
    float id = float(gl_InstanceID);
    float life = fract(uTime / 6.0 + id / 24.0);
    vec3 p = uOrigin + vec3(1.4 * life * life + 0.3 * sin(id * 3.1 + uTime * 0.7),
                            0.6 * life + 0.3 * cos(id * 1.7 + uTime * 0.5), 0.2 + life * 5.5);
    float size = 0.4 + life * 2.6;
    vec2 q = CORNERS[gl_VertexID];
    vQ = q;
    vSeed = id;
    vAlpha = smoothstep(0.0, 0.12, life) * (1.0 - life) * 0.35;
    vLight = scatterLights(p, normalize(p - uCamPos), 6, false) * 0.6 + HAZE * 2.0;
    gl_Position = uViewProj * vec4(p + (uCamRight * q.x + uCamUp * q.y) * size, 1.0);
}
"""

    const val STEAM_FS = """
in vec2 vQ;
in float vAlpha;
in vec3 vLight;
in float vSeed;
out vec4 oColor;
void main() {
    float r = length(vQ);
    if (r > 1.0) discard;
    float puff = fbm(vQ * 1.6 + vec2(vSeed * 0.37, uTime * 0.2), 3);
    float a = vAlpha * smoothstep(1.0, 0.2, r) * (0.4 + 0.6 * puff);
    oColor = vec4(vLight * a * uHdrWrite, a);
}
"""

    // ── Post: volumetric fog, combine, bloom, streaks, final ────────────────

    const val POST_VS = """
out vec2 vUV;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUV = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

    /** Half-resolution ray march through rain mist, lit by the grid (and the searchlight's shadow map). */
    const val FOG_FS = """
uniform highp sampler2D uDepth;
uniform mat4 uInvViewProj;
uniform int uFogSteps;
uniform int uFogLights;
uniform float uFrame;
in vec2 vUV;
out vec4 oColor;

const float FOG_MAX = 170.0;
const float FOG_DENSITY = 0.011;
const vec3 FOG_AMBIENT = HAZE;

void main() {
    float d = texture(uDepth, vUV).r;
    vec4 e = uInvViewProj * vec4(vUV * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    vec3 end = e.xyz / e.w;
    vec3 rd = normalize(end - uCamPos);
    float tEnd = min(length(end - uCamPos), FOG_MAX);
    float j = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))) + uFrame * 0.618034);
    float T = 1.0;
    vec3 S = vec3(0.0);
    float steps = float(uFogSteps);
    for (int i = 0; i < uFogSteps; i++) {
        float u = (float(i) + j) / steps;
        float t = tEnd * u * u;                            // denser sampling near the camera
        float dt = tEnd * 2.0 * u / steps;
        vec3 p = uCamPos + rd * t;
        float dens = FOG_DENSITY * exp(-max(p.z, 0.0) * 0.025)
                   * (0.5 + vnoise3(p * vec3(0.06, 0.06, 0.03) + vec3(0.3, 0.1, 0.5) * uTime));
        vec3 li = FOG_AMBIENT + scatterLights(p, rd, uFogLights, true);
        S += T * dens * dt * li;
        T *= exp(-dens * dt);
    }
    oColor = vec4(safeHdr(S) * uHdrWrite, T);
}
"""

    const val COMBINE_FS = """
uniform mediump sampler2D uScene;
uniform mediump sampler2D uFog;
in vec2 vUV;
out vec4 oColor;
void main() {
    vec4 f = texture(uFog, vUV);
    oColor = vec4(texture(uScene, vUV).rgb * f.a + f.rgb, 1.0);
}
"""

    /** 13-tap downsample; THRESHOLD keeps highlights only (first level). */
    const val DOWN_FS = """
uniform mediump sampler2D uSrc;
uniform vec2 uTexel;
in vec2 vUV;
out vec4 oColor;
void main() {
    vec2 t = uTexel;
    vec3 a = texture(uSrc, vUV + t * vec2(-2.0, -2.0)).rgb;
    vec3 b = texture(uSrc, vUV + t * vec2( 0.0, -2.0)).rgb;
    vec3 c = texture(uSrc, vUV + t * vec2( 2.0, -2.0)).rgb;
    vec3 d = texture(uSrc, vUV + t * vec2(-1.0, -1.0)).rgb;
    vec3 e = texture(uSrc, vUV).rgb;
    vec3 f = texture(uSrc, vUV + t * vec2( 1.0, -1.0)).rgb;
    vec3 g = texture(uSrc, vUV + t * vec2(-2.0,  0.0)).rgb;
    vec3 h = texture(uSrc, vUV + t * vec2( 2.0,  0.0)).rgb;
    vec3 i = texture(uSrc, vUV + t * vec2(-1.0,  1.0)).rgb;
    vec3 j = texture(uSrc, vUV + t * vec2( 1.0,  1.0)).rgb;
    vec3 k = texture(uSrc, vUV + t * vec2(-2.0,  2.0)).rgb;
    vec3 l = texture(uSrc, vUV + t * vec2( 0.0,  2.0)).rgb;
    vec3 m = texture(uSrc, vUV + t * vec2( 2.0,  2.0)).rgb;
    vec3 s = (d + f + i + j) * 0.125 + (a + c + k + m) * 0.03125 + (b + g + h + l) * 0.0625 + e * 0.125;
#ifdef THRESHOLD
    vec3 x = safeHdr(s * uHdrRead);
    float br = max(max(x.r, x.g), x.b);
    float soft = clamp(br - 0.6, 0.0, 1.2);
    soft = soft * soft / 4.8;
    s = x * (max(soft, br - 1.2) / max(br, 1e-4)) * uHdrWrite;
#endif
    oColor = vec4(s, 1.0);
}
"""

    /** 9-tap tent upsample, blended additively onto the next larger level. */
    const val UP_FS = """
uniform mediump sampler2D uSrc;
uniform vec2 uTexel;
uniform float uWeight;
in vec2 vUV;
out vec4 oColor;
void main() {
    vec2 t = uTexel;
    vec3 s = texture(uSrc, vUV).rgb * 4.0
           + (texture(uSrc, vUV + vec2(t.x, 0.0)).rgb + texture(uSrc, vUV - vec2(t.x, 0.0)).rgb
            + texture(uSrc, vUV + vec2(0.0, t.y)).rgb + texture(uSrc, vUV - vec2(0.0, t.y)).rgb) * 2.0
           + texture(uSrc, vUV + t).rgb + texture(uSrc, vUV - t).rgb
           + texture(uSrc, vUV + vec2(t.x, -t.y)).rgb + texture(uSrc, vUV + vec2(-t.x, t.y)).rgb;
    oColor = vec4(s * (uWeight / 16.0), 1.0);
}
"""

    /** Horizontal 9-tap blur with a growing stride: anamorphic streaks off bright lights. */
    const val STREAK_FS = """
uniform mediump sampler2D uSrc;
uniform vec2 uStep;
in vec2 vUV;
out vec4 oColor;
void main() {
    vec3 s = vec3(0.0);
    float wsum = 0.0;
    for (int i = -4; i <= 4; i++) {
        float w = exp(-float(i * i) * 0.12);
        s += texture(uSrc, vUV + uStep * float(i)).rgb * w;
        wsum += w;
    }
    oColor = vec4(s / wsum, 1.0);
}
"""

    const val FINAL_FS = """
uniform mediump sampler2D uHdr;
uniform mediump sampler2D uBloom;
uniform mediump sampler2D uStreak;
uniform highp sampler2D uDepth;
uniform mat4 uInvViewProj;
uniform mat4 uPrevViewProj;
uniform int uBlurSamples;
uniform float uExposure;
in vec2 vUV;
out vec4 oColor;

vec3 aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

void main() {
    // camera motion blur: reproject this pixel with last frame's camera
    float d = texture(uDepth, vUV).r;
    vec4 wp = uInvViewProj * vec4(vUV * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    vec4 pp = uPrevViewProj * vec4(wp.xyz / wp.w, 1.0);
    vec2 prev = pp.xy / pp.w * 0.5 + 0.5;
    vec2 vel = clamp(vUV - prev, vec2(-0.03), vec2(0.03)) * 0.5;
    float jit = hash12(gl_FragCoord.xy + uTime);
    vec3 c = vec3(0.0);
    for (int i = 0; i < uBlurSamples; i++) {
        float o = (float(i) + jit) / float(uBlurSamples) - 0.5;
        c += texture(uHdr, vUV + vel * o).rgb;
    }
    c *= uHdrRead / float(uBlurSamples);
    // a touch of lateral colour fringing toward the frame edges
    vec2 ca = (vUV - 0.5) * 0.0035;
    c.r = mix(c.r, texture(uHdr, vUV + ca).r * uHdrRead, 0.6);
    c.b = mix(c.b, texture(uHdr, vUV - ca).b * uHdrRead, 0.6);
    c += texture(uBloom, vUV).rgb * (uHdrRead * 0.16) + texture(uStreak, vUV).rgb * (uHdrRead * vec3(0.04, 0.05, 0.09));
    c = aces(c * uExposure);
    c = pow(c, vec3(1.0 / 2.2));
    vec2 v = vUV - 0.5;
    c *= 1.0 - dot(v, v) * 0.7;
    c = mix(vec3(dot(c, vec3(0.299, 0.587, 0.114))), c, 1.12);
    c += (hash12(vUV * 1733.0 + uTime) - 0.5) * 0.018;
    oColor = vec4(c, 1.0);
}
"""
}
