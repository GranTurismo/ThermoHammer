// VIDEO EXPORT (UX Test 6) — the edit every exported frame gets, in Direct3D 11 (SM 5.0): the phones' Core Image / OpenGL
// chain — saturation and contrast, a vignette, a dip to black where the clip loops, an opaque lower third (title card
// 1600×240) with a timecode drawn glyph by glyph (the eleven characters of 00:00:00:00). Texture coordinates are top-left.

cbuffer ExportParams : register(b0)
{
    float2 size;           // frame size, px
    float fade;            // 0.25 … 1: the dip where the clip loops
    float pad0;
    float4 box;            // the lower third in UV: x, y, w, h
    int4 digits[3];        // the 11 glyphs (0-9, 10 = ':'), four to a register
};

Texture2D<float4> frameTex : register(t0);    // the decoded frame as BGRA8
Texture2D<float4> titleTex : register(t1);    // the title card, premultiplied
Texture2D<float4> glyphTex : register(t2);    // 11 glyph cells side by side, premultiplied
SamplerState linearClamp : register(s0);

struct ExportOut { float4 position : SV_Position; float2 uv : TEXCOORD0; };

ExportOut exportVS(uint vid : SV_VertexID)
{
    float2 p = float2(float((vid << 1) & 2), float(vid & 2));
    ExportOut o;
    o.position = float4(p * 2.0 - 1.0, 0.0, 1.0);
    o.uv = float2(p.x, 1.0 - p.y);
    return o;
}

float4 exportEditFS(ExportOut i) : SV_Target
{
    float3 c = frameTex.SampleLevel(linearClamp, i.uv, 0).rgb;
    float l = dot(c, float3(0.2126, 0.7152, 0.0722));
    c = lerp(float3(l, l, l), c, 1.18);                       // saturation
    c = (c - 0.5) * 1.08 + 0.5;                               // contrast
    float2 d = (i.uv - 0.5) * size;
    float r = length(d) / (0.62 * size.x);                    // vignette: radius 0.62 of the width
    float v = saturate((r - 0.35) / 0.65);
    c *= 1.0 - 0.55 * v * v * (3.0 - 2.0 * v);
    c *= fade;
    float2 t = (i.uv - box.xy) / box.zw;                      // title card
    if (t.x >= 0.0 && t.x <= 1.0 && t.y >= 0.0 && t.y <= 1.0)
    {
        float4 card = titleTex.SampleLevel(linearClamp, t, 0);
        c = c * (1.0 - card.a) + card.rgb;
    }
    // timecode: glyph i at x = box.x + (0.13 + 0.04 i) w, 0.4 h tall, from 0.55 h below the box's top
    float gx = (i.uv.x - (box.x + 0.13 * box.z)) / (0.04 * box.z);
    float gy = (i.uv.y - (box.y + 0.55 * box.w)) / (0.40 * box.w);
    if (gx >= 0.0 && gx < 11.0 && gy >= 0.0 && gy <= 1.0)
    {
        int k = (int)floor(gx);
        int glyph = digits[k >> 2][k & 3];
        float4 g = glyphTex.SampleLevel(linearClamp, float2((float(glyph) + frac(gx)) / 11.0, gy), 0);
        c = c * (1.0 - g.a) + g.rgb;
    }
    return float4(saturate(c), 1.0);
}
