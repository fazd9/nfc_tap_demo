package com.pri.nfctouchdemo.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * 发送端的粒子：图片**开始收缩之后**，从残影**下缘及其后方**冒出来的一层细粒子；
 * 之后各自按自己的速度往下坠、往外散、逐个淡出。
 *
 * ## 依据（`发送端-1.mp4`，720×1584@60fps，逐帧量出来的）
 *
 *  · 图片静止到 **1.72s**；1.72→2.12s 收缩上移（轮廓底边 946→260，高度 386→157，收掉六成），
 *    同时褪色（平均饱和度 0.086 → 0.024），**2.12s 消失**；
 *  · 粒子 **2.00s 才零星出现**（"照片底部边缘出现少量白色光点"），2.05–2.10 密度冲到最高
 *    （"布满屏幕中上区域"），**2.12s 之后不再有新粒子**；
 *  · 拖尾方向是**向下坠**：视觉描述一致给出"粒子从照片下方散出、向下坠落、向屏幕中部蔓延"
 *    （2.05 / 2.10）、"进一步向下蔓延"（2.20）、"主要分布在屏幕中下方"（2.40）；
 *    像素中位数也 664 → 771 → 882 单调下移。**不是**"粒子全部往上飘"。
 *
 * ## 三条因此定下来的规则
 *
 *  1. **发射窗口跟着图片的真实运动走**（[EMIT_FROM]~[EMIT_TO]，单位是卡片运动进度）：
 *     图片还没开始收的时候一颗都不发；到 [EMIT_TO] 前后**彻底停发** —— 图片没了，就不再有个
 *     "固定在屏幕中心的发射器"继续喷，只剩已经生成的粒子自己漂。
 *     出生阈值 [P.birthU] 在 0..1 上均匀铺开，所以出生时间**摊在整个窗口里**，
 *     不会在某一帧一次性把粒子全建出来。
 *  2. **落点沿着图片走过的路**：每颗在**自己出生那一刻**从轨迹历史 `hist*` 里取当时的
 *     中心 / 下缘 / 半宽 / 高，而且往回看 [LAG_MAX_MS] 之内的某个时刻 —— 于是落点落在残影
 *     下缘**以及它刚刚走过的那段路**上，整朵云就是"图片路径的拖尾"，不是某一个定点。
 *  3. **每颗的运动各自独立**：自己的初速（纵向以向下为主，少数几乎不动或微升）、横向漂移、
 *     加速度、阻尼、寿命和透明度衰减指数；位置按**经过的真实时间**闭式求值
 *     `v0·(1−e^(−drag·t))/drag + ½at²` —— 既不是每帧重新随机，也没有逐帧抖动，
 *     于是"快的先散开、慢的慢慢挪、各自在不同时刻消失"。
 *
 * 时钟只有一条：[draw] 的 `timeMs` 定年龄（就是调用方那条时间轴），`progress`（卡片运动进度）
 * 定发射量 —— 没有任何逐阶段计时器。
 *
 * 调用方必须在画卡片**之前**调 [draw]：粒子在卡片下面，被卡片压住的那几颗自然被遮住。
 */
class SenderParticles {

    private class P(
        /** 出生阈值 0..1：`peakEmit` 涨过它就出生。均匀铺开 → 出生时间摊在整个发射窗口里。 */
        val birthU: Float,
        /** 横向落点 −1..+1（相对残影半宽）；|值| > 1 的少数几颗落在轮廓外一点点。 */
        val lane: Float,
        /** 在出生纵向区间里的位置 0..1（0 = 区间最上端，1 = 区间最下端），见 [SPAWN_BAND_FRAC]。 */
        val depth: Float,
        /** 落点时刻往回看多少毫秒（沿图片自己的轨迹往回取点，见 [LAG_MAX_MS]）。 */
        val lagMs: Float,
        /** 初速：横向 = viewW × 这个比例 /s，纵向 = viewH × 这个比例 /s（**正 = 向下**）。 */
        val vxFrac: Float,
        val vyFrac: Float,
        /** 加速度：同样是 viewW / viewH 的比例 /s²（纵向以"越坠越快"为主）。 */
        val axFrac: Float,
        val ayFrac: Float,
        /** 阻尼（1/s）：越大越早减速停住。 */
        val drag: Float,
        /** 寿命（ms）：到点就没了 —— 各颗寿命不同，所以不会一起消失。 */
        val lifeMs: Float,
        val size: Float,
        val alpha: Float,
        val twinkle: Float,
        val blue: Boolean,
        /** 横向落点抖动（−1..1，出生时一次性加上）——预留好的，绘制期不再摇随机数。 */
        val jx: Float
    ) {
        var born = false
        var spawnT = 0f
        var spawnX = 0f
        var spawnY = 0f
        /** 出生时那条纵向区间的**上边**（世界坐标）—— 之后整段行程都夹在它和下边之间。 */
        var bandTop = 0f
        /** 纵向运动的缩放（0..1]）：出生时按"跑完这条命会冲出多少"算出来，冲得多就压得多。 */
        var vScale = 1f
    }

