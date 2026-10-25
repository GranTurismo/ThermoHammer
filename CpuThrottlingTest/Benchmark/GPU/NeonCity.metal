// NEON CITY SHADERS — Metal port of the Android app's GLSL ES (engine/gpu/NeonCityShaders.kt), GPU Test 2.
//
// Scene passes write linear HDR light (RGBA16F). Every lit surface walks the light list of its 4 m ground cell
// (GGX + Lambert, one shadowed searchlight); windows ray-trace a room behind the glass; the wet street reflects a
// mirrored render, smeared into vertical streaks; fog is ray-marched through the same light grid. The grid lives
// in buffers here (textures on Android) — the same records, read the same way. Metal's clip depth is 0..1 and its
// textures start top-left: matrices are built for that (NeonCityRenderer.swift) and screen UVs are flipped in y.
//
// Neon City RT (GPU Test 3, the port of the Android app's Vulkan renderer, cpp/shaders/) is the same scene with
// rays instead of the mirror and the shadow map: ncObjectRTFS reflects the wet street, glass, paint and chrome
// with rays into the city (hits shaded from the hit triangle's own vertices) and shadows the searchlight and each
// point's two strongest lights; ncFogRTFS cuts the searchlight's shafts with rays. The shading code is shared,
// templated on a tracer (NoRays / Rays), so the other passes hold no ray-tracing code.
#include <metal_stdlib>
#include <metal_raytracing>
using namespace metal;
using namespace metal::raytracing;

constant bool MIRROR [[function_constant(0)]];
constant bool DEPTH_ONLY [[function_constant(1)]];
constant bool THRESHOLD [[function_constant(2)]];

struct NCFrame {
    float4x4 viewProj;
    float4x4 invViewProj;
    float4x4 prevViewProj;
    float4x4 searchMat;
    float4 camPos;
    float4 camRight;
    float4 camUp;
    float4 camFwd;
    float4 origin;          // steam puff origin
    float groups[32];
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
};

struct NCDraw { int actor; int instanced; float seed; };   // actor: 0 static · 1 car · 2 person · 3 train/drone

constant float PI = 3.14159265;
constant float3 HAZE = float3(0.060, 0.042, 0.052);   // rain mist lit by the city
constant float HAZE_START = 150.0;
constant float HAZE_DENSITY = 0.0026;

// vertex classes (CLASSES in build_neon_city.py)
constant int C_CONCRETE = 0, C_BRICK = 1, C_TILE = 2, C_METAL = 3, C_GLASS = 4, C_SHOPGLASS = 5, C_EMISSIVE = 6,
    C_ASPHALT = 7, C_PAVING = 8, C_PAINT = 9, C_CARPAINT = 10, C_CARGLASS = 11, C_RUBBER = 12, C_CHROME = 13,
    C_HEADLIGHT = 14, C_TAILLIGHT = 15, C_FABRIC = 16, C_SKIN = 17, C_FOLIAGE = 18, C_SCREEN = 19, C_TOWER = 20,
    C_LANTERN = 21, C_TRAINWIN = 22, C_WOOD = 23;

static float hash11(float n) { return fract(sin(n) * 43758.5453); }

