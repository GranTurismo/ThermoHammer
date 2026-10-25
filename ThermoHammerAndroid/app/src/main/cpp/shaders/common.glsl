// Shared by every Neon City RT shader: the frame block, noise, sky and environment.
// Ported from NeonCityShaders.COMMON (GLES); Vulkan clip space (y down, depth 0..1).
#ifndef NRT_COMMON
#define NRT_COMMON

layout(set = 0, binding = 0) uniform Frame {
    mat4 viewProj;
    mat4 invViewProj;
    vec4 camPos;          // xyz, w = time
    vec4 camRight;
    vec4 camUp;
    vec4 camFwd;
    vec4 params;          // x light count, y lights per fragment, z fog steps, w frame
    vec4 groupsPacked[8]; // animation-group intensities
    vec4 search;          // police searchlight position, w = cos of its cone
} F;

#define uCamPos (F.camPos.xyz)
#define uTime (F.camPos.w)

float groupValueOf(int g) { return F.groupsPacked[g >> 2][g & 3]; }

const float PI = 3.14159265;
const vec3 HAZE = vec3(0.060, 0.042, 0.052);
const float HAZE_START = 150.0;
const float HAZE_DENSITY = 0.0026;

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

float resolved(float freq, float px) { return clamp(2.0 - 4.0 * px * freq, 0.0, 1.0); }

vec3 skyColor(vec3 d) {
    float h = max(d.z, 0.0);
    vec3 c = mix(vec3(0.20, 0.11, 0.12), vec3(0.022, 0.022, 0.038), pow(h, 0.45));
    if (d.z > 0.0) {
        vec2 uv = d.xy / (d.z + 0.12) * 0.8 + vec2(uTime * 0.006, uTime * 0.012);
        c *= 0.45 + 1.1 * fbm(uv * 1.4, 5);
    }
    return c;
}

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

vec3 safeHdr(vec3 c) {
    if (!(c.r < 1e20 && c.g < 1e20 && c.b < 1e20 && c.r >= 0.0 && c.g >= 0.0 && c.b >= 0.0)) return vec3(0.0);
    return min(c, vec3(3000.0));
}

#endif