    private companion object {
        /**
         * 上限颗数。出生按 [P.birthU] 摊开，所以任一时刻的可见数 ≈ 发射量 × COUNT。
         *
         * **这是这一层最直接的性能旋钮**：每颗粒子要画一次「实心圆 + 阴影光晕」，
         * 而光晕那一笔是**带模糊**的 —— 颗数直接决定每帧代价。120 会卡，所以降到 64；
         * 还想更轻就继续往下（48 / 32），想更密就往上加 —— 但往上加之前先看 [draw] 里
         * "光晕"那段注释：真要又密又不卡，得把阴影换成预渲染的光晕贴图。
         */
        const val COUNT = 64

        /**
         * 发射窗口，单位是**卡片运动进度**（0 = 松手那一刻，1 = 飞到顶 / 淡完）。
         *
         * **取 0.02：粒子和高亮同时起步**（高亮 [OverlayView.sendBlurP] 也是从 0 开始涨）。
         * 这里曾经是 0.45 —— 结果是高亮 68ms 就看得见，粒子却要 154ms 才生第一颗：中间空了
         * ~90ms"只有高亮、没有粒子"，而且"在冒"的过程只剩不到 0.15s，观感就是
         * "粒子太短、没感觉"。
         *
         * 和原片的关系：原片折算下来是"0.7 起"，远晚于这里 —— 所以这一条是**有意偏离原片**的，
         * 因为你要的是"确认发送那一刻高亮和粒子一起出现"。尾部仍严格对齐图片消失。
         */
        const val EMIT_FROM = 0.02f

        /**
         * 发射量涨到**满**的进度。必须明显早于 [EMIT_TO]。
         *
         * 踩过的坑：一开始让曲线一直爬到 [EMIT_TO] 才满，可是发射量是按帧算的 ——
         * 到 `pr >= EMIT_TO` 那一帧直接截到 0，曲线根本来不及爬到 1（实测最高只到 0.86），
         * 结果 120 颗里有 17 颗永远不出生。留出这段余量之后，最后一帧就已经发满了。
         */
        const val EMIT_FULL = 0.88f

        /** 图片消失的进度：到这儿就**彻底停发**，只剩已经生成的粒子自己漂。 */
        const val EMIT_TO = 0.96f

        /**
         * 发射量曲线的指数：`发射量 = p^EMIT_CURVE`。
         *
         * 1.8 保住"先少量、后迅速增加"的形状，但不像 2.2 那么空：p=0.25 时 8%、p=0.5 时 29%、
         * p=0.8 时 67%、p=1 才满 —— 起点挪到 [EMIT_FROM] 之后，一开始就有零星几颗先冒出来，
         * 之后整趟飞行都在持续补，而不是"前半程没有、后半程一坨"。
         */
        const val EMIT_CURVE = 1.8f

        /**
         * 出生纵向区间的**总高**，以**原始卡片宽度**为尺：`区间高 = 原始卡宽 × 0.6`。
         * 我们的卡片是 292×360（设计px），所以 0.6×292 ≈ 175 设计px ≈ **卡高的 49%**，
         * 大约就是"卡片下半部"那么高（原来是 0.34×卡高 ≈ 122px，全部在卡内）。
         */
        const val SPAWN_BAND_FRAC = 0.6f

        /**
         * 这个区间里有多大比例落在**卡片下缘以上**（卡内）：
         *   0.5 → 以下缘为中心，上下各 0.3W（当前选择）
         *   1.0 → 整个区间都在卡内（卡外一颗都不生）
         *   0.0 → 整个区间都在卡外（全部落在背景上）
         * 注意：粒子层画在卡片**下面**，所以落在卡内的那一半会被卡片压住，
         * 要等卡片缩小/飞走才露出来 —— 想让它们一开始就可见就把这个数往小调。
         */
        const val SPAWN_ABOVE_SHARE = 0.5f

        /** 落在轮廓外的比例（"及其附近"）：这几颗的横向落点放在 1.0~1.25 倍半宽处。 */
        const val OUTSIDE_SHARE = 0.12f

        /**
         * 落点往回看的上限（ms）：沿图片轨迹往回取点，于是粒子落在**图片刚走过的那段路**上，
         * 而不是全贴在当前下缘。这是"拖尾留在图片后方及下方"的主要来源 ——
         * 调大 → 尾巴更长、云更靠下；调小 → 粒子更贴着图片本身。
         */
        const val LAG_MAX_MS = 150f

        /**
         * 纵向初速（× viewH / s）：绝大多数向下坠，少数几乎不动甚至微升。
         *
         * 原片那颗图片是**向上射出去**的，粒子留在原地、再往下沉 —— 所以拖尾才在后面和下方。
         * 调大 → 坠得更快、拖尾更"垂"；调到 0 附近就变成"悬在原地慢慢散"。
         */
        const val VY_MIN = -0.06f
        const val VY_SPAN = 0.90f

        /** 横向初速（× viewW / s）：左右都有，展开成扇形而不是一条直线。 */
        const val VX_SPAN = 0.17f

        /** 加速度（× viewH / s²、× viewW / s²）：纵向以"越坠越快"为主。 */
        const val AY_MIN = -0.05f
        const val AY_SPAN = 0.40f
        const val AX_SPAN = 0.12f

        /** 阻尼（1/s）：把"逐渐减速"做进去，不同粒子停下来的时间差很多。 */
        const val DRAG_MIN = 0.35f
        const val DRAG_SPAN = 2.10f

        /**
         * 寿命（ms）：一颗粒子在屏幕上"看得见"多久。
         *
         * 取 230~560，**按出场顺序线性递增**（`LIFE_MIN + birthU × LIFE_SPAN`）：最早出生的活得
         * 最短、最晚出生的活得最长 —— 于是"死亡时刻 = 出生时刻 + 寿命"严格单调，**先出现的先
         * 消失**，和接收端那条规则一致（接收端由 [ReceiverRenderer.PARTICLE_DEATH_MS] 保证）。
         *
         * 原来是纯随机寿命（`rand^1.4` 偏短）：早出生的那颗完全可能比晚出生的后消失，和
         * "先出现的先消失"对不上，已改掉。均值没变 —— `birthU` 在 0..1 均匀，平均仍是
         * 230 + 165 = 395ms，所以同时挂在屏上的颗数（这一层最贵的那笔"实心圆 + 阴影光晕"）
         * 不会因此变多；变的只是同屏那批**哪些先退场**。
         *
         * 和原片对表（2.00s 出第一批 → 2.12s 停发 → 2.40~2.50s 基本散尽）：单颗最长 ≈0.4~0.5s，
         * 整层跨度 ≈0.5s。这里最长一颗 0.56s、整层跨度 ≈ 最晚出生（≈0.88 × 飞行时长）+ 最大
         * 寿命 ≈ 0.65s，比原片略长一点 —— 想调就动这两个数。
         */
        const val LIFE_MIN_MS = 230f
        const val LIFE_SPAN_MS = 330f

        /** 粒子整体尺寸系数。 */
        const val SIZE_SCALE = 0.62f

        /** v6 那套尺寸都是 CSS px，换算到设计 px 的系数（和接收端同一个值）。 */
        const val CSS_TO_DP = 380f / 291f

        /** 和 v6 完全同一条 LCG 的种子。 */
        const val SEED = 5815

        /** 轨迹历史长度（帧）。60fps 下 20 帧 ≈ 330ms，比 [LAG_MAX_MS] 长，够用。 */
        const val HIST = 20
    }

