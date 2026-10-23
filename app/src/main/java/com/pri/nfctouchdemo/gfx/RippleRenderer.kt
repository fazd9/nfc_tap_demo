package com.pri.nfctouchdemo.gfx

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import com.pri.nfctouchdemo.anim.Timeline
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 水波渲染器：把原型里的 WebGL 片元着色器搬到 GLES 2.0。
 *
 * **折射源是一张真实图片**：`GalleryTexture` 那张画出来的"照片界面"已经不用了，
 * 现在由 [upload] 把调用方给的背景图传进来当 `u_image`（演示时是用户在主页选的那张，
 * 产品里是对方的屏幕画面）—— 于是水波扭的是那张真图。还没上传时绑一张
 * 1×1 全透明占位纹理，那时水波只剩那层冷暖光带和波前压暗，窗口是透的。
 *
 * **v17：片元着色器整段换成了 HTML 复刻版 v13 的原样移植。** 原来那套
 * 「以屏宽为单位、椭圆距离场的单瓣高斯色带」调到头也只能是一根光带，和参考片
 * 那种"一颗透明的球 + 一圈玻璃棱"差得远，所以不再打补丁，直接搬过来：
 * - 几何用屏高单位、`aspect = W/H`，距离场是**正圆**（旧版 `metricX = 0.4`
 *   把环压成了横宽比 1.58:1 的扁椭圆）；
 * - 半径走 `earlyRadius/lateRadius` 两段扩散律：前 0.225s 加速到直径满屏，
 *   之后换成慢速指数逼近，正好对应参考片"小圆球 → 越过屏宽后变成弧"；
 * - 球体是球冠几何，法线由 `sphereQ` 的导数给出，因此球顶高光、0.70R 的暗带、
 *   球缘菲涅尔、软箱反射都跟着球一起长大；
 * - 屏幕内容经过三层折射界面（core / middle / shoulder）各按不同映射采样，再加
 *   棱上的窄色散，所以内容不是整体平移，而是在球内被压缩、放大、扭转；
 * - 环带用 13 条宽度/色调各异的线叠出玻璃棱，外侧还有波谷暗带、冲击环、边框光。
 * 数学一行没改，只做了四处适配：时间轴（`u_progress` 反解 Timeline 缓入后乘
 * [RIPPLE_TIMELINE_S]）、三个滑块常量、纹理朝向（见 `texOf`）、预乘 alpha 输出。
 * 移植后在 WebGL 里和 HTML 原版逐像素对拍过：全时间轴最大差 1/255、平均 0.001。
 *
 * **背景还能整张糊掉**：除了那张清晰的背景图，这里还持有它的一张**模糊版**（[BackdropBlur]，
 * 换图时在 UI 线程上算一次，尺寸约 1/4、由 `GL_LINEAR` 放大回来）。片元里的 `u_blur`
 * 是 0 → 1 的交叉淡入系数，由 `RippleSurfaceView.setBlur` 喂进来 —— 发送的照片（卡片）
 * 出现时背景糊掉，就是这一路。没算成模糊版时这个系数强制为 0，画面不变。
 *
 * 颜色按**预乘 alpha** 输出。`u_image` 的 alpha 只有 0（那张 1×1 全透明占位）
 * 和 1（上传了图）两种取值，所以这里直接输出 `vec4(col, alpha)` 而不在 rgb 上再乘
 * 一次 alpha：alpha=1 时两者等价；alpha=0 时内容本来就是黑的、只剩叠加的光，
 * 正好复刻"还没上传背景图时窗口是透的、只看得到光"的老行为。
 */
class RippleRenderer : GLSurfaceView.Renderer {

    /** 水波扩散进度，0..1。 */
    @Volatile
    var ripple: Float = 0f

    /**
     * 背景的模糊程度，0 = 全清晰、1 = 全模糊。
     *
     * 只在两张纹理都就位时才真的生效（模糊版没算成 → 强制 0），见 [onDrawFrame]。
     */
    @Volatile
    var blur: Float = 0f

    /**
     * 一套折射源：清晰那张 + 它的模糊版（可能为 null，见 [BackdropBlur]）。
     *
     * 两张图打包成一个对象，只用一个 `@Volatile` 引用发布 —— 否则 GL 线程可能读到
     * "新的清晰图 + 旧的模糊版"，那一刻会闪一下上一张照片的模糊残影。
     */
    private class Sources(val sharp: Bitmap, val blurred: Bitmap?)

    /** 待上传的一套折射源；和 [current] 一起在下一帧换上。 */
    @Volatile
    private var pending: Sources? = null

    /** 保留最近上传的那套，EGL 上下文重建时可以直接重传。 */
    @Volatile
    private var current: Sources? = null

    private var program = 0
    private var textureId = 0
    private var blurTextureId = 0
    private var aPosition = 0
    private var uImage = 0
    private var uBlurTex = 0
    private var uBlur = 0
    private var uProgress = 0
    private var uResolution = 0
    private var uCover = 0

