package cn.dsr213.nasphoto.service

import android.content.Context
import android.util.Log
import cn.dsr213.nasphoto.data.AppDb
import cn.dsr213.nasphoto.data.Prefs
import cn.dsr213.nasphoto.data.SyncLog
import cn.dsr213.nasphoto.engine.OffloadEngine
import cn.dsr213.nasphoto.engine.TrashStore
import cn.dsr213.nasphoto.engine.fmt
import cn.dsr213.nasphoto.media.MediaRepo
import cn.dsr213.nasphoto.ui.DeleteAskUi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * **手机侧删除 → NAS 备份同步处理**。
 *
 * ## 语义（刻意选的，别改）
 * 用户在手机相册里删了一张照片，NAS 上那份母本怎么处理？三种模式（`Prefs.deleteSyncMode`）：
 * 默认 `ask` —— 弹一句问，**不问就不动 NAS**。
 * 无论哪种模式，NAS 侧都**只 MOVE 进回收站、绝不硬删**：手机端删掉的是降级小图，
 * NAS 上那份才是唯一母本，硬删等于真丢。
 *
 * ## 三道安全闸（少一道都可能误删，而误删不可逆）
 * 1. **媒体库读不全就整轮放弃** —— 半截结果会被读成"一大批被删了"
 * 2. **系统回收站不计入删除** —— 小米相册删照片是先进系统回收站（30 天），
 *    用户随时可能点"恢复"，那时判成删除是错的
 * 3. **单轮熔断** —— 待处理数 ≥ [MAX_BATCH] 或 ≥ [MAX_RATIO] 就停下要人工确认。
 *    防的是"归档目录被改名/移走"被判定成"全删"
 *
 * ## 还有一条容易被忽略的反向保护
 * 用户从系统回收站把照片恢复了 → 本地文件又回来了，但 `_id` 没变，
 * 增量水位线扫不到它 → **会被当成"从未备份过"而一直裸奔**。
 * 所以这里记着一本 `Prefs.trashIndex`：发现"刚被我们移进回收站的路径又活了"，
 * 就把 NAS 母本**搬回原位**，而不是重新上传一遍（省一次几十 MB 的上传）。
 */
object DeletionWatcher {

    private const val TAG = "NasPhotoDel"

    /**
     * 熔断阈值**分两档**，因为两种模式的风险完全不同：
     *
     * - `auto`（不打扰、直接删）：没有二次确认，阈值必须保守
     * - `ask`（弹窗问）：**用户会亲眼看到"删了 N 项"再决定**，本身就有确认环节，
     *   阈值可以放宽 —— 否则"相册大扫除删了 30 张"这种正常操作会被挡住，
     *   用户反而没地方去同步
     */
    private const val MAX_BATCH_AUTO = 50
    private const val MAX_RATIO_AUTO = 0.20
    private const val MAX_BATCH_ASK = 200
    private const val MAX_RATIO_ASK = 0.50

    /** 两次全库比对之间的最小间隔（相册变动很频繁，不必每次都比） */
    private const val SCAN_THROTTLE_MS = 45_000L

    /**
     * 同一次未决询问**最多隔多久重问一次**。
     *
     * ⚠️ 为什么需要它：卡片会超时收起、通知会被划掉，用户很可能根本没看见。
     * 只认"首次发现才问"的话，那条待办就永远静默挂着 —— 用户永远不知道被问过。
     * 6 小时一次，既有机会被看到，也不至于烦人。
     */
    private const val REASK_INTERVAL_MS = 6L * 3600_000L

    /** 回收站索引保留多久（"恢复"场景反查用） */
    private const val INDEX_TTL_MS = 60L * 86_400_000L
    private const val INDEX_MAX = 300

    data class Suspect(val path: String, val remotePath: String, val size: Long)

    data class PendingItem(val path: String, val remotePath: String, val size: Long)

    data class TrashIndexItem(val path: String, val trashRel: String, val at: Long)

    // ================================================================== 扫描

    /**
     * 找出「数据库里有、但手机媒体库里已经没有了」的条目。
     *
     * @return null = **本轮放弃判定**（媒体库读不出来）。
     *   调用方必须把 null 当成"什么都不知道"，绝不能理解成"没有删除"以外的意思。
     */
    suspend fun scan(ctx: Context, force: Boolean = false): List<Suspect>? {
        val prefs = Prefs(ctx)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.lastDeletionScanAt < SCAN_THROTTLE_MS) return emptyList()
        prefs.lastDeletionScanAt = now

        val repo = MediaRepo(ctx)
        // ---- 安全闸 1：读不全就整轮放弃 ----
        val lib = repo.libraryPaths() ?: return null

