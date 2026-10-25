#version 460
layout(location = 0) in vec2 vQ;
layout(location = 1) in vec3 vLight;
layout(location = 0) out vec4 oColor;
void main() {
    float a = (1.0 - abs(vQ.x)) * smoothstep(0.0, 0.3, vQ.y) * smoothstep(1.0, 0.6, vQ.y);
    oColor = vec4(vLight * a, 0.0);
}
