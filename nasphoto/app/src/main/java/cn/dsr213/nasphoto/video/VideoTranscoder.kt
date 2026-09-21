package cn.dsr213.nasphoto.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** 转码结果。失败时 [reason] 说明原因，调用方应退回"只备份"，绝不丢数据。 */
data class TranscodeOutcome(
    val ok: Boolean,
    val reason: String? = null,
    val codec: String = "",
    val bitrateBps: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val hdrPreserved: Boolean = false
)

/**
 * 视频转码：解码 → 编码 → 封装 MP4，产物是**本地小版本**。
 *
 * 对齐 iCloud「优化储存空间」的视频代理规格：
 * - **编码用 HEVC**（同画质比 H.264 省 30–50%，也是 iCloud 的选择）
 * - **单段体积上限**（默认 100 MB，与 iCloud 一致）→ 码率按"上限 ÷ 时长"动态压
 * - HDR 源尽量**保留 HDR**（HEVC Main10 + 复制色彩元数据），而不是粗暴转 SDR
 * - 音频若为 AAC 则**直接复用**，不重编码
 *
 * 失败逐级回退：HEVC(Main10) → HEVC → H.264 → 交由调用方退回"只备份"。
 * **母本始终已在 NAS 上**，所以任何一步失败都不会丢数据。
 *
 * ## ⚠️⚠️ 缩放通路在本机是坏的（2026-09-15 实测定位）
 *
 * **症状**：产出的视频大面积**绿色/紫色重影**，左侧约 30% 是好的。
 *
 * **排查结论**（逐项变量对照，均以 ffmpeg 解码后比对像素为准）：
 *
 * | 变量 | 结果 |
 * |---|---|
 * | 源有无 rotation | **无关**。剥掉旋转前后产物**字节完全相同**，无旋转源照样复现 |
 * | 编码器 HEVC / H.264 | **无关**。两者都坏，且坏法完全一致 |
 * | 是否复制色彩元数据 | **无关** |
 * | 编码尺寸 = 源尺寸(1728×1296) | ✅ **Y-NCC=1.000 / Cb=0.992 / Cr=0.994，像素级完美** |
 * | 编码尺寸 1280×960（缩放 0.74×） | ❌ 坏 |
 * | 编码尺寸 640×480（缩放 0.37×） | ❌ 坏 |
 *
 * **机理**：解码器输出 Surface → 编码器输入 Surface 之间**缩放根本没生效**。
 * 编码器直接拿到解码器的**原始缓冲区**（1728×1296），却按自己配置的尺寸（1280×960）
 * 去算平面偏移 —— 于是：
 * - 亮度：按缓冲区真实跨距 1728 逐行读，只要 1280 列 ⇒ 碰巧是"左上角 1:1 裁切"，
 *   所以画面**看着像那只猫**（这也是为什么很容易误判成"只是颜色偏了"）
 *   实测模型 `app[y][x] = src[y*1728 + x]` 的 NCC = **0.988**
 * - 色度：平面起点按 1280×960 算 = 字节 1,228,800，而真实色度平面在 2,239,488
 *   ⇒ 读到的是**亮度数据**，于是出现大面积绿紫
 *
 * **注意它不报任何错**：解码 0 error、编码正常出帧、MP4 结构完全合法。
 * 只有把产物解出来比对像素才能发现 —— 所以验证时必须**抽帧比 NCC**，不能只看能不能播。
 *
 * **对策**：**不做缩放**，编码尺寸 = 源尺寸（1:1 走的是零拷贝路径，逐像素正确）；
 * 体积继续由 `maxBytes` / 码率控制 —— 因为**体积 = 码率 × 时长，本来就与分辨率无关**，
 * 所以"保持原分辨率 + 降码率"完全能达到同样的瘦身效果。
 */
object VideoTranscoder {

    private const val TIMEOUT_US = 10_000L
    private const val AUDIO_CACHE_CAP = 32 * 1024 * 1024

    /** 连续这么久没有任何编解码进展就判定为停滞，直接失败退出 */
    private const val STALL_MS = 45_000L

    /** 内层取样本循环的兜底上限，防止异常文件导致空转 */
    private const val MAX_SEEK_STEPS = 100_000

    /** 码率下限，低于这个画面会明显糊 */
    private const val MIN_BITRATE = 1_200_000

    private const val NO_PROFILE = -1

