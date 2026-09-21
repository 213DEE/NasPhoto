package cn.dsr213.nasphoto.ui

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cn.dsr213.nasphoto.R
import cn.dsr213.nasphoto.engine.OffloadEngine
import cn.dsr213.nasphoto.engine.TrashStore
import cn.dsr213.nasphoto.engine.fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 回收站（最近删除）。
 *
 * 这是 App 里**唯一一个能真正抹掉母本的地方**，所以交互上刻意做了分层：
 *
 * | 动作 | 入口 | 可逆性 |
 * |---|---|---|
 * | 还原 | 行内按钮 / 全部还原 | 可逆（只是 MOVE 回原位） |
 * | 彻底删除 | **长按整行** + 二次确认 | **不可逆** |
 * | 清空回收站 | 底部按钮 + 二次确认 | **不可逆** |
 *
 * 几条设计约束（都是踩过的）：
 * - **读不到 ≠ 空的**：`TrashStore.list()` 返回 null 时页面明确报"没读到"，
 *   绝不渲染成空列表 —— 否则用户会以为回收站里的东西全没了，跑去别处找。
 * - **不自己算路径**：原位路径一律交给 `TrashStore.origRelOf()`，那边处理过
 *   "`_回收站` 与日期两层"这个坑（`split(limit=2)` 会凭空多出一层日期）。
 * - **还原不会覆盖**：`WebDavClient.move` 走 `Overwrite: F`，撞车返回 412 什么都不动。
 *   所以这里先在界面上说清"原位已有同名文件"，而不是让用户点了只看到"失败"。
 */
class TrashActivity : AppCompatActivity() {

    private lateinit var engine: OffloadEngine
    private lateinit var adapter: TrashAdapter
    private lateinit var tvSummary: TextView
    private lateinit var tvLog: TextView

    /** 已连上 NAS 的回收站入口；null = 还没连上 */
    private var store: TrashStore? = null
    private var items: List<TrashStore.TrashItem> = emptyList()

