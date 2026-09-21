package cn.dsr213.nasphoto.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import cn.dsr213.nasphoto.data.AppDb
import cn.dsr213.nasphoto.data.PhotoRecord
import cn.dsr213.nasphoto.data.Prefs
import cn.dsr213.nasphoto.data.STATE_BACKED_UP
import cn.dsr213.nasphoto.data.STATE_OFFLOADED
import cn.dsr213.nasphoto.data.STATE_RESTORED
import cn.dsr213.nasphoto.data.SyncLog
import cn.dsr213.nasphoto.device.DeviceState
import cn.dsr213.nasphoto.image.MotionPhoto
import cn.dsr213.nasphoto.image.Previews
import cn.dsr213.nasphoto.media.MediaItem
import cn.dsr213.nasphoto.media.MediaRepo
import cn.dsr213.nasphoto.net.Endpoint
import cn.dsr213.nasphoto.net.EndpointResolver
import cn.dsr213.nasphoto.net.NetGate
import cn.dsr213.nasphoto.net.NetPolicy
import cn.dsr213.nasphoto.net.WebDavClient
import cn.dsr213.nasphoto.policy.Action
import cn.dsr213.nasphoto.policy.Decision
import cn.dsr213.nasphoto.policy.FormatPolicy
import cn.dsr213.nasphoto.util.Hashing
import cn.dsr213.nasphoto.video.VideoProbe
import cn.dsr213.nasphoto.video.VideoTranscoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 动态照片**内嵌视频**的码率上限。
 *
 * 内嵌视频只是"长按时的 3 秒动效"，不必按正片规格给；但也**不能太小** ——
 * 因为本机缩放通路是坏的、编码尺寸 = 源尺寸（通常 1728×1296），
 * 码率太低就是每像素比特不足。
 *
 * 依据（2026-09-15 实测码率阶梯，1728×1296 / 18.7fps / 3.3s 素材）：
 * | 码率 | 体积 | SSIM | PSNR |
 * |---|---|---|---|
 * | 1.8M | 822KB | 0.975 | 42.9dB |
 * | 3.0M | 1.36MB | 0.980 | 44.4dB |  ← 取这个
 * | 4.5M | 2.02MB | 0.983 | 45.5dB |
 * | 9.0M | 4.00MB | 0.988 | 47.3dB |
 * 1.8M 其实已经视觉无损，但那是按"会降到 1280"定的老值、没有余量；3M 留安全边际，
 * 成品总大小仍是原图的 ~17%。
 */
internal const val MOTION_VIDEO_BITRATE_CAP = 3_000_000

/** logcat 标签，用于诊断（`adb logcat -s NasPhotoPrune`） */
private const val TAG = "NasPhotoPrune"

/** 内部存储的几种等价前缀，用来把绝对路径裁成"手机上的相对路径" */
private val STORAGE_PREFIXES = listOf(
    "/storage/emulated/0/",
    "/storage/self/primary/",
    "/storage/emulated/legacy/",
    "/sdcard/"
)

/**
 * 单轮回灌的文件数上限。
 *
 * 删除侧的上限是为了「删错了不可逆」；回灌侧也要有闸，但**理由不同** ——
 * 它不会丢数据，只会**把手机空间吃光**。整库实测 470 个，限额给到 2000，
 * 正常整库重建一轮就能跑完；真遇到异常庞大的库也不会一次跑到底。
 */
internal const val MAX_RESTORE_FILES = 2000

/** 回灌进度日志的间隔条数（每 N 条打一行，避免 470 行刷屏） */
private const val RESTORE_PROGRESS_EVERY = 20

/**
 * 回灌/恢复下载时用的临时文件前缀：`.nasphoto_restore_<正式名>`。
 *
 * 这个名字承担一个很关键的安全语义：**先写临时名、完整后再 rename** ⇒
 * 半截文件永远不会以正式名落盘 ⇒ **相册里永远看不到坏图**。
 *
 * 代价是进程被强杀时会留下孤儿临时文件（见 [sweepOrphanTemps]）。
 * 前缀**带 `.` 是有意的**：MediaStore 不索引点文件，所以它对相册完全隐形 ——
 * 这是好事（不污染相册），也是麻烦（用户看不见、不知道自己被占了空间）。
 */
private const val TEMP_PREFIX = ".nasphoto_restore_"

/** 从文件名里认拍摄时间：`IMG20250727224141.jpg`（与 `np_adopt.py` 的 `parse_taken` 同一口径） */
private val TAKEN_RE = Regex("""^IMG(\d{14})""")

/**
 * `dedupeRemote()` 加的后缀形状：`名字~8位hex.扩展名`。
 *
 * ⚠️ **光凭形状不足以判定** —— 必须同时满足"基名版本也存在"，
 * 理由见 [OffloadEngine.isDedupeArtifact]。
 */
private val DEDUPE_RE = Regex("""^(.+)~([0-9a-fA-F]{8})(\.[^.]*)$""")

/**
 * ⚠️ `SimpleDateFormat` **非线程安全**，而回灌在 IO 线程池上跑 ⇒ ThreadLocal 各持一份。
 *
 * 时间戳按 **+08:00** 解释：文件名里那 14 位是**相机本地时间**，不是 UTC ——
 * 按 UTC 解析会让整库照片的排序时间集体偏 8 小时。
 */
private val TAKEN_FMT = object : ThreadLocal<java.text.SimpleDateFormat>() {
    override fun initialValue(): java.text.SimpleDateFormat =
        java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.ENGLISH).apply {
            timeZone = java.util.TimeZone.getTimeZone("GMT+08:00")
        }
}

data class Candidate(
    val item: MediaItem,
    val decision: Decision,
    /** 曾经"只备份"过；本次是重新评估，NAS 上大概率已有完好副本，可跳过上传 */
    val alreadyBackedUp: Boolean = false
)

data class SurveyRow(
    val tag: String,
    val isVideo: Boolean,
    val action: Action,
    val count: Int,
    val bytes: Long,
    /**
     * 这一组为什么只能「只备份」而没降级（null = 本来就能降级）。
     * 单独成组而不是并进同一行：用户看预检，问的是「我设的保护相册挡住了什么」。
     */
    val blockedBy: String? = null
)

data class RunResult(
    val candidates: Int = 0,
    val backedUp: Int = 0,
    val downgraded: Int = 0,
    val skippedByGate: Int = 0,
    val failed: Int = 0,
    val bytesSaved: Long = 0L,
    /**
     * 本轮是否因缺少「所有文件访问」权限而**整体跳过了降级**（#103）。
     *
     * 用布尔而不是计数：缺权限时**所有**降级都会被拦（见 [downgradeBlockReason]），
     * 不存在"跳过一部分"的情况，用户需要知道的也只是"降级被权限挡住了"这一件事。
     *
     * 与 [failed] 分开：这不是失败 —— 备份照做，只是"改动用户文件"那一步主动不做了。
     * 混进 [failed] 会让日志看起来像网络问题，把真实原因（权限被撤销）藏起来。
     * 上层据此发一次**可见的**提醒，而不是让它静默。
     */
    val permissionBlocked: Boolean = false
)

/**
 * 一条「把归档里的母本从 A 挪到 B，并让 DB 跟着改」的指令（见 [OffloadEngine.relink]）。
 *
 * 纯改大小写也要 [move] = true —— `dcim/` 与 `DCIM/` 在 NAS 的 ext4 上是**两个真实目录**，
 * 不搬的话文件还在旧目录里躺着。
 */
data class Relink(
    /** DB 里现有的 path（本地绝对路径），用来定位记录；找不到记录时只搬 NAS 文件 */
    val oldPath: String,
    /** 要改写成的 path（与 [oldPath] 相同则只改远端） */
    val newPath: String,
    /** NAS 上现在的位置（相对 WebDAV 基址，**含归档根**） */
    val oldRemote: String,
    /** NAS 上的目标位置 */
    val newRemote: String,
    val move: Boolean = true
)

class OffloadEngine(private val ctx: Context) {

    private val prefs = Prefs(ctx)
    private val dao = AppDb.get(ctx).photos()
    private val repo = MediaRepo(ctx)

    // ------------------------------------------------------------ 候选筛选
    /**
     * 候选筛选。**两件事分开看**：
     *
     * - **备份（上传母本到 NAS）覆盖全部媒体** —— 对齐 iCloud / 小米云：
     *   云端永远是完整的一份，本地才是有取舍的那一份。
     *   所以"体积小 / 分辨率低 / 已收藏 / 在保护相册 / 太新"这些条件
     *   **都不再阻止上传**，只影响"要不要把本地换成小版本"。
     *
     * - **降级（本地换小版本）要过门槛** —— 见 [downgradeBlockReason]。
     *   不满足就退化成"只备份"，本地一个字节都不动。
     *
     * 真正**完全跳过**（连备份都不做）的只有几种：
     * 已经降级过的、**已恢复且在保护期内的**、不在限定相册内的、文件已不存在的、
     * 视频总开关被显式关闭的。
     *
     * 关于"已处理"的判定：
     * - 已降级（OFFLOADED）的永不再动。
     * - 仅备份（BACKED_UP）的**允许被重新评估**：如果当前策略判定它现在应该降级
     *   （典型场景：用户后来打开了 Ultra HDR / 动态照片降级开关），就把它重新纳入候选，
     *   运行时只需核对 NAS 上那份仍然完好即可跳过重复上传。
     *   没有这一步，被"只备份"标记过的文件就永远轮不到降级了。
     * - 已恢复（RESTORED）的**默认永不降级**，除非用户在设置里打开
     *   「恢复后允许再次降级」并选了 3/7/15/30 天的宽限期 —— 到期才重新纳入候选。
     *   详见 [STATE_RESTORED] 与本函数内 `restored` 处的注释。
     *
     * 返回前会按 [orderByUrgency] 做一次**稳定重排**（只调顺序、不增删）。
     * 那条排序修的是一类会静默发生的故障：见 [orderByUrgency] 的注释。
     */
    fun pickCandidates(
        now: Long = System.currentTimeMillis(),
        onlyPaths: Set<String>? = null,
        /**
         * 在 `prefs.maxBatch` 之上再压一层**硬上限**（>0 时生效，取两者较小值）。
         * 后台自动跑用它兜底 —— `maxBatch=0` 在配置里表示"不限"，
         * 前台手动点没问题，但无人值守的后台任务绝不能真的不限。
         */
        limit: Int = -1
    ): List<Candidate> = applyCap(pickFrom(repo.all(), now, onlyPaths), limit)

    /**
     * 诊断用：与 [pickCandidates] **同一套规则，但不套批次上限**。
     *
     * 为什么必须有一个不封顶的版本：`maxBatch`（现 50）会把候选列表截断，于是
     * 「排在第 51 位」会被读成「不会被处理」—— 排查"某条记录下一轮会不会被动"时，
     * 这个差别恰好把问题**藏起来**（`RESTORED` 被重新降级的 bug 就是因此一直没在日志里显形：
     * 每轮都固定报「候选 30 个」，谁也没法从日志里看出那两条恢复过的原图在里面）。
     */
    fun pickCandidatesUncapped(
        now: Long = System.currentTimeMillis(),
        onlyPaths: Set<String>? = null
    ): List<Candidate> = pickFrom(repo.all(), now, onlyPaths)

    /**
     * 把 `prefs.maxBatch`（以及调用方给的硬上限）落实成一次截断。
     *
     * ⚠️ 必须与筛选**分开**：筛选规则回答"这条该不该处理"，截断只回答"这轮先处理前几个"。
     * 混在一起时，任何"被截断"都和"不合格"长得一模一样。
     */
    private fun applyCap(out: List<Candidate>, limit: Int): List<Candidate> {
        var batch = prefs.maxBatch
        if (limit > 0 && (batch <= 0 || limit < batch)) batch = limit
        return if (batch > 0) out.take(batch) else out
    }

