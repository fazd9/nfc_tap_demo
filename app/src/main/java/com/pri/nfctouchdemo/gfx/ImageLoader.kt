package com.pri.nfctouchdemo.gfx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import kotlin.math.max

/**
 * 把用户选的那两张图读成动效要用的位图。
 *
 * 动效侧只认位图，解码 / 降采样 / 按比例裁切都在这里做完：
 *
 * - **背景图**走 [decodeCover]：按屏幕比例 **cover 裁切** —— 铺满整屏、不变形，
 *   只是把多出来的那一条裁掉（照片比例和手机屏幕比例不一样时，必须裁或必须拉伸，
 *   这里选裁），再垫一层底色保证不透明；
 * - **卡片照片**只要卡片里那么大，[decode] 降采样之后交给
 *   [com.pri.nfctouchdemo.ui.OverlayView] 按卡片尺寸 cover 裁。
 *
 * 两个坑单独写出来，它们都是"看着不像占满全屏"的直接原因：
 *
 * 1. **EXIF 方向**：竖拍照片常常存成横向像素 + 一个"要旋转 90°"的标记。
 *    `BitmapFactory` 不认这个标记，解出来就是躺倒的 —— 再按屏幕比例 cover 一裁，
 *    画面就成了莫名其妙的一小块。API 28+ 走 `ImageDecoder`（它自己会摆正），
 *    27 回落到 `BitmapFactory`。
 * 2. **透明像素**：带 alpha 的 PNG 铺满屏幕之后，透明的地方会直接透出下层应用，
 *    那就不是"占满全屏"了 —— 所以最后统一垫一层深色底再画图。
 */
object ImageLoader {

    private const val TAG = "ImageLoader"

    /** 默认解码上限（长边）：贴着 GL 纹理的保守上限，同时把内存压住。 */
    private const val MAX_DECODE_SIDE = 1536

    /** 垫在透明像素下面的底色，和演示页 / 主页的深色底一致。 */
    private const val BASE_COLOR = 0xFF050608.toInt()

    /**
     * 解码一张图，按 [dstW] x [dstH] 的比例居中裁切（cover），并保证不透明。
     *
     * 结果就是拿去当 GL 折射源的那张：着色器把它整张铺满屏幕（`uv` 从 0 到 1 走一遍），
     * 所以只要比例和屏幕一致，铺出来就是**占满全屏、还不拉伸**。
     */
    fun decodeCover(context: Context, uri: Uri, dstW: Int, dstH: Int): Bitmap? {
        val source = decode(context, uri) ?: return null
        val cropped = coverCrop(source, dstW, dstH)
        if (cropped !== source) source.recycle()
        val opaque = flatten(cropped)
        if (opaque !== cropped) cropped.recycle()
        return opaque
    }

    /** 按 2 的幂降采样解码，结果长边不超过 [maxSide] 附近。 */
    fun decode(context: Context, uri: Uri, maxSide: Int = MAX_DECODE_SIDE): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeWithImageDecoder(context, uri, maxSide)
        } else {
            decodeWithBitmapFactory(context, uri, maxSide)
        }
    } catch (t: Throwable) {
        Log.e(TAG, "解码失败：$uri", t)
        null
    }

    /**
     * API 28+ 的正路：`ImageDecoder` 会按 EXIF 把图摆正，也能直接指定降采样倍数。
     *
     * 必须用 `ALLOCATOR_SOFTWARE`：GL 上传（`GLUtils.texImage2D`）读不了硬件位图。
     */
    private fun decodeWithImageDecoder(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val sample = sampleSize(info.size.width, info.size.height, maxSide)
            if (sample > 1) decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    /** API 27 的回落：`BitmapFactory` 不认识 EXIF 方向，竖拍照片会躺倒。 */
    private fun decodeWithBitmapFactory(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    /** 系统相册里那个文件名（拿不到就退回 Uri 尾巴）。 */
    fun displayName(context: Context, uri: Uri): String = queryInfo(context, uri).first

    /** 标题 + 副标题：文件名（去掉扩展名、截短，卡片放不下长的）+ 类型 / 大小。 */
    fun describe(context: Context, uri: Uri): Pair<String, String> {
        val (name, size) = queryInfo(context, uri)
        val title = name.substringBeforeLast('.').take(18).ifEmpty { "照片" }
        val mime = context.contentResolver.getType(uri).orEmpty()
        val kind = when {
            mime.contains("png", true) -> "PNG"
            mime.contains("webp", true) -> "WEBP"
            mime.contains("heic", true) || mime.contains("heif", true) -> "HEIC"
            else -> "JPG"
        }
        val sizeText = if (size > 0) " · %.1f MB".format(size / 1024f / 1024f) else ""
        return title to "$kind · 1 张照片$sizeText"
    }

    /** 一次 query 拿文件名和大小。 */
    private fun queryInfo(context: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex)?.let { name = it }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
                }
            }
        }.onFailure { Log.w(TAG, "读不到图片信息，用兜底文案", it) }
        return name to size
    }

    /** 居中裁切到目标比例（只裁不缩，省内存也省时间）。 */
    fun coverCrop(src: Bitmap, dstW: Int, dstH: Int): Bitmap {
        if (dstW <= 0 || dstH <= 0) return src
        val dstRatio = dstW.toFloat() / dstH
        val srcRatio = src.width.toFloat() / src.height
        val cropW: Int
        val cropH: Int
        if (srcRatio > dstRatio) {
            cropH = src.height
            cropW = (src.height * dstRatio).toInt().coerceIn(1, src.width)
        } else {
            cropW = src.width
            cropH = (src.width / dstRatio).toInt().coerceIn(1, src.height)
        }
        return Bitmap.createBitmap(src, (src.width - cropW) / 2, (src.height - cropH) / 2, cropW, cropH)
    }

    /**
     * 垫到不透明底上：透明像素不留洞。
     *
     * 本来就全不透明的（比如 `ImageDecoder` 对不透明 JPEG 给的 RGB_565）直接原样返回，
     * 省掉一次十几 MB 的拷贝。
     */
    private fun flatten(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(BASE_COLOR)
            drawBitmap(src, 0f, 0f, null)
        }
        return out
    }

    /** 按 2 的幂降采样，让长边落到 [maxSide] 附近（比它小一档就停）。 */
    private fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
        var sample = 1
        while (max(w, h) / (sample * 2) >= maxSide) sample *= 2
        return sample
    }
}
