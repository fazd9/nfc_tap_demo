package com.pri.nfctouchdemo.gfx

import android.graphics.Bitmap

/**
 * 背景图的**模糊版**：面板（发送的照片）出现时，GL 层用它把整张背景糊掉。
 *
 * 为什么不在片元着色器里直接做卷积：模糊半径要到屏幕上十几二十 px，9 个抽样的核远远不够，
 * 抽稀了会出现"重影"；真要做就得两趟 FBO，多一套离屏渲染。这里改成**先在 CPU 上算一次**，
 * 传一张更小的纹理给 GL，用 `GL_LINEAR` 放大回同一块区域 —— 每帧只多一次采样，
 * 模糊半径想要多大有多大，代价固定。
 *
 * 两步都不省：
 *
 * 1. **逐级对半降采样**到约 `1 / SCALE_DIV`。一次缩 4 倍是"4 个像素里抽 1 个"，
 *    背景里的高频（树叶、砖墙）会走样成一片噪点；每次只缩一半时双线性抽样的正好是
 *    2×2 的均值，等于标准的 box 降采样，干净。
 * 2. 在这个小图上做 [PASSES] 遍**盒模糊**（可分离：先横后竖，滑动窗口求和，与半径无关）。
 *    两遍盒的等效核是三角形，比一遍盒柔和得多，看不出方块。
 *
 * 半径按小图算（[BOX_RADIUS]），折算回原图要乘 [SCALE_DIV] —— 现在约 24px。
 */
internal object BackdropBlur {

    /** 模糊纹理的边长约为原图的 1/4（GL 负责放大 4 倍，放大倍率小才不会有"折面"感）。 */
    private const val SCALE_DIV = 4

    /** 小图上的盒半径：1/4 尺度上的 6 → 原图约 24px。 */
    private const val BOX_RADIUS = 6

    /** 盒模糊遍数：2 遍 ≈ 三角核。再多只是更慢，观感几乎不变。 */
    private const val PASSES = 2

    /** 最小尺寸兜底：再小的图没有模糊的意义。 */
    private const val MIN_SIDE = 8

    /**
     * 生成 [src] 的模糊版；图太小或不可用（已回收）时返回 null，调用方就保持清晰。
     *
     * 只在换背景图时调一次（在 UI 线程上，不是 GL 线程）：@1080 图上输出约 270×600，
     * 两遍盒合计约 65 万次像素运算，几毫秒。
     */
    fun of(src: Bitmap): Bitmap? {
        if (src.isRecycled || src.width < MIN_SIDE || src.height < MIN_SIDE) return null
        val targetW = (src.width / SCALE_DIV).coerceAtLeast(MIN_SIDE)
        val targetH = (src.height / SCALE_DIV).coerceAtLeast(MIN_SIDE)

        // 1) 逐级对半，降到目标尺寸附近（每一步都 ≤ 2 倍，不会走样）
        var work: Bitmap = src
        var owned = false
        while (work.width / 2 >= targetW && work.height / 2 >= targetH) {
            val next = Bitmap.createScaledBitmap(work, work.width / 2, work.height / 2, true)
            if (owned) work.recycle()
            work = next
            owned = true
        }
        if (work.width != targetW || work.height != targetH) {
            val next = Bitmap.createScaledBitmap(work, targetW, targetH, true)
            if (owned) work.recycle()
            work = next
            owned = true
        }

        val w = work.width
        val h = work.height
        val px = IntArray(w * h)
        work.getPixels(px, 0, w, 0, 0, w, h)
        if (owned) work.recycle()

        // 2) 可分离盒模糊：先横后竖，来回 [PASSES] 遍
        val scratch = IntArray(px.size)
        repeat(PASSES) { boxBlur(px, scratch, w, h, BOX_RADIUS) }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(px, 0, w, 0, 0, w, h)
        }
    }

    /** 一遍可分离盒模糊：`px → scratch`（横向）再 `scratch → px`（纵向）。 */
    private fun boxBlur(px: IntArray, scratch: IntArray, w: Int, h: Int, radius: Int) {
        if (radius < 1 || w < 2 || h < 2) return
        horizontal(px, scratch, w, h, radius)
        vertical(scratch, px, w, h, radius)
    }

    /**
     * 横向滑动窗口求和。
     *
     * 窗口是 `[x − r, x + r]`，两边都**夹取**到图内（等价于 CLAMP_TO_EDGE），
     * 于是边缘不会往内吸暗边。窗口每移一格只加一个、减一个，和半径无关。
     */
    private fun horizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val win = r * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            var a = 0
            var cr = 0
            var cg = 0
            var cb = 0
            for (i in -r..r) {
                val c = src[row + i.coerceIn(0, w - 1)]
                a += (c ushr 24) and 0xFF
                cr += (c shr 16) and 0xFF
                cg += (c shr 8) and 0xFF
                cb += c and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = ((a / win) shl 24) or ((cr / win) shl 16) or
                    ((cg / win) shl 8) or (cb / win)
                val out = src[row + (x - r).coerceIn(0, w - 1)]
                val inn = src[row + (x + r + 1).coerceIn(0, w - 1)]
                a += ((inn ushr 24) and 0xFF) - ((out ushr 24) and 0xFF)
                cr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                cg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                cb += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }

    /** 纵向滑动窗口求和：和 [horizontal] 同一套，只是相邻像素相差 `w`。 */
    private fun vertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val win = r * 2 + 1
        for (x in 0 until w) {
            var a = 0
            var cr = 0
            var cg = 0
            var cb = 0
            for (i in -r..r) {
                val c = src[i.coerceIn(0, h - 1) * w + x]
                a += (c ushr 24) and 0xFF
                cr += (c shr 16) and 0xFF
                cg += (c shr 8) and 0xFF
                cb += c and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = ((a / win) shl 24) or ((cr / win) shl 16) or
                    ((cg / win) shl 8) or (cb / win)
                val out = src[(y - r).coerceIn(0, h - 1) * w + x]
                val inn = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                a += ((inn ushr 24) and 0xFF) - ((out ushr 24) and 0xFF)
                cr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                cg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                cb += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }
}
