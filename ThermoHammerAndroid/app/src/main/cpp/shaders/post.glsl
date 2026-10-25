// Bindings of the compute post chain (set 1) and its push block.
#ifndef NRT_POST
#define NRT_POST
layout(set = 1, binding = 0) uniform sampler2D srcA;
layout(set = 1, binding = 1) uniform sampler2D srcB;
layout(push_constant) uniform PostPush { vec2 texel; float p0; float p1; } pp;   // texel = 1 / source size
#endif
