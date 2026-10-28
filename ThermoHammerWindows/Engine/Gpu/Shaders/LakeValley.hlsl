// LAKE VALLEY SHADERS — HLSL port (Direct3D 11, shader model 5.0) of the iPhone app's Metal shaders
// (CpuThrottlingTest/Benchmark/GPU/LakeValley.metal) and the Android GLSL ES, line for line.
//
// Scene shaders write tonemapped, gamma-encoded colour (RGBA8 targets, no HDR); the MIRROR variants write linear
// light for the water's reflection texture instead. Lighting: sun + hemisphere ambient, PCF sun shadows, moving
// cloud shadows, aerial-perspective fog toward the sky colour. Direct3D's clip-space depth is 0..1 and its textures
// start at the top-left, as Metal's — the matrices (LakeValleyRenderer.cs) and the full-screen UVs account for both.
//
// Variants (compile-time defines, like Metal's function constants): MIRROR, TERRAIN, SMOKE, THRESHOLD.
#ifndef MIRROR
#define MIRROR 0
#endif
#ifndef TERRAIN
#define TERRAIN 0
#endif
#ifndef SMOKE
#define SMOKE 0
#endif
#ifndef THRESHOLD
#define THRESHOLD 0
#endif

cbuffer LVFrame : register(b0)
{
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

cbuffer LVDraw : register(b1)
{
    float meshHeight; float sway; float flutter; float tint; float seed; float3 drawPad;
};

cbuffer LVPost : register(b2)
{
    float2 postTexel; float2 postStep; float postTime; float3 postPad;
};

Texture2D<float> shadowMap : register(t1);
Texture2D<float4> reflection : register(t2);
Texture2D<float4> srcA : register(t0);
Texture2D<float4> srcB : register(t1);
Texture2D<float4> srcC : register(t2);
Texture2D<float4> srcD : register(t3);
SamplerState linearClamp : register(s0);
SamplerComparisonState shadowSampler : register(s1);

static const float CLOUD_Z = 1600.0;
static const float FOG_DENSITY = 0.00015;
static const float3 ZENITH = float3(0.10, 0.24, 0.58);
static const float3 HORIZON = float3(0.60, 0.68, 0.78);

float hash12(float2 p)
{
    float3 p3 = frac(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return frac((p3.x + p3.y) * p3.z);
}

float hash11(float n) { return frac(sin(n) * 43758.5453); }

float vnoise(float2 p)
{
    float2 i = floor(p);
    float2 f = frac(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash12(i);
    float b = hash12(i + float2(1.0, 0.0));
    float c = hash12(i + float2(0.0, 1.0));
    float d = hash12(i + float2(1.0, 1.0));
    return lerp(lerp(a, b, u.x), lerp(c, d, u.x), u.y);
}

float fbm3(float2 p)
{
    float s = 0.5 * vnoise(p);
    p = p * 2.03 + float2(17.1, 9.3);
    s += 0.25 * vnoise(p);
    p = p * 2.03 + float2(17.1, 9.3);
    s += 0.125 * vnoise(p);
    return s / 0.875;
}

float fbm5(float2 p)
{
    float s = 0.0, a = 0.5;
    for (int i = 0; i < 5; i++) { s += a * vnoise(p); p = p * 2.03 + float2(17.1, 9.3); a *= 0.5; }
    return s / 0.96875;
}

float2 cloudUV(float2 xy) { return (xy / 1800.0 + wind.xy * (time * 0.006)) * 3.0; }

float cloudCover(float n) { return smoothstep(0.50, 0.80, n); }

float3 skyColor(float3 d)
{
    float h = clamp(d.z, 0.0, 1.0);
    float3 c = lerp(HORIZON, ZENITH, pow(h, 0.5));
    float s = max(dot(d, sunDir.xyz), 0.0);
    c += sunColor.xyz * (0.05 * pow(s, 5.0) + 0.22 * pow(s, 40.0));
    c += float3(0.12, 0.07, 0.02) * pow(1.0 - h, 5.0) * (0.35 + 0.65 * s);
    return c;
}

// Weight for a noise layer of `freq` cycles/m at `px` m/pixel: 1 when well resolved, 0 once it would alias.
float resolved(float freq, float px) { return clamp(2.0 - 4.0 * px * freq, 0.0, 1.0); }

float3 ambient(float3 n) { return lerp(float3(0.09, 0.085, 0.07), float3(0.30, 0.38, 0.52), n.z * 0.5 + 0.5); }

float cloudShadow(float3 w)
{
    float2 xy = w.xy + sunDir.xy / max(sunDir.z, 0.2) * (CLOUD_Z - w.z);
    return 1.0 - 0.6 * cloudCover(fbm3(cloudUV(xy)));
}

float3 applyFog(float3 col, float3 w)
{
    float3 v = w - camPos.xyz;
    float d = length(v);
    float3 dir = v / max(d, 0.001);
    dir.z = max(dir.z, 0.02);
    return lerp(col, skyColor(normalize(dir)), 1.0 - exp(-d * FOG_DENSITY));
}

float3 finish(float3 c)
{
    c *= exposure;
    c = (c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14);
    return pow(clamp(c, 0.0, 1.0), float3(1.0 / 2.2, 1.0 / 2.2, 1.0 / 2.2));
}

// Reflection target: linear light at quarter scale under a square-root curve.
float3 encodeRefl(float3 c) { return sqrt(clamp(c * 0.25, 0.0, 1.0)); }
float3 decodeRefl(float3 e) { return e * e * 4.0; }

float4 sceneOut(float3 c)
{
    if (MIRROR) return float4(encodeRefl(c), 1.0);
    return float4(finish(c), 1.0);
}

/** Sun shadow lookup — 4 bilinear-PCF taps (16 depth compares in hardware). */
float sunShadow(float3 w, float3 n)
{
    float4 s = mul(shadowMat, float4(w + n * 0.35, 1.0));
    float3 c = s.xyz / s.w;
    if (c.x <= 0.001 || c.x >= 0.999 || c.y <= 0.001 || c.y >= 0.999 || c.z >= 1.0) return 1.0;
    const float T = 1.0 / 2048.0;
    float z = c.z - 0.0004;
    float sum = shadowMap.SampleCmpLevelZero(shadowSampler, c.xy + float2(-0.7, -0.7) * T, z)
              + shadowMap.SampleCmpLevelZero(shadowSampler, c.xy + float2( 0.7, -0.7) * T, z)
              + shadowMap.SampleCmpLevelZero(shadowSampler, c.xy + float2(-0.7,  0.7) * T, z)
              + shadowMap.SampleCmpLevelZero(shadowSampler, c.xy + float2( 0.7,  0.7) * T, z);
    return sum * 0.25;
}

// ── Instanced objects: terrain, trees, rocks, reeds, props ──────────────────

struct ObjectIn
{
    float3 pos : POSITION;
    float4 normal : NORMAL;
    float4 color : COLOR;       // sRGB albedo + vertex class
    float4 row0 : INSTROW0;     // instance transform rows (xyz | translation)
    float4 row1 : INSTROW1;
    float4 row2 : INSTROW2;
    uint iid : SV_InstanceID;
};

struct ObjectOut
{
    float4 position : SV_Position;
    float3 world : TEXCOORD0;
    float3 normal : TEXCOORD1;
    float3 color : TEXCOORD2;
    float cls : TEXCOORD3;
};

ObjectOut objectVertex(ObjectIn i)
{
    float4 p = float4(i.pos, 1.0);
    float3 w = float3(dot(i.row0, p), dot(i.row1, p), dot(i.row2, p));
    float3 n = float3(dot(i.row0.xyz, i.normal.xyz), dot(i.row1.xyz, i.normal.xyz), dot(i.row2.xyz, i.normal.xyz));
    float rnd = hash11(float(i.iid) * 0.618034 + seed);
    // wind: a gust front rolls across the valley, each plant sways on its own phase
    float h = clamp(i.pos.z / meshHeight, 0.0, 1.0);
    float2 root = float2(i.row0.w, i.row1.w);
    float gust = 0.5 + 0.5 * sin(dot(root, wind.xy) * 0.03 - time * 1.1);
    float swayAmt = sin(time * (1.3 + 0.7 * rnd) + rnd * 6.2831) * 0.35 + gust * 0.65;
    w.xy += wind.xy * (sway * wind.z * swayAmt * h * h);
    float leaf = step(0.9, i.color.a);
    w += leaf * flutter * h * float3(sin(time * 6.0 + i.pos.x * 3.1 + rnd * 9.0),
                                     sin(time * 5.3 + i.pos.y * 2.7 + rnd * 4.0),
                                     sin(time * 4.7 + i.pos.z * 1.9));
    ObjectOut o;
    o.world = w;
    o.normal = n;
    o.color = pow(i.color.rgb, float3(2.2, 2.2, 2.2)) * (1.0 + tint * (rnd * 2.0 - 1.0));
    o.cls = i.color.a;
    o.position = mul(viewProj, float4(w, 1.0));
    return o;
}

ObjectOut lvObjectVS(ObjectIn i) { return objectVertex(i); }

float4 lvObjectFS(ObjectOut i, bool front : SV_IsFrontFace) : SV_Target
{
    if (MIRROR && i.world.z < lake.z - 0.05) discard;
    float3 n = normalize(i.normal);
    if (!front) n = -n;
    float3 albedo = i.color;
    if (TERRAIN)
    {
        float px = length(fwidth(i.world.xy));
        float d = (vnoise(i.world.xy * 0.012) - 0.5) * 0.6
                + (vnoise(i.world.xy * 0.08) - 0.5) * 0.4 * resolved(0.08, px)
                + (vnoise(i.world.xy * 0.45) - 0.5) * 0.5 * resolved(0.45, px)
                + (vnoise(i.world.xy * 2.7) - 0.5) * 0.4 * resolved(2.7, px);
        albedo *= 1.0 + 0.55 * d;
    }
    float ndl = dot(n, sunDir.xyz);
    float sh = sunShadow(i.world, n) * cloudShadow(i.world);
    float3 light = sunColor.xyz * (max(ndl, 0.0) * sh);
    if (i.cls > 0.75) light += sunColor.xyz * float3(0.5, 0.6, 0.2) * (max(-ndl, 0.0) * 0.5 * sh);
    float3 col = albedo * (light + ambient(n));
    if (i.cls > 0.4 && i.cls < 0.6) col = i.color * 5.0;          // lit cabin windows
    return sceneOut(applyFog(col, i.world));
}

/** Shadow pass: positions only (the swaying trees cast swaying shadows). */
float4 lvDepthVS(ObjectIn i) : SV_Position { return objectVertex(i).position; }

// ── Water: wave displacement, 9-octave ripple normals, planar reflection ─────

struct WaterOut { float4 position : SV_Position; float3 world : TEXCOORD0; };

WaterOut lvWaterVS(float2 a : POSITION)
{
    float z = lake.z + 0.05 * sin(dot(a, float2(0.13, 0.05)) + time * 1.3)
                     + 0.03 * sin(dot(a, float2(-0.06, 0.16)) + time * 1.9);
    WaterOut o;
    o.world = float3(a, z);
    o.position = mul(viewProj, float4(o.world, 1.0));
    return o;
}

// Slope of 9 directional deep-water waves; ripples finer than a few pixels fade out instead of aliasing into bands.
float2 waveSlope(float2 p, float px, float t)
{
    float2 g = float2(0.0, 0.0);
    float amp = 0.07, k = 0.45;
    for (int i = 0; i < 9; i++)
    {
        float a = float(i) * 2.39996 + 0.4;
        float2 dir = float2(cos(a), sin(a));
        float ph = dot(dir, p) * k + t * sqrt(9.81 * k) + float(i) * 1.7;
        g += dir * (amp * k * cos(ph) * resolved(k / 6.2832, px));
        amp *= 0.68;
        k *= 1.55;
    }
    return g;
}

float4 lvWaterFS(WaterOut i) : SV_Target
{
    float px = length(fwidth(i.world.xy));
    float3 n = normalize(float3(-waveSlope(i.world.xy, px, time), 1.0));
    float3 toCam = camPos.xyz - i.world;
    float3 v = normalize(toCam);
    float fres = 0.02 + 0.98 * pow(1.0 - max(dot(n, v), 0.0), 5.0);
    // the reflection was rendered from the same viewpoint, so it is sampled at this pixel (both top-left origin)
    float2 uv = i.position.xy * invViewport + n.xy * float2(1.0, -1.0) * (0.025 / (1.0 + length(toCam) * 0.01));
    float3 refl = decodeRefl(reflection.SampleLevel(linearClamp, uv, 0).rgb);
    float sh = sunShadow(i.world, float3(0.0, 0.0, 1.0)) * cloudShadow(i.world);
    float3 body = float3(0.010, 0.032, 0.036) * (ambient(n) + sunColor.xyz * (0.15 * sh));
    float3 col = lerp(body, refl, fres);
    float3 hv = normalize(sunDir.xyz + v);
    col += sunColor.xyz * (pow(max(dot(n, hv), 0.0), 700.0) * 60.0 * sh);
    return sceneOut(applyFog(col, i.world));
}

// ── Full-screen triangle: `ndc` is clip space (y up), `uv` samples a texture (y down) ──

struct FullOut { float4 position : SV_Position; float2 ndc : TEXCOORD0; float2 uv : TEXCOORD1; };

FullOut fullTriangle(uint vid, float z)
{
    float2 p = float2(float((vid << 1) & 2), float(vid & 2));
    FullOut o;
    o.ndc = p * 2.0 - 1.0;
    o.uv = float2(p.x, 1.0 - p.y);
    o.position = float4(o.ndc, z, 1.0);
    return o;
}

FullOut lvSkyVS(uint vid : SV_VertexID) { return fullTriangle(vid, 1.0); }    // on the far plane

FullOut lvPostVS(uint vid : SV_VertexID) { return fullTriangle(vid, 0.0); }

// ── Sky: atmosphere gradient, sun disc, drifting self-shadowed clouds ─────────

float4 lvSkyFS(FullOut i) : SV_Target
{
    float4 a = mul(invViewProj, float4(i.ndc, 0.0, 1.0));
    float4 b = mul(invViewProj, float4(i.ndc, 1.0, 1.0));
    float3 dir = normalize(b.xyz / b.w - a.xyz / a.w);
    if (MIRROR) dir.z = -dir.z;
    float3 col = skyColor(dir);
    float s = dot(dir, sunDir.xyz);
    col += sunColor.xyz * (smoothstep(0.99955, 0.99985, s) * 25.0);
    if (dir.z > 0.004)
    {
        float2 uv = cloudUV(camPos.xy + dir.xy * ((CLOUD_Z - camPos.z) / dir.z));
        float c = cloudCover(fbm5(uv));
        float toSun = cloudCover(fbm5(uv + sunDir.xy * 0.15));
        float3 cloud = lerp(float3(1.0, 0.97, 0.93) * 1.5, float3(0.42, 0.45, 0.52), toSun * 0.85);
        cloud += sunColor.xyz * (pow(max(s, 0.0), 6.0) * 0.5 * (1.0 - c));
        col = lerp(col, cloud, c * smoothstep(0.004, 0.10, dir.z) * 0.92);
    }
    return sceneOut(col);
}

// ── Birds: flocks circling the lake, wings flapping in the vertex shader ──────

WaterOut lvBirdVS(float4 aBird : POSITION, uint iid : SV_InstanceID)   // xyz local (x right, y forward), w = wing weight
{
    float id = float(iid);
    float flock = floor(id / 16.0);
    float r = hash11(id * 3.17 + 0.7);
    float fr = hash11(flock * 7.13 + 2.0);
    float turn = fmod(flock, 2.0) < 0.5 ? 1.0 : -1.0;
    float radius = 60.0 + 110.0 * fr + 14.0 * r;
    float ang = time * (0.11 + 0.05 * fr) * turn + flock * 1.9 + (r - 0.5) * 0.4;
    float2 c = lake.xy + float2(cos(flock * 2.4), sin(flock * 2.4)) * 45.0;
    float3 pos = float3(c + float2(cos(ang), sin(ang)) * radius,
                        lake.z + 22.0 + 30.0 * fr + 5.0 * sin(time * 0.6 + r * 6.2831) + (r - 0.5) * 10.0);
    float2 fwd = float2(-sin(ang), cos(ang)) * turn;
    float2 right = float2(fwd.y, -fwd.x);
    float3 lp = aBird.xyz * 1.6;
    lp.z += sin(time * (8.0 + 4.0 * r) + r * 30.0) * aBird.w * 0.6;
    WaterOut o;
    o.world = pos + float3(right * lp.x + fwd * lp.y, lp.z);
    o.position = mul(viewProj, float4(o.world, 1.0));
    return o;
}

float4 lvBirdFS(WaterOut i) : SV_Target { return sceneOut(applyFog(float3(0.035, 0.034, 0.038), i.world)); }

// ── Particles: chimney smoke (SMOKE) or sunlit pollen around the camera ───────

struct ParticleOut { float4 position : SV_Position; float2 uv : TEXCOORD0; float alpha : TEXCOORD1; float3 world : TEXCOORD2; float seed : TEXCOORD3; };

ParticleOut lvParticleVS(uint vid : SV_VertexID, uint iid : SV_InstanceID)
{
    static const float2 corners[6] = { float2(-1, -1), float2(1, -1), float2(1, 1), float2(-1, -1), float2(1, 1), float2(-1, 1) };
    float2 q = corners[vid % 6];
    float id = float(iid);
    float3 p;
    float size;
    ParticleOut o;
    if (SMOKE)
    {
        float life = frac(time / 9.0 + id / 48.0);
        p = origin.xyz + float3(wind.xy * (life * life * 24.0 * wind.z), life * 15.0)
            + float3(sin(id * 3.1 + time * 0.6), cos(id * 1.7 + time * 0.5), 0.0) * (life * 1.6);
        size = 0.45 + life * 4.5;
        o.alpha = smoothstep(0.0, 0.08, life) * (1.0 - life) * 0.6;
    }
    else
    {
        float3 anchor = float3(hash11(id * 1.1), hash11(id * 2.3 + 1.0), hash11(id * 3.7 + 2.0)) * 60.0
                      + float3(sin(time * 0.31 + id), cos(time * 0.27 + id * 1.3), 0.6 * sin(time * 0.23 + id * 0.7)) * 2.5
                      + float3(wind.xy * (time * 1.2), 0.0);
        float3 m = anchor - origin.xyz + 30.0;
        p = origin.xyz + (m - 60.0 * floor(m / 60.0)) - 30.0;   // world-fixed, wrapped around the camera (GLSL mod)
        size = 0.035 + 0.03 * hash11(id * 5.3);
        o.alpha = 0.5 + 0.5 * sin(time * 2.0 + id * 7.0);
    }
    o.uv = q;
    o.seed = id;
    o.world = p + (camRight.xyz * q.x + camUp.xyz * q.y) * size;
    o.position = mul(viewProj, float4(o.world, 1.0));
    return o;
}

/** Premultiplied output: smoke blends over, pollen (alpha 0) adds light. */
float4 lvParticleFS(ParticleOut i) : SV_Target
{
    float r = length(i.uv);
    if (r > 1.0) discard;
    if (SMOKE)
    {
        float puff = fbm3(i.uv * 1.7 + float2(i.seed * 0.37, time * 0.15));
        float a = i.alpha * smoothstep(1.0, 0.25, r) * (0.45 + 0.55 * puff);
        float3 col = float3(0.50, 0.50, 0.52) * (ambient(float3(0.0, 0.0, 1.0)) + sunColor.xyz * (0.35 * cloudShadow(i.world)));
        return float4(finish(applyFog(col, i.world)) * a, a);
    }
    float a = i.alpha * smoothstep(1.0, 0.0, r) * 0.8;
    return float4(finish(sunColor.xyz * 0.5) * a, 0.0);
}

// ── Post: bloom chain + composite (vignette, grade, grain) ────────────────────

/** 2× downsample (4 bilinear taps = 16 texels); with THRESHOLD keeps only highlights. */
float4 lvDownFS(FullOut i) : SV_Target
{
    float3 c = srcA.SampleLevel(linearClamp, i.uv + postTexel * float2(-1.0, -1.0), 0).rgb
             + srcA.SampleLevel(linearClamp, i.uv + postTexel * float2( 1.0, -1.0), 0).rgb
             + srcA.SampleLevel(linearClamp, i.uv + postTexel * float2(-1.0,  1.0), 0).rgb
             + srcA.SampleLevel(linearClamp, i.uv + postTexel * float2( 1.0,  1.0), 0).rgb;
    c *= 0.25;
    if (THRESHOLD) c *= smoothstep(0.70, 0.95, max(max(c.r, c.g), c.b));
    return float4(c, 1.0);
}

/** 9-tap Gaussian as 5 linear-filtered fetches. */
float4 lvBlurFS(FullOut i) : SV_Target
{
    float3 c = srcA.SampleLevel(linearClamp, i.uv, 0).rgb * 0.2270270;
    c += (srcA.SampleLevel(linearClamp, i.uv + postStep * 1.3846154, 0).rgb + srcA.SampleLevel(linearClamp, i.uv - postStep * 1.3846154, 0).rgb) * 0.3162162;
    c += (srcA.SampleLevel(linearClamp, i.uv + postStep * 3.2307692, 0).rgb + srcA.SampleLevel(linearClamp, i.uv - postStep * 3.2307692, 0).rgb) * 0.0702703;
    return float4(c, 1.0);
}

float4 lvCompositeFS(FullOut i) : SV_Target
{
    float3 c = srcA.SampleLevel(linearClamp, i.uv, 0).rgb;
    float3 b = srcB.SampleLevel(linearClamp, i.uv, 0).rgb * 0.35 + srcC.SampleLevel(linearClamp, i.uv, 0).rgb * 0.45
             + srcD.SampleLevel(linearClamp, i.uv, 0).rgb * 0.6;
    c = 1.0 - (1.0 - c) * (1.0 - clamp(b, 0.0, 1.0));          // screen blend keeps highlights in range
    float2 d = i.uv - 0.5;
    c *= 1.0 - dot(d, d) * 0.55;                               // vignette
    c = lerp(dot(c, float3(0.299, 0.587, 0.114)).xxx, c, 1.08); // a touch more saturation
    float g = frac(sin(dot(i.uv * 1000.0 + postTime, float2(12.9898, 78.233))) * 43758.5453);
    return float4(c + (g - 0.5) * 0.012, 1.0);                 // film grain
}