        // ---- 安全闸 1b：可见集为空 = 读取失败，不是"全删了" ----
        //
        // ⚠️ 这条是拿一次真实误报换来的：某些时刻（MediaProvider 刚启动、重装后首次运行）
        //    `query()` 会**不抛异常地返回空结果**。此时若拿 DB 去比，全部记录都会被判成
        //    "已删除"，直接撞上熔断 —— 用户会收到一条"疑似大批量删除"的惊悚通知。
        //    库里明明还有几百张，可见集不可能是空的，所以这一定是读取问题。
        val recordCount = AppDb.get(ctx).photos().allRecords().size
        if (lib.visible.isEmpty() && recordCount > 0) {
            SyncLog.add("·删除  媒体库读取异常（可见项为 0，但库里有 $recordCount 条）—— 本轮放弃判定")
            Log.w(TAG, "可见集为空但库里有记录，判定为读取失败")
            return null
        }

        // ---- 反向保护：从系统回收站恢复出来的，先把 NAS 母本搬回来 ----
        runCatching { restoreBackFromTrash(ctx, lib.all) }
            .onFailure { Log.w(TAG, "回收站回搬失败：${it.message}") }

        val dao = AppDb.get(ctx).photos()
        val records = dao.allRecords()
        val suspects = records
            .filter { it.path !in lib.all && !File(it.path).exists() }
            .map { Suspect(it.path, it.remotePath, it.originalSize) }