static float hash12(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

static float hash13(float3 p3) {
    p3 = fract(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return fract((p3.x + p3.y) * p3.z);
}

static float2 hash22(float2 p) {
    float3 p3 = fract(float3(p.xyx) * float3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

static float vnoise(float2 p) {
    float2 i = floor(p), f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash12(i), hash12(i + float2(1.0, 0.0)), u.x), mix(hash12(i + float2(0.0, 1.0)), hash12(i + float2(1.0, 1.0)), u.x), u.y);
}

static float vnoise3(float3 p) {
    float3 i = floor(p), f = fract(p);
    float3 u = f * f * (3.0 - 2.0 * f);
    float a = mix(mix(hash13(i), hash13(i + float3(1, 0, 0)), u.x), mix(hash13(i + float3(0, 1, 0)), hash13(i + float3(1, 1, 0)), u.x), u.y);
    float b = mix(mix(hash13(i + float3(0, 0, 1)), hash13(i + float3(1, 0, 1)), u.x), mix(hash13(i + float3(0, 1, 1)), hash13(i + float3(1, 1, 1)), u.x), u.y);
    return mix(a, b, u.z);
}

static float fbm(float2 p, int octaves) {
    float s = 0.0, a = 0.5, norm = 0.0;
    for (int i = 0; i < octaves; i++) { s += a * vnoise(p); norm += a; p = p * 2.03 + float2(17.1, 9.3); a *= 0.5; }
    return s / norm;
}

// Weight for detail of `freq` cycles/m at `px` m/pixel: 1 when resolved, 0 once it would alias.
static float resolved(float freq, float px) { return clamp(2.0 - 4.0 * px * freq, 0.0, 1.0); }

// Low overcast lit orange-pink from below by the city; drifting rain clouds.
static float3 skyColor(float3 d, float t) {
    float h = max(d.z, 0.0);
    float3 c = mix(float3(0.20, 0.11, 0.12), float3(0.022, 0.022, 0.038), pow(h, 0.45));
    if (d.z > 0.0) {
        float2 uv = d.xy / (d.z + 0.12) * 0.8 + float2(t * 0.006, t * 0.012);
        c *= 0.45 + 1.1 * fbm(uv * 1.4, 5);
    }
    return c;
}

// Cheap surroundings for glossy surfaces: sky above, city lights along the horizon, wet street below.
static float3 envColor(float3 r, float t) {
    if (r.z >= 0.0) {
        float3 c = skyColor(r, t);
        float band = exp(-r.z * 7.0);
        float a = atan2(r.y, r.x);
        float lights = smoothstep(0.6, 0.95, vnoise(float2(a * 46.0, r.z * 38.0)));
        float3 tint = mix(float3(1.0, 0.35, 0.75), float3(0.3, 0.75, 1.0), vnoise(float2(a * 9.0, 3.0)));
        return c + band * (float3(0.16, 0.10, 0.09) + lights * tint * 1.6);
    }
    return float3(0.010, 0.009, 0.012) + float3(0.09, 0.05, 0.07) * exp(r.z * 5.0);
}

static float3 applyHaze(float3 col, float3 w, float3 cam) {
    float d = length(w - cam);
    return mix(col, HAZE, 1.0 - exp(-max(d - HAZE_START, 0.0) * HAZE_DENSITY));
}

// Half-float targets overflow to inf past 65504, and one inf/NaN smears a block through the bloom.
static float3 safeHdr(float3 c) {
    if (!(c.r < 1e20 && c.g < 1e20 && c.b < 1e20 && c.r >= 0.0 && c.g >= 0.0 && c.b >= 0.0)) return float3(0.0);
    return min(c, float3(3000.0));
}

// ── The light grid: cell lists, light records, GGX shading, fog in-scatter, the searchlight shadow ──

constant float2 GRID_ORIGIN = float2(-256.0, -320.0);
constant float GRID_CELL = 4.0;
constant int2 GRID_SIZE = int2(128, 336);
constant float LIGHT_SCALE = 70.0;

struct Grid {
    const device float4* lights;     // 4 records per light: (x y z radius) (r g b type) (dir, cos outer) (group)
    const device uint* cells;        // offset << 12 | moving << 6 | count
    const device ushort* indices;
    depth2d<float> searchShadow;
    constant NCFrame& F;
};

// p's light list: (first index, count, how many of them lead as moving lights)
static int3 cellRange(float3 p, thread const Grid& g) {
    int2 c = int2(floor((p.xy - GRID_ORIGIN) / GRID_CELL));
    if (c.x < 0 || c.y < 0 || c.x >= GRID_SIZE.x || c.y >= GRID_SIZE.y) return int3(0);
    uint v = g.cells[c.y * GRID_SIZE.x + c.x];
    return int3(int(v >> 12u), int(v & 63u), int((v >> 6u) & 63u));
}

static float searchShadow(float3 p, thread const Grid& g) {
    constexpr sampler ss(coord::normalized, filter::linear, address::clamp_to_edge, compare_func::less_equal);
    float4 s = g.F.searchMat * float4(p, 1.0);
    if (s.w <= 0.0) return 0.0;
    float3 c = s.xyz / s.w;
    if (c.x <= 0.0 || c.x >= 1.0 || c.y <= 0.0 || c.y >= 1.0 || c.z >= 1.0) return 1.0;
    const float T = 0.7 / 1024.0;
    float z = c.z - 0.0006;
    return 0.25 * (g.searchShadow.sample_compare(ss, c.xy + float2(-T, -T), z) + g.searchShadow.sample_compare(ss, c.xy + float2(T, -T), z)
                 + g.searchShadow.sample_compare(ss, c.xy + float2(-T, T), z) + g.searchShadow.sample_compare(ss, c.xy + float2(T, T), z));
}

// ── Ray tracing (Neon City RT) ──────────────────────────────────────────────

struct NCVertex { packed_float3 pos; packed_char4 normal; packed_uchar4 color; };   // the asset's 20-byte vertex
struct NCMeshRef { device const NCVertex* vertices; device const uchar* indices; uint wide; uint pad; };
struct NCRTInstance { float4 row0, row1, row2, extra; int mesh; int actor; int instanced; float seed; };

struct NoRays {};
struct Rays {
    instance_acceleration_structure tlas;
    const device NCMeshRef* meshes;
    const device NCRTInstance* instances;
};

static bool rays(thread const NoRays&) { return false; }
static bool rays(thread const Rays&) { return true; }

static float traceShadow(thread const NoRays&, float3, float3) { return 1.0; }

/** 1 if nothing blocks the segment from `from` to (just short of) the light at `to`. */
static float traceShadow(thread const Rays& r, float3 from, float3 to) {
    float3 d = to - from;
    float len = length(d);
    if (len < 0.8) return 1.0;
    intersector<instancing> isect;
    isect.accept_any_intersection(true);
    isect.assume_geometry_type(geometry_type::triangle);
    isect.force_opacity(forced_opacity::opaque);
    auto hit = isect.intersect(ray(from, d / len, 0.02, len - 0.6), r.tlas);    // stop before the lamp's own housing
    return hit.type == intersection_type::none ? 1.0 : 0.0;
}

// Distance + cone attenuation of one light at p (direction to the light in L). Without rays the searchlight
// is shadowed by its shadow map; with rays the caller traces shadows.
template <typename Tr>
static float lightAtten(float4 l0, float4 l1, int id, float3 p, thread float3& L, thread const Grid& g, thread const Tr& tr) {
    float3 lv = l0.xyz - p;
    float d2 = dot(lv, lv);
    float r2 = l0.w * l0.w;
    L = float3(0.0, 0.0, 1.0);
    if (d2 >= r2) return 0.0;
    L = lv * rsqrt(d2);
    float f = d2 / r2;
    float att = (1.0 - f) * (1.0 - f) / (d2 + 1.0);
    if (l1.w > 0.5) {
        float4 l2 = g.lights[id * 4 + 2];
        att *= smoothstep(l2.w, mix(l2.w, 1.0, 0.45), dot(-L, l2.xyz));
        if (!rays(tr) && l1.w > 1.5 && att > 0.0) att *= searchShadow(p, g);
    }
    return att * g.F.groups[int(g.lights[id * 4 + 3].x)];
}

// Light reflected toward v by every light in p's cell: Lambert diffuse + GGX specular. With rays and `shadows`,
// the searchlight and the two strongest lights are shadowed by rays; `most` caps the lights shaded.
template <typename Tr>
static float3 shadeLights(float3 p, float3 n, float3 v, float3 diff, float3 f0, float rough, thread const Grid& g,
                          thread const Tr& tr, bool shadows = true, int most = 1 << 16) {
    const int SHADOWED_LIGHTS = 2;
    int3 r = cellRange(p, g);
    int nm = min(min(r.z, g.F.maxMoving), most);
    int cnt = min(nm + min(r.y - r.z, g.F.maxStatic), most);
    int traced = 0;
    float a = max(rough * rough, 0.012);                  // mirror puddles: sharpness comes from the reflection
    float a2 = a * a, k = a * 0.5;
    float ndv = max(dot(n, v), 1e-3);
    float gv = ndv * (1.0 - k) + k;
    float3 ps = p + n * 0.12;
    float3 sum = float3(0.0);
    for (int j = 0; j < cnt; j++) {
        int id = int(g.indices[r.x + (j < nm ? j : r.z + j - nm)]);
        float4 l0 = g.lights[id * 4];
        float4 l1 = g.lights[id * 4 + 1];
        float3 L;
        float att = lightAtten(l0, l1, id, ps, L, g, tr);
        float ndl = dot(n, L);
        if (att <= 0.0 || ndl <= 0.0) continue;
        if (rays(tr) && shadows && (l1.w > 1.5 || (traced < SHADOWED_LIGHTS && att * max(l1.r, max(l1.g, l1.b)) > 0.002))) {
            att *= traceShadow(tr, ps, l0.xyz);
            traced++;
            if (att <= 0.0) continue;
        }
        float3 h = normalize(L + v);
        float ndh = max(dot(n, h), 0.0);
        float dd = ndh * ndh * (a2 - 1.0) + 1.0;
        float D = a2 / (PI * dd * dd);
        float vis = 0.25 / ((ndl * (1.0 - k) + k) * gv);
        float3 F = f0 + (1.0 - f0) * pow(1.0 - max(dot(L, h), 0.0), 5.0);
        sum += l1.rgb * (att * ndl) * (diff * (1.0 / PI) + F * (D * vis));
    }
    return sum * LIGHT_SCALE;
}

static float phaseHG(float c, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * PI * pow(1.0 + g2 - 2.0 * g * c, 1.5));
}

// Light scattered toward the camera by mist/rain at p (rd = view ray direction); with rays the searchlight beam
// is shadowed (shafts). movingOnly: just the cell's leading moving lights, so traffic never swaps static lights in and out.
template <typename Tr>
static float3 scatterLights(float3 p, float3 rd, int maxLights, bool movingOnly, thread const Grid& g, thread const Tr& tr) {
    int3 r = cellRange(p, g);
    int cnt = min(movingOnly ? r.z : r.y, maxLights);
    float3 sum = float3(0.0);
    for (int i = 0; i < cnt; i++) {
        int id = int(g.indices[r.x + i]);
        float4 l0 = g.lights[id * 4];
        float4 l1 = g.lights[id * 4 + 1];
        float3 L;
        float att = lightAtten(l0, l1, id, p, L, g, tr);
        if (att <= 0.0) continue;
        if (rays(tr) && l1.w > 1.5) att *= traceShadow(tr, p, l0.xyz);
        sum += l1.rgb * att * phaseHG(dot(L, rd), 0.35);
    }
    return sum * LIGHT_SCALE;
}

static float3 scatterLights(float3 p, float3 rd, int maxLights, bool movingOnly, thread const Grid& g) {
    NoRays none;
    return scatterLights(p, rd, maxLights, movingOnly, g, none);
}

// ── Objects: buildings, props, cars, people, trains ─────────────────────────

struct ObjectIn {
    float3 pos [[attribute(0)]];
    float4 normal [[attribute(1)]];    // normal + animation channel / 127
    float4 color [[attribute(2)]];     // sRGB colour (or parameters) + class / 255
    float4 row0 [[attribute(3)]];      // instance transform rows
    float4 row1 [[attribute(4)]];
    float4 row2 [[attribute(5)]];
    float4 extra0 [[attribute(6)]];    // car: paint + brake · person: umbrella + coat shade
    float4 extra1 [[attribute(7)]];    // person: walk phase, swing amplitude
};

struct ObjectOut {
    float4 position [[position]];
    float3 world;
    float3 normal;
    float3 color;
    float4 extra;
    float3 room;                       // glass: position in the window's frame
    float3 roomView;                   // glass: view ray in the window's frame
    int cls [[flat]];
    int chan [[flat]];
    float rand [[flat]];
};

static ObjectOut objectVertex(ObjectIn in, constant NCFrame& F, constant NCDraw& D) {
    int chan = int(in.normal.w * 127.0 + 0.5);
    float3 p = in.pos;
    float3 n = in.normal.xyz;
    if (D.actor == 2) {
        // walk cycle: legs swing about the hip, arms about the shoulder (channel 1-4)
        int part = chan & 31;
        float swing = in.extra1.y * sin(in.extra1.x);
        float ang = part == 1 ? swing : part == 2 ? -swing : part == 3 ? -0.8 * swing : part == 4 ? 0.8 * swing : 0.0;
        if (ang != 0.0) {
            float pivot = part <= 2 ? 0.93 : 1.41;
            float c = cos(ang), s = sin(ang);
            float2 yz = float2(p.y, p.z - pivot);
            p.yz = float2(c * yz.x - s * yz.y, s * yz.x + c * yz.y + pivot);
            n.yz = float2(c * n.y - s * n.z, s * n.y + c * n.z);
        }
    }
    float4 hp = float4(p, 1.0);
    float3 w = float3(dot(in.row0, hp), dot(in.row1, hp), dot(in.row2, hp));
    ObjectOut o;
    o.world = w;
    o.position = F.viewProj * float4(w, 1.0);
    if (DEPTH_ONLY) return o;
    float3 nw = normalize(float3(dot(in.row0.xyz, n), dot(in.row1.xyz, n), dot(in.row2.xyz, n)));
    int cls = int(in.color.a * 255.0 + 0.5);
    o.normal = nw;
    o.cls = cls;
    o.chan = chan;
    o.color = cls == C_SCREEN ? in.color.rgb : pow(in.color.rgb, float3(2.2));
    o.extra = in.extra0;
    float3 origin = float3(in.row0.w, in.row1.w, in.row2.w);
    o.rand = hash13(origin * 0.37 + D.seed);
    // window frame: x along the facade, y out of it, z up (origin = window sill, or world for merged chunks)
    float3 O = normalize(float3(nw.xy, 0.0) + float3(1e-5, 0.0, 0.0));
    float3 T = float3(O.y, -O.x, 0.0);
    float3 d = w - origin;
    o.room = float3(dot(d, T), dot(d, O), d.z);
    float3 vv = w - F.camPos.xyz;
    o.roomView = float3(dot(vv, T), dot(vv, O), vv.z);
    return o;
}

vertex ObjectOut ncObjectVS(ObjectIn in [[stage_in]], constant NCFrame& F [[buffer(2)]], constant NCDraw& D [[buffer(3)]]) {
    return objectVertex(in, F, D);
}

vertex float4 ncDepthVS(ObjectIn in [[stage_in]], constant NCFrame& F [[buffer(2)]], constant NCDraw& D [[buffer(3)]]) {
    return objectVertex(in, F, D).position;
}

constant float TIER[4] = { 1.0, 2.5, 5.0, 9.0 };

static float groupValue(int ch, float3 p, constant NCFrame& F) {
    int g = ch & 31;
    if (g >= 9 && g <= 12) {                               // letter chase: a bright band runs along the sign
        float s = dot(p, float3(0.7, 0.7, 0.3)) * 0.9 - F.time * (1.6 + 0.4 * float(g - 9));
        return 0.2 + 0.8 * smoothstep(0.32, 0.12, abs(fract(s) - 0.5));
    }
    return F.groups[g];
}

static float gmod(float a, float b) { return a - b * floor(a / b); }

// LED billboard: animated ad content on a visible pixel grid.
static float3 screenContent(float2 uv, float seed, float time, float fwHint = -1.0) {
    float t = time * 0.6 + seed * 20.0;
    float scene = floor(gmod(t / 4.0 + seed * 3.0, 3.0));
    float3 c;
    if (scene < 0.5) {                                     // brand gradient with a pulsing logo disc
        c = mix(float3(1.0, 0.1, 0.45), float3(0.15, 0.25, 1.0), uv.x + 0.25 * sin(t + uv.y * 3.0));
        float r = length((uv - float2(0.3, 0.55)) * float2(2.0, 1.0));
        c = mix(c, float3(1.0, 0.95, 0.8), smoothstep(0.24, 0.2, r) * (0.7 + 0.3 * sin(t * 3.0)));
        c *= 0.75 + 0.25 * step(0.5, fract(uv.y * 6.0 - t));
    } else if (scene < 1.5) {                              // scrolling headline bars
        float row = floor(uv.y * 5.0);
        float bar = step(0.35, hash12(float2(floor(uv.x * 9.0 + t * 2.0 * (1.0 + row * 0.3)), row)));
        c = mix(float3(0.02, 0.05, 0.12), float3(0.2, 0.9, 1.0), bar * step(0.2, fract(uv.y * 5.0)));
        c += float3(1.0, 0.5, 0.05) * step(0.82, uv.y);
    } else {                                               // abstract motion (music video)
        float v = sin(uv.x * 9.0 + t * 2.0) + sin(uv.y * 7.0 - t * 1.3) + sin((uv.x + uv.y) * 6.0 + t);
        c = 0.5 + 0.5 * cos(float3(0.0, 2.1, 4.2) + v * 1.4 + t * 0.4);
    }
    float2 px = fract(uv * float2(160.0, 80.0));
    float grid = smoothstep(0.0, 0.18, px.x) * smoothstep(0.0, 0.18, px.y);
    float fw = fwHint >= 0.0 ? fwHint : length(fwidth(uv * float2(160.0, 80.0)));
    return c * mix(grid, 0.72, clamp(fw * 1.2, 0.0, 1.0));
}

// A room behind the glass, ray-traced in the window's frame. Returns emitted light.
static float3 interior(float3 q, float3 v, float style, bool shop, float3 vColor, float vRand, int instanced, float time) {
    float roomW = shop ? 6.0 : style > 0.7 ? 4.2 : 3.4;
    float h = shop ? 3.4 : 3.0;
    float base = shop ? 0.15 : instanced == 1 ? (style > 0.9 ? 0.0 : -0.9) : 4.8;
    float cx = floor(q.x / roomW + 0.5);
    float cz = shop ? 0.0 : floor((q.z - base) / 3.2);
    float x0 = (cx - 0.5) * roomW;
    float z0 = base + cz * 3.2;
    float id = hash13(float3(cx, cz, vRand * 97.0));
    float depth = (shop ? 7.0 : 4.0) + 2.5 * hash11(id * 13.1);
    float3 iv = 1.0 / (v + float3(1e-5));
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
    float3 lamp = shop ? vColor : style > 0.7 ? float3(0.80, 0.90, 1.0) : mix(float3(1.0, 0.62, 0.32), float3(1.0, 0.80, 0.55), hash11(id * 7.0));
    float3 wall = mix(float3(0.55, 0.50, 0.42), float3(0.42, 0.48, 0.52), hash11(id * 3.3));
    float3 c;
    if (t == tz) {
        c = hp.z > z0 + 0.5 * h ? float3(0.75) : (shop ? float3(0.5, 0.48, 0.45) : float3(0.32, 0.20, 0.12));
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
            c = mix(c, mix(vColor, float3(1.0), hash12(floor(float2(rx * 14.0, rz * 5.0)))), shelf);
        } else {
            c *= 1.0 - 0.6 * step(rz, 0.3) * step(0.2, rx) * step(rx, 0.8);   // sofa / desk
            if (hash11(id * 5.1) > 0.5) c = mix(c, float3(0.08, 0.06, 0.05), step(abs(rx - 0.5), 0.12) * step(abs(rz - 0.62), 0.12));
        }
    }
    // the lamp hangs near the ceiling in the middle of the room
    float3 lp = float3(x0 + 0.5 * roomW, q.y - depth * 0.45, z0 + h - 0.3);
    float fall = 1.6 / (1.0 + 0.12 * dot(hp - lp, hp - lp));
    float3 e = lit ? c * lamp * fall * (shop ? 1.8 : 1.0) : c * float3(0.006, 0.006, 0.01);
    if (tv) e += float3(0.25, 0.45, 1.0) * (0.25 + 0.2 * sin(time * 7.0 + id * 40.0) * sin(time * 2.3)) * smoothstep(0.5, 0.0, ry);
    if (!shop && hash11(id * 9.7) > 0.62) {                // blinds or curtains in front of the room
        float slats = 0.55 + 0.45 * step(0.25, fract(q.z * 9.0));
        float3 cloth = lit ? lamp * (0.18 + 0.2 * hash11(id * 3.9)) * slats : float3(0.008) * slats;
        e = mix(e, cloth, 0.85);
    }
    return e;
}

