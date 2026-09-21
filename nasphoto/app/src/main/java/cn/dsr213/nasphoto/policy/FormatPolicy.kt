package cn.dsr213.nasphoto.policy

import cn.dsr213.nasphoto.data.Prefs
import cn.dsr213.nasphoto.media.MediaItem
import java.io.File

/** 对每个文件采取的动作 */
enum class Action {
    /** 不处理 */
    SKIP,

    /** 上传到 NAS 备份并校验，**本地原样保留**（技术不可解码，或用户选择保效果） */
    BACKUP_ONLY,

    /** 上传 + 本地降级为预览（母本已在 NAS，可随时取回） */
    DOWNGRADE,

    /** 特殊：PNG → WebP（有损但保留透明通道） */
    DOWNGRADE_WEBP
}

data class Decision(
    val action: Action,
    /** 简短标签，用于日志/界面 */
    val tag: String,
    val reason: String,
    /** 转码时是否要尽量保留 HDR（HEVC Main10 + 色彩元数据） */
    val preserveHdr: Boolean = false,
    /**
     * 动态照片：降级时要走「保动态」路径 —— 底图缩小 + 内嵌视频转码后重新拼装，
     * 生成一个依然能"动"的小版本（已在小米相册实测可播放）。
     */
    val keepMotion: Boolean = false,
    /**
     * **为什么「不能降级、只能备份」** —— null 表示可以降级。
     *
     * 为什么要有这个独立字段（而不是继续只拼在 [reason] 里）：
     * `reason` 是一句给人读的话，`survey()` 的分组键却只看 [tag] ⇒
     * 「在保护相册」这类原因**在预检里完全不可见**。用户设了保护相册后，
     * 界面上看不出任何变化，只能自己翻逐条日志去猜 —— 那等于这个功能没做完。
     * 结构化出来之后，预检可以按它单独成组统计。
     */
    val blockedBy: String? = null
)

/** JPEG 里两个影响策略的特性 —— 一次读盘拿全，别读两遍 */
data class JpegTraits(
    /** 带增益图（Ultra HDR） */
    val ultraHdr: Boolean = false,
    /** 动态照片（内嵌一段短视频，长按照片会「动」） */
    val motionPhoto: Boolean = false
)

/**
 * 格式策略 —— 对齐 **iCloud「优化储存空间」/ 小米云「优化设备存储」** 的模型：
 *
 * > 云端（这里是 NAS）**永远保留全分辨率原始文件**；
 * > 手机本地只放**派生的省空间小版本**；需要时随时取回。
 *
 * 因为母本永远在 NAS 上、且写入前经过 SHA-256 校验，
 * "本地降级"是**可逆**操作 —— 所以只要系统解码器处理得了，就应该降级。
 *
 * 于是判定标准变成 **技术可行性**，而不是"保守/激进"：
 * - 能解码（JPEG 含 Ultra HDR、HEIC、PNG、H.264/HEVC 视频）→ 降级
 * - 解不了（RAW/DNG/TIFF）→ 只备份
 * - 完全没见过的格式 → 只备份（安全网：没见过就不敢保证能解码）
 *
 * 例外（**用户可切换**，默认保效果）：**动态照片**降级后会丢掉内嵌的动态视频、
 * 本地变成静帧。这类默认只备份，把选择权交给用户。
 *
 * 与 Google Photos 的路线差异：Google 是"上传即转码，云端只存压缩版，**不可逆**"；
 * 我们是"云端存原图，**可逆**"。这也是 Apple / 小米的做法。
 */
object FormatPolicy {

    // ⚠️ 注意：Android 的 MediaFormat / MediaStore 用的是 **MediaFormat 自己的一套取值**，
    // 与 H.273 / ISO 23091-2 的编号**并不一致**。别照 H.273 写。
    //   COLOR_TRANSFER_LINEAR    = 1
    //   COLOR_TRANSFER_SDR_VIDEO = 3
    //   COLOR_TRANSFER_ST2084    = 6   (HDR10 / PQ)
    //   COLOR_TRANSFER_HLG       = 7
    // 另外 MediaStore 里也可能出现容器里的原始值（如小米录的 129），那属于未知。
    private const val CT_LINEAR = 1
    private const val CT_SDR_VIDEO = 3
    private const val CT_ST2084 = 6      // HDR10 (PQ)
    private const val CT_HLG = 7         // HLG

    private const val CS_BT709 = 1
    private const val CS_BT601 = 2
    private const val CS_BT2020 = 6      // 广色域 → HDR 特征

    private val RAW_EXTS = setOf(
        "dng", "raw", "arw", "cr2", "cr3", "nef", "nrw", "orf", "rw2",
        "raf", "pef", "srw", "tif", "tiff"
    )
    private val JPEG_EXTS = setOf("jpg", "jpeg", "jpe")
    private val PNG_EXTS = setOf("png")
    private val HEIC_EXTS = setOf("heic", "heif", "avif")

    /** 嗅探窗口：增益图 / 动态照片的标记都在文件头部，256KB 足够 */
    private const val SNIFF_BYTES = 256L * 1024

    fun decide(item: MediaItem, prefs: Prefs): Decision =
        if (item.isVideo) decideVideo(item, prefs) else decideImage(item, prefs)

