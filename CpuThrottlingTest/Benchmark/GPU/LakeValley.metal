// LAKE VALLEY SHADERS — Metal port of the Android app's GLSL ES (engine/gpu/LakeValleyShaders.kt), line for line.
//
// Scene shaders write tonemapped, gamma-encoded colour (RGBA8 targets, no HDR); the MIRROR variants write linear
// light for the water's reflection texture instead. Lighting: sun + hemisphere ambient, PCF sun shadows, moving
// cloud shadows, aerial-perspective fog toward the sky colour. Metal's clip-space depth is 0..1 and its textures
// start at the top-left — the matrices (LakeValleyRenderer.swift) and the full-screen UVs account for both.
#include <metal_stdlib>
using namespace metal;

constant bool MIRROR [[function_constant(0)]];
constant bool TERRAIN [[function_constant(1)]];
constant bool SMOKE [[function_constant(2)]];
constant bool THRESHOLD [[function_constant(3)]];

struct LVFrame {
    float4x4 viewProj;
    float4x4 invViewProj;
    float4x4 shadowMat;
    float4 sunDir;
    float4 sunColor;
    float4 camPos;
    float4 wind;          // xy direction, z strength
    float4 lake;          // centre xy, water z
    float4 camRight;
    float4 camUp;
    float4 origin;        // smoke: chimney top · pollen: camera
    float2 invViewport;
    float time;
    float exposure;
};

struct LVDraw { float meshHeight; float sway; float flutter; float tint; float seed; };

constant float CLOUD_Z = 1600.0;
constant float FOG_DENSITY = 0.00015;
constant float3 ZENITH = float3(0.10, 0.24, 0.58);
constant float3 HORIZON = float3(0.60, 0.68, 0.78);