    private val dots = ArrayList<P>(COUNT)
    private var seed = SEED

    /**
     * 两枚常驻画笔（蓝 / 白），代替"每颗粒子 reset 一遍再重设 shadowLayer"。
     *
     * `setShadowLayer` 的参数只跟发射量和 k 有关，一帧配一次就够；每颗粒子只改 alpha
     * —— 于是每帧的 shadow 层重建从"颗数 × 2 次"降到 2 次。
     */
    private val dotBlue = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotWhite = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * 这一轮"发射到过的最高水位"。
     *
     * 只升不降是关键：图片消失后发射量会掉回 0，但已经冒出来的粒子不能跟着被撤掉 ——
     * 它们要按自己的寿命慢慢散。所以"谁该出生"看高水位，不看当前值。
     */
    private var peakEmit = 0f

    /** 上一帧的时钟：用来发现"时间倒流"（时间轴重来 = 新的一轮）。 */
    private var lastTime = -1f

    // ── 图片的轨迹历史：每帧记一次"当时的中心 x / 下边沿 / 半宽 / 高" ──
    // 用普通数组 + 前移，HIST 只有 20，一帧 20 个 float 的拷贝可以忽略；换来的是
    // 取样时能直接顺序查找，不必绕环形下标。
    private val histT = FloatArray(HIST)
    private val histX = FloatArray(HIST)
    private val histY = FloatArray(HIST)
    private val histHW = FloatArray(HIST)
    private val histHH = FloatArray(HIST)
    private var histN = 0