// Tower facades seen from afar: a grid of windows, some lit.
static float3 towerWindows(float3 p, float3 n, thread float& glassMask, float pxHint = -1.0) {
    float3 t = normalize(float3(n.y, -n.x, 0.0) + float3(1e-4, 0.0, 0.0));
    float2 uv = float2(dot(p, t) / 1.8, p.z / 3.5);
    float2 cell = floor(uv), f = fract(uv);
    float px = pxHint >= 0.0 ? pxHint : length(fwidth(uv));
    float win = step(abs(f.x - 0.5), 0.32) * step(abs(f.y - 0.55), 0.3);
    win = mix(win, 0.38, clamp(px * 1.5 - 0.3, 0.0, 1.0));
    float r = hash12(cell + floor(n.xy * 3.0) * 17.0);
    float lit = step(0.62, r);
    lit = mix(lit, 0.38, clamp(px * 1.5 - 0.3, 0.0, 1.0));
    glassMask = win;
    float3 warm = mix(float3(1.0, 0.66, 0.36), float3(0.75, 0.88, 1.0), step(0.86, r));
    return win * lit * warm * 1.4;
}

static float2 rippleSlope(float2 p, float time) {
    float2 g = float2(0.0);
    for (int layer = 0; layer < 2; layer++) {
        float2 q = p * 1.9 + float(layer) * float2(0.37, 0.71);
        float2 cell = floor(q);
        float2 h = hash22(cell + float(layer) * 13.0);
        float2 f = fract(q) - 0.25 - 0.5 * h;
        float life = fract(time * 1.2 + h.x * 7.0);
        float r = length(f);
        float ring = r - life * 0.45;
        float wave = sin(ring * 60.0) * smoothstep(0.08, 0.0, abs(ring)) * (1.0 - life) * (1.0 - life);
        g += f / max(r, 1e-3) * wave * 0.35;
    }
    return g;
}

