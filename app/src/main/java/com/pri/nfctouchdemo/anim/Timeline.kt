package com.pri.nfctouchdemo.anim

import android.os.SystemClock
import android.view.View

/**
 * 动效时间轴。
 *
 * 分两段：
 * - **入场段** 0 → [INTRO_MS]：按顺序依次走完水波扩散、边缘点亮、面板浮现。
 * - **常驻段** [INTRO_MS] 之后：时间轴不停，继续往前走。
 *   四边白线、边缘脉冲、上滑提示的浮动都挂在时间上，所以它们会一直动下去。
 *
 * 常驻段只剩慢动效，没必要跑满刷新率，因此自动把帧间隔放宽到 [AMBIENT_FRAME_MS]（约 30fps）。
 * 手势拖动和卡片飞走由各自的动画驱动，不受这个节流影响。
 */
class Timeline(private val host: View) {

    companion object {
        /**
         * 水波扩散时长：**1 秒**（按需求定）。
         *
         * 早先是从 OPPO 参考录屏里量的：顶边 ~6.75s、底边 ~7.15s 起亮，落差约 0.4s。
         * 但那是「阈值穿越」量出来的（顶边底边原本都是满亮，得等变暗足够多才翻位），
         * 所以它只是真实波前耗时的**下界**。中途试过 520（偏快）和 900，现在按需求直接取 1000：
         * 水波 1s 铺满整屏。
         *
         * 注意边缘白线**不跟着它走**（见 [EDGE_START_MS] / [EDGE_REVEAL_MS]），
         * 所以改这个数只影响 GL 那层的水波与压暗，不会顺带改白线的节奏。
         */
        const val WAVE_MS = 1000L

        /** 入场动效总时长；过了这个点就进入常驻状态。 */
        const val INTRO_MS = 2300L

        /** 白线显形阶段起点（常量名沿用原型的 glow）。 */
        const val GLOW_MS = 300L

        /** 边缘点亮阶段起点（白线 500ms 起步之后 90ms）。 */
        const val EDGE_MS = 590L

        /**
         * 边缘白线开始显形的时刻：**水波出现 0.5s 之后**（= [WAVE_MS] / 2 = 500ms）。
         *
         * 水波 1s 从顶边中点推到最远的底角，屏幕中线大致落在行程一半处（20:9 上精确值约 449ms），
         * 而需求给的就是 0.5s，两者差几帧，所以直接取一半，不按长宽比去解。
         *
         * 白线的显形**不跟水波进度走**：水波 1s 就结束了，白线自己也要走 [EDGE_REVEAL_MS]
         * （现在是 0.6s：500 → 1100ms），跟着水波凑不出这段时间。
         */
        const val EDGE_START_MS = WAVE_MS / 2

        /**
         * 白线从顶边中点走到"四边全亮"的时长：**0.6 秒**（于是 500 → 1100ms）。
         *
         * 这段时间里半径**匀速**从 0 推到最远的底角：顶边中点先亮，沿顶边往两边走，
         * 再顺两侧往下，最后是底边中点 —— 顺序由 `OverlayView.waveReached` 那套几何决定
         * （圆心在顶边中点的径向距离）；辉光的逐段显形用的是同一条剖面，所以两者同步。
         *
         * 0.6s 是需求给的"白线花费 0.6s 完全显示并铺好"。它同时是后面几件事的基准：
         * 顶部凸起在白线出现 0.5s 后开始（1000ms）、**白线铺满 0.3s 后凸起长满**（1400ms）、
         * 再过 0.1s 凸起开始变色（1500ms `TOP_BLOOM_TINT_AT_MS`）、再 0.1s 卡片入场（1600ms）——
         * 改这个数，后面这几件事会一起跟着挪（见 `OverlayView` 里那几条常量）。
         */
        const val EDGE_REVEAL_MS = 600f

        /**
         * 发送面板阶段起点：**1500ms** —— 正好是"顶部凸起长满 0.1s 之后、开始变色"那一刻。
         *
         * 面板（scrim + 接收方信息 + 卡片 + 上滑提示）是一整块，照片卡片比它再晚
         * `OverlayView.CARD_DELAY_MS`（0.1s）入场，于是卡片落在需求要的 **1600ms**。
         * 背景模糊也从这一刻起（见 [photoBlurAt]）。
         */
        const val PANEL_MS = 1500L

        /**
         * 「发送面板浮现」到「动效收尾」的分段点 = 面板刚好就位那一刻
         * （[PANEL_MS] + 550 = 2050ms）。
         *
         * 原型里这是光带扫过的结束时间；光带整体去掉后，它只剩给阶段文案分段的用途，
         * 代码里没人读它（`glowing` / `edgeLit` / `panelActive` 才是真在用的状态）。
         */
        const val SETTLE_MS = PANEL_MS + 550L

        /** 面板浮起的过渡时长。 */
        const val PANEL_FADE_MS = 550f

        /**
         * 「发送的照片出现 → 背景糊掉」的过渡时长（起点是 [PANEL_MS]，于是 1500 → 2000ms）。
         *
         * 和 [PANEL_FADE_MS] 同步走，让"面板上来"和"背景退后"几乎是同一个动作，
         * 而不是先看清照片、背景再慢慢糊；卡片在 1600ms 落地时背景已经糊了两成。
         * 给 0 = 到点直接糊。
         */
        const val PHOTO_BLUR_MS = 500f

        //pri added by zhuyang,E6: blur ramp-down on dissolve (video: 0.2-0.3s),20261014
        const val BLUR_RELEASE_MS = 250f

        /**
         * 原型里水波用的缓入指数：1 - (1 - raw)^1.18。
         *
         * 水波本身（GL 层）用这条曲线求进度；边缘白线**不用** —— 它是一条独立的线性时间轴
         * （[EDGE_START_MS] 起、[EDGE_REVEAL_MS] 走完），所以不再需要反解它。
         */
        const val RIPPLE_EASE = 1.18

        /** 常驻段的目标帧间隔，约 30fps。 */
        private const val AMBIENT_FRAME_MS = 33L

        // 同步自 D:\A17\NFCTouchDemo 动效主线：降帧时点从 INTRO_MS 推迟到接收端那一段之后。
        /**
         * 帧率放宽的时点：在这之前一直跟着 vsync 跑满帧率，之后才降到 [AMBIENT_FRAME_MS]。
         *
         * 它**比 [INTRO_MS] 更晚**，因为接收端在落定之后还有一长段动效（按现在的速率折算：
         * 卡片 1600ms 起落 → 2100ms 对焦完成、清晰可见 → 2300ms 收势结束
         * （= 视频里的 14:40）→ 粒子一直飘到 **3900ms** 才完全消失（视频 16:00））。
         * 这些原来都掉进了 30fps 区间，落地那一下的闪光/对焦收尾会看着发顿，
         * 所以放宽时点取到它们之后。改接收端 [ReceiverRenderer] 那几段时间常量时，记得同步这一项。
         */
        private const val RELAX_FRAME_MS = 4000L
    }

