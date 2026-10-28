// Copies a finished benchmark frame to the stage's view (not part of any measurement): scales it into a small target.
Texture2D<float4> frameTex : register(t0);
SamplerState linearClamp : register(s0);

struct PresentOut { float4 position : SV_Position; float2 uv : TEXCOORD0; };

PresentOut presentVS(uint vid : SV_VertexID)
{
    float2 p = float2(float((vid << 1) & 2), float(vid & 2));
    PresentOut o;
    o.position = float4(p * 2.0 - 1.0, 0.0, 1.0);
    o.uv = float2(p.x, 1.0 - p.y);
    return o;
}

float4 presentFS(PresentOut i) : SV_Target { return float4(frameTex.SampleLevel(linearClamp, i.uv, 0).rgb, 1.0); }