    // sampleAt 的结果（避免每颗粒子都建对象）
    private var sX = 0f
    private var sY = 0f
    private var sHW = 0f
    private var sHH = 0f

    init {
        build()
    }

    /** 和 v6 同一条 LCG；Kotlin 的 Int 运算本身就回绕，等价于 JS 的 `>>> 0`。 */
    private fun rand(): Float {
        seed = seed * 1664525 + 1013904223
        return (seed.toLong() and 0xFFFFFFFFL) / 4294967296f
    }

    /** 一次性把每颗粒子的"私事"掷好：出生阈值、落点、初速、加速度、阻尼、寿命、衰减曲线。 */
    private fun build() {
        dots.clear()
        for (i in 0 until COUNT) {
            // 出生阈值：把 [0,1] 均匀铺满，每颗再抖一点点 —— 出生时间就摊在整个窗口里了
            val birthU = ((i + rand() * 0.85f) / COUNT).coerceIn(0f, 1f)

            // 横向落点：多数在残影宽度内，少数落到轮廓外一点点（"及其附近"）
            val lane = if (rand() < OUTSIDE_SHARE) {
                (if (rand() < 0.5f) -1f else 1f) * (1f + rand() * 0.25f)
            } else {
                rand() * 2f - 1f
            }
            // 沿图片轨迹往回看的时长：多数离当前很近，少数回到更早
            val lagMs = rand().pow(1.4f) * LAG_MAX_MS
            // 在出生区间里的位置 0..1（0 = 区间最上端、1 = 最下端，见 [SPAWN_BAND_FRAC]）
            val depth = rand()

            val vxFrac = (rand() * 2f - 1f) * VX_SPAN
            val vyFrac = VY_MIN + rand() * VY_SPAN
            val axFrac = (rand() * 2f - 1f) * AX_SPAN
            val ayFrac = AY_MIN + rand() * AY_SPAN
            val drag = DRAG_MIN + rand() * DRAG_SPAN
            // 寿命**随出场顺序递增**：`birthU` 越小越早出生，寿命也越短 —— 于是
            // "死亡时刻 = 出生时刻 + 寿命"严格单调，**先出现的先消失**。
            // （原来是纯随机寿命，早出生的那颗完全可能后消失，和接收端对不上。）
            val lifeMs = LIFE_MIN_MS + birthU * LIFE_SPAN_MS
            val size = 0.65f + rand() * 1.9f
            val alpha = 0.38f + rand() * 0.62f
            val twinkle = rand() * (2f * PI.toFloat())
            val blue = rand() < 0.32f
            val jx = rand() * 2f - 1f

            dots.add(
                P(
                    birthU, lane, depth, lagMs, vxFrac, vyFrac, axFrac, ayFrac,
                    drag, lifeMs, size, alpha, twinkle, blue, jx
                )
            )
        }
        // 按出生阈值排序：扫到第一颗"还没到阈值"的就可以停，后面的阈值只会更大
        dots.sortBy { it.birthU }
    }

