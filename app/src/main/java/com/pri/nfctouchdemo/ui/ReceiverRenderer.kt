package com.pri.nfctouchdemo.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 接收端专属的两件事：**卡片从上方落下（小卡片等比长大）** + **卡片下方那两个按钮**。
 *
 * 规格来自 `D:\浏览器\oppo_receiver_animation_demo_v6.html`（用户认可的 v6），
 * 数值是把它拉到各个时刻、读 computedStyle 得到的一手实测。v6 的设备宽 291 CSS px
 * 对应本工程 380 的设计px（`380/291 = 1.306`），纵向百分比直接乘 viewH 即可
 * （720/1584 和 380/836 是同一个比例）。
 *
 * 和发送端的分工（用户口径：「卡片动画效果和卡片下方的按钮按 v6，其他和发送端一致」）：
 * 水波、白线、顶部凸起、面板、压暗、背景模糊、信息行**都不在这里**，全部沿用
 * [OverlayView] 原有路径；这里只画卡片和它下面那两个按钮。
 *
 * 卡片的**落位尺寸**由调用方传进来（就是发送端版面算出来的那个 cardRect），
 * 所以换一张竖图时卡片照样是发送端的比例，不会被我写死。
 */
class ReceiverRenderer {

    companion object {
        // ── 时间：都相对"卡片开始进场"，起点沿用发送端的 PANEL_MS + CARD_DELAY_MS ──

        /**
         * 这条卡片时间轴的**推进速率**（>1 = 更快；只作用于接收端自己算的那几段：
         * 下落、对焦、闪光、粒子飘散、按钮淡入/淡出；发送端那条共用时间轴完全不受影响）。
         *
         * 用户口径：「整体时间为 0.5s」—— 取"从卡片出现到**照片完全对焦稳定**"为整体
         * （也就是粒子那段描述里的"照片稳定"），所以速率 = 880 / 500 = 1.76：
         *   下落 570 → **324ms**，完整收敛（[FOCUS_END_MS] 880）→ **正好 500ms**。
         *   之后粒子再飘散 [PARTICLE_OUT_MS] 900 → 约 511ms，即收尾那 0.5s。
         *
         * 如果你要的 0.5s 是**连粒子飘散一起算**的总时长，把这里改成 2.96 即可
         * （下落 193ms + 飘散 304ms = 500ms）。
         *
         * 下面所有 `*_MS` 保持参考片的**原始实测值**不动，只在 [compute] 里把 `s` 乘一次速率 ——
         * 这样各段之间的相对关系（例如"落地前 120ms 才开始对焦"）原样保留。
         * 想回到参考片原速：这里改回 1f。
         */
        const val TIME_RATE = 1.76f

        /** 6.10 → 6.67：小卡片从屏幕上方落到落位点，同时**等比**长大（实际时长 ÷ [TIME_RATE]）。 */
        const val FALL_MS = 570f

        /** 照片自身的不透明度：参考片 6.12→6.20（卡片 6.10s 起落，即 20→100ms）。 */
        const val FADE_IN_AT_MS = 20f
        const val FADE_IN_MS = 80f

        /**
         * 照片**从过曝到本色**的窗口：参考片 6.55→6.98 ⇒ 相对卡片起点是 450→880ms。
         *
         * 这一段是关键：它落在**下落的尾段**，不是一出现就开始变清楚 —— 所以整个下落
         * 过程中照片一直是"过曝 + 去饱和"的一团（brightness 3.3、saturate 0.72），
         * 快落定才洗回 1/1。
         */
        const val FOCUS_AT_MS = 450f
        const val FOCUS_END_MS = 880f

        /**
         * 「清晰可见之后」那一小段收势 —— 用户自备视频：13:55 从上方出现、下落 → 14:20 能清晰
         * 看见图片（此时照片**稍微放大**着）→ 14:40 缩回正常大小，并且**从这时起**才同步发送端
         * 那枚呼吸浮动。
         *
         * [SETTLE_AT_MS] 直接挂在"对焦完成"那一刻（= 照片真正清晰可见）。
         * 时长按视频给的 **0.2s 真实时间**，所以要乘一次 [TIME_RATE] 换算到 s 的刻度。
         */
        const val SETTLE_AT_MS = FOCUS_END_MS
        const val SETTLE_REAL_MS = 200f

        /** 收势走完那一刻（视频 14:40：照片回到正常大小、呼吸就位）。粒子也从这时才开始飘散。 */
        const val SETTLE_END_MS = SETTLE_AT_MS + SETTLE_REAL_MS * TIME_RATE

        /** 清晰可见那一刻卡片的放大倍数（视频里"稍微放大了一点"）。改这一个数就能调幅度。 */
        const val ENLARGE_SCALE = 1.06f

        /**
         * 分层开关 —— **只用来定位掉帧**：一次编译就能逐层关掉，看是哪一层在吃帧。
         *
         * 之所以需要它：接收端每帧比发送端多了三层大渐变（光晕/洗色/闪光）+ 粒子。
         * [HALO] 尤其可疑：半径是 `max(卡片宽,高) × 0.95`，落定前后基本等于**每帧铺满全屏**
         * 一遍径向渐变。这是它们那套粒子（PriSystemUI 的 ParticleSystem）从来不用付的钱 ——
         * 那边就是若干个小实心圆，而且粒子还画在**自己的小 View** 里，脏区只有那一小块。
         *
         * 四项默认全开（`const val` 会被编译期折叠，全开时这层判断是零成本）。
         * 想再排查：只把可疑那一项改成 false 编译一次。
         */
        const val DEBUG_HALO = true
        const val DEBUG_WASH = true
        const val DEBUG_FLASH = true
        const val DEBUG_PARTICLES = true

        /** 6.40 → 6.96 落地那一下的白色闪光（正弦脉冲，v6 用 ^0.75 把峰拉宽）。 */
        const val FLASH_AT_MS = 300f
        const val FLASH_MS = 560f

        /** 6.43 → 6.98 两个按钮淡入上浮。 */
        const val ACTION_IN_AT_MS = 330f
        const val ACTION_IN_MS = 550f

        /**
         * 粒子飘散：从**收势结束**（[SETTLE_END_MS]，视频 14:40：照片已回到正常大小、呼吸就位）
         * 一直飘到**完全消失**（视频 16:00）—— 用户口径「粒子整整到 16:00 才完全消失」，
         * 这一段在视频里是 **1.6s**。
         *
         * 所以窗口按真实时间给 1.6s，再乘 [TIME_RATE] 换到 s 的刻度；改 [TIME_RATE] 不会
         * 把这个 1.6s 带跑。
         *
         * 注意：这个窗口现在是**逐颗扫过**的（见 [PARTICLE_DEATH_MS]）—— 出场最早的粒子在窗口
         * 开头就开始退场，最晚出场的那颗正好在窗口末尾散尽，总时长仍是这 1.6s。每颗自己的淡出
         * 曲线仍是参考片那条（前 40% 掉 60%），所以整体仍是"主体先散、余粒拖到末尾"。
         */
        const val PARTICLE_DISSIPATE_REAL_MS = 1600f
        const val PARTICLE_OUT_AT_MS = SETTLE_END_MS
        const val PARTICLE_OUT_MS = PARTICLE_DISSIPATE_REAL_MS * TIME_RATE

        /**
         * 粒子"到场"的进度指数：第 index 颗在 `((index−4)/(N−4))^(1/POW)` 这个下落进度上出现。
         * 出场顺序 = 序号顺序，所以这个指数同时也是**退场顺序**的依据（见 [PARTICLE_DEATH_MS]）。
         */
        const val PARTICLE_GROW_POW = 1.35

        /**
         * **每颗自己的退场时长**（s 的刻度）。
         *
         * 以前这里是一个全局时钟 `preDissolve`：所有粒子同时开始淡、同时淡完 —— 一整团"唰"地
         * 一起消失。现在改成**每颗按自己的时刻退场**：出场越早的越早开始消失（先出现的先消失），
         * 而每颗的淡出曲线都是**同一条** → 任何时刻，出场早的那颗一定比出场晚的更淡。
         *
         * 取整段窗口的 30%：既保证每颗都是软淡出（不是逐颗闪灭），又让最后一颗正好在
         * [PARTICLE_OUT_AT_MS] + [PARTICLE_OUT_MS] 处散尽 —— 视频那条"整整到 16:00 才完全消失"
         * 的总时长不变。
         */
        const val PARTICLE_DEATH_MS = PARTICLE_OUT_MS * 0.30f

        /** 6.10 → 6.20 粒子进来的淡入。 */
        const val PARTICLE_IN_MS = 120f

        /** 点「接收」之后：7.02 → 7.25 卡片轻微放大、按钮淡出（v6 的反馈）。 */
        const val ACCEPT_ZOOM_AT_MS = 920f
        const val ACCEPT_ZOOM_MS = 230f

        /** v6 里按钮在 t=6.9 之前不给点（相对卡片进场就是 800ms）。 */
        const val ACTION_ENABLE_MS = 800f

        /** 拒绝之后蒙层再压暗一档的过渡。 */
        const val REJECT_DIM_MS = 500f

        // ── 卡片几何（v6 实测）──
        /** 起手：12.5% 屏宽 × 4.2% 屏高的**小卡片**（终态卡片的等比缩小版，圆角也等比）。 */
        const val START_W_FRAC = 0.125f
        const val START_H_FRAC = 0.042f

        /**
         * v6 里照片最终长到的比例（77.4% 屏宽 × 35.2% 屏高）。
         *
         * 落点本身由发送端版面给的 cardRect 决定，这两个数**只用来当高度的上限** ——
         * 让下落途中的宽高比沿着 v6 那条 1.35 → 1.0 的轨迹走，不至于在半空中变成正圆。
         */
        const val END_W_FRAC = 0.774f
        const val END_H_FRAC = 0.352f

        /** 下落的后 45% 把高度上限放开，长成版面给的那个矩形（竖图就是这时候竖着展开的）。 */
        const val OPEN_AT = 0.55f
        /**
         * 起手时卡片中心在屏幕上方外面（−1.2% 屏高）。
         *
         * 落点是**调用方给的**（发送端版面算出来的 cardRect），不写死百分比 ——
         * 写死的话，换一张竖图卡片就会落得偏高、压住上面那行字。
         */
        const val START_CY_FRAC = -0.012f

        // ── 两个按钮（视频实测；v6 是 65.6% 宽 + 11.95cqw 高，两者一致）──
        const val PRIMARY_W = 245f
        const val PRIMARY_H = 45f
        const val SECONDARY_W = 236f
        const val SECONDARY_H = 46f
        /** 两个按钮之间：视频实测 32 ref px ≈ 17dp。 */
        const val ACTION_GAP = 16f
        /** 这一行的总高（发送端是"上滑发送"提示的 25.6dp）。 */
        const val ACTION_ROW_H = PRIMARY_H + ACTION_GAP + SECONDARY_H

        /**
         * 粒子总数上限。
         *
         * 这是个**纯观感**参数，来回调过好几轮（96 → 72 → 108 → 132 → 108）。
         * 反复的原因是它和另外两个参数耦合：区域宽度（[PARTICLE_SPREAD_FRAC]）和
         * 粒子尺寸（[PARTICLE_SIZE_SCALE]）—— 铺得越宽、点越小，同样的颗数看着就越稀。
         * 现在 108 颗 + 尺寸 0.62，折合约每 1% 屏宽 1.8 颗。
         *
         * 注意这只是**上限**：实际数量是 `4 + (N−4)·fall^1.35` 从 4 涨到它，
         * "刚出现时只有少量粒子"那一段不受影响。
         * 更稀 / 更密就只改这一个数：96 / 120。
         */
        private const val PARTICLE_COUNT = 108

        /**
         * 两束的配比：28% 走外侧那一段槽位（"少量围绕边缘"），
         * 其余 72% 走里侧那一段（"主要聚集在后方"）。
         */
        private const val RIM_SHARE = 0.28f

        /**
         * 粒子云的横向范围：卡片**最终宽度**的五分之四
         * （用户口径："可以是五分之四的区域"；先前说的是中间四分之三）。0.4 = 4/5 的一半。
         *
         * 参照是**最终**宽度（[END_W_FRAC]）而不是当前宽度 —— 横向槽位在 [buildParticles]
         * 里一次定死、全程不变（见 [Particle.lane]）。
         */
        private const val PARTICLE_SPREAD_FRAC = 0.4f

        /** 上面那条范围对应的半宽（屏幕宽的百分比）：0.4 × 77.4% ≈ 31%。 */
        private const val REGION_HALF_FINAL = PARTICLE_SPREAD_FRAC * END_W_FRAC * 100f

        /**
         * 贴边那束分到的槽位区间：从整条区间的 70% 处到最外沿。
         * 也就是约 28% 的粒子铺在外侧 30% 的宽度里 —— 两段的**面密度基本相等**
         * （78 颗 / 70% 宽 ≈ 30 颗 / 30% 宽），所以读起来是"靠外的一层"，
         * 但既不会在外沿堆出一条硬边，也不会在交界处断开。
         */
        private const val RIM_LANE_FROM = 0.70f

        /**
         * 粒子整体尺寸系数（用户："大小更小"）。实心核和它那圈 box-shadow 光晕一起缩，
         * 比例不变；改这一个数就能单独调粒子大小（与数量、区域宽度互不影响）。
         *
         * 0.72 → 0.62：用户后来又要"大小再小一点"。最小那颗仍由 [drawParticles] 里的
         * `coerceAtLeast(1f)` 兜住，不会缩到看不见。
         */
        private const val PARTICLE_SIZE_SCALE = 0.62f

        /**
         * 每束**保底**贴边的粒子数：最后这几颗直接钉在最外侧的槽位上，
         * 保证"最左右两边至少各有几颗"（左右交替分配，所以每侧各得一半）。
         * 其余粒子的横向槽位都是随机的，不搞等距格点。
         */
        private const val EDGE_QUOTA = 4

        /**
         * 飘散阶段的抬升量（屏幕高百分比）：`DRIFT_MIN_PCT + u × DRIFT_SPAN_PCT`，u 就是纵向那个
         * 均匀变量。取 8 → 29，和原来那两个独立随机数的取值范围一致。
         *
         * **必须是 u 的仿射函数**：这样 dy/du 是常数，纵向密度在整个飘散过程里保持不变；
         * 一旦换成与 u 无关的独立随机数，密度立刻变成三角形（中间约两端的 2 倍）。
         */
        private const val DRIFT_MIN_PCT = 8f
        private const val DRIFT_SPAN_PCT = 21f

        /**
         * 飘散阶段槽位向外扩张的比例（均匀变量乘正数仍是均匀的，所以横向密度也不变）。
         * 0.15 让最外沿从 ±31% 扩到 ±35.6%，仍在卡片半宽（38.7%）以内。
         */
        private const val LANE_BLOOM = 0.15f

        /** v6 的 CSS px → 本工程设计px。 */
        private const val CSS_TO_DP = 380f / 291f

        // 标签原文（v6）
        const val LABEL_ACCEPT = "接收 1 张照片"
        const val LABEL_REJECT = "拒绝"
        const val TOAST_RECEIVING = "正在接收照片…"
        const val TOAST_ACCEPTED = "已接收 1 张照片"
        const val TOAST_REJECTED = "已拒绝接收"
    }