    fun transcode(
        src: File,
        dst: File,
        maxEdge: Int,
        bitrateBps: Int,
        maxBytes: Long,
        preserveHdr: Boolean,
        fallbackDurationMs: Long = 0L,
        /**
         * 是否让解码器原样输出（不应用源文件的 `rotation-degrees`）。
         * 默认 true：旋转只应该写进输出 MP4 的显示矩阵，不该由解码器代劳。
         * （实测本机解码器并不会自己转，但保持显式关闭更稳。）
         */
        stripRotation: Boolean = true,
        /**
         * 是否允许把画面**缩小**到 `maxEdge`。
         *
         * ⚠️ 默认 false，而且是**必须**的 —— 见本类顶部「缩放通路在本机是坏的」一节。
         *
         * true 只用于排查/未来在别的机型上验证缩放通路。
         */
        allowDownscale: Boolean = false,
        /** 是否把源的色彩元数据复制给编码器（排查用） */
        copyColor: Boolean = true,
        /** 强制使用的编码器 MIME（排查用），null = 按默认候选顺序 */
        forceMime: String? = null
    ): TranscodeOutcome {
        var ex: MediaExtractor? = null
        try {
            ex = MediaExtractor()
            ex.setDataSource(src.absolutePath)

            var vIdx = -1
            var aIdx = -1
            var vFmt: MediaFormat? = null
            var aFmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("video/") && vIdx < 0) {
                    vIdx = i; vFmt = f
                } else if (m.startsWith("audio/") && aIdx < 0) {
                    aIdx = i; aFmt = f
                }
            }
            val srcFmt = vFmt ?: return TranscodeOutcome(false, "无视频轨")
            val srcMime = srcFmt.getString(MediaFormat.KEY_MIME) ?: return TranscodeOutcome(false, "视频轨缺 MIME")

            val srcW = runCatching { srcFmt.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0)
            val srcH = runCatching { srcFmt.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0)
            if (srcW <= 0 || srcH <= 0) return TranscodeOutcome(false, "视频尺寸未知")
            // ⚠️ 编码尺寸一律取源尺寸（1:1），不做缩放 —— 理由见本类顶部说明
            val (tw, th) = if (allowDownscale) targetSize(srcW, srcH, maxEdge) else evenSize(srcW, srcH)
            val fps = frameRateOf(srcFmt)

            val durUs = runCatching { srcFmt.getLong(MediaFormat.KEY_DURATION) }
                .getOrDefault(fallbackDurationMs * 1000L)
            val effBitrate = effectiveBitrate(bitrateBps, maxBytes, durUs)

            // 候选编码器：HDR 优先 Main10，其次普通 HEVC，最后 H.264
            val candidates = ArrayList<Pair<String, Int>>()
            if (forceMime != null) {
                candidates.add(forceMime to NO_PROFILE)
            } else {
                if (preserveHdr) candidates.add("video/hevc" to MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                candidates.add("video/hevc" to NO_PROFILE)
                candidates.add("video/avc" to NO_PROFILE)
            }

            var lastReason = "未知错误"
            for ((mime, profile) in candidates) {
                val r = attempt(
                    ex, srcMime, srcFmt, aFmt, aIdx, vIdx,
                    dst, tw, th, fps, effBitrate, mime, profile,
                    preserveHdr, durUs, stripRotation, copyColor
                )
                if (r.ok) return r
                lastReason = r.reason ?: "失败"
                dst.delete()
            }
            return TranscodeOutcome(false, lastReason)
        } catch (t: Throwable) {
            return TranscodeOutcome(false, t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { ex?.release() }
        }
    }

