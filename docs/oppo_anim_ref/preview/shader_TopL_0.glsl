// VERTEX
#version 300 es
in vec4 a_position;
in vec2 a_texCoord;
out vec2 v_texCoord;
void main() {
    gl_Position = a_position;
    v_texCoord = a_texCoord;
}


// FRAGMENT
#version 300 es
precision highp float;
in vec2 v_texCoord;
out vec4 fragColor;
// @Vec2(iResolution,0.1)=[328,712]
uniform vec2 u_resolution;
// @Range(iTime,0,100,0.01)=0.1
uniform float u_time;
// @Range(black_alpha,0,1,0.01)=0.66
uniform float black_alpha;

void main() {
    fragColor = vec4(0., 0., 0., black_alpha*1.143);
}


