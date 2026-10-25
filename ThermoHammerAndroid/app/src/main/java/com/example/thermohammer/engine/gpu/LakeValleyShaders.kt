package com.example.thermohammer.engine.gpu

// ─────────────────────────────────────────────────────────────────────────────
// LAKE VALLEY SHADERS — GLSL ES 3.00.
//
//   Scene shaders write tonemapped, gamma-encoded colour (RGBA8 targets, no
//   HDR); the MIRROR variants write linear light for the water's reflection
//   texture instead. Lighting: sun + hemisphere ambient, PCF sun shadows,
//   moving cloud shadows, aerial-perspective fog toward the sky colour.
// ─────────────────────────────────────────────────────────────────────────────

internal object LakeValleyShaders {

    /** Shared by every scene fragment shader: noise, sky, clouds, fog, tonemap. */
    const val COMMON = """
uniform vec3 uSunDir;
uniform vec3 uSunColor;
uniform vec3 uCamPos;
uniform float uTime;
uniform vec4 uWind;          // xy direction, z strength
uniform float uExposure;
uniform float uWaterZ;

const float CLOUD_Z = 1600.0;
const float FOG_DENSITY = 0.00015;
const vec3 ZENITH = vec3(0.10, 0.24, 0.58);
const vec3 HORIZON = vec3(0.60, 0.68, 0.78);

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash12(i);
    float b = hash12(i + vec2(1.0, 0.0));
    float c = hash12(i + vec2(0.0, 1.0));
    float d = hash12(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm3(vec2 p) {
    float s = 0.0;
    float a = 0.5;
    for (int i = 0; i < 3; i++) {
        s += a * vnoise(p);
        p = p * 2.03 + vec2(17.1, 9.3);
        a *= 0.5;
    }
    return s / 0.875;
}

float fbm5(vec2 p) {
    float s = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        s += a * vnoise(p);
        p = p * 2.03 + vec2(17.1, 9.3);
        a *= 0.5;
    }
    return s / 0.96875;
}

vec2 cloudUV(vec2 xy) { return (xy / 1800.0 + uWind.xy * (uTime * 0.006)) * 3.0; }

float cloudCover(float n) { return smoothstep(0.50, 0.80, n); }

vec3 skyColor(vec3 d) {
    float h = clamp(d.z, 0.0, 1.0);
    vec3 c = mix(HORIZON, ZENITH, pow(h, 0.5));
    float s = max(dot(d, uSunDir), 0.0);
    c += uSunColor * (0.05 * pow(s, 5.0) + 0.22 * pow(s, 40.0));
    c += vec3(0.12, 0.07, 0.02) * pow(1.0 - h, 5.0) * (0.35 + 0.65 * s);
    return c;
}

// Weight for a noise layer of `freq` cycles/m at `px` m/pixel: 1 when well resolved, 0 once it would alias.
float resolved(float freq, float px) { return clamp(2.0 - 4.0 * px * freq, 0.0, 1.0); }

vec3 ambient(vec3 n) {
    return mix(vec3(0.09, 0.085, 0.07), vec3(0.30, 0.38, 0.52), n.z * 0.5 + 0.5);
}

float cloudShadow(vec3 w) {
    vec2 xy = w.xy + uSunDir.xy / max(uSunDir.z, 0.2) * (CLOUD_Z - w.z);
    return 1.0 - 0.6 * cloudCover(fbm3(cloudUV(xy)));
}

vec3 applyFog(vec3 col, vec3 w) {
    vec3 v = w - uCamPos;
    float d = length(v);
    vec3 dir = v / max(d, 0.001);
    dir.z = max(dir.z, 0.02);
    return mix(col, skyColor(normalize(dir)), 1.0 - exp(-d * FOG_DENSITY));
}

vec3 finish(vec3 c) {
    c *= uExposure;
    c = (c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14);
    return pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2));
}

// Reflection target: linear light at quarter scale under a square-root curve.
vec3 encodeRefl(vec3 c) { return sqrt(clamp(c * 0.25, 0.0, 1.0)); }
vec3 decodeRefl(vec3 e) { return e * e * 4.0; }

vec4 sceneOut(vec3 c) {
#ifdef MIRROR
    return vec4(encodeRefl(c), 1.0);
#else
    return vec4(finish(c), 1.0);
#endif
}
"""

