// PHOTO EDITING (UX Test 4) — HLSL port (Direct3D 11, SM 5.0) of the iPhone app's Metal shaders (PhotoEdit.metal) and the
// Android GLSL. Textures stay in image orientation (v = 0 is the top row), which in Direct3D is also the render targets'.

cbuffer PhotoParams : register(b0)
{
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

cbuffer BlurTable : register(b1)
{
    float4 blurOffV[9];   // 33 offsets and weights, four to a register
    float4 blurWV[9];
};

Texture2D<float4> srcTex : register(t0);
Texture2D<float4> softTex : register(t1);
Texture3D<float4> lutTex : register(t2);
SamplerState linearClamp : register(s0);

struct PhotoOut { float4 position : SV_Position; float2 uv : TEXCOORD0; };

PhotoOut photoVS(uint vid : SV_VertexID)
{
    float2 p = float2(float((vid << 1) & 2), float(vid & 2));
    PhotoOut o;
    o.position = float4(p * 2.0 - 1.0, 0.0, 1.0);
    o.uv = float2(p.x, 1.0 - p.y);
    return o;
}

float3 bicubic(float2 uv)
{
    float2 p = uv * size - 0.5;
    float2 f = frac(p);
    int2 i = int2(floor(p));
    float4 wx = float4(f.x * (-0.5 + f.x * (1.0 - 0.5 * f.x)), 1.0 + f.x * f.x * (-2.5 + 1.5 * f.x),
                       f.x * (0.5 + f.x * (2.0 - 1.5 * f.x)), f.x * f.x * (-0.5 + 0.5 * f.x));
    float4 wy = float4(f.y * (-0.5 + f.y * (1.0 - 0.5 * f.y)), 1.0 + f.y * f.y * (-2.5 + 1.5 * f.y),
                       f.y * (0.5 + f.y * (2.0 - 1.5 * f.y)), f.y * f.y * (-0.5 + 0.5 * f.y));
    int2 hi = int2(size) - 1;
    float3 c = float3(0.0, 0.0, 0.0);
    for (int y = 0; y < 4; y++)
    {
        float3 row = float3(0.0, 0.0, 0.0);
        for (int x = 0; x < 4; x++) row += srcTex.Load(int3(clamp(i + int2(x - 1, y - 1), int2(0, 0), hi), 0)).rgb * wx[x];
        c += row * wy[y];
    }
    return c;
}

/** Fisheye: magnifies the centre, squeezes the edges; red and blue land a little apart (lateral CA). */
float4 photoLensFS(PhotoOut i) : SV_Target
{
    float aspect = size.x / size.y;
    float2 c = (i.uv - 0.5) * float2(aspect, 1.0);
    float r2 = dot(c, c) / (0.25 * (aspect * aspect + 1.0));     // 1 at the corners
    float zoom = 1.0 + k * 0.8;                                  // the centre magnified, only the corners past the edge
    float2 d = c * (1.0 + k * r2) / zoom;
    float2 uvG = d / float2(aspect, 1.0) + 0.5;
    float2 uvR = (d * 1.004) / float2(aspect, 1.0) + 0.5;
    float2 uvB = (d * 0.996) / float2(aspect, 1.0) + 0.5;
    float3 col = bicubic(uvG);
    col.r = srcTex.SampleLevel(linearClamp, uvR, 0).r;
    col.b = srcTex.SampleLevel(linearClamp, uvB, 0).b;
    float inside = step(0.0, uvG.x) * step(uvG.x, 1.0) * step(0.0, uvG.y) * step(uvG.y, 1.0);
    return float4(clamp(col, 0.0, 1.0) * inside, 1.0);
}

/** Separable Gaussian, 129 texels: the centre + 32 bilinear pairs a side. */
float4 photoBlurFS(PhotoOut i) : SV_Target
{
    float3 s = srcTex.SampleLevel(linearClamp, i.uv, 0).rgb * blurWV[0].x;
    for (int n = 1; n < 33; n++)
    {
        float2 d = dir * blurOffV[n >> 2][n & 3];
        s += (srcTex.SampleLevel(linearClamp, i.uv + d, 0).rgb + srcTex.SampleLevel(linearClamp, i.uv - d, 0).rgb) * blurWV[n >> 2][n & 3];
    }
    return float4(s, 1.0);
}

/** Portrait mode: sharp inside the focus band, a highlight-blooming disc blur (of the pre-blurred photo) outside it. */
float4 photoBokehFS(PhotoOut i) : SV_Target
{
    float3 sharp = srcTex.SampleLevel(linearClamp, i.uv, 0).rgb;
    float t = saturate((abs(i.uv.y - focusY) - 0.05) / 0.25);
    float coc = t * t * (3.0 - 2.0 * t);
    float3 acc = float3(0.0, 0.0, 0.0);
    float wsum = 0.0;
    for (int n = 0; n < 48; n++)
    {
        float r = sqrt((float(n) + 0.5) / 48.0);
        float a = float(n) * 2.39996323;
        float3 c = softTex.SampleLevel(linearClamp, i.uv + float2(cos(a), sin(a)) * (r * radius * coc) * texel, 0).rgb;
        float l = dot(c, float3(0.299, 0.587, 0.114));
        float wt = 1.0 + 8.0 * l * l * l * l;
        acc += c * wt;
        wsum += wt;
    }
    return float4(lerp(sharp, acc / wsum, coc), 1.0);
}

float grainHash(float2 p) { return frac(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453); }

float4 photoFinishFS(PhotoOut i) : SV_Target
{
    float3 c = srcTex.SampleLevel(linearClamp, i.uv, 0).rgb;
    float3 s = softTex.SampleLevel(linearClamp, i.uv, 0).rgb;
    c = clamp(c + amount * (c - s), 0.0, 1.0);                          // unsharp mask
    float3 graded = lutTex.SampleLevel(linearClamp, c * (32.0 / 33.0) + 0.5 / 33.0, 0).rgb;
    c = lerp(c, graded, grade);
    float2 v = i.uv - 0.5;
    c *= 1.0 - dot(v, v) * 0.9;                                         // vignette
    c += (grainHash(i.uv * size + time) - 0.5) * 0.03;                  // grain
    return float4(clamp(c, 0.0, 1.0), 1.0);
}

float4 photoShowFS(PhotoOut i) : SV_Target
{
    return float4(srcTex.SampleLevel(linearClamp, i.uv, 0).rgb, 1.0);
}
