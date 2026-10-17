// VERTEX
#version 320 es
precision highp float;

layout (location = 0) in vec4 position;         // xyz=位置, w=大小
layout (location = 1) in vec4 acceleration;     // xyz: 加速度, w: 预留
layout (location = 2) in vec4 speed;            // xyz: 移动速度 w: 随机种子
layout (location = 3) in vec4 color;            // RGBA颜色
layout (location = 4) in vec4 lifeTime;         // x: 当前生命值, y: 整个生命周期, z: 一直存在, w: color alpha random


// @Vec3(viewPositionOffset, 0.01)=[0.0,0.0,0.0]
uniform vec3 viewPositionOffset;

out VS_OUT
{
    vec4 position;
    float pointSize;
    vec4 outColor;
    vec4 lifeTime;
}vs_out;

void main() {
    gl_Position = vec4(vec3(position.xyz)+viewPositionOffset, 1.0);
    gl_PointSize = position.w;
    vs_out.position = gl_Position;
    vs_out.pointSize = gl_PointSize;
    vs_out.outColor = color;
    vs_out.lifeTime = lifeTime;
}
// FRAGMENT
#version 320 es
precision highp float;

in VS_OUT
{
    vec4 position;
    float pointSize;
    vec4 outColor;
    vec4 lifeTime;
}vs_in;

out vec4 FragColor;

// @Range(particleAlpha, 0.0, 1.0, 0.01)=1.0
uniform float particleAlpha;

// @Vec2(fadeinTime, 0.1) = [0.8, 1.0]
uniform vec2 fadeinTime;

// @Vec2(fadeoutTime, 0.1) = [0.0, 0.5]
uniform vec2 fadeoutTime;

// @Vec2(showRange, 0.1) = [1.0, 1.0]
uniform vec2 showRange;

void main() {
    FragColor = vs_in.outColor;
    FragColor.a *= ((1.0 - smoothstep(showRange.x, showRange.y, vs_in.position.x)) * smoothstep(-showRange.y, -showRange.x, vs_in.position.x));
    FragColor.a *= smoothstep(fadeoutTime.x, fadeoutTime.y, vs_in.lifeTime.x / vs_in.lifeTime.y);               // 淡出
    FragColor.a *= (1.0 - smoothstep(fadeinTime.x, fadeinTime.y, vs_in.lifeTime.x / vs_in.lifeTime.y));       // 淡入

    // 计算当前片段在点范围内的坐标（归一化为[-1,1]）
    vec2 coord = gl_PointCoord * 2.0 - 1.0;
    // 计算到中心的距离
    float distance = length(coord);
    // 裁剪超出半径的像素
    if (distance > 1.0) {
        discard;
    }
    float alpha = smoothstep(1.0, 0.9, distance);
    FragColor.a *= alpha;
    FragColor.a *= particleAlpha;
}