    /** 模糊纹理是否真的有一张图（否则那张 1×1 占位会把背景抹成透明）。 */
    private var blurReady = false

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(12 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f, 1f))
            position(0)
        }

    private var viewW = 1
    private var viewH = 1

    /** 当前纹理尺寸，用来算"盖满视图"的比例修正；0 表示还是那张 1×1 占位。 */
    private var texWidth = 0
    private var texHeight = 0

    /**
     * 换掉折射源（那张被水波扭的背景图）。下一帧生效。
     *
     * 顺手在这里算一次**模糊版**（[BackdropBlur]）：调用方本来就站在 UI 线程上，
     * 几毫秒算完，GL 线程那边只负责上传 —— 换图的下一帧不会卡。
     */
    fun upload(bitmap: Bitmap) {
        val sources = Sources(bitmap, BackdropBlur.of(bitmap))
        current = sources
        pending = sources
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)

        program = runCatching { buildProgram(VERTEX_SHADER, FRAGMENT_SHADER) }
            .onFailure { android.util.Log.e("RippleRenderer", "着色器初始化失败，回退为空图层", it) }
            .getOrDefault(0)
        if (program == 0) return
        aPosition = GLES20.glGetAttribLocation(program, "a_position")
        uImage = GLES20.glGetUniformLocation(program, "u_image")
        uBlurTex = GLES20.glGetUniformLocation(program, "u_blur_tex")
        uBlur = GLES20.glGetUniformLocation(program, "u_blur")
        uProgress = GLES20.glGetUniformLocation(program, "u_progress")
        uResolution = GLES20.glGetUniformLocation(program, "u_resolution")
        uCover = GLES20.glGetUniformLocation(program, "u_cover")

        val ids = IntArray(2)
        GLES20.glGenTextures(2, ids, 0)
        textureId = ids[0]
        blurTextureId = ids[1]
        for (id in ids) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            // 模糊版就是靠这个 GL_LINEAR 从小图放大回整屏的
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            // 1×1 全透明占位（RGBA 全 0）。真正的图由 upload() 传进来替换它。
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
            )
        }
        texWidth = 0
        texHeight = 0
        blurReady = false
        // 上下文重建后把手上那套折射源重新传一次
        pending = current
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width.coerceAtLeast(1)
        viewH = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewW, viewH)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (program != 0) {
            val sources = pending
            if (sources != null) {
                uploadTexture(textureId, sources.sharp)
                // 模糊版是我们自己生成的（ARGB_8888、尺寸和裁切都跟着清晰那张）；
                // 没算成（图太小 / 已回收）就把系数按 0 喂，画面回到以前那样
                val soft = sources.blurred
                if (soft != null) {
                    uploadTexture(blurTextureId, soft)
                    blurReady = true
                } else {
                    blurReady = false
                }
                pending = null
            }
        }

        // 清成完全透明：没画到的地方就是下层界面
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (program == 0 || textureId == 0) return

        GLES20.glUseProgram(program)
        GLES20.glUniform1f(uProgress, ripple.coerceIn(0f, 1f))
        GLES20.glUniform2f(uResolution, viewW.toFloat(), viewH.toFloat())
        // "盖满视图"的比例修正：纹理比视图宽就裁左右，比视图窄就裁上下。
        // 正常情况下 decodeCover 已按屏幕比例裁过，这里算出来就是 (1, 1)；
        // 万一视图比例和裁切依据的屏幕比例不一致（窗口被系统缩进过），也不会拉伸或留边。
        if (texWidth > 0 && texHeight > 0 && viewW > 0 && viewH > 0) {
            val texAspect = texWidth.toFloat() / texHeight
            val viewAspect = viewW.toFloat() / viewH
            if (texAspect > viewAspect) {
                GLES20.glUniform2f(uCover, viewAspect / texAspect, 1f)
            } else {
                GLES20.glUniform2f(uCover, 1f, texAspect / viewAspect)
            }
        } else {
            GLES20.glUniform2f(uCover, 1f, 1f)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uImage, 0)
        // 模糊版走第二个纹理单元：两张图共用同一套 UV / cover，着色器里直接按 u_blur 混。
        // 没有模糊版（图太小 / 还没上传）时把系数按 0 喂进去，画面和以前完全一样。
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurTextureId)
        GLES20.glUniform1i(uBlurTex, 1)
        GLES20.glUniform1f(uBlur, if (blurReady) blur.coerceIn(0f, 1f) else 0f)

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(aPosition)
    }

    /**
     * 把一张位图传到指定纹理。
     *
     * 只有清晰那张会写 [texWidth] / [texHeight]：模糊版是同一张图、同一套裁切，
     * 比例一样，所以 `u_cover` 用一套就够了。
     */
    private fun uploadTexture(id: Int, bitmap: Bitmap) {
        if (id == textureId) {
            texWidth = bitmap.width
            texHeight = bitmap.height
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        // Bitmap 首行对应纹理 t = 0，和 WebGL canvas 的约定一致，
        // 所以片元着色器里的 UV 翻转逻辑可以原样保留。
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "程序链接失败：" + GLES20.glGetProgramInfoLog(p)
        }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, source: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, source)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "着色器编译失败：" + GLES20.glGetShaderInfoLog(s)
        }
        return s
    }

    private companion object {

        // ---- v17：涟漪着色器改成 HTML 复刻版 v13 的原样移植 ----
        // 扩散律、环带分布、球体明暗、色散、边框光全部搬进片元着色器，
        // 原来那套「屏宽单位的单瓣高斯色带 + 椭圆距离场」的常量随之作废。
        // 下面三个对应 HTML 里三个滑块的默认值，改它们等于改 HTML 里的滑块。
        const val RIPPLE_WARP = 1.0f
        const val RIPPLE_GLOW = 1.0f
        const val RIPPLE_IMPACT = 1.3f

        /** 移植后的动画时长：HTML 版是 1.1s（Timeline 的 WAVE_MS 是 1.0s，靠这个系数换算）。 */
        const val RIPPLE_TIMELINE_S = 1.1f

        const val VERTEX_SHADER = """
            attribute vec2 a_position;
            varying vec2 v_uv;
            void main() {
                v_uv = (a_position + 1.0) * 0.5;
                gl_Position = vec4(a_position, 0.0, 1.0);
            }
        """

        val FRAGMENT_SHADER = """
            precision highp float;
            varying vec2 v_uv;
            uniform sampler2D u_image;
            uniform sampler2D u_blur_tex;
            uniform float u_blur;
            uniform float u_progress;
            uniform vec2 u_resolution;
            uniform vec2 u_cover;

            // ---------------------------------------------------------------------------
            // 这段是 HTML 复刻版 v13 片元着色器的原样移植。数学一律不动，只做了四处适配：
            // 1. uT（0..1.1 秒）改成从 u_progress 反解 Timeline 的缓入，再乘 1.1；
            // 2. uWarp / uGlow / uImpact 三个滑块固定成 HTML 的默认值 1.0 / 1.0 / 1.3；
            // 3. uTex -> u_image，采样统一走 texOf()：本项目这张 Bitmap 由 GLUtils 上传、
            //    没有纵向翻转，首行落在 t=0，所以纹素纵坐标直接等于 p.y；
            // 4. 输出改成预乘 alpha。
            // 局部的 uWarp / uGlow 就是为了让下面的算式和 HTML 逐字一致、方便对照。
            // ---------------------------------------------------------------------------

            float ease01(float x){ x=clamp(x,0.0,1.0); return x*x*(3.0-2.0*x); }
            float roundRectSdf(vec2 pos, vec2 halfSize, float radius){
              vec2 q = abs(pos) - halfSize + vec2(radius);
              return length(max(q, vec2(0.0))) + min(max(q.x,q.y),0.0) - radius;
            }
            // p（x 居中、y 从屏幕顶部向下、单位 = 屏高）-> 纹理坐标，等价于原来的 sampleUv。
            vec2 texOf(vec2 pp){
              vec2 uv2 = vec2(pp.x, 1.0 - pp.y);
              vec2 covered = vec2(0.5) + (uv2 - vec2(0.5)) * u_cover;
              return vec2(covered.x, 1.0 - covered.y);
            }
            // 取折射源：u_blur 为 0（绝大多数时间）时不额外采一次模糊图。
            vec4 sampleSource(vec2 pp){
              vec2 tc = texOf(pp);
              vec4 s = texture2D(u_image, tc);
              if (u_blur > 0.001) s = mix(s, texture2D(u_blur_tex, tc), u_blur);
              return s;
            }

            void main(){
              vec2 p = vec2(v_uv.x, 1.0 - v_uv.y);
              float t = (1.0 - pow(1.0 - u_progress, ${1.0 / Timeline.RIPPLE_EASE})) * ${RIPPLE_TIMELINE_S};
              float impactStrength = ${RIPPLE_IMPACT};
              float uWarp = ${RIPPLE_WARP};
              float uGlow = ${RIPPLE_GLOW};

              float hitPulse = exp(-pow((t-0.084)/0.019,2.0));
              float hitFlash = exp(-pow((t-0.088)/0.014,2.0));
              float orbA = smoothstep(0.061,0.096,t) * (1.0-smoothstep(0.515,0.665,t));
              float pointIn = smoothstep(0.043,0.079,t);
              float pointOut = 1.0-smoothstep(0.116,0.158,t);
              float pointA = pointIn*pointOut;
              float waveIn = smoothstep(0.092,0.164,t);
              float waveOut = pow(1.0-smoothstep(0.620,0.960,t), 1.4);   // v61: 提前开始、更长更柔的淡出
              float waveA = waveIn*waveOut;

              float aspect = u_resolution.x/u_resolution.y;
              float originY = 0.0;
              vec2 rippleVec = vec2((p.x-0.5)*aspect, p.y-originY);
              float radialDistance = length(rippleVec);
              float pointGrow = ease01(smoothstep(0.045,0.092,t));
              float pointRadius = 0.0012 + 0.0044*pointGrow;
              float pointMask = (1.0-smoothstep(pointRadius,pointRadius+0.0028,radialDistance))*pointA;

              float age = max(t-0.070,0.0);
              float ageR = age*0.60;   // v71: 阶段A 拉长（满屏宽约 0.375s）   // v54: 球体长到满屏宽这一段放慢（0.225s → 约 0.30s）
      float earlyAge = min(ageR,0.225);
              float growthU = clamp(earlyAge/0.225,0.0,1.0);
              float baseRadius = 0.002 + 0.225*pow(growthU,1.2);
              float kickIn = smoothstep(0.0,0.012,age);
              float kickOut = exp(-max(age-0.025,0.0)/0.030);
              float launchKick = 0.021*impactStrength*kickIn*kickOut;
              float earlyRadius = baseRadius + launchKick;
              float lateAge = max(ageR-0.225,0.0);
              float lateRadius = 0.227 + 0.760*(1.0-exp(-6.0*lateAge));   // v64: 渐近逼近 75% 屏高，全程持续下移、无超调
              // 半径标定（参考片实测，屏宽单位）：0.15s→0.14、0.20s→0.21、0.25s→0.29、
              // 0.30s→0.43、0.35s→0.53、0.40s→0.58。原来的扩散律整体偏大 16~32%，乘这个
              // 系数后六个时间点全部落进 ±0.01 屏宽。相位结构不变（小圆球 → 越过屏宽 → 弧），
              // 直径跨过屏宽的时刻仍在 t≈0.34。
              float radiusScale = 0.83;
              float rippleRadius = (ageR <= 0.225 ? earlyRadius : lateRadius) * radiusScale;
              // v22: 环越过屏宽之后随扩张逐渐褪掉（参考片是"渐变消失"，不是撑到时间轴尾段才灭）。
              // 0.227 屏高 = 球直径正好等于屏宽。环要一直撑到约 0.46 屏高（≈屏幕高度一半）才淡完，
              // 所以区间取 0.235→0.460、幅度 50%；最后 0.765s 由 waveOut 收掉。
              // v23: 两个阶段，边界 = 圆的直径是否超过屏幕宽度（屏宽在 rippleVec 单位下 = aspect）
              float phaseA = 1.0 - smoothstep(0.5*aspect, 0.5*aspect + 0.030, rippleRadius);
              float spread = 1.0 - phaseA;
              float spreadFade = 1.0 - 0.55*smoothstep(0.5*aspect, 0.549, rippleRadius);
              waveA *= spreadFade;
              float contourOn = smoothstep(0.085,0.19,t);
              float contourNoise = contourOn * (
                  0.00115*sin(p.x*20.0 + t*2.0)
                + 0.00068*sin(p.x*47.0 - t*3.7)
                + 0.00042*sin(p.x*79.0 + t*4.6));
              float d = radialDistance-rippleRadius + contourNoise;

              float sphereR = max(rippleRadius, 0.006);
              float sphereQ = radialDistance / sphereR;
              float sphereQSafe = min(sphereQ, 0.998);
              float sphereHeight = sqrt(max(1.0 - sphereQSafe*sphereQSafe, 0.004));
              float sphereSlope = min((sphereQSafe / max(sphereHeight, 0.17)) * 0.88, 4.2);
              float sphereMask = (1.0 - smoothstep(0.925, 1.035, sphereQ)) * orbA;
              float sphereBody = (1.0 - smoothstep(0.90, 1.015, sphereQ)) * orbA;
              float orbRimDark = exp(-pow((sphereQ-0.996)/0.046,2.0))*orbA;
              float orbRimBlue = exp(-pow((sphereQ-0.962)/0.039,2.0))*orbA;
              float orbRimWhite = exp(-pow((sphereQ-0.978)/0.014,2.0))*orbA;
              float orbInnerReflect = exp(-pow((sphereQ-0.835)/0.065,2.0))*orbA;
              vec2 sphereDir = rippleVec / max(radialDistance, 0.00001);
              vec2 radialP = vec2(sphereDir.x/max(aspect,0.001), sphereDir.y);
              vec2 tangentP = vec2(-sphereDir.y/max(aspect,0.001), sphereDir.x);
              vec3 sphereNormal = normalize(vec3(sphereDir * sphereSlope, 1.0));

              vec3 viewDir = vec3(0.0, 0.0, 1.0);
              vec3 lightDirA = normalize(vec3(-0.05, -0.14, 1.0));
              vec3 lightDirB = normalize(vec3(0.56, -0.22, 0.80));
              vec3 halfDirA = normalize(lightDirA + viewDir);
              vec3 halfDirB = normalize(lightDirB + viewDir);
              float sphereDiffuse = max(dot(sphereNormal, lightDirA), 0.0);
              float sphereFresnel = pow(1.0 - clamp(sphereNormal.z, 0.0, 1.0), 2.4) * sphereMask;
              float sphereSpecBroad = pow(max(dot(sphereNormal, halfDirA), 0.0), 22.0) * sphereMask;
              float sphereSpecSharp = pow(max(dot(sphereNormal, halfDirA), 0.0), 76.0) * sphereMask;
              float sphereSpecSide = pow(max(dot(sphereNormal, halfDirB), 0.0), 34.0) * sphereMask;
              float sphereCentre = (1.0 - smoothstep(0.14, 0.94, sphereQ)) * orbA;
              float sphereSideOcclusion = smoothstep(0.52, 0.99, sphereQ) * sphereMask;
              float softboxReflection = exp(-pow((p.x - 0.49)/0.105, 2.0)
                  -pow((p.y - (originY + sphereR*0.27))/max(sphereR*0.18, 0.012), 2.0)) * sphereMask;

              vec2 normal = normalize(vec2(rippleVec.x, rippleVec.y));

              float lensDepth = mix(0.002,0.055,smoothstep(0.10,0.38,t));
              float innerLens = (1.0-smoothstep(-lensDepth,0.025,d))*waveA;
              float ringWiden = mix(0.30, 1.55, smoothstep(0.16, 0.92, t));   // v68: 末端再宽一档   // v63: 环带随时间逐渐变厚
      float rim = exp(-abs(d)/(0.045*ringWiden))*waveA;

              float glassMass = exp(-pow((d+0.010)/0.030,2.0))*waveA;
              float darkUndercut = exp(-pow((d+0.015)/0.010,2.0))*waveA;
              float deepBlueEdge = exp(-pow((d+0.010)/0.0085,2.0))*waveA;
              float blueTransmission = exp(-pow((d+0.0055)/0.0090,2.0))*waveA;
              float innerSpec = exp(-pow((d+0.0047)/0.0025,2.0))*waveA;
              float coreSpec = exp(-pow(d/0.0020,2.0))*waveA;
              float outerSpec = exp(-pow((d-0.0030)/0.0036,2.0))*waveA;
              float outerCaustic = exp(-pow((d-0.0077)/0.009,2.0))*waveA;
              float softOuterReflection = exp(-pow((d-0.0110)/0.016,2.0))*waveA;

              float shockFade = smoothstep(0.071,0.091,t) * (1.0-smoothstep(0.175,0.255,t));
              float shockOffset = (0.0034 + 0.0024*exp(-age*20.0))*(0.82+0.18*impactStrength);
              float shockRadius = rippleRadius + shockOffset;
              float shockRing = exp(-pow((radialDistance-shockRadius)/0.0026,2.0))*shockFade;
              float shockUndercut = exp(-pow((radialDistance-(shockRadius-0.0032))/0.0032,2.0))*shockFade;

              float innerInkGroove   = exp(-pow((d+0.032)/0.0065,2.0))*waveA;
              float innerSilverLine  = exp(-pow((d+0.030)/0.0019,2.0))*waveA;
              float innerBlueBand    = exp(-pow((d+0.026)/0.0040,2.0))*waveA;
              float innerWhiteHair   = exp(-pow((d+0.022)/0.0021,2.0))*waveA;
              float innerDarkGroove  = exp(-pow((d+0.019)/0.0030,2.0))*waveA;
              float innerCyanEdge    = exp(-pow((d+0.016)/0.0020,2.0))*waveA;
              float midSilverBand    = exp(-pow((d+0.013)/0.0032,2.0))*waveA;
              float midBlueHairline  = exp(-pow((d+0.008)/0.0016,2.0))*waveA;
              float outerWhiteHair   = exp(-pow((d+0.001)/0.0015,2.0))*waveA;
              float outerBlueLine    = exp(-pow((d-0.006)/0.0024,2.0))*waveA;
              float outerSilverLine  = exp(-pow((d-0.014)/0.0030,2.0))*waveA;
              float outerGlintLine   = exp(-pow((d-0.020)/0.0017,2.0))*waveA;

              float shellRelief = (0.62*sin((d+0.034)*236.0 + 0.62*sin(p.x*15.0-t*1.4))
                                 + 0.38*sin((d+0.015)*365.0 - t*2.8 + sin(p.x*23.0)))
                                 * exp(-pow((d+0.010)/0.052,2.0))*waveA;

              float facet = 0.70 + 0.30*(0.5 + 0.5*sin(p.x*17.0 + t*1.8 + 0.65*sin(p.x*6.5-t*0.7)));
              float specPatchA = 0.52 + 0.48*exp(-pow((p.x-0.36)/0.19,2.0));
              float specPatchB = 0.56 + 0.44*exp(-pow((p.x-0.72)/0.16,2.0));
              float specVariation = mix(specPatchA,specPatchB,smoothstep(0.35,0.72,p.x))*facet;

              float fineWave = sin(d*142.0 - t*16.0);
              float edgeEnvelope = exp(-abs(d)*23.0)*waveA;
              float crestLens = exp(-pow(d/0.026,2.0))*waveA;
              float rimWarp = fineWave*edgeEnvelope*0.0072
                            + sin(d*58.0-t*7.5 + p.x*8.0)*exp(-abs(d)*12.0)*0.0030
                            + shellRelief*0.0022;
              vec2 tangent = normalize(vec2(-normal.y,normal.x));
              float bodyBulge = exp(-pow((d+0.014)/0.050,2.0))*waveA;
              float bevelSlope = exp(-pow((d-0.002)/0.011,2.0))*waveA;
              vec2 sampleP = p;
              sampleP += normal * (rimWarp + bodyBulge*0.028 + bevelSlope*0.020 + crestLens*0.030) * uWarp;
              sampleP += tangent * sin(p.x*25.0+t*5.2) * bodyBulge*0.0026*uWarp;
              float impulseLens = 0.030*hitPulse*impactStrength*sphereMask*uWarp;
              sampleP -= radialP*impulseLens;

              float q = clamp(sphereQ, 0.0, 1.4);
              float coreLayer = (1.0-smoothstep(0.16,0.57,q))*waveA;
              float middleLayer = smoothstep(0.22,0.43,q)*(1.0-smoothstep(0.69,0.88,q))*waveA;
              float shoulderLayer = smoothstep(0.58,0.80,q)*(1.0-smoothstep(0.96,1.09,q))*waveA;

              float domeRefraction = sphereMask * sphereSlope * (0.0105 + 0.0050*sphereHeight);
              sampleP -= radialP * domeRefraction * uWarp;
              sampleP.x += (p.x-0.5)*innerLens*0.042*uWarp;
              sampleP.y += innerLens*(0.0055 + 0.0026*sin(p.x*9.0-t*3.2))*uWarp;
              sampleP = clamp(sampleP,vec2(0.001),vec2(0.999));

              // v18 修重影：原来是三张各自缩放/平移过的采样图叠在一起
              //（core 的采样坐标压到 0.84×，middle 撑到 1.19×，差 40%），同一段文字于是同时
              // 出现两份、错开 40~100px —— 这就是球里那道"重影"。真实透镜只有一张像，所以这里
              // 把三层的缩放/径向位移先按同一组权重合成一条连续剖面，再只采一次。
              float radialShift = -0.019*sin(min(q,1.0)*3.14159265)*coreLayer
                                + 0.024*exp(-pow((q-0.57)/0.23,2.0))*middleLayer
                                - 0.032*exp(-pow((q-0.84)/0.145,2.0))*shoulderLayer;
              float shearShift = (0.0042*sin(p.x*11.0-t*1.4)+0.0018*sin(p.y*18.0+t))*middleLayer
                               + 0.0045*sin(p.x*16.0+t*1.7)*shoulderLayer;
              float pullAmt = -0.160*coreLayer + 0.190*middleLayer + 0.160*shoulderLayer;
              float pushAmt = -0.055*coreLayer - 0.035*middleLayer + 0.075*shoulderLayer;
              vec2 refractP = sampleP + radialP*(radialShift*uWarp) + tangentP*(shearShift*uWarp);
              refractP.x = 0.5 + (refractP.x-0.5)*(1.0+pullAmt*uWarp);
              refractP.y = originY + (refractP.y-originY)*(1.0+pushAmt*uWarp);
              // v33: 与球同心、横向同宽的椭圆，纵向 1.30 倍；位移只作用在球外(d>0)，
              // 直径越过屏宽后随 phaseA 归零，消散成环的一部分。
              float wakeGate = phaseA*smoothstep(0.10, 0.18, t);
              float we = (p.x-0.5)/max(rippleRadius/aspect, 0.001);
              float wh = (p.y-originY)/max(rippleRadius*1.30, 0.001);
              float wakeE = we*we + wh*wh;
              float wakeOutside = smoothstep(0.0, 0.006, d);
              float wakeLens = exp(-wakeE*1.2)*wakeGate*wakeOutside;
              refractP.x += we*wakeLens*0.035;
              refractP.y += wh*wakeLens*0.200;
              // v69: 边缘透镜——大强度折射只压在椭圆边界上，用形变代替颜色
              float wakeEdgeLens = exp(-pow((wakeE-1.0)/0.22, 2.0))*wakeGate*wakeOutside;
              refractP.x += we*wakeEdgeLens*0.085;
              refractP.y += wh*wakeEdgeLens*0.140;
              refractP = clamp(refractP,vec2(0.001),vec2(0.999));
              vec3 layeredContent = sampleSource(refractP).rgb;
              // v26: 球内透镜模糊（越靠球心越糊，球缘为 0）
              // v72: 球内透镜——阶段A 加强 1.9 倍，阶段B 减到 0.35 倍
              float lensGain = mix(1.90, 0.35, spread);
              float lensBlur = clamp(-d/0.28, 0.0, 1.0)*lensGain*0.0022*smoothstep(0.0, 0.045, min(min(p.x,1.0-p.x), min(p.y,1.0-p.y)))*waveA;   // v48: 随涟漪一起消失，否则结束后照片仍发糊
              layeredContent = (layeredContent
                + sampleSource(refractP+vec2(lensBlur,0.0)).rgb
                + sampleSource(refractP-vec2(lensBlur,0.0)).rgb
                + sampleSource(refractP+vec2(0.0,lensBlur)).rgb
                + sampleSource(refractP-vec2(0.0,lensBlur)).rgb)*0.2;

              // 色散围绕真正采样的那个点（refractP，和 layeredContent 同一点），
              // 不是折射前的 sampleP —— 否则彩边会和内容错开一点。
              // HTML 里是在翻转过的 texUv 上加 normal*ca；这里换算回 p 空间，纵轴取反。
              float ca = (rim*0.0038 + coreSpec*0.0018)*uWarp;
              vec2 caP1 = clamp(refractP + vec2(normal.x, -normal.y)*ca, vec2(0.001), vec2(0.999));
              vec2 caP2 = clamp(refractP - vec2(normal.x, -normal.y)*ca, vec2(0.001), vec2(0.999));
              float red = sampleSource(caP1).r;
              float blue = sampleSource(caP2).b;
              vec3 refracted = vec3(mix(layeredContent.r,red,rim*0.62),layeredContent.g,
                                    mix(layeredContent.b,blue,rim*0.62));
              vec3 col = mix(layeredContent,refracted,clamp(rim*1.35,0.0,0.88));

              float bodyDark = exp(-pow((sphereQ - 0.70)/0.34, 2.0));
              float volumeShade = sphereMask * (0.020 + 0.068*bodyDark + 0.030*(1.0-sphereDiffuse));
              col *= 1.0-volumeShade;
              col *= 1.0-0.14*sphereSideOcclusion;
              col = mix(col, col*vec3(0.62,0.66,0.71)+vec3(0.008,0.009,0.011), sphereMask*0.17);
              col += vec3(0.026,0.030,0.035)*sphereMask*(0.28+0.42*sphereDiffuse)*uGlow;
              col += vec3(0.24,0.28,0.32)*sphereFresnel*0.44*uGlow;
              col += vec3(0.55,0.60,0.65)*sphereSpecBroad*0.26*uGlow;
              col += vec3(0.86,0.89,0.93)*sphereSpecSide*0.22*uGlow;
              col += vec3(0.91,0.97,1.0)*sphereSpecSharp*0.95*uGlow;
              col += vec3(0.96,0.98,1.0)*sphereMask*pow(max(sphereNormal.z,0.0),6.0)*0.34*uGlow;
      col += vec3(0.40,0.52,0.65)*softboxReflection*0.17*uGlow;

              col = mix(col, col*vec3(0.42,0.47,0.53)+vec3(0.002,0.003,0.004), sphereBody*0.12);
              col *= 1.0-0.22*orbRimDark;
              col += vec3(0.10,0.12,0.14)*orbInnerReflect*0.24*uGlow;
      col += vec3(0.80,0.88,1.00)*orbRimBlue*(0.24+0.14*hitPulse)*impactStrength*uGlow;
      col += vec3(0.94,0.97,1.00)*orbRimWhite*(0.34+0.24*hitPulse*impactStrength)*uGlow*(1.0+0.30*phaseA);
              col += vec3(0.35,0.40,0.46)*sphereMask*hitFlash*0.055*impactStrength*uGlow;

              col *= 1.0;  // v42: 启动瞬间的冲击压暗已移除
              col += vec3(0.72,0.78,0.84)*shockRing*0.19*impactStrength*uGlow;
              col += vec3(0.88,0.92,0.97)*shockRing*0.10*impactStrength*uGlow;

              col *= 1.0-0.16*pointMask;

              float bodyShade = 0.16*innerLens;
              col *= 1.0-bodyShade;
              col *= 1.0-0.38*darkUndercut;
              col = mix(col,col*vec3(0.72,0.76,0.80)+vec3(0.006,0.008,0.010),glassMass*0.17);
                            float dimTop = 0.600*smoothstep(0.245,0.555,t);
              float wakeBand = exp(-pow((p.y-0.615)/0.105, 2.0));
              float deepZone = smoothstep(0.62, 0.95, p.y);
              float globalDim = 0.0;   // v41: 按要求不做整体压暗（原来最多压 60%，还按高度分档）
                            col *= 1.0-globalDim;
              col *= 1.0 - 0.20*sphereMask*phaseA;   // v52: 球内压暗收轻，保住球里的内容   // v50: 球内加深，接近黑玻璃
              float lumNow = dot(col, vec3(0.299,0.587,0.114));
              col = mix(col, vec3(lumNow), 0.10*wakeBand*dimTop);
              col += vec3(0.050,0.055,0.062)*exp(-pow((p.y-0.500)/0.030,2.0))*wakeBand*dimTop;
              // v37: 椭圆边 = 细暖白亮线 + 紧贴内侧一道暗刻
              float wakeLine = exp(-pow((wakeE-1.0)/0.040, 2.0))*wakeGate*wakeOutside;
              float wakeCut  = exp(-pow((wakeE-0.88)/0.070, 2.0))*wakeGate*wakeOutside;
              float wakeGain = 0.70+0.65*smoothstep(-0.4,1.0,wh);
              // v70: 银白色玻璃边去掉，边缘只用透镜形变表现
      // col += vec3(0.93,0.97,1.00)*wakeLine*0.70*wakeGain;
      // v51: 椭圆区的大片亮白反光（参考片里那片"湿亮"）
      float wakeGlare = exp(-wakeE*0.60)*wakeGate*wakeOutside;
      col += vec3(0.90,0.95,1.00)*wakeGlare*0.025;
              col *= 1.0 - 0.30*wakeCut;

              col += vec3(0.012,0.014,0.017)*glassMass*0.75*uGlow;
              col += vec3(0.045,0.052,0.060)*deepBlueEdge*0.92*uGlow;
              col += vec3(0.090,0.100,0.112)*blueTransmission*0.60*uGlow;
              col += vec3(0.44,0.47,0.51)*outerCaustic*0.25*uGlow;
              col += mix(vec3(0.58,0.61,0.65),vec3(0.62,0.46,0.30),spread)*softOuterReflection*0.13*uGlow;

              float layeredFacet = 0.64 + 0.36*(0.5 + 0.5*sin(p.x*26.0 + t*2.2 + 0.7*sin(p.x*8.0)));
              col *= 1.0 - 0.16*innerInkGroove;
              col += vec3(0.86,0.90,0.94)*innerWhiteHair*0.38*specVariation*uGlow;
              col += vec3(0.94,0.97,1.0)*outerGlintLine*0.29*specVariation*uGlow;

              col += vec3(0.88,0.91,0.95)*innerSpec*0.66*specVariation*uGlow;
              col += vec3(0.96,0.99,1.0)*coreSpec*1.00*specVariation*uGlow;   // v76: 还原为原始强度
              col += vec3(0.92,0.95,0.98)*outerSpec*0.60*specVariation*uGlow;

              // ---- 环的颜色（v21）：分阶段 ----
              // 阶段 0 —— 球还没长到屏宽（rippleRadius < 0.24 屏高，即直径 < 屏宽）：
              //   保持 v18 那版整体冷调（对称高斯 + 球内 0.28 常冷）。这一段实测是对的，不动。
              // 阶段 1 —— 越过屏宽开始扩散（0.24 → 0.34 屏高）：整体冷调淡到 26%，
              //   避免大面积扩散时整圈/环内发蓝"太深"（v22：内侧斜坡 σ 0.120→0.085、幅度 70%、
              //   整体冷调底 38%→26%）；颜色改由两侧承担（内侧青、外侧暖，环峰回到中性白）。
              //   参考片峰位 R−B 只有 −3，色全在两侧：内侧 −67、外侧 +26。
              float coolAmt = clamp(exp(-pow(d/0.045,2.0))*waveA + sphereMask*0.28, 0.0, 1.0)
                            * mix(1.0, 0.26, spread);
              col *= mix(vec3(1.0), vec3(0.90,0.97,1.18), coolAmt);
              // v39: 阶段 A（球）几乎去色 -> 中性灰；阶段 B 保留环内侧的蓝
              float lumA = dot(col, vec3(0.299,0.587,0.114));
              col = mix(col, vec3(lumA), 0.88*phaseA*sphereMask);   // v45: 只作用在球内，否则整屏发灰
              float env  = exp(-pow(d/0.085,2.0))*waveA*spread;
              float envW = exp(-pow(d/0.070,2.0))*waveA*spread;
              float disp = clamp((d+0.014)/0.050, -1.3, 1.3);
              float coolW = max(-disp, 0.0)*env;
              float warmW = max( disp, 0.0)*envW;
              // 内侧（d<0）：压红抬蓝 —— 青蓝
              col *= 1.0 - vec3(0.24,0.05,-0.18)*coolW;
              col += vec3(-0.048,0.020,0.098)*coolW;
              // 外侧（d>0）：抬红压蓝 —— 琥珀
              col *= 1.0 + vec3(0.15,0.02,-0.15)*warmW;
              col += vec3(0.030,0.014,0.0)*warmW;



                            // v43: 涟漪起来之前原样输出背景，不做任何着色（修启动前整屏发黑）
              col = mix(sampleSource(p).rgb, col, smoothstep(0.0, 0.06, t));
              // v56: 环带改宽、柔边、偏冷青；外侧补一道暗槽
      float ringBandWide = smoothstep(-0.060*ringWiden, 0.0, d)*(1.0 - smoothstep(0.0, 0.080*ringWiden, d))*waveA;
      // v66: 环带改成莫兰迪三段式调色玻璃（压色，不是发白光）
      float bandC = clamp((d + 0.060*ringWiden)/(0.140*ringWiden), 0.0, 1.0);  // 0=上缘 1=下缘
      vec3 bandCol = mix(vec3(0.115,0.105,0.160), vec3(0.300,0.360,0.420), smoothstep(0.00,0.45,bandC)); // 暗紫蓝→暗灰蓝
      bandCol = mix(bandCol, vec3(0.340,0.400,0.380), smoothstep(0.30,0.60,bandC));                       // →灰青
      bandCol = mix(bandCol, vec3(0.420,0.380,0.320), smoothstep(0.55,0.85,bandC));                       // →浅灰褐(奶茶)
      bandCol = mix(bandCol, vec3(0.160,0.130,0.170), smoothstep(0.85,1.00,bandC));                       // →深灰紫
      bandCol += vec3(0.055,0.038,0.020)*smoothstep(0.10,0.00,bandC);                                     // 上缘暖棕
      float edgeVig = mix(0.40, 1.0, smoothstep(0.0, 0.24, min(p.x, 1.0-p.x)));                           // 左右暗角
      bandCol *= edgeVig;
      float ringGeo = smoothstep(-0.060*ringWiden, 0.0, d)*(1.0 - smoothstep(0.0, 0.080*ringWiden, d));   // v67: 不乘 spreadFade
      col = mix(col, bandCol, clamp(ringGeo*waveOut*0.92*(0.30/ringWiden + 0.75), 0.0, 0.92));
      col *= 1.0 - 0.22*exp(-pow((d-0.062)/0.032,2.0))*waveA;
      col = max(col,vec3(0.0));
              col = col/(1.0 + max(col-vec3(0.95),vec3(0.0))*0.12);

              // 预乘输出：alpha 只有 0（占位纹理）和 1（真图）两种取值，
              // alpha=1 时 vec4(col,alpha) 与 vec4(col*alpha,alpha) 等价；
              // alpha=0 时内容为黑、只剩叠加的光，即"窗口透、只看到光"。
              float alpha = sampleSource(p).a;
              // v44: 涟漪启动前整窗全透明，避免首帧纹理未就绪时铺一层黑
              float alphaGate = smoothstep(0.0, 0.06, t);
              alpha *= alphaGate;
              // v46: 照搬原版那层压暗 —— 深蓝黑 + 抬 alpha，边界随进度扫过全屏
              float darkR = u_progress * (aspect + 0.17);
              float reached = 1.0 - smoothstep(darkR - 0.10, darkR + 0.17, d);
              float darkenAmt = reached * smoothstep(0.17, 0.92, u_progress) * 0.45;   // v53: 恢复全屏灰层（sphereMask 会随球体淡出而消失）
              col = mix(col, vec3(0.018, 0.026, 0.04), darkenAmt);
              alpha = mix(alpha, 1.0, darkenAmt);
              gl_FragColor = vec4(col, alpha);
            }
        """
    }
}
