package cn.dsr213.nasphoto.video

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

data class VideoColor(val transfer: Int, val standard: Int)

/**
 * 从文件本身探测色彩信息。
 *
 * 为什么需要它：MediaStore 的色彩列只对"设备自己录制/被正确索引"的文件有效；
 * 从外部拷进来、或某些 App 生成的文件，这三列可能是 NULL。
 * 这时若直接判"未知"，所有视频都会走保守路径 —— 所以要回退到读文件本身。
 */
object VideoProbe {

    fun colorOf(f: File): VideoColor? {
        var ex: MediaExtractor? = null
        try {
            ex = MediaExtractor()
            ex.setDataSource(f.absolutePath)
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                val ct = runCatching {
                    fmt.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                }.getOrDefault(0)
                val cs = runCatching {
                    fmt.getInteger(MediaFormat.KEY_COLOR_STANDARD)
                }.getOrDefault(0)
                if (ct == 0 && cs == 0) return null
                return VideoColor(ct, cs)
            }
            return null
        } catch (t: Throwable) {
            return null
        } finally {
            runCatching { ex?.release() }
        }
    }
}
