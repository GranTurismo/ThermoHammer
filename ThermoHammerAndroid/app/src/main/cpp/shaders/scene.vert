#version 460
// Buildings, props, cars, people, trains — ported from NeonCityShaders.OBJECT_VS.
#include "common.glsl"

layout(location = 0) in vec3 aPos;
layout(location = 1) in vec4 aNormal;    // normal + animation channel / 127
layout(location = 2) in vec4 aColor;     // sRGB colour (or parameters) + class / 255
layout(location = 3) in vec4 aRow0;      // instance transform rows
layout(location = 4) in vec4 aRow1;
layout(location = 5) in vec4 aRow2;
layout(location = 6) in vec4 aExtra0;    // car: paint + brake · person: umbrella + coat shade
layout(location = 7) in vec4 aExtra1;    // person: walk phase, swing amplitude

layout(push_constant) uniform Push { int actor; int instanced; float seed; int pad; } pc;

layout(location = 0) out vec3 vWorld;
layout(location = 1) out vec3 vNormal;
layout(location = 2) out vec3 vColor;
layout(location = 3) out vec4 vExtra;
layout(location = 4) out vec3 vRoom;
layout(location = 5) out vec3 vRoomView;
layout(location = 6) flat out int vClass;
layout(location = 7) flat out int vChan;
layout(location = 8) flat out float vRand;

void main() {
    int chan = int(aNormal.w * 127.0 + 0.5);
    vec3 p = aPos;
    vec3 n = aNormal.xyz;
    if (pc.actor == 2) {
        int part = chan & 31;
        float swing = aExtra1.y * sin(aExtra1.x);
        float ang = part == 1 ? swing : part == 2 ? -swing : part == 3 ? -0.8 * swing : part == 4 ? 0.8 * swing : 0.0;
        if (ang != 0.0) {
            float pivot = part <= 2 ? 0.93 : 1.41;
            float c = cos(ang);
            float s = sin(ang);
            vec2 yz = vec2(p.y, p.z - pivot);
            p.yz = vec2(c * yz.x - s * yz.y, s * yz.x + c * yz.y + pivot);
            n.yz = vec2(c * n.y - s * n.z, s * n.y + c * n.z);
        }
    }
    vec4 hp = vec4(p, 1.0);
    vec3 w = vec3(dot(aRow0, hp), dot(aRow1, hp), dot(aRow2, hp));
    vWorld = w;
    gl_Position = F.viewProj * vec4(w, 1.0);
    vec3 nw = normalize(vec3(dot(aRow0.xyz, n), dot(aRow1.xyz, n), dot(aRow2.xyz, n)));
    int cls = int(aColor.a * 255.0 + 0.5);
    vNormal = nw;
    vClass = cls;
    vChan = chan;
    vColor = cls == C_SCREEN ? aColor.rgb : pow(aColor.rgb, vec3(2.2));
    vExtra = aExtra0;
    vec3 origin = vec3(aRow0.w, aRow1.w, aRow2.w);
    vRand = hash13(origin * 0.37 + pc.seed);
    vec3 O = normalize(vec3(nw.xy, 0.0) + vec3(1e-5, 0.0, 0.0));
    vec3 T = vec3(O.y, -O.x, 0.0);
    vec3 d = w - origin;
    vRoom = vec3(dot(d, T), dot(d, O), d.z);
    vec3 vv = w - uCamPos;
    vRoomView = vec3(dot(vv, T), dot(vv, O), vv.z);
}