    /** 「接收 / 拒绝」的结果。 */
    enum class Decision { NONE, ACCEPTED, REJECTED }

    /** 每帧算出来的状态。矩形是**设计px**，绘制时统一乘 k。 */
    class Frame {
        val card = RectF()
        var radiusX = 0f
        var radiusY = 0f
        var cardAlpha = 0f
        /** 进场进度 0..1（粒子数量、扩散都吃它）。 */
        var fall = 0f
        /** 照片自身的不透明度 0..1 —— 参考片里它只占 20→100ms（6.12→6.20），一闪就到位。 */
        var reveal = 0f
        /** 清晰度 0..1：0 = 过曝且去饱和（brightness 3.3 / saturate .72），1 = 本色。 */
        var focus = 0f
        /** 落位闪光 0..1；也可以顺手加到顶部光斑上。 */
        var flash = 0f
        var actionAlpha = 0f
        var actionShift = 0f
        val primary = RectF()
        val secondary = RectF()
        var activeActions = false
        var scrimBoost = 0f
        /** 相对卡片进场的毫秒数（粒子的相位要用它）。 */
        var sinceMs = 0f
    }

    private class Particle(
        val index: Int,
        val rim: Boolean,
        /**
         * 纵向位置 [0,1)。**普通随机**，整条带里均匀取值，不做分层。
         * 纵向密度之所以还能保持均匀，靠的是飘散量对 u 的单调性（见 [drawParticles] 第三段），
         * 不是靠这个数本身。
         */
        val phase: Float,
        val size: Float,
        val alpha: Float,
        val twinkle: Float,
        val blue: Boolean
    ) {
        /** 左右：−1 左 / +1 右。第二遍里按序号奇偶交替，两侧数量天然均衡。 */
        var side = -1f

        /**
         * 这颗粒子在卡片"最终宽度的五分之四"区域里的**横向槽位大小**（屏幕宽的百分比，
         * 正负由 [side] 决定）。参照的是**最终**宽度。
         *
         * 取值是**随机**的，只加两条约束（见 [buildParticles] 第二遍）：
         *   · 出现得越晚、允许的半宽越大 → 中间先有、两侧后补，密度中间略密、往两边渐稀；
         *   · 每束最后 [EDGE_QUOTA] 颗钉在最外侧 → 最左右两边至少各有几颗。
         *
         * （弯路记一笔：先是"按出现那一刻的卡宽算"，先出现的粒子被永久冻在内侧、最终宽度的
         *   边缘几乎没人；后来为了"保证边缘有人"又上了低差异序列 + 左右成对分配，
         *   结果整个点阵太规整 —— 用户："这要保证最两边至少有几个就可以了，不要写那么死"。）
         */
        var lane = 0f
    }

