// The light grid (built by NeonLightGrid.kt) and ray-traced visibility.
// With RT, the strongest lights of every cell and the police searchlight are shadowed by a ray query
// against the whole city (cars, people and trains included); without RT everything is unshadowed.
#ifndef NRT_LIGHTS
#define NRT_LIGHTS

layout(std430, set = 0, binding = 1) readonly buffer LightBuf { vec4 lightData[]; };
layout(std430, set = 0, binding = 2) readonly buffer CellBuf { uint cellData[]; };
layout(std430, set = 0, binding = 3) readonly buffer LightIdxBuf { uint lightIdx[]; };
#ifdef RT
layout(set = 0, binding = 9) uniform accelerationStructureEXT tlas;
#endif

const vec2 GRID_ORIGIN = vec2(-256.0, -320.0);
const float GRID_CELL = 4.0;
const ivec2 GRID_SIZE = ivec2(128, 336);
const float LIGHT_SCALE = 70.0;
const int SHADOWED_LIGHTS = 2;      // per shaded point, besides the searchlight

// p's light list: (first index, count, how many of them lead as moving lights)
ivec3 cellRange(vec3 p) {
    ivec2 c = ivec2(floor((p.xy - GRID_ORIGIN) / GRID_CELL));
    if (c.x < 0 || c.y < 0 || c.x >= GRID_SIZE.x || c.y >= GRID_SIZE.y) return ivec3(0);
    uint v = cellData[c.y * GRID_SIZE.x + c.x];
    return ivec3(int(v >> 12u), int(v & 63u), int((v >> 6u) & 63u));
}

/** 1 if nothing blocks the segment from `from` to (just short of) the light at `to`. */
float traceShadow(vec3 from, vec3 to) {
#ifdef RT
    vec3 d = to - from;
    float len = length(d);
    if (len < 0.8) return 1.0;
    rayQueryEXT rq;
    rayQueryInitializeEXT(rq, tlas, gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsOpaqueEXT, 0xFF,
                          from, 0.02, d / len, len - 0.6);   // stop before the lamp's own housing
    while (rayQueryProceedEXT(rq)) {}
    return rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionNoneEXT ? 1.0 : 0.0;
#else
    return 1.0;
#endif
}

// Distance + cone falloff of light `id` at p; L = direction to it.
float lightAtten(uint id, vec3 p, out vec3 L, out vec3 lpos, out vec3 color, out float type) {
    vec4 l0 = lightData[id * 4u];
    vec4 l1 = lightData[id * 4u + 1u];
    lpos = l0.xyz;
    color = l1.rgb;
    type = l1.w;
    vec3 lv = l0.xyz - p;
    float d2 = dot(lv, lv);
    float r2 = l0.w * l0.w;
    L = vec3(0.0, 0.0, 1.0);
    if (d2 >= r2) return 0.0;
    L = lv * inversesqrt(d2);
    float f = d2 / r2;
    float att = (1.0 - f) * (1.0 - f) / (d2 + 1.0);
    if (type > 0.5) {
        vec4 l2 = lightData[id * 4u + 2u];
        att *= smoothstep(l2.w, mix(l2.w, 1.0, 0.45), dot(-L, l2.xyz));
    }
    return att * groupValueOf(int(lightData[id * 4u + 3u].x));
}

/** Light reflected toward v by the lights of p's cell (Lambert + GGX); `shadows` = cast shadow rays. */
vec3 shadeLights(vec3 p, vec3 n, vec3 v, vec3 diff, vec3 f0, float rough, int maxLights, bool shadows) {
    ivec3 r = cellRange(p);
    int cnt = min(r.y, maxLights);
    float a = max(rough * rough, 0.012);
    float a2 = a * a;
    float k = a * 0.5;
    float ndv = max(dot(n, v), 1e-3);
    float gv = ndv * (1.0 - k) + k;
    vec3 ps = p + n * 0.05;
    vec3 sum = vec3(0.0);
    int traced = 0;
    for (int i = 0; i < cnt; i++) {
        uint id = lightIdx[r.x + i];
        vec3 L, lpos, color;
        float type;
        float att = lightAtten(id, ps, L, lpos, color, type);
        float ndl = dot(n, L);
        if (att <= 0.0 || ndl <= 0.0) continue;
        if (shadows && (type > 1.5 || (traced < SHADOWED_LIGHTS && att * max(color.r, max(color.g, color.b)) > 0.002))) {
            att *= traceShadow(ps, lpos);
            traced++;
            if (att <= 0.0) continue;
        }
        vec3 h = normalize(L + v);
        float ndh = max(dot(n, h), 0.0);
        float dd = ndh * ndh * (a2 - 1.0) + 1.0;
        float D = a2 / (PI * dd * dd);
        float vis = 0.25 / ((ndl * (1.0 - k) + k) * gv);
        vec3 F = f0 + (1.0 - f0) * pow(1.0 - max(dot(L, h), 0.0), 5.0);
        sum += color * (att * ndl) * (diff * (1.0 / PI) + F * (D * vis));
    }
    return sum * LIGHT_SCALE;
}

float phaseHG(float c, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * PI * pow(1.0 + g2 - 2.0 * g * c, 1.5));
}

/** Light scattered toward the camera by mist at p; with RT the searchlight beam is shadowed (shafts). */
// movingOnly: just the cell's leading moving lights, so traffic never swaps static lights in and out
vec3 scatterLights(vec3 p, vec3 rd, int maxLights, bool shadows, bool movingOnly) {
    ivec3 r = cellRange(p);
    int cnt = min(movingOnly ? r.z : r.y, maxLights);
    vec3 sum = vec3(0.0);
    for (int i = 0; i < cnt; i++) {
        uint id = lightIdx[r.x + i];
        vec3 L, lpos, color;
        float type;
        float att = lightAtten(id, p, L, lpos, color, type);
        if (att <= 0.0) continue;
        if (shadows && type > 1.5) att *= traceShadow(p, lpos);
        sum += color * att * phaseHG(dot(L, rd), 0.35);
    }
    return sum * LIGHT_SCALE;
}

#endif