static bool isEmissive(int k) {
    return k == C_EMISSIVE || k == C_HEADLIGHT || k == C_TAILLIGHT || k == C_LANTERN || k == C_TRAINWIN || k == C_SCREEN;
}

// Signs, lamps, screens and lit train windows: their light, by brightness tier and animation group.
static float3 emission(int k, int chan, float3 color, float3 P, float3 N, int actor, float4 extra, constant NCFrame& F, float fwHint = -1.0) {
    float tier = TIER[(chan >> 5) & 3];
    float3 base = color;
    float strength = k == C_HEADLIGHT ? 2.0 : k == C_TAILLIGHT ? 1.2 : k == C_TRAINWIN ? 0.45 : k == C_LANTERN ? 0.9 : 1.0;
    if (k == C_TAILLIGHT && actor == 1) strength *= 1.0 + 2.5 * extra.w;
    if (k == C_SCREEN) base = screenContent(color.rg, color.b, F.time, fwHint) * 0.35;
    if (k == C_LANTERN) base *= 0.6 + 0.4 * smoothstep(-0.2, 0.6, N.z + 0.3);   // paper glows brighter on top
    return base * (tier * strength * groupValue(chan, P, F));
}

// Albedo, roughness and metalness of the solid classes (car paint and umbrellas take the instance's colour).
static void surfaceParams(int k, int chan, int actor, float4 extra, thread float3& albedo, thread float& rough, thread float& metal) {
    rough = 0.6;
    metal = 0.0;
    if (k == C_CARPAINT) { albedo = albedo * (actor == 1 ? extra.rgb : float3(0.05)); rough = 0.22; }
    else if (k == C_CARGLASS) { albedo = float3(0.01); rough = 0.05; }
    else if (k == C_RUBBER) { rough = 0.55; }
    else if (k == C_CHROME) { metal = 1.0; rough = 0.18; }
    else if (k == C_METAL) { metal = 0.6; rough = 0.38; }
    else if (k == C_FABRIC) {
        int part = chan & 31;
        if (actor == 2 && part == 5) albedo = extra.rgb;                                   // wet umbrella
        else if (actor == 2 && part == 6) albedo = float3(extra.w) * float3(0.55, 0.5, 0.48) * 0.6;
        rough = part == 5 ? 0.25 : 0.8;
    }
    else if (k == C_SKIN) { rough = 0.5; }
    else if (k == C_FOLIAGE) { rough = 0.7; }
    else if (k == C_WOOD) { rough = 0.55; }
}

static float3 cityGlow(float3 diff, float3 N) {
    return diff * (float3(0.018, 0.015, 0.022) + float3(0.03, 0.02, 0.025) * max(N.z, 0.0));
}

static float3 traceReflection(thread const NoRays&, float3, float3 d, thread const Grid& g) { return envColor(d, g.F.time); }

