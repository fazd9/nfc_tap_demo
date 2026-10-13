package com.pri.nfctouchdemo.gfx

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max

/**
 * 用 Canvas 复刻原型照片卡片里的「海边的黄昏」SVG 插画。
 *
 * 插画本身画在 274 x 298 的坐标系里，调用方给一个矩形，
 * 内部按 cover 规则居中裁切填满，等价于原型的 preserveAspectRatio="xMidYMid slice"。
 */
object PhotoArtwork {

    private const val VB_W = 274f
    private const val VB_H = 298f

    private val sky: Shader by lazy {
        LinearGradient(
            0f, 0f, 0.2f * VB_W, VB_H,
            intArrayOf(0xFFF5D7C0.toInt(), 0xFFE9C4AD.toInt(), 0xFF91B4BD.toInt()),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private val sea: Shader by lazy {
        LinearGradient(
            0f, 0f, 0.8f * VB_W, VB_H,
            intArrayOf(0xFF86B7BC.toInt(), 0xFF385A68.toInt()),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private val cliff: Shader by lazy {
        LinearGradient(
            0f, 0f, VB_W, VB_H,
            intArrayOf(0xFF657D7C.toInt(), 0xFF263E49.toInt()),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    fun draw(canvas: Canvas, rect: RectF) {
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val scale = max(rect.width() / VB_W, rect.height() / VB_H)

        val save = canvas.save()
        canvas.clipRect(rect)
        canvas.translate(
            rect.centerX() - VB_W * scale / 2f,
            rect.centerY() - VB_H * scale / 2f
        )
        canvas.scale(scale, scale)
        drawScene(canvas)
        canvas.restoreToCount(save)
    }

    private fun drawScene(c: Canvas) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        // 黄昏天空
        c.drawRect(0f, 0f, VB_W, VB_H, fill.apply { shader = sky })

        // 暖阳：外圈用径向渐变替代 SVG 的 feGaussianBlur 软化
        c.drawCircle(
            192f, 72f, 49f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    192f, 72f, 49f,
                    intArrayOf(0xD9FFF0D7.toInt(), 0xB2FFF0D7.toInt(), 0x00FFF0D7),
                    floatArrayOf(0f, 0.6f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        )
        c.drawCircle(192f, 72f, 19f, fill.apply { shader = null; color = 0xD9FFF0D7.toInt() })

        // 远处灰蓝色山脊
        c.drawPath(
            Path().apply {
                moveTo(0f, 130f)
                quadTo(36f, 108f, 70f, 124f)
                quadTo(104f, 140f, 145f, 120f)
                quadTo(186f, 100f, 220f, 111f)
                quadTo(254f, 122f, 274f, 122f)
                lineTo(274f, 298f)
                lineTo(0f, 298f)
                close()
            },
            fill.apply { shader = null; color = 0xFFA3B8B4.toInt(); alpha = 191 }
        )

        // 海面
        c.drawPath(
            Path().apply {
                moveTo(0f, 166f)
                quadTo(53f, 141f, 104f, 165f)
                quadTo(155f, 189f, 202f, 157f)
                quadTo(249f, 125f, 274f, 150f)
                lineTo(274f, 298f)
                lineTo(0f, 298f)
                close()
            },
            fill.apply { shader = sea; alpha = 255 }
        )

        // 海面高光
        c.drawPath(
            Path().apply {
                moveTo(0f, 205f)
                quadTo(48f, 191f, 91f, 207f)
                quadTo(134f, 223f, 180f, 202f)
                quadTo(226f, 181f, 274f, 194f)
            },
            stroke.apply { shader = null; color = 0xFFE9D9C7.toInt(); alpha = 107; strokeWidth = 2f }
        )
        c.drawPath(
            Path().apply {
                moveTo(0f, 232f)
                quadTo(56f, 216f, 114f, 232f)
                quadTo(172f, 248f, 220f, 224f)
                quadTo(268f, 200f, 274f, 231f)
            },
            stroke.apply { color = 0xFFD2DED5.toInt(); alpha = 87; strokeWidth = 1.4f }
        )

        // 前方悬崖剪影
        c.drawPath(
            Path().apply {
                moveTo(-12f, 184f)
                lineTo(28f, 142f)
                lineTo(63f, 159f)
                lineTo(103f, 118f)
                lineTo(134f, 158f)
                lineTo(166f, 141f)
                lineTo(205f, 173f)
                lineTo(240f, 147f)
                lineTo(288f, 184f)
                lineTo(288f, 298f)
                lineTo(-12f, 298f)
                close()
            },
            fill.apply { shader = cliff; alpha = 255 }
        )

        // 更近一层悬崖，压暗拉开层次
        c.drawPath(
            Path().apply {
                moveTo(-10f, 205f)
                lineTo(42f, 167f)
                lineTo(74f, 190f)
                lineTo(110f, 150f)
                lineTo(143f, 183f)
                lineTo(178f, 166f)
                lineTo(224f, 201f)
                lineTo(282f, 177f)
                lineTo(282f, 298f)
                lineTo(-10f, 298f)
                close()
            },
            fill.apply { shader = null; color = 0xFF294653.toInt(); alpha = 199 }
        )

        // 水面细纹
        c.drawPath(
            Path().apply {
                moveTo(20f, 258f)
                quadTo(78f, 241f, 135f, 258f)
                quadTo(192f, 275f, 266f, 252f)
            },
            stroke.apply { color = 0xFFADC8C2.toInt(); alpha = 64; strokeWidth = 1f }
        )

        // 几点星芒
        val spark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
        c.drawCircle(46f, 54f, 1.3f, spark)
        c.drawCircle(65f, 83f, 1f, spark)
        c.drawCircle(104f, 42f, 1.4f, spark)
        c.drawCircle(231f, 111f, 1f, spark)
    }
}
