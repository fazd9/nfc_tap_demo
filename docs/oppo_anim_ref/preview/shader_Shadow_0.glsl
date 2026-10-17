// VERTEX
#version 300 es
in vec4 a_position;
in vec2 a_texCoord;
out vec2 v_texCoord;
uniform mat4 u_projection;
uniform mat4 u_view;
uniform mat4 u_model;
void main() {
    gl_Position = a_position;
    v_texCoord = a_texCoord;
}


// FRAGMENT
#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;

// @Vec2(iResolution,0.1)=[1080,2400]
uniform vec2 u_resolution;

// @Texture(shadowTex)=[1,1,0,0]
uniform sampler2D shadowTex;

// @Range(CardScale, 0.01, 2.0, 0.01)=1.0
uniform float CardScale;
// @Range(XPosition, -0.2, 1.5, 0.01)=0.4
uniform float XPosition;
// @Range(YPosition, -0.2, 1.5, 0.01)=0.4
uniform float YPosition;
// @Range(CradRotate, 0.0, 360.0, 90.0)=0.0
uniform float CradRotate;

// @Range(aspect, 0.01, 2.0, 0.01)=1.0
uniform float aspect;    // 纵横比
// @Range(alpha, 0.00, 1.0, 0.01)=1.0
uniform float alpha;

in vec2 v_texCoord;
out vec4 fragColor;

const float PI = 3.1415926;

// 有效范围
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
    moveUv+=vec2(0.5);
    return moveUv;
}
vec4 getlayerColor(sampler2D tex,vec2 uv){
    vec4 color = texture(tex, uv);
    color.a*=  getUVValid(uv);
    return color;
}
mat2 rotate2d(float angle) {
    float radian = PI * angle / 180.0;
    return mat2(cos(radian), -sin(radian),
                sin(radian), cos(radian));
}
void main() {
    vec2 UV = uvMove(CardScale); 
    vec2 centeredUV = UV - vec2(0.5);
    vec2 rotatedUV = rotate2d(CradRotate) * centeredUV;
    rotatedUV += vec2(0.5);

    // vec4 color = texture(bgTex, rotate2d(CradRotate) * UV);
    // vec4 color = texture(shadowTex, rotatedUV);
    vec4 color = getlayerColor(shadowTex, rotatedUV);
    // color.a *= getUVValid(UV);

    color.a*=alpha;
    fragColor = color;
    fragColor.rgb *= fragColor.a;
}