    /**
     * 单次尝试。aligned to iCloud：码率取「设定值」与「体积上限 ÷ 时长」的较小者。
     */
    private fun attempt(
        ex: MediaExtractor,
        srcMime: String,
        srcFmt: MediaFormat,
        aFmt: MediaFormat?,
        aIdx: Int,
        vIdx: Int,
        dst: File,
        tw: Int,
        th: Int,
        fps: Int,
        bitrate: Int,
        encMime: String,
        encProfile: Int,
        preserveHdr: Boolean,
        durUs: Long,
        stripRotation: Boolean,
        copyColor: Boolean
    ): TranscodeOutcome {
        var mux: MediaMuxer? = null
        var dec: MediaCodec? = null
        var enc: MediaCodec? = null
        try {
            ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val outFmt = MediaFormat.createVideoFormat(encMime, tw, th)
            outFmt.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            outFmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            outFmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            outFmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (encProfile != NO_PROFILE) {
                outFmt.setInteger(MediaFormat.KEY_PROFILE, encProfile)
            }
            // 把源文件的色彩元数据原样带到输出，HDR 才不会发灰
            if (copyColor) {
                copyIfPresent(srcFmt, outFmt, MediaFormat.KEY_COLOR_TRANSFER)
                copyIfPresent(srcFmt, outFmt, MediaFormat.KEY_COLOR_STANDARD)
                copyIfPresent(srcFmt, outFmt, MediaFormat.KEY_COLOR_RANGE)
            }

            enc = MediaCodec.createEncoderByType(encMime)
            enc.configure(outFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            // 旋转信息先读出来：它只应该体现在**输出 MP4 的显示矩阵**上，
            // 绝不能让解码器去转正画面（见 stripRotationFromDecoder）
            val rot = runCatching { srcFmt.getInteger(MediaFormat.KEY_ROTATION) }.getOrDefault(0)

            val surface = enc.createInputSurface()
            enc.start()

            dec = MediaCodec.createDecoderByType(srcMime)
            dec.configure(
                if (stripRotation) stripRotationFromDecoder(srcFmt) else srcFmt,
                surface, null, 0
            )
            dec.start()
            // 显式声明"缩放到铺满"：文档说默认就是它，但实测某些机型/解码器
            // 不设这个就不会做缩放（画面变成 1:1 裁切 + 色度错位 → 绿紫）
            runCatching { dec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT) }

            mux = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val rotNorm = ((rot % 360) + 360) % 360
            if (rotNorm == 90 || rotNorm == 180 || rotNorm == 270) {
                mux.setOrientationHint(rotNorm)
            }

            val audioMime = aFmt?.getString(MediaFormat.KEY_MIME) ?: ""
            val audioCopy = aIdx >= 0 && aFmt != null &&
                (audioMime.contains("aac") || audioMime.contains("mp4a"))

            var vTrack = -1
            var aTrack = -1
            if (audioCopy) aTrack = mux.addTrack(aFmt!!)
            var started = false

            val pendingData = ArrayList<ByteArray>()
            val pendingInfo = ArrayList<MediaCodec.BufferInfo>()
            var pendingBytes = 0

            ex.selectTrack(vIdx)
            if (audioCopy) ex.selectTrack(aIdx)

            val aBuf = ByteBuffer.allocate(512 * 1024)
            val info = MediaCodec.BufferInfo()

            fun consumeAudioSample() {
                if (!audioCopy) return
                val sz = ex.readSampleData(aBuf, 0)
                if (sz <= 0) return
                aBuf.position(0)
                aBuf.limit(sz)
                val data = ByteArray(sz)
                aBuf.get(data)
                val bi = MediaCodec.BufferInfo().apply { set(0, sz, ex.sampleTime, 0) }
                if (started && aTrack >= 0) {
                    mux.writeSampleData(aTrack, ByteBuffer.wrap(data), bi)
                } else if (pendingBytes + sz <= AUDIO_CACHE_CAP) {
                    pendingData.add(data)
                    pendingInfo.add(bi)
                    pendingBytes += sz
                }
            }

            var inDone = false
            var outDone = false
            var encDone = false
            var encEosSent = false
            var lastProgress = System.currentTimeMillis()

            while (!encDone) {
                if (System.currentTimeMillis() - lastProgress > STALL_MS) {
                    return TranscodeOutcome(false, "转码停滞超时（${STALL_MS / 1000}s 无进展）")
                }

                if (!inDone) {
                    val ii = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (ii >= 0) {
                        lastProgress = System.currentTimeMillis()
                        val ib = dec.getInputBuffer(ii)!!
                        var filled = -1
                        var pts = 0L
                        var steps = 0
                        while (steps++ < MAX_SEEK_STEPS) {
                            val ti = ex.sampleTrackIndex
                            if (ti < 0) break
                            if (ti == vIdx) {
                                filled = ex.readSampleData(ib, 0)
                                pts = ex.sampleTime
                                break
                            }
                            if (ti == aIdx) consumeAudioSample()
                            ex.advance()
                        }
                        if (filled < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            dec.queueInputBuffer(ii, 0, filled, pts, 0)
                            ex.advance()
                        }
                    }
                }

                if (!outDone) {
                    val oi = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (oi >= 0) {
                        lastProgress = System.currentTimeMillis()
                        dec.releaseOutputBuffer(oi, true)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outDone = true
                        }
                    }
                }

                // 输入是 Surface，必须显式通知编码器结束，否则永远等不到 EOS 输出
                if (outDone && !encEosSent) {
                    enc.signalEndOfInputStream()
                    encEosSent = true
                    lastProgress = System.currentTimeMillis()
                }

                val ei = enc.dequeueOutputBuffer(info, TIMEOUT_US)
                if (ei == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    lastProgress = System.currentTimeMillis()
                    vTrack = mux.addTrack(enc.outputFormat)
                    mux.start()
                    started = true
                    for (i in pendingData.indices) {
                        mux.writeSampleData(aTrack, ByteBuffer.wrap(pendingData[i]), pendingInfo[i])
                    }
                    pendingData.clear()
                    pendingInfo.clear()
                    pendingBytes = 0
                } else if (ei >= 0) {
                    lastProgress = System.currentTimeMillis()
                    val eb = enc.getOutputBuffer(ei)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started && eb != null) {
                        eb.position(info.offset)
                        eb.limit(info.offset + info.size)
                        mux.writeSampleData(vTrack, eb, info)
                    }
                    enc.releaseOutputBuffer(ei, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encDone = true
                }
            }

            // 收尾：把 extractor 里剩余的音频样本写掉
            if (audioCopy && started && aTrack >= 0) {
                var steps = 0
                while (steps++ < MAX_SEEK_STEPS) {
                    val ti = ex.sampleTrackIndex
                    if (ti < 0) break
                    if (ti == aIdx) consumeAudioSample()
                    ex.advance()
                }
            }

            if (!started) return TranscodeOutcome(false, "编码器未产出任何帧")
            if (dst.length() <= 0L) return TranscodeOutcome(false, "输出为空")

            val hdrKept = preserveHdr && encMime == "video/hevc" &&
                encProfile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
            return TranscodeOutcome(
                ok = true, codec = encMime, bitrateBps = bitrate,
                width = tw, height = th, hdrPreserved = hdrKept
            )
        } catch (t: Throwable) {
            return TranscodeOutcome(false, "${encMime}: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { enc?.stop() }
            runCatching { enc?.release() }
            runCatching { dec?.stop() }
            runCatching { dec?.release() }
            runCatching { mux?.stop() }
            runCatching { mux?.release() }
        }
    }