    private val particles = ArrayList<Particle>(PARTICLE_COUNT)

    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val toastPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val matrix = ColorMatrix()

    /** 亮度那一档单独用一个矩阵（ColorMatrix 的 setScale 不改变饱和度）。 */
    private val matrixScale = ColorMatrix()

    /** 照片的**主色**（给下落中的卡片打光）+ 它来自哪张原图。 */
    private var dominant = 0
    private var dominantFrom: Bitmap? = null

    /** 主色那层光的画笔、以及 highlightOf 复用的 HSV 暂存（每帧调用，别每次都 new）。 */
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hsvScratch = FloatArray(3)



    var decision: Decision = Decision.NONE
        private set

    private var decisionAtMs = 0f

    init {
        buildParticles()
    }

    // ══════════════════════════ 粒子表（严格照抄 v6 的随机序列） ══════════════════════════
    private var seed = 5815

    /** 和 v6 完全同一条 LCG；Kotlin 的 Int 运算本身就回绕，等价于 JS 的 `>>> 0`。 */
    private fun rand(): Float {
        seed = seed * 1664525 + 1013904223
        return (seed.toLong() and 0xFFFFFFFFL) / 4294967296f
    }

    private fun buildParticles() {
        particles.clear()
        for (i in 0 until PARTICLE_COUNT) {
            // 抽签顺序沿用 v6 的排列：rim → phase → size → alpha → twinkle → blue
            val rim = rand() < RIM_SHARE
            val phase = rand()
            val size = 0.65f + rand() * 1.9f
            val alpha = 0.38f + rand() * 0.62f
            val twinkle = rand() * (2f * PI.toFloat())
            val blue = rand() < 0.32f
            particles.add(Particle(i, rim, phase, size, alpha, twinkle, blue))
        }

        // 第二遍：横向槽位 + 左右侧。
        //
        // 槽位就是**随机**的，只加两条约束（用户："保证最两边至少有几个就可以了，不要写那么死"）：
        //   1. 出现得越晚、允许的半宽越大：第 k 颗在 [0, hi(k)] 里随机取，hi 从 0.35 线性放到 1.0
        //      —— 于是中间先有、两侧后补（这是 oppo 的行为），密度自然形成"中间略密、往两边渐稀"，
        //      而不是一条一条等距的格点。
        //   2. 每束最后 [EDGE_QUOTA] 颗直接钉在最外侧（0.86 → 1.0 依次排开，各自再抖一点），
        //      保证最左右两边至少各有几颗。
        // 左右由序号奇偶交替决定，不参与随机 → 两侧数量天然均衡。
        val rimCount = particles.count { it.rim }
        val wakeCount = particles.size - rimCount
        var rimSeen = 0
        var wakeSeen = 0
        for (p in particles) {
            val k: Int
            val lo: Float
            val span: Float
            val count: Int
            if (p.rim) {
                k = ++rimSeen
                lo = RIM_LANE_FROM
                span = 1f - RIM_LANE_FROM
                count = rimCount
            } else {
                k = ++wakeSeen
                lo = 0f
                span = RIM_LANE_FROM
                count = wakeCount
            }
            val t: Float
            if (k > count - EDGE_QUOTA) {
                // 保底贴边：这几颗从 0.86 到 1.0 依次排开
                val u = (k - (count - EDGE_QUOTA) - 1f) / (EDGE_QUOTA - 1f)
                t = 0.86f + 0.14f * u + (rand() * 2f - 1f) * 0.03f
            } else {
                val hi = 0.35f + 0.65f * (k.toFloat() / count)
                t = hi * rand()
            }
            p.side = if (k % 2 == 1) -1f else 1f
            p.lane = (lo + span * t.coerceIn(0f, 1f)) * REGION_HALF_FINAL
        }
    }