    /** 记录一帧轨迹。 */
    private fun pushSample(t: Float, cx: Float, bottom: Float, halfW: Float, h: Float) {
        if (histN == HIST) {
            for (i in 1 until HIST) {
                histT[i - 1] = histT[i]
                histX[i - 1] = histX[i]
                histY[i - 1] = histY[i]
                histHW[i - 1] = histHW[i]
                histHH[i - 1] = histHH[i]
            }
            histN = HIST - 1
        }
        histT[histN] = t
        histX[histN] = cx
        histY[histN] = bottom
        histHW[histN] = halfW
        histHH[histN] = h
        histN++
    }

    /**
     * 取"**时刻 t 时那张图片**的状态"（线性插值），写进 `sX / sY / sHW / sHH`。
     *
     * 比最老的一帧还早（或还没有历史）就用最老那一帧；比最新还新就用最新那一帧。
     */
    private fun sampleAt(t: Float) {
        if (histN <= 0) {
            sX = 0f; sY = 0f; sHW = 0f; sHH = 0f
            return
        }
        val last = histN - 1
        if (t <= histT[0]) {
            sX = histX[0]; sY = histY[0]; sHW = histHW[0]; sHH = histHH[0]
            return
        }
        if (t >= histT[last]) {
            sX = histX[last]; sY = histY[last]; sHW = histHW[last]; sHH = histHH[last]
            return
        }
        for (i in 1..last) {
            if (histT[i] >= t) {
                val span = (histT[i] - histT[i - 1]).coerceAtLeast(0.001f)
                val u = ((t - histT[i - 1]) / span).coerceIn(0f, 1f)
                sX = lerp(histX[i - 1], histX[i], u)
                sY = lerp(histY[i - 1], histY[i], u)
                sHW = lerp(histHW[i - 1], histHW[i], u)
                sHH = lerp(histHH[i - 1], histHH[i], u)
                return
            }
        }
        sX = histX[last]; sY = histY[last]; sHW = histHW[last]; sHH = histHH[last]
    }

    /** 清干净：高水位归零、轨迹清空、所有粒子退回未出生。 */
    private fun resetAll() {
        peakEmit = 0f
        histN = 0
        for (p in dots) p.born = false
    }

