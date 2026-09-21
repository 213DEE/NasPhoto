package cn.dsr213.nasphoto.service

import android.content.Context
import android.util.Log
import cn.dsr213.nasphoto.data.AppDb
import cn.dsr213.nasphoto.data.Prefs
import cn.dsr213.nasphoto.data.STATE_OFFLOADED
import cn.dsr213.nasphoto.data.SyncLog
import cn.dsr213.nasphoto.engine.OffloadEngine
import cn.dsr213.nasphoto.engine.StoragePermission
import cn.dsr213.nasphoto.engine.TrashStore
import cn.dsr213.nasphoto.engine.fmt
import cn.dsr213.nasphoto.media.MediaRepo
import cn.dsr213.nasphoto.net.WebDavClient
import cn.dsr213.nasphoto.ui.DeleteAskUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * **NAS 侧删除 → 手机同步处理**（双向同步的第二个方向）。
 *
 * ## 场景
 * 用户在电脑上挂着 WebDAV 盘（或在 NAS 上直接操作）把某张照片删了，
 * 手机里那份要么是**降级小图**、要么还是**原图** —— 这时手机上该怎么动？
 *
 * ## 分两种情况（这是刻意的，别拍平）
 * | 手机上的状态 | 数据风险 | 处理 |
 * |---|---|---|
 * | `OFFLOADED`（本地已是降级小图） | 低 —— 本来就是"母本在 NAS"的缓存 | `auto` 模式下**直接删** |
 * | `BACKED_UP` / `RESTORED`（本地还是原图） | **高** —— 母本没了，这份就是仅存的完整副本 | 永远**只提示**，绝不自动删 |
 *
 * 换句话说：`auto` 只对"反正是缓存"的那一类生效。「NAS 删了 → 手机原图也没了」
 * 是不可逆的，必须由人来点。
 *
 * ## 三道安全闸（与 [DeletionWatcher] 同构，但判据全换了一遍）
 * 1. **远端目录列不全就整轮放弃** —— [WebDavClient.listFilesRecursive] 返回 null 时，
 *    "扫描失败"和"文件都被删了"长得一模一样，用它去做删除决策等于拿数据赌运气
 * 2. **归档树读出来是空的，但库里有一堆记录** → 一定是目录被改名/移走或读取异常，
 *    不是"用户把整个归档删了"。这一条能挡掉最恐怖的一种误判
 * 3. **单轮熔断** —— 见 [MAX_BATCH_AUTO] / [MAX_BATCH_ASK]
 *
 * ## 为什么不算「回收站里的文件」
 * `<归档根>/_回收站/` 底下的一切**不算被删**：它还能一键还原，而且很可能就是
 * （⚠️ 写注释时别用带星号通配的路径写法 —— Kotlin 的块注释**是可嵌套的**，
 *   注释体里出现斜杠加星号会直接开一层永不闭合的嵌套注释，编译报 "Unclosed comment"。踩过。）
 * 我们自己按「手机删 → NAS」搬进去的。只有等保留期到了被 [TrashStore.purgeExpired]
 * 真删掉，它才算"NAS 上没了"，那时才会进入这里。
 */
object RemoteDeletionWatcher {

    private const val TAG = "NasPhotoDel"

    /**
     * 熔断阈值。比 [DeletionWatcher] 更紧，因为这一侧的后果更重：
     * 那边动的是"还能还原的 NAS 回收站"，这边动的是**手机上的文件**。
     */
    private const val MAX_BATCH_AUTO = 20
    private const val MAX_RATIO_AUTO = 0.10
    private const val MAX_BATCH_ASK = 200
    private const val MAX_RATIO_ASK = 0.50

    /** 两次远端全树扫描之间的最小间隔 */
    const val SCAN_THROTTLE_MS = 10 * 60_000L

    /** 未决询问的重问间隔（同 [DeletionWatcher.REASK_INTERVAL_MS] 的理由） */
    private const val REASK_INTERVAL_MS = 6L * 3600_000L

    data class Suspect(
        val path: String,
        val remotePath: String,
        /** 本地当前体积（降级后是小图） */
        val localSize: Long,
        /** 原图体积 */
        val originalSize: Long,
        /** 本地是降级小图 → auto 模式可直接删 */
        val offloaded: Boolean
    )