    /** 重播 / 换角色时清掉上一轮的选择。 */
    fun reset() {
        decision = Decision.NONE
        decisionAtMs = 0f
        // 粒子的横向槽位不用清：它在 [buildParticles] 里定死，每轮完全一样。
    }

    fun accept(elapsedMs: Long) {
        if (decision == Decision.NONE) {
            decision = Decision.ACCEPTED
            decisionAtMs = elapsedMs.toFloat()
        }
    }

    fun reject(elapsedMs: Long) {
        if (decision != Decision.REJECTED) {
            decision = Decision.REJECTED
            decisionAtMs = elapsedMs.toFloat()
        }
    }

    /** 按钮能不能点：v6 是 t ≥ 6.9，相对卡片进场就是 800ms 之后。 */
    fun actionsEnabled(elapsedMs: Long, cardStartMs: Long): Boolean =
        decision == Decision.NONE && (elapsedMs - cardStartMs).toFloat() >= ACTION_ENABLE_MS

    /**
     * 接收端卡片的**起点**：屏幕最顶上那个 mini 卡片（设计空间坐标，调用方自己乘 k）。
     *
     * 发送端的收尾是"接收端下落的严格反向"，那条飞行的终点就是这里 —— 抽成一个函数是为了
     * 两端共用同一份几何：改这里，接收端从哪儿出来、发送端收到哪儿去，一起变。
     */
    fun startCardRect(out: RectF, viewW: Float, viewH: Float) {
        val w = START_W_FRAC * viewW
        val h = START_H_FRAC * viewH
        val cy = START_CY_FRAC * viewH
        val left = viewW / 2f - w / 2f
        out.set(left, cy - h / 2f, left + w, cy + h / 2f)
    }

    /**
     * 下落那一段的**真实**时长（ms）。
     *
     * [FALL_MS] 是量在接收端自己的时间轴上的（[compute] 里 `s` 乘了 [TIME_RATE]），
     * 所以真实时长要除回来：570 / 1.76 ≈ **324ms**。
     *
     * 发送端的收尾飞行直接拿这个数当 duration —— "发送飞上去"和"接收落下来"就是同一段时间
     * 倒着放，两端的节奏永远同步；以后调 [FALL_MS] 或 [TIME_RATE]，发送端自动跟着变。
     */
    fun fallRealMs(): Float = FALL_MS / TIME_RATE