static float hash12(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

static float hash11(float n) { return fract(sin(n) * 43758.5453); }

static float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash12(i);
    float b = hash12(i + float2(1.0, 0.0));
    float c = hash12(i + float2(0.0, 1.0));
    float d = hash12(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

static float fbm3(float2 p) {
    float s = 0.0, a = 0.5;
    for (int i = 0; i < 3; i++) { s += a * vnoise(p); p = p * 2.03 + float2(17.1, 9.3); a *= 0.5; }
    return s / 0.875;
}

static float fbm5(float2 p) {
    float s = 0.0, a = 0.5;
    for (int i = 0; i < 5; i++) { s += a * vnoise(p); p = p * 2.03 + float2(17.1, 9.3); a *= 0.5; }
    return s / 0.96875;
}

static float2 cloudUV(float2 xy, constant LVFrame& F) { return (xy / 1800.0 + F.wind.xy * (F.time * 0.006)) * 3.0; }

static float cloudCover(float n) { return smoothstep(0.50, 0.80, n); }

static float3 skyColor(float3 d, constant LVFrame& F) {
    float h = clamp(d.z, 0.0, 1.0);
    float3 c = mix(HORIZON, ZENITH, pow(h, 0.5));
    float s = max(dot(d, F.sunDir.xyz), 0.0);
    c += F.sunColor.xyz * (0.05 * pow(s, 5.0) + 0.22 * pow(s, 40.0));
    c += float3(0.12, 0.07, 0.02) * pow(1.0 - h, 5.0) * (0.35 + 0.65 * s);
    return c;
}

// Weight for a noise layer of `freq` cycles/m at `px` m/pixel: 1 when well resolved, 0 once it would alias.
static float resolved(float freq, float px) { return clamp(2.0 - 4.0 * px * freq, 0.0, 1.0); }

static float3 ambient(float3 n) { return mix(float3(0.09, 0.085, 0.07), float3(0.30, 0.38, 0.52), n.z * 0.5 + 0.5); }

static float cloudShadow(float3 w, constant LVFrame& F) {
    float2 xy = w.xy + F.sunDir.xy / max(F.sunDir.z, 0.2) * (CLOUD_Z - w.z);
    return 1.0 - 0.6 * cloudCover(fbm3(cloudUV(xy, F)));
}

static float3 applyFog(float3 col, float3 w, constant LVFrame& F) {
    float3 v = w - F.camPos.xyz;
    float d = length(v);
    float3 dir = v / max(d, 0.001);
    dir.z = max(dir.z, 0.02);
    return mix(col, skyColor(normalize(dir), F), 1.0 - exp(-d * FOG_DENSITY));
}

static float3 finish(float3 c, constant LVFrame& F) {
    c *= F.exposure;
    c = (c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14);
    return pow(clamp(c, 0.0, 1.0), float3(1.0 / 2.2));
}

// Reflection target: linear light at quarter scale under a square-root curve.
static float3 encodeRefl(float3 c) { return sqrt(clamp(c * 0.25, 0.0, 1.0)); }
static float3 decodeRefl(float3 e) { return e * e * 4.0; }

static float4 sceneOut(float3 c, constant LVFrame& F) {
    if (MIRROR) return float4(encodeRefl(c), 1.0);
    return float4(finish(c, F), 1.0);
}

/** Sun shadow lookup — 4 bilinear-PCF taps (16 depth compares in hardware). */
static float sunShadow(float3 w, float3 n, constant LVFrame& F, depth2d<float> shadowMap) {
    constexpr sampler ss(coord::normalized, filter::linear, address::clamp_to_edge, compare_func::less_equal);
    float4 s = F.shadowMat * float4(w + n * 0.35, 1.0);
    float3 c = s.xyz / s.w;
    if (c.x <= 0.001 || c.x >= 0.999 || c.y <= 0.001 || c.y >= 0.999 || c.z >= 1.0) return 1.0;
    const float T = 1.0 / 2048.0;
    float z = c.z - 0.0004;
    float sum = shadowMap.sample_compare(ss, c.xy + float2(-0.7, -0.7) * T, z)
              + shadowMap.sample_compare(ss, c.xy + float2( 0.7, -0.7) * T, z)
              + shadowMap.sample_compare(ss, c.xy + float2(-0.7,  0.7) * T, z)
              + shadowMap.sample_compare(ss, c.xy + float2( 0.7,  0.7) * T, z);
    return sum * 0.25;
}

// ── Instanced objects: terrain, trees, rocks, reeds, props ──────────────────

struct ObjectIn {
    float3 pos [[attribute(0)]];
    float4 normal [[attribute(1)]];
    float4 color [[attribute(2)]];     // sRGB albedo + vertex class
    float4 row0 [[attribute(3)]];      // instance transform rows (xyz | translation)
    float4 row1 [[attribute(4)]];
    float4 row2 [[attribute(5)]];
};

struct ObjectOut {
    float4 position [[position]];
    float3 world;
    float3 normal;
    float3 color;
    float cls;
};

static ObjectOut objectVertex(ObjectIn in, uint iid, constant LVFrame& F, constant LVDraw& D) {
    float4 p = float4(in.pos, 1.0);
    float3 w = float3(dot(in.row0, p), dot(in.row1, p), dot(in.row2, p));
    float3 n = float3(dot(in.row0.xyz, in.normal.xyz), dot(in.row1.xyz, in.normal.xyz), dot(in.row2.xyz, in.normal.xyz));
    float rnd = hash11(float(iid) * 0.618034 + D.seed);
    // wind: a gust front rolls across the valley, each plant sways on its own phase
    float h = clamp(in.pos.z / D.meshHeight, 0.0, 1.0);
    float2 root = float2(in.row0.w, in.row1.w);
    float gust = 0.5 + 0.5 * sin(dot(root, F.wind.xy) * 0.03 - F.time * 1.1);
    float sway = sin(F.time * (1.3 + 0.7 * rnd) + rnd * 6.2831) * 0.35 + gust * 0.65;
    w.xy += F.wind.xy * (D.sway * F.wind.z * sway * h * h);
    float leaf = step(0.9, in.color.a);
    w += leaf * D.flutter * h * float3(sin(F.time * 6.0 + in.pos.x * 3.1 + rnd * 9.0),
                                       sin(F.time * 5.3 + in.pos.y * 2.7 + rnd * 4.0),
                                       sin(F.time * 4.7 + in.pos.z * 1.9));
    ObjectOut o;
    o.world = w;
    o.normal = n;
    o.color = pow(in.color.rgb, float3(2.2)) * (1.0 + D.tint * (rnd * 2.0 - 1.0));
    o.cls = in.color.a;
    o.position = F.viewProj * float4(w, 1.0);
    return o;
}

vertex ObjectOut lvObjectVS(ObjectIn in [[stage_in]], uint iid [[instance_id]],
                            constant LVFrame& F [[buffer(2)]], constant LVDraw& D [[buffer(3)]]) {
    return objectVertex(in, iid, F, D);
}

fragment float4 lvObjectFS(ObjectOut in [[stage_in]], bool front [[front_facing]], constant LVFrame& F [[buffer(2)]],
                           depth2d<float> shadowMap [[texture(1)]]) {
    if (MIRROR && in.world.z < F.lake.z - 0.05) discard_fragment();
    float3 n = normalize(in.normal);
    if (!front) n = -n;
    float3 albedo = in.color;
    if (TERRAIN) {
        float px = length(fwidth(in.world.xy));
        float d = (vnoise(in.world.xy * 0.012) - 0.5) * 0.6
                + (vnoise(in.world.xy * 0.08) - 0.5) * 0.4 * resolved(0.08, px)
                + (vnoise(in.world.xy * 0.45) - 0.5) * 0.5 * resolved(0.45, px)
                + (vnoise(in.world.xy * 2.7) - 0.5) * 0.4 * resolved(2.7, px);
        albedo *= 1.0 + 0.55 * d;
    }
    float ndl = dot(n, F.sunDir.xyz);
    float sh = sunShadow(in.world, n, F, shadowMap) * cloudShadow(in.world, F);
    float3 light = F.sunColor.xyz * (max(ndl, 0.0) * sh);
    if (in.cls > 0.75) light += F.sunColor.xyz * float3(0.5, 0.6, 0.2) * (max(-ndl, 0.0) * 0.5 * sh);
    float3 col = albedo * (light + ambient(n));
    if (in.cls > 0.4 && in.cls < 0.6) col = in.color * 5.0;          // lit cabin windows
    return sceneOut(applyFog(col, in.world, F), F);
}

/** Shadow pass: positions only (the swaying trees cast swaying shadows). */
vertex float4 lvDepthVS(ObjectIn in [[stage_in]], uint iid [[instance_id]],
                        constant LVFrame& F [[buffer(2)]], constant LVDraw& D [[buffer(3)]]) {
    return objectVertex(in, iid, F, D).position;
}

// ── Water: wave displacement, 9-octave ripple normals, planar reflection ─────

struct WaterOut { float4 position [[position]]; float3 world; };

vertex WaterOut lvWaterVS(uint vid [[vertex_id]], const device float2* xy [[buffer(0)]], constant LVFrame& F [[buffer(2)]]) {
    float2 a = xy[vid];
    float z = F.lake.z + 0.05 * sin(dot(a, float2(0.13, 0.05)) + F.time * 1.3)
                       + 0.03 * sin(dot(a, float2(-0.06, 0.16)) + F.time * 1.9);
    WaterOut o;
    o.world = float3(a, z);
    o.position = F.viewProj * float4(o.world, 1.0);
    return o;
}

// Slope of 9 directional deep-water waves; ripples finer than a few pixels fade out instead of aliasing into bands.
static float2 waveSlope(float2 p, float px, float t) {
    float2 g = float2(0.0);
    float amp = 0.07, k = 0.45;
    for (int i = 0; i < 9; i++) {
        float a = float(i) * 2.39996 + 0.4;
        float2 dir = float2(cos(a), sin(a));
        float ph = dot(dir, p) * k + t * sqrt(9.81 * k) + float(i) * 1.7;
        g += dir * (amp * k * cos(ph) * resolved(k / 6.2832, px));
        amp *= 0.68;
        k *= 1.55;
    }
    return g;
}

fragment float4 lvWaterFS(WaterOut in [[stage_in]], constant LVFrame& F [[buffer(2)]],
                          depth2d<float> shadowMap [[texture(1)]], texture2d<float> reflection [[texture(2)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    float px = length(fwidth(in.world.xy));
    float3 n = normalize(float3(-waveSlope(in.world.xy, px, F.time), 1.0));
    float3 toCam = F.camPos.xyz - in.world;
    float3 v = normalize(toCam);
    float fres = 0.02 + 0.98 * pow(1.0 - max(dot(n, v), 0.0), 5.0);
    // the reflection was rendered from the same viewpoint, so it is sampled at this pixel (both top-left origin)
    float2 uv = in.position.xy * F.invViewport + n.xy * float2(1.0, -1.0) * (0.025 / (1.0 + length(toCam) * 0.01));
    float3 refl = decodeRefl(reflection.sample(linear, uv).rgb);
    float sh = sunShadow(in.world, float3(0.0, 0.0, 1.0), F, shadowMap) * cloudShadow(in.world, F);
    float3 body = float3(0.010, 0.032, 0.036) * (ambient(n) + F.sunColor.xyz * (0.15 * sh));
    float3 col = mix(body, refl, fres);
    float3 hv = normalize(F.sunDir.xyz + v);
    col += F.sunColor.xyz * (pow(max(dot(n, hv), 0.0), 700.0) * 60.0 * sh);
    return sceneOut(applyFog(col, in.world, F), F);
}

// ── Full-screen triangle: `ndc` is clip space (y up), `uv` samples a texture (y down) ──

struct FullOut { float4 position [[position]]; float2 ndc; float2 uv; };

static FullOut fullTriangle(uint vid, float z) {
    float2 p = float2(float((vid << 1) & 2), float(vid & 2));
    FullOut o;
    o.ndc = p * 2.0 - 1.0;
    o.uv = float2(p.x, 1.0 - p.y);
    o.position = float4(o.ndc, z, 1.0);
    return o;
}

vertex FullOut lvSkyVS(uint vid [[vertex_id]]) { return fullTriangle(vid, 1.0); }    // on the far plane

vertex FullOut lvPostVS(uint vid [[vertex_id]]) { return fullTriangle(vid, 0.0); }

// ── Sky: atmosphere gradient, sun disc, drifting self-shadowed clouds ─────────

fragment float4 lvSkyFS(FullOut in [[stage_in]], constant LVFrame& F [[buffer(2)]]) {
    float4 a = F.invViewProj * float4(in.ndc, 0.0, 1.0);
    float4 b = F.invViewProj * float4(in.ndc, 1.0, 1.0);
    float3 dir = normalize(b.xyz / b.w - a.xyz / a.w);
    if (MIRROR) dir.z = -dir.z;
    float3 col = skyColor(dir, F);
    float s = dot(dir, F.sunDir.xyz);
    col += F.sunColor.xyz * (smoothstep(0.99955, 0.99985, s) * 25.0);
    if (dir.z > 0.004) {
        float2 uv = cloudUV(F.camPos.xy + dir.xy * ((CLOUD_Z - F.camPos.z) / dir.z), F);
        float c = cloudCover(fbm5(uv));
        float toSun = cloudCover(fbm5(uv + F.sunDir.xy * 0.15));
        float3 cloud = mix(float3(1.0, 0.97, 0.93) * 1.5, float3(0.42, 0.45, 0.52), toSun * 0.85);
        cloud += F.sunColor.xyz * (pow(max(s, 0.0), 6.0) * 0.5 * (1.0 - c));
        col = mix(col, cloud, c * smoothstep(0.004, 0.10, dir.z) * 0.92);
    }
    return sceneOut(col, F);
}

// ── Birds: flocks circling the lake, wings flapping in the vertex shader ──────

vertex WaterOut lvBirdVS(uint vid [[vertex_id]], uint iid [[instance_id]], const device float4* bird [[buffer(0)]],
                         constant LVFrame& F [[buffer(2)]]) {
    float4 aBird = bird[vid];                    // xyz local (x right, y forward), w = wing weight
    float id = float(iid);
    float flock = floor(id / 16.0);
    float r = hash11(id * 3.17 + 0.7);
    float fr = hash11(flock * 7.13 + 2.0);
    float turn = fmod(flock, 2.0) < 0.5 ? 1.0 : -1.0;
    float radius = 60.0 + 110.0 * fr + 14.0 * r;
    float ang = F.time * (0.11 + 0.05 * fr) * turn + flock * 1.9 + (r - 0.5) * 0.4;
    float2 c = F.lake.xy + float2(cos(flock * 2.4), sin(flock * 2.4)) * 45.0;
    float3 pos = float3(c + float2(cos(ang), sin(ang)) * radius,
                        F.lake.z + 22.0 + 30.0 * fr + 5.0 * sin(F.time * 0.6 + r * 6.2831) + (r - 0.5) * 10.0);
    float2 fwd = float2(-sin(ang), cos(ang)) * turn;
    float2 right = float2(fwd.y, -fwd.x);
    float3 lp = aBird.xyz * 1.6;
    lp.z += sin(F.time * (8.0 + 4.0 * r) + r * 30.0) * aBird.w * 0.6;
    WaterOut o;
    o.world = pos + float3(right * lp.x + fwd * lp.y, lp.z);
    o.position = F.viewProj * float4(o.world, 1.0);
    return o;
}

fragment float4 lvBirdFS(WaterOut in [[stage_in]], constant LVFrame& F [[buffer(2)]]) {
    return sceneOut(applyFog(float3(0.035, 0.034, 0.038), in.world, F), F);
}

// ── Particles: chimney smoke (SMOKE) or sunlit pollen around the camera ───────

struct ParticleOut { float4 position [[position]]; float2 uv; float alpha; float3 world; float seed; };

vertex ParticleOut lvParticleVS(uint vid [[vertex_id]], uint iid [[instance_id]], constant LVFrame& F [[buffer(2)]]) {
    const float2 corners[6] = { float2(-1, -1), float2(1, -1), float2(1, 1), float2(-1, -1), float2(1, 1), float2(-1, 1) };
    float2 q = corners[vid % 6];
    float id = float(iid);
    float3 p;
    float size;
    ParticleOut o;
    if (SMOKE) {
        float life = fract(F.time / 9.0 + id / 48.0);
        p = F.origin.xyz + float3(F.wind.xy * (life * life * 24.0 * F.wind.z), life * 15.0)
            + float3(sin(id * 3.1 + F.time * 0.6), cos(id * 1.7 + F.time * 0.5), 0.0) * (life * 1.6);
        size = 0.45 + life * 4.5;
        o.alpha = smoothstep(0.0, 0.08, life) * (1.0 - life) * 0.6;
    } else {
        float3 anchor = float3(hash11(id * 1.1), hash11(id * 2.3 + 1.0), hash11(id * 3.7 + 2.0)) * 60.0
                      + float3(sin(F.time * 0.31 + id), cos(F.time * 0.27 + id * 1.3), 0.6 * sin(F.time * 0.23 + id * 0.7)) * 2.5
                      + float3(F.wind.xy * (F.time * 1.2), 0.0);
        float3 m = anchor - F.origin.xyz + 30.0;
        p = F.origin.xyz + (m - 60.0 * floor(m / 60.0)) - 30.0;   // world-fixed, wrapped around the camera (GLSL mod)
        size = 0.035 + 0.03 * hash11(id * 5.3);
        o.alpha = 0.5 + 0.5 * sin(F.time * 2.0 + id * 7.0);
    }
    o.uv = q;
    o.seed = id;
    o.world = p + (F.camRight.xyz * q.x + F.camUp.xyz * q.y) * size;
    o.position = F.viewProj * float4(o.world, 1.0);
    return o;
}

/** Premultiplied output: smoke blends over, pollen (alpha 0) adds light. */
fragment float4 lvParticleFS(ParticleOut in [[stage_in]], constant LVFrame& F [[buffer(2)]]) {
    float r = length(in.uv);
    if (r > 1.0) discard_fragment();
    if (SMOKE) {
        float puff = fbm3(in.uv * 1.7 + float2(in.seed * 0.37, F.time * 0.15));
        float a = in.alpha * smoothstep(1.0, 0.25, r) * (0.45 + 0.55 * puff);
        float3 col = float3(0.50, 0.50, 0.52) * (ambient(float3(0.0, 0.0, 1.0)) + F.sunColor.xyz * (0.35 * cloudShadow(in.world, F)));
        return float4(finish(applyFog(col, in.world, F), F) * a, a);
    }
    float a = in.alpha * smoothstep(1.0, 0.0, r) * 0.8;
    return float4(finish(F.sunColor.xyz * 0.5, F) * a, 0.0);
}

// ── Post: bloom chain + composite (vignette, grade, grain) ────────────────────

struct LVPost { float2 texel; float2 step; float time; };

/** 2× downsample (4 bilinear taps = 16 texels); with THRESHOLD keeps only highlights. */
fragment float4 lvDownFS(FullOut in [[stage_in]], texture2d<float> src [[texture(0)]], constant LVPost& P [[buffer(0)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    float3 c = src.sample(linear, in.uv + P.texel * float2(-1.0, -1.0)).rgb
             + src.sample(linear, in.uv + P.texel * float2( 1.0, -1.0)).rgb
             + src.sample(linear, in.uv + P.texel * float2(-1.0,  1.0)).rgb
             + src.sample(linear, in.uv + P.texel * float2( 1.0,  1.0)).rgb;
    c *= 0.25;
    if (THRESHOLD) c *= smoothstep(0.70, 0.95, max(max(c.r, c.g), c.b));
    return float4(c, 1.0);
}

/** 9-tap Gaussian as 5 linear-filtered fetches. */
fragment float4 lvBlurFS(FullOut in [[stage_in]], texture2d<float> src [[texture(0)]], constant LVPost& P [[buffer(0)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    float3 c = src.sample(linear, in.uv).rgb * 0.2270270;
    c += (src.sample(linear, in.uv + P.step * 1.3846154).rgb + src.sample(linear, in.uv - P.step * 1.3846154).rgb) * 0.3162162;
    c += (src.sample(linear, in.uv + P.step * 3.2307692).rgb + src.sample(linear, in.uv - P.step * 3.2307692).rgb) * 0.0702703;
    return float4(c, 1.0);
}

fragment float4 lvCompositeFS(FullOut in [[stage_in]], texture2d<float> scene [[texture(0)]], texture2d<float> bloomA [[texture(1)]],
                              texture2d<float> bloomB [[texture(2)]], texture2d<float> bloomC [[texture(3)]],
                              constant LVPost& P [[buffer(0)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    float3 c = scene.sample(linear, in.uv).rgb;
    float3 b = bloomA.sample(linear, in.uv).rgb * 0.35 + bloomB.sample(linear, in.uv).rgb * 0.45 + bloomC.sample(linear, in.uv).rgb * 0.6;
    c = 1.0 - (1.0 - c) * (1.0 - clamp(b, 0.0, 1.0));          // screen blend keeps highlights in range
    float2 d = in.uv - 0.5;
    c *= 1.0 - dot(d, d) * 0.55;                               // vignette
    c = mix(float3(dot(c, float3(0.299, 0.587, 0.114))), c, 1.08); // a touch more saturation
    float g = fract(sin(dot(in.uv * 1000.0 + P.time, float2(12.9898, 78.233))) * 43758.5453);
    return float4(c + (g - 0.5) * 0.012, 1.0);                 // film grain
}