    data class PendingItem(
        val path: String,
        val remotePath: String,
        val localSize: Long,
        val originalSize: Long,
        val offloaded: Boolean
    )

    // ================================================================== 扫描

    /**
     * 找出「数据库里有记录、但 NAS 归档树上已经没有了」的条目。
     *
     * @return `null` = **本轮放弃判定**（连不上 / 列不全 / 明显是读取异常）。
     *   调用方务必把 null 当成"什么都不知道"，绝不能读成"没有远端删除"以外的东西。
     */
    suspend fun scan(ctx: Context, force: Boolean = false): List<Suspect>? = withContext(Dispatchers.IO) {
        val prefs = Prefs(ctx)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.lastRemoteScanAt < SCAN_THROTTLE_MS) return@withContext emptyList()
        prefs.lastRemoteScanAt = now

        val root = prefs.remoteRoot.trim().trim('/')
        if (root.isEmpty()) return@withContext null

        val records = AppDb.get(ctx).photos().allRecords()
        val underRoot = records.filter { it.remotePath.startsWith("$root/") }
        // 归档根下一条记录都没有 = 要么还没开始用，要么 remoteRoot 被改过 —— 两种情况都不该扫
        if (underRoot.isEmpty()) return@withContext emptyList()

        val client = try {
            OffloadEngine(ctx).newClientForDeletion().also { it.connect() }
        } catch (t: Throwable) {
            Log.w(TAG, "远端扫描：连不上 NAS（${t.message}）")
            return@withContext null
        }

        // ---- 安全闸 1：列不全就整轮放弃 ----
        val files = client.listFilesRecursive(root, setOf(TrashStore.TRASH_NAME))
        if (files == null) {
            SyncLog.add("·删除  NAS 目录没读全 —— 本轮放弃远端删除判定（不猜）")
            return@withContext null
        }

        // ---- 安全闸 2：非空库 + 空归档树 = 读取异常，绝不是"全被删了" ----
        if (files.isEmpty()) {
            SyncLog.add(
                "·删除  NAS 归档树读出来是空的，但库里还有 ${underRoot.size} 条 —— " +
                    "判定为读取异常/目录被移走，本轮放弃"
            )
            return@withContext null
        }

        val alive = HashSet<String>(files.size * 2)
        for (f in files) alive.add(f)

        // 回收站里的（= 我们自己删的，或用户删了但还能还原）不算丢失
        val inTrash = trashOrigins(client, root)