        return suspects
    }

    // ================================================================== 处理

    /**
     * 按用户配置的模式处理一批可疑删除。
     * @return 新登记/新处理的条数
     */
    suspend fun handle(ctx: Context, suspects: List<Suspect>): Int {
        if (suspects.isEmpty()) return 0
        val prefs = Prefs(ctx)
        val mode = prefs.deleteSyncMode

        // ---- 安全闸 3：熔断（阈值随模式变，见常量注释）----
        val total = AppDb.get(ctx).photos().allRecords().size.coerceAtLeast(1)
        val maxBatch = if (mode == "auto") MAX_BATCH_AUTO else MAX_BATCH_ASK
        val maxRatio = if (mode == "auto") MAX_RATIO_AUTO else MAX_RATIO_ASK
        if (suspects.size >= maxBatch || suspects.size >= (total * maxRatio)) {
            SyncLog.add(
                "·删除  疑似大批量删除（${suspects.size}/$total 条，超阈值）—— 已暂停，"
                    + "请确认 NAS 归档目录或手机相册是否被改动过"
            )
            DeleteAskUi.warnBulk(ctx, suspects.size, total)
            return 0
        }

        return when (mode) {
            "off" -> {
                SyncLog.add("·删除  发现 ${suspects.size} 个手机侧已删，但「删除同步」已关闭 —— 不动 NAS")
                0
            }

            "auto" -> {
                mergePending(ctx, suspects)
                applyTrash(ctx)
            }

            else -> {
                // ask：只登记 + 弹窗，**不问就不动 NAS**
                val added = mergePending(ctx, suspects)
                val pendingN = pendingCount(ctx)
                val overdue = System.currentTimeMillis() - prefs.lastDeleteAskAt > REASK_INTERVAL_MS

                // 条件不是"有新发现"，而是"**有未决待办、且隔够久了**" ——
                // 否则用户错过一次卡片之后就再也没人问过他。
                if (pendingN > 0 && (added > 0 || overdue)) {
                    val bytes = suspects.sumOf { it.size }
                    SyncLog.add(
                        "·删除  手机侧删了 ${suspects.size} 个（${fmt(bytes)}），" +
                            "共 $pendingN 个待你确认要不要一起删 NAS 备份"
                    )
                    DeleteAskUi.ask(
                        ctx,
                        count = pendingN,
                        bytes = bytes,
                        sampleName = suspects.last().path.substringAfterLast('/')
                    )
                    prefs.lastDeleteAskAt = System.currentTimeMillis()
                }
                added
            }
        }
    }

    /**
     * 用户点了「一起删到回收站」：把母本 MOVE 进回收站，**移成功了才删数据库记录**。
     *
     * 顺序不能反：先删记录的话，母本还在 NAS 上就成了拿不回来的孤儿
     * （恢复列表里找不到它，插件也不知道它属于谁）—— 这正是老 `pruneMissing` 的毛病。
     */
    suspend fun applyTrash(ctx: Context): Int {
        val pending = loadPending(ctx)
        if (pending.isEmpty()) return 0

        val dao = AppDb.get(ctx).photos()
        val prefs = Prefs(ctx)

        val client = try {
            OffloadEngine(ctx).newClientForDeletion().also { it.connect() }
        } catch (t: Throwable) {
            SyncLog.add("!失败  连不上 NAS，${pending.size} 个待删先留着，下次再试")
            return 0
        }

        val trash = TrashStore(client, prefs.remoteRoot)
        var moved = 0
        var failed = 0
        val stillPending = ArrayList<PendingItem>()
        val index = ArrayList<TrashIndexItem>()

        for (p in pending) {
            val remote = p.remotePath
            if (remote.isBlank()) {
                stillPending.add(p)
                failed++
                continue
            }
            // 远端本来就不在了（比如之前已手动删过）→ 直接清记录，不需要搬
            val ok = !client.exists(remote) || trash.moveToTrash(remote) { m -> SyncLog.add(m) }
            if (ok) {
                dao.deleteByPath(p.path)
                index.add(TrashIndexItem(p.path, trash.trashPathFor(remote), System.currentTimeMillis()))
                moved++
            } else {
                stillPending.add(p) // 搬不动就留着，记录也保留 —— 不让它变孤儿
                failed++
            }
        }

        savePending(ctx, stillPending)
        appendTrashIndex(ctx, index)

        if (moved > 0) SyncLog.add("·删除  已把 $moved 个母本移入 NAS 回收站（可在「照片回收站」里还原）")
        if (failed > 0) SyncLog.add("!失败  有 $failed 个没能移入回收站，已保留记录待重试")
        return moved
    }

    /** 用户点了「保留」：清掉待办即可，NAS 一个字节都不动 */
    fun keepAll(ctx: Context) {
        val n = pendingCount(ctx)
        savePending(ctx, emptyList())
        if (n > 0) SyncLog.add("·删除  你选择保留 NAS 备份（$n 个）—— 没有动 NAS")
    }

    // ================================================================== 反向：从回收站捞回来

    /**
     * 用户在**系统回收站**里点了"恢复"：本地文件又出现了，但 `_id` 没变，
     * 增量水位线永远扫不到它 → 不处理的话它就再也不会被备份（裸奔）。
     *
     * 所以：发现"最近被我们移进回收站的路径又活了"，就把 NAS 母本搬回原位，
     * 再走一遍正常同步把记录补回来。
     */
    private suspend fun restoreBackFromTrash(ctx: Context, alive: Set<String>) {
        val idx = loadTrashIndex(ctx)
        if (idx.isEmpty()) return

        val revived = idx.filter { it.path in alive }
        if (revived.isEmpty()) return

        val engine = OffloadEngine(ctx)
        val client = try {
            engine.newClientForDeletion().also { it.connect() }
        } catch (t: Throwable) {
            return
        }
        val trash = TrashStore(client, Prefs(ctx).remoteRoot)
        val keep = ArrayList<TrashIndexItem>()
        var back = 0

        for (it in idx) {
            if (it.path !in alive) {
                keep.add(it)
                continue
            }
            if (trash.moveBack(it.trashRel)) {
                back++
                SyncLog.add("·删除  检测到「${File(it.path).name}」被恢复了，NAS 母本已从回收站搬回原位")
                runCatching {
                    val item = MediaRepo(ctx).all(0).firstOrNull { m -> m.path == it.path }
                    if (item != null) engine.run({ m -> SyncLog.add(m) }, null, 1, listOf(item))
                }.onFailure { Log.w(TAG, "恢复后补同步失败：${it.message}") }
            } else {
                keep.add(it) // 搬不回来就留着索引，下次再试
            }
        }
        saveTrashIndex(ctx, keep)
        if (back > 0) Log.i(TAG, "从回收站搬回 $back 个")
    }

    // ================================================================== 待办存储

    private fun loadPending(ctx: Context): List<PendingItem> = try {
        val raw = Prefs(ctx).pendingDeletions
        if (raw.isBlank()) emptyList() else {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val path = o.optString("path")
                if (path.isBlank()) null
                else PendingItem(path, o.optString("remotePath"), o.optLong("size"))
            }
        }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun savePending(ctx: Context, items: List<PendingItem>) {
        val arr = JSONArray()
        for (p in items) arr.put(
            JSONObject()
                .put("path", p.path)
                .put("remotePath", p.remotePath)
                .put("size", p.size)
                .put("at", System.currentTimeMillis())
        )
        Prefs(ctx).pendingDeletions = arr.toString()
    }

    /** 把新发现的并进待办；返回新增个数（决定要不要弹窗） */
    private fun mergePending(ctx: Context, suspects: List<Suspect>): Int {
        val cur = loadPending(ctx).associateBy { it.path }.toMutableMap()
        var added = 0
        for (s in suspects) {
            if (cur.containsKey(s.path)) continue
            cur[s.path] = PendingItem(s.path, s.remotePath, s.size)
            added++
        }
        savePending(ctx, cur.values.toList())
        return added
    }

    fun pendingCount(ctx: Context): Int = loadPending(ctx).size

    // ================================================================== 回收站索引

    private fun loadTrashIndex(ctx: Context): List<TrashIndexItem> = try {
        val raw = Prefs(ctx).trashIndex
        if (raw.isBlank()) emptyList() else {
            val cutoff = System.currentTimeMillis() - INDEX_TTL_MS
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val at = o.optLong("at")
                val path = o.optString("path")
                if (at < cutoff || path.isBlank()) null
                else TrashIndexItem(path, o.optString("trashRel"), at)
            }
        }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun saveTrashIndex(ctx: Context, items: List<TrashIndexItem>) {
        val arr = JSONArray()
        for (it in items.takeLast(INDEX_MAX)) arr.put(
            JSONObject().put("path", it.path).put("trashRel", it.trashRel).put("at", it.at)
        )
        Prefs(ctx).trashIndex = arr.toString()
    }

    private fun appendTrashIndex(ctx: Context, items: List<TrashIndexItem>) {
        if (items.isEmpty()) return
        saveTrashIndex(ctx, loadTrashIndex(ctx) + items)
    }
}
