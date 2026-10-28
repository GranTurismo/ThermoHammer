// NEON CITY SHADERS — HLSL port (Direct3D 11, shader model 5.0) of the iPhone app's Metal shaders
// (CpuThrottlingTest/Benchmark/GPU/NeonCity.metal) and the Android GLSL ES, GPU Test 2.
//
// Scene passes write linear HDR light (RGBA16F). Every lit surface walks the light list of its 4 m ground cell
// (GGX + Lambert, one shadowed searchlight); windows ray-trace a room behind the glass; the wet street reflects a
// mirrored render, smeared into vertical streaks; fog is ray-marched through the same light grid. The grid lives
// in buffers (textures on Android) — the same records, read the same way. Direct3D's clip depth is 0..1 and its
// textures start top-left, as Metal's: the matrices are built for that (NeonCityRenderer.cs) and screen UVs are
// flipped in y.
//
// Derivatives (fwidth) are taken once at the top of the pixel shader, never inside a branch: Direct3D's compiler
// refuses gradients in flow control, and the values are the same.
//
// Variants (compile-time defines, like Metal's function constants): MIRROR, DEPTH_ONLY, THRESHOLD.
#ifndef MIRROR
#define MIRROR 0
#endif
#ifndef DEPTH_ONLY
#define DEPTH_ONLY 0
#endif
#ifndef THRESHOLD
#define THRESHOLD 0
#endif
// NC_RT: 0 = the raster test (mirror render, shadow map); 1 = Neon City RT (rays through DXR ray queries: reflections on the wet
// street, glass, paint and chrome, shadows, searchlight shafts); 2 = the RT code paths without rays (checks of the renderer's plumbing)
#ifndef NC_RT
#define NC_RT 0
#endif
#define RAYS (NC_RT != 0)

float sstep(float a, float b, float x) { float t = saturate((x - a) / (b - a)); return t * t * (3.0 - 2.0 * t); }
#define smoothstep(a, b, x) sstep(a, b, x)

cbuffer NCFrame : register(b0)
{
    float4x4 viewProj;
    float4x4 invViewProj;
    float4x4 prevViewProj;
    float4x4 searchMat;
    float4 camPos;
    float4 camRight;
    float4 camUp;
    float4 camFwd;
    float4 origin;          // steam puff origin
    float4 groups4[8];      // animation-group multipliers, 32 floats
    float2 invViewport;
    float time;
    float frame;
    int maxMoving;          // lights shaded per fragment: leading moving ones …
    int maxStatic;          // … then static ones (separate, so traffic never evicts a sign)
    int reflTaps;
    int fogSteps;
    int fogLights;
    int blurSamples;
    float exposure;
    float framePad;
};

cbuffer NCDraw : register(b1) { int actor; int instanced; float seed; float drawPad; };   // actor: 0 static · 1 car · 2 person · 3 train/drone

cbuffer NCPost : register(b2) { float2 postTexel; float2 postStep; float postWeight; float3 postPad; };

Texture2D<float> searchTex : register(t0);
Texture2D<float4> reflTex : register(t1);
Texture2D<float> depthTex : register(t2);
Texture2D<float4> srcA : register(t4);
Texture2D<float4> srcB : register(t5);
Texture2D<float4> srcC : register(t6);
// The light grid: textures 4096 texels wide, read with Load (an element n is texel (n & 4095, n >> 12)) — Direct3D 11, where Wine's older
// HLSL compiler has no buffers — or, in the Direct3D 12 renderer (NC_BUFFERS), plain buffers.
#ifndef NC_BUFFERS
#define NC_BUFFERS 0
#endif
#if NC_BUFFERS
StructuredBuffer<float4> lightsBuf : register(t8);  // 4 records per light: (x y z radius) (r g b type) (dir, cos outer) (group)
StructuredBuffer<uint> cellsBuf : register(t9);     // offset << 12 | moving << 6 | count
ByteAddressBuffer indexBuf : register(t10);         // u16 light indices, two to a word
#else
Texture2D<float4> lightsTex : register(t8);
Texture2D<uint> cellsTex : register(t9);
Texture2D<uint> indexTex : register(t10);
#endif

SamplerState linearClamp : register(s0);
SamplerComparisonState shadowSampler : register(s1);
SamplerState pointClamp : register(s2);
SamplerState mipLinear : register(s3);

#if NC_BUFFERS
float4 lightRec(int n) { return lightsBuf[n]; }
#else
float4 lightRec(int n) { return lightsTex.Load(int3(n & 4095, n >> 12, 0)); }
#endif

static const float PI = 3.14159265;
static const float3 HAZE = float3(0.060, 0.042, 0.052);   // rain mist lit by the city
static const float HAZE_START = 150.0;
static const float HAZE_DENSITY = 0.0026;

// vertex classes (CLASSES in build_neon_city.py)
static const int C_CONCRETE = 0, C_BRICK = 1, C_TILE = 2, C_METAL = 3, C_GLASS = 4, C_SHOPGLASS = 5, C_EMISSIVE = 6,
    C_ASPHALT = 7, C_PAVING = 8, C_PAINT = 9, C_CARPAINT = 10, C_CARGLASS = 11, C_RUBBER = 12, C_CHROME = 13,
    C_HEADLIGHT = 14, C_TAILLIGHT = 15, C_FABRIC = 16, C_SKIN = 17, C_FOLIAGE = 18, C_SCREEN = 19, C_TOWER = 20,
    C_LANTERN = 21, C_TRAINWIN = 22, C_WOOD = 23;

float groupOf(int g) { return groups4[g >> 2][g & 3]; }

float hash11(float n) { return frac(sin(n) * 43758.5453); }