// What a reflection ray sees where it lands: the same materials, lit by a few unshadowed lights.
static float3 shadeHit(thread const Rays& r, uint inst, uint prim, float2 bc, float3 P, float3 V, thread const Grid& g) {
    constant NCFrame& F = g.F;
    NCRTInstance I = r.instances[inst];
    NCMeshRef m = r.meshes[I.mesh];
    uint i0, i1, i2;
    if (m.wide != 0) {
        device const uint* ix = (device const uint*)m.indices;
        i0 = ix[prim * 3]; i1 = ix[prim * 3 + 1]; i2 = ix[prim * 3 + 2];
    } else {
        device const ushort* ix = (device const ushort*)m.indices;
        i0 = ix[prim * 3]; i1 = ix[prim * 3 + 1]; i2 = ix[prim * 3 + 2];
    }
    NCVertex a = m.vertices[i0], b = m.vertices[i1], c = m.vertices[i2];
    float w0 = 1.0 - bc.x - bc.y;
    float3 n = (float3(a.normal.xyz) * w0 + float3(b.normal.xyz) * bc.x + float3(c.normal.xyz) * bc.y) / 127.0;
    float3 N = normalize(float3(dot(I.row0.xyz, n), dot(I.row1.xyz, n), dot(I.row2.xyz, n)));
    if (dot(N, V) < 0.0) N = -N;
    float3 raw = (float3(a.color.rgb) * w0 + float3(b.color.rgb) * bc.x + float3(c.color.rgb) * bc.y) / 255.0;
    int k = int(a.color.a);
    int ch = int(a.normal.w);
    float dist = length(P - F.camPos.xyz);
    float3 col;
    if (isEmissive(k)) {
        col = emission(k, ch, k == C_SCREEN ? raw : pow(raw, float3(2.2)), P, N, I.actor, I.extra, F, 1.0);
    } else if (k == C_GLASS || k == C_SHOPGLASS) {
        // a lit room or a dark pane, decided per window cell
        float lit = step(0.5, hash13(floor(P * float3(0.3, 0.3, 0.31))));
        col = k == C_SHOPGLASS ? pow(raw, float3(2.2)) * 1.2 : mix(float3(0.004), float3(0.6, 0.42, 0.25), lit);
        col += envColor(reflect(-V, N), F.time) * 0.08;
    } else {
        float3 albedo = pow(raw, float3(2.2));
        float rough, metal;
        surfaceParams(k, ch, I.actor, I.extra, albedo, rough, metal);
        float3 emit = float3(0.0);
        if (k == C_TOWER) {
            float gm;
            emit = towerWindows(P, N, gm, dist * 0.004);
            albedo *= 1.0 - 0.7 * gm;
        }
        if (k == C_ASPHALT || k == C_PAVING || k == C_PAINT) albedo *= 0.5;
        float3 diff = albedo * (1.0 - metal);
        col = shadeLights(P, N, V, diff, mix(float3(0.04), albedo, metal), rough, g, r, false, 4) + cityGlow(diff, N) + emit;
    }
    return applyHaze(col, P, F.camPos.xyz);
}

/** Radiance arriving along a reflection ray from o in direction d. */
static float3 traceReflection(thread const Rays& r, float3 o, float3 d, thread const Grid& g) {
    intersector<triangle_data, instancing> isect;
    isect.assume_geometry_type(geometry_type::triangle);
    isect.force_opacity(forced_opacity::opaque);
    auto hit = isect.intersect(ray(o, d, 0.03, 400.0), r.tlas);
    if (hit.type == intersection_type::triangle) {
        return shadeHit(r, hit.instance_id, hit.primitive_id, hit.triangle_barycentric_coord, o + d * hit.distance, -d, g);
    }
    return mix(skyColor(d, g.F.time), HAZE, exp(-max(d.z, 0.0) * 12.0) * 0.8);
}

template <typename Tr>
static float4 objectShade(ObjectOut in, constant NCFrame& F, constant NCDraw& D, thread const Grid& g,
                          texture2d<float> reflection, thread const Tr& tr) {
    float3 P = in.world;
    int k = in.cls;
    bool ground = k == C_ASPHALT || k == C_PAVING || k == C_PAINT;
    if (MIRROR && (P.z < -0.02 || (ground && P.z < 0.3))) discard_fragment();
    float3 N = normalize(in.normal);
    float3 V = normalize(F.camPos.xyz - P);
    if (dot(N, V) < 0.0) N = -N;
    float3 col;
    // with rays every branch only chooses its reflection rays; they are traced once, below
    float3 rayDir[2];
    int nrays = 0;
    float reflMix = 0.0;         // how much of the result is reflection
    float3 after = float3(0.0);  // added on top of the reflection (lights on glass, window emission)

    if (isEmissive(k)) {
        col = emission(k, in.chan, in.color, P, N, D.actor, in.extra, F);
        col += envColor(reflect(-V, N), F.time) * 0.04;     // a glossy wet film over every lamp and sign
    } else if (k == C_GLASS || k == C_SHOPGLASS) {
        bool shop = k == C_SHOPGLASS;
        float3 inside = interior(in.room, normalize(in.roomView), shop ? 0.5 : in.color.b, shop, in.color, in.rand, D.instanced, F.time);
        float Fr = 0.04 + 0.96 * pow(1.0 - max(dot(N, V), 0.0), 5.0);
        if (rays(tr)) {
            col = inside;
            rayDir[0] = reflect(-V, N);
            nrays = 1;
            reflMix = Fr;
            after = shadeLights(P, N, V, float3(0.0), float3(0.04), 0.07, g, tr, false);
        } else {
            col = inside * (1.0 - Fr) + envColor(reflect(-V, N), F.time) * Fr;
            col += shadeLights(P, N, V, float3(0.0), float3(0.04), 0.07, g, tr);
        }
    } else {
        float3 albedo = in.color;
        float rough, metal, wet = 0.0;
        surfaceParams(k, in.chan, D.actor, in.extra, albedo, rough, metal);
        float3 emit = float3(0.0);
        if (k == C_TOWER) {
            float glassMask;
            emit = towerWindows(P, N, glassMask);
            rough = mix(0.5, 0.08, glassMask);
            albedo *= 1.0 - 0.7 * glassMask;
        }

        if (k == C_CONCRETE || k == C_BRICK || k == C_TILE || k == C_WOOD) {
            float3 t = normalize(float3(N.y, -N.x, 0.0) + float3(1e-4, 0.0, 0.0));
            float2 uv = abs(N.z) > 0.7 ? P.xy : float2(dot(P, t), P.z);
            float px = length(fwidth(uv));
            if (k == C_BRICK) {
                float row = floor(uv.y / 0.16);
                float bx = uv.x / 0.32 + 0.5 * gmod(row, 2.0);
                float mortar = max(step(0.86, fract(uv.y / 0.16)), step(0.93, fract(bx)));
                float tone = 0.8 + 0.4 * hash12(float2(floor(bx), row));
                albedo *= mix(1.0, mix(tone, 1.7, mortar), resolved(3.0, px));
            } else if (k == C_TILE) {
                float2 tg = fract(uv / 0.2);
                float grout = max(step(0.92, tg.x), step(0.92, tg.y));
                albedo *= mix(1.0, mix(0.9 + 0.2 * hash12(floor(uv / 0.2)), 0.55, grout), resolved(2.5, px));
                rough = 0.3;
            }
            float grime = fbm(uv * float2(0.35, 0.18), 3);
            albedo *= 0.7 + 0.6 * grime;
            // rain runs down the walls in streaks: darker and glossier
            float streak = smoothstep(0.45, 0.8, vnoise(float2(uv.x * 2.5, uv.y * 0.12 + F.time * 0.05)));
            wet = streak * (1.0 - abs(N.z)) * resolved(1.0, px) + 0.35;
            rough = mix(rough, 0.18, wet * 0.6);
            albedo *= 1.0 - 0.35 * wet;
        }

        float3 refl = float3(0.0);
        float reflAmount = 0.0;
        if (ground) {
            // wet street: standing water in puddles, ripples from the rain, mirror (or ray-traced) reflections
            float big = fbm(P.xy * (k == C_PAVING ? 0.3 : 0.16), 4);
            float puddle = smoothstep(0.5, 0.6, big);
            if (k == C_PAVING) puddle *= 0.7;
            float2 slope = rippleSlope(P.xy, F.time) * puddle;
            float px = length(fwidth(P.xy));
            slope += (float2(vnoise(P.xy * 7.0), vnoise(P.xy * 7.0 + 31.0)) - 0.5) * 0.18 * (1.0 - puddle) * resolved(7.0, px);
            N = normalize(float3(-slope, 1.0));
            rough = mix(k == C_PAVING ? 0.4 : 0.3, 0.03, puddle);
            albedo *= mix(0.75, 0.35, puddle) * (0.8 + 0.4 * fbm(P.xy * 0.9, 2));
            wet = 0.7 + 0.3 * puddle;
            float Fr = 0.02 + 0.98 * pow(1.0 - max(dot(N, V), 0.0), 5.0);
            if (rays(tr)) {
                // two rays fanned up and down the street smear lights into the long streaks of wet asphalt
                float3 R = reflect(-V, N);
                float3 up = normalize(cross(cross(R, float3(0.0, 0.0, 1.0)), R) + float3(1e-4));
                float spread = rough * 0.35;
                rayDir[0] = normalize(R + up * spread);
                rayDir[1] = normalize(R - up * spread * 0.6);
                nrays = 2;
                reflMix = mix(0.25, 1.0, puddle) * Fr;
            } else if (!MIRROR) {
                constexpr sampler mip(filter::linear, mip_filter::linear, address::clamp_to_edge);
                float2 suv = in.position.xy * F.invViewport + N.xy * float2(1.0, -1.0) * 0.035;
                float spread = 0.004 + rough * 0.09;
                float lod = rough * 5.0;
                float wsum = 0.0;
                for (int i = 0; i < F.reflTaps; i++) {
                    float o = (float(i) + 0.5) / float(F.reflTaps) * 2.0 - 1.0;
                    float wt = exp(-o * o * 2.5);
                    refl += reflection.sample(mip, float2(suv.x, suv.y + o * spread), level(lod)).rgb * wt;
                    wsum += wt;
                }
                refl /= wsum;
                reflAmount = mix(0.25, 1.0, puddle) * Fr;
            }
        } else if (rays(tr) && (k == C_CARPAINT || k == C_CARGLASS || k == C_CHROME || (k == C_METAL && metal > 0.0))) {
            float Fc = 0.04 + 0.96 * pow(1.0 - max(dot(N, V), 0.0), 5.0);
            rayDir[0] = reflect(-V, N);
            nrays = 1;
            reflMix = metal > 0.5 ? 0.7 : Fc * (k == C_CARPAINT ? 1.4 : 1.0);
        }

        float3 diff = albedo * (1.0 - metal);
        float3 f0 = mix(float3(0.04 + 0.02 * wet), albedo, metal);
        col = shadeLights(P, N, V, diff, f0, rough, g, tr);
        col += cityGlow(diff, N);
        float Fe = 0.04 + 0.96 * pow(1.0 - max(dot(N, V), 0.0), 5.0);
        float gloss = (1.0 - rough) * (1.0 - rough);
        if (!ground && nrays == 0) col += envColor(reflect(-V, N), F.time) * mix(Fe * gloss, 0.7, metal) * (k == C_CARPAINT ? 1.6 : 1.0);
        col = mix(col, refl, reflAmount);
        after = emit;
    }
    if (nrays > 0) {
        float3 refl = float3(0.0);
        for (int i = 0; i < nrays; i++) refl += traceReflection(tr, P + N * 0.03, rayDir[i], g);
        col = mix(col, refl / float(nrays), clamp(reflMix, 0.0, 1.0));
    }
    col = applyHaze(col + after, P, F.camPos.xyz);
    return float4(safeHdr(col), 1.0);
}