    /** 有任务在跑时挡住重复点击 —— 批量操作跑两次会很难解释结果 */
    private var busy = false
    private val logBuf = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trash)

        engine = OffloadEngine(this)
        tvSummary = findViewById(R.id.tvSummary)
        tvLog = findViewById(R.id.tvLog)

        adapter = TrashAdapter(
            onRestore = { restoreOne(it) },
            onDelete = { askDeleteOne(it) }
        )
        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@TrashActivity)
            adapter = this@TrashActivity.adapter
        }
        findViewById<Button>(R.id.btnRestoreAll).setOnClickListener { askRestoreAll() }
        findViewById<Button>(R.id.btnEmptyTrash).setOnClickListener { askEmpty() }
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        load()
    }

    // ------------------------------------------------------------------ 读

    private fun load() {
        if (busy) return
        busy = true
        tvSummary.text = "正在读取回收站…"
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { fetch() }
            busy = false
            if (res == null) {
                tvSummary.text = "⚠ 没能读到回收站（连不上 NAS，或目录没列全）\n" +
                    "为免误判成「里面是空的」，这里不显示空列表。点「返回」可重进重试。"
                return@launch
            }
            val (st, list, days) = res
            store = st
            items = list
            adapter.submit(list)
            renderSummary(days)
        }
    }

    /**
     * 返回 (入口, 条目, 保留天数)；**读不到返回 null**。
     *
     * ⚠️ 这里刻意把「列不全」当成失败：宁可让用户看到一条明确的错误，
     *    也不要给他一个可能是假的「空回收站」。
     */
    private fun fetch(): Triple<TrashStore, List<TrashStore.TrashItem>, Int>? {
        return try {
            val st = engine.newTrashStore()
            val raw = st.list() ?: return null
            val recs = engine.recordsByRemotePath()
            // 大小与「本地那份还在不在」**优先查数据库**（一次查询全搞定）。
            // 只有库里查不到记录的条目（例如"手机删→NAS"时记录已随之清掉）才去发 HEAD，
            // 那种一般是十来条，等一下可以接受；能省则省。
            val mapped = raw.map { e ->
                val rec = recs[st.remoteRelOf(e.origRel)]
                e.copy(
                    origSize = rec?.originalSize ?: st.sizeOf(e.trashRel),
                    localState = rec?.state
                )
            }
            Triple(st, mapped, st.keepDays())
        } catch (t: Throwable) {
            logLine("!失败  连 NAS：" + (t.message ?: t.javaClass.simpleName))
            null
        }
    }

    private fun renderSummary(days: Int) {
        if (items.isEmpty()) {
            tvSummary.text = "回收站是空的"
            return
        }
        val known = items.filter { it.origSize >= 0 }
        val total = known.sumOf { it.origSize }
        tvSummary.text = buildString {
            append("共 ${items.size} 个文件")
            when {
                known.size == items.size && total > 0 -> append(" · ${fmt(total)}")
                known.isNotEmpty() -> append(" · 已知 ${fmt(total)}，另 ${items.size - known.size} 个大小未知")
            }
            append("\n超过 $days 天没还原的，后台会自动彻底清掉")
        }
    }

    // ------------------------------------------------------------------ 还原（可逆）

    private fun restoreOne(item: TrashStore.TrashItem) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) { doRestore(item) }
            busy = false
            logLine(msg)
            load()
        }
    }

    private fun askRestoreAll() {
        if (items.isEmpty()) {
            toast("回收站是空的")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("全部还原？")
            .setMessage(
                "把回收站里的 ${items.size} 个文件都移回原位。\n\n" +
                    "原位已经有同名文件的会**跳过**，不会被覆盖。"
            )
            .setPositiveButton("全部还原") { _, _ -> restoreAll() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun restoreAll() {
        if (busy) return
        busy = true
        tvSummary.text = "正在还原…"
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                val st = store ?: return@withContext "没连上 NAS，未做任何改动"
                var ok = 0
                var skip = 0
                var fail = 0
                for (item in items) {
                    try {
                        if (st.originalOccupied(item.origRel)) skip++
                        else if (st.moveBack(item.trashRel)) ok++ else fail++
                    } catch (_: Throwable) {
                        fail++
                    }
                }
                "全部还原：成功 $ok · 跳过 $skip（原位已有同名）· 失败 $fail"
            }
            busy = false
            logLine("↑ " + summary)
            load()
        }
    }

    private fun doRestore(item: TrashStore.TrashItem): String = try {
        val st = store ?: return "没连上 NAS，未做任何改动"
        when {
            st.originalOccupied(item.origRel) ->
                "·跳过  ${item.name} —— 原位已有同名文件，没有覆盖它"
            st.moveBack(item.trashRel) -> buildString {
                append("↑还原  ").append(item.origRel)
                // 手机相册里已经没有这份时要说清 —— 否则用户会去相册里找，找不到以为没成功
                if (item.localState == null) append("（手机上没有这份，还原后只存在于 NAS）")
            }
            else -> "!失败  还原 ${item.origRel}（MOVE 没成功，原件仍在回收站）"
        }
    } catch (t: Throwable) {
        "!失败  还原 ${item.origRel}：" + (t.message ?: t.javaClass.simpleName)
    }

    // ------------------------------------------------------------------ 彻底删除（不可逆）

    private fun askDeleteOne(item: TrashStore.TrashItem) {
        val sizeTxt = if (item.origSize >= 0) "（${fmt(item.origSize)}）" else ""
        AlertDialog.Builder(this)
            .setTitle("彻底删除这个文件？")
            .setMessage(
                item.origRel + sizeTxt + "\n\n" +
                    "⚠️ 不可逆：会从 NAS 上真正抹掉，两块硬盘的副本里都不再保留。\n" +
                    "如果只是想把它拿回来，请点「还原」。"
            )
            .setPositiveButton("彻底删除") { _, _ -> deleteOne(item) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteOne(item: TrashStore.TrashItem) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                val st = store ?: return@withContext "没连上 NAS，未做任何删除"
                try {
                    if (st.deletePermanently(item.trashRel)) "·彻底删除  ${item.origRel}"
                    else "!失败  彻底删除 ${item.origRel}（文件仍在回收站）"
                } catch (t: Throwable) {
                    "!失败  彻底删除 ${item.origRel}：" + (t.message ?: t.javaClass.simpleName)
                }
            }
            busy = false
            logLine(msg)
            load()
        }
    }

    private fun askEmpty() {
        if (items.isEmpty()) {
            toast("回收站已经是空的")
            return
        }
        val known = items.filter { it.origSize >= 0 }
        val sizeTxt = if (known.isNotEmpty()) "，约 ${fmt(known.sumOf { it.origSize })}" else ""
        // 数量异常大时额外提醒 —— 与删除同步的熔断是同一个思路：
        // 突然冒出一大批，先怀疑"是不是读错了"，而不是照着删。
        val abnormal = if (items.size >= 50) {
            "\n\n⚠️ 这个数量比平常大得多。如果意料之外，请先返回、重新进一次，确认不是读取异常。" +
                if (items.size > TrashStore.MAX_EMPTY_FILES) {
                    // 说在前面 —— 否则用户点了确认却发现什么都没发生，只会更困惑
                    "\n\n⛔ 已超过安全上限 ${TrashStore.MAX_EMPTY_FILES} 个，点确认也不会执行删除。"
                } else {
                    ""
                }
        } else {
            ""
        }
        AlertDialog.Builder(this)
            .setTitle("彻底清空回收站？")
            .setMessage(
                "将永久删除 ${items.size} 个文件$sizeTxt。\n\n" +
                    "⚠️ 不可逆：这些文件会从 NAS 上真正抹掉，两块硬盘的副本里都不再保留。\n" +
                    "只是想拿回来的话，请点「全部还原」。" + abnormal
            )
            .setPositiveButton("我确认清空") { _, _ -> emptyTrash() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun emptyTrash() {
        if (busy) return
        busy = true
        tvSummary.text = "正在清空…"
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                val st = store ?: return@withContext "没连上 NAS，未做任何删除"
                try {
                    val r = st.emptyAll()
                    when {
                        // 根目录读不出来 → 一个字节都没动。要说"未执行"，不能说"已清空"
                        r.unreadable -> "!失败  回收站根目录列不出来，未做任何删除"
                        // 熔断：拦在动手之前
                        r.blockedBatches > 0 ->
                            "!暂停  待删文件超过安全上限（${r.blockedBatches} 个批次），未做任何删除。" +
                                "若确实要清，请分批处理"
                        // ⚠️ 这个分支必须存在：删掉一部分 ≠ 失败。旧实现把它归进"没删干净"，
                        //    用户以为什么都没发生、直接退出，剩下的残批次就永远躺在那儿。
                        r.partialBatches > 0 ->
                            "!删除  已删 ${r.deleted} 个，但有 ${r.partialBatches} 个批次只删掉一部分，" +
                                "剩余文件仍在回收站，可再点一次继续"
                        r.badBatches > 0 ->
                            "·彻底删除  ${r.deleted} 个文件，另有 ${r.badBatches} 个批次没删干净"
                        else -> "·彻底删除  ${r.deleted} 个文件（回收站已清空）"
                    }
                } catch (t: Throwable) {
                    "!失败  清空回收站：" + (t.message ?: t.javaClass.simpleName)
                }
            }
            busy = false
            logLine(summary)
            load()
        }
    }

    // ------------------------------------------------------------------ 杂项

    private fun logLine(s: String) {
        logBuf.addLast(s)
        while (logBuf.size > 5) logBuf.removeFirst()
        tvLog.text = logBuf.joinToString("\n")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