float hash12(float2 p)
{
    float3 p3 = frac(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return frac((p3.x + p3.y) * p3.z);
}

float hash13(float3 p3)
{
    p3 = frac(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return frac((p3.x + p3.y) * p3.z);
}

float2 hash22(float2 p)
{
    float3 p3 = frac(float3(p.xyx) * float3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return frac((p3.xx + p3.yz) * p3.zy);
}

float vnoise(float2 p)
{
    float2 i = floor(p), f = frac(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return lerp(lerp(hash12(i), hash12(i + float2(1.0, 0.0)), u.x), lerp(hash12(i + float2(0.0, 1.0)), hash12(i + float2(1.0, 1.0)), u.x), u.y);
}

float vnoise3(float3 p)
{
    float3 i = floor(p), f = frac(p);
    float3 u = f * f * (3.0 - 2.0 * f);
    float a = lerp(lerp(hash13(i), hash13(i + float3(1, 0, 0)), u.x), lerp(hash13(i + float3(0, 1, 0)), hash13(i + float3(1, 1, 0)), u.x), u.y);
    float b = lerp(lerp(hash13(i + float3(0, 0, 1)), hash13(i + float3(1, 0, 1)), u.x), lerp(hash13(i + float3(0, 1, 1)), hash13(i + float3(1, 1, 1)), u.x), u.y);
    return lerp(a, b, u.z);
}

float fbm(float2 p, int octaves)
{
    float s = 0.0, a = 0.5, norm = 0.0;
    for (int i = 0; i < octaves; i++) { s += a * vnoise(p); norm += a; p = p * 2.03 + float2(17.1, 9.3); a *= 0.5; }
    return s / norm;
}

// Weight for detail of `freq` cycles/m at `px` m/pixel: 1 when resolved, 0 once it would alias.
float resolved(float freq, float px) { return clamp(2.0 - 4.0 * px * freq, 0.0, 1.0); }

// Low overcast lit orange-pink from below by the city; drifting rain clouds.
float3 skyColor(float3 d, float t)
{
    float h = max(d.z, 0.0);
    float3 c = lerp(float3(0.20, 0.11, 0.12), float3(0.022, 0.022, 0.038), pow(h, 0.45));
    if (d.z > 0.0)
    {
        float2 uv = d.xy / (d.z + 0.12) * 0.8 + float2(t * 0.006, t * 0.012);
        c *= 0.45 + 1.1 * fbm(uv * 1.4, 5);
    }
    return c;
}

// Cheap surroundings for glossy surfaces: sky above, city lights along the horizon, wet street below.
float3 envColor(float3 r, float t)
{
    if (r.z >= 0.0)
    {
        float3 c = skyColor(r, t);
        float band = exp(-r.z * 7.0);
        float a = atan2(r.y, r.x);
        float lightsN = smoothstep(0.6, 0.95, vnoise(float2(a * 46.0, r.z * 38.0)));
        float3 tint = lerp(float3(1.0, 0.35, 0.75), float3(0.3, 0.75, 1.0), vnoise(float2(a * 9.0, 3.0)));
        return c + band * (float3(0.16, 0.10, 0.09) + lightsN * tint * 1.6);
    }
    return float3(0.010, 0.009, 0.012) + float3(0.09, 0.05, 0.07) * exp(r.z * 5.0);
}

float3 applyHaze(float3 col, float3 w, float3 cam)
{
    float d = length(w - cam);
    return lerp(col, HAZE, 1.0 - exp(-max(d - HAZE_START, 0.0) * HAZE_DENSITY));
}

// Half-float targets overflow to inf past 65504, and one inf/NaN smears a block through the bloom.
float3 safeHdr(float3 c)
{
    if (!(c.r < 1e20 && c.g < 1e20 && c.b < 1e20 && c.r >= 0.0 && c.g >= 0.0 && c.b >= 0.0)) return float3(0.0, 0.0, 0.0);
    return min(c, float3(3000.0, 3000.0, 3000.0));
}

// ── The light grid: cell lists, light records, GGX shading, fog in-scatter, the searchlight shadow ──

static const float2 GRID_ORIGIN = float2(-256.0, -320.0);
static const float GRID_CELL = 4.0;
static const int2 GRID_SIZE = int2(128, 336);
static const float LIGHT_SCALE = 70.0;

uint lightIndex(uint i)
{
#if NC_BUFFERS
    uint w = indexBuf.Load((i >> 1) * 4);
#else
    uint n = i >> 1;
    uint w = indexTex.Load(int3(n & 4095, n >> 12, 0));
#endif
    return (i & 1) != 0 ? (w >> 16) : (w & 0xFFFF);
}

// p's light list: (first index, count, how many of them lead as moving lights)
int3 cellRange(float3 p)
{
    int2 c = int2(floor((p.xy - GRID_ORIGIN) / GRID_CELL));
    if (c.x < 0 || c.y < 0 || c.x >= GRID_SIZE.x || c.y >= GRID_SIZE.y) return int3(0, 0, 0);
#if NC_BUFFERS
    uint v = cellsBuf[c.y * GRID_SIZE.x + c.x];
#else
    uint n = uint(c.y * GRID_SIZE.x + c.x);
    uint v = cellsTex.Load(int3(n & 4095, n >> 12, 0));
#endif
    return int3(int(v >> 12u), int(v & 63u), int((v >> 6u) & 63u));
}

float searchShadow(float3 p)
{
    float4 s = mul(searchMat, float4(p, 1.0));
    if (s.w <= 0.0) return 0.0;
    float3 c = s.xyz / s.w;
    if (c.x <= 0.0 || c.x >= 1.0 || c.y <= 0.0 || c.y >= 1.0 || c.z >= 1.0) return 1.0;
    const float T = 0.7 / 1024.0;
    float z = c.z - 0.0006;
    return 0.25 * (searchTex.SampleCmpLevelZero(shadowSampler, c.xy + float2(-T, -T), z) + searchTex.SampleCmpLevelZero(shadowSampler, c.xy + float2(T, -T), z)
                 + searchTex.SampleCmpLevelZero(shadowSampler, c.xy + float2(-T, T), z) + searchTex.SampleCmpLevelZero(shadowSampler, c.xy + float2(T, T), z));
}

#if NC_RT == 1
struct NCRTInstance { float4 row0; float4 row1; float4 row2; float4 extra; int mesh; int actor; int instanced; float seed; };
RaytracingAccelerationStructure tlas : register(t7);
StructuredBuffer<NCRTInstance> rtInstances : register(t11);
StructuredBuffer<uint> rtMeshWide : register(t12);              // 1 where a mesh's indices are 32-bit
ByteAddressBuffer rtVertices[] : register(t0, space1);          // per mesh: the asset's 20-byte vertices
ByteAddressBuffer rtIndices[] : register(t0, space2);           // per mesh: u16 or u32 indices
#endif

/** 1 if nothing blocks the segment from `from` to (just short of) the light at `to`. */
float traceShadow(float3 from, float3 to)
{
#if NC_RT == 1
    float3 d = to - from;
    float len = length(d);
    if (len < 0.8) return 1.0;
    RayDesc ray;
    ray.Origin = from; ray.Direction = d / len; ray.TMin = 0.02; ray.TMax = len - 0.6;        // stop before the lamp's own housing
    RayQuery<RAY_FLAG_FORCE_OPAQUE | RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH> q;
    q.TraceRayInline(tlas, 0, 0xFF, ray);
    while (q.Proceed()) { }
    return q.CommittedStatus() == COMMITTED_NOTHING ? 1.0 : 0.0;
#else
    return 1.0;
#endif
}

// Distance + cone attenuation of one light at p (direction to the light in L). The searchlight is shadowed by its shadow map
// (with rays, the callers trace shadows).
float lightAtten(float4 l0, float4 l1, int id, float3 p, out float3 L)
{
    float3 lv = l0.xyz - p;
    float d2 = dot(lv, lv);
    float r2 = l0.w * l0.w;
    L = float3(0.0, 0.0, 1.0);
    if (d2 >= r2) return 0.0;
    L = lv * rsqrt(d2);
    float f = d2 / r2;
    float att = (1.0 - f) * (1.0 - f) / (d2 + 1.0);
    if (l1.w > 0.5)
    {
        float4 l2 = lightRec(id * 4 + 2);
        att *= smoothstep(l2.w, lerp(l2.w, 1.0, 0.45), dot(-L, l2.xyz));
        if (!RAYS && l1.w > 1.5 && att > 0.0) att *= searchShadow(p);
    }
    return att * groupOf(int(lightRec(id * 4 + 3).x));
}

// Light reflected toward v by every light in p's cell: Lambert diffuse + GGX specular. With rays and `shadows`, the searchlight and
// the two strongest lights are shadowed by rays; `most` caps the lights shaded.
float3 shadeLights(float3 p, float3 n, float3 v, float3 diff, float3 f0, float rough, bool shadows = true, int most = 1 << 16)
{
    const int SHADOWED_LIGHTS = 2;
    int3 r = cellRange(p);
    int nm = min(min(r.z, maxMoving), most);
    int cnt = min(nm + min(r.y - r.z, maxStatic), most);
    int traced = 0;
    float a = max(rough * rough, 0.012);                  // mirror puddles: sharpness comes from the reflection
    float a2 = a * a, k = a * 0.5;
    float ndv = max(dot(n, v), 1e-3);
    float gv = ndv * (1.0 - k) + k;
    float3 ps = p + n * 0.12;
    float3 sum = float3(0.0, 0.0, 0.0);
    [loop] for (int j = 0; j < cnt; j++)
    {
        int id = int(lightIndex(uint(r.x + (j < nm ? j : r.z + j - nm))));
        float4 l0 = lightRec(id * 4);
        float4 l1 = lightRec(id * 4 + 1);
        float3 L;
        float att = lightAtten(l0, l1, id, ps, L);
        float ndl = dot(n, L);
        if (att <= 0.0 || ndl <= 0.0) continue;
#if NC_RT
        if (shadows && (l1.w > 1.5 || (traced < SHADOWED_LIGHTS && att * max(l1.r, max(l1.g, l1.b)) > 0.002)))
        {
            att *= traceShadow(ps, l0.xyz);
            traced++;
            if (att <= 0.0) continue;
        }
#endif
        float3 h = normalize(L + v);
        float ndh = max(dot(n, h), 0.0);
        float dd = ndh * ndh * (a2 - 1.0) + 1.0;
        float D = a2 / (PI * dd * dd);
        float vis = 0.25 / ((ndl * (1.0 - k) + k) * gv);
        float3 Fr = f0 + (1.0 - f0) * pow(1.0 - max(dot(L, h), 0.0), 5.0);
        sum += l1.rgb * (att * ndl) * (diff * (1.0 / PI) + Fr * (D * vis));
    }
    return sum * LIGHT_SCALE;
}

float phaseHG(float c, float g)
{
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * PI * pow(1.0 + g2 - 2.0 * g * c, 1.5));
}

// Light scattered toward the camera by mist/rain at p (rd = view ray direction). movingOnly: just the cell's leading
// moving lights, so traffic never swaps static lights in and out.
float3 scatterLights(float3 p, float3 rd, int maxLights, bool movingOnly)
{
    int3 r = cellRange(p);
    int cnt = min(movingOnly ? r.z : r.y, maxLights);
    float3 sum = float3(0.0, 0.0, 0.0);
    [loop] for (int i = 0; i < cnt; i++)
    {
        int id = int(lightIndex(uint(r.x + i)));
        float4 l0 = lightRec(id * 4);
        float4 l1 = lightRec(id * 4 + 1);
        float3 L;
        float att = lightAtten(l0, l1, id, p, L);
        if (att <= 0.0) continue;
#if NC_RT
        if (l1.w > 1.5) att *= traceShadow(p, l0.xyz);      // the searchlight's beam: shafts cut by the buildings
#endif
        sum += l1.rgb * att * phaseHG(dot(L, rd), 0.35);
    }
    return sum * LIGHT_SCALE;
}

// ── Objects: buildings, props, cars, people, trains ─────────────────────────

struct ObjectIn
{
    float3 pos : POSITION;
    float4 normal : NORMAL;      // normal + animation channel / 127
    float4 color : COLOR;        // sRGB colour (or parameters) + class / 255
    float4 row0 : INSTROW0;      // instance transform rows
    float4 row1 : INSTROW1;
    float4 row2 : INSTROW2;
    float4 extra0 : EXTRA0;      // car: paint + brake · person: umbrella + coat shade
    float4 extra1 : EXTRA1;      // person: walk phase, swing amplitude
};

struct ObjectOut
{
    float4 position : SV_Position;
    float3 world : TEXCOORD0;
    float3 normal : TEXCOORD1;
    float3 color : TEXCOORD2;
    float4 extra : TEXCOORD3;
    float3 room : TEXCOORD4;            // glass: position in the window's frame
    float3 roomView : TEXCOORD5;        // glass: view ray in the window's frame
    nointerpolation int cls : TEXCOORD6;
    nointerpolation int chan : TEXCOORD7;
    nointerpolation float rnd : TEXCOORD8;
};

ObjectOut objectVertex(ObjectIn i)
{
    int chan = int(i.normal.w * 127.0 + 0.5);
    float3 p = i.pos;
    float3 n = i.normal.xyz;
    if (actor == 2)
    {
        // walk cycle: legs swing about the hip, arms about the shoulder (channel 1-4)
        int part = chan & 31;
        float swing = i.extra1.y * sin(i.extra1.x);
        float ang = part == 1 ? swing : part == 2 ? -swing : part == 3 ? -0.8 * swing : part == 4 ? 0.8 * swing : 0.0;
        if (ang != 0.0)
        {
            float pivot = part <= 2 ? 0.93 : 1.41;
            float c = cos(ang), s = sin(ang);
            float2 yz = float2(p.y, p.z - pivot);
            p.yz = float2(c * yz.x - s * yz.y, s * yz.x + c * yz.y + pivot);
            n.yz = float2(c * n.y - s * n.z, s * n.y + c * n.z);
        }
    }
    float4 hp = float4(p, 1.0);
    float3 w = float3(dot(i.row0, hp), dot(i.row1, hp), dot(i.row2, hp));
    ObjectOut o = (ObjectOut)0;
    o.world = w;
    o.position = mul(viewProj, float4(w, 1.0));
    if (DEPTH_ONLY) return o;
    float3 nw = normalize(float3(dot(i.row0.xyz, n), dot(i.row1.xyz, n), dot(i.row2.xyz, n)));
    int cls = int(i.color.a * 255.0 + 0.5);
    o.normal = nw;
    o.cls = cls;
    o.chan = chan;
    o.color = cls == C_SCREEN ? i.color.rgb : pow(i.color.rgb, float3(2.2, 2.2, 2.2));
    o.extra = i.extra0;
    float3 org = float3(i.row0.w, i.row1.w, i.row2.w);
    o.rnd = hash13(org * 0.37 + seed);
    // window frame: x along the facade, y out of it, z up (origin = window sill, or world for merged chunks)
    float3 O = normalize(float3(nw.xy, 0.0) + float3(1e-5, 0.0, 0.0));
    float3 T = float3(O.y, -O.x, 0.0);
    float3 d = w - org;
    o.room = float3(dot(d, T), dot(d, O), d.z);
    float3 vv = w - camPos.xyz;
    o.roomView = float3(dot(vv, T), dot(vv, O), vv.z);
    return o;
}

ObjectOut ncObjectVS(ObjectIn i) { return objectVertex(i); }

float4 ncDepthVS(ObjectIn i) : SV_Position { return objectVertex(i).position; }

static const float TIER[4] = { 1.0, 2.5, 5.0, 9.0 };

float groupValue(int ch, float3 p)
{
    int g = ch & 31;
    if (g >= 9 && g <= 12)                                 // letter chase: a bright band runs along the sign
    {
        float s = dot(p, float3(0.7, 0.7, 0.3)) * 0.9 - time * (1.6 + 0.4 * float(g - 9));
        return 0.2 + 0.8 * smoothstep(0.32, 0.12, abs(frac(s) - 0.5));
    }
    return groupOf(g);
}

float gmod(float a, float b) { return a - b * floor(a / b); }

// LED billboard: animated ad content on a visible pixel grid (`fw` = fwidth of the pixel grid's coordinates).
float3 screenContent(float2 uv, float sd, float tm, float fw)
{
    float t = tm * 0.6 + sd * 20.0;
    float scene = floor(gmod(t / 4.0 + sd * 3.0, 3.0));
    float3 c;
    if (scene < 0.5)                                       // brand gradient with a pulsing logo disc
    {
        c = lerp(float3(1.0, 0.1, 0.45), float3(0.15, 0.25, 1.0), uv.x + 0.25 * sin(t + uv.y * 3.0));
        float r = length((uv - float2(0.3, 0.55)) * float2(2.0, 1.0));
        c = lerp(c, float3(1.0, 0.95, 0.8), smoothstep(0.24, 0.2, r) * (0.7 + 0.3 * sin(t * 3.0)));
        c *= 0.75 + 0.25 * step(0.5, frac(uv.y * 6.0 - t));
    }
    else if (scene < 1.5)                                  // scrolling headline bars
    {
        float row = floor(uv.y * 5.0);
        float bar = step(0.35, hash12(float2(floor(uv.x * 9.0 + t * 2.0 * (1.0 + row * 0.3)), row)));
        c = lerp(float3(0.02, 0.05, 0.12), float3(0.2, 0.9, 1.0), bar * step(0.2, frac(uv.y * 5.0)));
        c += float3(1.0, 0.5, 0.05) * step(0.82, uv.y);
    }
    else                                                   // abstract motion (music video)
    {
        float v = sin(uv.x * 9.0 + t * 2.0) + sin(uv.y * 7.0 - t * 1.3) + sin((uv.x + uv.y) * 6.0 + t);
        c = 0.5 + 0.5 * cos(float3(0.0, 2.1, 4.2) + v * 1.4 + t * 0.4);
    }
    float2 px = frac(uv * float2(160.0, 80.0));
    float grid = smoothstep(0.0, 0.18, px.x) * smoothstep(0.0, 0.18, px.y);
    return c * lerp(grid, 0.72, clamp(fw * 1.2, 0.0, 1.0));
}

// A room behind the glass, ray-traced in the window's frame. Returns emitted light.
float3 interior(float3 q, float3 v, float style, bool shop, float3 vColor, float vRand, int inst, float tm)
{
    float roomW = shop ? 6.0 : style > 0.7 ? 4.2 : 3.4;
    float h = shop ? 3.4 : 3.0;
    float base = shop ? 0.15 : inst == 1 ? (style > 0.9 ? 0.0 : -0.9) : 4.8;
    float cx = floor(q.x / roomW + 0.5);
    float cz = shop ? 0.0 : floor((q.z - base) / 3.2);
    float x0 = (cx - 0.5) * roomW;
    float z0 = base + cz * 3.2;
    float id = hash13(float3(cx, cz, vRand * 97.0));
    float depth = (shop ? 7.0 : 4.0) + 2.5 * hash11(id * 13.1);
    float3 iv = 1.0 / (v + float3(1e-5, 1e-5, 1e-5));
    float tx = max((x0 - q.x) * iv.x, (x0 + roomW - q.x) * iv.x);
    float tz = max((z0 - q.z) * iv.z, (z0 + h - q.z) * iv.z);
    float ty = -depth * iv.y;
    float t = clamp(min(min(tx, tz), ty), 0.0, 60.0);
    float3 hp = q + v * t;
    float rx = (hp.x - x0) / roomW;
    float rz = (hp.z - z0) / h;
    float ry = (q.y - hp.y) / depth;

    bool lit = shop || id > (style > 0.7 ? 0.66 : 0.48);
    bool tv = !shop && id > 0.48 && id < 0.56;
    float3 lamp = shop ? vColor : style > 0.7 ? float3(0.80, 0.90, 1.0) : lerp(float3(1.0, 0.62, 0.32), float3(1.0, 0.80, 0.55), hash11(id * 7.0));
    float3 wall = lerp(float3(0.55, 0.50, 0.42), float3(0.42, 0.48, 0.52), hash11(id * 3.3));
    float3 c;
    if (t == tz)
    {
        c = hp.z > z0 + 0.5 * h ? float3(0.75, 0.75, 0.75) : (shop ? float3(0.5, 0.48, 0.45) : float3(0.32, 0.20, 0.12));
        if (hp.z > z0 + 0.5 * h && (style > 0.7 || shop))     // fluorescent panels in the ceiling
            c += step(0.7, frac(ry * 3.0)) * step(0.3, frac(rx * 2.0)) * 2.0;
    }
    else if (t == tx)
    {
        c = wall * (0.8 + 0.2 * step(0.15, rz));
        if (shop) c = lerp(c, vColor * 0.9, step(0.15, rz) * step(rz, 0.85) * step(0.5, frac(ry * 6.0)));
    }
    else
    {
        c = wall;
        if (shop)                                          // shelves of goods along the back
        {
            float shelf = step(0.45, frac(rz * 5.0)) * step(0.12, rz) * step(rz, 0.85);
            c = lerp(c, lerp(vColor, float3(1.0, 1.0, 1.0), hash12(floor(float2(rx * 14.0, rz * 5.0)))), shelf);
        }
        else
        {
            c *= 1.0 - 0.6 * step(rz, 0.3) * step(0.2, rx) * step(rx, 0.8);   // sofa / desk
            if (hash11(id * 5.1) > 0.5) c = lerp(c, float3(0.08, 0.06, 0.05), step(abs(rx - 0.5), 0.12) * step(abs(rz - 0.62), 0.12));
        }
    }
    // the lamp hangs near the ceiling in the middle of the room
    float3 lp = float3(x0 + 0.5 * roomW, q.y - depth * 0.45, z0 + h - 0.3);
    float fall = 1.6 / (1.0 + 0.12 * dot(hp - lp, hp - lp));
    float3 e = lit ? c * lamp * fall * (shop ? 1.8 : 1.0) : c * float3(0.006, 0.006, 0.01);
    if (tv) e += float3(0.25, 0.45, 1.0) * (0.25 + 0.2 * sin(tm * 7.0 + id * 40.0) * sin(tm * 2.3)) * smoothstep(0.5, 0.0, ry);
    if (!shop && hash11(id * 9.7) > 0.62)                  // blinds or curtains in front of the room
    {
        float slats = 0.55 + 0.45 * step(0.25, frac(q.z * 9.0));
        float3 cloth = lit ? lamp * (0.18 + 0.2 * hash11(id * 3.9)) * slats : float3(0.008, 0.008, 0.008) * slats;
        e = lerp(e, cloth, 0.85);
    }
    return e;
}

// The tower facade's window grid coordinates (derivatives of this are taken by the caller).
float2 towerUV(float3 p, float3 n)
{
    float3 t = normalize(float3(n.y, -n.x, 0.0) + float3(1e-4, 0.0, 0.0));
    return float2(dot(p, t) / 1.8, p.z / 3.5);
}

// Tower facades seen from afar: a grid of windows, some lit (`px` = fwidth of towerUV).
float3 towerWindows(float3 p, float3 n, out float glassMask, float px)
{
    float2 uv = towerUV(p, n);
    float2 cell = floor(uv), f = frac(uv);
    float win = step(abs(f.x - 0.5), 0.32) * step(abs(f.y - 0.55), 0.3);
    win = lerp(win, 0.38, clamp(px * 1.5 - 0.3, 0.0, 1.0));
    float r = hash12(cell + floor(n.xy * 3.0) * 17.0);
    float lit = step(0.62, r);
    lit = lerp(lit, 0.38, clamp(px * 1.5 - 0.3, 0.0, 1.0));
    glassMask = win;
    float3 warm = lerp(float3(1.0, 0.66, 0.36), float3(0.75, 0.88, 1.0), step(0.86, r));
    return win * lit * warm * 1.4;
}

float2 rippleSlope(float2 p, float tm)
{
    float2 g = float2(0.0, 0.0);
    for (int layer = 0; layer < 2; layer++)
    {
        float2 q = p * 1.9 + float(layer) * float2(0.37, 0.71);
        float2 cell = floor(q);
        float2 h = hash22(cell + float(layer) * 13.0);
        float2 f = frac(q) - 0.25 - 0.5 * h;
        float life = frac(tm * 1.2 + h.x * 7.0);
        float r = length(f);
        float ring = r - life * 0.45;
        float wave = sin(ring * 60.0) * smoothstep(0.08, 0.0, abs(ring)) * (1.0 - life) * (1.0 - life);
        g += f / max(r, 1e-3) * wave * 0.35;
    }
    return g;
}

bool isEmissive(int k)
{
    return k == C_EMISSIVE || k == C_HEADLIGHT || k == C_TAILLIGHT || k == C_LANTERN || k == C_TRAINWIN || k == C_SCREEN;
}

// Signs, lamps, screens and lit train windows: their light, by brightness tier and animation group.
float3 emission(int k, int chan, float3 color, float3 P, float3 N, int act, float4 extra, float fwScreen)
{
    float tier = TIER[(chan >> 5) & 3];
    float3 base = color;
    float strength = k == C_HEADLIGHT ? 2.0 : k == C_TAILLIGHT ? 1.2 : k == C_TRAINWIN ? 0.45 : k == C_LANTERN ? 0.9 : 1.0;
    if (k == C_TAILLIGHT && act == 1) strength *= 1.0 + 2.5 * extra.w;
    if (k == C_SCREEN) base = screenContent(color.rg, color.b, time, fwScreen) * 0.35;
    if (k == C_LANTERN) base *= 0.6 + 0.4 * smoothstep(-0.2, 0.6, N.z + 0.3);   // paper glows brighter on top
    return base * (tier * strength * groupValue(chan, P));
}

// Albedo, roughness and metalness of the solid classes (car paint and umbrellas take the instance's colour).
void surfaceParams(int k, int chan, int act, float4 extra, inout float3 albedo, out float rough, out float metal)
{
    rough = 0.6;
    metal = 0.0;
    if (k == C_CARPAINT) { albedo = albedo * (act == 1 ? extra.rgb : float3(0.05, 0.05, 0.05)); rough = 0.22; }
    else if (k == C_CARGLASS) { albedo = float3(0.01, 0.01, 0.01); rough = 0.05; }
    else if (k == C_RUBBER) { rough = 0.55; }
    else if (k == C_CHROME) { metal = 1.0; rough = 0.18; }
    else if (k == C_METAL) { metal = 0.6; rough = 0.38; }
    else if (k == C_FABRIC)
    {
        int part = chan & 31;
        if (act == 2 && part == 5) albedo = extra.rgb;                                   // wet umbrella
        else if (act == 2 && part == 6) albedo = float3(extra.w, extra.w, extra.w) * float3(0.55, 0.5, 0.48) * 0.6;
        rough = part == 5 ? 0.25 : 0.8;
    }
    else if (k == C_SKIN) { rough = 0.5; }
    else if (k == C_FOLIAGE) { rough = 0.7; }
    else if (k == C_WOOD) { rough = 0.55; }
}

float3 cityGlow(float3 diff, float3 N)
{
    return diff * (float3(0.018, 0.015, 0.022) + float3(0.03, 0.02, 0.025) * max(N.z, 0.0));
}

#if NC_RT == 1
int4 unpackS8(uint v) { return int4(int(v << 24) >> 24, int(v << 16) >> 24, int(v << 8) >> 24, int(v) >> 24); }
uint4 unpackU8(uint v) { return uint4(v & 255, (v >> 8) & 255, (v >> 16) & 255, v >> 24); }

uint indexAt(uint mesh, uint n)
{
    if (rtMeshWide[mesh] != 0) return rtIndices[NonUniformResourceIndex(mesh)].Load(n * 4);
    uint w = rtIndices[NonUniformResourceIndex(mesh)].Load((n >> 1) * 4);
    return (n & 1) != 0 ? (w >> 16) : (w & 0xFFFF);
}

// What a reflection ray sees where it lands: the same materials, lit by a few unshadowed lights.
float3 shadeHit(uint inst, uint prim, float2 bc, float3 P, float3 V)
{
    NCRTInstance I = rtInstances[inst];
    uint mesh = (uint)I.mesh;
    uint i0 = indexAt(mesh, prim * 3), i1 = indexAt(mesh, prim * 3 + 1), i2 = indexAt(mesh, prim * 3 + 2);
    ByteAddressBuffer vb = rtVertices[NonUniformResourceIndex(mesh)];
    int4 na = unpackS8(vb.Load(i0 * 20 + 12)), nb = unpackS8(vb.Load(i1 * 20 + 12)), nc = unpackS8(vb.Load(i2 * 20 + 12));
    uint4 ca = unpackU8(vb.Load(i0 * 20 + 16)), cb = unpackU8(vb.Load(i1 * 20 + 16)), cc = unpackU8(vb.Load(i2 * 20 + 16));
    float w0 = 1.0 - bc.x - bc.y;
    float3 n = (float3(na.xyz) * w0 + float3(nb.xyz) * bc.x + float3(nc.xyz) * bc.y) / 127.0;
    float3 N = normalize(float3(dot(I.row0.xyz, n), dot(I.row1.xyz, n), dot(I.row2.xyz, n)));
    if (dot(N, V) < 0.0) N = -N;
    float3 raw = (float3(ca.rgb) * w0 + float3(cb.rgb) * bc.x + float3(cc.rgb) * bc.y) / 255.0;
    int k = int(ca.a);
    int ch = na.w;
    float dist = length(P - camPos.xyz);
    float3 col;
    if (isEmissive(k))
    {
        col = emission(k, ch, k == C_SCREEN ? raw : pow(raw, float3(2.2, 2.2, 2.2)), P, N, I.actor, I.extra, 1.0);
    }
    else if (k == C_GLASS || k == C_SHOPGLASS)
    {
        // a lit room or a dark pane, decided per window cell
        float lit = step(0.5, hash13(floor(P * float3(0.3, 0.3, 0.31))));
        col = k == C_SHOPGLASS ? pow(raw, float3(2.2, 2.2, 2.2)) * 1.2 : lerp(float3(0.004, 0.004, 0.004), float3(0.6, 0.42, 0.25), lit);
        col += envColor(reflect(-V, N), time) * 0.08;
    }
    else
    {
        float3 albedo = pow(raw, float3(2.2, 2.2, 2.2));
        float rough, metal;
        surfaceParams(k, ch, I.actor, I.extra, albedo, rough, metal);
        float3 emit = float3(0.0, 0.0, 0.0);
        if (k == C_TOWER)
        {
            float gm;
            emit = towerWindows(P, N, gm, dist * 0.004);
            albedo *= 1.0 - 0.7 * gm;
        }
        if (k == C_ASPHALT || k == C_PAVING || k == C_PAINT) albedo *= 0.5;
        float3 diff = albedo * (1.0 - metal);
        col = shadeLights(P, N, V, diff, lerp(float3(0.04, 0.04, 0.04), albedo, metal), rough, false, 4) + cityGlow(diff, N) + emit;
    }
    return applyHaze(col, P, camPos.xyz);
}
#endif

/** Radiance arriving along a reflection ray from o in direction d. */
float3 traceReflection(float3 o, float3 d)
{
#if NC_RT == 1
    RayDesc ray;
    ray.Origin = o; ray.Direction = d; ray.TMin = 0.03; ray.TMax = 400.0;
    RayQuery<RAY_FLAG_FORCE_OPAQUE> q;
    q.TraceRayInline(tlas, 0, 0xFF, ray);
    while (q.Proceed()) { }
    if (q.CommittedStatus() == COMMITTED_TRIANGLE_HIT)
        return shadeHit(q.CommittedInstanceID(), q.CommittedPrimitiveIndex(), q.CommittedTriangleBarycentrics(), o + d * q.CommittedRayT(), -d);
    return lerp(skyColor(d, time), HAZE, exp(-max(d.z, 0.0) * 12.0) * 0.8);
#else
    return envColor(d, time);
#endif
}

float4 objectShade(ObjectOut i)
{
    float3 P = i.world;
    int k = i.cls;
    bool ground = k == C_ASPHALT || k == C_PAVING || k == C_PAINT;
    float3 Nraw = normalize(i.normal);
    float3 V = normalize(camPos.xyz - P);
    // derivatives, once, outside every branch
    float fwGround = length(fwidth(P.xy));
    float3 tW = normalize(float3(Nraw.y, -Nraw.x, 0.0) + float3(1e-4, 0.0, 0.0));
    float fwWall = length(fwidth(abs(Nraw.z) > 0.7 ? P.xy : float2(dot(P, tW), P.z)));
    float fwTower = length(fwidth(towerUV(P, Nraw)));
    float fwScreen = length(fwidth(i.color.rg * float2(160.0, 80.0)));
    float2 suv = i.position.xy * invViewport;

    if (MIRROR && (P.z < -0.02 || (ground && P.z < 0.3))) discard;
    float3 N = Nraw;
    if (dot(N, V) < 0.0) N = -N;
    float3 col;
    float3 after = float3(0.0, 0.0, 0.0);   // added on top (window emission)
#if NC_RT
    // with rays every branch only chooses its reflection rays; they are traced once, at the end
    float3 rayDir0 = float3(0.0, 0.0, 1.0), rayDir1 = float3(0.0, 0.0, 1.0);
    int nrays = 0;
    float reflMix = 0.0;                    // how much of the result is reflection
#endif

    if (isEmissive(k))
    {
        col = emission(k, i.chan, i.color, P, N, actor, i.extra, fwScreen);
        col += envColor(reflect(-V, N), time) * 0.04;     // a glossy wet film over every lamp and sign
    }
    else if (k == C_GLASS || k == C_SHOPGLASS)
    {
        bool shop = k == C_SHOPGLASS;
        float3 inside = interior(i.room, normalize(i.roomView), shop ? 0.5 : i.color.b, shop, i.color, i.rnd, instanced, time);
        float Fr = 0.04 + 0.96 * pow(1.0 - max(dot(N, V), 0.0), 5.0);
#if NC_RT
        col = inside;
        rayDir0 = reflect(-V, N);
        nrays = 1;
        reflMix = Fr;
        after = shadeLights(P, N, V, float3(0.0, 0.0, 0.0), float3(0.04, 0.04, 0.04), 0.07, false);
#else
        col = inside * (1.0 - Fr) + envColor(reflect(-V, N), time) * Fr;
        col += shadeLights(P, N, V, float3(0.0, 0.0, 0.0), float3(0.04, 0.04, 0.04), 0.07);
#endif
    }
    else
    {
        float3 albedo = i.color;
        float rough, metal, wet = 0.0;
        surfaceParams(k, i.chan, actor, i.extra, albedo, rough, metal);
        float3 emit = float3(0.0, 0.0, 0.0);
        if (k == C_TOWER)
        {
            float glassMask;
            emit = towerWindows(P, N, glassMask, fwTower);
            rough = lerp(0.5, 0.08, glassMask);
            albedo *= 1.0 - 0.7 * glassMask;
        }

        if (k == C_CONCRETE || k == C_BRICK || k == C_TILE || k == C_WOOD)
        {
            float3 t = normalize(float3(N.y, -N.x, 0.0) + float3(1e-4, 0.0, 0.0));
            float2 uv = abs(N.z) > 0.7 ? P.xy : float2(dot(P, t), P.z);
            float px = fwWall;
            if (k == C_BRICK)
            {
                float row = floor(uv.y / 0.16);
                float bx = uv.x / 0.32 + 0.5 * gmod(row, 2.0);
                float mortar = max(step(0.86, frac(uv.y / 0.16)), step(0.93, frac(bx)));
                float tone = 0.8 + 0.4 * hash12(float2(floor(bx), row));
                albedo *= lerp(1.0, lerp(tone, 1.7, mortar), resolved(3.0, px));
            }
            else if (k == C_TILE)
            {
                float2 tg = frac(uv / 0.2);
                float grout = max(step(0.92, tg.x), step(0.92, tg.y));
                albedo *= lerp(1.0, lerp(0.9 + 0.2 * hash12(floor(uv / 0.2)), 0.55, grout), resolved(2.5, px));
                rough = 0.3;
            }
            float grime = fbm(uv * float2(0.35, 0.18), 3);
            albedo *= 0.7 + 0.6 * grime;
            // rain runs down the walls in streaks: darker and glossier
            float streak = smoothstep(0.45, 0.8, vnoise(float2(uv.x * 2.5, uv.y * 0.12 + time * 0.05)));
            wet = streak * (1.0 - abs(N.z)) * resolved(1.0, px) + 0.35;
            rough = lerp(rough, 0.18, wet * 0.6);
            albedo *= 1.0 - 0.35 * wet;
        }

        float3 refl = float3(0.0, 0.0, 0.0);
        float reflAmount = 0.0;
        if (ground)
        {
            // wet street: standing water in puddles, ripples from the rain, mirror reflections
            float big = fbm(P.xy * (k == C_PAVING ? 0.3 : 0.16), 4);
            float puddle = smoothstep(0.5, 0.6, big);
            if (k == C_PAVING) puddle *= 0.7;
            float2 slope = rippleSlope(P.xy, time) * puddle;
            float px = fwGround;
            slope += (float2(vnoise(P.xy * 7.0), vnoise(P.xy * 7.0 + 31.0)) - 0.5) * 0.18 * (1.0 - puddle) * resolved(7.0, px);
            N = normalize(float3(-slope, 1.0));
            rough = lerp(k == C_PAVING ? 0.4 : 0.3, 0.03, puddle);
            albedo *= lerp(0.75, 0.35, puddle) * (0.8 + 0.4 * fbm(P.xy * 0.9, 2));
            wet = 0.7 + 0.3 * puddle;
            float Fr = 0.02 + 0.98 * pow(1.0 - max(dot(N, V), 0.0), 5.0);
#if NC_RT
            {
                // two rays fanned up and down the street smear lights into the long streaks of wet asphalt
                float3 R = reflect(-V, N);
                float3 up = normalize(cross(cross(R, float3(0.0, 0.0, 1.0)), R) + float3(1e-4, 1e-4, 1e-4));
                float spread = rough * 0.35;
                rayDir0 = normalize(R + up * spread);
                rayDir1 = normalize(R - up * spread * 0.6);
                nrays = 2;
                reflMix = lerp(0.25, 1.0, puddle) * Fr;
            }
#else
            if (!MIRROR)
            {
                float2 ruv = suv + N.xy * float2(1.0, -1.0) * 0.035;
                float spread = 0.004 + rough * 0.09;
                float lod = rough * 5.0;
                float wsum = 0.0;
                [loop] for (int s = 0; s < reflTaps; s++)
                {
                    float o = (float(s) + 0.5) / float(reflTaps) * 2.0 - 1.0;
                    float wt = exp(-o * o * 2.5);
                    refl += reflTex.SampleLevel(mipLinear, float2(ruv.x, ruv.y + o * spread), lod).rgb * wt;
                    wsum += wt;
                }
                refl /= wsum;
                reflAmount = lerp(0.25, 1.0, puddle) * Fr;
            }
#endif
        }
#if NC_RT
        else if (k == C_CARPAINT || k == C_CARGLASS || k == C_CHROME || (k == C_METAL && metal > 0.0))
        {
            float Fc = 0.04 + 0.96 * pow(1.0 - max(dot(N, V), 0.0), 5.0);
            rayDir0 = reflect(-V, N);
            nrays = 1;
            reflMix = metal > 0.5 ? 0.7 : Fc * (k == C_CARPAINT ? 1.4 : 1.0);
        }
#endif

        float3 diff = albedo * (1.0 - metal);
        float3 f0 = lerp(float3(0.04 + 0.02 * wet, 0.04 + 0.02 * wet, 0.04 + 0.02 * wet), albedo, metal);
        col = shadeLights(P, N, V, diff, f0, rough);
        col += cityGlow(diff, N);
        float Fe = 0.04 + 0.96 * pow(1.0 - max(dot(N, V), 0.0), 5.0);
        float gloss = (1.0 - rough) * (1.0 - rough);
#if NC_RT
        if (!ground && nrays == 0) col += envColor(reflect(-V, N), time) * lerp(Fe * gloss, 0.7, metal) * (k == C_CARPAINT ? 1.6 : 1.0);
#else
        if (!ground) col += envColor(reflect(-V, N), time) * lerp(Fe * gloss, 0.7, metal) * (k == C_CARPAINT ? 1.6 : 1.0);
#endif
        col = lerp(col, refl, reflAmount);
        after = emit;
    }
#if NC_RT
    if (nrays > 0)
    {
        float3 rr = traceReflection(P + N * 0.03, rayDir0);
        if (nrays > 1) rr += traceReflection(P + N * 0.03, rayDir1);
        col = lerp(col, rr / float(nrays), clamp(reflMix, 0.0, 1.0));
    }
#endif
    col = applyHaze(col + after, P, camPos.xyz);
    return float4(safeHdr(col), 1.0);
}

float4 ncObjectFS(ObjectOut i) : SV_Target { return objectShade(i); }

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

FullOut ncSkyVS(uint vid : SV_VertexID) { return fullTriangle(vid, 1.0); }
FullOut ncPostVS(uint vid : SV_VertexID) { return fullTriangle(vid, 0.0); }

float4 ncSkyFS(FullOut i) : SV_Target
{
    float4 a = mul(invViewProj, float4(i.ndc, 0.0, 1.0));
    float4 b = mul(invViewProj, float4(i.ndc, 1.0, 1.0));
    float3 dir = normalize(b.xyz / b.w - a.xyz / a.w);
    if (MIRROR) dir.z = -dir.z;
    return float4(lerp(skyColor(dir, time), HAZE, exp(-max(dir.z, 0.0) * 12.0) * 0.8), 1.0);
}

// ── Rain, splashes, light halos, steam (premultiplied: alpha 0 adds light) ─────

struct QuadOut { float4 position : SV_Position; float2 q : TEXCOORD0; float3 light : TEXCOORD1; float alpha : TEXCOORD2; float sd : TEXCOORD3; };

static const float2 CORNERS_UP[6] = { float2(-1, 0), float2(1, 0), float2(1, 1), float2(-1, 0), float2(1, 1), float2(-1, 1) };
static const float2 CORNERS[6] = { float2(-1, -1), float2(1, -1), float2(1, 1), float2(-1, -1), float2(1, 1), float2(-1, 1) };

float3 gmod3(float3 a, float3 b) { return a - b * floor(a / b); }

QuadOut ncRainVS(uint vid : SV_VertexID, uint iid : SV_InstanceID)
{
    const float3 BOX = float3(36.0, 36.0, 24.0);
    float id = float(iid);
    float3 sd = float3(hash11(id * 0.1371 + 0.3), hash11(id * 0.7193 + 3.1), hash11(id * 1.3711 + 7.7));
    float speed = 8.0 + 3.0 * hash11(id * 2.17);
    float3 fall = float3(1.1, 0.45, -speed);
    float3 cam = camPos.xyz;
    float3 centre = cam + float3(camFwd.xy, 0.0) * (BOX.x * 0.36);
    float3 p = sd * BOX + fall * time;
    p = centre + gmod3(p - centre + BOX * 0.5, BOX) - BOX * 0.5;
    float3 axis = normalize(fall);
    float dist = length(cam - p);
    float len = 0.04 * speed * (1.0 + 0.5 * hash11(id * 5.3));       // ~1/20 s exposure
    float3 side = normalize(cross(axis, cam - p)) * (0.004 + dist * 0.0009);
    float2 q = CORNERS_UP[vid];
    float3 w = p + side * q.x + axis * (q.y * len);
    QuadOut o = (QuadOut)0;
    o.q = q;
    // drops light up where they cross a lamp's beam, mostly when back-lit
    float3 rd = normalize(p - cam);
    o.light = (scatterLights(p, rd, 6, false) * 0.35 + HAZE * 0.6) * smoothstep(0.8, 2.5, dist) * (1.0 - smoothstep(24.0, 34.0, dist));
    o.position = mul(viewProj, float4(w, 1.0));
    return o;
}

float4 ncRainFS(QuadOut i) : SV_Target
{
    float a = (1.0 - abs(i.q.x)) * smoothstep(0.0, 0.3, i.q.y) * smoothstep(1.0, 0.6, i.q.y);
    return float4(i.light * a, 0.0);
}

QuadOut ncSplashVS(uint vid : SV_VertexID, uint iid : SV_InstanceID)
{
    float id = float(iid);
    float rate = 2.4 + hash11(id * 3.7);
    float cyc = time * rate + hash11(id * 1.9);
    float life = frac(cyc);
    float n = floor(cyc);
    float2 r = float2(hash11(id * 0.37 + n * 1.13), hash11(id * 0.91 + n * 2.71));
    float2 ahead = camPos.xy + camFwd.xy * 14.0;
    float2 xy = ahead + (r - 0.5) * float2(30.0, 30.0);
    float z = abs(xy.x) > 7.0 ? 0.16 : 0.01;
    float size = 0.05 + 0.1 * life;
    float2 q = CORNERS_UP[vid];
    float3 w = float3(xy, z) + camRight.xyz * (q.x * size) + float3(0.0, 0.0, q.y * size * 0.7);
    QuadOut o = (QuadOut)0;
    o.q = q;
    o.alpha = life;
    float dist = length(float3(xy, z) - camPos.xyz);
    o.light = (scatterLights(float3(xy, z + 0.2), normalize(float3(xy, z) - camPos.xyz), 4, false) * 0.25 + HAZE)
            * (1.0 - smoothstep(16.0, 24.0, dist));
    o.position = mul(viewProj, float4(w, 1.0));
    return o;
}

float4 ncSplashFS(QuadOut i) : SV_Target
{
    // a thin crown: an arc that rises and thins as the splash ages
    float r = length(float2(i.q.x, i.q.y * 1.4));
    float ring = smoothstep(0.25, 0.0, abs(r - 0.75)) * step(0.0, i.q.y);
    float a = ring * (1.0 - i.alpha) * 0.9;
    return float4(i.light * a, 0.0);
}

/** Soft halo around each light, the glow of rain mist close to the lamp. */
QuadOut ncGlowVS(uint vid : SV_VertexID, uint iid : SV_InstanceID)
{
    int id = int(iid);
    float4 l0 = lightRec(id * 4);
    float4 l1 = lightRec(id * 4 + 1);
    float g = groupOf(int(lightRec(id * 4 + 3).x));
    float3 toCam = camPos.xyz - l0.xyz;
    float dist = length(toCam);
    float size = 0.5 + l0.w * 0.09 + dist * 0.004;
    if (l1.w > 0.5)
    {
        // a spot glares only into its beam; lamps aimed at the street keep a dim halo round the head
        float4 l2 = lightRec(id * 4 + 2);
        float facing = smoothstep(l2.w - 0.3, 1.0, dot(l2.xyz, toCam / max(dist, 0.01)));
        g *= max(l2.z < -0.5 ? 0.35 : 0.0, facing);
    }
    float3 c = l1.rgb * g * (0.022 / (1.0 + dist * 0.015)) * (1.0 - smoothstep(110.0, 160.0, dist));
    float2 q = CORNERS[vid];
    float3 w = l0.xyz + toCam / max(dist, 0.01) * min(0.6, dist * 0.5) + (camRight.xyz * q.x + camUp.xyz * q.y) * size;
    QuadOut o = (QuadOut)0;
    o.q = q;
    o.light = c;
    o.position = dist < 1.0 ? float4(0.0, 0.0, 2.0, 1.0) : mul(viewProj, float4(w, 1.0));
    return o;
}

float4 ncGlowFS(QuadOut i) : SV_Target
{
    float r2 = dot(i.q, i.q);
    float a = exp(-r2 * 4.0) + 0.6 * exp(-r2 * 30.0);
    return float4(i.light * a, 0.0);
}

QuadOut ncSteamVS(uint vid : SV_VertexID, uint iid : SV_InstanceID)
{
    float id = float(iid);
    float life = frac(time / 6.0 + id / 24.0);
    float3 p = origin.xyz + float3(1.4 * life * life + 0.3 * sin(id * 3.1 + time * 0.7),
                                   0.6 * life + 0.3 * cos(id * 1.7 + time * 0.5), 0.2 + life * 5.5);
    float size = 0.4 + life * 2.6;
    float2 q = CORNERS[vid];
    QuadOut o = (QuadOut)0;
    o.q = q;
    o.sd = id;
    o.alpha = smoothstep(0.0, 0.12, life) * (1.0 - life) * 0.35;
    o.light = scatterLights(p, normalize(p - camPos.xyz), 6, false) * 0.6 + HAZE * 2.0;
    o.position = mul(viewProj, float4(p + (camRight.xyz * q.x + camUp.xyz * q.y) * size, 1.0));
    return o;
}

float4 ncSteamFS(QuadOut i) : SV_Target
{
    float r = length(i.q);
    if (r > 1.0) discard;
    float puff = fbm(i.q * 1.6 + float2(i.sd * 0.37, time * 0.2), 3);
    float a = i.alpha * smoothstep(1.0, 0.2, r) * (0.4 + 0.6 * puff);
    return float4(i.light * a, a);
}

// ── Post: volumetric fog, combine, bloom, streaks, final ──────────────────────

/** Half-resolution ray march through rain mist, lit by the grid (the searchlight shadowed by its map). */
float4 ncFogFS(FullOut i) : SV_Target
{
    const float FOG_MAX = 170.0, FOG_DENSITY = 0.011;
    float d = depthTex.SampleLevel(pointClamp, i.uv, 0);
    float4 e = mul(invViewProj, float4(i.ndc, d, 1.0));
    float3 end = e.xyz / e.w;
    float3 cam = camPos.xyz;
    float3 rd = normalize(end - cam);
    float tEnd = min(length(end - cam), FOG_MAX);
    float j = frac(52.9829189 * frac(dot(i.position.xy, float2(0.06711056, 0.00583715))) + frame * 0.618034);
    float T = 1.0;
    float3 S = float3(0.0, 0.0, 0.0);
    float steps = float(fogSteps);
    [loop] for (int s = 0; s < fogSteps; s++)
    {
        float u = (float(s) + j) / steps;
        float t = tEnd * u * u;                            // denser sampling near the camera
        float dt = tEnd * 2.0 * u / steps;
        float3 p = cam + rd * t;
        float dens = FOG_DENSITY * exp(-max(p.z, 0.0) * 0.025)
                   * (0.5 + vnoise3(p * float3(0.06, 0.06, 0.03) + float3(0.3, 0.1, 0.5) * time));
        float3 li = HAZE + scatterLights(p, rd, fogLights, true);
        S += T * dens * dt * li;
        T *= exp(-dens * dt);
    }
    return float4(safeHdr(S), T);
}

float4 ncCombineFS(FullOut i) : SV_Target
{
    float4 f = srcB.SampleLevel(linearClamp, i.uv, 0);
    return float4(srcA.SampleLevel(linearClamp, i.uv, 0).rgb * f.a + f.rgb, 1.0);
}

/** 13-tap downsample; THRESHOLD keeps highlights only (first level). */
float4 ncDownFS(FullOut i) : SV_Target
{
    float2 t = postTexel, uv = i.uv;
    float3 a = srcA.SampleLevel(linearClamp, uv + t * float2(-2, -2), 0).rgb, b = srcA.SampleLevel(linearClamp, uv + t * float2(0, -2), 0).rgb;
    float3 c = srcA.SampleLevel(linearClamp, uv + t * float2(2, -2), 0).rgb, d = srcA.SampleLevel(linearClamp, uv + t * float2(-1, -1), 0).rgb;
    float3 e = srcA.SampleLevel(linearClamp, uv, 0).rgb, f = srcA.SampleLevel(linearClamp, uv + t * float2(1, -1), 0).rgb;
    float3 g = srcA.SampleLevel(linearClamp, uv + t * float2(-2, 0), 0).rgb, h = srcA.SampleLevel(linearClamp, uv + t * float2(2, 0), 0).rgb;
    float3 ii = srcA.SampleLevel(linearClamp, uv + t * float2(-1, 1), 0).rgb, j = srcA.SampleLevel(linearClamp, uv + t * float2(1, 1), 0).rgb;
    float3 k = srcA.SampleLevel(linearClamp, uv + t * float2(-2, 2), 0).rgb, l = srcA.SampleLevel(linearClamp, uv + t * float2(0, 2), 0).rgb;
    float3 m = srcA.SampleLevel(linearClamp, uv + t * float2(2, 2), 0).rgb;
    float3 s = (d + f + ii + j) * 0.125 + (a + c + k + m) * 0.03125 + (b + g + h + l) * 0.0625 + e * 0.125;
    if (THRESHOLD)
    {
        float3 x = safeHdr(s);
        float br = max(max(x.r, x.g), x.b);
        float soft = clamp(br - 0.6, 0.0, 1.2);
        soft = soft * soft / 4.8;
        s = x * (max(soft, br - 1.2) / max(br, 1e-4));
    }
    return float4(s, 1.0);
}

/** 9-tap tent upsample, blended additively onto the next larger level. */
float4 ncUpFS(FullOut i) : SV_Target
{
    float2 t = postTexel, uv = i.uv;
    float3 s = srcA.SampleLevel(linearClamp, uv, 0).rgb * 4.0
             + (srcA.SampleLevel(linearClamp, uv + float2(t.x, 0.0), 0).rgb + srcA.SampleLevel(linearClamp, uv - float2(t.x, 0.0), 0).rgb
              + srcA.SampleLevel(linearClamp, uv + float2(0.0, t.y), 0).rgb + srcA.SampleLevel(linearClamp, uv - float2(0.0, t.y), 0).rgb) * 2.0
             + srcA.SampleLevel(linearClamp, uv + t, 0).rgb + srcA.SampleLevel(linearClamp, uv - t, 0).rgb
             + srcA.SampleLevel(linearClamp, uv + float2(t.x, -t.y), 0).rgb + srcA.SampleLevel(linearClamp, uv + float2(-t.x, t.y), 0).rgb;
    return float4(s * (postWeight / 16.0), 1.0);
}

/** Horizontal 9-tap blur with a growing stride: anamorphic streaks off bright lights. */
float4 ncStreakFS(FullOut i) : SV_Target
{
    float3 s = float3(0.0, 0.0, 0.0);
    float wsum = 0.0;
    for (int k = -4; k <= 4; k++)
    {
        float w = exp(-float(k * k) * 0.12);
        s += srcA.SampleLevel(linearClamp, i.uv + postStep * float(k), 0).rgb * w;
        wsum += w;
    }
    return float4(s / wsum, 1.0);
}

float3 aces(float3 x) { return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0); }

float4 ncFinalFS(FullOut i) : SV_Target
{
    float2 uv = i.uv;
    // camera motion blur: reproject this pixel with last frame's camera
    float d = depthTex.SampleLevel(pointClamp, uv, 0);
    float4 wp = mul(invViewProj, float4(i.ndc, d, 1.0));
    float4 pp = mul(prevViewProj, float4(wp.xyz / wp.w, 1.0));
    float2 prevNdc = pp.xy / pp.w;
    float2 prev = float2(prevNdc.x * 0.5 + 0.5, 0.5 - prevNdc.y * 0.5);
    float2 vel = clamp(uv - prev, float2(-0.03, -0.03), float2(0.03, 0.03)) * 0.5;
    float jit = hash12(i.position.xy + time);
    float3 c = float3(0.0, 0.0, 0.0);
    [loop] for (int s = 0; s < blurSamples; s++)
    {
        float o = (float(s) + jit) / float(blurSamples) - 0.5;
        c += srcA.SampleLevel(linearClamp, uv + vel * o, 0).rgb;
    }
    c /= float(blurSamples);
    // a touch of lateral colour fringing toward the frame edges
    float2 ca = (uv - 0.5) * 0.0035;
    c.r = lerp(c.r, srcA.SampleLevel(linearClamp, uv + ca, 0).r, 0.6);
    c.b = lerp(c.b, srcA.SampleLevel(linearClamp, uv - ca, 0).b, 0.6);
    c += srcB.SampleLevel(linearClamp, uv, 0).rgb * 0.16 + srcC.SampleLevel(linearClamp, uv, 0).rgb * float3(0.04, 0.05, 0.09);
    c = aces(c * exposure);
    c = pow(c, float3(1.0 / 2.2, 1.0 / 2.2, 1.0 / 2.2));
    float2 v = uv - 0.5;
    c *= 1.0 - dot(v, v) * 0.7;
    c = lerp(dot(c, float3(0.299, 0.587, 0.114)).xxx, c, 1.12);
    c += (hash12(uv * 1733.0 + time) - 0.5) * 0.018;
    return float4(c, 1.0);
}

// ── Resolve of the 4× MSAA depth (Direct3D can't resolve depth formats): sample 0, written as depth ──

Texture2DMS<float> msaaDepthTex : register(t3);

float ncDepthResolveFS(FullOut i) : SV_Depth
{
    return msaaDepthTex.Load(int2(i.position.xy), 0);
}