fragment float4 ncObjectFS(ObjectOut in [[stage_in]], constant NCFrame& F [[buffer(2)]], constant NCDraw& D [[buffer(3)]],
                           const device float4* lights [[buffer(4)]], const device uint* cells [[buffer(5)]],
                           const device ushort* indices [[buffer(6)]],
                           depth2d<float> search [[texture(0)]], texture2d<float> reflection [[texture(1)]]) {
    Grid g = { lights, cells, indices, search, F };
    NoRays tr;
    return objectShade(in, F, D, g, reflection, tr);
}

fragment float4 ncObjectRTFS(ObjectOut in [[stage_in]], constant NCFrame& F [[buffer(2)]], constant NCDraw& D [[buffer(3)]],
                             const device float4* lights [[buffer(4)]], const device uint* cells [[buffer(5)]],
                             const device ushort* indices [[buffer(6)]], instance_acceleration_structure tlas [[buffer(7)]],
                             const device NCMeshRef* meshes [[buffer(8)]], const device NCRTInstance* instances [[buffer(9)]],
                             depth2d<float> search [[texture(0)]], texture2d<float> reflection [[texture(1)]]) {
    Grid g = { lights, cells, indices, search, F };
    Rays tr = { tlas, meshes, instances };
    return objectShade(in, F, D, g, reflection, tr);
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

vertex FullOut ncSkyVS(uint vid [[vertex_id]]) { return fullTriangle(vid, 1.0); }
vertex FullOut ncPostVS(uint vid [[vertex_id]]) { return fullTriangle(vid, 0.0); }

fragment float4 ncSkyFS(FullOut in [[stage_in]], constant NCFrame& F [[buffer(2)]]) {
    float4 a = F.invViewProj * float4(in.ndc, 0.0, 1.0);
    float4 b = F.invViewProj * float4(in.ndc, 1.0, 1.0);
    float3 dir = normalize(b.xyz / b.w - a.xyz / a.w);
    if (MIRROR) dir.z = -dir.z;
    return float4(mix(skyColor(dir, F.time), HAZE, exp(-max(dir.z, 0.0) * 12.0) * 0.8), 1.0);
}

// ── Rain, splashes, light halos, steam (premultiplied: alpha 0 adds light) ─────

struct QuadOut { float4 position [[position]]; float2 q; float3 light; float alpha; float seed; };

constant float2 CORNERS_UP[6] = { float2(-1, 0), float2(1, 0), float2(1, 1), float2(-1, 0), float2(1, 1), float2(-1, 1) };
constant float2 CORNERS[6] = { float2(-1, -1), float2(1, -1), float2(1, 1), float2(-1, -1), float2(1, 1), float2(-1, 1) };

static float3 gmod3(float3 a, float3 b) { return a - b * floor(a / b); }

vertex QuadOut ncRainVS(uint vid [[vertex_id]], uint iid [[instance_id]], constant NCFrame& F [[buffer(2)]],
                        const device float4* lights [[buffer(4)]], const device uint* cells [[buffer(5)]],
                        const device ushort* indices [[buffer(6)]], depth2d<float> search [[texture(0)]]) {
    Grid g = { lights, cells, indices, search, F };
    const float3 BOX = float3(36.0, 36.0, 24.0);
    float id = float(iid);
    float3 seed = float3(hash11(id * 0.1371 + 0.3), hash11(id * 0.7193 + 3.1), hash11(id * 1.3711 + 7.7));
    float speed = 8.0 + 3.0 * hash11(id * 2.17);
    float3 fall = float3(1.1, 0.45, -speed);
    float3 cam = F.camPos.xyz;
    float3 centre = cam + float3(F.camFwd.xy, 0.0) * (BOX.x * 0.36);
    float3 p = seed * BOX + fall * F.time;
    p = centre + gmod3(p - centre + BOX * 0.5, BOX) - BOX * 0.5;
    float3 axis = normalize(fall);
    float dist = length(cam - p);
    float len = 0.04 * speed * (1.0 + 0.5 * hash11(id * 5.3));       // ~1/20 s exposure
    float3 side = normalize(cross(axis, cam - p)) * (0.004 + dist * 0.0009);
    float2 q = CORNERS_UP[vid];
    float3 w = p + side * q.x + axis * (q.y * len);
    QuadOut o;
    o.q = q;
    // drops light up where they cross a lamp's beam, mostly when back-lit
    float3 rd = normalize(p - cam);
    o.light = (scatterLights(p, rd, 6, false, g) * 0.35 + HAZE * 0.6) * smoothstep(0.8, 2.5, dist) * (1.0 - smoothstep(24.0, 34.0, dist));
    o.position = F.viewProj * float4(w, 1.0);
    return o;
}

fragment float4 ncRainFS(QuadOut in [[stage_in]]) {
    float a = (1.0 - abs(in.q.x)) * smoothstep(0.0, 0.3, in.q.y) * smoothstep(1.0, 0.6, in.q.y);
    return float4(in.light * a, 0.0);
}

vertex QuadOut ncSplashVS(uint vid [[vertex_id]], uint iid [[instance_id]], constant NCFrame& F [[buffer(2)]],
                          const device float4* lights [[buffer(4)]], const device uint* cells [[buffer(5)]],
                          const device ushort* indices [[buffer(6)]], depth2d<float> search [[texture(0)]]) {
    Grid g = { lights, cells, indices, search, F };
    float id = float(iid);
    float rate = 2.4 + hash11(id * 3.7);
    float cyc = F.time * rate + hash11(id * 1.9);
    float life = fract(cyc);
    float n = floor(cyc);
    float2 r = float2(hash11(id * 0.37 + n * 1.13), hash11(id * 0.91 + n * 2.71));
    float2 ahead = F.camPos.xy + F.camFwd.xy * 14.0;
    float2 xy = ahead + (r - 0.5) * float2(30.0, 30.0);
    float z = abs(xy.x) > 7.0 ? 0.16 : 0.01;
    float size = 0.05 + 0.1 * life;
    float2 q = CORNERS_UP[vid];
    float3 w = float3(xy, z) + F.camRight.xyz * (q.x * size) + float3(0.0, 0.0, q.y * size * 0.7);
    QuadOut o;
    o.q = q;
    o.alpha = life;
    float dist = length(float3(xy, z) - F.camPos.xyz);
    o.light = (scatterLights(float3(xy, z + 0.2), normalize(float3(xy, z) - F.camPos.xyz), 4, false, g) * 0.25 + HAZE)
            * (1.0 - smoothstep(16.0, 24.0, dist));
    o.position = F.viewProj * float4(w, 1.0);
    return o;
}

fragment float4 ncSplashFS(QuadOut in [[stage_in]]) {
    // a thin crown: an arc that rises and thins as the splash ages
    float r = length(float2(in.q.x, in.q.y * 1.4));
    float ring = smoothstep(0.25, 0.0, abs(r - 0.75)) * step(0.0, in.q.y);
    float a = ring * (1.0 - in.alpha) * 0.9;
    return float4(in.light * a, 0.0);
}

/** Soft halo around each light, the glow of rain mist close to the lamp. */
vertex QuadOut ncGlowVS(uint vid [[vertex_id]], uint iid [[instance_id]], constant NCFrame& F [[buffer(2)]],
                        const device float4* lights [[buffer(4)]]) {
    int id = int(iid);
    float4 l0 = lights[id * 4];
    float4 l1 = lights[id * 4 + 1];
    float g = F.groups[int(lights[id * 4 + 3].x)];
    float3 toCam = F.camPos.xyz - l0.xyz;
    float dist = length(toCam);
    float size = 0.5 + l0.w * 0.09 + dist * 0.004;
    if (l1.w > 0.5) {
        // a spot glares only into its beam; lamps aimed at the street keep a dim halo round the head
        float4 l2 = lights[id * 4 + 2];
        float facing = smoothstep(l2.w - 0.3, 1.0, dot(l2.xyz, toCam / max(dist, 0.01)));
        g *= max(l2.z < -0.5 ? 0.35 : 0.0, facing);
    }
    float3 c = l1.rgb * g * (0.022 / (1.0 + dist * 0.015)) * (1.0 - smoothstep(110.0, 160.0, dist));
    float2 q = CORNERS[vid];
    float3 w = l0.xyz + toCam / max(dist, 0.01) * min(0.6, dist * 0.5) + (F.camRight.xyz * q.x + F.camUp.xyz * q.y) * size;
    QuadOut o;
    o.q = q;
    o.light = c;
    o.position = dist < 1.0 ? float4(0.0, 0.0, 2.0, 1.0) : F.viewProj * float4(w, 1.0);
    return o;
}

fragment float4 ncGlowFS(QuadOut in [[stage_in]]) {
    float r2 = dot(in.q, in.q);
    float a = exp(-r2 * 4.0) + 0.6 * exp(-r2 * 30.0);
    return float4(in.light * a, 0.0);
}

vertex QuadOut ncSteamVS(uint vid [[vertex_id]], uint iid [[instance_id]], constant NCFrame& F [[buffer(2)]],
                         const device float4* lights [[buffer(4)]], const device uint* cells [[buffer(5)]],
                         const device ushort* indices [[buffer(6)]], depth2d<float> search [[texture(0)]]) {
    Grid g = { lights, cells, indices, search, F };
    float id = float(iid);
    float life = fract(F.time / 6.0 + id / 24.0);
    float3 p = F.origin.xyz + float3(1.4 * life * life + 0.3 * sin(id * 3.1 + F.time * 0.7),
                                     0.6 * life + 0.3 * cos(id * 1.7 + F.time * 0.5), 0.2 + life * 5.5);
    float size = 0.4 + life * 2.6;
    float2 q = CORNERS[vid];
    QuadOut o;
    o.q = q;
    o.seed = id;
    o.alpha = smoothstep(0.0, 0.12, life) * (1.0 - life) * 0.35;
    o.light = scatterLights(p, normalize(p - F.camPos.xyz), 6, false, g) * 0.6 + HAZE * 2.0;
    o.position = F.viewProj * float4(p + (F.camRight.xyz * q.x + F.camUp.xyz * q.y) * size, 1.0);
    return o;
}

fragment float4 ncSteamFS(QuadOut in [[stage_in]], constant NCFrame& F [[buffer(2)]]) {
    float r = length(in.q);
    if (r > 1.0) discard_fragment();
    float puff = fbm(in.q * 1.6 + float2(in.seed * 0.37, F.time * 0.2), 3);
    float a = in.alpha * smoothstep(1.0, 0.2, r) * (0.4 + 0.6 * puff);
    return float4(in.light * a, a);
}

// ── Post: volumetric fog, combine, bloom, streaks, final ──────────────────────

/** Half-resolution ray march through rain mist, lit by the grid (the searchlight shadowed by its map, or by rays). */
template <typename Tr>
static float4 fogMarch(FullOut in, constant NCFrame& F, thread const Grid& g, depth2d<float> depth, thread const Tr& tr) {
    constexpr sampler nearest(filter::nearest, address::clamp_to_edge);
    const float FOG_MAX = 170.0, FOG_DENSITY = 0.011;
    float d = depth.sample(nearest, in.uv);
    float4 e = F.invViewProj * float4(in.ndc, d, 1.0);
    float3 end = e.xyz / e.w;
    float3 cam = F.camPos.xyz;
    float3 rd = normalize(end - cam);
    float tEnd = min(length(end - cam), FOG_MAX);
    float j = fract(52.9829189 * fract(dot(in.position.xy, float2(0.06711056, 0.00583715))) + F.frame * 0.618034);
    float T = 1.0;
    float3 S = float3(0.0);
    float steps = float(F.fogSteps);
    for (int i = 0; i < F.fogSteps; i++) {
        float u = (float(i) + j) / steps;
        float t = tEnd * u * u;                            // denser sampling near the camera
        float dt = tEnd * 2.0 * u / steps;
        float3 p = cam + rd * t;
        float dens = FOG_DENSITY * exp(-max(p.z, 0.0) * 0.025)
                   * (0.5 + vnoise3(p * float3(0.06, 0.06, 0.03) + float3(0.3, 0.1, 0.5) * F.time));
        float3 li = HAZE + scatterLights(p, rd, F.fogLights, true, g, tr);
        S += T * dens * dt * li;
        T *= exp(-dens * dt);
    }
    return float4(safeHdr(S), T);
}

fragment float4 ncFogFS(FullOut in [[stage_in]], constant NCFrame& F [[buffer(2)]],
                        const device float4* lights [[buffer(4)]], const device uint* cells [[buffer(5)]],
                        const device ushort* indices [[buffer(6)]], depth2d<float> search [[texture(0)]],
                        depth2d<float> depth [[texture(2)]]) {
    Grid g = { lights, cells, indices, search, F };
    NoRays tr;
    return fogMarch(in, F, g, depth, tr);
}

fragment float4 ncFogRTFS(FullOut in [[stage_in]], constant NCFrame& F [[buffer(2)]],
                          const device float4* lights [[buffer(4)]], const device uint* cells [[buffer(5)]],
                          const device ushort* indices [[buffer(6)]], instance_acceleration_structure tlas [[buffer(7)]],
                          const device NCMeshRef* meshes [[buffer(8)]], const device NCRTInstance* instances [[buffer(9)]],
                          depth2d<float> search [[texture(0)]], depth2d<float> depth [[texture(2)]]) {
    Grid g = { lights, cells, indices, search, F };
    Rays tr = { tlas, meshes, instances };
    return fogMarch(in, F, g, depth, tr);
}

fragment float4 ncCombineFS(FullOut in [[stage_in]], texture2d<float> scene [[texture(0)]], texture2d<float> fog [[texture(1)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    float4 f = fog.sample(linear, in.uv);
    return float4(scene.sample(linear, in.uv).rgb * f.a + f.rgb, 1.0);
}

struct NCPost { float2 texel; float2 step; float weight; };

/** 13-tap downsample; THRESHOLD keeps highlights only (first level). */
fragment float4 ncDownFS(FullOut in [[stage_in]], texture2d<float> src [[texture(0)]], constant NCPost& P [[buffer(0)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    float2 t = P.texel, uv = in.uv;
    float3 a = src.sample(linear, uv + t * float2(-2, -2)).rgb, b = src.sample(linear, uv + t * float2(0, -2)).rgb;
    float3 c = src.sample(linear, uv + t * float2(2, -2)).rgb, d = src.sample(linear, uv + t * float2(-1, -1)).rgb;
    float3 e = src.sample(linear, uv).rgb, f = src.sample(linear, uv + t * float2(1, -1)).rgb;
    float3 g = src.sample(linear, uv + t * float2(-2, 0)).rgb, h = src.sample(linear, uv + t * float2(2, 0)).rgb;
    float3 i = src.sample(linear, uv + t * float2(-1, 1)).rgb, j = src.sample(linear, uv + t * float2(1, 1)).rgb;
    float3 k = src.sample(linear, uv + t * float2(-2, 2)).rgb, l = src.sample(linear, uv + t * float2(0, 2)).rgb;
    float3 m = src.sample(linear, uv + t * float2(2, 2)).rgb;
    float3 s = (d + f + i + j) * 0.125 + (a + c + k + m) * 0.03125 + (b + g + h + l) * 0.0625 + e * 0.125;
    if (THRESHOLD) {
        float3 x = safeHdr(s);
        float br = max(max(x.r, x.g), x.b);
        float soft = clamp(br - 0.6, 0.0, 1.2);
        soft = soft * soft / 4.8;
        s = x * (max(soft, br - 1.2) / max(br, 1e-4));
    }
    return float4(s, 1.0);
}

/** 9-tap tent upsample, blended additively onto the next larger level. */
fragment float4 ncUpFS(FullOut in [[stage_in]], texture2d<float> src [[texture(0)]], constant NCPost& P [[buffer(0)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    float2 t = P.texel, uv = in.uv;
    float3 s = src.sample(linear, uv).rgb * 4.0
             + (src.sample(linear, uv + float2(t.x, 0.0)).rgb + src.sample(linear, uv - float2(t.x, 0.0)).rgb
              + src.sample(linear, uv + float2(0.0, t.y)).rgb + src.sample(linear, uv - float2(0.0, t.y)).rgb) * 2.0
             + src.sample(linear, uv + t).rgb + src.sample(linear, uv - t).rgb
             + src.sample(linear, uv + float2(t.x, -t.y)).rgb + src.sample(linear, uv + float2(-t.x, t.y)).rgb;
    return float4(s * (P.weight / 16.0), 1.0);
}

/** Horizontal 9-tap blur with a growing stride: anamorphic streaks off bright lights. */
fragment float4 ncStreakFS(FullOut in [[stage_in]], texture2d<float> src [[texture(0)]], constant NCPost& P [[buffer(0)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    float3 s = float3(0.0);
    float wsum = 0.0;
    for (int i = -4; i <= 4; i++) {
        float w = exp(-float(i * i) * 0.12);
        s += src.sample(linear, in.uv + P.step * float(i)).rgb * w;
        wsum += w;
    }
    return float4(s / wsum, 1.0);
}

static float3 aces(float3 x) { return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0); }

fragment float4 ncFinalFS(FullOut in [[stage_in]], constant NCFrame& F [[buffer(2)]], texture2d<float> hdr [[texture(0)]],
                          texture2d<float> bloom [[texture(1)]], texture2d<float> streak [[texture(3)]],
                          depth2d<float> depth [[texture(2)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    constexpr sampler nearest(filter::nearest, address::clamp_to_edge);
    float2 uv = in.uv;
    // camera motion blur: reproject this pixel with last frame's camera
    float d = depth.sample(nearest, uv);
    float4 wp = F.invViewProj * float4(in.ndc, d, 1.0);
    float4 pp = F.prevViewProj * float4(wp.xyz / wp.w, 1.0);
    float2 prevNdc = pp.xy / pp.w;
    float2 prev = float2(prevNdc.x * 0.5 + 0.5, 0.5 - prevNdc.y * 0.5);
    float2 vel = clamp(uv - prev, float2(-0.03), float2(0.03)) * 0.5;
    float jit = hash12(in.position.xy + F.time);
    float3 c = float3(0.0);
    for (int i = 0; i < F.blurSamples; i++) {
        float o = (float(i) + jit) / float(F.blurSamples) - 0.5;
        c += hdr.sample(linear, uv + vel * o).rgb;
    }
    c /= float(F.blurSamples);
    // a touch of lateral colour fringing toward the frame edges
    float2 ca = (uv - 0.5) * 0.0035;
    c.r = mix(c.r, hdr.sample(linear, uv + ca).r, 0.6);
    c.b = mix(c.b, hdr.sample(linear, uv - ca).b, 0.6);
    c += bloom.sample(linear, uv).rgb * 0.16 + streak.sample(linear, uv).rgb * float3(0.04, 0.05, 0.09);
    c = aces(c * F.exposure);
    c = pow(c, float3(1.0 / 2.2));
    float2 v = uv - 0.5;
    c *= 1.0 - dot(v, v) * 0.7;
    c = mix(float3(dot(c, float3(0.299, 0.587, 0.114))), c, 1.12);
    c += (hash12(uv * 1733.0 + F.time) - 0.5) * 0.018;
    return float4(c, 1.0);
}