    /**
     * 与 [pickCandidates] 同一套筛选规则，但**数据来源由调用方给定**。
     *
     * 实时同步（`service/SyncService`）必须走这条：它只拿到「`_id` 水位线之后的新增媒体」，
     * 走 [pickCandidates] 会退化成每次全库扫描 —— 而相册的任何变动都会触发回调，
     * 那会把电耗直接打上去。
     */
    fun pickFrom(
        items: List<MediaItem>,
        now: Long = System.currentTimeMillis(),
        onlyPaths: Set<String>? = null
    ): List<Candidate> {
        val offloaded = dao.offloadedPaths().toHashSet()
        val backedUp = dao.backedUpPaths().toHashSet()
        // ⭐ 「已恢复」= 用户显式把原图要回本地了 ⇒ 默认**直接跳过**，与 `offloaded` 同一待遇。
        //
        // 为什么不能只是"让它在 backedUp 里"（2026-09-16 修的就是这个）：
        // `RESTORED` 既不在 `offloaded` 也不在 `backedUp` 里，于是每一轮它都是候选、
        // 且 `alreadyBackedUp=false` ⇒ 先**白传一份原图**，再走降级把用户刚恢复的原图压掉。
        //
        // 改成"退化为 BACKUP_ONLY"也不成立：那条分支结尾是 `upsert(state = BACKED_UP)`，
        // 会把 RESTORED 标记**洗成 BACKED_UP**，下一轮它又变回"可重新评估"、照样被压。
        //
        // ⇒ 唯一稳的做法是根本不进候选。「恢复」默认是粘性的，这是用户按下按钮就该得到的结果。
        //
        // 2026-09-17 放宽为**可配置宽限期**（用户要求）：设置里打开「恢复后允许再次降级」
        // 并选定 3/7/15/30 天后，恢复时刻起算、过期才重新放回候选。
        // 计时起点是 `restoredAt`（恢复动作那一刻），**不是** `offloadedAt`（上次降级时刻）。
        val restored = dao.restoredRows().associate { it.path to it.restoredAt }
        val graceMs = prefs.restoreGraceMs   // <0 = 永不降级；>0 = 宽限期毫秒
        val only = prefs.onlyBucket.trim()

        // 「这条以前有没有进过库」的集合 —— 只给下面的稳定排序用，见 [orderByUrgency]
        val known = HashSet<String>(offloaded.size + backedUp.size + restored.size)
        known.addAll(offloaded)
        known.addAll(backedUp)
        known.addAll(restored.keys)

        val out = ArrayList<Candidate>()
        for (it in items) {
            // ---- 完全跳过 ----
            if (it.path in offloaded) continue
            // 「已恢复」：默认终态；开了宽限期则看是否已到期
            val restoredAtMs = restored[it.path]
            if (restoredAtMs != null) {
                if (graceMs < 0L) continue
                // 时间戳缺失（只可能是迁移前的残渣）→ 保守起见当作"刚恢复"
                if (restoredAtMs <= 0L) continue
                if (now - restoredAtMs < graceMs) continue
            }
            if (onlyPaths != null && it.path !in onlyPaths) continue
            if (only.isNotEmpty() && !it.bucket.equals(only, ignoreCase = true)) continue
            if (!File(it.path).exists()) continue
            // 视频总开关：用户显式关闭则完全不碰（连备份都不做）
            if (it.isVideo && !prefs.videoEnabled) continue

            val resolved = resolveColor(it)
            var d = FormatPolicy.decide(resolved, prefs)
            if (d.action == Action.SKIP) continue

            // ---- 降级门槛：不满足 → 退化为「只备份」，但备份照做 ----
            if (d.action != Action.BACKUP_ONLY) {
                val why = downgradeBlockReason(resolved, now)
                if (why != null) {
                    // `blockedBy` 单独带出来：预检要按它分组，见 Decision.blockedBy 的注释
                    d = Decision(
                        Action.BACKUP_ONLY, d.tag, "$why → 仅备份，本地不动",
                        blockedBy = why
                    )
                }
            }

            val retry = it.path in backedUp && d.action != Action.BACKUP_ONLY
            out.add(Candidate(resolved, d, alreadyBackedUp = retry))
        }
        return orderByUrgency(out, known)
    }

    /**
     * 把候选按「该干的活有多紧急」重排，**只调整顺序，不增删任何一条**。
     *
     * ⚠️ 这不是优化，是修一个会让备份**永久不完整**的 bug（Task #89）：
     * 批次上限（后台硬上限 30）是"取前 N 个"。如果排在前面的永远是同一批老面孔，
     * 那么排在后面的新文件**永远进不了批次**。实测：媒体库 519 条 / 库内 156 条，
     * 连续四轮日志都是「候选 30 个 / 仅备份 30」，两轮之间 30 个文件名 diff 为空
     * —— 372 个文件从未被备份过一次，而它们就藏在候选列表的第 31 位之后。
     *
     * 三档的含义：
     * - `0` 从没进过库 —— 真正的待办（母本还没上 NAS，**优先级最高**）
     * - `1` 已知已备份、本轮判定要降级 —— 真活儿（把原图换成小图）
     * - `2` 其余是"每轮都要重新核对一遍"的重复候选 —— 核对后什么都不做，
     *   让它们占满槽位正是饥饿的成因，所以排最后
     *
     * ⚠️ 依赖 `sortedBy` 的**稳定性**（Kotlin 走 TimSort）：同档内保持数据源原顺序，
     * 不会因为排序本身引入抖动。别改成 `sortedByDescending` 或换成不稳定的排序。
     */
    private fun orderByUrgency(
        out: List<Candidate>,
        known: Set<String>
    ): List<Candidate> = out.sortedBy {
        when {
            it.item.path !in known -> 0
            it.alreadyBackedUp -> 1
            else -> 2
        }
    }

    /**
     * 返回"为什么不允许降级"；返回 null 表示可以降级。
     * 这些条件**只拦降级，不拦备份** —— NAS 必须有全部母本。
     */
    private fun downgradeBlockReason(item: MediaItem, now: Long): String? {
        // ⚠️ **放最前面**（#103）：没有「所有文件访问」时，降级的最后一步
        //    `src.delete()` + `tmp.renameTo(src)` 必然失败 —— 而那时原图**已经传上 NAS 了**，
        //    文件于是停在"已备份、却没降级"的中间态，日志里只留一行看不懂的失败。
        //    提前拦成「仅备份」比事后失败干净得多，也不会让本地文件卡在半吊子状态。
        if (!StoragePermission.canWritePublicStorage(ctx)) {
            return "缺少「所有文件访问」权限"
        }
        if (item.size < prefs.minSizeKb * 1024L) {
            return "小于 ${prefs.minSizeKb}KB"
        }
        if (item.isFavorite) return "已收藏"

        val protected = prefs.protectedBuckets.map { it.lowercase() }.toSet()
        if (item.bucket.lowercase() in protected) return "在保护相册「${item.bucket}」"

        // 降级时机：**照片 / 视频各自独立**，0 = 备份完立刻降级。
        // ⚠️ days=0 时要连"拍摄时间未知"也不再拦 —— 用户明确要求立刻降级，
        //    没有拍摄时间的文件（比如从电脑拷进来的）不该因此卡住。
        val days = prefs.downgradeDelayDays(item.isVideo)
        if (days > 0) {
            if (item.takenAt <= 0L) return "拍摄时间未知"
            val cutoff = now - days * 86_400_000L
            if (item.takenAt >= cutoff) return "未满 $days 天"
        }

        // 图片：长边不够大时降级只会白搭一次有损压缩；视频看时长/体积，不看长边
        if (!item.isVideo) {
            val need = (prefs.previewLongEdge * 1.1).toInt()
            if (item.longEdge in 1..need) {
                return "长边 ${item.longEdge}px 已不大于预览目标 ${prefs.previewLongEdge}px"
            }
        }
        return null
    }

    /**
     * 视频若在 MediaStore 里没有色彩元数据，回退到读文件本身补全，
     * 否则所有这类文件都会被判"未知"而走保守路径。
     */
    private fun resolveColor(item: MediaItem): MediaItem {
        if (!item.isVideo || item.colorTransfer != 0 || item.colorStandard != 0) return item
        val c = VideoProbe.colorOf(File(item.path)) ?: return item
        return item.copy(colorTransfer = c.transfer, colorStandard = c.standard)
    }

    /**
     * 按策略分类统计（只读预检，给界面用）。
     *
     * ⚠️⚠️ **必须走不封顶的 [pickFrom]，不能用 [pickCandidates]**（会犯 `applyCap` 那个坑）：
     * 带 `maxBatch`（现 50）时，预检那句「共 N 个候选」是**截断后的数字**，
     * 用户会把"排在第 51 位"读成"不会被处理"。这正是
     * [pickCandidatesUncapped] 注释里记的那个 bug —— 预检当时漏改了。
     *
     * ⚠️ 分组键含 [SurveyRow.blockedBy]：否则"在保护相册"这类原因会被并进
     * 同 tag 的行里，界面上看不出保护设置到底挡掉了多少。
     */
    suspend fun survey(now: Long = System.currentTimeMillis()): List<SurveyRow> =
        withContext(Dispatchers.IO) {
            val g = LinkedHashMap<String, SurveyRow>()
            for (c in pickFrom(repo.all(), now, null)) {
                val blocked = c.decision.blockedBy
                val key = c.decision.tag + "|" + c.item.isVideo + "|" + (blocked ?: "")
                val old = g[key]
                g[key] = SurveyRow(
                    tag = c.decision.tag,
                    isVideo = c.item.isVideo,
                    action = c.decision.action,
                    count = (old?.count ?: 0) + 1,
                    bytes = (old?.bytes ?: 0L) + c.item.size,
                    blockedBy = blocked
                )
            }
            g.values.sortedByDescending { it.bytes }
        }

    // 统计只算"本地文件仍在"的记录，否则用户删过的图会被算进已释放空间里
    fun savedBytes(): Long = liveOffloaded().sumOf { it.originalSize - it.localSize }
    fun offloadedCount(): Int = liveOffloaded().size
    fun backedUpCount(): Int = dao.backedUpPaths().count { File(it).exists() }

