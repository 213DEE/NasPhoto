package cn.dsr213.nasphoto.engine

import cn.dsr213.nasphoto.net.WebDavClient
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * NAS 侧「最近删除」的读写约定 —— **App 与 NAS 插件共用，改之前先两边对齐**。
 *
 * ```
 * <归档根>/_回收站/<YYYY-MM-DD>/<原相对路径>      ← 日期目录剥掉就是原位
 * <归档根>/_回收站/.trash_config.json            {"keepDays":7,"updatedAt":...}
 * ```
 *
 * 几个刻意的设计：
 * - **日期目录 = 删除日期**：还原就是一次 `MOVE` 回原位，零判断；插件那边直接读日期目录
 *   算"还剩几天"。
 * - **保留完整目录结构**：同名文件在不同目录下不会互相覆盖（压平 = 不可逆的数据丢失）。
 * - **`_回收站` 带下划线前缀**：App 做全树扫描时要排除它，插件也靠这个前缀识别。
 * - **一律 MOVE，绝不硬删**：手机端删的是降级小图，NAS 上那份才是唯一的母本。
 */
class TrashStore(
    private val client: WebDavClient,
    archiveRoot: String,
    /**
     * 回收站目录名。**只给测试注入用**（指向 `_回收站_TEST` 之类的隔离目录），
     * 生产代码一律用默认值。
     *
     * 存在的理由：[emptyAll] 和 [purgeExpired] 会**真删**回收站里的东西，
     * 想测它们就只能拿真数据冒险 —— 有了这个参数，测试可以在一个隔离批次上
     * 反复验证，而真实回收站里的文件一个都不碰。
     */
    trashName: String = TRASH_NAME
) {

    private val root: String = archiveRoot.trim().trim('/')

    /** `_回收站` 相对 WebDAV 基址的路径 */
    val dir: String = if (root.isEmpty()) trashName else "$root/$trashName"

    /** 配置文件名（隐藏文件，插件 list 时会跳过） */
    private val configPath: String = "$dir/$CONFIG_NAME"

    // ---------------------------------------------------------------- 路径换算

    /** 归档根下的相对路径；不在归档根里就原样返回 */
    fun relUnderArchive(remoteRel: String): String {
        val p = remoteRel.trimStart('/')
        return if (root.isNotEmpty() && p.startsWith("$root/")) p.removePrefix("$root/") else p
    }

    /** 某个远端路径对应的回收站位置 */
    fun trashPathFor(remoteRel: String, date: String = today()): String =
        "$dir/$date/${relUnderArchive(remoteRel)}"

    companion object {
        const val TRASH_NAME = "_回收站"
        private const val CONFIG_NAME = ".trash_config.json"
        const val DEFAULT_KEEP_DAYS = 7
        private const val TAG = "NasPhotoTrash"

        /**
         * 回收站里一次最多删多少个文件。
         *
         * 参考 `DeletionWatcher` 的熔断思路：**突然冒出一大批，先怀疑"是不是读错了"，
         * 而不是照着删**。这里比那边宽松，因为清空回收站是用户明确点过确认的动作；
         * 这个上限只用来挡住"把几千条误读成待删"这类灾难。
         */
        const val MAX_EMPTY_FILES = 1000

        /**
         * `purgeExpired` 的熔断线 —— **比 [MAX_EMPTY_FILES] 紧得多，因为它是无人值守的**：
         * 每轮同步后台自动跑，用户根本看不到。正常节奏下同时过期的批次也就个位数
         * （保留 7 天 ≈ 7 个批次），超过这个数说明要么 `keepDays` 被改小了、
         * 要么日期目录有问题，**停手等人看一眼**才是对的。
         */
        const val MAX_PURGE_BATCHES = 10
        const val MAX_PURGE_FILES = 500

        fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        /** 目录名是不是日期（用来在回收站里挑出"批次目录"） */
        fun isDateDir(name: String): Boolean =
            Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(name)
    }

    /**
     * 一次批量清理的结果。
     *
     * ⚠️ **不要退回成 `Pair<Int, Int>` 或布尔** —— 它们表达不了 [partialBatches]，
     * 而"删了一半"恰恰是最危险的状态：旧实现用一个 `allOk` 布尔把它糊成"失败"，
     * UI 于是显示"没删干净"，**用户以为什么都没发生，实际上已经不可逆地少了一批文件**。
     * 这个字段就是为了让"删了一半"这件事**必须被说出来**。
     */
    data class PurgeResult(
        /** 确认删掉的文件数（**只统计确定成功的**） */
        val deleted: Int = 0,
        /** 完全没动过的批次 —— 安全，下次可直接重试 */
        val badBatches: Int = 0,
        /** ⚠️ 只删了一部分的批次 —— 必须让用户看见 */
        val partialBatches: Int = 0,
        /** 回收站根目录读不出来，整个操作**未执行**（与"没有东西可删"是两回事） */
        val unreadable: Boolean = false,
        /** 因熔断被拦下的批次数（一个字节都没动，等人工处理） */
        val blockedBatches: Int = 0,
    ) {
        val ok: Boolean
            get() = !unreadable && badBatches == 0 && partialBatches == 0 && blockedBatches == 0
    }

    /** 单个批次的删除结果 */
    private data class BatchOutcome(val deleted: Int, val left: Int) {
        val complete: Boolean get() = left == 0
        val partial: Boolean get() = deleted > 0 && left > 0
    }

    /**
     * 删掉一个批次目录及其内容，**如实**统计。
     *
     * 顺序是"先内容、后目录"，中途失败不回滚已删的 —— WebDAV 没有事务，
     * 真原子做不到。能做的是把真相报出来（[BatchOutcome]），而不是用一个布尔盖住。
     * 内容没清干净时**故意保留目录**：让那个残缺批次在界面上看得见、能重试，
     * 比留一个空目录更有信息量。
     *
     * （考虑过"先 MOVE 到临时目录再删"来逼近原子性 —— MOVE 是单次原子操作。
     *  但那要新增隐藏目录、还要 NAS 插件配合识别，收益不抵复杂度，暂不做。）
     */
    private fun deleteBatch(batch: String, inner: List<String>): BatchOutcome {
        var deleted = 0
        var left = 0
        for (f in inner) if (client.delete(f)) deleted++ else left++
        if (left == 0) client.delete(batch)
        return BatchOutcome(deleted, left)
    }

    // ---------------------------------------------------------------- 移进 / 移出

    /**
     * 把 NAS 上的一个文件**移进回收站**（删除的正确姿势）。
     *
     * 返回 true 表示"源文件确实已经不在原位了"（移成功，或它本来就不在）。
     * 返回 false 表示**没搬动**——调用方此时**不能**删数据库记录，否则那个文件就成了
     * 拿不回来的孤儿（这正是之前 pruneMissing 的老毛病）。
     */
    fun moveToTrash(remoteRel: String, onNote: ((String) -> Unit)? = null): Boolean {
        if (!client.existsPath(remoteRel)) return true          // 本来就没有，无需搬

        var dest = trashPathFor(remoteRel)
        val parent = dest.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) client.ensureDirs(parent)

        var code = client.move(remoteRel, dest)
        if (code == 412) {
            // Overwrite: F 撞上同名（同一天删过同名文件）→ 加时间戳，两边都保住
            val stamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
            val slash = dest.lastIndexOf('/')
            val dot = dest.lastIndexOf('.')
            dest = if (dot > slash) {
                dest.substring(0, dot) + "_$stamp" + dest.substring(dot)
            } else {
                dest + "_$stamp"
            }
            code = client.move(remoteRel, dest)
        }

        val gone = !client.existsPath(remoteRel)
        if (!gone) {
            onNote?.invoke("!失败  移入回收站 $remoteRel（HTTP $code，原位仍在）")
        }
        return gone
    }

    /**
     * 从回收站还原回原位（插件那边做的是同一件事）。
     *
     * ⚠️ 剥目录必须**从 `_回收站` 那一层往后切**，不能简单 `split(limit=2)` 取第二段。
     * 踩过的坑：`_回收站/2026-09-16/DCIM/Camera/a.jpg` 用 limit=2 切出来是
     * `["_回收站", "2026-09-16/DCIM/Camera/a.jpg"]` —— 于是把**日期目录当成了原路径**，
     * 还原到了 `<归档根>/2026-09-16/DCIM/Camera/a.jpg`（凭空多一层日期，成了孤儿）。
     *
     * 入参允许带或不带归档根前缀（索引里存的是不带的那种）。
     */
    fun moveBack(trashRel: String): Boolean {
        val orig = origRelOf(trashRel) ?: return false
        val dest = if (root.isEmpty()) orig else "$root/$orig"
        val parent = dest.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) client.ensureDirs(parent)
        val code = client.move(trashRel, dest)
        return code in 200..299
    }

    /**
     * `_回收站/<日期>/x/y` → `x/y`（**不含归档根**）。格式不对返回 null。
     *
     * ⚠️ 剥目录必须**从 `_回收站` 那一层往后切**，不能简单 `split(limit=2)` 取第二段。
     * 踩过的坑：`_回收站/2026-09-16/DCIM/Camera/a.jpg` 用 limit=2 切出来是
     * `["_回收站", "2026-09-16/DCIM/Camera/a.jpg"]` —— 于是把**日期目录当成了原路径**，
     * 还原到了 `<归档根>/2026-09-16/DCIM/Camera/a.jpg`（凭空多一层日期，成了孤儿）。
     *
     * 入参允许带或不带归档根前缀（索引里存的是不带的那种）。
     */
    fun origRelOf(trashRel: String): String? {
        val rel = relUnderArchive(trashRel)
        val parts = rel.split('/').filter { it.isNotEmpty() }
        val i = parts.indexOf(TRASH_NAME)
        if (i < 0 || i >= parts.size - 2) return null   // 至少要还有「日期/文件名」两段
        val orig = parts.drop(i + 1).drop(1).joinToString("/")   // 丢掉 `_回收站` 与日期层
        return orig.ifEmpty { null }
    }

    /** 某个归档内相对路径对应的完整远端路径（含归档根）—— 用来反查数据库记录 */
    fun remoteRelOf(origRel: String): String =
        if (root.isEmpty()) origRel else "$root/${origRel.trimStart('/')}"

    // ---------------------------------------------------------------- 浏览 / 永久删除（回收站页面用）

    /**
     * 回收站里的一条。
     *
     * [origSize] 与 [localState] 不来自 NAS —— 它们由调用方**反查数据库**补上
     * （[origRel] 加归档根就是 `PhotoRecord.remotePath`）。这样做是为了避免
     * 对每个文件发一次 HEAD：目录 HTML 索引里根本没有大小，逐文件 HEAD 在
     * 条目多时会明显卡顿。
     */
    data class TrashItem(
        /** 含 `_回收站` 与日期层的完整相对路径 —— [moveBack] / [deletePermanently] 直接用 */
        val trashRel: String,
        /** 删除日期（日期目录名） */
        val date: String,
        /** 原位相对路径（已剥掉归档根与两层目录） */
        val origRel: String,
        val name: String,
        /** 原位字节数；-1 = 库里查不到（不是本 App 删的，或记录已被清） */
        val origSize: Long = -1L,
        /** 本地那份的状态（`OFFLOADED` / `BACKED_UP`）；null = 手机上没有 */
        val localState: String? = null
    )

    /**
     * 列出回收站全部条目，**按删除日期倒序**。
     *
     * ⚠️ 读不到时返回 `null`，与「真的空」严格区分 —— 与 [WebDavClient.listEntriesOrNull]
     *    同一个理由：把"网络抖动"当成"回收站空了"，页面上会显示成用户以为东西全没了。
     */
    fun list(): List<TrashItem>? {
        val out = ArrayList<TrashItem>()
        val top = client.listEntriesOrNull(dir) ?: return null
        for (e in top) {
            if (!e.isDir || !isDateDir(e.name)) continue
            val batch = "$dir/${e.name}"
            val files = client.listFilesRecursive(batch, emptySet()) ?: return null
            for (f in files) {
                val orig = origRelOf(f) ?: continue
                out.add(
                    TrashItem(
                        trashRel = f,
                        date = e.name,
                        origRel = orig,
                        name = orig.substringAfterLast('/')
                    )
                )
            }
        }
        return out.sortedWith(compareByDescending<TrashItem> { it.date }.thenBy { it.origRel })
    }

    /**
     * 原位是否**已被别的文件占用**。
     *
     * [moveBack] 走的是 `Overwrite: F`，撞车会返回 412、什么都不动 —— 所以这个检查
     * 不是为了"防覆盖"（本来就覆盖不了），而是为了**提前告诉用户为什么还原不了**，
     * 而不是让他点了按钮只看到一句"失败"。
     */
    fun originalOccupied(origRel: String): Boolean = client.existsPath(remoteRelOf(origRel))

    /**
     * 单个条目的字节数（走一次 HEAD）；取不到返回 -1。
     *
     * 目录 HTML 索引里没有大小，只有 `href`，所以想要大小只能逐个问。
     * 调用方应当**优先用数据库里的 `originalSize`**，只有查不到记录时才退到这里 ——
     * 否则十来条就是十来个请求，白等。
     */
    fun sizeOf(trashRel: String): Long = try {
        client.size(trashRel)
    } catch (_: Throwable) {
        -1L
    }

    /**
     * **彻底删除**一条（不可逆）。只删文件本身，不留任何副本。
     *
     * ⚠️ 这是整个 App 里唯一会真正抹掉母本的操作 —— 调用方必须先让用户二次确认。
     *
     * **护栏**：只认回收站目录之下的路径。这个函数太危险，不能只靠调用方自觉传对 ——
     * 路径一旦来自别处（或将来被别处复用），`delete()` 是认不出来的，那就是**直接删归档根里的原图**。
     * 多一次字符串前缀比较，换掉一整类"手滑删库"的可能。
     */
    fun deletePermanently(trashRel: String): Boolean {
        val p = trashRel.trimStart('/')
        if (p != dir && !p.startsWith("$dir/")) {
            android.util.Log.w(TAG, "拒删：路径不在回收站内 -> $trashRel")
            return false
        }
        return client.delete(p)
    }

    /**
     * 清空回收站里**所有日期批次**（`.trash_config.json` 配置保留）。
     *
     * **两阶段**：先全部列出来数清楚（这一阶段只读，出错可以安全放弃），
     * 确认规模没超 [MAX_EMPTY_FILES] 之后才动手删。旧实现是"边列边删"，
     * 数到一半发现读不出第三个批次时，前两个**已经永久删掉了** —— 那才是真不可逆。
     *
     * ⚠️ 与 [purgeExpired] 共用 [deleteBatch]，两者的删除语义必须一致
     * （旧实现是两段各自复制一遍 `for + allOk`，改一处忘一处就是这个 bug 的来源）。
     */
    fun emptyAll(): PurgeResult {
        val top = client.listEntriesOrNull(dir) ?: return PurgeResult(unreadable = true)

        // 阶段一：只读，把要删的东西列全
        val planned = mutableListOf<Pair<String, List<String>>>()
        var bad = 0
        for (e in top) {
            if (!e.isDir || !isDateDir(e.name)) continue
            val batch = "$dir/${e.name}"
            val inner = client.listFilesRecursive(batch, emptySet())
            if (inner == null) {
                bad++
                continue
            }
            planned += batch to inner
        }
        val total = planned.sumOf { it.second.size }
        if (total > MAX_EMPTY_FILES) {
            android.util.Log.w(TAG, "清空回收站被拦：待删 $total 个，超过上限 $MAX_EMPTY_FILES")
            return PurgeResult(badBatches = bad, blockedBatches = planned.size)
        }

        // 阶段二：动手
        var deleted = 0
        var partial = 0
        for ((batch, inner) in planned) {
            val o = deleteBatch(batch, inner)
            deleted += o.deleted
            if (o.partial) partial++ else if (!o.complete) bad++
        }
        return PurgeResult(deleted = deleted, badBatches = bad, partialBatches = partial)
    }

    // ---------------------------------------------------------------- 保留天数 / 清理

    /** 读插件写的保留天数；读不到就按默认值 */
    fun keepDays(): Int = try {
        val raw = downloadText(configPath) ?: return DEFAULT_KEEP_DAYS
        JSONObject(raw).optInt("keepDays", DEFAULT_KEEP_DAYS).coerceIn(1, 3650)
    } catch (_: Throwable) {
        DEFAULT_KEEP_DAYS
    }

    private fun downloadText(rel: String): String? = client.readText(rel)

    /** 回收站里当前有多少个文件（含被删的母本），有目录列不出来时返回 -1 */
    fun count(): Int = client.listFilesRecursive(dir, emptySet())?.size ?: -1

    /**
     * 清掉超过保留天数的批次目录 —— 让"保留 N 天后彻底删"这句承诺真的成立
     * （没有它，回收站只会一直长）。
     *
     * ⚠️ **这是三条不可逆路径里唯一无人值守的一条**：由 `OffloadWorker` 每轮同步后
     * 自动触发，用户看不到、也没人会被问。所以它必须比另外两条**更保守**：
     * - 读不到就报错，**绝不**当成"没有东西可删"（见下）
     * - 超量就熔断，一个字节都不动，交给上层发通知
     *
     * 只删**整目录**（`<日期>/`）。
     */
    fun purgeExpired(now: Long = System.currentTimeMillis()): PurgeResult {
        val keep = keepDays()

        // ⚠️ **必须用 OrNull 版**。旧实现用的是 `listEntries()`，而它内部就是
        //    `listEntriesOrNull() ?: emptyList()` —— NAS 读不到时返回**空列表**，
        //    于是这里安静地"0 删 0 错"收场，等于**报告清理成功**。
        //    用户永远不知道"保留 7 天"其实一次都没执行过。安全方向没错（不会误删），
        //    但失败不可见 = 故障永远查不出来。失败必须可见。
        val top = client.listEntriesOrNull(dir) ?: return PurgeResult(unreadable = true)

        // 阶段一：只读，挑出真过期的
        val expired = mutableListOf<Pair<String, List<String>>>()
        var bad = 0
        for (e in top) {
            if (!e.isDir || !isDateDir(e.name)) continue
            val ts = parseDateMillis(e.name) ?: continue
            // 到期日 = 删除日 + 保留天数（与插件显示的"剩余天数"同一套算法）
            if (ts + keep * 86_400_000L > now) continue

            val batch = "$dir/${e.name}"
            // ⚠️ 不能用 `?: run { continue }` —— `continue` 在 inline lambda 里是实验特性，
            //    编译器会直接报错。老老实实写成语句。
            val inner = client.listFilesRecursive(batch, emptySet())
            if (inner == null) {
                bad++
                continue
            }
            expired += batch to inner
        }

        // 阶段二：熔断 —— 自动路径，宁可不动
        val total = expired.sumOf { it.second.size }
        if (expired.size > MAX_PURGE_BATCHES || total > MAX_PURGE_FILES) {
            android.util.Log.w(
                TAG,
                "自动清理被拦：${expired.size} 个批次 / $total 个文件超过熔断线，未做任何删除"
            )
            return PurgeResult(badBatches = bad, blockedBatches = expired.size)
        }

        // 阶段三：动手
        var deleted = 0
        var partial = 0
        for ((batch, inner) in expired) {
            val o = deleteBatch(batch, inner)
            deleted += o.deleted
            if (o.partial) partial++ else if (!o.complete) bad++
        }
        return PurgeResult(deleted = deleted, badBatches = bad, partialBatches = partial)
    }

    private fun parseDateMillis(name: String): Long? = try {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(name)?.time
    } catch (_: Throwable) {
        null
    }
}
