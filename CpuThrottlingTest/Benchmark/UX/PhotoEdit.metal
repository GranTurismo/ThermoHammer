// PHOTO EDITING (UX Test 4) — Metal port of the Android app's GLSL (engine/gpu/PhotoEditScene.kt). Textures stay
// in image orientation (v = 0 is the top row), which in Metal is also the render targets' orientation.
#include <metal_stdlib>
using namespace metal;

struct PhotoParams {
    float2 size;          // photo size, px
    float2 dir;           // blur step (one texel along x or y)
    float2 texel;
    float k;              // lens strength
    float radius;         // bokeh radius, px
    float focusY;
    float amount;         // unsharp amount
    float grade;
    float time;
};

struct PhotoOut { float4 position [[position]]; float2 uv; };

vertex PhotoOut photoVS(uint vid [[vertex_id]]) {
    float2 p = float2(float((vid << 1) & 2), float(vid & 2));
    PhotoOut o;
    o.position = float4(p * 2.0 - 1.0, 0.0, 1.0);
    o.uv = float2(p.x, 1.0 - p.y);
    return o;
}

static float3 bicubic(texture2d<float> src, float2 uv, float2 size) {
    float2 p = uv * size - 0.5;
    float2 f = fract(p);
    int2 i = int2(floor(p));
    float4 wx = float4(f.x * (-0.5 + f.x * (1.0 - 0.5 * f.x)), 1.0 + f.x * f.x * (-2.5 + 1.5 * f.x),
                       f.x * (0.5 + f.x * (2.0 - 1.5 * f.x)), f.x * f.x * (-0.5 + 0.5 * f.x));
    float4 wy = float4(f.y * (-0.5 + f.y * (1.0 - 0.5 * f.y)), 1.0 + f.y * f.y * (-2.5 + 1.5 * f.y),
                       f.y * (0.5 + f.y * (2.0 - 1.5 * f.y)), f.y * f.y * (-0.5 + 0.5 * f.y));
    int2 hi = int2(size) - 1;
    float3 c = float3(0.0);
    for (int y = 0; y < 4; y++) {
        float3 row = float3(0.0);
        for (int x = 0; x < 4; x++) row += src.read(uint2(clamp(i + int2(x - 1, y - 1), int2(0), hi))).rgb * wx[x];
        c += row * wy[y];
    }
    return c;
}

/** Fisheye: magnifies the centre, squeezes the edges; red and blue land a little apart (lateral CA). */
fragment float4 photoLensFS(PhotoOut in [[stage_in]], texture2d<float> src [[texture(0)]], constant PhotoParams& P [[buffer(0)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    float aspect = P.size.x / P.size.y;
    float2 c = (in.uv - 0.5) * float2(aspect, 1.0);
    float r2 = dot(c, c) / (0.25 * (aspect * aspect + 1.0));     // 1 at the corners
    float zoom = 1.0 + P.k * 0.8;                                // the centre magnified, only the corners past the edge
    float2 d = c * (1.0 + P.k * r2) / zoom;
    float2 uvG = d / float2(aspect, 1.0) + 0.5;
    float2 uvR = (d * 1.004) / float2(aspect, 1.0) + 0.5;
    float2 uvB = (d * 0.996) / float2(aspect, 1.0) + 0.5;
    float3 col = bicubic(src, uvG, P.size);
    col.r = src.sample(linear, uvR).r;
    col.b = src.sample(linear, uvB).b;
    float inside = step(0.0, uvG.x) * step(uvG.x, 1.0) * step(0.0, uvG.y) * step(uvG.y, 1.0);
    return float4(clamp(col, 0.0, 1.0) * inside, 1.0);
}

/** Separable Gaussian, 129 texels: the centre + 32 bilinear pairs a side. */
fragment float4 photoBlurFS(PhotoOut in [[stage_in]], texture2d<float> src [[texture(0)]], constant PhotoParams& P [[buffer(0)]],
                            constant float* off [[buffer(1)]], constant float* w [[buffer(2)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    float3 s = src.sample(linear, in.uv).rgb * w[0];
    for (int i = 1; i < 33; i++) {
        float2 d = P.dir * off[i];
        s += (src.sample(linear, in.uv + d).rgb + src.sample(linear, in.uv - d).rgb) * w[i];
    }
    return float4(s, 1.0);
}

/** Portrait mode: sharp inside the focus band, a highlight-blooming disc blur (of the pre-blurred photo) outside it. */
fragment float4 photoBokehFS(PhotoOut in [[stage_in]], texture2d<float> sharpTex [[texture(0)]], texture2d<float> soft [[texture(1)]],
                             constant PhotoParams& P [[buffer(0)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    float3 sharp = sharpTex.sample(linear, in.uv).rgb;
    float coc = smoothstep(0.05, 0.30, abs(in.uv.y - P.focusY));
    float3 acc = float3(0.0);
    float wsum = 0.0;
    for (int i = 0; i < 48; i++) {
        float r = sqrt((float(i) + 0.5) / 48.0);
        float a = float(i) * 2.39996323;
        float3 c = soft.sample(linear, in.uv + float2(cos(a), sin(a)) * (r * P.radius * coc) * P.texel).rgb;
        float l = dot(c, float3(0.299, 0.587, 0.114));
        float wt = 1.0 + 8.0 * l * l * l * l;
        acc += c * wt;
        wsum += wt;
    }
    return float4(mix(sharp, acc / wsum, coc), 1.0);
}

static float grainHash(float2 p) { return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453); }

fragment float4 photoFinishFS(PhotoOut in [[stage_in]], texture2d<float> img [[texture(0)]], texture2d<float> soft [[texture(1)]],
                              texture3d<float> lut [[texture(2)]], constant PhotoParams& P [[buffer(0)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    float3 c = img.sample(linear, in.uv).rgb;
    float3 s = soft.sample(linear, in.uv).rgb;
    c = clamp(c + P.amount * (c - s), 0.0, 1.0);                       // unsharp mask
    float3 graded = lut.sample(linear, c * (32.0 / 33.0) + 0.5 / 33.0).rgb;
    c = mix(c, graded, P.grade);
    float2 v = in.uv - 0.5;
    c *= 1.0 - dot(v, v) * 0.9;                                        // vignette
    c += (grainHash(in.uv * P.size + P.time) - 0.5) * 0.03;            // grain
    return float4(clamp(c, 0.0, 1.0), 1.0);
}

fragment float4 photoShowFS(PhotoOut in [[stage_in]], texture2d<float> tex [[texture(0)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    return float4(tex.sample(linear, in.uv).rgb, 1.0);
}
