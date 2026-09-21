package cn.dsr213.nasphoto.image

import android.util.Log
import cn.dsr213.nasphoto.video.VideoTranscoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 动态照片（MotionPhoto / Live Photo）工具 —— 实现 **「保动态降级」**。
 *
 * ## 文件结构（实测 OPPO / 小米实现，已在小米相册验证可播放）
 * ```
 * [Primary JPEG] [GainMap JPEG] [MP4 视频]          ← 顺序拼接，无间隙
 * ```
 * 各段位置**不靠 offset 字段**，而是靠 XMP 里 Container 的 `Item:Length`
 * **按声明顺序隐含定位**：
 * ```
 * Item:Semantic="Primary"     Item:Length="0"          ← 0 = 从文件开头到下一段
 * Item:Semantic="GainMap"     Item:Length="329438"     ← 实测 = 到下一段起点的距离
 * Item:Semantic="MotionPhoto" Item:Length="7817421"    ← 实测 = 视频净字节数
 * ```
 * 于是"保动态降级"只需三步：**缩底图**（Android 14+ 框架会把增益图一并缩放）、
 * **转码内嵌视频**、**改 XMP 里两个 Length 再拼回去**。
 *
 * ## 关键结论（已实测）
 * - MPF 里 GainMap 用的是 **相对 TIFF header 的偏移**，Primary 是 0 —— 所以
 *   **XMP 段变长不会让 MPF 失效**，不必重算。（把 XMP 换掉时，GainMap 与 MPF
 *   一起后移，相对差值不变。）
 * - MPF 只描述 Primary + GainMap 两项（`NumberOfImages = 2`），**不含视频**。
 * - 视频起点 = `文件长度 - Container 里 MotionPhoto 的 Length`。
 */
object MotionPhoto {

    private const val TAG = "NasPhotoMotion"
    private const val XMP_NS = "http://ns.adobe.com/xap/1.0/"

    /** 超过这个大小就不在内存里拼了（避免 OOM），退回普通降级 */
    private const val MAX_IN_MEMORY = 96L * 1024 * 1024

    /** 解析出来的关键结构 */
    data class Layout(
        /** 原文件的 XMP 段数据（含命名空间前缀），作为改造模板 */
        val xmpData: ByteArray,
        /** Container 里声明的视频字节数 */
        val motionLength: Int,
        /** 视频在文件中的起点 */
        val videoOffset: Int
    )