    fun interface OnFrame {
        /**
         * @param elapsedMs 从入场开始累计的时间，进入常驻段后继续增长
         * @param ripple 0..1 的水波扩散进度
         */
        fun onFrame(elapsedMs: Long, ripple: Float)
    }

    var onFrame: OnFrame? = null

    var elapsedMs: Long = 0L
        private set

    var isPlaying: Boolean = false
        private set

    /** 入场段是否已经走完。 */
    val introFinished: Boolean get() = elapsedMs >= INTRO_MS

    private var startUptime = 0L
    private var lastEmitUptime = 0L

    //pri added by zhuyang,E6: blur release state; -1 = not triggered this round,20261014,start
    private var blurReleaseAt = -1L
    private var blurReleaseFrom = 1f
    private var blurReleaseMs = BLUR_RELEASE_MS
    //pri added by zhuyang,E6: blur release state; -1 = not triggered this round,20261014,end

    private val ticker = object : Runnable {
        override fun run() {
            if (!isPlaying) return
            val now = SystemClock.uptimeMillis()
            val e = now - startUptime

            // 常驻段只跑慢动效，限到约 30fps（但要等接收端那段落定动效也走完）
            if (e > RELAX_FRAME_MS && now - lastEmitUptime < AMBIENT_FRAME_MS) {
                host.postOnAnimation(this)
                return
            }
            lastEmitUptime = now

            elapsedMs = e
            onFrame?.onFrame(elapsedMs, rippleAt(elapsedMs))
            // 不停：四边白线的常驻微颤要一直在
            host.postOnAnimation(this)
        }
    }