        val suspects = ArrayList<Suspect>()
        for (r in underRoot) {
            if (r.remotePath in alive) continue
            if (r.remotePath in inTrash) continue
            // 本地也没了 → 那是 [OffloadEngine.pruneMissing] 的活，不在这里重复处理
            if (!File(r.path).exists()) continue
            suspects.add(
                Suspect(
                    path = r.path,
                    remotePath = r.remotePath,
                    localSize = r.localSize,
                    originalSize = r.originalSize,
                    offloaded = r.state == STATE_OFFLOADED
                )
            )
        }
        suspects
    }

    /**
     * 回收站里那些文件的**原始归档路径**集合。
     *
     * `<root>/_回收站/<日期>/DCIM/Camera/a.jpg` → `<root>/DCIM/Camera/a.jpg`
     * （剥掉 `_回收站` 与其后紧跟的**日期目录**两层，剩下的就是原位）
     */
    private fun trashOrigins(client: WebDavClient, root: String): Set<String> {
        val dir = if (root.isEmpty()) TrashStore.TRASH_NAME else "$root/${TrashStore.TRASH_NAME}"
        val files = runCatching { client.listFilesRecursive(dir, emptySet()) }.getOrNull() ?: return emptySet()
        val out = HashSet<String>(files.size * 2)
        for (f in files) {
            val parts = f.split('/')
            val i = parts.indexOf(TrashStore.TRASH_NAME)
            if (i < 0 || i >= parts.size - 2) continue
            val orig = parts.drop(i + 1).drop(1).joinToString("/")
            if (orig.isNotEmpty()) out.add(if (root.isEmpty()) orig else "$root/$orig")
        }
        return out
    }

    // ================================================================== 处理

    /**
     * 按 [Prefs.remoteDeleteSyncMode] 处理一批"NAS 那边没了"的条目。
     *
     * ⚠️ 返回值**含义随模式而变**，调用方别把它当成"删了几个"：
     * - `ask`  → 本轮**新登记**的待办条数（一个文件都没删）
     * - `auto` → 本轮**真正删掉的**本地文件数
     * - `off`  → 恒为 0
     * 只适合用来判断"这轮有没有发现新情况"，不适合拿去做统计。
     */
    suspend fun handle(ctx: Context, suspects: List<Suspect>): Int {
        if (suspects.isEmpty()) return 0
        val prefs = Prefs(ctx)
        val mode = prefs.remoteDeleteSyncMode

        // ---- 安全闸 3：熔断 ----
        val total = AppDb.get(ctx).photos().allRecords().size.coerceAtLeast(1)
        val maxBatch = if (mode == "auto") MAX_BATCH_AUTO else MAX_BATCH_ASK
        val maxRatio = if (mode == "auto") MAX_RATIO_AUTO else MAX_RATIO_ASK
        if (suspects.size >= maxBatch || suspects.size >= total * maxRatio) {
            SyncLog.add(
                "·删除  NAS 侧一次性少了 ${suspects.size}/$total 个 —— 超安全阈值，已暂停。" +
                    "请确认归档目录是不是被改名/移走了"
            )
            DeleteAskUi.warnBulkRemote(ctx, suspects.size, total)
            return 0
        }

        return when (mode) {
            "off" -> {
                SyncLog.add("·删除  NAS 侧少了 ${suspects.size} 个，但「NAS→手机」已关闭 —— 不动手机")
                0
            }

            "auto" -> {
                // 降级小图（本来就是"母本在 NAS"的缓存）可以直接清
                val auto = suspects.filter { it.offloaded }
                // 本地还是原图的：母本没了，这份就是仅存完整副本 → 永远只提示
                val manual = suspects.filterNot { it.offloaded }

                var n = 0
                // 自动删失败的不用登记：记录还在、本地文件还在，下一轮扫描会重新发现它
                if (auto.isNotEmpty()) n = deleteLocalNow(ctx, auto).first

                if (manual.isNotEmpty()) {
                    val added = mergePending(ctx, manual)
                    val pendingN = pendingCount(ctx)
                    val overdue =
                        System.currentTimeMillis() - prefs.lastRemoteAskAt > REASK_INTERVAL_MS
                    if (pendingN > 0 && (added > 0 || overdue)) {
                        val bytes = manual.sumOf { it.localSize }
                        SyncLog.add(
                            "·删除  NAS 上删了 ${manual.size} 个，但手机里还是原图（${fmt(bytes)}）—— " +
                                "共 $pendingN 个等你决定要不要一起删"
                        )
                        DeleteAskUi.askRemote(
                            ctx, pendingN, bytes, manual.last().path.substringAfterLast('/')
                        )
                        prefs.lastRemoteAskAt = System.currentTimeMillis()
                    }
                }
                n
            }

            else -> {
                val added = mergePending(ctx, suspects)
                val pendingN = pendingCount(ctx)
                val overdue = System.currentTimeMillis() - prefs.lastRemoteAskAt > REASK_INTERVAL_MS
                if (pendingN > 0 && (added > 0 || overdue)) {
                    val bytes = suspects.sumOf { it.localSize }
                    SyncLog.add(
                        "·删除  NAS 上删了 ${suspects.size} 个，共 $pendingN 个待你确认要不要从手机也删掉"
                    )
                    DeleteAskUi.askRemote(
                        ctx, pendingN, bytes, suspects.last().path.substringAfterLast('/')
                    )
                    prefs.lastRemoteAskAt = System.currentTimeMillis()
                }
                added
            }
        }
    }

    /** 扫描 + 处理一步到位（后台定时通道用） */
    suspend fun scanAndHandle(ctx: Context): Int {
        val s = scan(ctx) ?: return 0
        if (s.isEmpty()) return 0
        return handle(ctx, s)
    }

    /**
     * 用户点了「手机上也删掉」：真删本地文件 + 清 MediaStore 条目 + 删记录。
     *
     * 删数据库记录是**必须**的：NAS 上那份已经不存在，记录留着只会让后面每一轮扫描
     * 都把它重新报成"NAS 删了"，变成一个永远问不完的死循环。
     */
    suspend fun applyDeleteLocal(ctx: Context): Int = withContext(Dispatchers.IO) {
        val pending = loadPending(ctx)
        if (pending.isEmpty()) return@withContext 0

        val (n, failed) = deleteLocalNow(ctx, pending.map {
            Suspect(it.path, it.remotePath, it.localSize, it.originalSize, it.offloaded)
        })

        // ⚠️ 处理完必须回写待办（哪怕清成空）——漏掉这一步的话，删过的条目会永远留在
        //    待办里，下次询问的计数会把它们算进去，用户看到的是虚高的"N 个待确认"。
        savePending(ctx, failed.map {
            PendingItem(it.path, it.remotePath, it.localSize, it.originalSize, it.offloaded)
        })
        if (n > 0) SyncLog.add("·删除  已按你的选择从手机删除 $n 个")
        n
    }

    /** 用户点了「保留手机上的」：清空待办，手机一个字节都不动 */
    fun keepAll(ctx: Context) {
        val n = pendingCount(ctx)
        savePending(ctx, emptyList())
        if (n > 0) SyncLog.add("·删除  你选择保留手机上的副本（$n 个）—— 没有动手机")
    }

    /**
     * 真正执行本地删除。返回 (成功数, 失败的那些条目)。
     *
     * 顺序：**先删文件 → 再删记录**。反过来的话，文件还在但记录没了，
     * 它既不会被同步、也不会再被任何扫描看到 —— 又是一个孤儿。
     */
    private fun deleteLocalNow(ctx: Context, items: List<Suspect>): Pair<Int, List<Suspect>> {
        // ⚠️ 缺「所有文件访问」时**一条都别试**（#103）。这个权限被撤销时系统不通知 App，
        //    而这里删的全是相机 / 微信创建的文件，没有它必然**全部**失败。
        //    提前整体跳过，免得刷一屏"A 删不掉、B 删不掉"把真正的原因埋掉；
        //    条目原样留在 failed 里等下次重试，一个都不会丢。
        if (!StoragePermission.canWritePublicStorage(ctx)) {
            SyncLog.add(
                "!权限  缺少「所有文件访问」权限，本次未删任何本地文件" +
                    "（${items.size} 个待删已保留，下轮重试）"
            )
            Log.w(TAG, "缺写权限，整体跳过本地删除 ${items.size} 个")
            return 0 to items
        }
        val repo = MediaRepo(ctx)
        val dao = AppDb.get(ctx).photos()
        var n = 0
        val failed = ArrayList<Suspect>()
        for (s in items) {
            if (repo.deleteLocal(s.path)) {
                dao.deleteByPath(s.path)
                n++
                SyncLog.add("·删除  NAS 上已删除 → 手机本地一并清掉：${s.path.substringAfterLast('/')}")
            } else {
                failed.add(s)
                SyncLog.add("!失败  删不掉本地文件，已留住，下次再试：${s.path.substringAfterLast('/')}")
            }
        }
        if (failed.isNotEmpty()) Log.w(TAG, "本地删除失败 ${failed.size} 个")
        return n to failed
    }

    // ================================================================== 待办存储

    private fun loadPending(ctx: Context): List<PendingItem> = try {
        val raw = Prefs(ctx).pendingRemoteDeletions
        if (raw.isBlank()) emptyList() else {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val path = o.optString("path")
                if (path.isBlank()) null else PendingItem(
                    path = path,
                    remotePath = o.optString("remotePath"),
                    localSize = o.optLong("localSize"),
                    originalSize = o.optLong("originalSize"),
                    offloaded = o.optBoolean("offloaded")
                )
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
                .put("localSize", p.localSize)
                .put("originalSize", p.originalSize)
                .put("offloaded", p.offloaded)
                .put("at", System.currentTimeMillis())
        )
        Prefs(ctx).pendingRemoteDeletions = arr.toString()
    }

    private fun mergePending(ctx: Context, suspects: List<Suspect>): Int {
        val cur = loadPending(ctx).associateBy { it.path }.toMutableMap()
        var added = 0
        for (s in suspects) {
            if (cur.containsKey(s.path)) continue
            cur[s.path] = PendingItem(s.path, s.remotePath, s.localSize, s.originalSize, s.offloaded)
            added++
        }
        savePending(ctx, cur.values.toList())
        return added
    }

    fun pendingCount(ctx: Context): Int = loadPending(ctx).size
}
