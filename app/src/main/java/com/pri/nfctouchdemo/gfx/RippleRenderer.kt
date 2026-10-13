package com.pri.nfctouchdemo.gfx

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
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
 * 波纹本身就是一条**由细变粗、宽阔柔和的冷暖光带**：颜色按**带内位置**在暖白 ↔ 冷青之间
 * 插值 —— 不再是按"绕波源的角度"取色相的整圈彩虹（那正是霓虹灯带感的来源）。
 * 带宽随扩散变宽、半径同时减速。
 *
 * **形变单独一套，照搬 SystemUI 充电水波 `PriChargeRippleShader`（AGSL）的写法**：
 * 前缘前后各 ±[RIPPLE_RING_HALF] 的 smoothstep 环，环内是一条**长波长**行波
 * `sin((dist − 前缘) · [RIPPLE_RIDGE_K])`（波长 ≈ 0.6 屏宽），位移幅度 [RIPPLE_DISP]，
 * 远处只衰减到 [RIPPLE_FAR_FALLOFF] —— 所以波前过去之后水面还在动、再慢慢平复，
 * 而不是"一过就还原"。光的波峰那道冷白微光（[RIPPLE_CREST_GAIN]）也挂在这条波上。
 * 扩散到屏高的 `RIPPLE_HEIGHT_FRAC` 就结束，而"扫过就压暗"那道边界仍然走到底。
 * 参数都在下面那组 `RIPPLE_*` 常量里。
 *
 * **背景还能整张糊掉**：除了那张清晰的背景图，这里还持有它的一张**模糊版**（[BackdropBlur]，
 * 换图时在 UI 线程上算一次，尺寸约 1/4、由 `GL_LINEAR` 放大回来）。片元里的 `u_blur`
 * 是 0 → 1 的交叉淡入系数，由 `RippleSurfaceView.setBlur` 喂进来 —— 发送的照片（卡片）
 * 出现时背景糊掉，就是这一路。没算成模糊版时这个系数强制为 0，画面不变。
 *
 * 颜色按**预乘 alpha** 输出（`gl_FragColor.rgb` 已经乘过 alpha），
 * 高光则直接加到 rgb 上、不抬 alpha —— 这样它在预乘混合里就是"加光"。
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

        // ---- 水波参数（都在"以屏宽为 1"的归一化坐标里，和着色器里的 p 同一套单位）----
        //
        // 要改水波手感，基本上只动这几个数；它们会被插值进片元着色器。

        /**
         * 扩散的尽头：**屏幕高度的这个比例**。
         *
         * 0.75 = 波纹只推到四分之三屏高就结束，不再跑到底边（原来是铺满整个高度）。
         * 注意这只管**那条冷暖光带**：下面那道"扫过就压暗"的边界仍然走到底 ——
         * 否则最下面四分之一会亮一条，和 Canvas 那层全屏 scrim 对不上。
         *
         * 边缘白线的显形**不跟它走**（那条线要一直铺到底角，见 `OverlayView.glowRadiusScale`）。
         */
        const val RIPPLE_HEIGHT_FRAC = 0.75f

        /**
         * 半径的减速指数：`半径 = rMax · (1 − (1 − 进度)^这个值)`。
         *
         * 越大越"越粗越慢"：扩散刚开始就窜出去大半，之后明显拖慢。
         * 1.0 = 线性匀速；1.7 大约在头一半时间走完 69% 的路。
         */
        const val RIPPLE_SLOW = 1.7f

        /**
         * **光带**（看得到的那层冷暖渐变）的宽度：**由细变粗**（跟着半径同一条进度走）。
         *
         * 0.06 → 0.24 ≈ @720 上的 43px → 173px：一上来就是一条宽而软的光，越铺越宽。
         * （原来 0.02 → 0.10 = 14px → 72px，又细又硬，是"霓虹光带"的一半原因。）
         *
         * 0.30 → 0.24 是按 OPPO 参考录屏实测收的：那条带子扫过屏幕上方的纯黑边时，
         * 彩色核心的可见宽度约 175px / 720 ≈ **0.24 屏宽**（高斯 FWHM ≈ 1.75 × 这个值
         * ≈ 0.42 屏宽）；原来 0.30 在末端相当于半屏宽以上，铺得过了。
         */
        const val RIPPLE_BAND_THIN = 0.06f
        const val RIPPLE_BAND_THICK = 0.24f

        /** 光带边缘的陡度（高斯指数的系数）：越大带越"窄边"，越小越糊。1.6 → 0.9 更柔。 */
        const val RIPPLE_BAND_FALLOFF = 0.9f

        /**
         * **形变环**的半宽（相对屏宽）。波前在一点上停留的时长 = 2 · 这个值 / 波前速度。
         *
         * 参考 SystemUI `PriChargeRippleShader`（AGSL；充电工程里那份 GLES 镜像是
         * `ChargeRippleRenderer`）用 `band = halfW · 0.20` = 0.10 屏宽，而它的相速度只有
         * `halfW · 0.68` ≈ 0.34 屏宽/秒 —— 慢速 + 中等环宽，一点上的形变持续约 0.59s。
         * 我们是一次性动效、波前比它快，所以把环放宽到 0.26 屏宽来换停留时间：
         * @720 上早期约 0.19s、后期约 0.93s（波前越走越慢）。
         */
        const val RIPPLE_RING_HALF = 0.26f

        /**
         * 形变环里的波峰密度：**波长 = 2π / 这个值**（dist 以屏宽归一化）。
         *
         * 参考是 `sin((dist − front) · (5.2 / halfW))`，halfW = 0.5 屏宽 ⇒ 系数 10.4，
         * 波长 ≈ **0.604 屏宽**（@720 上 435px、1080 上 652px）—— 是一大口鼓起，不是细纹。
         * 波长长是"位移能给得大而不折采样"的前提。
         */
        const val RIPPLE_RIDGE_K = 10.4f

        /**
         * 位移幅度（相对屏宽）。参考：110px / 1080 短边 ≈ 0.102。
         *
         * 取 0.075（@720 上 54px）：峰值拉伸率 o′ = 幅度 · 2π / 波长 ≈ 0.78 < 1，采样不再
         * 翻折（原来 0.026 配 0.02~0.03 的带宽，o′ 高达 3.6，纹理被拧出硬折痕）。
         * 想更明显就往 0.09（o′≈0.94）加，超过 0.10 就开始折了。
         */
        const val RIPPLE_DISP = 0.075f

        /** 远处形变的保底幅度：参考 `mix(1.0, 0.12, …)` —— 波走到边上只剩 12%，但不归零。 */
        const val RIPPLE_FAR_FALLOFF = 0.12f

        /**
         * 光带里那层"中心微亮"的陡度（相对光带的宽度）：越大越细。
         *
         * 9.0 → 2.5：原来它是一条**更窄更亮**的波峰线，那条线是霓虹感的主要来源；
         * 现在只比光带略紧一点，亮度也压到 [RIPPLE_CORE_GAIN]，是一层柔光而不是亮线。
         */
        const val RIPPLE_CORE_FALLOFF = 2.5f

        /**
         * 光带的三个色（直接写进着色器）。按**带内位置**插值：
         * 尾迹（内侧）粉紫 → 中段近白 → 前沿（外侧）青蓝。
         *
         * 这三个值是**按 OPPO 参考录屏实测**定的 —— 那条带子扫过屏幕上方的纯黑边时，
         * 像素只可能来自带子自己，量到的是品红/紫：`(188,74,159)`、`(153,62,158)`、
         * `(187,89,210)`、`(142,82,195)`，亮部近白 `(247,171,245)`，另一侧偏青蓝。
         * 也就是说参考的冷暖是**粉紫 ↔ 青蓝**这一对，带子里**没有橙/黄/绿**：
         * 参考画面里的橙色来自底图（那张广告海报）本身，不是光带的颜色。
         *
         * 中段必须过近白的中性色：从暖直接插到冷会穿过绿/品红，那又变回"彩带"了。
         * （原来是用 `hsv2rgb` 绕波源取满色相环，整圈七彩 —— 霓虹感的另一半原因。）
         *
         * 想反过来（内侧冷、外侧暖）就把 WARM 和 COOL 两个色值互换。
         * 名字里的 WARM/COOL 现在指"粉紫端 / 青蓝端"，保留名字是为了改回来只动数值。
         */
        const val RIPPLE_TINT_WARM = "vec3(1.00, 0.70, 0.96)"
        const val RIPPLE_TINT_MID = "vec3(0.98, 0.97, 1.00)"
        const val RIPPLE_TINT_COOL = "vec3(0.62, 0.80, 1.00)"

        /** 光带的自发光增益。0.5 → 0.30：原来偏"灯"，现在偏"水面上的一层光"。 */
        const val RIPPLE_GLOW = 0.30f

        /** 中心那层柔光的增益。0.3 → 0.07：不再是亮线，只剩一点微亮。 */
        const val RIPPLE_CORE_GAIN = 0.07f

        /**
         * 波峰那道冷白柔光的增益 —— 参考里**光就挂在波峰上**：
         * `crest = pow(max(wave, 0), 1.45) · 0.68`，波谷只压暗 `·0.14`（峰 0.68 / 谷 0.14）。
         *
         * 我们的冷暖光带仍走 [RIPPLE_GLOW]（那是"带内位置"的渐变），这一层是叠在**波峰**上的
         * 冷白微光（`(0.94, 0.98, 1.0)` 混一点 tint），让"波"和"光"是同一个场，而不是两条
         * 互不相干的带。
         */
        const val RIPPLE_CREST_GAIN = 0.10f

        /**
         * 光带的淡出区间（进度的占比）：0.70 → 1.00 之间淡掉。
         *
         * 必须淡到 0：常驻状态下 `u_progress` 停在 1，不淡掉就会在四分之三屏高处
         * 留下一条静止的光带。
         */
        const val RIPPLE_FADE0 = 0.7f
        const val RIPPLE_FADE1 = 1.0f

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
            // 折射源：调用方给的背景图；还没上传时是 1×1 全透明占位
            uniform sampler2D u_image;
            // 同一张图的模糊版（约 1/4 尺寸，靠 GL_LINEAR 放大回同一块区域）与它的淡入系数
            uniform sampler2D u_blur_tex;
            uniform float u_blur;
            uniform float u_progress;
            uniform vec2 u_resolution;
            // 盖满视图的比例修正：1.0 = 该轴不裁，< 1 表示这一轴要往中间收
            uniform vec2 u_cover;

            void main() {
                vec2 uv = v_uv;
                // 先按 cover 收到"盖满视图"的那一块，再翻 y（Bitmap 首行对应 t = 0）。
                // 注意 uv 本身不动：波纹几何要的是视图坐标，不是纹理坐标。
                vec2 covered = 0.5 + (uv - 0.5) * u_cover;
                vec2 sampleUv = vec2(covered.x, 1.0 - covered.y);
                // 用屏幕宽度作为统一度量，确保扩散波纹在真实屏幕中接近圆形。
                vec2 p = vec2(uv.x - 0.5, (1.0 - uv.y) * (u_resolution.y / u_resolution.x));
                float dist = length(p);
                float aspect = u_resolution.y / u_resolution.x;
                // 波源那一格 dist 为 0，atan(0,0) 未定义 —— 兜一个方向，只影响这一个像素。
                vec2 dir = dist > 0.0001 ? p / dist : vec2(0.0, 1.0);

                // ---- 扩散：到屏高的 ${RIPPLE_HEIGHT_FRAC} 结束 ----
                // 半径走减速曲线（先窜出去、再明显放慢），带宽跟着**同一条进度**由细变粗 ——
                // "光带越粗、速度越慢"是同一条进度的两个表现，所以从同一个 e 里取。
                // bandW 是**看得到的光带**（宽而软，冷暖渐变就铺在它的宽度上）；
                // 形变走参考那套"长波长环"，见下面 ring / ridge。
                float e = 1.0 - pow(1.0 - u_progress, ${RIPPLE_SLOW});
                float radius = ${RIPPLE_HEIGHT_FRAC} * aspect * e;
                float bandW = mix(${RIPPLE_BAND_THIN}, ${RIPPLE_BAND_THICK}, e);
                float bandPos = (dist - radius) / bandW;
                float band = exp(-bandPos * bandPos * ${RIPPLE_BAND_FALLOFF});

                // ---- 形变环：照 SystemUI PriChargeRippleShader 的写法 ----
                // 环 = 前缘前后各 ±RIPPLE_RING_HALF 的 smoothstep 帐篷（不是高斯，支撑是紧的）；
                // 环内是一条**长波长的行波**，相位在波前处为 0（ridge(radius) = 0，前沿无跳变）。
                // 远处幅度只衰减到 RIPPLE_FAR_FALLOFF（参考 0.12）—— 所以波前走过之后，
                // 它身后那段水面仍在起伏、再随 envelope 慢慢平复，而不是"一过就还原"。
                float ring = smoothstep(radius - ${RIPPLE_RING_HALF}, radius, dist)
                           * (1.0 - smoothstep(radius, radius + ${RIPPLE_RING_HALF}, dist));
                float ridge = sin((dist - radius) * ${RIPPLE_RIDGE_K});
                float farFade = mix(1.0, ${RIPPLE_FAR_FALLOFF},
                                    smoothstep(0.0, ${RIPPLE_HEIGHT_FRAC} * aspect, dist));
                float waveBand = ring * farFade;

                // 时间包络：出现时渐入，扩散将尽时淡出（常驻状态不能留下一条静止的光带）。
                float envelope = smoothstep(0.0, 0.055, u_progress)
                               * (1.0 - smoothstep(${RIPPLE_FADE0}, ${RIPPLE_FADE1}, u_progress));

                // ---- 形变：位移沿半径把背景推开（参考也是"逐像素位移采样"）----
                // 位移必须沿半径：d(sampleUv.y) = d(p.y) / aspect（**没有负号**）—— 原来的
                // `-dir.y` 会把圆形波纹镜像成剪切；再乘 u_cover 才是"按 cover 裁剪"下正确的量。
                float amp = ${RIPPLE_DISP} * ridge * waveBand * envelope;
                vec2 offset = vec2(dir.x * amp, dir.y * amp / aspect) * u_cover;

                // 折射采样：u_image 是调用方给的那张背景图（演示时是主页选的那张，
                // 产品里是对方的屏幕画面），这里的位移就是把那张图"推开"。
                vec2 refracted = clamp(sampleUv + offset, 0.001, 0.999);
                vec4 src = texture2D(u_image, refracted);

                // ---- 发送的照片出现时，背景整张糊掉 ----
                // 模糊版是同图同裁切的 1/4 小图，UV 完全一致，所以这里只是"两张图按系数混"。
                // 用 uniform 分支：u_blur 为 0（绝大多数时间）时不多取一次纹理。
                if (u_blur > 0.001) {
                    src = mix(src, texture2D(u_blur_tex, refracted), u_blur);
                }
                vec3 rgb = src.rgb;
                float alpha = src.a;

                // ---- 压暗：跟着自己的半径走，必须盖满整屏 ----
                // 这是"水波扫过的地方变暗"，和光带是两件事：光带只推到 3/4 屏高，
                // 压暗要到底 —— 否则最下面四分之一会亮一条，和 Canvas 那层全屏 scrim 对不上。
                // 半径多给 0.17（过渡带的外半宽），这样 u_progress 到 1 时整屏都已经完全压暗。
                float darkR = u_progress * (aspect + 0.17);
                float reached = 1.0 - smoothstep(darkR - 0.10, darkR + 0.17, dist);
                float darken = reached * smoothstep(0.17, 0.92, u_progress) * 0.45;
                rgb = mix(rgb, vec3(0.018, 0.026, 0.04), darken);
                alpha = mix(alpha, 1.0, darken);

                // ---- 柔光本体：一条**宽阔的冷暖渐变**（按参考录屏是 粉紫 ↔ 青蓝）----
                // 颜色按**带内位置**插值，不再按"绕波源的角度"取色相 —— 绕一圈取色相就是一条
                // 彩虹灯带；按带内位置取色，这一圈光自己就带一条横跨带宽的冷暖渐变，跟着波
                // 一起往外铺。bandPos：-1 = 内侧（尾迹，粉紫）、0 = 峰、+1 = 外侧（前沿，青蓝）。
                float bandT = clamp(bandPos * 0.5 + 0.5, 0.0, 1.0);
                vec3 tint = mix(${RIPPLE_TINT_WARM}, ${RIPPLE_TINT_MID}, smoothstep(0.0, 0.5, bandT));
                tint = mix(tint, ${RIPPLE_TINT_COOL}, smoothstep(0.5, 1.0, bandT));

                float glow = band * envelope;
                float core = exp(-bandPos * bandPos * ${RIPPLE_CORE_FALLOFF}) * envelope;
                // 波峰那道冷白柔光（参考：`crest = pow(max(wave,0),1.45)*0.68`）—— 光于是也挂在
                // 波峰上、和形变共用一个场；波谷只轻微压暗（参考里峰 0.68 / 谷 0.14）。
                float crest = pow(max(ridge, 0.0), 1.45) * waveBand * envelope;
                float trough = max(-ridge, 0.0) * waveBand * envelope;

                // 预乘输出：底色乘 alpha；光只加 rgb、不抬 alpha，在预乘混合里就是"加光"。
                rgb *= alpha;
                rgb += tint * glow * ${RIPPLE_GLOW};
                rgb += mix(vec3(1.0), tint, 0.35) * core * ${RIPPLE_CORE_GAIN};
                rgb += mix(vec3(0.94, 0.98, 1.0), tint, 0.30) * crest * ${RIPPLE_CREST_GAIN};
                rgb -= vec3(0.08, 0.10, 0.12) * trough * 0.14;
                gl_FragColor = vec4(rgb, alpha);
            }
        """
    }
}