    /** 轻量判断：只扫 JPEG 段头，不解码像素 */
    fun isMotionPhoto(file: File): Boolean {
        return try {
            if (file.length() <= 0 || file.length() > MAX_IN_MEMORY) return false
            val d = file.readBytes()
            val xmp = findXmpSegment(d) ?: return false
            val txt = String(xmp.third, Charsets.ISO_8859_1)
            txt.contains("MotionPhoto") || txt.contains("MicroVideo")
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 生成"保动态"的小版本。
     *
     * @return null 表示成功；非 null 是失败原因（调用方据此回退）
     */
    fun rebuild(
        src: File,
        dst: File,
        previewLongEdge: Int,
        previewQuality: Int,
        videoMaxEdge: Int,
        videoBitrateBps: Int,
        videoMaxBytes: Long
    ): String? {
        if (src.length() > MAX_IN_MEMORY) return "文件过大，不适用保动态降级"
        val raw = try {
            src.readBytes()
        } catch (t: Throwable) {
            return "读取失败：${t.message}"
        }

        val layout = parse(raw) ?: return "不是动态照片"
        if (layout.videoOffset <= 0 || layout.videoOffset >= raw.size) return "视频位置异常"

        val dir = src.parentFile ?: return "无父目录"
        val tag = src.name.hashCode().toString(16)
        val mp4Raw = File(dir, ".nasphoto_mv_$tag.mp4")
        val mp4Small = File(dir, ".nasphoto_mv_${tag}_s.mp4")
        val jpegSmall = File(dir, ".nasphoto_mv_${tag}.jpg")

        try {
            // ---- 1) 抠出内嵌视频 ----
            FileOutputStream(mp4Raw).use { it.write(raw, layout.videoOffset, raw.size - layout.videoOffset) }

            // ---- 2) 转码成小视频 ----
            val r = VideoTranscoder.transcode(
                src = mp4Raw,
                dst = mp4Small,
                maxEdge = videoMaxEdge,
                bitrateBps = videoBitrateBps,
                maxBytes = videoMaxBytes,
                preserveHdr = false,           // 动态照片的短视频按 SDR 处理足够
                fallbackDurationMs = 0L
            )
            if (!r.ok || !mp4Small.exists() || mp4Small.length() <= 0L) {
                return "内嵌视频转码失败：${r.reason ?: "未知"}"
            }
            val smallVideo = mp4Small.readBytes()

            // ---- 3) 缩底图（框架会把增益图一并缩放并重新写回）----
            if (!Previews.makePreview(src, jpegSmall, previewLongEdge, previewQuality)) {
                return "底图缩放失败"
            }
            val smallJpeg = jpegSmall.readBytes()

            val smallXmp = findXmpSegment(smallJpeg) ?: return "缩小版缺 XMP 段"
            // ⚠️ 必须用【缩小版自己的】XMP 段位置/长度来替换。
            //    用原文件的会错位，把新 XMP 插进图像数据里（能解码但 XMP 声明丢失）。
            val smallXmpStart = smallXmp.first
            val smallXmpTotal = smallXmp.second

            val (gmStart, gmEnd) = gainMapRange(smallJpeg, smallXmpStart, smallXmpTotal)
                ?: return "缩小版找不到增益图"
            val newGainLen = gmEnd - gmStart

            // ---- 4) 用原 XMP 做模板（保留 GCamera/OpCamera 等属性），改两个 Length ----
            val newXmp = patchXmp(
                origin = layout.xmpData,
                gainMapLen = newGainLen,
                motionLen = smallVideo.size
            ) ?: return "XMP 改造失败"

            val segLen = newXmp.size + 2
            if (segLen > 0xFFFF) return "XMP 过长（${segLen} 字节）"

            // ---- 5) 替换缩小版的 XMP 段 + 追加新视频 ----
            // MPF 用相对偏移，所以 XMP 段长度变化不会让增益图失联。
            val out = ByteArrayOutputStream(smallJpeg.size + smallVideo.size)
            out.write(smallJpeg, 0, smallXmpStart)
            out.write(0xFF)
            out.write(0xE1)
            out.write((segLen ushr 8) and 0xFF)
            out.write(segLen and 0xFF)
            out.write(newXmp)
            out.write(smallJpeg, smallXmpStart + smallXmpTotal,
                smallJpeg.size - smallXmpStart - smallXmpTotal)
            out.write(smallVideo)

            FileOutputStream(dst).use { it.write(out.toByteArray()) }
            Log.i(TAG, "保动态降级: ${src.name} ${raw.size} → ${dst.length()} (视频 ${layout.motionLength} → ${smallVideo.size})")
            return null
        } catch (t: Throwable) {
            Log.w(TAG, "保动态降级异常", t)
            return "重组异常：${t.message}"
        } finally {
            mp4Raw.delete()
            mp4Small.delete()
            jpegSmall.delete()
        }
    }

    // ------------------------------------------------------------------ 解析

    fun parse(d: ByteArray): Layout? {
        val xmp = findXmpSegment(d) ?: return null
        val txt = String(xmp.third, Charsets.ISO_8859_1)
        if (!txt.contains("MotionPhoto") && !txt.contains("MicroVideo")) return null
        val items = parseContainer(txt)
        val motionLen = items["MotionPhoto"] ?: return null
        if (motionLen <= 0 || motionLen >= d.size) return null
        return Layout(
            xmpData = xmp.third,
            motionLength = motionLen,
            videoOffset = d.size - motionLen
        )
    }

    /** 遍历 JPEG 段，返回 APP1(XMP)：段起点、整段长度、段数据 */
    private fun findXmpSegment(d: ByteArray): Triple<Int, Int, ByteArray>? {
        var i = 2
        var guard = 0
        while (i + 4 <= d.size && guard++ < 64) {
            if ((d[i].toInt() and 0xFF) != 0xFF) return null
            val m = d[i + 1].toInt() and 0xFF
            if (m == 0xD8 || m == 0xD9) { i += 2; continue }
            if (m in 0xD0..0xD7) { i += 2; continue }
            if (m == 0xDA) return null                       // 进到图像数据了，XMP 本该在前
            val ln = ((d[i + 2].toInt() and 0xFF) shl 8) or (d[i + 3].toInt() and 0xFF)
            if (m == 0xE1 && ln > 30) {
                val ns = String(d, i + 4, XMP_NS.length, Charsets.ISO_8859_1)
                if (ns.startsWith(XMP_NS)) {
                    return Triple(i, 2 + ln, d.copyOfRange(i + 4, i + 2 + ln))
                }
            }
            i += 2 + ln
        }
        return null
    }

    /** 解析 Container 的 Item:Semantic → Item:Length */
    private fun parseContainer(xmp: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        Regex("""<Container:Item\b.*?/>""", RegexOption.DOT_MATCHES_ALL).findAll(xmp).forEach { m ->
            val block = m.value
            val sem = Regex("""Item:Semantic="([^"]+)"""").find(block)?.groupValues?.get(1)
            val len = Regex("""Item:Length="(\d+)"""").find(block)?.groupValues?.get(1)?.toIntOrNull()
            if (sem != null && len != null) out[sem] = len
        }
        return out
    }

    /**
     * 找"第一张 JPEG 结束 → 第二张 JPEG 结束"的区间（即增益图范围）。
     * 返回 [增益图 SOI 偏移, 增益图 EOI 结束偏移]
     */
    private fun gainMapRange(d: ByteArray, xmpStart: Int, xmpTotal: Int): Pair<Int, Int>? {
        val sos = findSos(d, xmpStart + xmpTotal) ?: return null
        val firstEnd = findEoi(d, sos) ?: return null
        val gmStart = findSoi(d, firstEnd) ?: return null
        val gmEnd = findEoi(d, gmStart + 2) ?: return null
        return gmStart to gmEnd
    }

    private fun findSos(d: ByteArray, from: Int): Int? {
        var i = from
        var guard = 0
        while (i + 4 <= d.size && guard++ < 64) {
            if ((d[i].toInt() and 0xFF) != 0xFF) return null
            val m = d[i + 1].toInt() and 0xFF
            if (m == 0xDA) return i + 2 + (((d[i + 2].toInt() and 0xFF) shl 8) or (d[i + 3].toInt() and 0xFF))
            if (m == 0xD8 || m == 0xD9) { i += 2; continue }
            if (m in 0xD0..0xD7) { i += 2; continue }
            val ln = ((d[i + 2].toInt() and 0xFF) shl 8) or (d[i + 3].toInt() and 0xFF)
            i += 2 + ln
        }
        return null
    }

    /** 熵编码数据里找真正的 EOI（跳过 FF00 填充与 RST 标记），返回 EOI 之后的位置 */
    private fun findEoi(d: ByteArray, from: Int): Int? {
        var i = from
        while (i + 1 < d.size) {
            if ((d[i].toInt() and 0xFF) == 0xFF) {
                val n = d[i + 1].toInt() and 0xFF
                if (n == 0xD9) return i + 2
                if (n == 0x00 || n in 0xD0..0xD7) { i += 2; continue }
                i += 2
            } else i++
        }
        return null
    }

    private fun findSoi(d: ByteArray, from: Int): Int? {
        var i = from
        while (i + 2 < d.size) {
            if ((d[i].toInt() and 0xFF) == 0xFF && (d[i + 1].toInt() and 0xFF) == 0xD8
                && (d[i + 2].toInt() and 0xFF) == 0xFF
            ) return i
            i++
        }
        return null
    }

    // ------------------------------------------------------------------ 改造 XMP

    /**
     * 以原 XMP 为模板：只改 GainMap / MotionPhoto 的 Length 与 OpCamera:VideoLength。
     * 其余属性（GCamera:MotionPhoto、OpCamera:MotionPhotoOwner 等）原样保留 ——
     * 小米相册识别动态照片要靠它们。
     */
    private fun patchXmp(origin: ByteArray, gainMapLen: Int, motionLen: Int): ByteArray? {
        var s = String(origin, Charsets.ISO_8859_1)

        s = Regex("""<Container:Item\b.*?/>""", RegexOption.DOT_MATCHES_ALL).replace(s) { m ->
            val block = m.value
            val sem = Regex("""Item:Semantic="([^"]+)"""").find(block)?.groupValues?.get(1)
            when (sem) {
                "GainMap" -> block.replace(
                    Regex("""Item:Length="\d+""""), "Item:Length=\"$gainMapLen\""
                )
                "MotionPhoto" -> block.replace(
                    Regex("""Item:Length="\d+""""), "Item:Length=\"$motionLen\""
                )
                else -> block
            }
        }
        s = s.replace(
            Regex("""OpCamera:VideoLength="\d+""""),
            "OpCamera:VideoLength=\"$motionLen\""
        )
        return s.toByteArray(Charsets.ISO_8859_1)
    }
}