    // ---------------------------------------------------------------- 图片
    private fun decideImage(item: MediaItem, prefs: Prefs): Decision {
        val mime = item.mime.lowercase()
        val ext = item.ext

        // 1) RAW / TIFF：系统 BitmapFactory 解不了 —— 技术限制，只能备份
        if (ext in RAW_EXTS || mime.contains("raw") || mime.contains("adobe")) {
            return Decision(Action.BACKUP_ONLY, "RAW", "系统解码器不支持，只备份")
        }

        // 2) JPEG 家族（可能是 SDR / Ultra HDR / 动态照片，以及它们的组合）
        if (ext in JPEG_EXTS || mime == "image/jpeg") {
            val t = sniffJpeg(item.path)

            // 动态照片优先判定：丢掉「动」比丢掉 HDR 更容易被察觉，
            // 且小米/安卓相机常同时带增益图与动态视频。
            if (t.motionPhoto) {
                return if (prefs.motionPhotoDowngrade) {
                    Decision(
                        Action.DOWNGRADE, "动态照片",
                        "保动态降级：底图缩小 + 内嵌视频转码后重新拼装，依然可播放",
                        keepMotion = true
                    )
                } else {
                    Decision(
                        Action.BACKUP_ONLY, "动态照片",
                        "已关闭动态照片降级：本地原样不动，不省空间"
                    )
                }
            }

            if (!t.ultraHdr) {
                return Decision(Action.DOWNGRADE, "JPEG", "SDR JPEG")
            }
            return if (prefs.ultraHdrDowngrade) {
                Decision(
                    Action.DOWNGRADE, "UltraHDR",
                    "带增益图；含 HDR 的母本已在 NAS，本地存小版本（HDR 一并保留）"
                )
            } else {
                Decision(
                    Action.BACKUP_ONLY, "UltraHDR",
                    "已关闭 Ultra HDR 降级：本地保留 HDR 效果，不省空间"
                )
            }
        }

        // 3) HEIC / HEIF / AVIF：系统原生解码
        if (ext in HEIC_EXTS || mime == "image/heic" || mime == "image/heif") {
            return Decision(Action.DOWNGRADE, "HEIC", "系统原生解码")
        }

        // 4) PNG：转 WebP（有损但**保留透明通道**，截图不会丢 alpha）
        if (ext in PNG_EXTS || mime == "image/png") {
            return Decision(Action.DOWNGRADE_WEBP, "PNG→WebP", "有损压缩但保留透明通道")
        }

        // 5) 没见过的格式 → 安全网（不敢保证能解码）
        val label = if (mime.isNotEmpty()) mime else (if (ext.isNotEmpty()) ".$ext" else "未知")
        return Decision(Action.BACKUP_ONLY, "未识别:$label", "未识别格式，只备份（本地不动）")
    }

    // ---------------------------------------------------------------- 视频
    private fun decideVideo(item: MediaItem, prefs: Prefs): Decision {
        if (!prefs.videoEnabled) {
            return Decision(Action.BACKUP_ONLY, "视频", "视频处理已关闭")
        }
        val ct = item.colorTransfer
        val cs = item.colorStandard

        // HDR 判定：PQ(ST2084) / HLG / BT.2020 广色域
        // 本地转成小版本时**保留 HDR**（HEVC Main10 + 复制色彩元数据），而不是粗暴转 SDR
        if (ct == CT_ST2084 || ct == CT_HLG || cs == CS_BT2020) {
            return Decision(
                Action.DOWNGRADE, "HDR视频",
                "母本在 NAS；本地转 HEVC Main10 小版本并保留 HDR",
                preserveHdr = true
            )
        }

        // 明确的 SDR：传输特性是 linear / SDR，且色域是 BT.709 / BT.601
        val sdr = ct == CT_LINEAR || ct == CT_SDR_VIDEO || ct == 0
        val sdCs = cs == CS_BT709 || cs == CS_BT601 || cs == 0
        if (sdr && sdCs) {
            return Decision(Action.DOWNGRADE, "SDR视频", "标准动态范围")
        }

        // 其余（容器里的原始值、异常组合，如小米录的 129）→ 仍降级，
        // 因为母本在 NAS、可逆；转码时把源文件里能找到的色彩键原样带上。
        return Decision(
            Action.DOWNGRADE, "视频(色彩未知)",
            "色彩元数据非标准 (ct=$ct cs=$cs)，按原样复制可得的部分降级"
        )
    }

    /**
     * 嗅探 JPEG 的两个关键特性（Ultra HDR 增益图、动态照片内嵌视频）。
     * 只读文件头 256KB，一次读盘出两个结论。
     */
    fun sniffJpeg(path: String): JpegTraits {
        return try {
            val f = File(path)
            if (!f.exists() || f.length() <= 0) return JpegTraits()
            val n = minOf(f.length(), SNIFF_BYTES).toInt()
            val buf = ByteArray(n)
            f.inputStream().use { ins ->
                var off = 0
                while (off < n) {
                    val r = ins.read(buf, off, n - off)
                    if (r <= 0) break
                    off += r
                }
            }
            val s = String(buf, Charsets.ISO_8859_1)
            val ultra = s.contains("hdrgm:") ||
                s.contains("GainMap") ||
                s.contains("urn:iso:std:iso:ts:21496")
            // MotionPhoto 是 Google/小米的通用标记；MicroVideo 是旧版小米的写法
            val motion = s.contains("MotionPhoto") || s.contains("MicroVideo")
            JpegTraits(ultraHdr = ultra, motionPhoto = motion)
        } catch (t: Throwable) {
            // 读不了就当作两个特性都有（更保守，最终会退化成"只备份"）
            JpegTraits(ultraHdr = true, motionPhoto = true)
        }
    }
}