    /** Sun shadow lookup — 4 bilinear-PCF taps (16 depth compares in hardware). */
    const val SHADOW = """
uniform highp sampler2DShadow uShadowMap;
uniform mat4 uShadowMat;

float sunShadow(vec3 w, vec3 n) {
    vec4 s = uShadowMat * vec4(w + n * 0.35, 1.0);
    vec3 c = s.xyz / s.w;
    if (c.x <= 0.001 || c.x >= 0.999 || c.y <= 0.001 || c.y >= 0.999 || c.z >= 1.0) return 1.0;
    const float T = 1.0 / 2048.0;
    float z = c.z - 0.0004;
    float sum = texture(uShadowMap, vec3(c.xy + vec2(-0.7, -0.7) * T, z))
              + texture(uShadowMap, vec3(c.xy + vec2( 0.7, -0.7) * T, z))
              + texture(uShadowMap, vec3(c.xy + vec2(-0.7,  0.7) * T, z))
              + texture(uShadowMap, vec3(c.xy + vec2( 0.7,  0.7) * T, z));
    return sum * 0.25;
}
"""

    // ── Instanced objects: terrain, trees, rocks, reeds, props ──────────────

    const val OBJECT_VS = """
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec4 aNormal;
layout(location = 2) in vec4 aColor;     // sRGB albedo + vertex class
layout(location = 3) in vec4 aRow0;      // instance transform rows (xyz | translation)
layout(location = 4) in vec4 aRow1;
layout(location = 5) in vec4 aRow2;

uniform mat4 uViewProj;
uniform float uTime;
uniform vec4 uWind;
uniform float uMeshHeight;
uniform float uSway;                     // bend at the top, metres
uniform float uFlutter;                  // leaf jitter, metres
uniform float uTint;                     // per-instance albedo variation
uniform float uSeed;

out vec3 vWorld;
out vec3 vNormal;
out vec3 vColor;
out float vClass;

float hash11(float n) { return fract(sin(n) * 43758.5453); }

void main() {
    vec4 p = vec4(aPos, 1.0);
    vec3 w = vec3(dot(aRow0, p), dot(aRow1, p), dot(aRow2, p));
    vec3 n = vec3(dot(aRow0.xyz, aNormal.xyz), dot(aRow1.xyz, aNormal.xyz), dot(aRow2.xyz, aNormal.xyz));
    float rnd = hash11(float(gl_InstanceID) * 0.618034 + uSeed);

    // wind: a gust front rolls across the valley, each plant sways on its own phase
    float h = clamp(aPos.z / uMeshHeight, 0.0, 1.0);
    vec2 root = vec2(aRow0.w, aRow1.w);
    float gust = 0.5 + 0.5 * sin(dot(root, uWind.xy) * 0.03 - uTime * 1.1);
    float sway = sin(uTime * (1.3 + 0.7 * rnd) + rnd * 6.2831) * 0.35 + gust * 0.65;
    w.xy += uWind.xy * (uSway * uWind.z * sway * h * h);
    float leaf = step(0.9, aColor.a);
    w += leaf * uFlutter * h * vec3(sin(uTime * 6.0 + aPos.x * 3.1 + rnd * 9.0),
                                    sin(uTime * 5.3 + aPos.y * 2.7 + rnd * 4.0),
                                    sin(uTime * 4.7 + aPos.z * 1.9));

    vWorld = w;
    vNormal = n;
    vColor = pow(aColor.rgb, vec3(2.2)) * (1.0 + uTint * (rnd * 2.0 - 1.0));
    vClass = aColor.a;
    gl_Position = uViewProj * vec4(w, 1.0);
}
"""

