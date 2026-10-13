package com.pri.nfctouchdemo.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.View
import android.view.animation.LinearInterpolator
import com.pri.nfctouchdemo.R
import com.pri.nfctouchdemo.anim.Timeline
import com.pri.nfctouchdemo.gfx.PhotoArtwork
import com.pri.nfctouchdemo.gfx.PhotoTint
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 一碰互联动效的所有 2D 图层，对应原型里除 WebGL 画布以外的全部 CSS 结构：
 *
 * 1. 四边白色线条    —— 原型 .ambient-breathing-glow / @keyframes breatheGlow；
 *    这里换成白色线条：显形由波前驱动，之后靠**线宽**做阻尼呼吸（亮度恒定不动）
 * 2. 屏幕边缘点亮    —— .edge-frame / edgeIgnite + edgePulse
 * 3. 发送面板        —— .transfer-panel（深色遮罩 + 接收方信息 + 照片卡片 + 上滑提示 + 品牌）
 *
 * 原型里的 .light-sweep（光带扫过，z-index 18）没有移植：OPPO 参考录屏的动画段里
 * 找不到这条从左到右的亮带，它更像是原型自己加的氛围光，不是一碰互联的一部分。
 * 原型里的 .toast-msg（发送结果提示，居中那枚「已发送」药丸）也去掉了 —— 按需求不要它：
 * 卡片飞走之后直接复位时间轴、重播入场，中间不再有任何提示。
 *
 * 另外接管了照片卡片的上滑手势：拖动跟手、松手回弹、超过阈值飞走并复位。
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private companion object {
        /** 设计基准宽度，对应原型里 380px 宽的手机屏。 */
        const val DESIGN_W = 380f
        const val CARD_W = 292f
        const val CARD_H = 360f

        const val EDGE_IGNITE_MS = 650f
        const val PANEL_TEXT_MS = 420f
        const val CARD_DELAY_MS = 100f
        const val CARD_ENTER_MS = 680f
        const val CARD_FADE_MS = 480f
        // ---- 边缘辉光：两层 ----
        // 1 核心高光线：**呼吸就在这条线上** —— 铺满之后粗细缓慢起伏（[LINE_THIN_PX] + [LINE_SWING_PX]），
        //   而且沿边**逐点不同步**（见 [LINE_JITTER_BLOCKS]）。
        // 2 内侧辉光：宽度和 alpha **全程恒定**，不参与呼吸；**离白线越远越浅**
        //   （一条平滑曲线铺满整圈，见 [GLOW_STOPS] / [GLOW_COLORS]）。
        // 单位是设计宽度 380px 下的 px，绘制时乘 k。
        //
        // 白线**不贴着屏幕边缘**：它内缩 [LINE_INSET_PX]，两侧都有辉光 ——
        // 屏幕边缘 → 外侧辉光 → 白线（正好压在辉光的脊线上）→ 内侧辉光。

        /**
         * 核心高光线的**中线**离屏幕边缘的距离（设计 px ≈ dp）。
         *
         * **1~2dp**：白线就贴在屏幕边缘内侧一点点，它和屏幕边缘之间那一小条就是
         * "贴着屏幕的外侧辉光"（见 [glowProfile] 里 0 → 这个值 的那半个剖面）——
         * 所以看上去是"屏幕 → 一层薄唇光 → 白线"，白线并不是压在屏幕边上、
         * 也不是离得很远。给大了那条贴着屏幕的唇光就会变宽，白线会显得"缩进来"。
         *
         * 改这个值，辉光脊线会跟着线一起挪；角上同理，线的圆角半径取 [cornerR] − 内缩，
         * 于是**四个角到屏幕边缘的距离和直边一样**。
         *
         * 2 → **2.4**（≈2.3dp，仍在"1~2dp 左右"）：按需求"最大宽度再宽一点"，
         * 而这个值**同时就是线宽的硬上限** —— 线体对称偏在中线两侧，`w/2` 不能超过它，
         * 否则外沿会伸出屏幕、被边缘切平（见 [LINE_SWING_PX] 里那条约束）。
         * 所以"线更粗"这件事只能靠"中线往里挪"换来：`最大宽度 = 2 × 这个值`，
         * 2.4 时是 4.8 设计 px（原来 2.0 时是 4.0）。
         */
        const val LINE_INSET_PX = 2.4f

        /**
         * 白线**逐点宽度**的采样步长（设计 px，沿中线量）。
         *
         * 线宽沿边在变（见 [LINE_JITTER_BLOCKS]），而描边做不到变宽，
         * 所以线体是**按中线两侧各偏 w/2 拼出来的闭合填充路径**：
         * 采样越密越平滑，代价是每帧的 `lineTo` 次数（6 设计 px @720 ≈ 11px ≈ 400 点）。
         */
        const val LINE_RIBBON_STEP_PX = 6f

        /**
         * 白线抖动沿整圈的**控制点数**（一头一尾相接，所以是环形噪声）。
         *
         * 10 个控制点绕整圈 = 每条边摊到 2~3 个，于是**同一条边内部**也各段不同步：
         * 有的地方先鼓、有的地方鼓得多（幅度下限见 [LINE_JITTER_AMP_FLOOR]）。
         * 给太少（如 4）就变回"整条边一起胀"；给太多就成锯齿。
         */
        const val LINE_JITTER_BLOCKS = 10

        /**
         * 逐点相位散布（**周期**占比）：±这个值，所以相邻控制点最多错开约 0.16 个周期
         * （4800ms × 0.16 ≈ 770ms）。给大了会变成"波动沿线跑"，那就不是呼吸了。
         */
        const val LINE_JITTER_PHASE = 0.08f

        /**
         * 逐点幅度散布的**下限**：每个采样点的幅度在 `[下限, 1]` 之间散开。
         *
         * 0.55 —— 关键在于**下限不是 0**：早先给的是 `[0, 1]`，于是约四分之一的位置幅度正好为 0
         * （那几个位置**一直不动**），其余位置平均也只有半个幅度 —— 整圈读起来像"静态的不规则"，
         * 而不是在呼吸，用户反馈"呼吸变化不够明显"就是它。
         * 现在每个位置都在呼吸，只是**有的鼓得多、有的鼓得少**（1.8:1 的差别还在，"逐点不同"没丢）。
         * 给 1 = 整圈完全同步（最"整齐"的呼吸，但也就没了沿边的细节）；
         * 给 0 = 又会退回到"一块一块冻住"的老问题。
         */
        const val LINE_JITTER_AMP_FLOOR = 0.55f

        // 白线逐点抖动的两串噪声种子：相位一串、幅度一串（同一串会同步，那就白抖了）。
        const val SEED_LINE_PHASE = 21
        const val SEED_LINE_AMP = 22

        /**
         * 核心高光线**最细那一档**的宽度 —— 白线刚显形、还没开始呼吸时就是这个宽度。
         *
         * 1.2 → 1.8 → 2.0 → 现在 **2.6**（@720 约 4.9px）。它是呼吸的下限：白线先以这个宽度
         * （"最浅的状态"）跟着波前铺满整圈，铺满之后才靠 [LINE_SWING_PX] 一下一下变粗。
         *
         * 抬下限的同时 [LINE_SWING_PX] 从 1.4 收到 1.1 —— 最**粗**那一档仍然钉在 4.8
         * （几何上限，见那边的推导），也就是抬的是下限、不是整条线。
         * 想让整条线一起变粗，要动 [LINE_INSET_PX]（代价是贴着屏幕那条唇光会跟着变宽）。
         */
        const val LINE_THIN_PX = 2.6f

        /**
         * 核心线变粗的幅度（设计 px）：**呼吸改的就是这个量**。
         *
         * 宽度 = [LINE_THIN_PX] + 渐入 × 幅度 × (1 + 果冻)，果冻极值是 +1.0 / −0.66
         * （见 [breath]），逐点再乘一个 `[LINE_JITTER_AMP_FLOOR, 1]` 的幅度系数
         * （见 [lineJellyAt]），所以铺满之后线宽在
         * `2.6 + 1.1 × [0.34, 2.0]` = **2.97 ~ 4.8 设计 px**（@720 约 5.6 ~ 9.1px）之间走 ——
         * 鼓比缩明显。渐入见 [LINE_SWING_IN_MS]：四边铺满之前这个量一点不起作用。
         *
         * **上限是几何定死的**：线体沿法线两侧各偏 w/2，而中线只离屏幕边缘
         * [LINE_INSET_PX]，所以 `(LINE_THIN_PX + 2 × 幅度) / 2 ≤ LINE_INSET_PX`
         * → 内缩 2.4 时**幅度 ≤ 1.1**（[LINE_THIN_PX] 从 2.0 抬到 2.6 之后，这里就从 1.4
         * 收到了 1.1，最粗那一档不动）。再大就要么把内缩调大（贴着屏幕那条唇光会跟着变宽），
         * 要么让线被屏幕边缘切掉。想让呼吸更夸张，先动 [LINE_THIN_PX]（变细 = 相对幅度更大），
         * 别动这里。
         */
        const val LINE_SWING_PX = 1.1f

        /**
         * 线宽呼吸的渐入时长（毫秒），从"四边刚好铺满"（= [Timeline.EDGE_START_MS] +
         * [Timeline.EDGE_REVEAL_MS] = 1100ms）那一刻开始算。
         *
         * 需求是"白线刚出来时处于最浅的状态，完全显示之后才开始随呼吸变粗"：
         * 所以显形那 600ms 里线宽恒为 [LINE_THIN_PX]，之后 600ms 渐入到满幅（1100 → 1700ms）。
         */
        const val LINE_SWING_IN_MS = 600f

        /**
         * 线宽硬下限（兜底）：任何一层抖动把该点宽度压到这个值以下时，就按这个值画。
         *
         * 0.6 → **1.2**：0.6 设计 px 在 @720 上只有约 1.1px，真触发时会细成一根发丝，
         * 和两侧辉光一起读成"线断了"。正常路径到不了这里（最细一档 [LINE_THIN_PX] = 2.6，
         * 果冻最负也只到 2.97），它只是防振荡的保险。
         */
        const val LINE_MIN_PX = 1.2f

        /**
         * 核心高光线的 alpha：全程恒定，不参与呼吸（"亮度稳定"）。
         *
         * 0.84 是刻意压下来的：核心线太亮时整圈会读成"白色边框"，
         * 而不是"玻璃边缘向内散射的光"。
         *
         * 按需求连着抬了两档：0.84 → 0.92 → **0.96**。辉光峰值抬到 0.53
         * （脊线上原来两层叠出来的那点亮度）之后，线压得太低会在顶部那枚光斑下面"糊"进去；
         * 抬到 0.96 时线和它下面那一片亮光仍然分得开，整条线也白得很干净。
         * **这一档基本到头了**（1.0 就是纯白，再往上没有空间）—— 还想让边缘更"跳"，
         * 应该动辉光的 [GLOW_PEAK_ALPHA]，而不是把线推到 1.0（那会读成一圈硬描边）。
         */
        const val LINE_ALPHA = 0.96f

        // ---- 两层边缘辉光 ----
        // 1 核心高光线：一条闭合的圆角矩形路径、一次画完（见 drawEdgeStroke），
        //   逐段显形由波前遮罩逐像素完成 —— 没有段，也就没有接缝。
        // 2 内侧辉光：见下面 GLOW_* 那几张表。
        //
        // 之前这里是"核心线 + 一层柔光 + 边缘点亮自带的描边与多层内发光"，
        // 三层都是白的、又落在同一条路径上，最边上合起来接近 1.0 —— 必然过曝，
        // 看上去就是一圈细白描边，而不是玻璃辉光。

        /**
         * 光晕层数（不含核心线）：**现在只有 0 = 内侧辉光**。
         *
         * 曾经是两层（0 内侧辉光 + 1 外围散射）：外围那层更宽更淡，负责"光散得更远"。
         * 按需求删掉了，宽度并进内侧这一层（[GLOW_DEPTH_PX] 从 `12` 变成 `24`，
         * 也就是原来外围那层的铺开范围），上下左右**完全一样**，整圈只有一层辉光：
         * 离白线越远越浅（见 [GLOW_STOPS]）。表还是按层索引的，想再加一层就是往
         * `GLOW_*` 这几张表各补一项。
         */
        const val GLOW_LAYERS = 1
        const val GLOW_NEAR = 0

        /**
         * 各层**往屏幕内**的深度（设计 px ≈ dp，@720 宽约 ×1.9）—— 从白线那条脊线量起。
         * **四条边同一个值**（上下左右统一）。
         *
         * 收窄过两次：原来 `20 / 72`，在 1080 宽屏上外圈会铺到 **205px**（照片边缘被白白洗掉
         * 一大条，"图没占满屏"的感觉）；后来 `12 / 40`。中间试过"左右比上下窄 40%"
         * （`GLOW_SIDE_SCALE`），竖边上确实显窄了，但整圈的光晕宽窄不一，读起来像没对齐 ——
         * 按需求**上下左右统一**，那条按边给系数的分支已经删掉。
         *
         * `12` → `24`（"删掉外围散射之后把宽度加给内侧辉光"）→ **回到 `12`**
         * （按需求"内侧辉光全部砍一半的宽度"）：原来 `12`（内侧，亮）+ `24`（外围，淡）
         * 两层叠着铺到 24；外围删掉后一层先接管了整个 24，现在再对半砍 ——
         * 整圈总宽度是 `2.4 + 12` 设计 px ≈ **27px @720**（四条边一样），
         * 剖面形状（[GLOW_STOPS] 那串比例）和颜色一点没动，只是整条曲线被压窄了一半。
         *
         * 注意口径：这是白线**往里**那一段；白线**外侧**（屏幕边缘 → 白线）只有
         * [LINE_INSET_PX] = 2.4（≈2.3dp），那是"贴着屏幕的那层薄唇光"。这一层在屏幕上铺的总宽度
         * 就是 `LINE_INSET_PX + 这里的值`（@720 ≈4.5px + 23px）。
         */
        val GLOW_DEPTH_PX = floatArrayOf(12f)

        /**
         * 各层**外侧辉光**在屏幕边缘处的强度（相对该层峰值）。
         *
         * 剖面以白线（深度 = [LINE_INSET_PX]）为脊：往外线性降到这个值（屏幕边缘那一线），
         * 往内按 [GLOW_STOPS] 衰减。1 = 外内侧一样亮（看着像整条带子），
         * 0.5~0.6 能读出"光是从线上散出去的、往外只是漏出来一点"。
         *
         * 0.55 / 0.62 → 0.66 / 0.72 → **0.74**（按需求"亮一点"、"内侧辉光再白再亮一点"）：
         * 贴着屏幕那条唇光原来只有峰值的一半多一点，整圈看起来像"内亮外忽然断掉"；
         * 抬上来之后边缘那一线也跟着发光，整圈是一整片连续的白。
         * （外围散射删掉之后只剩这一项，取的是原来内侧那一档。）
         */
        val GLOW_EDGE_ALPHA = floatArrayOf(0.74f)

        /**
         * 各层峰值 alpha：辉光最亮的地方，就在白线那条脊线上。
         *
         * 0.24 / 0.15 → 0.32 / 0.21 → 0.40 / 0.21（两层时代）→ **0.53**（只有一层了）：
         * 两层时代脊线上的**合成**亮度是 `1−(1−0.40)(1−0.21) ≈ 0.53`，现在一层要把这个数
         * 自己扛下来（贴屏幕那一线也基本没变：`0.74 × 0.53 ≈ 0.39`，原来两层叠出来是 0.40）。
         * 白线 0.96 压在上面读起来仍然是一条清楚的线（差约 0.43）。
         */
        val GLOW_PEAK_ALPHA = floatArrayOf(0.53f)

        /**
         * 各层 alpha 沿**内侧深度**的分布（0 = 白线那条脊线，1 = 该层最深处）。
         *
         * 只有一层，所以这就是**整圈辉光的衰减曲线**：从白线上开始、离白线越远越浅，
         * 一路平滑淡到 0（"离白线越远，颜色越浅"）。五个断点排成一条光滑的凸曲线 ——
         * 前段掉得快（光是从线上散出来的）、后段拖着一条很淡的长尾。
         *
         * 断点少（比如早先的 3 个）会在曲线上留下折角，那种"**肩**"看上去像多了一层光；
         * 5 个断点之后肉眼已经看不出来。屏幕边缘那一侧（外侧辉光）不在这张表里：
         * 它由 [GLOW_EDGE_ALPHA] 线性升到脊线，见 [glowProfile]。
         * **色标数必须与这里的断点数一致**（见 [GLOW_COLORS]）。
         */
        val GLOW_STOPS = arrayOf(
            floatArrayOf(0f, 0.22f, 0.48f, 0.74f, 1f)
        )

        /**
         * 各层色带，alpha 是相对值（再乘 [GLOW_PEAK_ALPHA]）。
         *
         * 五档都是白，只是**越远越浅**：脊线上纯白 → 极淡的暖白 → 中性 → 极淡的冷白 →
         * 尾巴上那丝低饱和冰蓝。原来那条冰蓝尾巴属于外围散射那一层，现在并进这一层，
         * 于是"光从线上的白散到远处偏冷、偏淡"这件事由一条曲线独立完成。
         * 最外面的色标 alpha 必须是 0（那一端要彻底淡掉，不能留一条硬边）。
         *
         * 相对 alpha 一路排下来是 `1.00 / 0.76 / 0.46 / 0.20 / 0`：单调递减，
         * 曲线形状由 [GLOW_STOPS] 的断点位置决定。
         */
        val GLOW_COLORS = arrayOf(
            intArrayOf(
                0xFFFFFFFF.toInt(),
                0xC2FFFDFA.toInt(),
                0x75FBFDFF.toInt(),
                0x33F2F7FF.toInt(),
                0x00EAF2FF.toInt()
            )
        )

        /**
         * 各层辉光的 alpha 呼吸摆幅。**现在全 0：辉光不呼吸。**
         *
         * 呼吸整个搬到核心白线的**粗细**上了（见 [LINE_SWING_PX]）——
         * 早前把呼吸放在辉光的宽度和亮度上，那是错的：辉光应该是稳定的"衬底"，
         * 动的是那根线。想再给辉光留一点点亮度起伏，给 0.05~0.1 即可。
         */
        val GLOW_BREATHE_AMP = floatArrayOf(0f)

        /**
         * 各层呼吸的相位（**周期**占比）：只有一层了，这项没用了（保持 0）。
         *
         * 曾经给外围散射配 0.06（≈156ms 的层间滞后），让光晕"从边缘往里呼" ——
         * 外围那层删掉之后没有第二层可以错开。表留着，加层就能用。
         */
        val GLOW_BREATHE_PHASE = floatArrayOf(0f)

        /**
         * 各层辉光**宽度**的相对摆幅。**现在全 0：辉光宽度恒定。**
         *
         * 早前这里给的是 0.35 / 0.65，让左右（上下）成对守恒地涌 —— 那是把呼吸放错了地方：
         * 现在呼吸只改核心白线的粗细，辉光的宽度全程不动，四条边完全一样宽。
         *
         * 非 0 时会重新打开"沿边逐段给宽度"的那条路径（`glowWidthPx` / `taperAt` /
         * 逐段噪声都还在，见 [GLOW_JITTER_BLOCK]），语义是：
         * 宽度 = 基准 × (1 + 摆幅 · 符号 · 果冻)，左/上符号 +1、右/下 −1，于是
         * `w(左) + w(右) = w(上) + w(下) = 2 × 基准`（一边变宽、对面必然同量变窄）。
         */
        val GLOW_BREATHE_TRADE = floatArrayOf(0f)

        /**
         * 水波抵达边缘后的短暂增强幅度。
         *
         * 从 `Timeline.EDGE_MS` 起走一个 sin(πt) 包络（1 -> 1+这个值 -> 1）。
         * 只乘在光晕层的 alpha 上、不动几何，所以不会突然变粗，也不会过曝。
         */
        const val EDGE_IGNITE_BOOST = 0.32f

        /**
         * 四角附近过渡带的加宽倍数 —— 角上的淡入比直边慢这么多。
         *
         * 直边是"贴边一维渐变 + 28 段各自取 alpha"，光晕随波前逐段铺过来，本来就是渐显；
         * 角上那团是**一次 drawCircle、整块共用一个 alpha**，而圆只有 `cornerR` 那么小，
         * 带扫过它只要一百多毫秒，看着就是"啪"地亮起来。
         *
         * 做法是**纯几何**的：离角越近带越宽（见 [revealSpanAt]），于是切片和角走的是
         * 同一条曲线，交界天然连续。反过来 —— 单独给角一条更慢的曲线 —— 交界就会变成
         * 一条亮度突变，四个角各显出一块方格（踩过）。
         *
         * 1.4 表示角上带宽 = 基准 × 2.4：0.38 × 2.4 / 2.356 × 1000 ≈ 390ms 的淡入。
         */
        const val CORNER_SOFTEN = 1.4f

        /**
         * "角的影响范围"（归一化半径，与波前同一套度量）。
         *
         * 0.18 在 720 宽的屏上 ≈ 130px：每条边离角最近的那 130px 开始变慢，边中间仍是基准速度。
         * 调大 = 变慢的范围更宽（也更早开始发亮）。
         */
        const val CORNER_SOFT_R = 0.18f

        /**
         * 呼吸周期（毫秒）。**4.8s**（原 2.6s → 3.6s → 4.8s，一路按需求放慢）。
         *
         * 核心白线的**粗细**按这个周期变化（以及一点辉光 alpha）；核心线本身是几何在动，
         * 顶部波源光斑只跟着变**高度**，面板、提示也不参与。周期内是**果冻弹簧**
         * （[GLOW_BREATHE_WOBBLES] 下衰减振荡），所以"一下"约 1600ms（4800 / 3），
         * 不是一次匀称的呼吸。再慢就调到 6000（一下 2s）；注意每段的相位抖动是按
         * **周期占比**给的，改周期会同时缩放各段之间的错开时间。
         */
        const val LINE_BREATHE_PERIOD_MS = 4800f

        /**
         * 果冻弹簧在一个呼吸周期里抖几下。
         *
         * 3 下 → 每下约 870ms（2600 / 3），慢而黏，像果冻被轻轻推一下。
         * 想更"Q 弹"就加大，但别超过 5：光晕会从"抖"变成"闪"。
         */
        const val GLOW_BREATHE_WOBBLES = 3f

        /**
         * 果冻的衰减系数（指数上的系数）：一个呼吸周期内振幅衰减到 e^-2.5 ≈ 8%。
         *
         * 2.5 → 一个周期能看到 2~3 下明显的回弹，然后稳住（果冻）。
         * 越小弹得越久（更软，但小到 1 以下就又变回"接近匀速的正弦"了）；
         * 越大越快停下来（更硬的橡胶，4 左右就只剩"弹一下"）。
         */
        const val GLOW_BREATHE_DECAY = 2.5f

        /**
         * 归一化因子：让果冻的**第一个峰**正好是 1，和原来正弦呼吸的幅度口径一致。
         *
         * 冲激响应的第 i 个峰在 s = (4i−3)/(4N)、振幅 e^(−K·s)，随 i 单调下降，
         * 所以最大值必然出现在第一个峰（s = 1/(4N)）—— 于是它就是 e^(−K/(4N))。
         */
        val GLOW_BREATHE_PEAK = exp(-GLOW_BREATHE_DECAY / (4f * GLOW_BREATHE_WOBBLES))

        /** 2π，抖动相位用。 */
        const val TWO_PI = 6.2831855f

        // ------------------------------------------------ 逐段抖动（沿边的不规则感）

        /**
         * 逐段抖动沿边的相关尺度（单位是**段**）：每这么多段放一个噪声控制点，中间插值。
         *
         * 直接用逐段白噪声是不行的：相邻两段的相位/幅度完全无关，而弹簧在 1200ms 尺度上摆得很快，
         * 相邻段的宽度差能到 40px 以上 —— 光带内沿会被拉成锯齿（扫参数实测过）。
         * 9 段 ≈ 顶边 190px / 侧边 510px，一条边上大约 3 个"区"，宽度变化是连成片的：
         * 有的区在鼓、有的区几乎不动。
         */
        const val GLOW_JITTER_BLOCK = 9

        /**
         * 逐段抖动的**相位**散布（周期占比）。
         *
         * 0.08 ≈ 0.08 × 3600 = 290ms：各段错开一点点相位，同一条边上就不是整齐划一地胀缩。
         * 别给大（0.5 = 各段差半个周期）：相位差一大，相邻段的宽度台阶会重新变成锯齿，
         * 上面那个相关尺度也压不住。
         *
         * 相位只跟 (层, 边组, 段号) 有关、**跟时间无关**（见 [sliceNoise]）：
         * 每帧重新随机的话整条边会闪，那就不是"有的段在动、有的段不动"了。
         */
        const val GLOW_SLICE_PHASE_JITTER = 0.08f

        /**
         * 逐段抖动的**幅度**散布：0 = 每段摆幅都一样（整齐），越大越不齐。
         *
         * 幅度基准是 ×1，取值 `noise · (1 + J) − J/2` 再截到 `[0, 1]` —— J = 0.9 时
         * 约四分之一的段正好落在 0（**完全不动**），其余段的摆幅在 0~1 之间散开，
         * 这就是"上半部分有地方在变、有几段没变"的来源。
         *
         * **只减不增**（封顶 1）：每一段的摆幅都不超过基准，左边变宽时右边只会更窄，
         * "两边加起来 = 2 × 基准"这条守恒的口径不变。
         */
        const val GLOW_SLICE_AMP_JITTER = 0.9f

        // ------------------------------------------------ 顶部波源光斑（单独的淡蓝鼓包）

        /**
         * 顶部波源光斑出现的时刻：**白线出现 0.5s 之后**。
         *
         * 白线在 [Timeline.EDGE_START_MS]（500ms）从顶边中点冒头，所以这里是 500 + 500 = 1000ms，
         * 正好也是水波散开结束、常驻呼吸起振的那一刻。
         */
        const val TOP_BLOOM_START_MS = Timeline.EDGE_START_MS + 500L

        /**
         * 光斑的淡入时长：1000 → **1400ms** 柔和长到位，不"啪"地跳出来。
         *
         * 400 = 需求里的"白线完全显示（1100ms）**0.3s 后**，顶部凸起到最长处"——
         * 凸起的"长满"就是这个淡入走完（它的**高度**是恒定的，之后只有呼吸时变高变矮）。
         * 长满之后 0.1s 开始换色（[TOP_BLOOM_TINT_AT_MS] = 1500ms），
         * 再 0.1s 照片卡片入场（1600ms）。
         */
        const val TOP_BLOOM_IN_MS = 400f

        /**
         * 光斑横向半轴占屏幕宽度的比例：1/3 → **整枚横向 = 2/3 屏宽**（@720 约 480px）。
         *
         * 宽度按屏宽比例给（不是设计 px）：换机身、换分辨率都还是"占屏宽三分之二"。
         */
        const val TOP_BLOOM_RX_FRAC = 1f / 3f

        /**
         * 光斑纵向半轴（设计 px，@720 约 121px）：**凸出多深**看它。
         *
         * 圆心压在**顶边中点、y = 0** 上，所以屏幕里露出来的正好是下半椭圆 ——
         * 一个贴着白线、往屏幕里鼓出来的宽扁包（横向 480px、纵向 121px，约 4:1）。
         * 121px 比辉光那层（`12` 设计 px ≈ 23px）再多鼓出去约 5 倍，加上峰值 alpha 也略高一点
         * （`0.56` 对 `0.53`），"凸出"才既够远又够亮。想更"扁"就减这个数。
         */
        const val TOP_BLOOM_RY_PX = 64f

        /** 光斑峰值 alpha：0.44 → **0.56**（按需求"顶部的凸起也再亮一点"），仍低于白线 0.92。 */
        const val TOP_BLOOM_PEAK_ALPHA = 0.56f

        /**
         * 光斑色带（**没采到发送图片主色时**用的默认色）：中心浅蓝白 → 中段**更深的蓝** → 透明。
         *
         * 中段从原来的 `#9CC9FF` 加深到 `#6FA9FF`（更饱和），中心也从 `#E8F2FF` 压到 `#DCE9FF` ——
         * 整枚大了 2.6 倍之后，中心再给纯白就成了一块白光，中段不够深又会被辉光的灰白尾巴压住。
         *
         * 采到主色之后走 [TOP_BLOOM_TINT_*] 那套（同一套相对 alpha，只换 RGB），
         * 见 [topBloomTintColors]。
         */
        val TOP_BLOOM_COLORS = intArrayOf(
            0xE6DCE9FF.toInt(),
            0xC06FA9FF.toInt(),
            0x006FA9FF.toInt()
        )

        /** [TOP_BLOOM_COLORS] 对应的断点（沿半轴归一化，0 = 贴线处）。 */
        val TOP_BLOOM_STOPS = floatArrayOf(0f, 0.30f, 1f)

        /**
         * 光斑换成"发送图片主色"的时刻：**顶部凸起长满（1400ms）之后 0.1s** = **1500ms**。
         *
         * 需求给的链条是：白线铺满 1100 → 凸起 0.3s 后长满 1400 → 0.1s 后变色 1500
         * → 再 0.1s 卡片入场 1600。所以这里 = [Timeline.EDGE_START_MS]（500）
         * + [Timeline.EDGE_REVEAL_MS]（600）+ [TOP_BLOOM_IN_MS]（400）+ 100；
         * 写成 `EDGE_START_MS + 1000L` 是因为那三项加起来正好 1000 ——
         * 改 [Timeline.EDGE_REVEAL_MS] 或 [TOP_BLOOM_IN_MS] 时记得一起改这一项。
         *
         * 光斑 1000~1400ms 就长满了，所以换色时它已经完整地挂在屏幕上。
         */
        const val TOP_BLOOM_TINT_AT_MS = Timeline.EDGE_START_MS + 1000L

        /**
         * 换色的交叉淡入时长：直接替换会看出"跳"一下色（两枚渐变的 RGB 差得可能很远），
         * 用 300ms 把旧的压下去、新的抬上来。给 0 = 到点硬换。
         */
        const val TOP_BLOOM_TINT_MS = 300f

        /** 主色 → 中心那一段的"变浅"量（HSV 里抬 V，见 [PhotoTint.lighten]）。 */
        const val TOP_BLOOM_TINT_CENTER_MIX = 0.26f

        /** 主色 → 中段那一段的"变浅"量：几乎就是主色本身，只比中心深一点。 */
        const val TOP_BLOOM_TINT_MID_MIX = 0.10f

        /**
         * 变浅之后 V 的下限：光斑是一枚**发光**的包，主色再深也不该让它变成一块暗斑。
         * 给 0 = 不兜底（主色多深就多深）。
         *
         * 0.62 → **0.68**（按需求"顶部的凸起也再亮一点"）：深色照片的主色兜到 0.68 之后
         * 那枚包才真的在发光，而不是一块压暗的色块。
         */
        const val TOP_BLOOM_TINT_MIN_VALUE = 0.68f

        /** 中心那一段的相对 alpha（和 [TOP_BLOOM_COLORS] 的 `0xE6 / 0xC0` 一一对应）。 */
        const val TOP_BLOOM_TINT_CENTER_ALPHA = 0xE6

        /** 中段那一段的相对 alpha。 */
        const val TOP_BLOOM_TINT_MID_ALPHA = 0xC0

        /**
         * 光斑呼吸的摆幅：**只作用在纵向半轴**（横向恒定，见 [drawTopBloom]）。
         *
         * 它**单独呼吸**：用的是同一枚 `breath()`，但相位是自己的 [TOP_BLOOM_BREATHE_PHASE]，
         * 所以和边缘那圈（白线粗细、辉光）并不同步 —— 一枚自己一鼓一鼓的光斑。
         * 给 0 = 高度也恒定，只剩 alpha 的微起伏。
         */
        const val TOP_BLOOM_HEIGHT_SWING = 0.35f

        /**
         * 光斑呼吸的相位（**周期**占比）—— 它独立于边缘。
         *
         * 0.31 ≈ 1.5s：和"四边同时吸呼"的白线错开约三分之一周期，看的时候不会读成同一个动作。
         * 同一枚弹簧、同一个周期，只是起点不同（想让它更"独自"一点，把相位再往 0.5 靠）。
         */
        const val TOP_BLOOM_BREATHE_PHASE = 0.31f

        /** 卡片飞走（向上飘出屏幕）的时长。 */
        const val FLY_MS = 460L

        /**
         * 卡片飞走之后，隔多久复位时间轴、重播入场。
         *
         * 原来这段时间里屏幕中央会弹一枚「已发送」药丸（`TOAST_DELAY_MS` 260ms 出场、
         * 250ms 淡入、1.75s 后连它一起收掉）；提示按需求去掉之后，
         * 这个延迟就只剩下"让飞走那 460ms 自己跑完、再留一小段空屏"的作用。
         * 给 0 = 卡片刚飞出去就立刻重播。
         */
        const val RESET_DELAY_MS = 1750L

        /** 上滑多少 dp 才算"发送"（松手时判断）。 */
        const val SEND_THRESHOLD_DP = 85f

        /**
         * 上滑淡出时，"发送给 xxx"跟着手指往上让开的比例（相对卡片自己的位移）。
         *
         * 卡片是整段跟手（1.0），这行字只跟 0.35 —— 于是卡片往上顶时它被"挤"着往上走，
         * 而不是原地不动只变淡；走向和卡片一致，不会读成两件事。
         */
        const val PANEL_CHROME_DRAG_FOLLOW = 0.35f

        const val SIDE_TOP = 0
        const val SIDE_BOTTOM = 1
        const val SIDE_LEFT = 2
        const val SIDE_RIGHT = 3

        /** 四个圆角，顺序是绕一圈的。 */
        const val CORNER_TL = 0
        const val CORNER_TR = 1
        const val CORNER_BR = 2
        const val CORNER_BL = 3

        // ---- 屏幕边缘那圈白线的几何 ----
        // 核心线是**按逐点宽度拼出来的填充路径**（见 LINE_RIBBON_STEP_PX / buildRibbon），
        // 走一条圆角矩形中线；辉光的脊线就压在这条中线上（见 LINE_INSET_PX / glowProfile），
        // 于是线两侧都有光：屏幕边缘 → 外侧辉光 → 白线 → 内侧辉光。

        /**
         * 呼吸宽度在每条边两端收敛回基准值的区间长度（占该边全长的比例）。
         *
         * 角上那团光晕的剖面是按**基准宽度**建的（径向渐变的几何由屏幕圆角定死，
         * 没法跟着宽度缩），所以直边的宽度必须在两端的接缝处回到基准值 ——
         * 否则变宽的那一侧会把角"比"下去，接缝立刻显成一条暗线。
         *
         * 0.18 = 每条边两端各 18% 走过渡、中间 64% 走满摆幅。顶边两端各约 130px、
         * 侧边两端各约 285px。不能给太小：过渡区里每段切片的宽度台阶会变大，
         * 在带的内沿露出可见的阶梯（顶边 130px ≈ 6 段，台阶已经落在软渐变里看不出来）。
         *
         * 现在 [GLOW_BREATHE_TRADE] 是 `[0]`（辉光宽度恒定），所以这一项暂时不生效。
         */
        const val CORNER_QUIET_FRAC = 0.18f

        /**
         * 圆角半径（设计 px）。
         *
         * 不能用方角：手机的显示圆角会把四个角切掉，线画到那里就是空的。
         */
        const val EDGE_CORNER_R_PX = 22f

        /**
         * 把切线的位置对齐到**整数设备像素**：消掉角盘和直边矩形那一列像素的重叠。
         *
         * 切线在 `x = cornerR`：直边矩形从 `cornerR` 起铺（[edgeHalo]），角盘裁到
         * `[0, cornerR]²`（[cornerHalo]）。两块都是非 AA 画笔（[glowPaint] / [cornerGlowPaint]），
         * 而非 AA 的填充和 `clipRect` 各自取整到整数像素 —— 边界落在小数上时，那一条切线上
         * 就有一列像素的归属不确定：两边的取整方向一旦不一致就是**重叠**（两次 source-over，
         * 比周围亮一档），一致过头则是留缝。成型之后屏幕上相邻成对的只剩这 8 处，
         * 肉眼就是「四个圆角两处交界上的白缝」。
         *
         * 对齐到整数像素后两块落在同一条像素边界上（`[.., cornerR)` / `[cornerR, ..)`），
         * 既不重叠也不留缝。代价：圆角半径最多挪 <1px，白线弧跟着挪 <1px，看不出来。
         * 散开阶段的**段边界**同理（见 [edgeHalo] 里的 `cutAt`）：不对齐的话每条边会多出
         * 27 条亮梳齿。
         *
         * **置 false 就整块退回原来的行为**（对照用）。
         */
        const val SNAP_SEAM_TO_PX = true

        /** 四边光晕沿边缘切分的段数（各层共用）。段越细，波前扫过时的过渡越顺滑。 */
        const val GLOW_SLICES = 28

        /**
         * 波前遮罩的采样点数：把 `1 - smoothstep()` 那段剖面拆成几个色标。
         *
         * 整圈白线现在是**一次 `drawPath`**（见 [drawEdgeStroke]），逐段显形改用
         * 一枚径向渐变当遮罩 —— 渐变只能给色标、给不了 smoothstep 这条曲线，
         * 于是用 7 个点把曲线采样出来。再多肉眼也看不出，少了拐点会硬折。
         */
        const val FRONT_MASK_STOPS = 7

        /**
         * 遮罩渐变的重建阈值（px）：波前内沿挪动超过它才重建一枚。
         *
         * 散开那 1000ms 里大约重建几十次，之后半径不再变，一枚都不再分配。
         */
        const val FRONT_MASK_STEP_PX = 6f

        // 下面两个常量定义波前的过渡带。
        /**
         * 白线波前半径的下限系数：`radius = edgeReveal * glowRadiusScale`。
         *
         * **它只服务于这条白线**，和 GL 层那道彩虹波纹已经不是同一个数了：
         * 彩虹只推到屏高的 3/4（见 `RippleRenderer.RIPPLE_HEIGHT_FRAC`），
         * 白线则必须一直铺到底角，所以这里保留 2.18 作为下限、
         * 实际取 `max(它, 到最远底角的距离 + 过渡带)`（见 [glowRadiusScale]）。
         */
        const val RIPPLE_RADIUS_SCALE = 2.18f
        /** 过渡带的内端点：相对 radius 往内 0.10 就算"已经到了"（与着色器一致）。 */
        const val RIPPLE_REVEAL_NEAR = 0.10f

        /**
         * 过渡带的宽度（归一化半径）—— 它决定**每个点**从全透明到全亮花多久：
         *
         *   时长 ≈ SPAN / glowRadiusScale × EDGE_REVEAL_MS = 0.38 / 2.356 × 600 ≈ 97ms
         *
         * 原来是 0.27（≈114ms，按当时的 EDGE_REVEAL_MS = 1000 算）：角上那段弧只有几十 px，
         * 几乎在一帧里就到位，看着就是"啪"地亮起来，所以这里加宽。想要更明显的"由浅到显"就继续加，代价是扫过的那道前缘会拉成彗尾
         * （沿顶边可见的渐变长度 ≈ SPAN × 屏宽 px）。
         *
         * 着色器里那条带（`smoothstep(radius - 0.10, radius + 0.17, …)`）只管压暗，
         * 不需要跟着改：白线现在有自己的时间轴，两者本来就不是同一条边界。
         */
        const val RIPPLE_REVEAL_SPAN = 0.38f

        // ---- 接收确认页（本次新增的变体） ----
        // 接收态画的是同一套页面，只把「上滑发送」那一行（tipGap + tipH）换成两个按钮，
        // 所以下面这几个常量就是那一行的高度来源，[drawTransferPanel] 的排版用它算 tailH。

        /** 主按钮（「接收 N 张照片」）的高度（设计 px）。 */
        const val RECEIVE_PRIMARY_H_PX = 48f

        /** 次按钮（「拒绝」）的高度（设计 px）：比主按钮矮一档，读起来是次要动作。 */
        const val RECEIVE_SECONDARY_H_PX = 40f

        /** 两个按钮之间的间距（设计 px）。 */
        const val RECEIVE_BTN_GAP_PX = 10f

        /** 按钮圆角（设计 px）：比卡片（23）小一档，和卡片摆在一起不抢。 */
        const val RECEIVE_BTN_R_PX = 14f

        /**
         * 卡片下沿到按钮区之间那一口气（设计 px）。
         *
         * 面板里上滑提示那一段用的也是 18f（`tipGap`）—— 这里单独取一个常量是为了让
         * [layoutReceiveActions] 在**绘制和触摸两条路径上都能只跟 [cardRect] 有关**地算出
         * 同一份几何；改面板里那个 18f 时记得把这里一起改。
         */
        const val RECEIVE_TAIL_GAP_PX = 18f

        /** 底部状态行那行小字离屏幕底边的高度（设计 px）：正好落在底部品牌文字上方。 */
        const val STATUS_BOTTOM_PX = 46f

        /** 接收态按中的是哪个按钮（[pressedButton] 用）。 */
        const val BUTTON_NONE = 0
        const val BUTTON_PRIMARY = 1
        const val BUTTON_SECONDARY = 2
    }

    /** 状态栏高度，避免面板文字被系统栏压住。 */
    var topInset: Float = 0f

    /** 导航栏高度，避免底部品牌文字贴到系统栏。 */
    var bottomInset: Float = 0f

    /** 一次「上滑发送」流程结束后的回调。 */
    var onSendFinished: (() -> Unit)? = null

    // ------------------------------------------------------------------ 接收确认页（变体）

    /** 动效页的两种身份：发送（原来那套，默认）/ 接收确认页（本次新增的变体）。 */
    enum class Mode { SEND, RECEIVE }

    /** 上滑越过 [SEND_THRESHOLD_DP] 判定成立的**那一刻**回调（先于飞走动画），一次手势只回调一次。 */
    var onSendConfirmed: (() -> Unit)? = null

    /** 接收态点主按钮（「接收 N 张照片」）。 */
    var onReceiveAccepted: (() -> Unit)? = null

    /** 接收态点次按钮（「拒绝」）。 */
    var onReceiveRejected: (() -> Unit)? = null

    /** 当前身份；默认 [Mode.SEND] —— 单独演示这个动效页时和以前一模一样。 */
    var mode: Mode = Mode.SEND
        private set

    /** 接收态底部状态行；null = 不画（见 [setStatus]）。 */
    private var statusText: String? = null

    /** 接收态两个按钮的矩形（屏幕 px）。**绘制和命中判定读的是同一份**，见 [layoutReceiveActions]。 */
    private val receivePrimaryRect = RectF()
    private val receiveSecondaryRect = RectF()

    /** 接收态按下的是哪一枚按钮（[BUTTON_NONE] = 没按中 / 已经抬起）。 */
    private var pressedButton = BUTTON_NONE

    /** 上滑确认是否已经回调过（一次手势只回调一次，见 [startFlyAway]）。 */
    private var sendConfirmedNotified = false

    /** 对方标识：发送态是「发送给 X」，接收态是「来自 X的分享」；null = 用内置默认文案。 */
    private var peer: String? = null

    /** 对方给的张数：驱动「上滑发送 N 张照片」/ 卡片副标题 / 主按钮文案；<= 0 = 退回默认文案。 */
    private var peerCount = 0

    /**
     * 切换动效页的身份。[Mode.RECEIVE] 时不再画「上滑发送」提示、上滑手势一并禁用，
     * 改成卡片下面两个按钮（见 [drawReceiveActions]）。
     */
    fun setMode(mode: Mode) {
        this.mode = mode
        pressedButton = BUTTON_NONE
        // 标题跟着身份走：发送态「发送给 X」、接收态「来自 X的分享」
        refreshTexts()
        invalidate()
    }

    /**
     * 对方标识 + 张数，改完面板文案跟着换。
     *
     * [peer] 为空 / [count] <= 0 时**逐项退回**原来的内置文案（`panel_receiver` /
     * `panel_swipe_tip` / `card_title` / `card_meta`），所以这个动效页单独演示时和以前完全一样。
     */
    fun setPeerInfo(peer: String?, count: Int) {
        this.peer = peer
        this.peerCount = count
        refreshTexts()
        invalidate()
    }

    /** 底部状态行（「正在连接对方…」/「已连接，上滑发送即可」/ 失败原因）；null = 不画。 */
    fun setStatus(text: String?) {
        statusText = text
        invalidate()
    }

    /**
     * 换掉卡片里那张照片（主页选的那张）。
     *
     * 传 null 就退回内置的海边插画。缓存的位图是"已经按卡片尺寸裁好的那张"，
     * 所以换图时必须连缓存一起作废。
     *
     * 顺便在这里取一次**主色**（[PhotoTint.dominant]，32×32 采样，一次几毫秒）：
     * 取色只跟图片有关，跟时间轴无关，所以放在这里而不是每帧算 ——
     * 绘制路径上一帧都不碰它，换色那一刻只是换一枚早就建好的渐变。
     */
    fun setCardPhoto(bitmap: Bitmap?) {
        cardPhoto = bitmap
        bloomTint = bitmap?.let { PhotoTint.dominant(it) }
        topBloomTintShader = null
        topBloomTintBuilt = null
        artCache?.recycle()
        artCache = null
        invalidate()
    }

    /** 卡片上的标题 / 副标题（默认是内置文案，选了照片之后换成真文件名和大小）。 */
    fun setCardInfo(title: String, meta: String) {
        cardTitle = title
        cardMeta = meta
        invalidate()
    }

    /**
     * 卡片回到「还没有照片」的初始状态：清掉照片，标题 / 副标题退回内置默认文案。
     *
     * 换轮次时用（[com.pri.nfctouchdemo.service.OverlayService] 在一个新流程开始前调用）：
     * 窗口被摘掉时**视图树是留着的**，不擦的话上一轮那张照片和文案会在新窗口挂上的
     * 一瞬间被原样画出来 —— 看起来就是"先闪一下上次那个完成了的悬浮窗"。
     */
    fun clearCard() {
        setCardPhoto(null)
        cardTitle = defaultCardTitle
        cardMeta = defaultCardMeta
        invalidate()
    }

    // ---- 时间轴状态 ----
    private var elapsed = 0L

    /**
     * 白线自己的显形进度（0..1）。它和水波进度是**两条时间轴**。
     *
     * 需求是"水波出现 0.5s 后白线才起步、之后 1 秒内四边全亮"，而水波 1s 就结束了 ——
     * 跟着水波走无论如何凑不出这 1 秒，所以白线单独算：见 [applyTimeline]。
     * （水波进度本身不在这层存着：GL 层由服务直接喂给 `RippleSurfaceView.setRipple`。）
     */
    private var edgeReveal = 0f

    // ---- 尺寸与缩放 ----
    private var k = 1f
    private var viewW = 0f
    private var viewH = 0f

    /** 四边白线用的波前半径系数，随屏幕比例在 onSizeChanged 里算。 */
    private var glowRadiusScale = RIPPLE_RADIUS_SCALE

    /** 屏幕边缘圆角的半径（px），onSizeChanged 里算：取系统显示圆角与设计值的大者。 */
    private var cornerR = 0f

    // ---- 卡片几何 ----
    private val cardRect = RectF()
    private val scratch = RectF()

    /**
     * 整圈白线的**中线**（圆角矩形，闭合）：内缩 [LINE_INSET_PX]、圆角半径 [cornerR] − 内缩，
     * 所以它到屏幕边缘的距离处处一样。以及量它弧长用的度量器。
     */
    private val linePath = Path()
    private var lineMeasure: PathMeasure? = null
    private var lineLen = 0f

    /**
     * 每帧**按逐点宽度**拼出来的线体（闭合填充路径），几何全部缓存在下面几个数组里。
     *
     * 中线几何是静态的：`onSizeChanged` 里沿中线等距采样一次，存下位置、**朝屏幕边缘的单位法线**
     * 和该点的抖动参数（相位/幅度，跟时间无关）；每帧只按当前呼吸算出每点的半宽，
     * 外沿正向、内沿反向各连一遍就成了一个连续带子 —— 没有分段描边的圆头叠加，
     * 也没有宽度台阶（对比 [edgeHalo] 那种逐段矩形）。
     */
    private val ribbonPath = Path()
    private var lineSigma = 0
    private var linePx = FloatArray(0)
    private var linePy = FloatArray(0)
    private var lineNx = FloatArray(0)
    private var lineNy = FloatArray(0)

    /** 每个采样点的抖动参数（静态）：相位偏置（周期占比）、幅度系数（0..1）。 */
    private var linePh = FloatArray(0)
    private var lineAm = FloatArray(0)

    /** 每个采样点当前的半宽（px），每帧重算一次，供外沿/内沿两趟共用。 */
    private var lineHalf = FloatArray(0)

    // 波前遮罩：圆心在顶边中点、半径固定的径向渐变，用色标把 smoothstep 剖面采样出来。
    // 只在"波前正在扫、还没盖满整圈"的那段时间存在；常驻状态是纯色一次画完，根本不碰它。
    private var frontMask: Shader? = null
    private var frontMaskNear = Float.NaN
    private var frontMaskFar = Float.NaN

    // ---- 手势 / 动画状态 ----
    private var dragging = false
    private var downY = 0f
    private var dragY = 0f
    private var flying = false
    private var flyT = 0f
    private var flyFromY = 0f
    private var flyFromScale = 1f

    /**
     * 卡片上滑时，面板上**除卡片之外**的东西（"发送给 xxx"、上滑提示）还剩多少可见度。
     *
     * 手指往上带满 [SEND_THRESHOLD_DP] 的行程 —— 也就是"松手就会发送"的那一刻 —— 正好淡到 0，
     * 所以带得越高、画面上越只剩下那张卡片。
     *
     * [flying] 期间恒为 0：卡片飞走时这些字**不许弹回来**（原来 [dragY] 在飞走时被清零，
     * 上滑提示就当着面又亮了一次）。回弹（[returnCard]）时 [dragY] 归零，它们跟着一起回来。
     */
    private val panelChromeFade: Float
        get() = if (flying) 0f else (1f + dragY / (SEND_THRESHOLD_DP * k)).coerceIn(0f, 1f)

    /** 卡片插画的缓存位图。 */
    private var artCache: Bitmap? = null

    /**
     * 发送图片的**主色**（已经变浅，见 [PhotoTint]）：顶部那枚凸起的光斑在
     * [TOP_BLOOM_TINT_AT_MS] 之后换用它。null = 没图 / 取不到色 → 保持默认淡蓝。
     */
    private var bloomTint: Int? = null

    /** 用主色建的那枚光斑渐变（和默认那枚同一套断点 / 相对 alpha）。 */
    private var topBloomTintShader: Shader? = null

    /** [topBloomTintShader] 是给哪个色建的：换图或换尺寸时置空，下一帧重建一次。 */
    private var topBloomTintBuilt: Int? = null

    /** 主色那枚渐变的局部矩阵（**不跟默认那枚共用**：setLocalMatrix 是否复制矩阵不该赌）。 */
    private val topBloomTintMatrix = Matrix()

    /** 用户选的「要发送的照片」；为空时卡片退回内置的海边插画。 */
    private var cardPhoto: Bitmap? = null
    private val artSrc = Rect()

    private var returnAnimator: ValueAnimator? = null
    private var flyAnimator: ValueAnimator? = null
    private var resetRunnable: Runnable? = null

    // ---- 文案 ----
    // 内置默认文案：`peer == null` / `count <= 0` 时用的就是这几条（单独演示动效页的画面不变）
    private val defaultReceiverText = context.getString(R.string.panel_receiver)
    private val defaultSwipeTipText = context.getString(R.string.panel_swipe_tip)
    private val defaultCardTitle = context.getString(R.string.card_title)
    private val defaultCardMeta = context.getString(R.string.card_meta)

    // 当前生效的文案：默认是上面那几条，[setPeerInfo] / [setCardInfo] 会覆盖
    private var receiverText = defaultReceiverText
    private var swipeTipText = defaultSwipeTipText
    // 卡片标题 / 副标题：默认是内置文案，选了照片之后由 [setCardInfo] 换成真文件名和大小
    private var cardTitle = defaultCardTitle
    private var cardMeta = defaultCardMeta

    /** 接收态主按钮文案（「接收 N 张照片」；张数未知时是「接收」）。 */
    private var receiveAcceptText = context.getString(R.string.receive_accept)

    /** 接收态次按钮文案（「拒绝」）。 */
    private var receiveRejectText = context.getString(R.string.receive_reject)

    /**
     * 发送页要不要用 NFC 层给过来的对端标识 / 张数改写文案。
     *
     * **false（当前）：发送页与 `D:\A17\Test` 逐字一致** —— 面板标题是内置的「发送给 fazd9」、
     * 上滑提示是「上滑发送 1 张照片」、卡片副标题是照片的真文件名与大小；NFC 的连接进度
     * 只出现在底部状态行，不动门面文案。
     *
     * 想要产品里那两句「发送给 用户XXXX」「上滑发送 N 张照片」时改成 true 即可。
     * 接收页**不受**这个开关影响：它本来就用对端标识和真实张数（Test 里没有这一页）。
     */
    private val sendTextsFromPeer = false

    /**
     * 按当前 [mode] / [peer] / [peerCount] 重算这几条文案。
     *
     * **只有真的取到了对应的输入、而且 [sendTextsFromPeer] 允许时才换成带参数的新文案**，
     * 否则逐项退回内置文案 —— 于是发送页（以及单独演示动效页）和 Test 工程逐字一样。
     *
     * 注意：卡片副标题**只在真要显示「类型 · N 张照片」时才写**。无条件回落会冲掉
     * [setCardInfo] 刚灌进来的真文件名与大小（Test 的卡片副标题一直是真文件名 + 大小）。
     */
    private fun refreshTexts() {
        val name = peer
        receiverText = when {
            name == null -> defaultReceiverText
            mode == Mode.RECEIVE -> context.getString(R.string.peer_title_receive, name)
            sendTextsFromPeer -> context.getString(R.string.peer_title_send, name)
            else -> defaultReceiverText
        }
        // 发送页不认「N 张照片」时把张数当 0：上滑提示与卡片副标题都保持 Test 原样
        val count = if (mode == Mode.SEND && !sendTextsFromPeer) 0 else peerCount
        swipeTipText = if (count > 0) {
            context.getString(R.string.swipe_tip_count, count)
        } else {
            defaultSwipeTipText
        }
        if (count > 0) {
            cardMeta = context.getString(R.string.card_meta_count, metaType(), count)
        }
        receiveAcceptText = if (peerCount > 0) {
            context.getString(R.string.receive_accept_count, peerCount)
        } else {
            context.getString(R.string.receive_accept)
        }
        receiveRejectText = context.getString(R.string.receive_reject)
    }

    /**
     * 卡片副标题里 `·` 之前那一段：默认副标题是「JPG · 4.8 MB · 1 张照片」，取出来就是 `JPG`。
     * 取不到（[setCardInfo] 换过副标题、或者里面没有 `·`）时退回 `JPG`。
     */
    private fun metaType(): String = defaultCardMeta.substringBefore('·').trim().ifEmpty { "JPG" }

    // ---- 画笔 ----
    private val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val normal = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)

    // 柔光是宽而软的渐变块，按波前分段绘制，关掉抗锯齿以免相邻切片之间出现细缝。
    private val glowPaint = Paint()

    // 角上的柔光是整块径向渐变，要抗锯齿（它的外沿就是屏幕圆角，锯齿会很显眼）。
    private val cornerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 各层各边/各角的渐变，onSizeChanged 里建一次。绘制时只改 Paint.alpha 和 strokeWidth，
    // 每帧不再 new 任何 Paint / Shader / Path。
    private val edgeShaders = arrayOfNulls<Shader>(GLOW_LAYERS * 4)
    private val cornerShaders = arrayOfNulls<Shader>(GLOW_LAYERS * 4)

    // 顶部波源光斑：一枚独立的宽扁淡蓝包（横向 2/3 屏宽），整块径向渐变，要抗锯齿。
    private val topBloomPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var topBloomShader: Shader? = null

    // 光斑是椭圆，画的是 drawOval：这个矩形每帧复用，不在绘制路径上分配对象。
    private val topBloomOval = RectF()

    // 光斑的呼吸同样走"按最宽建、每帧压回来"，所以也要一枚自己的矩阵
    // （不跟贴边的共用：setLocalMatrix 是否复制矩阵不该赌）。
    private val topBloomMatrix = Matrix()

    // 贴边渐变是按"呼吸到最宽"时建的，每帧用这个矩阵把它压到当前宽度
    // （改 shader 的 end 点就得每帧重建，见 scaleEdgeShader）。每层每边各一个，
    // 互不共享 —— 无论 setLocalMatrix 是否复制矩阵都不会串。
    private val glowMatrices = Array(GLOW_LAYERS) { Array(4) { Matrix() } }

    // 整圈白线是**一条闭合路径、一次画完**：没有分段、没有 cap，也就不存在
    // "相邻两段的圆头在接缝处各叠半个线宽、叠出一排小亮点"这件事。
    // 线宽要沿边变，描边做不到，所以线体是填充路径（[ribbonPath]）——用这枚 FILL 画笔。
    private val edgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.ROUND
    }
    private val lineRibbon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val photoClip = Paint(Paint.ANTI_ALIAS_FLAG)

    // 往卡片插画缓存里画用户照片时用（缩放要过滤，否则缩略图边缘会发硬）
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val cardPath = Path()

    private val arrowUp = Path().apply {
        moveTo(12f, 3f); lineTo(5f, 10f); lineTo(9f, 10f); lineTo(9f, 20f)
        lineTo(15f, 20f); lineTo(15f, 10f); lineTo(19f, 10f); close()
    }

    // ------------------------------------------------------------------ 对外接口

    /**
     * 时间轴每推进一帧调用。
     *
     * [rippleProgress] 是 GL 层的水波进度，这一层现在用不上它（白线走自己的 [edgeReveal]）；
     * 保留参数是为了让 GL 和 Canvas 两层的输入是同一个签名 —— 服务那边一次拿到
     * (elapsed, ripple)，分别喂给 `RippleSurfaceView.setRipple` 和这里。
     */
    fun applyTimeline(elapsedMs: Long, rippleProgress: Float) {
        elapsed = elapsedMs
        // 白线另起一条时间轴：水波出现 0.5s 后（EDGE_START_MS = 500ms）才开始显形，
        // 再用 EDGE_REVEAL_MS = 600ms 从顶边中点匀速走到四边全亮（1100ms 收尾）。
        // 线性是故意的：走边看起来是匀速"长出来"，比缓动更像描边在被画出来。
        edgeReveal = ((elapsedMs - Timeline.EDGE_START_MS) / Timeline.EDGE_REVEAL_MS)
            .coerceIn(0f, 1f)
        invalidate()
    }

    /**
     * 面板浮现进度，0..1 —— 和 `Timeline.panelAppearAt(elapsed)` 是同一个式子。
     *
     * 这一层手里只有服务每帧喂进来的 [elapsed]（没有 Timeline 实例），所以按同一个式子现算：
     * 卡片 / 接收方信息 / 上滑提示 / 接收态按钮的淡入全都挂在它上面（和时间轴、白线一致）。
     */
    private fun panelAppearAt(elapsedMs: Long): Float =
        ((elapsedMs - Timeline.PANEL_MS) / Timeline.PANEL_FADE_MS).coerceIn(0f, 1f)

    /** 清掉发送流程留下的临时状态。 */
    fun resetSendState() {
        cancelAnimators()
        dragging = false
        dragY = 0f
        flying = false
        flyT = 0f
        // 下一次上滑可以重新回报一次确认
        sendConfirmedNotified = false
        resetRunnable?.let { removeCallbacks(it) }
        resetRunnable = null
        invalidate()
    }

    // ------------------------------------------------------------------ 尺寸

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewW = w.toFloat()
        viewH = h.toFloat()
        k = viewW / DESIGN_W

        // 波源在顶部中央，最远的点是底部两角：dist = hypot(0.5, aspect)。
        // 长屏（20:9）这个值约 2.22，比下限 2.18 还大 —— 于是那几个角永远到不了 100%。
        // 把半径按最远点放大一点点，让白线推到头（edgeReveal=1）时刚好盖满整圈，
        // 就能全程保住自顶向下的推进。16:9 等常见比例算出来小于 2.18，取 max 后仍是 2.18。
        // （GL 层那道彩虹波纹与这里无关：它只推到屏高的 3/4，见 RippleRenderer。）
        val far = hypot(0.5f, viewH / viewW)
        glowRadiusScale = max(RIPPLE_RADIUS_SCALE, far + RIPPLE_REVEAL_NEAR)

        // 整圈白线走一条圆角矩形**中线**：方角版本会一直画到屏幕角上，
        // 而手机的显示圆角把那里切掉了 —— 四个角看上去就是空的。
        // [cornerR] 是**屏幕显示圆角**的半径（辉光那套几何用它，见 cornerGradient），
        // 取「系统显示圆角」和「设计值」里的大者；系统取不到（老系统 / 直屏）时用设计值兜底。
        // 而白线本身内缩 [LINE_INSET_PX]，圆角半径相应减掉一个内缩 ——
        // 这样它的弧和显示圆角同心，**四个角到屏幕边缘的距离和直边一样**（外侧辉光宽度才均匀）。
        val inset = LINE_INSET_PX * k
        val rawCornerR = min(
            max(EDGE_CORNER_R_PX * k, systemCornerRadiusPx()),
            min(viewW, viewH) * 0.5f - inset
        )
        // 对齐到整数设备像素：切线正好落在一列像素的边界上，角盘和直边矩形就不会各盖一次
        // （见 [SNAP_SEAM_TO_PX]）。置 false 就用回原来的小数半径。
        cornerR = if (SNAP_SEAM_TO_PX) floor(rawCornerR).coerceAtLeast(1f) else rawCornerR
        linePath.reset()
        val lineR = (cornerR - inset).coerceAtLeast(1f)
        linePath.addRoundRect(
            RectF(inset, inset, viewW - inset, viewH - inset),
            lineR,
            lineR,
            Path.Direction.CW
        )
        val m = lineMeasure ?: PathMeasure().also { lineMeasure = it }
        m.setPath(linePath, false)
        lineLen = m.length
        buildLineSamples(m)

        // 遮罩渐变的几何（圆心、半径、断面位置）全跟着屏幕尺寸走，尺寸一变必须重建
        frontMask = null
        frontMaskNear = Float.NaN
        frontMaskFar = Float.NaN

        // 一次性把各层各边的渐变建好（深度和圆角半径都定下来了），
        // 之后每帧只改 Paint.alpha、shader 的局部矩阵 —— 不在绘制路径上分配对象。
        for (layer in 0 until GLOW_LAYERS) {
            val base = GLOW_DEPTH_PX[layer] * k
            for (side in SIDE_TOP..SIDE_RIGHT) {
                // 贴边渐变按"呼吸到最宽"时建（基准 × (1 + 摆幅)）：最宽的那一侧也够用，
                // 每帧再用局部矩阵压到当前宽度。四条边同一个深度。
                edgeShaders[layer * 4 + side] = haloGradient(side, maxGlowDepthPx(layer), layer)
            }
            // 角上的径向渐变的几何由屏幕圆角决定（固定），宽度不参与呼吸，
            // 所以这里仍然用基准深度（也和直边的基准深度是同一个值，接缝对得上）。
            for (corner in CORNER_TL..CORNER_BL) {
                cornerShaders[layer * 4 + corner] = cornerGradient(corner, base, layer)
            }
        }

        // 顶部波源光斑：横向是恒定的（按屏宽比例），所以渐变按横向半轴建一次就行，
        // 每帧只在纵向压一下 —— 光斑只"变高变矮"，不"变大变小"。
        topBloomShader = topBloomGradient(TOP_BLOOM_RX_FRAC * viewW, TOP_BLOOM_COLORS)
        // 主色那枚渐变的圆心同样按屏宽走，尺寸一变就得重建（下一帧按需建）。
        topBloomTintShader = null
        topBloomTintBuilt = null
    }

    /** 系统给出的显示圆角半径（px）。API 31 以下或直屏返回 0，由设计值兜底。 */
    private fun systemCornerRadiusPx(): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0f
        val d = display ?: return 0f
        val corner = d.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT) ?: return 0f
        return corner.radius.toFloat()
    }

    // ------------------------------------------------------------------ 绘制

    override fun onDraw(canvas: Canvas) {
        if (viewW <= 0f || viewH <= 0f) return

        // 图层顺序：面板 < 边缘。
        // 原型是「白线 20 < 边缘 25 < 面板 40 < 提示 70」，这里把边缘挪到了面板之上：
        // 面板那层 scrim 是铺满全屏的，画在边缘后面会把白线罩上一层灰 ——
        // 线条的颜色必须一直是原来的白色。（原型 z-index 70 那枚「已发送」提示也不要了。）
        // 原型 z-index 18 那条「光带扫过」（.light-sweep）已整体去掉：OPPO 参考录屏的
        // 动画段里没有这条从左到右的亮带，它是原型自己加的氛围光，不是一碰互联的一部分。
        // 白线的显形由 edgeReveal 决定（水波出现 0.5s 后才起步，见 applyTimeline）；
        // 在那之前整圈不画 —— 包括辉光，否则边框会先于水波出现。
        if (elapsed >= Timeline.PANEL_MS) drawTransferPanel(canvas)
        if (edgeReveal > 0f) drawEdgeGlow(canvas)
    }

    // ---------------------------------------------------------- 1. 边缘辉光（核心线 + 内侧辉光）

    private fun drawEdgeGlow(c: Canvas) {
        // 两层，从外到内：内侧辉光 -> 核心高光线。
        // 核心线最后画，保证它干净；光晕层只改 alpha 和贴边宽度，
        // 几何（圆角、深度、路径）全程不变。
        // 强度 = 该层峰值 × 呼吸；「水波抵达后的短暂增强」再乘一下。
        val boost = arrivalBoost()
        for (layer in GLOW_NEAR until GLOW_LAYERS) {
            for (side in SIDE_TOP..SIDE_RIGHT) edgeHalo(c, layer, side, boost)
            for (corner in CORNER_TL..CORNER_BL) cornerHalo(c, layer, corner, boost)
        }
        // 波源光斑压在辉光**之上**（它要"凸出"）、核心白线**之下**（白线必须仍然干净）。
        drawTopBloom(c)
        drawEdgeStroke(c)
    }

    /**
     * 顶部波源光斑：顶边中点、贴着白线往屏幕里鼓出来的一枚**宽扁包**，独立一层。
     *
     * 白线出现（[Timeline.EDGE_START_MS] = 500ms）之后 0.5s 才冒头（[TOP_BLOOM_START_MS]），
     * 之后**常驻**：纵向半轴和 alpha 跟着呼吸走，用的是同一枚 `breath()`，但相位是自己的
     * [TOP_BLOOM_BREATHE_PHASE] —— 它**单独呼吸**，和边缘那圈（白线粗细）并不同步。
     * **横向恒定**：光斑只变高变矮，随呼吸变宽变窄会显得整块在缩放。
     *
     * **颜色跟着发送的图片走**：图片的主色（[PhotoTint]，稍微变浅一点）在
     * [TOP_BLOOM_TINT_AT_MS]（= 凸起长满 1400ms 之后的 1500ms）起用 [TOP_BLOOM_TINT_MS] 交叉淡入换掉
     * 默认的淡蓝。光斑 1000~1400ms 就长满了，所以换色时它已经完整挂在屏幕上。
     * 没图 / 取不到色 → 一直是默认色。
     *
     * 圆心压在 `y = 0`（屏幕边缘）上，屏幕里看到的正好是下半椭圆 —— 呼应波源就在顶边中点。
     * 渐变按横向半轴建一次（纵向比例恒 ≤ 1），每帧只压纵向：几何跟着呼吸变，但不重建 shader。
     */
    private fun drawTopBloom(c: Canvas) {
        val shader = topBloomShader ?: return
        val since = (elapsed - TOP_BLOOM_START_MS).toFloat()
        if (since <= 0f) return
        val appear = (since / TOP_BLOOM_IN_MS).coerceIn(0f, 1f)
        if (appear <= 0f) return

        val jelly = breath(TOP_BLOOM_BREATHE_PHASE)
        // 横向：只按屏宽比例给，**不参与呼吸**；纵向：跟着呼吸在 [0.77, 1.35] 之间变。
        val rBuilt = TOP_BLOOM_RX_FRAC * viewW
        val rx = rBuilt
        val ry = TOP_BLOOM_RY_PX * k * (1f + TOP_BLOOM_HEIGHT_SWING * jelly)
        val alpha = TOP_BLOOM_PEAK_ALPHA *
                (1f + GLOW_BREATHE_AMP[GLOW_NEAR] * jelly) * appear

        // 圆心在 y = 0 上：上半椭圆在屏幕外，露出来的正好是贴着白线的下半部分。
        topBloomOval.set(viewW * 0.5f - rx, -ry, viewW * 0.5f + rx, ry)

        val tintShader = bloomTintShader(rBuilt)
        if (tintShader == null) {
            drawBloomOval(c, shader, topBloomMatrix, rBuilt, rx, ry, alpha)
            return
        }

        // 换色进度：凸起长满之后 100ms 起，用 [TOP_BLOOM_TINT_MS] 交叉淡入
        // （两枚渐变形状一样，只是 RGB 不同）。
        val mix = ((elapsed - TOP_BLOOM_TINT_AT_MS) / TOP_BLOOM_TINT_MS).coerceIn(0f, 1f)
        if (mix <= 0f) {
            drawBloomOval(c, shader, topBloomMatrix, rBuilt, rx, ry, alpha)
        } else if (mix >= 1f) {
            drawBloomOval(c, tintShader, topBloomTintMatrix, rBuilt, rx, ry, alpha)
        } else {
            // 旧的压下去、新的抬上来：画两遍。alpha 分成 (1−mix) / mix 两份，
            // 两次 source-over 叠起来在中间会比目标亮百分之十几 —— 300ms 的过渡里看不出来。
            drawBloomOval(c, shader, topBloomMatrix, rBuilt, rx, ry, alpha * (1f - mix))
            drawBloomOval(c, tintShader, topBloomTintMatrix, rBuilt, rx, ry, alpha * mix)
        }
    }

    /**
     * 主色那枚光斑渐变（按需建一次）：返回 null 表示"没有主色"，调用方就一直用默认那枚。
     *
     * 只在换图 / 换尺寸之后的第一帧建一次 —— 换色那一刻不重建，只是换一个 shader 引用。
     */
    private fun bloomTintShader(radius: Float): Shader? {
        val tint = bloomTint ?: return null
        val cached = topBloomTintShader
        if (cached != null && topBloomTintBuilt == tint) return cached
        val built = topBloomGradient(radius, topBloomTintColors(tint))
        topBloomTintShader = built
        topBloomTintBuilt = tint
        return built
    }

    /**
     * 把主色排成光斑的色带：**断点和相对 alpha 与 [TOP_BLOOM_COLORS] 完全一致**，只换 RGB。
     *
     * 中心比中段更浅（和默认那套"中心浅、中段深"的关系一致）：
     * 中心是主色抬 [TOP_BLOOM_TINT_CENTER_MIX]，中段只抬 [TOP_BLOOM_TINT_MID_MIX] ——
     * 于是靠近白线那一小块是"主色的浅版"，往外才是主色本身，淡出成透明。
     */
    private fun topBloomTintColors(tint: Int): IntArray = intArrayOf(
        withAlpha(
            PhotoTint.lighten(tint, TOP_BLOOM_TINT_CENTER_MIX, TOP_BLOOM_TINT_MIN_VALUE),
            TOP_BLOOM_TINT_CENTER_ALPHA / 255f
        ),
        withAlpha(
            PhotoTint.lighten(tint, TOP_BLOOM_TINT_MID_MIX, TOP_BLOOM_TINT_MIN_VALUE),
            TOP_BLOOM_TINT_MID_ALPHA / 255f
        ),
        withAlpha(tint, 0f)
    )

    /** 把光斑椭圆按当前几何 + alpha 画一遍（两枚渐变共用）。 */
    private fun drawBloomOval(
        c: Canvas,
        shader: Shader,
        matrix: Matrix,
        rBuilt: Float,
        rx: Float,
        ry: Float,
        alpha: Float
    ) {
        if (alpha <= 0.002f) return
        // 渐变是按"横向半轴那个圆"建的，这里只在纵向压到 ry（横向比例恒为 1）：
        // 椭圆边界正好落在渐变透明的那一圈上，所以不会有硬边（裁切会在内侧留下硬边界）。
        matrix.setScale(rx / rBuilt, ry / rBuilt, viewW * 0.5f, 0f)
        shader.setLocalMatrix(matrix)
        topBloomPaint.shader = shader
        topBloomPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        c.drawOval(topBloomOval, topBloomPaint)
    }

    /**
     * 顶部波源光斑的径向渐变：圆心压在顶边中点上，半径是**横向半轴**（= [TOP_BLOOM_RX_FRAC] × 屏宽）。
     *
     * 圆心在 `y = 0`（屏幕边缘）上，所以屏幕里露出来的正好是下半椭圆 —— 贴着白线往里鼓；
     * 纵向由 `drawTopBloom` 里的局部矩阵压到 [TOP_BLOOM_RY_PX]（并跟着呼吸变），
     * 横向比例恒为 1，所以这里只需要一个固定半径。
     * 色带从中心（贴线处最亮）一路淡到透明，见 [TOP_BLOOM_COLORS] / [topBloomTintColors]。
     *
     * 只在 `onSizeChanged`（默认那枚）和换色后的第一帧（主色那枚）各调用一次；
     * 之后每帧只改 alpha + 局部矩阵。
     */
    private fun topBloomGradient(radius: Float, colors: IntArray): RadialGradient =
        RadialGradient(
            viewW * 0.5f,
            0f,
            radius,
            colors,
            TOP_BLOOM_STOPS,
            Shader.TileMode.CLAMP
        )

    /**
     * 单边光晕（某一层）：从屏幕边缘往内一维淡出的一段。
     *
     * 沿边缘切成若干段：散开阶段逐段用「波前是否已经到达」决定显形程度（[GLOW_SLICES] 段），
     * 水波从顶部中央扩散，于是顶边先亮、左右两边自上而下亮、底边最后从中间亮起，与着色器里
     * 画面被压暗的范围同步。
     *
     * 常驻阶段只有在**宽度沿边变化**时才需要继续分段（[GLOW_BREATHE_TRADE] 非 0 时，
     * 两端要收敛回基准，见 [taperAt]）；现在宽度恒定（摆幅全 0），所以常驻状态一条边
     * 直接一个矩形铺完，28 段和 28 次 `setLocalMatrix` 全省掉。
     *
     * 起点是 [cornerR] 而不是"边缘内缩 + 圆角半径"：圆角弧的切线正好落在 `cornerR` 处，
     * 那里角上那团和这里的深度剖面完全重合（见 [cornerHalo]），接缝才真正连续。
     *
     * 只铺直边；四个角由 [cornerHalo] 铺，细的核心线由 [drawEdgeStroke] 沿圆角路径描。
     */
    private fun edgeHalo(c: Canvas, layer: Int, side: Int, boost: Float) {
        val shader = edgeShaders[layer * 4 + side] ?: return
        val alpha = layerIntensity(layer) * boost
        val horizontal = side == SIDE_TOP || side == SIDE_BOTTOM
        val along = if (horizontal) viewW else viewH
        // 直边只铺到圆角弧的切线位置：两条直边的光晕在角上各铺一遍的话，
        // 两份 alpha 会叠成一份半 —— 四个角那团堆光就是这么来的。
        val lo = cornerR
        val hi = along - lo
        if (hi - lo <= 1f) return

        val filled = frontMaskFilled()

        // 渐变是 onSizeChanged 里建好的，这里只改 alpha + 局部矩阵，不再分配对象。
        glowPaint.shader = shader

        // 快速路径：宽度恒定的层 + 已经铺满 → 整条边一个矩形。
        // 各段 alpha 和宽度都一样，切段纯属白干。
        if (filled && GLOW_BREATHE_TRADE[layer] == 0f) {
            val depth = edgeBandPx(layer)
            glowPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
            scaleEdgeShader(layer, side, depth / maxEdgeBandPx(layer))
            c.drawRect(edgeSlice(scratch, side, lo, hi, depth), glowPaint)
            return
        }

        // 否则要分段：散开阶段各段 alpha 不同，或者常驻时**宽度**沿边在变
        // （两端收敛回基准，见 taperAt）。段数跟散开阶段一致，
        // 否则带的内沿会露出宽度台阶。
        val step = (hi - lo) / GLOW_SLICES
        // 段边界同样对齐到整数设备像素（见 [SNAP_SEAM_TO_PX]）：相邻两段是各自往外取整的非 AA
        // 矩形，边界落在小数上时那一列像素会被两段各盖一次，沿边看就是一排亮梳齿。
        // lo / hi 本身是整数（cornerR 与 边长 − cornerR），所以最后一段仍然严丝合缝。
        val snap = SNAP_SEAM_TO_PX
        fun cutAt(index: Int): Float =
            if (snap) floor(lo + index * step + 0.5f) else lo + index * step

        for (i in 0 until GLOW_SLICES) {
            val s0 = cutAt(i)
            val s1 = if (i == GLOW_SLICES - 1) hi else cutAt(i + 1)
            val mid = (s0 + s1) * 0.5f

            // 取样点压在屏幕边缘上（不是带内中点）：线本身就贴着边，
            // 按这个点反解出来的才是线真正被点亮的时刻。
            val reached = if (filled) {
                1f
            } else {
                val mx: Float
                val my: Float
                when (side) {
                    SIDE_TOP -> {
                        mx = mid
                        my = 0f
                    }

                    SIDE_BOTTOM -> {
                        mx = mid
                        my = viewH
                    }

                    SIDE_LEFT -> {
                        mx = 0f
                        my = mid
                    }

                    else -> {
                        mx = viewW
                        my = mid
                    }
                }
                waveReached(mx, my)
            }
            if (reached <= 0.002f) continue

            // 宽度：呼吸的摆幅越靠近两端越小，端点上正好回到基准值 ——
            // 角上那团用的就是那个基准剖面，于是交界处任何时刻都对得上。
            // 果冻值逐段给：同一条边上每段各弹各的（见 sliceJelly）。
            val taper = taperAt(mid, lo, hi)
            val depth = glowWidthPx(layer, side, taper, sliceJelly(layer, side, i))
            if (depth <= 0.5f) continue

            glowPaint.alpha = (reached * alpha * 255f).toInt().coerceIn(0, 255)
            // 渐变按"最宽"建的，压到当前宽度：不重建 shader，也不裁切
            // （裁切会在带的内侧留下一条硬边）。每段设一次，段内共用。
            scaleEdgeShader(layer, side, depth / maxEdgeBandPx(layer))
            c.drawRect(edgeSlice(scratch, side, s0, s1, depth), glowPaint)
        }
    }

    /**
     * 呼吸宽度在边两端的收敛系数（0..1）：端点是 0，离开端点 [CORNER_QUIET_FRAC] 之后是 1。
     *
     * 角上那团的径向渐变没法跟着宽度缩放（圆弧的位置由屏幕圆角定死），所以直边的宽度
     * 必须在接缝处回到基准值，两边才始终是同一条剖面。见 [glowWidthPx]。
     *
     * 用**归一化位置**而不是像素：左右两条边、上下两条边长度相同，同一个位置取到同一个系数，
     * 所以"左+右 = 2 × 基准"的守恒不受影响。
     */
    private fun taperAt(mid: Float, lo: Float, hi: Float): Float {
        val pos = ((mid - lo) / (hi - lo)).coerceIn(0f, 1f)
        val d = min(pos, 1f - pos)
        return smoothStep((d / CORNER_QUIET_FRAC).coerceIn(0f, 1f))
    }

    /**
     * 第 [index] 段的果冻强度：**每条边各段各弹各的**，不再整条边一起胀。
     *
     * 相位和幅度都按 (层, 边, 段号) 从 [sliceNoise] 取，跟时间无关，所以图案稳定、不闪。
     * 幅度抖动**只减不增**（截到 `[0, 1]`），于是约四分之一的段正好是 0（那几段完全不动），
     * 其余段的摆幅在 0~1 之间散开 —— 沿边看过去就是"有的地方在变、有的地方没变"。
     *
     * 对边共用同一串随机数（左↔右、上↔下 用同一个 pair）：于是同一个段号上
     * `w(左) + w(右) = 2 × 基准` 这条守恒**逐段**仍然成立，抖动不会破坏"两边加起来的上限"。
     * 反过来，每条边各取各的随机数就会把这条守恒打散 —— 那正是先前特意要避免的。
     */
    private fun sliceJelly(layer: Int, side: Int, index: Int): Float {
        val pair = if (side == SIDE_TOP || side == SIDE_BOTTOM) 0 else 1
        val seed = layer * 8 + pair
        val phase = GLOW_BREATHE_PHASE[layer] +
                GLOW_SLICE_PHASE_JITTER * sliceNoise(seed, index)
        // 幅度只减不增（封顶 1）：每段摆幅都不超过基准，左边变宽时右边只会更窄，
        // "两边加起来 = 2 × 基准"的口径不变。
        val amp = (
                sliceNoise(seed + 4, index) * (1f + GLOW_SLICE_AMP_JITTER) -
                        GLOW_SLICE_AMP_JITTER * 0.5f
                ).coerceIn(0f, 1f)
        return breath(phase) * amp
    }

    /**
     * 沿边连续（插值过）的确定性噪声，0..1：同一个 (seed, 位置) 永远同一个值。
     *
     * 位置以**段**为单位、每 [GLOW_JITTER_BLOCK] 段一个控制点，段内用 smoothstep 插值 ——
     * 相邻段的抖动值因此是渐变的，光带内沿不会被拉成锯齿。
     * 只跟段号有关、**跟时间无关**：这是"稳定地不规则"，不是"每帧乱闪"。
     * 纯 Int / Float 运算，不分配对象，可以在绘制路径上逐段调用。
     */
    private fun sliceNoise(seed: Int, index: Int): Float {
        val u = index / GLOW_JITTER_BLOCK.toFloat()
        val k = floor(u).toInt()
        val f = u - k
        val a = hash01(seed, k)
        val b = hash01(seed, k + 1)
        return a + (b - a) * (f * f * (3f - 2f * f))
    }

    /** 整数哈希 → 0..1，[sliceNoise] 的控制点取值。 */
    private fun hash01(seed: Int, index: Int): Float {
        var h = seed * 374761393 + index * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65536f
    }

    /**
     * 这一层这一边当前位置的辉光宽度（px）——**呼吸真正改的就是它**。
     *
     * 宽度 = 基准 × 收窄 × (1 + 摆幅 · 符号 · 果冻 · [taper])，[GLOW_BREATHE_TRADE] 里有符号约定：
     * 左 / 上是 +1，右 / 下是 −1。所以
     *
     *   w(左) + w(右) = 2 × 基准
     *   w(上) + w(下) = 2 × 基准
     *
     * **逐段恒定**（果冻值逐段不同，但正负两边的果冻口径一样、[taper] 也一样），
     * 也就是"两边宽度加起来的上限"：一边变宽多少，对面就变窄多少。
     * 另外左/上同号，于是整圈是沿「左上 ↔ 右下」对角地涌。
     *
     * 注意这里**不能**给左/右各自独立摆动（比如各走一条自己的曲线）：
     * 那样两边的和会变，就成了"整圈一起变粗变细"。
     *
     * [taper] 见 [taperAt]：靠近角时摆幅收敛到 0，端点上正好回到基准宽度，
     * 与角上那团（固定用基准剖面）严丝合缝。[jelly] 是该段自己的果冻强度（[sliceJelly]）。
     *
     * **现在 [GLOW_BREATHE_TRADE] 全是 0，所以这个函数恒等于基准宽度** ——
     * 呼吸已经搬到核心白线的粗细上（[LINE_SWING_PX]），辉光宽度全程不动，四条边一样宽。
     * 保留这条路径是为了把摆幅调回非 0 就能重新打开（[edgeHalo] 里有对应的快速路径）。
     */
    private fun glowWidthPx(layer: Int, side: Int, taper: Float, jelly: Float): Float {
        val base = glowDepthPx(layer)
        val sign = if (side == SIDE_TOP || side == SIDE_LEFT) 1f else -1f
        // 铺的宽度 = 白线外侧那半段（恒定）+ 内侧这段（会呼吸）
        return LINE_INSET_PX * k +
                base * (1f + GLOW_BREATHE_TRADE[layer] * sign * jelly * taper)
    }

    /** 这一层**往屏幕内**的深度（px）。四条边同一个值。 */
    private fun glowDepthPx(layer: Int): Float = GLOW_DEPTH_PX[layer] * k

    /** 这一层在屏幕上实际铺的宽度（px）：屏幕边缘 → 外侧辉光 → 白线 → 内侧辉光。 */
    private fun edgeBandPx(layer: Int): Float = LINE_INSET_PX * k + glowDepthPx(layer)

    /** 这一层贴边渐变被建成的宽度（= 内缩 + 基准 × (1 + 摆幅)），缩放矩阵的基准值。 */
    private fun maxEdgeBandPx(layer: Int): Float = LINE_INSET_PX * k + maxGlowDepthPx(layer)

    /** 这一层**内侧**那段被建成的深度（= 基准 × (1 + 摆幅)）。 */
    private fun maxGlowDepthPx(layer: Int): Float =
        glowDepthPx(layer) * (1f + GLOW_BREATHE_TRADE[layer])

    /**
     * 把这一边这一层的贴边渐变按 [scale] 压到当前宽度。
     *
     * 渐变是 onSizeChanged 里按"最宽时"建好的，要改宽度就得改它的两端点 ——
     * 每帧重建 8 个 Shader 是不行的（分配 + 上传）。这里用局部矩阵缩放：
     * 以**白线那条脊线**（距屏幕边缘 [LINE_INSET_PX]）为支点缩，脊线钉在线上不动、
     * 两侧按比例伸缩；端点之外由 CLAMP 收成透明，所以带的内侧不会出现硬边。
     *
     * 现在 [GLOW_BREATHE_TRADE] 全 0，传进来的 [scale] 恒为 1（矩阵是单位阵），
     * 这条路径只是备着。
     */
    private fun scaleEdgeShader(layer: Int, side: Int, scale: Float) {
        val pivot = LINE_INSET_PX * k
        val m = glowMatrices[layer][side]
        when (side) {
            SIDE_TOP -> m.setScale(1f, scale, 0f, pivot)
            SIDE_BOTTOM -> m.setScale(1f, scale, 0f, viewH - pivot)
            SIDE_LEFT -> m.setScale(scale, 1f, pivot, 0f)
            else -> m.setScale(scale, 1f, viewW - pivot, 0f)
        }
        edgeShaders[layer * 4 + side]?.setLocalMatrix(m)
    }

    /**
     * 整圈呼吸的归一化强度，-1..1 —— **欠阻尼弹簧（果冻）**，不是正弦。
     *
     * 波前铺完（[Timeline.WAVE_MS]）之前恒定返回 0：跟着水波散开的那段时间各层强度都停在
     * 基准值上，一点不抖。之后是二阶欠阻尼系统的冲激响应——
     *
     *   breath(s) = easeIn · e^(-DECAY · s) · sin(2π · WOBBLES · s) / PEAK
     *
     * 一个周期里先"弹"到最大值（约在周期的 1/(4·WOBBLES) 处，即 4800/12 = 400ms），
     * 再指数衰减着抖 [GLOW_BREATHE_WOBBLES] 下；周期末尾 sin 正好回到 0，
     * 所以循环处不跳变。观感是"一直被轻轻推一下的果冻"：上升快、过冲、回弹、稳住 ——
     * 而不是匀速的正弦呼吸。
     *
     * 第一个周期额外乘一个 0→1 的斜坡：弹簧从静止起振，入场处不会来一下硬的。
     *
     * [phase] 是**周期**占比（不是抖动圈数），用来错开不同位置的节拍：
     * 辉光那层用它做（现在只有一层，[GLOW_BREATHE_PHASE] 这项空着），白线则给**每个采样点**一个
     * 自己的相位（[LINE_JITTER_PHASE]），于是同一条边上各处不同步 ——
     * 那是"呼吸有细节"，不是"波动沿线跑"：散布只有 ±0.08 个周期。
     */
    private fun breath(phase: Float): Float {
        val dt = elapsed - Timeline.WAVE_MS
        if (dt <= 0L) return 0f
        val t = dt / LINE_BREATHE_PERIOD_MS
        val easeIn = t.coerceIn(0f, 1f)          // 第一个周期渐入满幅，之后恒为 1
        val s = (t - phase) - floor(t - phase)   // 周期内进度 0..1（带相位滞后）
        return easeIn * exp(-GLOW_BREATHE_DECAY * s) *
                sin(TWO_PI * GLOW_BREATHE_WOBBLES * s) / GLOW_BREATHE_PEAK
    }

    /**
     * 单边光晕的渐变：**脊线在白线上**，往屏幕边缘那侧（外侧辉光）和往屏幕内那侧（内侧辉光）
     * 各淡出一段。
     *
     * [layer] 决定色带（[GLOW_COLORS]）和衰减曲线（[GLOW_STOPS]）：现在只有一层，
     * 脊线上是纯白，离白线越远越浅，尾巴上带一点低饱和冰蓝。
     *
     * 渐变跨度 = [LINE_INSET_PX]（外侧那半段）+ [depth]（内侧），剖面由 [glowProfile] 排。
     *
     * 只在 onSizeChanged 里调用一次。
     */
    private fun haloGradient(side: Int, depth: Float, layer: Int): LinearGradient {
        val inset = LINE_INSET_PX * k
        val span = inset + depth
        val n = glowProfile(layer, depth, inset)
        val positions = FloatArray(n)
        val colors = IntArray(n)
        for (i in 0 until n) {
            // 深度 0（屏幕边缘）→ 1（内侧尾巴末端）映射到渐变的 0 → 1
            positions[i] = (profileDepth[i] / span).coerceIn(0f, 1f)
            colors[i] = profileColor[i]
            if (i > 0 && positions[i] <= positions[i - 1]) {
                positions[i] = (positions[i - 1] + 0.001f).coerceAtMost(1f)
            }
        }
        return when (side) {
            SIDE_TOP -> LinearGradient(0f, 0f, 0f, span, colors, positions, Shader.TileMode.CLAMP)
            SIDE_BOTTOM ->
                LinearGradient(0f, viewH, 0f, viewH - span, colors, positions, Shader.TileMode.CLAMP)

            SIDE_LEFT -> LinearGradient(0f, 0f, span, 0f, colors, positions, Shader.TileMode.CLAMP)
            else -> LinearGradient(viewW, 0f, viewW - span, 0f, colors, positions, Shader.TileMode.CLAMP)
        }
    }

    // glowProfile 的复用缓冲（只在 onSizeChanged 里跑，绘制路径上不分配）。
    private val profileDepth = FloatArray(GLOW_STOPS[0].size + 1)
    private val profileColor = IntArray(GLOW_STOPS[0].size + 1)

    /**
     * 排一层的亮度剖面，按**深度**给（深度 = 离屏幕边缘多远，px），结果写进 [profileDepth] /
     * [profileColor]，返回用到的断点数。
     *
     * 峰在 `inset`（= [LINE_INSET_PX]，白线所在的那条脊）：
     *
     * - 往屏幕内：`inset + depth × GLOW_STOPS`，也就是这套辉光原来的衰减曲线，一点没变；
     * - 往屏幕边缘：线性升回峰值，屏幕边缘处只剩 [GLOW_EDGE_ALPHA] × 峰值。
     *
     * 于是屏幕上从外到内是"边缘 → 外侧辉光 → 白线 → 内侧辉光"一条连续剖面，
     * 白线两侧都有光（这是"白线不贴着屏幕"的几何来源）。
     */
    private fun glowProfile(layer: Int, depth: Float, inset: Float): Int {
        val stops = GLOW_STOPS[layer]
        val src = GLOW_COLORS[layer]
        val n = stops.size
        profileDepth[0] = 0f
        // 屏幕边缘那一点：同一个颜色，alpha 乘 GLOW_EDGE_ALPHA（色带里的 alpha 是相对值）
        val head = src[0]
        val headAlpha = GLOW_EDGE_ALPHA[layer] * ((head ushr 24) and 0xFF) / 255f
        profileColor[0] = withAlpha(head, headAlpha)
        for (i in 0 until n) {
            profileDepth[i + 1] = inset + depth * stops[i]
            profileColor[i + 1] = src[i]
        }
        return n + 1
    }

    /**
     * 角上某一层的径向渐变：圆心是圆角弧的圆心，半径是 [cornerR]。
     *
     * **圆心就是屏幕圆角的圆心**（`cornerR` 距两边各一个半径）——
     * 内缩会让亮环比真正的显示圆角小几 px，那几 px 没有任何一层在铺，
     * 就会沿圆角露出一条暗边（"把整圈光切断"的那条缝）。
     *
     * 深度归一到半径：圆角弧上 depth = 0，弧心处 depth = [cornerR]。
     * 这样"深度"和直边的"离屏幕边缘多远"是同一个量，两条直边在切线上和它严丝合缝。
     * 剖面的脊线落在 depth = [LINE_INSET_PX] 处 —— 白线的圆弧半径取的正是
     * `cornerR − [LINE_INSET_PX]`，所以**角上脊线也严丝合缝地压在线上**。
     * 深度超过 [cornerR] 的部分会被压进这一段（径向渐变出了圆心没法再算深度）——
     * 现在 [GLOW_DEPTH_PX] = `12`，比 `cornerR`（≈22 设计 px）还小，**所以角上一点都不裁**，
     * 直边和圆角用的是同一条完整剖面。只有把深度调到 22 以上才会开始吃到这个限制
     * （那一段 alpha 已经掉到 `0.2` 以下，即便裁了也看不出来）。
     *
     * 只在 onSizeChanged 里调用一次。
     */
    private fun cornerGradient(corner: Int, depth: Float, layer: Int): RadialGradient {
        val right = corner == CORNER_TR || corner == CORNER_BR
        val bottom = corner == CORNER_BL || corner == CORNER_BR
        val cx = if (right) viewW - cornerR else cornerR
        val cy = if (bottom) viewH - cornerR else cornerR

        // 色带是"由亮到暗"排的，而径向渐变的位置是"半径由小到大"，
        // 两者方向相反，所以要倒着放。倒过来之后位置天然递增，
        // 深度超过 cornerR 的层再夹一次并强制递增，避免出现相等的 stop。
        val n = glowProfile(layer, depth, LINE_INSET_PX * k)
        val rgba = IntArray(n)
        val positions = FloatArray(n)
        for (i in 0 until n) {
            val d = profileDepth[n - 1 - i]
            val p = ((cornerR - d) / cornerR).coerceIn(0f, 1f)
            rgba[i] = profileColor[n - 1 - i]
            positions[i] = if (i > 0 && p <= positions[i - 1]) {
                (positions[i - 1] + 0.001f).coerceAtMost(1f)
            } else {
                p
            }
        }
        return RadialGradient(cx, cy, cornerR, rgba, positions, Shader.TileMode.CLAMP)
    }

    /**
     * 某一处的过渡带宽度（归一化半径）—— **四角附近平滑加宽**。
     *
     * 为什么要按位置改宽度：角上那团光晕是"一次 drawCircle、整块一个 alpha"（见 [cornerHalo]），
     * 而它和相邻的直边切片在同一个位置交接（clip 方框的边界 = 圆角弧的起点）。
     * 早先给角单独写过一条更慢的淡入曲线，结果这个交界就成了一条亮度突变 ——
     * 四个角看起来各压着一块方格。
     *
     * 现在改成纯几何：越靠近四个角中的任意一个，带越宽（淡入越慢）。切片和角用的
     * 是同一条曲线，交界天然连续，方块边不可能出现。
     *
     * [dx] / [dy] 是 [waveReached] 里那套归一化坐标（dy 已经乘过 aspect）。
     */
    private fun revealSpanAt(dx: Float, dy: Float, aspect: Float): Float {
        val d = min(
            min(hypot(dx - 0.5f, dy), hypot(dx + 0.5f, dy)),
            min(hypot(dx - 0.5f, dy - aspect), hypot(dx + 0.5f, dy - aspect))
        )
        val corner = smoothStep((1f - d / CORNER_SOFT_R).coerceIn(0f, 1f))
        return RIPPLE_REVEAL_SPAN * (1f + CORNER_SOFTEN * corner)
    }

    /**
     * 角上的光晕：铺该层的一个径向渐变，只画角上那一格（`[0, cornerR]²`）。
     *
     * 直边那套是"贴边的一维淡出"，照搬到角上就得让相邻两条边各铺一遍 ——
     * 两份 alpha 叠成一份半，角上那团堆光就是这么来的。这里换成径向：铺一遍，
     * 而且"往屏幕内"在角上本来也就是半径方向。
     *
     * 圆心取**屏幕圆角的圆心**、半径取 [cornerR]，和 [edgeHalo] 的起点（同样是 `cornerR`）
     * 正好相切：切线上的深度两套算法都是"离屏幕边缘多远"，所以是同一个剖面，
     * 交界处不会有暗缝。直边的宽度在两端也收敛回基准（[taperAt]），保证这条性质任何时候都成立。
     *
     * 宽度不参与呼吸：圆弧的位置由屏幕圆角定死，径向渐变的 stop 位置没法像直边那样
     * 用局部矩阵缩放（那会把弧本身也挪走）。所以角上固定走基准剖面。
     */
    private fun cornerHalo(c: Canvas, layer: Int, corner: Int, boost: Float) {
        if (cornerR <= 0.5f) return
        val shader = cornerShaders[layer * 4 + corner] ?: return
        val right = corner == CORNER_TR || corner == CORNER_BR
        val bottom = corner == CORNER_BL || corner == CORNER_BR
        val cx = if (right) viewW - cornerR else cornerR
        val cy = if (bottom) viewH - cornerR else cornerR

        // 弧中点（四个角都在 ±45° 方向上，偏移 r/√2）：用它反解显形程度。
        // 一段圆弧只有几十 px，而波前的过渡带有几百 px（角上还会被 CORNER_SOFTEN 加宽到约 390ms），
        // 整段共用一个 alpha 看不出台阶；而这个采样点和相邻切片在同一位置交接，
        // 用的又是同一条曲线（revealSpanAt 是纯几何的），所以交界处不会出现方块边。
        val d = cornerR * 0.70710678f
        val reached = waveReached(
            if (right) cx + d else cx - d,
            if (bottom) cy + d else cy - d
        )
        if (reached <= 0.002f) return

        cornerGlowPaint.shader = shader
        cornerGlowPaint.alpha =
            (reached * layerIntensity(layer) * boost * 255f).toInt().coerceIn(0, 255)

        // 只画角上那一格，别溢到已经铺过光晕的直边上去。
        // 边界正好是两条直边的起点（切线位置），而那里两边的剖面已经完全一致
        // （深度都等于"离屏幕边缘多远"，宽度也都收敛到了基准），所以这条切线不可见。
        val reach = cornerR
        scratch.set(
            if (right) viewW - reach else 0f,
            if (bottom) viewH - reach else 0f,
            if (right) viewW else reach,
            if (bottom) viewH else reach
        )
        c.save()
        c.clipRect(scratch)
        c.drawCircle(cx, cy, cornerR, cornerGlowPaint)
        c.restore()
    }

    /**
     * 线宽呼吸的渐入门（0..1）：从"四边刚好铺满"那一刻起，用 [LINE_SWING_IN_MS] 渐入到 1。
     *
     * 显形阶段恒为 0 —— 白线刚铺出来时就是最细的 [LINE_THIN_PX]，不抖；铺满之后才变粗。
     * 用 smoothStep 而不是线性，是为了"开始变粗"那一刻没有折点。
     */
    private fun lineSwingGate(): Float {
        val fullMs = Timeline.EDGE_START_MS + Timeline.EDGE_REVEAL_MS
        val t = (elapsed - fullMs) / LINE_SWING_IN_MS
        return smoothStep(t.coerceIn(0f, 1f))
    }

    /**
     * 一整圈白色线条：沿**圆角矩形**的轮廓描边，而不是四条直边。
     *
     * 方角的版本会一直画到屏幕角上，而手机的显示圆角把那里切掉了 —— 四个角是空的；
     * 方角本身也不像手机边缘。
     *
     * 关键：整条路径**只描边一次**。早先的版本用 `PathMeasure` 按弧长切成 220 段、
     * 每段单独 `drawPath`，相邻两段各带半个 ROUND cap，叠在一起就是每 ~21px
     * 一个亮点 / 接缝 —— 常驻状态那圈线一直亮着，所以最明显。现在改成：
     *
     * - 波前还没铺满 → 给 [edgeStroke] 挂一枚 [frontMask] 径向渐变，
     *   逐**像素**按 `1 - smoothstep()` 的剖面给 alpha。它和 [waveReached] 同源
     *   （同一个圆心、同一段过渡带），所以显形顺序一点没变，而且比分段采样更连续；
     * - 波前铺满 → 连渐变都不用，纯白一次画完，零接缝零亮点。
     *
     * - **粗细就是呼吸**：[LINE_ALPHA] 亮度恒定，变的是线宽 —— 显形阶段恒为最细的
     *   [LINE_THIN_PX]，四边铺满之后才渐入地随 [breath] 变粗（见 [LINE_SWING_PX] /
     *   [LINE_SWING_IN_MS]）；
     * - **但粗细沿边逐点不同**：每个采样点有自己的相位和幅度（见 [lineJellyAt]），
     *   所以同一条边上不会整段一起胀 —— "左边从上到下都一样"那种机械感就是这么去掉的。
     *   代价是描边做不到变宽，线体改成 [buildRibbon] 拼出来的闭合填充路径。
     * - **亮度**恒定 [LINE_ALPHA]，整圈一致，不参与呼吸。
     *
     * 中线内缩 [LINE_INSET_PX]，两侧都有辉光（辉光的脊线正压在这条中线上）。
     */
    private fun drawEdgeStroke(c: Canvas) {
        if (viewW <= 0f || viewH <= 0f || lineSigma < 3) return
        val path = buildRibbon()

        if (frontMaskFilled()) {
            // 常驻状态（录屏里被长时间盯着的那段）：纯色 + 一次 drawPath
            lineRibbon.shader = null
            lineRibbon.color = withAlpha(0xFFFFFFFF.toInt(), LINE_ALPHA)
            c.drawPath(path, lineRibbon)
            return
        }

        val shader = frontMaskShader() ?: return
        lineRibbon.shader = shader
        // 挂着 shader 时颜色被忽略，但它的 alpha 通道会乘上去 —— 必须是全不透明
        lineRibbon.color = 0xFFFFFFFF.toInt()
        c.drawPath(path, lineRibbon)
    }

    /**
     * 沿中线把白线的采样点算好：位置、**朝屏幕边缘的单位法线**、以及该点的抖动参数。
     *
     * 中线几何是静态的（只跟屏幕尺寸有关），所以 `getPosTan` 只在这里跑一次，
     * 绘制路径上一点都不碰它 —— 每帧只按当前呼吸算半宽。
     *
     * 法线用切线转 90° 得到，正负靠"离屏幕中心的方向"定：圆角矩形上这个判据是稳的
     * （屏幕中心永远在路径内部），于是法线一定朝屏幕边缘那一侧。
     *
     * 抖动参数是**跟时间无关**的环形噪声：`u` 是沿整圈的归一化弧长，
     * [LINE_JITTER_BLOCKS] 个控制点首尾相接（`u = 1` 与 `u = 0` 取到同一个值，所以绕一圈不跳变）。
     */
    private fun buildLineSamples(m: PathMeasure) {
        val step = (LINE_RIBBON_STEP_PX * k).coerceAtLeast(2f)
        val count = (lineLen / step).toInt() + 1   // 末尾那一枚和起点重合，正好绕回来
        if (lineSigma != count) {
            lineSigma = count
            linePx = FloatArray(count)
            linePy = FloatArray(count)
            lineNx = FloatArray(count)
            lineNy = FloatArray(count)
            linePh = FloatArray(count)
            lineAm = FloatArray(count)
            lineHalf = FloatArray(count)
        }

        val pos = FloatArray(2)
        val tan = FloatArray(2)
        val cx = viewW * 0.5f
        val cy = viewH * 0.5f
        for (i in 0 until count) {
            // 最后一枚钉在弧长末尾（= 起点，闭路径的首尾重合），于是它的抖动参数
            // 和第一枚完全相同 —— 收口那一段两侧宽度严丝合缝，不会留个小台阶。
            val d = if (i == count - 1) lineLen else (i * step).coerceAtMost(lineLen)
            m.getPosTan(d, pos, tan)
            val x = pos[0]
            val y = pos[1]
            var nx = -tan[1]
            var ny = tan[0]
            if (nx * (x - cx) + ny * (y - cy) < 0f) {
                nx = -nx
                ny = -ny
            }
            val len = hypot(nx, ny).coerceAtLeast(0.0001f)
            linePx[i] = x
            linePy[i] = y
            lineNx[i] = nx / len
            lineNy[i] = ny / len

            val u = (d / lineLen).coerceIn(0f, 1f)
            // 相位 ±，所以相邻控制点有的先鼓、有的后鼓
            linePh[i] = LINE_JITTER_PHASE * (2f * ringNoise(SEED_LINE_PHASE, u) - 1f)
            // 幅度散在 [下限, 1]：**每个位置都呼吸**，只是有的鼓得多、有的鼓得少。
            // 下限不能给 0 —— 那会让约四分之一的位置整段冻住，整圈的呼吸就看不出来了。
            lineAm[i] = LINE_JITTER_AMP_FLOOR +
                    (1f - LINE_JITTER_AMP_FLOOR) * ringNoise(SEED_LINE_AMP, u)
        }
    }

    /**
     * 环向连续、**首尾相接**的确定性噪声（0..1）：同一个 (seed, u) 永远同一个值。
     *
     * 和 [sliceNoise] 同一套路（控制点之间用 smoothstep 插值，所以宽度沿边是渐变的，
     * 不会出现台阶），区别是这里按**整圈归一化弧长**取样、并且绕回起点 ——
     * 白线是一个闭环，噪声不接上的话，起点/终点那里会突然错开一截宽度。
     */
    private fun ringNoise(seed: Int, u: Float): Float {
        val x = u * LINE_JITTER_BLOCKS
        val i = floor(x).toInt()
        val f = x - i
        val a = hash01(seed, wrapBlock(i))
        val b = hash01(seed, wrapBlock(i + 1))
        return a + (b - a) * (f * f * (3f - 2f * f))
    }

    /** 把控制点序号折回 `0 until LINE_JITTER_BLOCKS`（噪声绕一圈接上）。 */
    private fun wrapBlock(i: Int): Int {
        val n = LINE_JITTER_BLOCKS
        return ((i % n) + n) % n
    }

    /**
     * 第 [i] 个采样点的果冻强度：`breath(自己的相位) × 自己的幅度`。
     *
     * 幅度在 `[LINE_JITTER_AMP_FLOOR, 1]`，所以果冻仍在 `[−0.66, 1.0]` 之内（见 [breath]）、
     * 且**每个位置都真的在动**；`(1 + 果冻) ≥ 0.34 > 0` 这条下限不变：线**只会比最细那一档粗**。
     */
    private fun lineJellyAt(i: Int): Float = breath(linePh[i]) * lineAm[i]

    /**
     * 按当前呼吸拼出线体：**外沿正向走一遍、内沿反向走一遍**，再闭合。
     *
     * 每点的半宽 = `(LINE_THIN_PX + 门 × 幅度 × (1 + 果冻)) / 2`，两侧对称地偏在法线两边，
     * 于是线宽沿边连续变化、内侧外沿都不会出现台阶（对比 [edgeHalo] 的逐段矩形）。
     * 半宽最大 `(2.6 + 1.1 × 2) / 2 = 2.4` 设计 px —— 正好等于 [LINE_INSET_PX]（@720 ≈4.5px），
     * 也就是最粗那一刻线体的外沿刚好贴到屏幕边缘（不越界、不被切）；
     * 圆角半径比它大得多，所以内沿也不会自交。
     */
    private fun buildRibbon(): Path {
        val swing = lineSwingGate() * LINE_SWING_PX
        val n = lineSigma
        for (i in 0 until n) {
            val w = (LINE_THIN_PX + swing * (1f + lineJellyAt(i))).coerceAtLeast(LINE_MIN_PX)
            lineHalf[i] = w * k * 0.5f
        }

        ribbonPath.reset()
        for (i in 0 until n) {
            val h = lineHalf[i]
            val x = linePx[i] + lineNx[i] * h
            val y = linePy[i] + lineNy[i] * h
            if (i == 0) ribbonPath.moveTo(x, y) else ribbonPath.lineTo(x, y)
        }
        for (i in n - 1 downTo 0) {
            val h = lineHalf[i]
            ribbonPath.lineTo(linePx[i] - lineNx[i] * h, linePy[i] - lineNy[i] * h)
        }
        ribbonPath.close()
        return ribbonPath
    }

    /** 波前半径（px，圆心在顶边中点）：与 [waveReached] 里 `edgeReveal * glowRadiusScale` 同式。 */
    private fun frontRadiusPx(): Float = edgeReveal * glowRadiusScale * viewW

    /** 波前的内沿（从这里开始显形）到圆心的距离（px）。 */
    private fun frontNearPx(): Float = frontRadiusPx() - RIPPLE_REVEAL_NEAR * viewW

    /** 环上离波源最远的点（两个底角）到圆心的距离（px）。 */
    private fun frontFarPx(): Float = hypot(viewW * 0.5f, viewH)

    /**
     * 波前是不是已经盖满整圈（连最远的底角都满亮）。
     *
     * [glowRadiusScale] 在 onSizeChanged 里就是按"edgeReveal=1 时刚好盖满最远点"定的，
     * 所以常驻状态必然走到这里 —— 那条路径上没有渐变，也就没有采样误差。
     */
    private fun frontMaskFilled(): Boolean = frontNearPx() >= frontFarPx() - 0.5f

    /**
     * 波前遮罩：圆心在顶边中点的径向渐变，色标按 `1 - smoothstep()` 剖面排。
     *
     * 渐变半径固定成 [frontFarPx]，变的是各色标的位置 —— 所以只有位置挪够
     * [FRONT_MASK_STEP_PX] 时才重建一枚：显形那 1000ms 里几十次，常驻段一次都不分配。
     *
     * 只保留落在渐变域（0..far）之内的那段剖面：域内的 alpha 就等于该处的剖面值，
     * 域外由 CLAMP 取端点值，正好是"波前里面满亮、外面全暗"。
     */
    private fun frontMaskShader(): Shader? {
        val far = frontFarPx()
        val near = frontNearPx()
        val span = RIPPLE_REVEAL_SPAN * viewW
        val d0 = near.coerceAtLeast(0f)
        val d1 = (near + span).coerceAtMost(far)
        if (d1 - d0 < 0.5f) return null

        if (frontMask == null ||
            abs(frontMaskNear - near) > FRONT_MASK_STEP_PX ||
            abs(frontMaskFar - far) > 0.5f
        ) {
            val n = FRONT_MASK_STOPS
            val positions = FloatArray(n)
            val colors = IntArray(n)
            for (i in 0 until n) {
                val d = d0 + (d1 - d0) * i / (n - 1f)
                // 剖面参数：0 = 内沿（满亮），1 = 外沿（全透明）
                val u = ((d - near) / span).coerceIn(0f, 1f)
                positions[i] = d / far
                colors[i] = withAlpha(0xFFFFFFFF.toInt(), (1f - smoothStep(u)) * LINE_ALPHA)
            }
            frontMask = RadialGradient(
                viewW * 0.5f,
                0f,
                far,
                colors,
                positions,
                Shader.TileMode.CLAMP
            )
            frontMaskNear = near
            frontMaskFar = far
        }
        return frontMask
    }

    /** 把 [r] 摆成某条边上的一段，从屏幕边缘向内延伸 [depth]。 */
    private fun edgeSlice(r: RectF, side: Int, s0: Float, s1: Float, depth: Float): RectF {
        when (side) {
            SIDE_TOP -> r.set(s0, 0f, s1, depth)
            SIDE_BOTTOM -> r.set(s0, viewH - depth, s1, viewH)
            SIDE_LEFT -> r.set(0f, s0, depth, s1)
            else -> r.set(viewW - depth, s0, viewW, s1)
        }
        return r
    }

    /**
     * 白线的显形程度：1 = 已经扫过这里，0 = 还没到，中间是平滑过渡。
     *
     * 几何与 RippleRenderer 片元着色器里的 reached 同源（圆心都在顶边中点）：
     * p = (uv.x - 0.5, (1 - uv.y) * aspect)，reached = 1 - smoothstep(radius - 0.10, radius + 0.17, |p|)。
     *
     * 但半径**不跟水波走**，而是跟着白线自己的 [edgeReveal]：水波扫到屏幕一半时半径从 0 起步，
     * 用 [Timeline.EDGE_REVEAL_MS] 匀速推到最远的底角。因为圆心在顶边中点，这个径向半径
     * 天然给出"顶边中点 → 沿顶边往两侧 → 顺两侧向下 → 底边"的顺序。
     *
     * 半径系数用 [glowRadiusScale] 而不是写死的 2.18：让推到头时刚好够到最远的底角
     * （GL 层那道彩虹波纹只推到屏高的 3/4，两者不是同一条边界，也不需要是）。
     * 注意这里**不能**改成对未到达的区域做全局补亮：那样等于抬高了整屏的下限，
     * 收尾阶段所有边会一起亮起来，正好丢掉这个顺序。
     *
     * 另外过渡带的**宽度也不是常数**：越靠近四个角越宽（见 [revealSpanAt]）——
     * 角上那团光晕整块只有一个 alpha，带不宽一点就是"啪"地亮起来；而宽度是按位置连续变化的，
     * 所以它和相邻切片仍然是同一条曲线，交界处不会出现方块边。
     */
    private fun waveReached(x: Float, y: Float): Float {
        if (viewW <= 0f || viewH <= 0f) return 1f
        val aspect = viewH / viewW
        val dx = x / viewW - 0.5f
        val dy = y / viewH * aspect
        val dist = hypot(dx, dy)
        val radius = edgeReveal * glowRadiusScale
        // 带宽度不是常数：四角附近更宽（淡入更慢），这样角上那团和相邻切片是连续的。
        val span = revealSpanAt(dx, dy, aspect)
        return 1f - smoothStep((dist - (radius - RIPPLE_REVEAL_NEAR)) / span)
    }

    /**
     * 白线扫到某一点的时刻（毫秒）——[waveReached] 那套几何的正解。
     *
     * [edgeReveal] 是线性的：t = EDGE_START_MS + EDGE_REVEAL_MS · p，
     * 其中 p 是该点在 [glowRadiusScale] 下的归一化距离。
     *
     * 绘制路径已经不再调用它（整圈呼吸改成同步之后不需要错开相位了），
     * 留着是因为 README 里那张「各处什么时候被点亮」的表就是按它算的。
     */
    private fun edgeArriveMs(x: Float, y: Float): Long {
        if (viewW <= 0f || viewH <= 0f) return 0L
        val aspect = viewH / viewW
        val dist = hypot(x / viewW - 0.5f, y / viewH * aspect)
        val p = (dist / glowRadiusScale).coerceIn(0f, 1f)
        return Timeline.EDGE_START_MS + (Timeline.EDGE_REVEAL_MS * p).toLong()
    }

    // ---------------------------------------------------------- 2. 边缘点亮

    /**
     * 水波抵达边缘后的短暂增强：从 [Timeline.EDGE_MS] 起 1 -> 1+[EDGE_IGNITE_BOOST] -> 1。
     *
     * 只乘在光晕层的 alpha 上，不动任何几何：水波扫过时辉光"亮一下"，
     * 然后平滑交还给常驻呼吸（同一时刻呼吸正在慢慢起振，所以衔接处是连续的）。
     */
    private fun arrivalBoost(): Float {
        val since = (elapsed - Timeline.EDGE_MS).toFloat()
        if (since <= 0f) return 1f
        val t = (since / EDGE_IGNITE_MS).coerceIn(0f, 1f)
        return 1f + EDGE_IGNITE_BOOST * sin(TWO_PI * t * 0.5f)
    }

    /** 光晕层 [layer] 当前的整体强度（已含峰值 alpha 与该层的呼吸）。 */
    private fun layerIntensity(layer: Int): Float =
        GLOW_PEAK_ALPHA[layer] * (1f + GLOW_BREATHE_AMP[layer] * breath(GLOW_BREATHE_PHASE[layer]))

    // ---------------------------------------------------------- 3. 发送面板

    private fun drawTransferPanel(c: Canvas) {
        val since = (elapsed - Timeline.PANEL_MS).toFloat()
        val appear = panelAppearAt(elapsed)
        val textAlpha = (since / PANEL_TEXT_MS).coerceIn(0f, 1f)
        val slide = (1f - easeOutQuint(appear)) * 8f * k

        // 第二层蒙层：面板浮现时铺满全屏的一条竖直渐变（整体压暗下层界面）。
        // 它和 GL 层那条"跟着波前走"的压暗是**两层**，叠起来原图只剩一两成亮度 ——
        // 所以两边都比原型轻：这里约 32% / 35% / 43%（原来 64% / 71% / 85%）。
        scrimPaint.shader = LinearGradient(
            0f, 0f, 0f, viewH,
            intArrayOf(0x52070A10.toInt(), 0x5A080C14.toInt(), 0x6D080C14.toInt()),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        scrimPaint.alpha = (textAlpha * 255f).toInt().coerceIn(0, 255)
        c.drawRect(0f, 0f, viewW, viewH, scrimPaint)

        // 版面：接收方信息贴在照片卡片正上方，整块（信息 + 卡片 + 上滑提示）纵向居中
        val padTop = max(56f * k, min(72f * k, 0.08f * viewH)) + topInset
        val padBottom = 28f * k + bottomInset

        textPaint.typeface = bold
        textPaint.textSize = 16f * k
        val lineH = 16f * k * 1.35f
        val fm1 = textPaint.fontMetrics
        // 信息与卡片之间留一口气，贴太紧会读成"卡片标题"
        val infoGap = 14f * k
        val infoH = lineH + infoGap

        val tipGap = 18f * k
        val tipH = 16f * k * 1.6f
        // 发送态这一行是「上滑发送」提示；接收态换成两个按钮，占位高度按按钮算。
        // 发送态下 tailH 恒等于 tipH，下面几行算式一个数都没变。
        val tailH = if (mode == Mode.RECEIVE) receiveActionsHeight() else tipH

        val available = viewH - padTop - padBottom
        var cardW = min(CARD_W * k, viewW - 100f * k)
        var cardH = cardW * CARD_H / CARD_W
        var blockH = infoH + cardH + tipGap + tailH
        if (blockH > available && available > 0f) {
            val fit = ((available - infoH - tipGap - tailH) / cardH).coerceIn(0.4f, 1f)
            cardW *= fit
            cardH = cardW * CARD_H / CARD_W
            blockH = infoH + cardH + tipGap + tailH
        }

        val top = padTop + ((available - blockH) / 2f).coerceAtLeast(0f)
        val cardTop = top + infoH
        cardRect.set(viewW / 2f - cardW / 2f, cardTop, viewW / 2f + cardW / 2f, cardTop + cardH)

        // 接收态：按钮的几何每帧在这里算一次（只跟 cardRect 有关），
        // 触摸命中判定调的是同一个函数 —— 画在哪就点得到哪（见 [layoutReceiveActions]）。
        if (mode == Mode.RECEIVE) {
            layoutReceiveActions()
        }

        // 面板整体上浮 8px，与卡片自身的入场位移叠加
        val save = c.save()
        c.translate(0f, slide)
        // 卡片以外的文字（接收方信息、上滑提示）按 [panelChromeFade] 淡出：手指往上带，
        // 画面上只剩下那张卡片；松手飞走期间它恒为 0，所以不会在卡片飞出时又亮回来。
        val chrome = panelChromeFade
        val chromeAlpha = textAlpha * chrome
        if (chromeAlpha > 0.003f) {
            // 这行字只跟手指走 [PANEL_CHROME_DRAG_FOLLOW]，被卡片挤着往上让开
            val infoSave = c.save()
            c.translate(0f, dragY * PANEL_CHROME_DRAG_FOLLOW)
            drawReceiverInfo(c, top, fm1, lineH, chromeAlpha)
            c.restoreToCount(infoSave)
        }
        drawCard(c)
        if (chromeAlpha > 0.003f) {
            if (mode == Mode.RECEIVE) {
                // 接收态：原来「上滑发送」那一行换成两个按钮，淡入跟面板进度（appear）
                drawReceiveActions(c, appear)
            } else {
                drawSwipeTip(c, cardTop + cardH + tipGap + tipH / 2f, chromeAlpha)
            }
        }
        c.restoreToCount(save)

        // 底部状态行：发送 / 接收两态都用，画在底部品牌文字上方那一行（setStatus 有值才画）
        if (statusText != null && textAlpha > 0.003f) {
            drawStatusLine(c, textAlpha)
        }
    }

    /** 接收方信息：单行、居中，压在照片卡片正上方。 */
    private fun drawReceiverInfo(
        c: Canvas, top: Float,
        fm: Paint.FontMetrics, lineH: Float, alpha: Float
    ) {
        textPaint.typeface = bold
        textPaint.textSize = 16f * k
        textPaint.letterSpacing = 0.012f
        textPaint.color = withAlpha(0xFFFFFFFF.toInt(), alpha)
        textPaint.setShadowLayer(12f * k, 0f, 2f * k, withAlpha(0xFF000000.toInt(), 0.55f * alpha))
        val textW = textPaint.measureText(receiverText)
        c.drawText(
            receiverText, viewW / 2f - textW / 2f,
            top + lineH / 2f - (fm.ascent + fm.descent) / 2f,
            textPaint
        )
        textPaint.clearShadowLayer()
    }

    private fun drawCard(c: Canvas) {
        if (cardRect.width() <= 0f) return

        // 入场：delay .1s / 位移 .68s / 淡入 .48s
        val enterRaw = ((elapsed - Timeline.PANEL_MS - CARD_DELAY_MS) / CARD_ENTER_MS).coerceIn(0f, 1f)
        val fadeRaw = ((elapsed - Timeline.PANEL_MS - CARD_DELAY_MS) / CARD_FADE_MS).coerceIn(0f, 1f)
        val enter = easeOutQuint(enterRaw)

        var scale = lerp(0.88f, 1f, enter)
        var offsetY = lerp(22f * k, 0f, enter)
        var alpha = fadeRaw

        if (flying) {
            val e = easeOutCubic(flyT)
            offsetY = lerp(flyFromY, -720f * k, e)
            scale = lerp(flyFromScale, 0.72f, e)
            alpha *= (1f - (flyT / 0.76f)).coerceIn(0f, 1f)
        } else if (dragging || dragY != 0f) {
            offsetY += dragY
            scale *= max(0.94f, 1f + dragY / (900f * k))
        }

        if (alpha <= 0.003f) return

        val save = c.save()
        c.translate(cardRect.centerX(), cardRect.centerY() + offsetY)
        c.scale(scale, scale)
        c.translate(-cardRect.centerX(), -cardRect.centerY())

        val r = 23f * k

        // 投影：0 22px 55px #0007，用几层半透明圆角矩形近似
        shadowPaint.color = 0xFF000000.toInt()
        for (i in 0..4) {
            val grow = i * 8f * k
            shadowPaint.alpha = ((26f - i * 5f) * alpha).toInt().coerceIn(0, 255)
            scratch.set(
                cardRect.left - grow,
                cardRect.top - grow + 22f * k * (1f - i * 0.12f),
                cardRect.right + grow,
                cardRect.bottom + grow + 22f * k * (1f - i * 0.12f)
            )
            c.drawRoundRect(scratch, r + grow, r + grow, shadowPaint)
        }

        // 卡片本体
        cardPaint.color = withAlpha(0xFFF8F8F8.toInt(), alpha)
        c.drawRoundRect(cardRect, r, r, cardPaint)

        // 外侧 1px 白色描边（[edgeStroke] 和边缘光共用，先摘掉可能挂着的波前遮罩）
        edgeStroke.shader = null
        edgeStroke.strokeWidth = 1f * k
        edgeStroke.color = withAlpha(0xFFFFFFFF.toInt(), 0.66f * alpha)
        scratch.set(cardRect.left - 0.5f * k, cardRect.top - 0.5f * k, cardRect.right + 0.5f * k, cardRect.bottom + 0.5f * k)
        c.drawRoundRect(scratch, r, r, edgeStroke)

        // 照片区：插画是静态的，用缓存位图，避免常驻微颤动画每帧重画所有路径与渐变
        val photo = RectF(
            cardRect.left + 9f * k,
            cardRect.top + 9f * k,
            cardRect.right - 9f * k,
            cardRect.bottom - 53f * k
        )
        val pr = 15f * k
        val art = artworkBitmap(photo.width().toInt(), photo.height().toInt())
        photoClip.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        val save2 = c.save()
        cardPath.reset()
        cardPath.addRoundRect(photo, pr, pr, Path.Direction.CW)
        c.clipPath(cardPath)
        c.drawBitmap(art, artSrc, photo, photoClip)
        c.restoreToCount(save2)

        // 文字区
        textPaint.typeface = bold
        textPaint.textSize = 12f * k
        textPaint.letterSpacing = 0.008f
        textPaint.clearShadowLayer()
        val fmStrong = textPaint.fontMetrics
        val strongH = fmStrong.descent - fmStrong.ascent

        textPaint.typeface = normal
        textPaint.textSize = 9f * k
        textPaint.letterSpacing = 0f
        val fmSpan = textPaint.fontMetrics
        val spanH = fmSpan.descent - fmSpan.ascent

        val boxBottom = cardRect.bottom - 13f * k
        val spanTop = boxBottom - spanH
        val strongTop = spanTop - 4f * k - strongH

        textPaint.typeface = bold
        textPaint.textSize = 12f * k
        textPaint.color = withAlpha(0xFF252B31.toInt(), alpha)
        c.drawText(cardTitle, cardRect.left + 17f * k, strongTop - fmStrong.ascent, textPaint)

        textPaint.typeface = normal
        textPaint.textSize = 9f * k
        textPaint.color = withAlpha(0xFF737B82.toInt(), alpha)
        c.drawText(cardMeta, cardRect.left + 17f * k, spanTop - fmSpan.ascent, textPaint)

        c.restoreToCount(save)
    }

    private fun drawSwipeTip(c: Canvas, centerY: Float, alpha: Float) {
        // tipFloat: 1.5s 上下浮动 + 明暗呼吸
        val phase = (elapsed % 1500L) / 1500f
        val tri = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
        val floatY = -4f * k * smoothStep(tri)
        // 上滑 / 飞走时的淡出由调用方按 [panelChromeFade] 算进 alpha 里了 —— 这里不再自己算
        // 一份，否则同一个系数乘两遍，提示会比"发送给 xxx"先消失。
        val a = alpha * lerp(0.72f, 1f, smoothStep(tri))
        if (a <= 0.003f) return

        val iconSize = 16f * k
        textPaint.typeface = normal
        textPaint.textSize = 13f * k
        textPaint.letterSpacing = 0f
        val textW = textPaint.measureText(swipeTipText)
        val gap = 7f * k
        val rowW = iconSize + gap + textW
        val startX = viewW / 2f - rowW / 2f
        val cy = centerY + floatY

        iconPaint.color = withAlpha(0xFFFFFFFF.toInt(), a * 0.93f)
        val save = c.save()
        val s = iconSize / 24f
        c.translate(startX, cy - iconSize / 2f)
        c.scale(s, s)
        c.drawPath(arrowUp, iconPaint)
        c.restoreToCount(save)

        textPaint.color = withAlpha(0xFFFFFFFF.toInt(), a * 0.93f)
        textPaint.setShadowLayer(8f * k, 0f, 2f * k, withAlpha(0xFF000000.toInt(), 0.5f * a))
        val fm = textPaint.fontMetrics
        c.drawText(swipeTipText, startX + iconSize + gap, cy - (fm.ascent + fm.descent) / 2f, textPaint)
        textPaint.clearShadowLayer()
    }

    // ---------------------------------------------------------- 3b. 接收确认页（变体）

    /**
     * 算接收态两个按钮的位置（设计 px × k）—— **绘制和触摸命中判定共用的唯一一份几何**。
     *
     * 输入只有两样：面板算好的 [cardRect]（宽高、左右边界）和卡片下沿那口气
     * [RECEIVE_TAIL_GAP_PX]（= 面板里 tipGap 的 18f）。[drawTransferPanel] 每帧调一次，
     * [onReceiveTouch] 判命中之前也调一次 —— 同一个输入、同一个函数，画在哪就能点到哪。
     *
     * 摆法：**主按钮在上、占满卡片宽；次按钮在它下面、同样占满卡片宽**（竖着摞）。
     * 卡片是 292 × 360 设计 px、圆角 23，而这一行原来的「上滑发送」提示只有 25.6 高 ——
     * 两个按钮竖着摞（48 + 10 + 40）正好把这一行撑成一个和卡片同宽的按钮组：
     * 左右和卡片对齐，一眼就是"这张卡片的两个动作"，也比并排两个窄按钮好按。
     */
    private fun layoutReceiveActions() {
        val w = cardRect.width()
        if (w <= 0f) {
            receivePrimaryRect.setEmpty()
            receiveSecondaryRect.setEmpty()
            return
        }
        val left = cardRect.left
        val right = left + w
        val top = cardRect.bottom + RECEIVE_TAIL_GAP_PX * k
        val primaryH = RECEIVE_PRIMARY_H_PX * k
        val gap = RECEIVE_BTN_GAP_PX * k
        val secondaryH = RECEIVE_SECONDARY_H_PX * k
        receivePrimaryRect.set(left, top, right, top + primaryH)
        receiveSecondaryRect.set(left, top + primaryH + gap, right, top + primaryH + gap + secondaryH)
    }

    /** 接收态按钮区的总高度（面板排版用）：和 [layoutReceiveActions] 是同一组常量。 */
    private fun receiveActionsHeight(): Float =
        (RECEIVE_PRIMARY_H_PX + RECEIVE_BTN_GAP_PX + RECEIVE_SECONDARY_H_PX) * k

    /**
     * 接收态的两个按钮：主按钮（浅色底 + 深色字）在上、次按钮（半透明浅灰底 + 浅灰字）在下。
     *
     * 圆角矩形复用卡片那枚 [cardPaint]，文字复用 [textPaint]（字重取标题那档 [bold]、
     * 字号 15 设计 px，和 16 的接收方信息、12 的卡片标题同一档量级）。
     * 淡入跟的是**面板进度** [appear]（= [panelAppearAt]，和卡片 / 白线同一条时间轴）；
     * 调用点在 `translate(0f, slide)` 里面，所以面板整体上浮时按钮跟着一起上浮。
     *
     * 按下的那一枚压深一档底色，给一个"按到了"的反馈（[pressedButton] 由 [onReceiveTouch] 维护）。
     */
    private fun drawReceiveActions(c: Canvas, appear: Float) {
        if (appear <= 0.003f) return
        if (receivePrimaryRect.width() <= 0f) return
        val r = RECEIVE_BTN_R_PX * k

        // 主按钮：浅色底（#EDF7FF），按下时压深一档
        cardPaint.shader = null
        cardPaint.color = withAlpha(
            if (pressedButton == BUTTON_PRIMARY) 0xFFD7EAFB.toInt() else 0xFFEDF7FF.toInt(),
            appear
        )
        c.drawRoundRect(receivePrimaryRect, r, r, cardPaint)

        // 次按钮：半透明浅灰底（#14FFFFFF），按下时同样压深一档
        cardPaint.color = withAlpha(
            if (pressedButton == BUTTON_SECONDARY) 0x29FFFFFF else 0x14FFFFFF,
            appear
        )
        c.drawRoundRect(receiveSecondaryRect, r, r, cardPaint)

        // 文字：主按钮深色（#111923）、次按钮浅灰（#DDDDDD），都居中
        textPaint.typeface = bold
        textPaint.letterSpacing = 0f
        textPaint.clearShadowLayer()
        textPaint.textSize = 15f * k
        textPaint.color = withAlpha(0xFF111923.toInt(), appear)
        drawCenteredLabel(c, receivePrimaryRect, receiveAcceptText)
        textPaint.color = withAlpha(0xFFDDDDDD.toInt(), appear)
        drawCenteredLabel(c, receiveSecondaryRect, receiveRejectText)
    }

    /** 在一枚按钮矩形里居中画一行字（水平按测量宽度、垂直按 fontMetrics 校正）。 */
    private fun drawCenteredLabel(c: Canvas, r: RectF, text: String) {
        val fm = textPaint.fontMetrics
        val x = r.centerX() - textPaint.measureText(text) / 2f
        val y = r.centerY() - (fm.ascent + fm.descent) / 2f
        c.drawText(text, x, y, textPaint)
    }

    /**
     * 底部状态行（[setStatus] 有值才画）：一行中性灰白的小字，画在底部品牌文字上方那一条。
     *
     * 字号 11 设计 px，落在现有小字那一档（卡片副标题 9 / 上滑提示 13）之间；
     * 淡入跟面板文字 [alpha]（= [Timeline.PANEL_MS] + [PANEL_TEXT_MS]），和接收方信息同一档节奏。
     */
    private fun drawStatusLine(c: Canvas, alpha: Float) {
        val text = statusText ?: return
        textPaint.typeface = normal
        textPaint.textSize = 11f * k
        textPaint.letterSpacing = 0f
        textPaint.color = withAlpha(0xFFB9C2CC.toInt(), alpha * 0.92f)
        textPaint.setShadowLayer(8f * k, 0f, 2f * k, withAlpha(0xFF000000.toInt(), 0.5f * alpha))
        val fm = textPaint.fontMetrics
        val y = viewH - bottomInset - STATUS_BOTTOM_PX * k - (fm.ascent + fm.descent) / 2f
        c.drawText(text, viewW / 2f - textPaint.measureText(text) / 2f, y, textPaint)
        textPaint.clearShadowLayer()
    }

    // ---------------------------------------------------------- 上滑手势

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 接收态：整页只做两个按钮的命中判定，不进上滑拖动 / 飞走那套。
        // 发送态（默认）走下面原来那一段，一个字没动。
        if (mode == Mode.RECEIVE) return onReceiveTouch(event)

        if (flying || elapsed < Timeline.PANEL_MS) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!hitCard(event.x, event.y)) return false
                cancelAnimators()
                dragging = true
                downY = event.y
                dragY = 0f
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                dragY = min(0f, event.y - downY)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragY < -SEND_THRESHOLD_DP * k) startFlyAway() else returnCard()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * 接收态的触摸：只判两个按钮。
     *
     * 几何取自 [layoutReceiveActions]（和 [drawReceiveActions] 用的是同一份矩形），
     * 所以画出来的位置就是能点的位置。
     * - `ACTION_DOWN` 记下按中的是哪一枚（两枚都没中 → 返回 false，不吃无关的触摸）；
     * - `ACTION_UP` 时**还在同一枚矩形里**才回调（按下主按钮、抬到次按钮上不算）；
     * - `ACTION_MOVE` / `ACTION_CANCEL` 只维护按下状态，不触发任何回调。
     */
    private fun onReceiveTouch(event: MotionEvent): Boolean {
        if (elapsed < Timeline.PANEL_MS) return false
        // 触摸可能先于第一帧到来：按同一套几何补算一次，保证判定用的矩形就是将要画出来的那两个
        layoutReceiveActions()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedButton = when {
                    receivePrimaryRect.contains(event.x, event.y) -> BUTTON_PRIMARY
                    receiveSecondaryRect.contains(event.x, event.y) -> BUTTON_SECONDARY
                    else -> BUTTON_NONE
                }
                if (pressedButton == BUTTON_NONE) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> return pressedButton != BUTTON_NONE

            MotionEvent.ACTION_UP -> {
                val pressed = pressedButton
                if (pressed == BUTTON_NONE) return false
                pressedButton = BUTTON_NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                if (pressed == BUTTON_PRIMARY) {
                    if (receivePrimaryRect.contains(event.x, event.y)) onReceiveAccepted?.invoke()
                } else {
                    if (receiveSecondaryRect.contains(event.x, event.y)) onReceiveRejected?.invoke()
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (pressedButton == BUTTON_NONE) return false
                pressedButton = BUTTON_NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitCard(x: Float, y: Float): Boolean {
        val pad = 12f * k
        return x >= cardRect.left - pad && x <= cardRect.right + pad &&
                y >= cardRect.top - pad && y <= cardRect.bottom + pad
    }

    /**
     * 卡片里的插画是静态的，渲染一次缓存成 Bitmap。
     * 时间轴进入常驻段后画面会一直重绘，每帧重画十几条路径和渐变太浪费。
     */
    private fun artworkBitmap(width: Int, height: Int): Bitmap {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val cached = artCache
        if (cached != null && !cached.isRecycled && cached.width == w && cached.height == h) {
            return cached
        }
        cached?.recycle()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val photo = cardPhoto
        if (photo != null && !photo.isRecycled) {
            // 用户选的照片：cover 居中裁切（和插画同样的 slice 语义），不拉伸
            canvas.drawBitmap(
                photo,
                coverSource(photo, w, h),
                RectF(0f, 0f, w.toFloat(), h.toFloat()),
                artPaint
            )
        } else {
            PhotoArtwork.draw(canvas, RectF(0f, 0f, w.toFloat(), h.toFloat()))
        }
        artSrc.set(0, 0, w, h)
        artCache = bitmap
        return bitmap
    }

    /**
     * cover 裁切对应的源矩形（居中）。
     *
     * 注意必须是 **`Rect`（整数）**：`Canvas.drawBitmap` 只有
     * `(Bitmap, Rect?, Rect, Paint?)` 和 `(Bitmap, Rect?, RectF, Paint?)` 两个缩放重载，
     * 源矩形不接受 `RectF` —— 目标矩形用 `RectF` 是为了让它正好铺满卡片里的照片区。
     */
    private fun coverSource(src: Bitmap, dstW: Int, dstH: Int): Rect {
        val dstRatio = dstW.toFloat() / dstH
        val srcRatio = src.width.toFloat() / src.height
        return if (srcRatio > dstRatio) {
            // 源更宽：左右各裁掉一条
            val cropW = (src.height * dstRatio).toInt().coerceIn(1, src.width)
            val left = (src.width - cropW) / 2
            Rect(left, 0, left + cropW, src.height)
        } else {
            // 源更高：上下各裁掉一条
            val cropH = (src.width / dstRatio).toInt().coerceIn(1, src.height)
            val top = (src.height - cropH) / 2
            Rect(0, top, src.width, top + cropH)
        }
    }

    /** 松手回弹。 */
    private fun returnCard() {
        val from = dragY
        returnAnimator?.cancel()
        returnAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = LinearInterpolator()
            addUpdateListener {
                dragY = lerp(from, 0f, easeOutCubic(it.animatedValue as Float))
                invalidate()
            }
            start()
        }
    }

    /** 越过阈值：卡片飞走，随后复位时间轴、重播入场（不再有任何「已发送」提示）。 */
    private fun startFlyAway() {
        // 越阈值判定成立的那一刻先回报（**在 flying / 飞走动画启动之前**）；
        // 一次手势只会走到这里一次（`flying` 挡住后续触摸），标志位是第二道保险。
        if (!sendConfirmedNotified) {
            sendConfirmedNotified = true
            onSendConfirmed?.invoke()
        }
        flying = true
        flyFromY = dragY
        flyFromScale = max(0.94f, 1f + dragY / (900f * k))
        flyAnimator?.cancel()
        flyAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FLY_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                flyT = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        resetRunnable = Runnable { finishSend() }.also { postDelayed(it, RESET_DELAY_MS) }
    }

    private fun finishSend() {
        flying = false
        flyT = 0f
        dragY = 0f
        // 这一轮发送结束（`onSendFinished` 是复位语义，这里只是让下一次上滑能再回报一次确认）
        sendConfirmedNotified = false
        // 交回给宿主：复位时间轴，回到照片界面
        onSendFinished?.invoke()
        invalidate()
    }

    private fun cancelAnimators() {
        returnAnimator?.cancel(); returnAnimator = null
        flyAnimator?.cancel(); flyAnimator = null
    }

    override fun onDetachedFromWindow() {
        cancelAnimators()
        resetRunnable?.let { removeCallbacks(it) }
        artCache?.recycle()
        artCache = null
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------- 小工具

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun easeOutCubic(t: Float) = 1f - (1f - t.coerceIn(0f, 1f)).pow(3)

    /** 近似 CSS 的 cubic-bezier(.16, 1, .3, 1)。 */
    private fun easeOutQuint(t: Float) = 1f - (1f - t.coerceIn(0f, 1f)).pow(5)

    private fun smoothStep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24)
}
