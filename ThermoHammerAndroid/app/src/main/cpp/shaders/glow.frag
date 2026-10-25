#version 460
layout(location = 0) in vec2 vQ;
layout(location = 1) in vec3 vCol;
layout(location = 0) out vec4 oColor;
void main() {
    float r2 = dot(vQ, vQ);
    float a = exp(-r2 * 4.0) + 0.6 * exp(-r2 * 30.0);
    oColor = vec4(vCol * a, 0.0);
}
