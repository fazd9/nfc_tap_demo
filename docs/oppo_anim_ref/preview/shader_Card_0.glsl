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
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;

// @Vec2(iResolution,0.1)=[1080,2400]
uniform vec2 u_resolution;

// @Texture(pileTex, noExport)=[1,1,0,0]
uniform sampler2D pileTex;
// @Range(pileEnable, 0.0, 1.0, 1)=0.0
uniform float pileEnable;
// @Range(pileScale, 1.0, 2.0, 0.01)=1.0
uniform float pileScale;
// @Texture(bgTex, noExport)=[1,1,0,0]
uniform samplerExternalOES bgTex;

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

// 绕X轴旋转（上下翻转，正值顶部朝向屏幕），单位：度
// @Range(RotateX, -180.0, 180.0, 1.0)=0.0
uniform float RotateX;
// 绕Y轴旋转（左右翻转，正值右侧远离屏幕），单位：度
// @Range(RotateY, -180.0, 180.0, 1.0)=0.0
uniform float RotateY;
// 透视强度：相机到卡片的距离（UV单位），值越大透视效果越弱
// @Range(Perspective, 0.5, 20.0, 0.5)=5.0
uniform float Perspective;
// 旋转轴支点（卡片UV居中坐标：-0.5=左/底边, 0=中心, 0.5=右/顶边）
// @Range(PivotX, -0.5, 0.5, 0.01)=0.0
uniform float PivotX;
// @Range(PivotY, -0.5, 0.5, 0.01)=0.0
uniform float PivotY;

in vec2 v_texCoord;
out vec4 fragColor;

const float PI = 3.1415926;

// 有效范围
float getUVValid(vec2 uv) {
    if(all(bvec4(greaterThan(uv, vec2(0.0)), greaterThan(vec2(1.0), uv)))) {
        return 1.0;
    } else {
        return 0.0;
    }
}

vec2 uvMove(float scal) {
    vec2 moveUv = gl_FragCoord.xy - vec2(u_resolution.x*XPosition, u_resolution.y*YPosition);
    moveUv /= scal;
    moveUv /= vec2(u_resolution.x, u_resolution.x*aspect);
    moveUv += vec2(0.5);
    return moveUv;
}

vec4 getlayerColor(sampler2D tex, vec2 uv) {
    vec4 color = texture(tex, uv);
    color.a *= getUVValid(uv);
    return color;
}

mat2 rotate2d(float angle) {
    float radian = PI * angle / 180.0;
    return mat2(cos(radian), -sin(radian),
                sin(radian), cos(radian));
}

// ---------------------------------------------------------------
// 3D透视旋转逆映射（支持任意支点 pivot）
//
// 旋转顺序：R = Rx(rotX) * Ry(rotY) * Rz(rotZ)
// 旋转中心：pivot（卡片UV居中坐标，如 (0,-0.5) = 底边）
//
// 推导：
//   世界坐标 = R*(cardPos - pivot) + pivot
//   令 (p,q) = Rz*(dx,dy)，dx=x-px, dy=y-py
//   透视方程：
//     sx = (Rx*Ry*(p,q).x + px) * d / (d - Rx*Ry*(p,q).z)
//     sy = (Rx*Ry*(p,q).y + py) * d / (d - Rx*Ry*(p,q).z)
//   整理后，方程组右侧变为 (sx-px)*d 和 (sy-py)*d，
//   系数矩阵 A1/B1/A2/B2 保持不变，再逆 Rz 并加回 pivot。
// ---------------------------------------------------------------
vec2 applyPerspective3D(vec2 screenCentered, float rotX, float rotY, float rotZ, float d, vec2 pivot) {
    float cosA = cos(rotX); // Rx
    float sinA = sin(rotX);
    float cosB = cos(rotY); // Ry
    float sinB = sin(rotY);

    // 背面剔除：法线Z分量 = cosA*cosB，≤0 表示背面朝向相机
    if (cosA * cosB <= 0.0) return vec2(-10.0);

    float sx = screenCentered.x;
    float sy = screenCentered.y;
    float px = pivot.x;
    float py = pivot.y;

    // 解2x2线性方程组，RHS 相对于 pivot 偏移
    float A1 = cosB * d - sx * cosA * sinB;
    float B1 = sx * sinA;
    float A2 = sinA * sinB * d - sy * cosA * sinB;
    float B2 = cosA * d + sy * sinA;
    float det = A1 * B2 - A2 * B1;

    if (abs(det) < 1e-5) return vec2(-10.0);

    // 关键改动：右侧用 (sx-px) 和 (sy-py)
    float p = d * ((sx - px) * B2 - (sy - py) * B1) / det;
    float q = d * (A1 * (sy - py) - A2 * (sx - px)) / det;

    // 逆 Rz 旋转，并加回 pivot
    float cosZ = cos(rotZ);
    float sinZ = sin(rotZ);
    float card_x = p * cosZ - q * sinZ + px;
    float card_y = p * sinZ + q * cosZ + py;

    return vec2(card_x, card_y);
}

void main() {
    float d    = max(Perspective, 0.01);
    float rotX = PI * RotateX   / 180.0;
    float rotY = PI * RotateY   / 180.0;
    float rotZ = PI * -CradRotate / 180.0;
    vec2  pivot = vec2(PivotX, PivotY);

    // 主卡片纹理
    vec2 centeredUV  = uvMove(CardScale) - vec2(0.5);
    vec2 cardCentered = applyPerspective3D(centeredUV, rotX, rotY, rotZ, d, pivot);
    vec2 texUV        = cardCentered + vec2(0.5);

    vec4 color = texture(bgTex, texUV);
    color.a *= getUVValid(texUV);

    // 叠底纹理（pileEnable）
    if (pileEnable > 0.5) {
        vec2 pileCentered = applyPerspective3D(
            uvMove(CardScale * pileScale) - vec2(0.5),
            rotX, rotY, rotZ, d, pivot
        );
        vec2 pileTexUV = pileCentered + vec2(0.5);
        color = mix(getlayerColor(pileTex, pileTexUV), color, color.a);
    }

    color.a *= alpha;
    fragColor = color;
}