    const val OBJECT_FS = """
in vec3 vWorld;
in vec3 vNormal;
in vec3 vColor;
in float vClass;
out vec4 oColor;

void main() {
#ifdef MIRROR
    if (vWorld.z < uWaterZ - 0.05) discard;
#endif
    vec3 n = normalize(vNormal);
    if (!gl_FrontFacing) n = -n;
    vec3 albedo = vColor;
#ifdef TERRAIN
    float px = length(fwidth(vWorld.xy));
    float d = (vnoise(vWorld.xy * 0.012) - 0.5) * 0.6
            + (vnoise(vWorld.xy * 0.08) - 0.5) * 0.4 * resolved(0.08, px)
            + (vnoise(vWorld.xy * 0.45) - 0.5) * 0.5 * resolved(0.45, px)
            + (vnoise(vWorld.xy * 2.7) - 0.5) * 0.4 * resolved(2.7, px);
    albedo *= 1.0 + 0.55 * d;
#endif
    float ndl = dot(n, uSunDir);
    float sh = sunShadow(vWorld, n) * cloudShadow(vWorld);
    vec3 light = uSunColor * (max(ndl, 0.0) * sh);
    if (vClass > 0.75) light += uSunColor * vec3(0.5, 0.6, 0.2) * (max(-ndl, 0.0) * 0.5 * sh);
    vec3 col = albedo * (light + ambient(n));
    if (vClass > 0.4 && vClass < 0.6) col = vColor * 5.0;          // lit cabin windows
    oColor = sceneOut(applyFog(col, vWorld));
}
"""

    const val DEPTH_FS = "void main() {}"

    // ── Water: wave displacement, 9-octave ripple normals, planar reflection ─

    const val WATER_VS = """
layout(location = 0) in vec2 aXY;
uniform mat4 uViewProj;
uniform float uTime;
uniform float uWaterZ;
out vec3 vWorld;

void main() {
    float z = uWaterZ + 0.05 * sin(dot(aXY, vec2(0.13, 0.05)) + uTime * 1.3)
                      + 0.03 * sin(dot(aXY, vec2(-0.06, 0.16)) + uTime * 1.9);
    vWorld = vec3(aXY, z);
    gl_Position = uViewProj * vec4(vWorld, 1.0);
}
"""

    const val WATER_FS = """
uniform mediump sampler2D uReflection;
uniform vec2 uInvViewport;
in vec3 vWorld;
out vec4 oColor;

// Slope of 9 directional deep-water waves; ripples finer than a few pixels fade out
// instead of aliasing into bands.
vec2 waveSlope(vec2 p, float px) {
    vec2 g = vec2(0.0);
    float amp = 0.07;
    float k = 0.45;
    for (int i = 0; i < 9; i++) {
        float a = float(i) * 2.39996 + 0.4;
        vec2 dir = vec2(cos(a), sin(a));
        float ph = dot(dir, p) * k + uTime * sqrt(9.81 * k) + float(i) * 1.7;
        g += dir * (amp * k * cos(ph) * resolved(k / 6.2832, px));
        amp *= 0.68;
        k *= 1.55;
    }
    return g;
}

void main() {
    float px = length(fwidth(vWorld.xy));
    vec3 n = normalize(vec3(-waveSlope(vWorld.xy, px), 1.0));
    vec3 toCam = uCamPos - vWorld;
    vec3 v = normalize(toCam);
    float fres = 0.02 + 0.98 * pow(1.0 - max(dot(n, v), 0.0), 5.0);
    vec2 uv = gl_FragCoord.xy * uInvViewport + n.xy * (0.025 / (1.0 + length(toCam) * 0.01));
    vec3 refl = decodeRefl(texture(uReflection, uv).rgb);
    float sh = sunShadow(vWorld, vec3(0.0, 0.0, 1.0)) * cloudShadow(vWorld);
    vec3 body = vec3(0.010, 0.032, 0.036) * (ambient(n) + uSunColor * (0.15 * sh));
    vec3 col = mix(body, refl, fres);
    vec3 hv = normalize(uSunDir + v);
    col += uSunColor * (pow(max(dot(n, hv), 0.0), 700.0) * 60.0 * sh);
    oColor = sceneOut(applyFog(col, vWorld));
}
"""

    // ── Sky: atmosphere gradient, sun disc, drifting self-shadowed clouds ────

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
    vec3 col = skyColor(dir);
    float s = dot(dir, uSunDir);
    col += uSunColor * (smoothstep(0.99955, 0.99985, s) * 25.0);
    if (dir.z > 0.004) {
        vec2 uv = cloudUV(uCamPos.xy + dir.xy * ((CLOUD_Z - uCamPos.z) / dir.z));
        float c = cloudCover(fbm5(uv));
        float toSun = cloudCover(fbm5(uv + uSunDir.xy * 0.15));
        vec3 cloud = mix(vec3(1.0, 0.97, 0.93) * 1.5, vec3(0.42, 0.45, 0.52), toSun * 0.85);
        cloud += uSunColor * (pow(max(s, 0.0), 6.0) * 0.5 * (1.0 - c));
        col = mix(col, cloud, c * smoothstep(0.004, 0.10, dir.z) * 0.92);
    }
    oColor = sceneOut(col);
}
"""

    // ── Birds: flocks circling the lake, wings flapping in the vertex shader ─

    const val BIRD_VS = """