    /**
     * 清理"本地文件已被删除"的记录。
     *
     * 用户在系统相册里删掉照片后，这条记录已经没有意义：既不能恢复，
     * 也不该再计入统计。不清的话，随着时间推移会攒出一堆幽灵条目，
     * 让"已降级 N 个 / 仅备份 N 个"这类数字越来越虚高。
     *
     * 只在启动时跑一次，返回清理掉的条数。
     */
    /**
     * 清理**彻底失效**的记录：本地文件没了、**NAS 上的母本也没了**。
     *
     * ⚠️ 职责后来收窄过，别改回去（这是踩出来的）：
     * 早期它只判"本地文件不存在"就删记录，于是 NAS 上那份既不会被清理、App 里也再找不到
     * —— 攒出了一批没有记录的**孤儿文件**。
     *
     * ⚠️⚠️ 但**别急着把这些孤儿当成"丢失的照片/仅存副本"去回灌**（我照着这个错误前提
     * 做过一次，结果在相册里造出了重复照片）。2026-09-16 逐条查 sha256 后发现：归档根下
     * 那 9 个孤儿**全部**是历史「非 DCIM 文件压平到归档根」时期留下的**冗余副本** ——
     * 路径逻辑修好后同一个文件又正确上传了一份，旧的那份才被剩在这里。
     * 所以看到孤儿先做**内容级查重**（与全库 sha256 比），确认真的无人认领再谈恢复。
     *
     * 现在「本地没了但 NAS 还在」这一大类**归 [DeletionWatcher] 管**：
     * 由它按用户配置（询问 / 自动 / 不同步）决定要不要把母本移进回收站。
     * 这里只管最后那点收尾 —— 母本也不在了，记录留着没意义。
     *
     * 加 `remoteMissing` 判断不会变慢：只对"本地已缺失"的那几条发 HEAD。
     */
    suspend fun pruneMissing(): Int = withContext(Dispatchers.IO) {
        val all = dao.allRecords()
        val localGone = all.filter { !File(it.path).exists() }
        if (localGone.isEmpty()) {
            android.util.Log.i(TAG, "prune: total=${all.size}，无失效记录")
            return@withContext 0
        }

        // 远端也不在的，才是真正可以抹掉的
        val gone = try {
            val c = newClient().also { it.connect() }
            localGone.filter { !c.exists(it.remotePath) }
        } catch (t: Throwable) {
            // 连不上 NAS 就什么都别删 —— 无法确认母本是否还在，删了就可能变孤儿
            SyncLog.add("·清理  本地缺 ${localGone.size} 条，但连不上 NAS 无法核对 —— 本轮不动")
            android.util.Log.w(TAG, "prune 跳过：连不上 NAS（${t.message}）")
            return@withContext 0
        }
        if (gone.isEmpty()) {
            android.util.Log.i(TAG, "prune: 本地缺 ${localGone.size} 条，但 NAS 上都还在，交由删除同步处理")
            return@withContext 0
        }

        var deleted = 0
        gone.forEach { deleted += dao.deleteByPath(it.path) }

        // 删除没完全生效 —— 典型原因是**主键索引损坏**（数据库文件被外部工具覆盖过），
        // `WHERE path = ?` 走索引定位不到行，但 `SELECT *` 扫表能看到。
        // 这同时会让 byPath() 失效，进而**导致恢复原图时找不到记录**，所以必须修。
        if (deleted < gone.size) {
            android.util.Log.w(TAG, "删除仅影响 $deleted/${gone.size} 行 —— 疑似主键索引损坏")
            // Room 只认 INSERT/SELECT/UPDATE/DELETE，REINDEX/VACUUM 得走原生 SQL
            for (fix in listOf("REINDEX", "VACUUM")) {
                runCatching { rawExec(fix) }
                    .onFailure { android.util.Log.e(TAG, "$fix 执行失败", it) }
                deleted = 0
                gone.forEach { deleted += dao.deleteByPath(it.path) }
                if (deleted >= gone.size) {
                    android.util.Log.i(TAG, "$fix 修复成功")
                    break
                }
            }
        }

        // 兜底：仍删不掉就整表重建（无条件全删不走索引，再插回仍然有效的记录）
        if (dao.allRecords().any { !File(it.path).exists() && !it.remotePath.isBlank() && gone.any { g -> g.path == it.path } }) {
            val keep = dao.allRecords().filterNot { r -> gone.any { g -> g.path == r.path } }
            runCatching { rawExec("DELETE FROM photos") }
                .onFailure { android.util.Log.e(TAG, "整表清空失败", it) }
            keep.forEach { dao.upsert(it) }
            android.util.Log.w(TAG, "索引仍未修复，已整表重建，保留 ${keep.size} 条")
        }

        val after = dao.allRecords()
        android.util.Log.i(
            TAG,
            "prune: before=${all.size} localGone=${localGone.size} remoteGone=${gone.size} " +
                "deleted=$deleted after=${after.size}"
        )
        deleted
    }

    /** 执行 Room 不支持的 SQL（REINDEX / VACUUM 等），必须在非主线程调用 */
    private fun rawExec(sql: String) {
        AppDb.get(ctx).openHelper.writableDatabase.execSQL(sql)
    }

