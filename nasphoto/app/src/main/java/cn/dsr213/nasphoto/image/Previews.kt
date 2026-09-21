package cn.dsr213.nasphoto.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * 生成"高清预览"：长边压到 [longEdge]，JPEG 质量 [quality]。
 * 这是 A 方案的核心——本地留一份肉眼等同原图的预览，而不是糊图。
 */
object Previews {

    fun dimensions(file: File): IntArray? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        return intArrayOf(o.outWidth, o.outHeight)
    }

    fun makePreview(
        src: File,
        dst: File,
        longEdge: Int,
        quality: Int,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG
    ): Boolean {
        val dim = dimensions(src) ?: return false

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(dim[0], dim[1], longEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = BitmapFactory.decodeFile(src.absolutePath, opts) ?: return false

        // 目标长边 = min(配置值, 原图长边)。小图绝不上采样放大，
        // 大图才等比缩到配置值 —— 预览尺寸始终由原图决定。
        val srcLong = max(bmp.width, bmp.height)
        val target = if (longEdge < srcLong) longEdge else srcLong
        if (target < srcLong) {
            val scale = target.toFloat() / srcLong
            val tw = max(1, (bmp.width * scale).toInt())
            val th = max(1, (bmp.height * scale).toInt())
            val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
            if (scaled != bmp) bmp.recycle()
            bmp = scaled
        }

        bmp = applyExif(bmp, src)

        var ok = false
        try {
            FileOutputStream(dst).use { out ->
                ok = bmp.compress(format, quality, out)
            }
        } finally {
            bmp.recycle()
        }
        return ok
    }

    private fun sampleSize(w: Int, h: Int, target: Int): Int {
        var s = 1
        val longSide = max(w, h)
        while (longSide / s > target * 2) s *= 2
        return s
    }

    private fun applyExif(bmp: Bitmap, src: File): Bitmap {
        return try {
            val exif = ExifInterface(src.absolutePath)
            val ori = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
            val m = Matrix()
            when (ori) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                else -> return bmp
            }
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (r != bmp) bmp.recycle()
            r
        } catch (t: Throwable) {
            bmp
        }
    }
}