    /**
     * 画一遍。坐标全是**本机 px**，和卡片同一个坐标系（调用方还没叠加卡片自己的位移/缩放）。
     *
     * @param timeMs    动画时钟（ms）—— **唯一**的时间基准：粒子的年龄、摇摆都由它算
     * @param progress  图片的运动进度 0..1（松手后的飞行进度；还没松手传 0）。
     *                  发射窗口就是它上面的 [EMIT_FROM]~[EMIT_TO] 这一段
     * @param centerX   图片**当前**中心 x
     * @param bottom    图片**当前**轮廓下边沿 —— 新粒子就从这一带冒出来
     * @param halfWidth 图片**当前**半宽（跟着收缩一起变，不是静止时的宽度）
     * @param height    图片**当前**高度
     * @param alpha     整层可见度。**不能喂卡片那枚 alpha**：卡片收尾会淡到 0，
     *                  粒子却要活到卡片消失**之后**（调用方喂的是入场那一记淡入）
     */
    fun draw(
        c: Canvas,
        timeMs: Float,
        progress: Float,
        centerX: Float,
        bottom: Float,
        halfWidth: Float,
        height: Float,
        /** **原始卡片宽度**（未变形、未缩放的那张卡）：出生纵向区间以它为尺，见 [SPAWN_BAND_FRAC]。 */
        staticWidth: Float,
        alpha: Float,
        viewW: Float,
        viewH: Float,
        k: Float
    ) {
        // 时钟倒流 = 时间轴重来了一轮（发送流程复位）：全清，下一轮从头长
        if (lastTime >= 0f && timeMs < lastTime) resetAll()
        lastTime = timeMs

        // 整层看不见（面板还没进场 / 已经收尾）：不算也不画，状态留着
        if (alpha <= 0.004f) return

        // 记一帧轨迹：粒子出生时要按"自己那一刻"的图片状态落点
        pushSample(timeMs, centerX, bottom, halfWidth, height)

        // 发射量：图片开始收缩之后才起，涨满之后再走一小段就**彻底停发**
        // （图片没了，就不再有个"固定在屏幕中心的发射器"继续喷）
        val pr = progress.coerceIn(0f, 1f)
        val p = ((pr - EMIT_FROM) / (EMIT_FULL - EMIT_FROM)).coerceIn(0f, 1f)
        val emit = if (pr >= EMIT_TO) 0f else p.pow(EMIT_CURVE)
        if (emit > peakEmit) peakEmit = emit
        if (peakEmit <= 0.004f) return

        val t = timeMs / 1000f * 3.1f
        val sway = viewW * 0.004f
        var alive = 0

        // 画笔一帧配置一次：颜色固定，阴影半径只跟发射量走
        val glow = peakEmit > 0.1f
        dotBlue.reset()
        dotBlue.isAntiAlias = true
        dotBlue.color = 0xFFDCE8FF.toInt()
        dotWhite.reset()
        dotWhite.isAntiAlias = true
        dotWhite.color = Color.WHITE
        if (glow) {
            val blur = (2f + peakEmit * 4f) * SIZE_SCALE * CSS_TO_DP * k
            dotBlue.setShadowLayer(blur, 0f, 0f, 0xE0B4CDFF.toInt())
            dotWhite.setShadowLayer(blur, 0f, 0f, 0xE0FFFFFF.toInt())
        }

        // 出生区间的总高：**原始卡宽 × [SPAWN_BAND_FRAC]**（每帧只算一次，循环里复用）
        val bandH = staticWidth * SPAWN_BAND_FRAC

        for (dot in dots) {
            // ── 出生：发射量涨过它自己的阈值那一刻 ──
            if (!dot.born) {
                if (peakEmit < dot.birthU) break   // 排过序，后面的阈值只会更大
                dot.born = true
                dot.spawnT = timeMs
                // 落点取"出生时刻往前最多 [LAG_MAX_MS] 的那一瞬间"图片的下缘附近
                sampleAt(timeMs - dot.lagMs)
                dot.spawnX = sX + dot.lane * sHW + dot.jx * sHW * 0.06f
                // 出生纵向区间：总高 = 原始卡宽 × [SPAWN_BAND_FRAC]，
                // 其中 [SPAWN_ABOVE_SHARE] 落在卡片下缘**以上**、其余在**以下**（0.5 = 上下各 0.3W）。
                // **整段行程都留在这条区间里**：上边记下来，并顺手算一个纵向缩放。
                dot.bandTop = sY - bandH * SPAWN_ABOVE_SHARE
                dot.spawnY = dot.bandTop + dot.depth * bandH

                // ── 运动预算：这颗跑完一辈子会冲出区间多少，冲得多就按比例压多少 ──
                // 不压的话最快那颗纵向要跑 ≈1027px（vy 0.84·viewH·0.509 + ½·ay 0.35·viewH·0.56²），
                // 而区间总高才 ≈478px —— 一颗就能跑出两倍远。上下两侧分别算，取最紧的那个比例。
                // 只用**这颗自己的**寿命/阻尼/初速/加速度，所以是闭式、一次性算完，不逐帧抖。
                val tSec = dot.lifeMs / 1000f
                val decayAll = (1f - exp(-dot.drag * tSec)) / dot.drag
                val downDist = maxOf(0f, dot.vyFrac) * viewH * decayAll +
                        maxOf(0f, dot.ayFrac) * 0.5f * viewH * tSec * tSec
                val upDist = maxOf(0f, -dot.vyFrac) * viewH * decayAll +
                        maxOf(0f, -dot.ayFrac) * 0.5f * viewH * tSec * tSec
                val roomDown = bandH * (1f - dot.depth)
                val roomUp = bandH * dot.depth
                var sc = 1f
                if (downDist > roomDown && downDist > 0f) sc = minOf(sc, roomDown / downDist)
                if (upDist > roomUp && upDist > 0f) sc = minOf(sc, roomUp / upDist)
                dot.vScale = sc
            }

            val age = timeMs - dot.spawnT
            if (age < 0f || age >= dot.lifeMs) continue
            alive++

            // ── 位置：按经过的**真实时间**闭式求值（带阻尼的一阶运动 + 常数加速度）──
            // 同一颗粒子在不同帧只由 age 决定，所以既不会每帧重新随机，也不会逐帧抖动。
            val sec = age / 1000f
            val decay = (1f - exp(-dot.drag * sec)) / dot.drag
            val x = dot.spawnX + dot.vxFrac * viewW * decay +
                    0.5f * dot.axFrac * viewW * sec * sec +
                    sin(t + dot.twinkle) * sway          // 轻微不规则漂移（平滑、非随机）
            // 纵向：按出生时算好的比例缩放（平滑主力），最后再夹一次区间 ——
            // 夹是**硬保证**，只在摆动/边界这类极端情形兜底；因为缩放已经把绝大多数压在区间内，
            // 正常不会碰到边界，也就不会在边线上堆出一条"停住的点"。
            val y = (dot.spawnY + dot.vScale * (dot.vyFrac * viewH * decay +
                    0.5f * dot.ayFrac * viewH * sec * sec))
                    .coerceIn(dot.bandTop, dot.bandTop + bandH)

            // ── 透明度：曲线形状照抄参考片那条 `1 − smooth(0.40, 1, ·)`（现在两端都是它）──
            //   接收端按"每颗自己的退场时刻"算同一个形状，这里用**这颗自己的寿命进度** u。
            //   形状的含义：前 40% 的寿命**一点不变暗**，之后才快速退场 —— 就是"亮着、然后消失"。
            //   后面那个 smooth(0, 0.12, u) 是每颗自己的**出生淡入**，免得"啪"地冒出来（接收端的
            //   出生淡入是整层的 intro，在这里没有对应物，所以留在单颗上）。
            val u = (age / dot.lifeMs).coerceIn(0f, 1f)
            val fade = (1f - smooth(0.40f, 1f, u)) * smooth(0f, 0.12f, u)
            // 逐项对齐接收端： p.alpha · (0.36 + 0.64·进度) · 淡出 · 整层 alpha
            //   · 系数 0.36/0.64 和接收端一模一样（不再自己发明 0.55/0.45）；
            //   · **去掉了 intro** —— 接收端那枚 intro 吃的是"卡片入场以来的时间"，粒子出现时
            //     早已恒为 1，它其实是整层淡入；这里曾误接到 peakEmit 上，于是把最早出生的几颗
            //     按到 0.7%~8% 不透明度（等于没画）。单颗的出生淡入上面那行负责。
            val opacity = dot.alpha * (0.36f + 0.64f * peakEmit) * fade * alpha
            if (opacity <= 0.01f) continue

            // ── 尺寸：**字面照抄接收端** [ReceiverRenderer.drawParticles] ──
            //   接收端：(0.65 + size·(0.56+0.48·降落)) · (1−0.42·消散) · (1 + 0.35·降落 − 0.22·消散)
            //   这里  ：同一条式子，只把"降落"→ [peakEmit]（发射进度）、"消散"→ u（这颗自己的
            //          寿命进度：发送端没有"整团一起散"那一段，粒子是各死各的）。
            //   以前少了 (1+0.35·发射进度) 这一项 —— 发射满时半径只有接收端的 1/1.35 ≈ 74%，
            //   而**光晕半径两边是一样的**：小核心 + 大光晕，看上去就是"颜色发浅"。
            val px = ((0.65f + dot.size * (0.56f + 0.48f * peakEmit)) * (1f - 0.42f * u) *
                    (1f + 0.35f * peakEmit - 0.22f * u) * SIZE_SCALE * CSS_TO_DP * k).coerceAtLeast(1f)

            // 光晕照旧（原片那条 box-shadow，和接收端一致）：只是不再每颗重设一遍画笔。
            // 这一笔「圆 + 模糊光晕」是整层最贵的操作，颗数就是靠它换来的 —— 见 [COUNT]。
            val pen = if (dot.blue) dotBlue else dotWhite
            pen.alpha = (opacity.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
            c.drawCircle(x, y, px / 2f, pen)
        }

        if (glow) {
            dotBlue.clearShadowLayer()
            dotWhite.clearShadowLayer()
        }

        // 停发之后、已有的又全散尽了 → 重新武装，交给下一轮
        if (emit <= 0.004f && alive == 0) resetAll()
    }

    private fun lerp(a: Float, b: Float, v: Float) = a + (b - a) * v.coerceIn(0f, 1f)

    private fun smooth(a: Float, b: Float, v: Float): Float {
        if (b <= a) return if (v <= a) 0f else 1f
        val p = ((v - a) / (b - a)).coerceIn(0f, 1f)
        return p * p * (3f - 2f * p)
    }
}
