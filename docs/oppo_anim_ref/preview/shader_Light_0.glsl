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
precision highp float;
in vec2 v_texCoord;
out vec4 fragColor;
// @Vec2(iResolution,0.1)=[328,712]
uniform vec2 u_resolution;
// @Range(iTime,0,100,0.01)=0.1
uniform float u_time;

// @Label(边缘光)
// @Range(BaseA,0.0,23.0,0.01)=15.1
uniform float BaseA;
// @Range(LA,0.0,23.0,0.01)=11.34
uniform float LA;
// @Range(RA,0.0,23.0,0.01)=18.11
uniform float RA;
// @Range(LOmega,0.0,20.0,0.01)=5.07
uniform float LOmega;
// @Range(ROmega,0.0,20.0,0.01)=3.43
uniform float ROmega;
// @Range(LPhi,-10.2,10.0,0.01)=2.07
uniform float LPhi;
// @Range(RPhi,-10.2,10.0,0.01)=-5.52
uniform float RPhi;
// @Range(LFlowSpeed,0.01,10,0.01)=2.98
uniform float roundSpeedL;
// @Range(RFlowSpeed,0.01,10,0.01)=1.62
uniform float roundSpeedR;
// @Range(WaveAlpha,0.2,1.0,0.01)=1.0
uniform float waveAlpha;
// @Range(Radius,0,500,0.01)=145.0
uniform float Radius;
// @Range(Glow,0.01,0.2,0.001)=0.028
uniform float glow;
// @Range(viewAlpha,0,1.0,0.01)=1.0
uniform float viewAlpha;
// @Label(光晕动画)
// @Range(CastProgress,0,2400,1)=1053
uniform float castProgress;
// @Range(CastFade,0,1000,1)=586
uniform float castFade;
// @Vec2(CastCenter,1)=[540,2350]
uniform vec2 castCenter;
// @Color(backColor,0.01)=[0.1,1,0.3,1]
uniform vec4 backColor;
// @Color(frontColor,0.01)=[0.1,1,0.3,1]
uniform vec4 frontColor;
// @Range(castColorProgress,0,2400,1)=1053
uniform float castColorProgress;
// @Range(CastColorFade,1,100,1)=86
uniform float castColorFade;
// @Range(Margin,1,1000,1)=586
uniform float margin;
// @Range(Padding,1,1000,1)=586
uniform float padding;
// @Range(ColorAlpha,0.00,1.0,0.01)=1.0
uniform float ColorAlpha;

// @Label(边缘光动画)
// @Range(edgeProgress,0,4000,1)=2400
uniform float edgeProgress;
// @Range(edgeFade,0,1000,1)=324
uniform float edgeFade;
// @Vec2(edgeCenter,1)=[540,1920]
uniform vec2 edgeCenter;


mat2 rotate2d(float angle) {
    float s = sin(angle);
    float c = cos(angle);
    return mat2(c, -s, s, c);
}

// Function to compute the rounded box SDF
float RBox(vec2 coord, vec2 size ,float r) {
    vec2 d = abs(coord) - size + r;
    return min(max(d.x, d.y), 0.0) + length(max(d, 0.0)) - r;
}
vec4 alphaBlender(vec4 a ,vec4 b){
    vec4 s = vec4(a.xyz*a.a,a.a);
    vec4 d = vec4(b.xyz*b.a,b.a);
    float fact = 1.0-s.a;
    return s+d*fact;
}
void main() {
    vec2 fragCoord = gl_FragCoord.xy;
    vec2 ouv = v_texCoord.xy;
    vec2 muv = fragCoord/u_resolution.x;
    muv.x-=0.5;
    
    vec4 color = vec4(1.0);
    
    float castDis = length(fragCoord-castCenter);
    float dis = length(fragCoord-edgeCenter);
    float castMask = smoothstep(castProgress,castProgress-castFade,castDis);
    float edgeMask = smoothstep(edgeProgress,edgeProgress-edgeFade,dis);

    float dc = castDis-castColorProgress;

    float mM = smoothstep(castColorProgress+margin,castColorProgress,castDis);
    float pM = smoothstep(castColorProgress-padding,castColorProgress,castDis);
    vec3 marColor = mix(backColor.rgb,frontColor.rgb,clamp(dc/castColorFade*0.5+0.5,0.,1.0));

    vec4 resC = vec4(marColor,mM*pM*ColorAlpha);


    float si = smoothstep(-2.0,2.0,dc+15.0*sign(dc))*2.0-1.0;
    vec3 dcolor = clamp(backColor.rgb*si,0.0,1.0)+clamp(-frontColor.rgb*si,0.,1.0);
    float alpha = smoothstep(castColorFade,0.,abs(dc));
    vec4 dispersion = vec4(dcolor,alpha);
    // // Screen dimensions for calculations
    vec2 center = u_resolution * 0.5;
    vec2 buv = fragCoord - center;
    // // Calculate distance from the rounded box
    float d = RBox(buv, center, Radius);
    d = abs(d);

    // Calculate angles and rotations
    float angle = atan(buv.x, buv.y);

    float cf2 = BaseA;
    if(angle>0.){
        cf2 += (sin(angle * ROmega + u_time*roundSpeedR + RPhi) * sin(angle)) * RA;
    }else{
        cf2 += (sin(-angle * LOmega + u_time*roundSpeedL + LPhi) * sin(-angle) ) * LA;
    }
    
    // Line and glow effects
    float l = edgeMask*waveAlpha*1.1 / ((step(cf2, d) * (d - cf2) * glow + 1.0))-0.1;
    l = castMask+l*(1.0-castMask);
    // color.rgb = l+ backColor.rgb*(1.0-l);

    color.a = l*viewAlpha;
    // color.rgb*=color.a;
    color = alphaBlender(color,resC);
    fragColor = color;
    //fragColor = dispersion;
    // fragColor.rgb *= fragColor.a;
}