layout(location = 0) in vec4 aBird;      // xyz local (x right, y forward), w = wing weight
uniform mat4 uViewProj;
uniform float uTime;
uniform vec3 uLake;                      // lake centre xy, water z
out vec3 vWorld;

float hash11(float n) { return fract(sin(n) * 43758.5453); }

void main() {
    float id = float(gl_InstanceID);
    float flock = floor(id / 16.0);
    float r = hash11(id * 3.17 + 0.7);
    float fr = hash11(flock * 7.13 + 2.0);
    float turn = mod(flock, 2.0) < 0.5 ? 1.0 : -1.0;
    float radius = 60.0 + 110.0 * fr + 14.0 * r;
    float ang = uTime * (0.11 + 0.05 * fr) * turn + flock * 1.9 + (r - 0.5) * 0.4;
    vec2 c = uLake.xy + vec2(cos(flock * 2.4), sin(flock * 2.4)) * 45.0;
    vec3 pos = vec3(c + vec2(cos(ang), sin(ang)) * radius,
                    uLake.z + 22.0 + 30.0 * fr + 5.0 * sin(uTime * 0.6 + r * 6.2831) + (r - 0.5) * 10.0);
    vec2 fwd = vec2(-sin(ang), cos(ang)) * turn;
    vec2 right = vec2(fwd.y, -fwd.x);
    vec3 lp = aBird.xyz * 1.6;
    lp.z += sin(uTime * (8.0 + 4.0 * r) + r * 30.0) * aBird.w * 0.6;
    vWorld = pos + vec3(right * lp.x + fwd * lp.y, lp.z);
    gl_Position = uViewProj * vec4(vWorld, 1.0);
}
"""

    const val BIRD_FS = """
in vec3 vWorld;
out vec4 oColor;
void main() {
    oColor = sceneOut(applyFog(vec3(0.035, 0.034, 0.038), vWorld));
}
"""

    // ── Particles: chimney smoke (SMOKE) or sunlit pollen around the camera ──

    const val PARTICLE_VS = """
uniform mat4 uViewProj;
uniform float uTime;
uniform vec4 uWind;
uniform vec3 uOrigin;                    // smoke: chimney top · pollen: camera
uniform vec3 uCamRight;
uniform vec3 uCamUp;
out vec2 vUV;
out float vAlpha;
out vec3 vWorld;
out float vSeed;

const vec2 CORNERS[6] = vec2[6](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0),
                                vec2(-1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0));

float hash11(float n) { return fract(sin(n) * 43758.5453); }

void main() {
    vec2 q = CORNERS[gl_VertexID % 6];
    float id = float(gl_InstanceID);
    vec3 p;
    float size;
#ifdef SMOKE
    float life = fract(uTime / 9.0 + id / 48.0);
    p = uOrigin + vec3(uWind.xy * (life * life * 24.0 * uWind.z), life * 15.0)
        + vec3(sin(id * 3.1 + uTime * 0.6), cos(id * 1.7 + uTime * 0.5), 0.0) * (life * 1.6);
    size = 0.45 + life * 4.5;
    vAlpha = smoothstep(0.0, 0.08, life) * (1.0 - life) * 0.6;
#else
    vec3 anchor = vec3(hash11(id * 1.1), hash11(id * 2.3 + 1.0), hash11(id * 3.7 + 2.0)) * 60.0
                + vec3(sin(uTime * 0.31 + id), cos(uTime * 0.27 + id * 1.3), 0.6 * sin(uTime * 0.23 + id * 0.7)) * 2.5
                + vec3(uWind.xy * (uTime * 1.2), 0.0);
    p = uOrigin + mod(anchor - uOrigin + 30.0, 60.0) - 30.0;   // world-fixed, wrapped around the camera
    size = 0.035 + 0.03 * hash11(id * 5.3);
    vAlpha = 0.5 + 0.5 * sin(uTime * 2.0 + id * 7.0);
#endif
    vUV = q;
    vSeed = id;
    vWorld = p + (uCamRight * q.x + uCamUp * q.y) * size;
    gl_Position = uViewProj * vec4(vWorld, 1.0);
}
"""

    /** Premultiplied output: smoke blends over, pollen (alpha 0) adds light. */
    const val PARTICLE_FS = """