    /** 有效码率 = min(设定码率, 体积上限 ÷ 时长)，并保证不低于下限 */
    private fun effectiveBitrate(configuredBps: Int, maxBytes: Long, durUs: Long): Int {
        if (maxBytes <= 0 || durUs <= 0) return configuredBps
        val secs = durUs / 1_000_000.0
        if (secs <= 0.5) return configuredBps
        val capBps = (maxBytes * 8.0 / secs).toInt()
        val chosen = minOf(configuredBps, capBps)
        return max(chosen, MIN_BITRATE)
    }

    private fun copyIfPresent(from: MediaFormat, to: MediaFormat, key: String) {
        try {
            to.setInteger(key, from.getInteger(key))
        } catch (_: Throwable) {
            // 源里没有这个键就跳过
        }
    }

    /**
     * 把 `rotation-degrees` 从交给**解码器**的 MediaFormat 里去掉。
     *
     * 按 Android 文档，解码器处于 Surface 模式时会**自动应用** crop / rotation /
     * scaling。旋转本应只体现在输出 MP4 的显示矩阵上（`MediaMuxer.setOrientationHint`），
     * 所以这里显式不让解码器代劳。
     *
     * ⚠️ 这条**不是**绿紫重影的病因（实测剥离前后产物字节完全相同，
     * 且源无旋转时同样复现）。保留它是为了语义正确。真正的病因见本类顶部「缩放通路在本机是坏的」一节。
     */
    private fun stripRotationFromDecoder(fmt: MediaFormat): MediaFormat {
        try {
            val m = MediaFormat::class.java.getMethod("removeKey", String::class.java)
            m.invoke(fmt, MediaFormat.KEY_ROTATION)
            return fmt
        } catch (_: Throwable) {
            // 老版本没有 removeKey：显式置 0 同样能让解码器不转
        }
        runCatching { fmt.setInteger(MediaFormat.KEY_ROTATION, 0) }
        return fmt
    }

    private fun targetSize(w: Int, h: Int, maxEdge: Int): Pair<Int, Int> {
        val longE = max(h, w)
        var tw = w
        var th = h
        if (longE > maxEdge && longE > 0) {
            val s = maxEdge.toFloat() / longE
            tw = (w * s).roundToInt()
            th = (h * s).roundToInt()
        }
        if (tw < 2) tw = 2
        if (th < 2) th = 2
        return Pair(tw - (tw % 2), th - (th % 2))
    }

    /** YUV420 要求宽高为偶数 */
    private fun evenSize(w: Int, h: Int): Pair<Int, Int> {
        val ew = if (w % 2 == 0) w else w - 1
        val eh = if (h % 2 == 0) h else h - 1
        return Pair(max(ew, 2), max(eh, 2))
    }

    private fun frameRateOf(f: MediaFormat): Int {
        return try {
            f.getInteger(MediaFormat.KEY_FRAME_RATE)
        } catch (t: Throwable) {
            try {
                f.getFloat(MediaFormat.KEY_FRAME_RATE).roundToInt()
            } catch (t2: Throwable) {
                30
            }
        }
    }
}