    val glowing: Boolean get() = elapsedMs >= GLOW_MS
    val edgeLit: Boolean get() = elapsedMs >= EDGE_MS
    val panelActive: Boolean get() = elapsedMs >= PANEL_MS

    /** 水波扩散进度，带原型里的缓入曲线；扩散完成后恒为 1。 */
    fun rippleAt(elapsed: Long): Float {
        val raw = (elapsed.toFloat() / WAVE_MS).coerceIn(0f, 1f)
        return 1f - Math.pow((1f - raw).toDouble(), RIPPLE_EASE.toDouble()).toFloat()
    }

    /** 发送面板的浮现进度，0 = 完全隐藏，1 = 完全就位。 */
    fun panelAppearAt(elapsed: Long): Float =
        ((elapsed - PANEL_MS).toFloat() / PANEL_FADE_MS).coerceIn(0f, 1f)

    /**
     * 背景的模糊进度，0 = 全清晰，1 = 全模糊。
     *
     * 发送的照片（卡片）是跟着 [PANEL_MS] 入场的，所以这里也从 [PANEL_MS] 起，
     * 用 [PHOTO_BLUR_MS] 淡进去 —— 1500 → 2000ms：面板上来的同时背景开始糊，
     * 卡片 1600ms 落地时背景已糊了两成，之后继续退后，到 2000ms 是一张柔和的底。
     */
    fun photoBlurAt(elapsed: Long): Float {
        //pri added by zhuyang,E6: once releaseBlur() ran, the ramp-down takes over this round,20261014,start
        val at = blurReleaseAt
        if (at >= 0L) {
            return (blurReleaseFrom * (1f - (elapsed - at) / blurReleaseMs)).coerceIn(0f, 1f)
        }
        //pri added by zhuyang,E6: once releaseBlur() ran, the ramp-down takes over this round,20261014,end
        return ((elapsed - PANEL_MS).toFloat() / PHOTO_BLUR_MS).coerceIn(0f, 1f)
    }

    //pri added by zhuyang,E6: start the ramp-down from the current value; a later call re-bases it,20261014,start
    fun releaseBlur(ms: Float = BLUR_RELEASE_MS) {
        blurReleaseFrom = photoBlurAt(elapsedMs)
        blurReleaseAt = elapsedMs
        blurReleaseMs = ms
    }
    //pri added by zhuyang,E6: start the ramp-down from the current value; a later call re-bases it,20261014,end

    /** 从头再演示一遍入场动效。 */
    fun play() {
        stop()
        elapsedMs = 0L
        isPlaying = true
        startUptime = SystemClock.uptimeMillis()
        lastEmitUptime = 0L
        blurReleaseAt = -1L //pri added by zhuyang,E6: a new round must not inherit the release ramp,20261014
        // 先回到 0 帧，确保上一轮的卡片 / 面板 / 白线状态被清干净。
        onFrame?.onFrame(0L, 0f)
        host.postOnAnimation(ticker)
    }

    /** 从当前进度继续（例如从后台回到前台）。 */
    fun resume() {
        if (isPlaying) return
        isPlaying = true
        startUptime = SystemClock.uptimeMillis() - elapsedMs
        lastEmitUptime = 0L
        host.postOnAnimation(ticker)
    }

    /** 跳到指定时间点。 */
    fun seek(ms: Long) {
        stop()
        elapsedMs = ms.coerceAtLeast(0L)
        blurReleaseAt = -1L //pri added by zhuyang,E6: reset() goes through seek(0); every rewind clears the release ramp,20261014
        onFrame?.onFrame(elapsedMs, rippleAt(elapsedMs))
    }

    /** 回到初始状态（冻结在 0 帧）。 */
    fun reset() = seek(0L)

    /** 暂停推进，保留当前画面。 */
    fun stop() {
        isPlaying = false
        host.removeCallbacks(ticker)
    }
}