    // ══════════════════════════ 每帧几何 ══════════════════════════
    /**
     * @param viewW / viewH 设计空间（380×836，未乘 k）
     * @param endLeft/endTop/endRight/endBottom 卡片落位后的矩形 —— 就是发送端版面算出来的
     *   cardRect。落点交给版面决定，"卡片与上面那行字的间距"就永远等于发送端的 infoGap，
     *   这里一个百分比都不写死。
     * @param endRadius     落位后的圆角（发送端的 CARD_RADIUS_DP）
     * @param bobY          落定后整卡的呼吸位移（发送端的 cardBob，与顶部凸起同一个相位）
     * @param rowCenterY    按钮那一行的中心 y（发送端版面的 cardTop + cardH + tipGap + rowH/2）
     */
    fun compute(
        elapsedMs: Long,
        cardStartMs: Long,
        viewW: Float,
        viewH: Float,
        endLeft: Float,
        endTop: Float,
        endRight: Float,
        endBottom: Float,
        endRadius: Float,
        bobY: Float,
        rowCenterY: Float
    ): Frame {
        val f = Frame()
        // 接收端这条时间轴的快慢全收在这一个速率上（见 [TIME_RATE]，>1 = 更快）：
        // 各段的先后关系（落定时刻、对焦/闪光/粒子相对它的位置）因此原样保留。
        val s = (elapsedMs - cardStartMs).toFloat() * TIME_RATE
        f.sinceMs = s
        f.fall = smooth(0f, FALL_MS, s)
        // 不透明度：20→100ms 一闪到位（参考片 photo.opacity = reveal = smooth(6.12, 6.20)）
        f.reveal = smooth(FADE_IN_AT_MS, FADE_IN_AT_MS + FADE_IN_MS, s)
        // 清晰度：450→880ms 才从"过曝且去饱和"洗回本色（参考片 receiveP = smooth(6.55, 6.98)）
        f.focus = smooth(FOCUS_AT_MS, FOCUS_END_MS, s)

        // 起手：v6 那个小尺寸（12.5% 屏宽 × 4.2% 屏高），中心在屏幕上方外面
        val startW = START_W_FRAC * viewW
        val startH = START_H_FRAC * viewH
        val startCy = START_CY_FRAC * viewH
        val startLeft = viewW / 2f - startW / 2f
        val startRight = viewW / 2f + startW / 2f

        // 落点**就是发送端版面算出来的 cardRect**（中心与左右两端各自插值）：
        // 于是"卡片与上面那行字的间距"跟发送端一模一样，换任何比例的图片都不会跑上去压住文字；
        // 呼吸同样沿用发送端那枚（同一个相位、同一个振幅），只是要等收势走完才渐入 —— 见下面的 settle。
        val left = lerp(startLeft, endLeft, f.fall)
        val right = lerp(startRight, endRight, f.fall)
        val w = right - left

        // 收势：照片"清晰可见"（对焦完成）那一刻卡片还比正常大一点点，再用约 0.2s 缩回原位；
        // 而且**从这时起**才跟着发送端那枚呼吸浮起来 —— 所以 bobY 要乘一个 0→1 的 settle，
        // 下落途中它不该有呼吸。settle 到 1 之后，卡片就是发送端那一张：尺寸、位置、呼吸全一致。
        val settle = smooth(SETTLE_AT_MS, SETTLE_END_MS, s)
        val grow = 1f + (ENLARGE_SCALE - 1f) * (1f - settle)
        val cy = lerp(startCy, (endTop + endBottom) / 2f, f.fall) + bobY * settle

        // 高度**不能跑在宽度前面**。v6 那张是方照片（终态 294×295），所以它的高线性长起来
        // 也一直是"扁"的；换成竖图，落位高度大得多，再线性插值就会在半空中先变正圆、
        // 再变成一个竖长的方块（用户："太圆了"）。这里拿 v6 自己的高度轨迹当上限，最后 45% 再放开 ——
        // 方图下这条上限永远不生效，逐帧与 v6 一致。
        val v6w = (START_W_FRAC + (END_W_FRAC - START_W_FRAC) * f.fall) * viewW
        val v6h = (START_H_FRAC + (END_H_FRAC - START_H_FRAC) * f.fall) * viewH
        val capH = w * (v6h / v6w)
        val linearH = lerp(startH, endBottom - endTop, f.fall)
        val open = smooth(OPEN_AT, 1f, f.fall)
        val h = lerp(linearH, min(linearH, capH), 1f - open)
        // 放大是**绕中心**的（落点中心不动），所以只是四周各胀一点；缩回后与原矩形完全重合
        val gw = w * (grow - 1f) / 2f
        val gh = h * (grow - 1f) / 2f
        f.card.set(left - gw, cy - h / 2f - gh, right + gw, cy + h / 2f + gh)

        // 形状自始至终是"卡片的小号版"：圆角 = **当前宽度 × 终态圆角占比**（等比缩放）。
        // 以前这里是"椭圆(半径 = 半宽 / 半高) → 圆角矩形"的渐变，于是刚出现那几帧是一颗椭圆、
        // 宽高接近时甚至是圆球 —— 用户："刚开始卡片出来的时候不是椭圆和圆球，就是四方形的
        // 卡片 mini 版"。改等比之后，任何尺寸下都正好是终态卡片按比例缩小的样子；
        // 落位时（w = 终态宽度、grow = 1）半径精确等于 endRadius，所以那条插值曲线一起删了。
        // 宽度乘 grow 是必须的：外面那圈 1.06 的"胀"加在卡片矩形上，圆角不跟着胀就会变形。
        val cornerFrac = endRadius / (endRight - endLeft)
        f.radiusX = w * cornerFrac * grow
        f.radiusY = w * cornerFrac * grow

        // 进场那一瞬间的淡入很快（v6 的 reveal 前 80ms 就到 1）
        f.cardAlpha = f.reveal

        // 落地闪光
        f.flash = if (s >= FLASH_AT_MS && s <= FLASH_AT_MS + FLASH_MS) {
            val u = sin(PI * (s - FLASH_AT_MS) / FLASH_MS)      // Double
            u.coerceAtLeast(0.0).pow(0.75).toFloat()
        } else 0f

        // 两个按钮：淡入 + 上浮，落在给定那一行里（主按钮在上、次按钮在下）
        f.actionAlpha = smooth(ACTION_IN_AT_MS, ACTION_IN_AT_MS + ACTION_IN_MS, s)
        f.actionShift = lerp(6.5f, 0f, f.actionAlpha)
        val rowTop = rowCenterY - ACTION_ROW_H / 2f
        f.primary.set(
            viewW / 2f - PRIMARY_W / 2f, rowTop,
            viewW / 2f + PRIMARY_W / 2f, rowTop + PRIMARY_H
        )
        f.secondary.set(
            viewW / 2f - SECONDARY_W / 2f, rowTop + PRIMARY_H + ACTION_GAP,
            viewW / 2f + SECONDARY_W / 2f, rowTop + PRIMARY_H + ACTION_GAP + SECONDARY_H
        )
        f.activeActions = s >= 0f

        when (decision) {
            Decision.ACCEPTED -> {
                // v6：卡片轻轻放大一下，按钮淡出。
                //pri modify by zhuyang,clamp the accept window so a late tap still runs its own 230ms ramp,20261014,start
                // 窗口起点**不早于按下那一刻**：v6 那支 demo 是脚本在 7.02s（= 卡片进场 920ms）
                // 自动点的，所以它可以直接用 `s`；真机上用户可能更晚才点，那种时候
                // smooth(920, 1150, s) 一上来就是 1 —— 两枚按钮瞬间消失、卡片瞬间放大。
                // 夹一下之后：卡在 920ms 点 = 与 v6 逐帧一致；更晚点 = 从按下那一刻起同样走
                // [ACCEPT_ZOOM_MS] 那 230ms 的反馈。
                val tapS = (decisionAtMs - cardStartMs).toFloat() * TIME_RATE
                val at = maxOf(ACCEPT_ZOOM_AT_MS, tapS)
                val u = smooth(at, at + ACCEPT_ZOOM_MS, s)
                //pri modify by zhuyang,clamp the accept window so a late tap still runs its own 230ms ramp,20261014,end
                f.actionAlpha *= (1f - u)
                val z = lerp(1f, 1.015f, u)
                val cx = f.card.centerX()
                val cyy = f.card.centerY()
                val hw = f.card.width() / 2f * z
                val hh = f.card.height() / 2f * z
                f.card.set(cx - hw, cyy - hh, cx + hw, cyy + hh)
            }
            Decision.REJECTED -> {
                // v6：卡片、按钮都收掉，蒙层再压暗一档
                f.cardAlpha = 0f
                f.actionAlpha = 0f
                f.activeActions = false
                f.scrimBoost = smooth(0f, REJECT_DIM_MS, (elapsedMs - decisionAtMs).toFloat()) * 0.5f
            }
            Decision.NONE -> Unit
        }
        return f
    }

