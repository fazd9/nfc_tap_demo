// VERTEX
#version 300 es
in vec4 a_position;
in vec2 a_texCoord;
out vec2 v_texCoord;
uniform mat4 u_projection;
uniform mat4 u_view;
uniform mat4 u_model;
// @Vec2(iResolution,0.1)=[100,100]
uniform float iResolution;
void main() {
    // gl_Position = u_projection * u_view  * u_model *  a_position;
    gl_Position = a_position;
    v_texCoord = a_texCoord;
}


// FRAGMENT
#version 300 es
precision highp float;
// @Texture(tex_light)=[1,1,0,0]
uniform sampler2D tex_light;
// @Vec2(iResolution,0.1)=[1080,2400]
uniform vec2 u_resolution;
// @Range(aspect, 0.01, 3.0, 0.01)=1.0
uniform float aspect;    // 纵横比
// @Range(CardScale, 0.01, 4.0, 0.01)=1.0
uniform float CardScale;
// @Range(XPosition, -0.2, 1.5, 0.01)=0.4
uniform float XPosition;
// @Range(YPosition, -0.2, 1.5, 0.01)=0.4
uniform float YPosition;
// @Range(lightAlpha, 0.0, 1.0, 0.01)=1.0
uniform float lightAlpha;

in vec2 v_texCoord;
out vec4 fragColor;

float getUVValid(vec2 uv) {
    if(all(bvec4( greaterThan(uv,vec2(0.0)), greaterThan(vec2(1.0),uv)))){
        return 1.0;
    }else{
        return 0.0;
    }
}

vec2 uvMove(float scal) {
    vec2 moveUv = gl_FragCoord.xy-vec2(u_resolution.x*XPosition,u_resolution.y*YPosition);
    moveUv/=scal;
    moveUv/=vec2(u_resolution.x,u_resolution.x*aspect);
    moveUv+=vec2(0.5,0.5);
    return moveUv;
}

void main() {
    vec2 UV = uvMove(CardScale); 
    vec4 color = texture(tex_light, UV);
    color.a *= getUVValid(UV);
    color.a *= lightAlpha;
    fragColor = color;
    // vec4 light = texture(tex_light, v_texCoord);
    // fragColor = light;
}

