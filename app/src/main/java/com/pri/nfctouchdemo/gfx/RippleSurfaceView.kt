package com.pri.nfctouchdemo.gfx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.util.AttributeSet

/**
 * 承载水波的 GL 画布。
 *
 * 用 RENDERMODE_WHEN_DIRTY：时间轴每推进一帧才主动请求重绘，
 * 静止时完全不占 GPU，和原型的按需重绘行为一致。
 *
 * 这一层默认是**透明**的：EGL 配置带 8 位 alpha，surface 用 TRANSLUCENT 格式，
 * 着色器按预乘 alpha 输出 —— 没画到的地方透出下层界面。
 * 一旦用 [setGallerySource] 换上调用方给的背景图（演示时是主页选的那张，产品里是对方的屏幕画面），
 * 那张图就铺满整层，水波扭的就是它。同一张图还会顺手算一份**模糊版**，
 * 由 [setBlur] 在"发送的照片出现"时交叉淡入 —— 卡片上来，背景就退成一张柔和的底。
 */
class RippleSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : GLSurfaceView(context, attrs) {

    private val rippleRenderer = RippleRenderer()

    init {
        setEGLContextClientVersion(2)
        // 带 alpha 的 EGL 配置：没有它 surface 是不透明的，下层界面透不出来
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(rippleRenderer)
        // surface 自身支持透明，才能和下层界面混合
        holder.setFormat(PixelFormat.TRANSLUCENT)
        // 切到后台再回来时不丢纹理
        preserveEGLContextOnPause = true
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /**
     * 设置水波扩散进度并重绘一帧。
     *
     * 水波在 `Timeline.WAVE_MS`（1000ms）之后就恒为 1 了，这里跳过重复值，
     * 让常驻段（只剩四边白线在动）不用再刷 GL。
     */
    fun setRipple(progress: Float) {
        if (rippleRenderer.ripple == progress) return
        rippleRenderer.ripple = progress
        requestRender()
    }

    /**
     * 设置背景的模糊程度（0 = 全清晰，1 = 全模糊）并重绘一帧。
     *
     * 由时间轴喂进来：发送的照片（卡片）出现时淡到 1，之后一直保持；它跳过重复值，
     * 淡完之后 GL 不再重绘（模糊是静态的，最后一次画的那帧留在 surface 上就够了）。
     */
    fun setBlur(amount: Float) {
        val v = amount.coerceIn(0f, 1f)
        if (rippleRenderer.blur == v) return
        rippleRenderer.blur = v
        requestRender()
    }

    /**
     * 换掉被水波折射的背景图。
     *
     * 服务把调用方给的背景图解码好之后调一次 —— 视图还没挂上窗口时也可以调，
     * 纹理会在 surface 建好之后自动上传（模糊版也在这里顺手算好）。
     */
    fun setGallerySource(bitmap: Bitmap) {
        rippleRenderer.upload(bitmap)
        requestRender()
    }
}