    // ------------------------------------------------------------ 主流程
    suspend fun run(
        onLog: (String) -> Unit,
        onlyPaths: Set<String>? = null,
        /** 见 [pickCandidates] 的 `limit`：后台无人值守跑时用的硬上限 */
        limit: Int = -1,
        /**
         * 非空时**直接以这批媒体为数据源**，不再全库扫描。
         * 实时同步走这里（见 `service/SyncService`）。
         */
        items: List<MediaItem>? = null
    ): RunResult = withContext(Dispatchers.IO) {
        // ---- ⓪ 路径对账：同一条照片换了路径的先让记录跟过去 ----
        //
        // 放在门控**之前**：这是纯本地操作（读媒体库 + 改库），不联网、不吃流量，
        // 而它决定了这一轮"哪些算新文件"。不先做的话，改名/大小写变过的照片
        // 会被当成从没备份过而再传一份（#86 的假母本就是这么来的）。
        // 离线时同样要做 —— 记录一致性与能不能连上 NAS 无关。
        runCatching { PathReconciler.reconcile(ctx) }
            .onFailure { android.util.Log.w(TAG, "路径对账失败：${it.message}") }

        // ---- ① 备份时机策略门控（三档）----
        // 放在最前面，连候选都不用扫。注意这只管**备份**；删除同步各有独立路径，
        // 刻意不受这里约束 —— 那是安全相关的事，不该被"省流量"策略挡住。
        val policy = NetPolicy.byKey(prefs.netPolicy)
        val blocked = NetGate.blockReason(ctx, policy)
        if (blocked != null) {
            onLog("·网络  ${blocked} —— 本轮跳过")
            return@withContext RunResult()
        }

        // ---- ①.5 写权限预检（#103）----
        // 「所有文件访问」被撤销时系统**不通知 App**，而这条链路过去一处权限检查都没有，
        // 于是降级会静默失败。这里查一次并往下传（真正拦在 [downgradeBlockReason]）。
        //
        // ⚠️ **不能 early return**：读相册靠的是 READ_MEDIA_*，与本权限无关 ——
        //    缺了它备份照样能做，只是不能改用户文件。一刀切停掉整条链路是错的。
        // ⚠️ 这条日志只在全库路径打：实时路径每几秒跑一次，会把提示刷成噪声。
        val permissionBlocked = !StoragePermission.canWritePublicStorage(ctx)
        if (permissionBlocked && items == null) {
            onLog("!权限  缺少「所有文件访问」权限：本轮只上传备份、不做降级（不改动手机上的文件）")
        }

        val all = if (items != null) {
            applyCap(pickFrom(items, onlyPaths = onlyPaths), limit)
        } else {
            pickCandidates(onlyPaths = onlyPaths, limit = limit)
        }
        // 「已恢复」的原图是被**静默跳过**的（不进候选就不会出现在任何一行日志里），
        // 用户看到"这张怎么老不处理"时需要一个答案，所以说一句。
        // ⚠️ 只在全库路径上报：实时路径每几秒跑一次，会把这条提示刷成噪声。
        if (items == null) {
            val rows = dao.restoredRows()
            if (rows.isNotEmpty()) {
                val grace = prefs.restoreGraceMs
                if (grace < 0L) {
                    onLog("·说明  ${rows.size} 张「已恢复」的原图不参与降级（它们是你明确要留在本地的）")
                } else {
                    // 开了宽限期：分别报"还在保护期内"和"已到期会重新纳入"
                    val t = System.currentTimeMillis()
                    val held = rows.count { it.restoredAt <= 0L || t - it.restoredAt < grace }
                    if (held > 0) {
                        onLog(
                            "·说明  $held 张「已恢复」的原图还在 ${prefs.restoreRedowngradeDays} 天宽限期内" +
                                "（到期后允许再次降级）"
                        )
                    }
                }
            }
        }
        if (all.isEmpty()) {
            onLog("·开始  没有可处理的候选（都处理过了，或没到门槛）")
            return@withContext RunResult()
        }

        // 「充电 + WiFi」门槛：**照片/视频分开**，各自由用户的开关决定。
        // 关掉开关 = 该类型实时同步（不等条件和网络）。
        val gateOpen = DeviceState.gateOpen(ctx)
        val todo = ArrayList<Candidate>(all.size)
        var gatedPhoto = 0
        var gatedVideo = 0
        var gatedBytes = 0L
        for (c in all) {
            if (prefs.gatedByChargingWifi(c.item.isVideo) && !gateOpen) {
                if (c.item.isVideo) gatedVideo++ else gatedPhoto++
                gatedBytes += c.item.size
                continue
            }
            todo.add(c)
        }

        onLog("·开始  候选 ${all.size} 个（待处理 ${all.sumOf { it.item.size }.let { fmt(it) }}）")
        if (gatedPhoto + gatedVideo > 0) {
            onLog(
                "跳过 ${gatedVideo} 视频 / ${gatedPhoto} 照片：开关要求「充电 + WiFi」，" +
                    "当前 ${DeviceState.describe(ctx)}（${fmt(gatedBytes)}）"
            )
        }
        if (todo.isEmpty()) {
            onLog("·开始  当前没有可执行的任务")
            return@withContext RunResult(
                candidates = all.size, skippedByGate = gatedPhoto + gatedVideo,
                permissionBlocked = permissionBlocked
            )
        }

        // ---- ② 通道解析：挑一条当前**真正能用**的路 ----
        // 优先级：局域网 > 公网 IPv6 > Tailscale > Headscale（见 EndpointResolver.orderOf）。
        //
        // ⚠️⚠️ 全部通道都不通时必须**立刻返回**，绝不能往下走。
        //    往下走会拿一个连不上的 client 去做删除判定，把「读不到」读成「NAS 上被删了」——
        //    那是把唯一母本搬进回收站的不可逆事故。所以这里返回的 null 只有一个含义：本轮什么都别做。
        val resolved = EndpointResolver.resolve(prefs, ctx)
        val ep = resolved.endpoint
        if (ep == null) {
            onLog("!失败  所有通道均不可达，本轮跳过（⚠️ 这不代表 NAS 上少了东西）")
            for (p in resolved.probes) {
                val verdict = if (p.ok) "${p.ms}ms" else "不可达"
                onLog("   ·${p.endpoint.kind.label}  ${p.endpoint.display}  → $verdict")
            }
            return@withContext RunResult(
                all.size, failed = todo.size, skippedByGate = gatedPhoto + gatedVideo,
                permissionBlocked = permissionBlocked
            )
        }
        if (resolved.prefixUpdated != null) {
            onLog("·前缀  已刷新家庭 IPv6 前缀 ${resolved.prefixUpdated}（回家自动同步，出门直接可用）")
        }

        val client = newClient(ep)
        try {
            client.connect()
            onLog("·连接  已连上 NAS（${client.tlsMode}） ${ep.kind.label} ${ep.display} · ${ep.origin}")
        } catch (t: Throwable) {
            onLog("!失败  连接 NAS：${t.message}")
            return@withContext RunResult(
                all.size, failed = todo.size, skippedByGate = gatedPhoto + gatedVideo,
                permissionBlocked = permissionBlocked
            )
        }

        var backedUp = 0
        var downgraded = 0
        var failed = 0
        var saved = 0L

        try {
            todo.forEachIndexed { idx, c ->
                val item = c.item
                val name = item.name
                try {
                    val src = File(item.path)

                    // ---- 1) 上传 + 校验（两种模式都要做）----
                    val sha = Hashing.sha256(src)
                    val remote = remotePathFor(item.path, sha)

                    // 传输耗时/速度会拼进后面的结果行 —— 一眼区分「传得慢」还是「转得慢」
                    var txNote = ""
                    // 「只备份」时的简短原因 —— 用户配了"降级时机"后，
                    // 最想知道的就是「为什么这张没降级」。带右括号，调用处不再补。
                    val whySuffix = c.decision.reason
                        .substringBefore(" → ")
                        .take(28)
                        .let { if (it.isNotBlank() && it != c.decision.tag) " · $it)" else ")" }

                    // 老记录：NAS 上大概率已有完好副本，先核对，好了就完全不碰。
                    //
                    // ⚠️ 这里的条件**不能只写 `c.alreadyBackedUp`**。`alreadyBackedUp` 在
                    // `pickFrom` 里被定义成「已备份过 **且** 当前判定为要降级」，于是
                    // **凡是判定停留在"只备份"的文件，每一轮同步都会被重传一遍** ——
                    // 实测：库里 30 个「小于 300KB」的图，每个轮次白传 14.4 MB，
                    // 而 `pickCandidates` 的注释里明明写的是"核对完好即可跳过重复上传"。
                    // 所以把 BACKUP_ONLY 也纳入核对范围。
                    var needUpload = true
                    if (c.alreadyBackedUp || c.decision.action == Action.BACKUP_ONLY) {
                        val prior = dao.byPath(src.absolutePath)
                        var ok = prior != null &&
                            prior.remotePath == remote &&
                            prior.sha256.equals(sha, ignoreCase = true) &&
                            // 先比长度（一次 HEAD，几乎不要钱），长度不对就别去下载了
                            client.size(remote) == src.length()
                        // 开了校验才做逐字节核对（要整份下载，但这是 LAN，90 MB/s）
                        if (ok && prefs.verifyReadBack) {
                            ok = client.remoteSha256(remote)?.equals(sha, ignoreCase = true) == true
                        }
                        if (ok) needUpload = false
                    }

                    if (needUpload) {
                        val upT0 = System.currentTimeMillis()
                        val put = client.upload(src, remote)
                        val upMs = System.currentTimeMillis() - upT0
                        if (put !in 200..299) {
                            failed++
                            onLog("!失败  上传 HTTP $put  ${relRemote(remote)}")
                            return@forEachIndexed
                        }
                        val verified = if (prefs.verifyReadBack) {
                            client.remoteSha256(remote)?.equals(sha, ignoreCase = true) == true
                        } else {
                            client.size(remote) == src.length()
                        }
                        if (!verified) {
                            failed++
                            onLog("!失败  上传后校验不通过  ${relRemote(remote)}")
                            return@forEachIndexed
                        }
                        txNote = "  ${fmt(src.length())}  ${ms2s(upMs)}  ${speed(src.length(), upMs)}"
                    }

                    val origLen = src.length()
                    val origMtime = src.lastModified()

                    // ---- 2) 只备份：本地原样保留 ----
                    if (c.decision.action == Action.BACKUP_ONLY) {
                        dao.upsert(
                            PhotoRecord(
                                path = src.absolutePath,
                                remotePath = remote,
                                sha256 = sha,
                                originalSize = origLen,
                                localSize = origLen,
                                takenAt = item.takenAt,
                                mediaId = item.id,
                                origLastModified = origMtime,
                                state = STATE_BACKED_UP,
                                offloadedAt = System.currentTimeMillis()
                            )
                        )
                        backedUp++
                        // needUpload=false 表示 NAS 上本来就是同一份，别谎报"已上传"
                        val mark = if (needUpload) "↑备份" else "=已存档"
                        onLog("$mark  ${relRemote(remote)}$txNote  (${c.decision.tag}$whySuffix")
                        return@forEachIndexed
                    }

                    // ---- 2.5) 视频先做个便宜预估：转完能不能明显变小？
                    // 720p 之类本来就小的片子，转成 1080p HEVC 反而会变大，
                    // 与其白转一趟（几秒到几分钟），不如直接只备份。
                    if (item.isVideo && !videoCanShrink(item)) {
                        dao.upsert(
                            PhotoRecord(
                                path = src.absolutePath,
                                remotePath = remote,
                                sha256 = sha,
                                originalSize = origLen,
                                localSize = origLen,
                                takenAt = item.takenAt,
                                mediaId = item.id,
                                origLastModified = origMtime,
                                state = STATE_BACKED_UP,
                                offloadedAt = System.currentTimeMillis()
                            )
                        )
                        backedUp++
                        val mark = if (needUpload) "↑备份" else "=已存档"
                        onLog(
                            "$mark  ${relRemote(remote)}$txNote" +
                                "  （${item.longEdge}px 已足够小，转码省不出空间）$whySuffix"
                        )
                        return@forEachIndexed
                    }

                    // ---- 3) 降级：本地换成小版本 ----
                    val tmp = File(src.parentFile, ".nasphoto_tmp_$name")
                    var failWhy: String? = null
                    var transcodeNote = ""
                    if (item.isVideo) {
                        val r = VideoTranscoder.transcode(
                            src = src,
                            dst = tmp,
                            maxEdge = prefs.videoMaxEdge,
                            bitrateBps = prefs.videoBitrateKbps * 1000,
                            maxBytes = prefs.videoMaxMb.toLong() * 1024 * 1024,
                            preserveHdr = c.decision.preserveHdr,
                            fallbackDurationMs = item.durationMs
                        )
                        if (r.ok) {
                            transcodeNote = " · ${codecLabel(r.codec)}" +
                                (if (r.hdrPreserved) " 保留HDR" else "") +
                                " · ${r.bitrateBps / 1000}kbps"
                        } else {
                            failWhy = r.reason ?: "转码失败"
                        }
                    } else if (c.decision.keepMotion) {
                        // 保动态降级：底图缩小 + 内嵌视频转码，重新拼装成仍可播放的动态照片。
                        //
                        // 内嵌视频只是"长按时的 3 秒动效"，但**分辨率不再降**（本机缩放通路是坏的，
                        // 见 VideoTranscoder 顶部说明），所以码率必须按"原分辨率"给足。
                        //
                        // 3 Mbps 的依据（2026-09-15 在 1728×1296 / 18.7fps 素材上实测的码率阶梯）：
                        //   1.8M → SSIM 0.975 / PSNR 42.9dB / 822KB
                        //   3.0M → SSIM 0.980 / PSNR 44.4dB / 1.36MB   ← 选它
                        //   4.5M → SSIM 0.983 / PSNR 45.5dB / 2.02MB
                        //   9.0M → SSIM 0.988 / PSNR 47.3dB / 4.00MB
                        // 1.8M 已经视觉无损，但那是按"会降到 1280"定的老值、没有余量；
                        // 3M 给运动更剧烈的片段留了安全边际，而成品仍是原图的 ~17%。
                        val err = MotionPhoto.rebuild(
                            src = src,
                            dst = tmp,
                            previewLongEdge = prefs.previewLongEdge,
                            previewQuality = prefs.previewQuality,
                            videoMaxEdge = minOf(prefs.videoMaxEdge, 1280),
                            videoBitrateBps = minOf(
                                prefs.videoBitrateKbps * 1000,
                                MOTION_VIDEO_BITRATE_CAP
                            ),
                            videoMaxBytes = minOf(
                                prefs.videoMaxMb.toLong() * 1024 * 1024,
                                8L * 1024 * 1024
                            )
                        )
                        if (err == null) transcodeNote = " · 保动态" else failWhy = err
                    } else {
                        val fmt = if (c.decision.action == Action.DOWNGRADE_WEBP)
                            Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.JPEG
                        val ok = Previews.makePreview(
                            src, tmp, prefs.previewLongEdge, prefs.previewQuality, fmt
                        )
                        if (!ok) failWhy = "缩图失败"
                    }

                    if (failWhy != null || !tmp.exists() || tmp.length() <= 0L) {
                        tmp.delete()
                        // 转码失败**不回退数据**：原图已经安全传到 NAS，本次跳过降级
                        dao.upsert(
                            PhotoRecord(
                                path = src.absolutePath,
                                remotePath = remote,
                                sha256 = sha,
                                originalSize = origLen,
                                localSize = origLen,
                                takenAt = item.takenAt,
                                mediaId = item.id,
                                origLastModified = origMtime,
                                state = STATE_BACKED_UP,
                                offloadedAt = System.currentTimeMillis()
                            )
                        )
                        backedUp++
                        onLog("!失败  降级 $name：${failWhy ?: "输出为空"}（原图已在 NAS，本地未动）")
                        return@forEachIndexed
                    }

                    // 兜底：预览没明显变小就放弃降级
                    if (tmp.length() * 10 >= origLen * 9) {
                        tmp.delete()
                        dao.upsert(
                            PhotoRecord(
                                path = src.absolutePath,
                                remotePath = remote,
                                sha256 = sha,
                                originalSize = origLen,
                                localSize = origLen,
                                takenAt = item.takenAt,
                                mediaId = item.id,
                                origLastModified = origMtime,
                                state = STATE_BACKED_UP,
                                offloadedAt = System.currentTimeMillis()
                            )
                        )
                        backedUp++
                        onLog("·跳过  $name  ${fmt(origLen)} → ${fmt(tmp.length())}（省不出空间，原图已在 NAS）")
                        return@forEachIndexed
                    }

                    if (!src.delete() || !tmp.renameTo(src)) {
                        tmp.delete()
                        failed++
                        onLog("!失败  替换本地文件 $name（原图已在 NAS，可随时恢复）")
                        return@forEachIndexed
                    }
                    src.setLastModified(origMtime)
                    scan(src)

                    dao.upsert(
                        PhotoRecord(
                            path = src.absolutePath,
                            remotePath = remote,
                            sha256 = sha,
                            originalSize = origLen,
                            localSize = src.length(),
                            takenAt = item.takenAt,
                            origLastModified = origMtime,
                            state = STATE_OFFLOADED,
                            offloadedAt = System.currentTimeMillis()
                        )
                    )
                    downgraded++
                    saved += (origLen - src.length())
                    if (needUpload) onLog("↑上传  ${relRemote(remote)}$txNote")
                    onLog(
                        "·降级  ${relRemote(remote)}  ${fmt(origLen)} → ${fmt(src.length())}" +
                            "  省 ${fmt(origLen - src.length())}  (${c.decision.tag})$transcodeNote"
                    )
                    if (idx % 10 == 0 || idx == todo.lastIndex) {
                        onLog("·进度  ${idx + 1}/${todo.size} · 累计已省 ${fmt(saved)}")
                    }
                } catch (t: Throwable) {
                    failed++
                    onLog("!失败  $name: ${t.message}")
                }
            }
        } finally {
            client.close()
        }

        onLog("·完成  降级 $downgraded · 仅备份 $backedUp · 失败 $failed · 本次释放 ${fmt(saved)}")
        RunResult(
            candidates = all.size, backedUp = backedUp, downgraded = downgraded,
            skippedByGate = gatedPhoto + gatedVideo, failed = failed, bytesSaved = saved,
            permissionBlocked = permissionBlocked
        )
    }

    // ------------------------------------------------------------ 归档根迁移

    /**
     * 把归档根从 [oldPrefix] 迁到 [newPrefix]（均为**相对 WebDAV 基址**的路径前缀，
     * 形如 `我的文档/NasPhoto归档` → `OtherSpace/NasPhoto归档`）。
     *
     * 为什么必须有这一步：`PhotoRecord.remotePath` 存的是**相对全量路径**
     * （`我的文档/NasPhoto归档/DCIM/Camera/a.jpg`），只在设置里改 `remoteRoot`
     * 并不会动历史记录 —— 结果是「恢复」按旧路径去 NAS 上找 → 全部 404。
     *
     * ⚠️ 必须走 Room 执行。**绝不要用 root 去替换 SQLite 文件**：
     * 上次那样干把主键索引搞坏了（`COUNT(*)` 与全表扫描结果对不上、
     * `DELETE WHERE path=?` 影响 0 行、`byPath()` 静默返回 null → 恢复功能假死），
     * 还丢了 9 条记录（照片本体安全，但元数据没了）。
     *
     * 幂等：旧前缀命中 0 条时直接返回，重复执行无副作用。
     */
    suspend fun migrateRemoteRoot(oldPrefix: String, newPrefix: String): String =
        withContext(Dispatchers.IO) {
            val o = oldPrefix.trim().trim('/')
            val n = newPrefix.trim().trim('/')
            val sb = StringBuilder("归档根迁移：$o  →  $n\n")
            if (o.isEmpty() || n.isEmpty() || o == n) {
                return@withContext sb.append("   参数无效或新旧相同，未做任何改动").toString()
            }
            val totalBefore = dao.allRecords().size
            val hit = dao.countByRemotePrefix("$o/")
            sb.append("   迁移前：共 $totalBefore 条，命中旧前缀 $hit 条\n")
            if (hit == 0) {
                return@withContext sb.append("   无需迁移（已是新前缀或表为空）").toString()
            }
            val changed = dao.rewriteRemotePrefix("$o/", "$n/")
            val totalAfter = dao.allRecords().size
            val remain = dao.countByRemotePrefix("$o/")
            sb.append("   已改写 $changed 行；总数 $totalBefore → $totalAfter；残留旧前缀 $remain 条\n")
            sb.append(
                if (changed == hit && remain == 0 && totalAfter == totalBefore) {
                    "   ✅ 迁移成功（行数与总数都对得上）"
                } else {
                    "   ⚠️ 数量对不上，请人工检查"
                }
            )
            sb.toString()
        }