    // ══════════════════════════ 绘制 ══════════════════════════
    /**
     * 卡片 + 落光柱 + 闪光 + 粒子尾迹，一把画完（顺序就是 v6 的层序）。
     *
     * @param k 设计px → 屏幕px（[OverlayView] 里同一个 k）
     * @param photo 卡片里那张照片；null 时只铺一层底色，和发送端一样能跑
     */
    fun drawCard(c: Canvas, f: Frame, photo: Bitmap?, k: Float, viewW: Float, viewH: Float) {
        if (f.cardAlpha <= 0.003f || f.card.width() <= 0f) return

        val rect = RectF(f.card.left * k, f.card.top * k, f.card.right * k, f.card.bottom * k)
        val rx = f.radiusX * k
        val ry = f.radiusY * k

        // 卡片自己的"光度"：这张照片的主色。取色只在换图时算一次。
        // 光晕铺在卡片**下面**、色光罩在卡片**上面**，两者都跟着 reveal 退场 ——
        // 落定之后卡片就是照片本身，和发送端一致。
        val dom = if (photo != null && !photo.isRecycled) dominantOf(photo) else 0
        // 主色的光跟着**清晰度**退场：整个下落过程都带着它自己的颜色，落定对焦时才淡掉
        val lit = (1f - f.focus).coerceIn(0f, 1f)
        if (DEBUG_HALO && dom != 0 && lit > 0.01f) drawHalo(c, rect, dom, lit)

        if (DEBUG_FLASH && f.flash > 0.01f) drawFlash(c, rect, f)

        val save = c.save()
        clipPath.reset()
        clipPath.addRoundRect(rect, rx, ry, Path.Direction.CW)
        c.clipPath(clipPath)

        // 注意：Canvas **没有** alpha（那是 View 的属性）。整层淡入淡出必须落到
        // 各自的 Paint 上，或者用 saveLayer 配一个带 alpha 的 Paint。
        val cardAlpha = f.cardAlpha.coerceIn(0f, 1f)
        if (photo != null && !photo.isRecycled) {
            // 参考片：blur(8.5→0) brightness(3.3→1) saturate(.72→1)，而且这条曲线吃的是
            // receiveP（6.55→6.98）—— 发生在**下落的尾段**，整个下落过程中照片一直是
            // "过曝 + 去饱和"的一团，快落定才洗回 1/1。
            //
            // 卡片不模糊（原来那套"最糊 → 次糊 → 原图"三级交叠已经整段删掉），所以过曝
            // 全压在这一张清晰图上 —— 顺带省掉每帧最多三次整卡位图绘制。
            val focus = f.focus.coerceIn(0f, 1f)
            if (focus >= 0.995f) {
                // 落定之后矩阵已经退化成单位矩阵（bright/饱和都是 1），再挂一次滤镜就是每帧
                // 白扔一次 ColorMatrixColorFilter。直接裸画 —— 像素结果一样，卡片真正"回归一张图"。
                tintPaint.colorFilter = null
                tintPaint.alpha = (cardAlpha * 255f).toInt().coerceIn(0, 255)
                c.drawBitmap(photo, null, rect, tintPaint)
            } else {
                // 只高亮、不模糊：整段过曝全压在这一张**清晰**的图上。
                // focus → 1 时矩阵退化成单位矩阵，于是和上面那条"落定"分支无缝接上
                //（不会在 0.995 处跳一下）。
                val bright = lerp(3.3f, 1f, focus)
                val sat = lerp(0.72f, 1f, focus)
                matrix.setSaturation(sat)
                matrixScale.setScale(bright, bright, bright, 1f)
                matrix.postConcat(matrixScale)
                tintPaint.colorFilter = ColorMatrixColorFilter(matrix)
                tintPaint.alpha = (cardAlpha * 255f).toInt().coerceIn(0, 255)
                c.drawBitmap(photo, null, rect, tintPaint)
                tintPaint.colorFilter = null
            }
            tintPaint.alpha = 255
        } else {
            shapePaint.color = mul(0xFF2A3550.toInt(), cardAlpha)
            c.drawRect(rect, shapePaint)
        }

        // 主色光罩在照片之上（仍裁在卡片轮廓里）：下落时是"过曝 + 主色"的一团，
        // 落定过程里这层收到 0，照片自己洗回本色。
        if (DEBUG_WASH && dom != 0 && lit > 0.01f) drawWash(c, rect, dom, lit)
        c.restoreToCount(save)

        // 粒子压在卡片**上面**（v6 里 .particles 有 z-index:3）
        if (DEBUG_PARTICLES) drawParticles(c, f, k, viewW, viewH)
    }

    /**
     * 主按钮那一行字。
     *
     * 默认就是 [LABEL_ACCEPT]（主线那张**写死"1 张"**的文案）；宿主知道真实张数时在绘制前换掉即可
     * （一碰互联的 ROM 副本走 `R.string.receive_accept_count`，"接收 N 张照片"）。
     * 渲染器自己不认识资源、也不该认识 —— 换文案是宿主的事。
     */
    var acceptLabel: String = LABEL_ACCEPT

    /** 卡片下方那两个按钮：主（纯白 + 深色字）、次（半透明 + 白字）。 */
    fun drawActions(c: Canvas, f: Frame, k: Float, viewW: Float) {
        if (!f.activeActions || f.actionAlpha <= 0.003f) return

        // Canvas 没有 alpha，整行的淡入要乘进每个 Paint 的颜色里
        val a = f.actionAlpha.coerceIn(0f, 1f)
        val save = c.save()
        c.translate(0f, f.actionShift * k)

        val primary = RectF(f.primary.left * k, f.primary.top * k, f.primary.right * k, f.primary.bottom * k)
        val secondary = RectF(f.secondary.left * k, f.secondary.top * k, f.secondary.right * k, f.secondary.bottom * k)
        val cx = viewW / 2f * k

        // 主按钮：纯白胶囊
        shapePaint.reset()
        shapePaint.isAntiAlias = true
        shapePaint.style = Paint.Style.FILL
        shapePaint.color = mul(0xF7FFFFFF.toInt(), a)
        shapePaint.setShadowLayer(14f * k, 0f, 3f * k, mul(0x59000000, a))
        c.drawRoundRect(primary, PRIMARY_H / 2f * k, PRIMARY_H / 2f * k, shapePaint)
        shapePaint.clearShadowLayer()

        textPaint.reset()
        textPaint.isAntiAlias = true
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textPaint.textSize = 14f * k
        textPaint.color = mul(0xFF101820.toInt(), a)
        textPaint.textAlign = Paint.Align.CENTER
        val fm = textPaint.fontMetrics
        c.drawText(acceptLabel, cx, primary.centerY() - (fm.ascent + fm.descent) / 2f, textPaint)

        // 次按钮：半透明胶囊 + 一道细边
        shapePaint.color = mul(0x21FFFFFF, a)
        c.drawRoundRect(secondary, SECONDARY_H / 2f * k, SECONDARY_H / 2f * k, shapePaint)
        shapePaint.style = Paint.Style.STROKE
        shapePaint.strokeWidth = 1f * k
        shapePaint.color = mul(0x42FFFFFF, a)
        c.drawRoundRect(secondary, SECONDARY_H / 2f * k, SECONDARY_H / 2f * k, shapePaint)

        textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textPaint.color = mul(0xEBFFFFFF.toInt(), a)
        val fm2 = textPaint.fontMetrics
        c.drawText(LABEL_REJECT, cx, secondary.centerY() - (fm2.ascent + fm2.descent) / 2f, textPaint)

        c.restoreToCount(save)
    }