in vec2 vUV;
in float vAlpha;
in vec3 vWorld;
in float vSeed;
out vec4 oColor;

void main() {
    float r = length(vUV);
    if (r > 1.0) discard;
#ifdef SMOKE
    float puff = fbm3(vUV * 1.7 + vec2(vSeed * 0.37, uTime * 0.15));
    float a = vAlpha * smoothstep(1.0, 0.25, r) * (0.45 + 0.55 * puff);
    vec3 col = vec3(0.50, 0.50, 0.52) * (ambient(vec3(0.0, 0.0, 1.0)) + uSunColor * (0.35 * cloudShadow(vWorld)));
    oColor = vec4(finish(applyFog(col, vWorld)) * a, a);
#else
    float a = vAlpha * smoothstep(1.0, 0.0, r) * 0.8;
    oColor = vec4(finish(uSunColor * 0.5) * a, 0.0);
#endif
}
"""

    // ── Post: bloom chain + composite (vignette, grade, grain) ──────────────

    const val POST_VS = """
out vec2 vUV;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUV = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

    /** 2× downsample (4 bilinear taps = 16 texels); with THRESHOLD keeps only highlights. */
    const val DOWN_FS = """
uniform mediump sampler2D uSrc;
uniform vec2 uTexel;                     // source texel size
in vec2 vUV;
out vec4 oColor;
void main() {
    vec3 c = texture(uSrc, vUV + uTexel * vec2(-1.0, -1.0)).rgb
           + texture(uSrc, vUV + uTexel * vec2( 1.0, -1.0)).rgb
           + texture(uSrc, vUV + uTexel * vec2(-1.0,  1.0)).rgb
           + texture(uSrc, vUV + uTexel * vec2( 1.0,  1.0)).rgb;
    c *= 0.25;
#ifdef THRESHOLD
    c *= smoothstep(0.70, 0.95, max(max(c.r, c.g), c.b));
#endif
    oColor = vec4(c, 1.0);
}
"""

    /** 9-tap Gaussian as 5 linear-filtered fetches. */
    const val BLUR_FS = """
uniform mediump sampler2D uSrc;
uniform vec2 uStep;
in vec2 vUV;
out vec4 oColor;
void main() {
    vec3 c = texture(uSrc, vUV).rgb * 0.2270270;
    c += (texture(uSrc, vUV + uStep * 1.3846154).rgb + texture(uSrc, vUV - uStep * 1.3846154).rgb) * 0.3162162;
    c += (texture(uSrc, vUV + uStep * 3.2307692).rgb + texture(uSrc, vUV - uStep * 3.2307692).rgb) * 0.0702703;
    oColor = vec4(c, 1.0);
}
"""

    const val COMPOSITE_FS = """
uniform mediump sampler2D uScene;
uniform mediump sampler2D uBloomA;
uniform mediump sampler2D uBloomB;
uniform mediump sampler2D uBloomC;
uniform float uTime;
in vec2 vUV;
out vec4 oColor;
void main() {
    vec3 c = texture(uScene, vUV).rgb;
    vec3 b = texture(uBloomA, vUV).rgb * 0.35 + texture(uBloomB, vUV).rgb * 0.45 + texture(uBloomC, vUV).rgb * 0.6;
    c = 1.0 - (1.0 - c) * (1.0 - clamp(b, 0.0, 1.0));          // screen blend keeps highlights in range
    vec2 d = vUV - 0.5;
    c *= 1.0 - dot(d, d) * 0.55;                               // vignette
    c = mix(vec3(dot(c, vec3(0.299, 0.587, 0.114))), c, 1.08); // a touch more saturation
    float g = fract(sin(dot(vUV * 1000.0 + uTime, vec2(12.9898, 78.233))) * 43758.5453);
    oColor = vec4(c + (g - 0.5) * 0.012, 1.0);                 // film grain
}
"""
}
