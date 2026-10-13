package com.pri.nfctouchdemo.gfx

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * 从"要发送的那张图"里取**主色**，再把它变浅一档 —— 顶部那枚凸起的光斑用它换色
 * （见 `OverlayView.drawTopBloom` / `TOP_BLOOM_TINT_*`）。
 *
 * 不引第三方库：降采样 + 4bit/通道直方图 + 一个自己定的打分函数，整张图一次几毫秒以内，
 * 而且只在用户换图时跑一次（不在绘制路径上）。
 */
object PhotoTint {

    /** 采样边长：32×32 = 1024 个像素，够统计主色，又不至于在主线程上花时间。 */
    private const val SAMPLE = 32

    /** 低于这个 alpha 的像素不算数（PNG 的透明边）。 */
    private const val MIN_ALPHA = 32

    /** 低于这个亮度（最大通道）的像素整块跳过：合成图常见的黑边 / 暗角不该当主色。 */
    private const val DARK_CUTOFF = 32

    /** 直方图格数：每通道 4bit，所以是 16 × 16 × 16。 */
    private const val BINS = 16 * 16 * 16

    /**
     * 主色（ARGB，alpha 恒为 0xFF）。图是空的 / 全透明 / 全黑时返回 null，
     * 调用方就退回原来的默认色。
     *
     * 打分是 `出现次数 × (0.25 + 饱和度)`：
     *
     * - 不直接取"出现最多的那一格"—— 真实照片里最大的一块往往是天空、墙面、灰阶背景，
     *   那样取出来的主色几乎总是灰的。乘上饱和度之后，**面积小但颜色明确的**那部分会浮上来；
     * - 也不取"饱和度最高的那一格"—— 那会挑中一个孤立的高饱和噪点。两项相乘正好是折中；
     * - 常数 0.25 是地板：整张图都是灰的时候，饱和度项几乎不起作用，
     *   于是它老老实实退回"出现最多的那一格"（灰 → 灰，但至少是这张图的灰）。
     *
     * 每格再取**该格内像素的均值**，所以结果不是被量化过的 4bit 色，而是这一族的平均色。
     */
    fun dominant(bitmap: Bitmap): Int? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val w = min(SAMPLE, bitmap.width)
        val h = min(SAMPLE, bitmap.height)
        val small = if (w == bitmap.width && h == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        }
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()

        val count = IntArray(BINS)
        val sumR = IntArray(BINS)
        val sumG = IntArray(BINS)
        val sumB = IntArray(BINS)
        for (p in px) {
            if ((p ushr 24) < MIN_ALPHA) continue
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (max(r, max(g, b)) < DARK_CUTOFF) continue
            val bin = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            count[bin]++
            sumR[bin] += r
            sumG[bin] += g
            sumB[bin] += b
        }

        var bestScore = 0f
        var bestR = -1
        var bestG = 0
        var bestB = 0
        var bestCount = 0
        for (i in 0 until BINS) {
            val n = count[i]
            if (n == 0) continue
            val r = sumR[i] / n
            val g = sumG[i] / n
            val b = sumB[i] / n
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val sat = if (mx == 0) 0f else (mx - mn).toFloat() / mx
            val score = n * (0.25f + sat)
            if (score > bestScore) {
                bestScore = score
                bestR = r
                bestG = g
                bestB = b
                bestCount = n
            }
        }
        if (bestR < 0 || bestCount == 0) return null
        return Color.rgb(bestR, bestG, bestB)
    }

    /**
     * 「稍微变浅一点」：HSV 里把 V 抬 [whiteMix] 那么多、S 降 35% × [whiteMix]，
     * 最后把 V 兜到不低于 [minValue]。
     *
     * 在 HSV 里抬而不是直接和白色做 RGB 混合：后者会连明暗关系一起抹平
     * （深色图的主色掺白之后是发灰的粉彩，不是"变浅"）。V 只向 1 靠、不越界，
     * 所以调大这个值的结果永远是"更浅"，不会过曝成白块。
     *
     * [minValue] 是给"光"兜底的：光斑是一枚**发光**的包，主色再深也不该让它变成一块暗斑。
     * 给 0 = 完全不兜底，主色多暗就多暗。
     */
    fun lighten(color: Int, whiteMix: Float, minValue: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val m = whiteMix.coerceIn(0f, 1f)
        hsv[2] += (1f - hsv[2]) * m
        hsv[1] *= 1f - 0.35f * m
        if (hsv[2] < minValue) hsv[2] = minValue.coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }
}