    /** 底部提示条（正在接收 / 已接收 / 已拒绝）。 */
    fun drawToast(c: Canvas, k: Float, viewW: Float, viewH: Float, text: String) {
        if (text.isEmpty()) return
        textPaint.reset()
        textPaint.isAntiAlias = true
        textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textPaint.textSize = 12f * k
        textPaint.color = 0xFF151515.toInt()
        textPaint.textAlign = Paint.Align.LEFT
        val tw = textPaint.measureText(text)
        val padH = 18f * k
        val h = 34f * k
        val rect = RectF(
            viewW / 2f * k - tw / 2f - padH,
            viewH * 0.845f * k,
            viewW / 2f * k + tw / 2f + padH,
            viewH * 0.845f * k + h
        )
        toastPaint.reset()
        toastPaint.isAntiAlias = true
        toastPaint.color = 0xEDFFFFFF.toInt()
        toastPaint.setShadowLayer(22f * k, 0f, 6f * k, 0x80000000.toInt())
        c.drawRoundRect(rect, h / 2f, h / 2f, toastPaint)
        toastPaint.clearShadowLayer()
        val fm = textPaint.fontMetrics
        c.drawText(text, rect.centerX() - tw / 2f, rect.centerY() - (fm.ascent + fm.descent) / 2f, textPaint)
    }

    // ══════════════════════════ 零件 ══════════════════════════

