// Copies a finished benchmark frame to the stage's view (not part of any measurement).
#include <metal_stdlib>
using namespace metal;

struct PresentOut { float4 position [[position]]; float2 uv; };

vertex PresentOut presentVS(uint vid [[vertex_id]]) {
    float2 p = float2(float((vid << 1) & 2), float(vid & 2));
    PresentOut o;
    o.position = float4(p * 2.0 - 1.0, 0.0, 1.0);
    o.uv = float2(p.x, 1.0 - p.y);
    return o;
}

fragment float4 presentFS(PresentOut in [[stage_in]], texture2d<float> frame [[texture(0)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    return float4(frame.sample(linear, in.uv).rgb, 1.0);
}