    // ------------------------------------------------------------ 重新定位（relink）

    /**
     * 批量"重新定位"：**NAS 上挪文件 + DB 里改 `path`/`remotePath`**，逐条原子。
     *
     * 两个场景共用（本质都是"记录指向的位置 ≠ 母本实际所在的位置"）：
     * 1. **归位**：早期版本把非 `DCIM/` 的文件压平到归档根，`remotePath` 丢了目录前缀。
     *    后果不只是难看 —— 策略日后判定该降级时，会按这个残缺路径在 NAS 上**再存一份**。
     * 2. **大小写归一**：MediaStore 的 `_data` 曾返回小写 `dcim/`（见 `MediaRepo.canonical`），
     *    于是 NAS 上真的分裂出 `dcim/` 与 `DCIM/` 两套目录。
     *
     * 顺序是**先 MOVE 成功、再写 DB**：反过来的话 DB 已指向新位置而母本还在原地，
     * 「恢复」会 404，删除同步还会判定"母本没了"—— 那是最坏的结果（唯一一份被挪走）。
     *
     * ⚠️ MOVE 前必须先把目标父目录建出来：WebDAV 的 MOVE **不会**自动创建 `Destination`
     *    的父目录，缺了直接 500 `Permission denied` —— 看着像权限问题，其实是目录不存在。
     *
     * 幂等：`oldRemote` 已不在、`newRemote` 已在的条目直接跳过（视为已完成）。
     */
    suspend fun relink(items: List<Relink>, onLog: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val client = newClientForDeletion()
            // ⚠️ 必须显式 connect()：宽松 TLS 只在对它调过 connect() 之后才装上
            //    （permissiveFactory 要靠 connect() 里那次试探性握手失败才会被建出来）。
            //    漏掉的话**每一步都失败且看不出原因** —— 自签证书在严格模式下握手就挂了，
            //    existsPath() 一律返回 false，日志读起来像"文件全都不在 NAS 上"，极易误判。
            client.connect()
            var moved = 0
            var already = 0
            var dbDone = 0
            val problems = ArrayList<String>()

            // 先把所有目标父目录**去重后一次建好**。逐条边搬边建会慢很多（每条都要
            // 从根往下 MKCOL 一趟），而且这一步不能省：WebDAV 的 MOVE 不会自动创建
            // Destination 的父目录，缺了直接返回 500 Permission denied。
            val parents = items.asSequence()
                .filter { it.move && it.oldRemote != it.newRemote }
                .map { it.newRemote.substringBeforeLast('/', "") }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()
            for (p in parents) client.ensureDirs(p)

            for (it in items) {
                val name = it.oldPath.substringAfterLast('/')

                // ① NAS 侧：把母本挪到与记录一致的位置
                if (it.move && it.oldRemote != it.newRemote) {
                    if (client.existsPath(it.newRemote) && !client.existsPath(it.oldRemote)) {
                        already++
                        onLog("=已就位  $name")
                    } else if (!client.existsPath(it.oldRemote)) {
                        problems += "$name：NAS 上找不到源文件"
                        onLog("!失败  $name  NAS 上没有 ${it.oldRemote.substringAfterLast('/')}")
                        continue
                    } else {
                        val code = client.move(it.oldRemote, it.newRemote)
                        if (code !in 200..299) {
                            problems += "$name：MOVE 返回 HTTP $code"
                            onLog("!失败  $name  移动失败（HTTP $code）")
                            continue
                        }
                        moved++
                    }
                }

                // ② DB 侧：跟着改，保持 path ↔ remotePath 自洽
                val rec = dao.byPath(it.oldPath) ?: continue   // 无记录 = 纯 NAS 文件，不是错
                if (it.newPath == rec.path && it.newRemote == rec.remotePath) continue
                if (it.newPath != rec.path && dao.byPath(it.newPath) != null) {
                    problems += "$name：目标 path 已被另一条记录占用"
                    onLog("!失败  $name  目标记录已存在，未改写")
                    continue
                }
                // 主键就是 path，改它等价于"删旧+插新"，走 Room 两步做
                dao.deleteByPath(rec.path)
                dao.upsert(rec.copy(path = it.newPath, remotePath = it.newRemote))
                dbDone++
            }

            val sb = StringBuilder("重新定位：共 ${items.size} 条\n")
            sb.append("   NAS 移动 $moved；已就位跳过 $already；DB 改写 $dbDone 条\n")
            sb.append(
                if (problems.isEmpty()) "   ✅ 无异常"
                else "   ⚠️ ${problems.size} 条异常：\n" + problems.joinToString("\n") { "      $it" }
            )
            sb.toString()
        }

    // ------------------------------------------------------------ 恢复
    suspend fun offloadedList(): List<PhotoRecord> =
        withContext(Dispatchers.IO) { liveOffloaded() }

    /** 只列出本地文件仍在的记录 —— 用户若在系统相册里删过图，不该出现幽灵条目 */
    private fun liveOffloaded(): List<PhotoRecord> =
        dao.offloaded().filter { File(it.path).exists() }

    suspend fun restore(path: String, onLog: (String) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val rec = dao.byPath(path)
            if (rec == null) {
                onLog("没有这条记录：${File(path).name}")
                return@withContext false
            }
            val client = newClient()
            try {
                client.connect()
                restoreWith(client, rec, onLog)
            } catch (t: Throwable) {
                onLog("!失败  连接 NAS：${t.message}")
                false
            } finally {
                client.close()
            }
        }

    suspend fun restoreAll(onLog: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        val list = liveOffloaded()
        if (list.isEmpty()) {
            onLog("·恢复  没有已降级的照片")
            return@withContext 0
        }
        onLog("·恢复  待恢复 ${list.size} 个（合计 ${fmt(list.sumOf { it.originalSize })}）")
        val client = newClient()
        try {
            client.connect()
        } catch (t: Throwable) {
            onLog("连接 NAS 失败：${t.message}")
            return@withContext 0
        }
        var ok = 0
        try {
            list.forEachIndexed { i, rec ->
                if (restoreWith(client, rec, onLog)) ok++
                if ((i + 1) % 10 == 0) onLog("·恢复  进度 ${i + 1}/${list.size}")
            }
        } finally {
            client.close()
        }
        onLog("·恢复  完成：成功 $ok / 共 ${list.size}")
        return@withContext ok
    }

    private fun restoreWith(
        client: WebDavClient,
        rec: PhotoRecord,
        onLog: (String) -> Unit
    ): Boolean {
        val target = File(rec.path)
        val tmp = File(target.parentFile, "$TEMP_PREFIX${target.name}")
        val name = target.name
        val rel = relRemote(rec.remotePath)
        // 同 [adoptOne]：下载前先清同名残留，避免往半截旧文件上续写
        if (tmp.exists() && !tmp.delete()) {
            onLog("无法清除同名临时文件，跳过 $name")
            return false
        }
        val downT0 = System.currentTimeMillis()
        try {
            client.download(rec.remotePath, tmp)
        } catch (t: Throwable) {
            tmp.delete()
            onLog("!失败  下载 $rel：${t.message}")
            return false
        }
        val downMs = System.currentTimeMillis() - downT0
        val sha = try {
            Hashing.sha256(tmp)
        } catch (t: Throwable) {
            tmp.delete()
            onLog("!失败  读取下载结果 $name")
            return false
        }
        if (!sha.equals(rec.sha256, ignoreCase = true)) {
            tmp.delete()
            onLog("!失败  sha256 校验不通过 · 已放弃覆盖 $name（本地保持原样）")
            return false
        }
        if (target.exists() && !target.delete()) {
            tmp.delete()
            onLog("!失败  无法覆盖本地文件 $name")
            return false
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            onLog("!失败  重命名失败 $name")
            return false
        }
        target.setLastModified(rec.origLastModified)
        scan(target)
        // `restoredAt` 必须在这里落 —— 它是「恢复后宽限期」的唯一计时起点。
        // 用户若在设置里开了「恢复后 N 天可再降级」，就从这一刻开始算。
        dao.upsert(
            rec.copy(
                state = STATE_RESTORED,
                localSize = target.length(),
                restoredAt = System.currentTimeMillis()
            )
        )
        onLog("↓恢复  $rel  ${fmt(rec.originalSize)}  ${ms2s(downMs)}  ${speed(rec.originalSize, downMs)}")
        return true
    }

    // ------------------------------------------------------------ 孤儿回灌

    /**
     * 一条「孤儿文件」的元信息 —— NAS 上有、DB 里没有记录。
     *
     * 由工作区脚本 `np_adopt.py` 在 **NAS 侧**算好（sha256 在 NAS 上算，
     * 不必为了拿校验值把上百 MB 先下载一遍），App 只负责恢复 + 落库。
     */
    data class OrphanItem(
        val localPath: String,
        val remotePath: String,
        val sha256: String,
        val size: Long,
        val takenAt: Long,
        val lastModified: Long
    )