    /**
     * 卡片外面的同色光晕：用**这张照片的主色**发光，让落下来的这一颗一眼就带着自己的颜色，
     * 而不是一团白。alpha 跟着 `1 - reveal` 走，落定后完全消失。
     */
    private fun drawHalo(c: Canvas, rect: RectF, dom: Int, lit: Float) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val r = maxOf(rect.width(), rect.height()) * 0.95f
        if (r <= 0f) return
        glowPaint.reset()
        glowPaint.isAntiAlias = true
        glowPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(withAlpha(highlightOf(dom), 0.46f * lit), withAlpha(dom, 0f)),
            floatArrayOf(0.30f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, glowPaint)
        glowPaint.shader = null
    }

    /**
     * 卡片**上面**那层主色光（调用处已经裁在卡片轮廓里）：整体从 0.42 收到 0，
     * 于是"过曝 + 主色"的那一团在落定过程里逐渐对焦、洗净成照片本身。
     */
    private fun drawWash(c: Canvas, rect: RectF, dom: Int, lit: Float) {
        highlightPaint.reset()
        highlightPaint.isAntiAlias = true
        highlightPaint.shader = LinearGradient(
            rect.left, rect.top, rect.left, rect.bottom,
            intArrayOf(withAlpha(highlightOf(dom), 0.62f), withAlpha(dom, 0.30f)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        highlightPaint.alpha = (lit * 0.42f * 255f).toInt().coerceIn(0, 255)
        c.drawRect(rect, highlightPaint)
        highlightPaint.shader = null
    }

    private fun drawFlash(c: Canvas, rect: RectF, f: Frame) {
        val rx = rect.width() * 0.62f
        val ry = rect.height() * 0.62f
        val r = sqrt(rx * rx + ry * ry)
        if (r <= 0f) return
        glowPaint.reset()
        glowPaint.isAntiAlias = true
        glowPaint.shader = RadialGradient(
            rect.centerX(), rect.centerY(), r,
            intArrayOf(0xFFFFFFFF.toInt(), 0xE6FFFAEF.toInt(), 0x59B6C9FF, 0x00000000),
            floatArrayOf(0f, 0.52f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
        glowPaint.alpha = (f.flash.coerceIn(0f, 1f) * 0.85f * 255f).toInt().coerceIn(0, 255)
        c.drawCircle(rect.centerX(), rect.centerY(), r, glowPaint)
        glowPaint.shader = null
    }

    private fun drawParticles(c: Canvas, f: Frame, k: Float, viewW: Float, viewH: Float) {
        val s = f.sinceMs
        val fp = f.fall
        val active = min(
            PARTICLE_COUNT,
            maxOf(4, (4f + (PARTICLE_COUNT - 4) * fp.toDouble().pow(PARTICLE_GROW_POW)).roundToInt())
        )
        if (active <= 0) return

        val dissip = smooth(PARTICLE_OUT_AT_MS, PARTICLE_OUT_AT_MS + PARTICLE_OUT_MS, s)
        // 纵向跟着**此刻的卡片轮廓**走（落到哪、长到多大都自动对得上）；
        // 横向是固定槽位（[Particle.lane]），不随卡片变化 —— 所以已经出现的粒子永远不会被推走。
        val hPct = (f.card.height() / viewH) * 100f
        val cyPct = (f.card.centerY() / viewH) * 100f

        for (p in particles) {
            if (p.index >= active) continue
            var x: Float
            var y: Float
            // 纵向：u 在 [0,1] 均匀，铺在 [卡片上边沿 + 0.6%, 再往上 trailLen 屏高] 这条带上，
            // 所以纵向密度处处相同。
            // （两个历史坑都出在这几行：纵向曾用 (0.45 + 0.55·|sinθ|)·半高 —— θ 均匀时
            //   |sinθ| 是反正弦分布、粒子全堆在弧顶，和尾迹段一密一疏，就成了"多-少-多"三层；
            //   横向曾用"落位那一刻的卡宽"，于是最终宽度外沿没人、中间过密。
            //   现在纵向一条均匀分布，横向交给 [Particle.lane] 的分层槽位。）
            val u = p.phase
            val trailLen = lerp(2.4f, 19.5f, fp.toDouble().pow(0.82).toFloat())
            y = cyPct - hPct * 0.5f - 0.6f - u * trailLen
            // 横向直接用槽位本身，**不乘锥形系数** —— 乘了会把外沿拉空、又往中间堆。
            // 间距由分层抽样保证均匀，所以粒子之间是"散开"的，不会黏在一起。
            x = 50f + p.side * p.lane + sin(s / 1000f * 3.1f + p.twinkle) * lerp(0.12f, 1.1f, fp)
            // 第三段：照片稳定之后整团**向上**飘散、并向两侧散开一点，同时缩小、淡出。
            //
            // 两个飘散量都必须**由 u / 槽位单调导出**，不能叠独立随机数：
            // 均匀分布 + 独立均匀随机量 = 两个均匀分布之和，密度是**三角形**（中间约两端的 2 倍）
            // —— 这正是"从上往下 多-较多-多"的来源。
            //   纵向：仿射于 u（8% → 29% 屏高）→ dy/du 是常数 → 纵向密度处处相同；
            //   横向：把槽位整体外扩 → 均匀变量乘正数仍然是均匀的 → 横向密度也相同。
            x = 50f + (x - 50f) * (1f + dissip * LANE_BLOOM)
            y -= (DRIFT_MIN_PCT + u * DRIFT_SPAN_PCT) * dissip

            val intro = (s / PARTICLE_IN_MS).coerceIn(0f, 1f)
            // ── 每颗自己的退场时刻：先出现的先消失 ──
            // b = 这颗"到场"时的下落进度（把上面 active 的公式反解出来，用同一个指数）。
            // 出场顺序就是序号顺序，所以 b 随序号递增 → 退场时刻也随之递增。
            // 淡出曲线仍是参考片那条（前 40% 掉 60%），只是**每颗各算各的**：
            // 同一条曲线 + 递增的起始时刻 ⇒ 任何瞬间，早出场的都比晚出场的更淡。
            val b = ((p.index - 4f) / (PARTICLE_COUNT - 4f)).coerceIn(0f, 1f)
                .toDouble().pow(1.0 / PARTICLE_GROW_POW).toFloat()
            val deathAt = PARTICLE_OUT_AT_MS + b * (PARTICLE_OUT_MS - PARTICLE_DEATH_MS)
            val fadeOut = 1f - smooth(0.40f, 1f, ((s - deathAt) / PARTICLE_DEATH_MS).coerceIn(0f, 1f))
            val opacity = p.alpha * (0.36f + 0.64f * fp) * intro * fadeOut
            if (opacity <= 0.01f) continue

            // 尺寸 = 基础 px × 参考片那个 scale(1 + fall*.35 − dissip*.22) × 整体缩小系数
            val scaleP = 1f + fp * 0.35f - dissip * 0.22f
            val px = ((0.65f + p.size * (0.56f + 0.48f * fp)) * (1f - dissip * 0.42f) *
                scaleP * PARTICLE_SIZE_SCALE * CSS_TO_DP * k).coerceAtLeast(1f)

            // 粒子光晕：**还原**成参考片那种 box-shadow 模糊光（这里原本就是这个写法）。
            // 之前为了排查掉帧曾改成"手画两层淡圈 + 实心核"，但实测把粒子和光晕**全关掉**
            // 之后依旧觉得慢 —— 说明瓶颈不在渲染开销，那就不该为它牺牲观感。
            dotPaint.reset()
            dotPaint.isAntiAlias = true
            dotPaint.color = if (p.blue) 0xFFDCE8FF.toInt() else Color.WHITE
            dotPaint.alpha = (opacity.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
            if (fp > 0.1f) {
                // 参考片的 box-shadow：0 0 (2+4·fall)px (0.3+0.75·fall)px（跟着整体缩小系数一起缩）
                dotPaint.setShadowLayer(
                    (2f + fp * 4f) * PARTICLE_SIZE_SCALE * CSS_TO_DP * k, 0f, 0f,
                    if (p.blue) 0xE0B4CDFF.toInt() else 0xE0FFFFFF.toInt()
                )
            }
            c.drawCircle(x / 100f * viewW * k, y / 100f * viewH * k, px / 2f, dotPaint)
            dotPaint.clearShadowLayer()
        }
    }

    // ══════════════════════════ 小工具 ══════════════════════════

    /**
     * 把颜色自带的 alpha 再乘一个系数。Canvas 没有 alpha，淡入淡出只能乘进颜色里，
     * 所以这个函数是接收端所有"整块淡入"的基础：mul(0xF7FFFFFF, .5f) 就是 alpha 减半的白。
     */
    private fun mul(color: Int, factor: Float): Int {
        val base = (color ushr 24) and 0xFF
        val a = (base * factor.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    /**
     * 这张照片的**主色**：缩到 16×16，把每个像素按 `饱和度² × 明度` 加权投进 4bit/通道的直方图，
     * 取票数最高的那一桶再求桶内均值。灰底、白边这些"没颜色"的像素几乎不投票，所以选出来的是
     * 照片里真正占面积的那块颜色。只在换图时算一次。
     */
    private fun dominantOf(src: Bitmap): Int {
        val cached = dominant
        if (cached != 0 && dominantFrom === src) return cached

        val tiny = Bitmap.createScaledBitmap(src, 16, 16, true)
        val px = IntArray(16 * 16)
        tiny.getPixels(px, 0, 16, 0, 0, 16, 16)
        if (tiny !== src) tiny.recycle()

        val bins = 16 * 16 * 16
        val weight = FloatArray(bins)
        val count = IntArray(bins)
        val sumR = IntArray(bins)
        val sumG = IntArray(bins)
        val sumB = IntArray(bins)
        for (p in px) {
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            val sat = if (mx == 0) 0f else (mx - mn).toFloat() / mx
            val bin = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            // 0.02 的底票：整张都是灰照片时也还能选出"最不灰"的那一桶，不至于返回 0
            weight[bin] += sat * sat * (mx / 255f) + 0.02f
            count[bin]++
            sumR[bin] += r
            sumG[bin] += g
            sumB[bin] += b
        }
        var best = 0
        for (i in 1 until bins) if (weight[i] > weight[best]) best = i
        val n = count[best].coerceAtLeast(1)
        val color = Color.rgb(sumR[best] / n, sumG[best] / n, sumB[best] / n)
        dominant = color
        dominantFrom = src
        return color
    }

    /** 主色 → 高亮色：明度提到接近满、饱和再提一点，用来当卡片上那层光。 */
    private fun highlightOf(base: Int): Int {
        Color.colorToHSV(base, hsvScratch)
        hsvScratch[1] = (hsvScratch[1] * 1.05f + 0.18f).coerceIn(0f, 1f)
        hsvScratch[2] = (hsvScratch[2] * 0.35f + 0.72f).coerceIn(0f, 1f)
        return Color.HSVToColor(hsvScratch)
    }

    /** 把颜色换成指定 alpha（[mul] 是乘在原有 alpha 上，这里要的是"设为"）。 */
    private fun withAlpha(color: Int, frac: Float): Int =
        (color and 0x00FFFFFF) or
            (((frac.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)) shl 24)

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)

    private fun smooth(a: Float, b: Float, v: Float): Float {
        if (b <= a) return if (v <= a) 0f else 1f
        val p = ((v - a) / (b - a)).coerceIn(0f, 1f)
        return p * p * (3f - 2f * p)
    }
}