    /**
     * 回灌孤儿文件：**下载母本 → 校验 sha256 → 落库为 RESTORED**。
     *
     * ⚠️ 有个必须守住的顺序：**DB 里绝不出现"本地缺文件"的中间态**。
     * 若先把记录写进去、再慢慢下载，这一小段时间里删除同步会看到
     * 「库里有、手机相册里没有」—— 那正是它判定的"用户删了照片"，
     * 会把这批**仅存副本**的母本搬进回收站。所以记录只在下载且校验通过后，
     * 由 [restoreWith] 一次性落库。
     *
     * 同理，本地已有同名文件时**直接跳过**，绝不覆盖。
     *
     * @return 真正恢复的条数
     */
    suspend fun adoptOrphans(
        items: List<OrphanItem>,
        onLog: (String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        if (items.isEmpty()) {
            onLog("·回灌  清单为空，没有要做的事")
            return@withContext 0
        }
        onLog("·回灌  待恢复 ${items.size} 个（合计 ${fmt(items.sumOf { it.size })}）")
        val client = newClient()
        try {
            client.connect()
        } catch (t: Throwable) {
            onLog("!失败  连接 NAS：${t.message}")
            return@withContext 0
        }
        var ok = 0
        var skip = 0
        var fail = 0
        try {
            items.forEach { o ->
                val target = File(o.localPath)
                val name = target.name
                // 幂等：同 path 或同远端路径只要已有记录就跳过 —— 重复跑不会出乱子
                if (dao.byPath(o.localPath) != null || dao.byRemotePath(o.remotePath) != null) {
                    skip++
                    onLog("·回灌  库里已有记录，跳过 $name")
                    return@forEach
                }
                // 本地已有同名文件 → 绝不覆盖（可能是用户自己放在那儿的一张同名照片）
                if (target.exists()) {
                    skip++
                    onLog("·回灌  本地已存在同名文件，跳过 $name")
                    return@forEach
                }
                target.parentFile?.mkdirs()
                val rec = PhotoRecord(
                    path = o.localPath,
                    remotePath = o.remotePath,
                    sha256 = o.sha256,
                    originalSize = o.size,
                    localSize = 0,
                    takenAt = o.takenAt,
                    origLastModified = o.lastModified,
                    state = STATE_BACKED_UP,
                    offloadedAt = 0
                )
                if (restoreWith(client, rec, onLog)) ok++ else fail++
            }
        } finally {
            client.close()
        }
        onLog("·回灌  完成：恢复 $ok / 跳过 $skip / 失败 $fail")
        ok
    }

    // ------------------------------------------------------------ 整库回灌（NAS → 手机）

    /** 规划阶段的一条候选（**只读快照**，不代表一定会灌） */
    data class RestoreItem(
        /** 相对归档根，如 `DCIM/Camera/a.jpg` */
        val rel: String,
        /** 含归档根的远端路径，即 DB 里的 `remotePath` */
        val remotePath: String,
        /** 反推出的本地绝对路径 */
        val localPath: String,
        /** `-1` = 没解析出来（**不是 0 字节**，见 [WebDavClient.RemoteEntry]） */
        val size: Long,
        val mtime: Long,
    )

    /**
     * 回灌规划 —— [planRestore] 的产物，**纯只读**：跑完不动任何数据，也不写库。
     *
     * 分开「规划」与「执行」是为了**先把账算清再动手**：
     * 用户能先看到"要灌多少个、多少 GB、装不装得下"，而不是点了按钮才发现塞满手机。
     */
    data class RestorePlan(
        val candidates: List<RestoreItem> = emptyList(),
        /** 已知大小之和 */
        val knownBytes: Long = 0,
        /** 大小未知的条数 —— 只要不是 0 就不自动放行（按 0 算会**低估**空间需求） */
        val unknownSize: Int = 0,
        /** 手机里已经有同名文件 —— **绝不覆盖** */
        val alreadyLocal: Int = 0,
        /** 库里已有记录（按 `remotePath` 命中）—— 幂等跳过；**这正是"续跑"的实现方式** */
        val alreadyInDb: Int = 0,
        /**
         * 「残留副本」：NAS 上有、DB 的 `remotePath` 里没有，**但本地路径已被另一条记录占用**。
         *
         * 典型来路是 [dedupeRemote]：同一路径先后存过两份不同内容 ⇒ 后一份被改名成
         * `X~<sha8>.jpg` 并成为正式记录，**先前那一份 `X.jpg` 就留在 NAS 上没人管了**。
         *
         * ⚠️ 它们**不该回灌** —— 手机的 `X.jpg` 已经是新内容，灌回来只会把它覆盖掉。
         * 但也不能并进 [alreadyInDb] 里**静默吞掉**：那是 NAS 上真实占着空间的无主文件，
         * 属于"该被看见、该被清理"的一类。实测 2026-09-17 真实归档里有 **11 个**。
         */
        val residue: List<String> = emptyList(),
        /**
         * 「去重产物」：文件名形如 `X~<8位hex>.jpg`，**且去掉该后缀的 `X.jpg` 也在**（NAS 或库里）。
         *
         * 这个后缀是 [dedupeRemote] 自己加的，**不是用户的原始文件名** ——
         * 灌回相册等于凭空造一张怪名字的照片，而用户要的那份内容已经在了。
         * ⇒ 同样不回灌，只报告。
         *
         * ⚠️ 判定必须**"基名版本也存在"才算**，否则会误伤用户自己起的、
         * 名字里恰好带 `~xxxxxxxx` 的文件 —— 误判的代价是**该灌的不灌**，即丢数据。
         */
        val dedupeArtifacts: List<String> = emptyList(),
        /**
         * 归档树里目录名不合规的条目（如 `pictures/` 而非 `Pictures/`），**不自动回灌**。
         *
         * 原因：反推出的本地路径会与 NAS 上的位置**对不上** ——
         * 灌进 `Pictures/` 之后，将来重新降级算回的远端是 `…/Pictures/x.jpg`，
         * 而 NAS 上那份在 `…/pictures/x.jpg` ⇒ **多出一份同内容母本**。
         * 这正是 `np_adopt.py` 里"先归位、再回灌"要解决的问题，只是这里改成**先报告**。
         */
        val inconsistent: List<String> = emptyList(),
        /** ⚠️ 扫描失败 —— **绝不是「NAS 上没有文件」** */
        val unreadable: Boolean = false,
        /** 归档根在 NAS 上不存在 */
        val rootMissing: Boolean = false,
    ) {
        val ok: Boolean get() = !unreadable && !rootMissing
        val total: Int get() = candidates.size

        /**
         * 能不能放心开跑：扫描成功 · **没有大小未知的条目** · 且总量装得下。
         *
         * ⚠️ 有一条未知就返回 false：未知按 0 累加会算出一个**虚低**的总量，
         * 而"以为装得下"的后果是把手机塞满。
         */
        fun fitsIn(freeBytes: Long, ratio: Double = 0.9): Boolean =
            ok && unknownSize == 0 && freeBytes > 0 && knownBytes <= (freeBytes * ratio).toLong()
    }

    /** 回灌执行结果 */
    data class RestoreResult(
        val done: Int = 0,
        /** 内容在别处已有记录 ⇒ 撤回，**没落库**（防相册多出重复照片） */
        val duplicate: Int = 0,
        val failed: Int = 0,
        /** 触到单次上限，还有没处理完的 */
        val truncated: Boolean = false,
        val bytes: Long = 0,
    ) {
        val ok: Boolean get() = failed == 0 && !truncated
    }

    /**
     * 「挑选回灌」的筛选条件。**全部留空 = 不筛**（等价于整库重建）。
     *
     * 为什么要有它：整库重建解决的是"换手机"；但更常见的是只想捞回**某一段时间**
     * 或**某一批目录**的照片。在一个几百 GB 的归档里全灌一遍既没必要也慢。
     */
    data class RestoreFilter(
        /** 拍摄时间下界（含）。null = 不限 */
        val fromMs: Long? = null,
        /** 拍摄时间上界（含）。null = 不限 */
        val toMs: Long? = null,
        /** 远端目录前缀，相对归档根，如 `DCIM/Camera`。null/空 = 不限 */
        val dirPrefix: String? = null,
        /** 文件名关键字（忽略大小写）。null/空 = 不限 */
        val keyword: String? = null,
    ) {
        val isEmpty: Boolean
            get() = fromMs == null && toMs == null &&
                dirPrefix.isNullOrBlank() && keyword.isNullOrBlank()
    }

    /**
     * 从整库规划里筛出一个子集。
     *
     * ⭐ **纯内存操作，不重扫 NAS** —— 筛选要用的 `size`/`mtime` 在 [planRestore]
     * 阶段就"白拿到"了（nginx autoindex 的 HTML 自带这两个字段），
     * 所以用户每调一次筛选条件都是零网络开销的即时响应。
     *
     * ⚠️ 筛完**必须重算** [RestorePlan.knownBytes] / [RestorePlan.unknownSize]：
     * 否则熔断会拿整库的账去比，明明只挑了 3 张却报"装不下"。
     *
     * 其余诊断计数（`alreadyLocal` / `residue` / `dedupeArtifacts` …）**保持原样** ——
     * 它们是"整库体检结果"，不随筛选变化；篡改它们反而会让用户看不懂。
     */
    fun pickRestore(plan: RestorePlan, f: RestoreFilter): RestorePlan {
        if (!plan.ok || f.isEmpty) return plan
        val dir = f.dirPrefix?.trim('/')?.takeIf { it.isNotEmpty() }
        val kw = f.keyword?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val sel = plan.candidates.filter { c ->
            if (dir != null && !c.rel.startsWith("$dir/")) return@filter false
            // ⚠️ 关键字只匹配**文件名**，不匹配整条相对路径。
            //    拿整路径匹配会让"搜 a"把 `…/_np_r87a/` 下的文件全捞出来 ——
            //    目录名本身就常常含有用户要搜的词，实测踩过这个坑。
            //    （要按目录圈定范围请用 [dirPrefix]，那是另一个维度。）
            if (kw != null && !c.rel.substringAfterLast('/').lowercase().contains(kw)) {
                return@filter false
            }
            if (f.fromMs != null || f.toMs != null) {
                // 按**拍摄时间**筛，不是按 NAS 上的 mtime：mtime 是"上传/归档时刻"，
                // 与用户在相册里看到的时间可能差很远（实测能差一天以上）。
                val t = takenAtOf(c.rel.substringAfterLast('/'), c.mtime)
                if (f.fromMs != null && t < f.fromMs) return@filter false
                if (f.toMs != null && t > f.toMs) return@filter false
            }
            true
        }
        var known = 0L
        var unknown = 0
        for (c in sel) if (c.size < 0) unknown++ else known += c.size
        return plan.copy(candidates = sel, knownBytes = known, unknownSize = unknown)
    }

    /**
     * 规划里出现过的目录（相对归档根）+ 各自条数，供「挑选回灌」的目录下拉用。
     *
     * [maxDepth] 默认 2 是刻意的：归档树可能很深（`DCIM/Camera/Sub/…`），
     * 全列出来用户反而选不过来；截到两级既能区分主要相册，条目数也可控。
     */
    fun restoreDirs(plan: RestorePlan, maxDepth: Int = 2): List<Pair<String, Int>> {
        val cnt = HashMap<String, Int>()
        for (c in plan.candidates) {
            val dirParts = c.rel.split('/').dropLast(1).take(maxDepth)
            if (dirParts.isEmpty()) continue
            val key = dirParts.joinToString("/")
            cnt[key] = (cnt[key] ?: 0) + 1
        }
        return cnt.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /**
     * 扫 NAS 归档树 → 生成回灌规划。**只读，不写库、不下载、不删除。**
     *
     * 与 `np_adopt.py`（电脑端脚本）的分工差别：那份脚本在 NAS 侧算 sha256，
     * 因而能在**下载前**分出"冗余副本"；这里拿不到 sha256（见下），
     * 于是改成**规划阶段粗筛 + 下载后精筛**两道：
     * 规划阶段只按「库里有 path / remotePath」「本地已有同名文件」过滤，
     * 真正的内容级去重放在 [adoptOne] 里，代价是重复内容要**先下载才发现**。
     * 整库 470 文件 / 2.5 GB 的量级下这个代价可接受。
     */
    suspend fun planRestore(onLog: (String) -> Unit): RestorePlan = withContext(Dispatchers.IO) {
        val root = prefs.remoteRoot.trim().trim('/')
        if (root.isEmpty()) {
            onLog("!回灌  归档根未配置")
            return@withContext RestorePlan(rootMissing = true)
        }
        val client = newClient()
        try {
            try {
                client.connect()
            } catch (t: Throwable) {
                onLog("!回灌  连接 NAS 失败：${t.message}")
                return@withContext RestorePlan(unreadable = true)
            }
            val t0 = System.currentTimeMillis()
            // ⚠️ 必须用 strict 版：读不到会返回 null。把它当成"空"就等于"NAS 上没有文件要灌"，
            //    而真实原因可能只是网络抖了一下 —— 用户会以为库是空的。
            val files = client.walkFilesRecursive(root, setOf(TrashStore.TRASH_NAME))
                ?: run {
                    onLog("!回灌  扫描失败（有目录列不出来）—— 不是「NAS 上没有文件」，本次放弃")
                    return@withContext RestorePlan(unreadable = true)
                }
            val scanMs = System.currentTimeMillis() - t0

            val all = dao.allRecords()
            val dbPaths = HashSet<String>(all.size * 2)
            val dbRemotes = HashSet<String>(all.size * 2)
            for (r in all) {
                dbPaths.add(r.path)
                dbRemotes.add(r.remotePath)
            }

            val cands = ArrayList<RestoreItem>()
            val inconsistent = ArrayList<String>()
            val residue = ArrayList<String>()
            val dedupeArtifacts = ArrayList<String>()
            var knownBytes = 0L
            var unknownSize = 0
            var alreadyLocal = 0
            var alreadyInDb = 0

            for ((remote, e) in files) {
                if (!remote.startsWith("$root/")) continue
                val rel = remote.removePrefix("$root/")
                val raw = "/storage/emulated/0/$rel"
                val localPath = MediaRepo.canonicalPath(raw)
                // 目录名不合规：灌下去本地与远端就对不上了 —— 报告，不猜
                if (localPath != raw) {
                    inconsistent.add(rel)
                    continue
                }
                if (remote in dbRemotes) {
                    alreadyInDb++
                    continue
                }
                // ---- 走到这里 ⇒ DB 的 remotePath 里没有它。分三类，别混为一谈 ----
                // 以前这里写成 `remote in dbRemotes || localPath in dbPaths` 一起算 alreadyInDb，
                // 结果把"NAS 上真实存在的无主文件"静默吞掉了（实测 11 个）——
                // 与 #90/#103 是同一条纪律：**异常不能因为"没造成崩溃"就不报**。
                if (isDedupeArtifact(rel, files, dbRemotes, root)) {
                    dedupeArtifacts.add(rel)
                    continue
                }
                if (localPath in dbPaths) {
                    // 本地路径已被另一条记录占用 ⇒ 这是"旧内容留在 NAS 上"的残留副本，不能灌
                    residue.add(rel)
                    continue
                }
                if (File(localPath).exists()) {
                    alreadyLocal++
                    continue
                }
                if (e.size < 0) unknownSize++ else knownBytes += e.size
                cands.add(RestoreItem(rel, remote, localPath, e.size, e.mtime))
            }

            onLog(
                "·规划  NAS ${files.size} 个 · 待灌 ${cands.size} 个（${fmt(knownBytes)}）· " +
                    "已有记录 ${alreadyInDb} · 本地已有 ${alreadyLocal} · 耗时 ${ms2s(scanMs)}"
            )
            if (unknownSize > 0) onLog("⚠️ 有 $unknownSize 个拿不到大小，不会自动放行")
            if (residue.isNotEmpty()) {
                onLog("⚠️ 残留副本 ${residue.size} 个（NAS 上有、无记录，且本地名字已被别的记录占用）—— 不回灌：")
                residue.take(3).forEach { onLog("      $it") }
                if (residue.size > 3) onLog("      …还有 ${residue.size - 3} 个")
            }
            if (dedupeArtifacts.isNotEmpty()) {
                onLog("⚠️ 去重产物 ${dedupeArtifacts.size} 个（形如 X~sha8.jpg，是 App 自己改的名）—— 不回灌：")
                dedupeArtifacts.take(3).forEach { onLog("      $it") }
                if (dedupeArtifacts.size > 3) onLog("      …还有 ${dedupeArtifacts.size - 3} 个")
            }
            if (inconsistent.isNotEmpty()) {
                onLog("⚠️ 有 ${inconsistent.size} 个归档目录名不合规，已排除（先做远端规范化再灌）：")
                inconsistent.take(5).forEach { onLog("      $it") }
                if (inconsistent.size > 5) onLog("      …还有 ${inconsistent.size - 5} 个")
            }
            RestorePlan(
                candidates = cands,
                knownBytes = knownBytes,
                unknownSize = unknownSize,
                alreadyLocal = alreadyLocal,
                alreadyInDb = alreadyInDb,
                residue = residue,
                dedupeArtifacts = dedupeArtifacts,
                inconsistent = inconsistent
            )
        } finally {
            client.close()
        }
    }

    /**
     * 按规划回灌。**每条独立落库 ⇒ 中断后再跑一次自动续上**：
     * 已经灌好的会在 [planRestore] 里被 `alreadyInDb` 挡掉，不需要额外存断点。
     */
    suspend fun runRestorePlan(
        plan: RestorePlan,
        maxFiles: Int = MAX_RESTORE_FILES,
        freeBytesOverride: Long? = null,
        onLog: (String) -> Unit
    ): RestoreResult = withContext(Dispatchers.IO) {
        if (!plan.ok) {
            onLog("!回灌  规划不可用（扫描失败或归档根缺失），拒绝执行")
            return@withContext RestoreResult()
        }
        val list = plan.candidates
        if (list.isEmpty()) {
            onLog("·回灌  没有需要回灌的文件")
            return@withContext RestoreResult()
        }

        // ⭐ 熔断：先算账、再动手。回灌不会丢数据，但会把手机空间吃光。
        // [freeBytesOverride] 是**可注入的测试点** —— 手机可用空间通常以百 GB 计，
        // "装不下"这个分支在真机上没法自然触发；不能因为造不出来就让它永远没被跑过。
        val free = freeBytesOverride ?: freeSpaceBytes()
        if (!plan.fitsIn(free)) {
            val why = when {
                plan.unknownSize > 0 -> "有 ${plan.unknownSize} 个拿不到大小，无法评估"
                free <= 0 -> "读不到手机可用空间"
                else -> "需要 ${fmt(plan.knownBytes)}，可用 ${fmt(free)}"
            }
            onLog("!熔断  本轮不回灌：$why")
            onLog("!熔断  一个字节都没动。清理出空间后重试，或改用「挑选回灌」分次进行。")
            return@withContext RestoreResult()
        }

        val truncated = list.size > maxFiles
        val todo = if (truncated) list.take(maxFiles) else list
        if (truncated) {
            onLog("·回灌  本轮上限 $maxFiles 个，先处理 ${todo.size}/${list.size}（剩余的下一轮继续）")
        }
        onLog("·回灌  开始 ${todo.size} 个，共 ${fmt(plan.knownBytes)}；手机可用 ${fmt(free)}")

        // ⭐ 先清扫上一轮遗留的孤儿临时文件。放在这里而不是"每次下载前各自清"，
        // 是因为强杀留下的那个文件对应的**可能已经不是本轮的候选**（比如用户缩小了范围），
        // 那样它永远等不到被自己清掉的机会。
        val swept = sweepOrphanTemps(todo.map { it.localPath })
        if (swept.first > 0) {
            onLog("·回灌  清扫上一轮遗留的临时文件 ${swept.first} 个（${fmt(swept.second)}）")
        }

        // 内容级查重的索引：库里已有的 sha256 全集。**同一轮内新灌的也要并进去**，
        // 否则 NAS 上两处同内容文件会一前一后都灌进相册（2026-09-16 实测多出过 8 张重复照片）。
        val shaIndex = HashMap<String, PhotoRecord>()
        for (r in dao.allRecords()) shaIndex[r.sha256.lowercase()] = r

        val client = newClient()
        try {
            client.connect()
        } catch (t: Throwable) {
            onLog("!回灌  连接 NAS 失败：${t.message}")
            return@withContext RestoreResult(failed = todo.size)
        }

        var done = 0
        var dup = 0
        var fail = 0
        var bytes = 0L
        try {
            todo.forEachIndexed { i, item ->
                if (!isActive) return@forEachIndexed
                val o = adoptOne(client, item, shaIndex, onLog)
                when {
                    o.ok -> { done++; bytes += o.bytes }
                    o.duplicate -> dup++
                    else -> fail++
                }
                if ((i + 1) % RESTORE_PROGRESS_EVERY == 0) {
                    onLog("·回灌  进度 ${i + 1}/${todo.size}（已恢复 $done）")
                }
            }
        } finally {
            client.close()
        }
        val tail = if (truncated) "  ⚠️ 触到上限，还有 ${list.size - todo.size} 个未处理" else ""
        onLog("·回灌  完成：恢复 $done · 重复跳过 $dup · 失败 $fail（${fmt(bytes)}）$tail")
        RestoreResult(done, dup, fail, truncated, bytes)
    }

    /**
     * `X~966f2b72.jpg` 是不是 [dedupeRemote] 的产物？
     *
     * 判据**刻意保守**：光看形状不够 —— 用户完全可能自己起个带 `~xxxxxxxx` 的名字。
     * 必须同时满足"**把 `~8位hex` 去掉后的 `X.jpg` 也真实存在**"（在 NAS 上，或在库的
     * `remotePath` 里）才算数。
     *
     * ⚠️ 宁可漏判（当普通文件去灌），也不能误判 —— 误判的代价是**该灌的不灌**，
     * 也就是把用户真实存在的照片当成内部垃圾丢掉。
     */
    private fun isDedupeArtifact(
        rel: String,
        files: Map<String, WebDavClient.RemoteEntry>,
        dbRemotes: Set<String>,
        root: String
    ): Boolean {
        val m = DEDUPE_RE.find(rel.substringAfterLast('/')) ?: return false
        val base = m.groupValues[1] + m.groupValues[3] // group2 是 sha8，丢掉
        if (base.isEmpty()) return false
        val dir = rel.substringBeforeLast('/', "")
        val baseRel = if (dir.isEmpty()) base else "$dir/$base"
        val baseRemote = "$root/$baseRel"
        return files.containsKey(baseRemote) || dbRemotes.contains(baseRemote)
    }

    private data class AdoptOutcome(val ok: Boolean, val duplicate: Boolean, val bytes: Long)

    /**
     * 回灌一条：**下载母本 → 现算 sha256 → 查重 → 大小核对 → 落位 → 落库**。
     *
     * 与 [restoreWith] 的差别只有一处，但很关键：**这里的 sha256 事先不知道**。
     * 整库重建时 DB 是空的、NAS 侧也不存校验值，所以顺序只能是
     * 「先下载 → 现算 → 再决定落不落库」，而不是"先比对再下载"。
     *
     * ⚠️ 查重这一步**不能省**。NAS 上可能有两处同内容文件，其中一处已有正式记录；
     * 不查就会把第二份也灌进相册 —— 2026-09-16 实测正是这样凭空多出 8 张重复照片，
     * 事后全部回滚。这里发现重复时**删除刚下载的临时文件、不落库**，做到"没发生过"。
     */
    private fun adoptOne(
        client: WebDavClient,
        item: RestoreItem,
        shaIndex: MutableMap<String, PhotoRecord>,
        onLog: (String) -> Unit
    ): AdoptOutcome {
        val target = File(item.localPath)
        val tmp = File(target.parentFile, "$TEMP_PREFIX${target.name}")
        val name = target.name
        // ⚠️ 下载前先清掉**同名残留**：上一轮被强杀留下的 `.nasphoto_restore_X` 若不清，
        // `download()` 可能往它身上续写 ⇒ 拼出一个长度对得上、内容却错的文件
        // （长度校验只能抓"短了"，抓不住"前半截是旧的"）。删掉最干净。
        if (tmp.exists() && !tmp.delete()) {
            onLog("!回灌  无法清除同名临时文件，跳过 $name")
            return AdoptOutcome(false, false, 0)
        }
        val t0 = System.currentTimeMillis()
        try {
            client.download(item.remotePath, tmp)
        } catch (t: Throwable) {
            tmp.delete()
            onLog("!回灌  下载失败 ${item.rel}：${t.message}")
            return AdoptOutcome(false, false, 0)
        }
        val ms = System.currentTimeMillis() - t0
        val got = tmp.length()
        // ① 大小核对：autoindex 报的 size 就是这一层的"预期值"，能抓住传输截断
        if (item.size >= 0 && got != item.size) {
            tmp.delete()
            onLog("!回灌  大小不符 ${item.rel}：期望 ${item.size}，实得 $got —— 已丢弃")
            return AdoptOutcome(false, false, 0)
        }
        val sha = try {
            Hashing.sha256(tmp)
        } catch (t: Throwable) {
            tmp.delete()
            onLog("!回灌  读取下载结果失败 $name")
            return AdoptOutcome(false, false, 0)
        }
        // ② 内容查重 → 冗余副本：撤回，绝不灌进相册
        if (shaIndex.containsKey(sha.lowercase())) {
            tmp.delete()
            onLog("·回灌  内容在别处已有记录，跳过 ${item.rel}（避免相册多出重复照片）")
            return AdoptOutcome(false, true, 0)
        }
        // ③ 落位。规划阶段已排除过同名文件，这里再挡一次（规划之后用户可能自己放进去了）
        if (target.exists()) {
            tmp.delete()
            onLog("·回灌  本地已存在同名文件，跳过 $name")
            return AdoptOutcome(false, false, 0)
        }
        target.parentFile?.mkdirs()
        if (!tmp.renameTo(target)) {
            tmp.delete()
            onLog("!回灌  重命名失败 $name")
            return AdoptOutcome(false, false, 0)
        }
        val mtime = if (item.mtime > 0) item.mtime else target.lastModified()
        target.setLastModified(mtime)
        scan(target)
        val now = System.currentTimeMillis()
        val rec = PhotoRecord(
            path = item.localPath,
            remotePath = item.remotePath,
            sha256 = sha,
            originalSize = got,
            localSize = got,
            takenAt = takenAtOf(name, mtime),
            origLastModified = mtime,
            // 本地是完整原图 + NAS 有母本 ⇒ 语义上就是「已恢复」。
            // 且 RESTORED 默认粘性 ⇒ **刚灌回来不会被立刻重新降级**（那是很糟的体验）。
            state = STATE_RESTORED,
            offloadedAt = 0,
            restoredAt = now
        )
        dao.upsert(rec)
        shaIndex[sha.lowercase()] = rec
        onLog("↓回灌  ${item.rel}  ${fmt(got)}  ${ms2s(ms)}  ${speed(got, ms)}")
        return AdoptOutcome(true, false, got)
    }

    /**
     * 清扫回灌遗留的孤儿临时文件（`.nasphoto_restore_*`）。
     *
     * **它们是怎么来的**：下载走「先写临时名 → 完整后 rename」，所以半截文件永远不会
     * 以正式名落盘 —— 相册里看不到坏图，这是刻意换来的安全性。代价是 rename 之前
     * 进程若被强杀（用户手工停止 / 系统回收内存 / 崩溃），那个临时文件就留在原地。
     * 它带 `.` 前缀 ⇒ MediaStore 不索引 ⇒ **用户在自己的相册里完全看不见它**，
     * 只能默默占着空间。2026-09-17 实测：一次硬中断留下了 92.5 MB。
     *
     * **清扫范围只限"本轮候选文件所在的目录"**，且只认 [TEMP_PREFIX] 前缀。
     * 不做全盘扫描 —— 全盘遍历手机存储既慢又可能踩到别的应用的隐藏文件。
     *
     * ⚠️ 什么时候**不**能清：本 App 同时只跑一个回灌（UI 上是单次点击、引擎里是
     * 顺序 for 循环），所以进入本函数时不存在"正在写的临时文件"。若将来做了并发回灌，
     * 这里必须改成"只清超过 N 分钟的"。
     *
     * @return 已删个数 与 释放字节数
     */
    private fun sweepOrphanTemps(candidatePaths: List<String>): Pair<Int, Long> {
        var n = 0
        var bytes = 0L
        for (d in candidatePaths.mapNotNull { File(it).parentFile }.toSet()) {
            val junk = d.listFiles { f -> f.isFile && f.name.startsWith(TEMP_PREFIX) } ?: continue
            for (f in junk) {
                val len = f.length()
                if (f.delete()) {
                    n++
                    bytes += len
                }
            }
        }
        return n to bytes
    }

    /**
     * 删除 [deletedPaths] 之后，把因此变空的目录链一并收掉（自底向上，止于 [stopAt]）。
     *
     * 整库回灌回滚后会剩下一整棵空目录树（`Download/_np_big/DCIM/Camera/…`，
     * 实测 14 个）。空目录不占空间，但它在文件管理器和 SMB 里很扎眼，
     * 用户会以为"没删干净"。这里顺手收掉。
     *
     * 安全性：只删**确实为空**的目录，且**绝不越过 [stopAt]**（含它本身）。
     * 空目录无数据，误删代价约等于零 —— 但"不越过 stopAt"这条不能省，
     * 否则一路往上删有可能把用户自己建的空目录也收走。
     *
     * @return 已删目录数
     */
    fun pruneEmptyDirs(deletedPaths: List<String>, stopAt: String): Int {
        val stop = stopAt.trimEnd('/')
        val dirs = LinkedHashSet<File>()
        for (p in deletedPaths) {
            var d = File(p).parentFile
            while (d != null && d.absolutePath.trimEnd('/').length > stop.length) {
                dirs.add(d)
                d = d.parentFile
            }
        }
        var removed = 0
        // 深的优先：先删内层，外层才可能显露出"空了"
        for (d in dirs.sortedByDescending { it.absolutePath.length }) {
            if (d.isDirectory && (d.listFiles()?.isEmpty() == true) && d.delete()) removed++
        }
        return removed
    }

    /** 手机数据分区可用字节；读不到返回 -1（调用方据此**保守停手**，不是"当成 0 也不管"） */
    fun freeSpaceBytes(): Long = try {
        android.os.StatFs(android.os.Environment.getExternalStorageDirectory().absolutePath).availableBytes
    } catch (_: Throwable) {
        -1L
    }

    /**
     * 从文件名推拍摄时间（`IMG20250727224141.jpg` → 2025-07-27 22:41:41 +08），
     * 推不出来就退回 [fallback]。
     *
     * 相册排序依赖 `takenAt`。回灌的文件刚落盘、MediaStore 还没索引，
     * 拿不到系统记的拍摄时间，所以只能从名字里读；读不出来时用 NAS 上的 mtime ——
     * 归档时的 mtime 通常保留了上传时刻、与拍摄时刻相近，比 0 好得多。
     */
    private fun takenAtOf(name: String, fallback: Long): Long {
        val m = TAKEN_RE.find(name) ?: return fallback
        return try {
            TAKEN_FMT.get()?.parse(m.groupValues[1])?.time ?: fallback
        } catch (_: Throwable) {
            fallback
        }
    }

    // ------------------------------------------------------------ 工具
    /**
     * 便宜的"转码能省到空间吗"预估（纯算术，不解码）：
     * 按「设定码率 vs 体积上限」估出目标码率，再乘时长，只有预估能省 15% 以上才值得真去转。
     *
     * ⚠️ 注意：**不再按分辨率判断**。因为转码器不做缩放了（本机缩放通路是坏的，
     * 见 `VideoTranscoder` 顶部说明），编码尺寸一律 = 源尺寸；瘦身完全靠降码率。
     * 而"体积 = 码率 × 时长"与分辨率无关，所以估算式不变、反而更准。
     */
    private fun videoCanShrink(item: MediaItem): Boolean {
        val durSec = item.durationMs / 1000.0
        if (durSec <= 0.5) return false
        val capBytes = prefs.videoMaxMb.toLong() * 1024 * 1024
        val capBps = capBytes * 8.0 / durSec
        val bps = minOf(prefs.videoBitrateKbps * 1000.0, capBps)
        val estBytes = bps / 8.0 * durSec
        return estBytes < item.size * 0.85
    }

    /**
     * 按「上次成功过的通道 → 其次优先级」**静态**挑一条端点。
     *
     * ⚠️ 这里刻意不做探测 —— 调用它的旁路入口（`DeletionWatcher` /
     * `RemoteDeletionWatcher` / `OffloadWorker`）不在协程里，没法 await。
     * 改成"跟着主流程走"：主流程每成功一次都会写回 [Prefs.lastGoodChannel]，
     * 这些入口下次就自动走同一条路，不必给每个入口都塞一遍异步解析。
     */
    private fun pickEndpoint(): Endpoint? {
        val cands = EndpointResolver.candidates(prefs)
        if (cands.isEmpty()) return null
        val last = prefs.lastGoodChannel
        return cands.firstOrNull { it.kind.key == last } ?: cands.first()
    }

    /**
     * @param ep 已解析好的端点；传 null 时按 [pickEndpoint] 静态选一条
     *           （没有启用任何通道时回落到旧的 [Prefs.webdavBase]，保证老配置不失效）
     */
    private fun newClient(ep: Endpoint? = null) = WebDavClient(
        baseUrl = (ep ?: pickEndpoint())?.baseUrl ?: prefs.webdavBase,
        user = prefs.user,
        password = prefs.password,
        allowSelfSigned = prefs.allowSelfSigned
    )

    /**
     * 给「删除同步」用的连接入口（`DeletionWatcher` 在另一个包里）。
     * 复用同一套 WebDAV 参数，不另立一套配置 —— 否则迟早两边不一致。
     */
    fun newClientForDeletion() = newClient()

    /**
     * 给「回收站」页面用的入口 —— 与删除同步共用同一套 WebDAV 参数，不另立配置。
     *
     * ⚠️ 这里**必须先 `connect()`**：宽松 TLS 只在对它调过 `connect()` 之后才装上
     *    （详见 [newClientForDeletion] 那段注释）。漏掉的话 `existsPath()` 一律 false、
     *    `list()` 也拿不到东西 —— 页面会显示成「回收站是空的」，而真相是根本没连上。
     *    所以这里直接把它封在方法内，不给调用方忘掉的机会。
     */
    fun newTrashStore(): cn.dsr213.nasphoto.engine.TrashStore {
        val c = newClient()
        c.connect()
        return cn.dsr213.nasphoto.engine.TrashStore(c, prefs.remoteRoot)
    }

    /** 数据库里的全部记录，按 `remotePath` 索引 —— 回收站页面反查「本地那份还在不在」 */
    fun recordsByRemotePath(): Map<String, cn.dsr213.nasphoto.data.PhotoRecord> =
        dao.allRecords().associateBy { it.remotePath }

    /**
     * 远端相对路径 = `<归档根>/<手机上的相对路径>`，
     * 例：`/storage/emulated/0/Download/SmartStorage/a.jpg`
     *     → `我的文档/NasPhoto归档/Download/SmartStorage/a.jpg`
     *
     * ⚠️ 必须**保留完整目录结构**。早期只认 `/DCIM/`，其余一律压平到根目录，
     * 不同目录下的同名文件会互相覆盖 —— 那是**不可逆的数据丢失**。
     */
    private fun remotePathFor(localPath: String, sha256: String): String {
        val root = prefs.remoteRoot.trim().trim('/')
        val rel = relativeOf(localPath)
        val wanted = if (root.isEmpty()) rel else "$root/$rel"
        return dedupeRemote(wanted, sha256)
    }

    /**
     * 日志展示用的短路径：砍掉归档根前缀，只留 `DCIM/Camera/a.jpg` 这段。
     *
     * 归档根已经在界面上方写着，每行日志再重复一遍纯属噪音；
     * 但**保留子目录**很重要 —— 一眼就能看出文件落在哪，
     * 也方便判断"目录结构有没有被压平"。
     */
    private fun relRemote(remote: String): String {
        val root = prefs.remoteRoot.trim().trim('/')
        return if (root.isNotEmpty() && remote.startsWith("$root/")) remote.removePrefix("$root/") else remote
    }

    /**
     * 第二重保险：若该远端路径已被**另一份不同内容**占用（比如文件名相同但来源不同），
     * 就在扩展名前插入 `~<sha 前 8 位>`，绝不覆盖已有母本。
     */
    private fun dedupeRemote(wanted: String, sha256: String): String {
        val existing = dao.byRemotePath(wanted) ?: return wanted
        if (existing.sha256.equals(sha256, ignoreCase = true)) return wanted
        val dot = wanted.lastIndexOf('.')
        val tail = "~" + sha256.take(8)
        return if (dot > wanted.lastIndexOf('/')) {
            wanted.substring(0, dot) + tail + wanted.substring(dot)
        } else {
            wanted + tail
        }
    }

    private fun relativeOf(abs: String): String {
        for (m in STORAGE_PREFIXES) {
            val i = abs.indexOf(m)
            if (i >= 0) {
                val rest = abs.substring(i + m.length).trimStart('/')
                if (rest.isNotEmpty()) return rest
            }
        }
        return abs.substringAfterLast('/')
    }

    private fun scan(f: File) {
        try {
            MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), null, null)
        } catch (_: Throwable) {
        }
    }
}

fun fmt(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format("%.2f GB", bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> String.format("%.1f MB", bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> String.format("%.1f KB", bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}

/** 耗时展示：`820ms` / `1.2s` / `1m04s`。日志里要能一眼看出快慢。 */
fun ms2s(ms: Long): String = when {
    ms < 0L -> "-"
    ms < 1000L -> "${ms}ms"
    ms < 60_000L -> String.format("%.1fs", ms / 1000.0)
    else -> "${ms / 60_000L}m%02ds".format((ms % 60_000L) / 1000L)
}

/** 吞吐展示：给字节数与耗时，算出 `9.7 MB/s`。传输好坏全靠它判断。 */
fun speed(bytes: Long, ms: Long): String {
    if (ms <= 0L || bytes <= 0L) return "-"
    val bps = bytes.toDouble() * 1000.0 / ms
    return when {
        bps >= (1L shl 20) -> String.format("%.1f MB/s", bps / (1L shl 20))
        bps >= (1L shl 10) -> String.format("%.0f KB/s", bps / (1L shl 10))
        else -> String.format("%.0f B/s", bps)
    }
}

private fun codecLabel(mime: String): String = when (mime) {
    "video/hevc" -> "HEVC"
    "video/avc" -> "H.264"
    else -> mime
}
