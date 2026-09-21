package cn.dsr213.nasphoto.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import cn.dsr213.nasphoto.R
import cn.dsr213.nasphoto.engine.OffloadEngine
import cn.dsr213.nasphoto.engine.fmt
import kotlinx.coroutines.launch

/**
 * 「从 NAS 找回照片」—— 回灌的**产品入口**（整库重建 + 挑选回灌）。
 *
 * 分两步走，刻意不合并成一个按钮：
 * 1. **扫描**是**只读**的（不下载、不写库、不动文件），可以先放心看结果；
 * 2. **回灌**才会真的往手机里写。中间那道确认，就是为了让人**先看清要动多少**。
 *
 * 这个顺序对应了两条踩过的坑：
 * - 回灌前必须**先算空间**（NAS 归档整库可以是几十 GB，装不下会把手机塞满）；
 * - 扫描失败**绝不能显示成"NAS 上没有文件"** —— 那会让人以为库是空的。
 *
 * 筛选区（目录 / 时间 / 关键字）是**纯内存**的：[OffloadEngine.pickRestore] 直接复用
 * 扫描时拿到的 `size`/`mtime`，所以每调一次条件都是零网络开销的即时响应，
 * 不需要（也不应该）为此重扫 NAS。
 */
class RestoreActivity : AppCompatActivity() {

    private lateinit var engine: OffloadEngine
    private lateinit var tvSummary: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnScan: Button
    private lateinit var btnRun: Button
    private lateinit var boxFilter: View
    private lateinit var spDir: Spinner
    private lateinit var spTime: Spinner
    private lateinit var etKeyword: EditText

    /** 整库扫描结果，**恒定不变** —— 它是筛选的基准（筛掉多少要拿它当分母） */
    private var full: OffloadEngine.RestorePlan? = null
    /** 筛选之后实际要灌的集合 —— **回灌用的就是它** */
    private var picked: OffloadEngine.RestorePlan? = null
    /** 目录下拉对应的纯目录名；第 0 项为空串 = 全部目录 */
    private var dirKeys: List<String> = listOf("")

    private var lines = 0
    /** 程序化设置 Spinner 时置位，避免把"我在写值"当成"用户在选" */
    private var suppress = false

    private val timeLabels =
        listOf("全部时间", "近 7 天", "近 30 天", "近 90 天", "近 1 年", "一年以前")

    private val onSelect = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
            if (!suppress) applyFilter()
        }

        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_restore)

        engine = OffloadEngine(this)
        tvSummary = findViewById(R.id.tvSummary)
        tvLog = findViewById(R.id.tvLog)
        btnScan = findViewById(R.id.btnScanNas)
        btnRun = findViewById(R.id.btnRunRestore)
        boxFilter = findViewById(R.id.boxFilter)
        spDir = findViewById(R.id.spDir)
        spTime = findViewById(R.id.spTime)
        etKeyword = findViewById(R.id.etKeyword)

        spTime.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, timeLabels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        btnScan.setOnClickListener { scan() }
        btnRun.setOnClickListener { run() }
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        spDir.onItemSelectedListener = onSelect
        spTime.onItemSelectedListener = onSelect
        etKeyword.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun log(msg: String) {
        // 只留最近 200 行：日志区是给人看的，不是审计记录
        if (lines++ > 200) {
            tvLog.text = tvLog.text.split('\n').drop(50).joinToString("\n")
        }
        tvLog.text = if (tvLog.text.isNullOrEmpty()) msg else tvLog.text.toString() + "\n" + msg
        findViewById<ScrollView>(R.id.svLog).post {
            findViewById<ScrollView>(R.id.svLog).fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun setBusy(busy: Boolean) {
        btnScan.isEnabled = !busy
        if (busy) btnRun.isEnabled = false else updateRunEnabled()
    }

    private fun updateRunEnabled() {
        val p = picked
        btnRun.isEnabled = p != null && p.ok && p.total > 0 && p.fitsIn(engine.freeSpaceBytes())
    }

    // ------------------------------------------------------------------ 扫描

    private fun scan() {
        lifecycleScope.launch {
            setBusy(true)
            log("正在扫描 NAS 归档…（只读，不会改动任何数据）")
            val p = engine.planRestore { m -> runOnUiThread { log(m) } }
            full = p
            picked = p
            fillDirSpinner(p)
            boxFilter.visibility = if (p.ok && p.total > 0) View.VISIBLE else View.GONE
            renderSummary(p, p)
            setBusy(false)
        }
    }

    /**
     * 用扫描结果重建目录下拉。第 0 项固定是"全部目录" ——
     * 否则用户一旦选了某个目录就**再也回不到整库**了。
     */
    private fun fillDirSpinner(p: OffloadEngine.RestorePlan) {
        val dirs = if (p.ok) engine.restoreDirs(p) else emptyList()
        dirKeys = listOf("") + dirs.map { it.first }
        val labels = listOf("全部目录") + dirs.map { "${it.first}（${it.second}）" }
        suppress = true
        spDir.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, labels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spDir.setSelection(0)
        suppress = false
    }

    // ------------------------------------------------------------------ 筛选

    private fun applyFilter() {
        val base = full ?: return
        if (!base.ok) return
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val pos = spTime.selectedItemPosition
        val fromMs: Long? = when (pos) {
            1 -> now - 7 * day
            2 -> now - 30 * day
            3 -> now - 90 * day
            4 -> now - 365 * day
            else -> null
        }
        // "一年以前"是**上界**，与上面几项的语义相反 —— 单独一处，别混进 when 里
        val toMs: Long? = if (pos == 5) now - 365 * day else null
        val dir = dirKeys.getOrNull(spDir.selectedItemPosition)?.takeIf { it.isNotEmpty() }
        val kw = etKeyword.text?.toString().orEmpty()

        val f = OffloadEngine.RestoreFilter(fromMs, toMs, dir, kw)
        val p = engine.pickRestore(base, f)
        picked = p
        renderSummary(base, p)
        updateRunEnabled()
    }

    // ------------------------------------------------------------------ 渲染

    private fun renderSummary(base: OffloadEngine.RestorePlan, p: OffloadEngine.RestorePlan) {
        if (!base.ok) {
            tvSummary.text = if (base.rootMissing) {
                "⚠️ 归档根不存在 —— 检查设置里的「NAS 归档根」"
            } else {
                "⚠️ 扫描失败：有目录列不出来。\n" +
                    "这**不是**「NAS 上没有文件」，是没读到 —— 稍后重试。"
            }
            return
        }
        val free = engine.freeSpaceBytes()
        val fits = p.fitsIn(free)
        val sb = StringBuilder()
        if (p.total != base.total) {
            sb.append("筛选中：").append(p.total).append(" / 整库 ").append(base.total)
                .append("（筛掉 ").append(base.total - p.total).append("）\n")
        }
        sb.append("待回灌：").append(p.total).append(" 个（").append(fmt(p.knownBytes)).append("）\n")
        sb.append("已有记录：").append(p.alreadyInDb)
            .append(" · 本地已有：").append(p.alreadyLocal).append("（不覆盖）\n")
        if (p.residue.isNotEmpty()) {
            sb.append("⚠️ 残留副本：").append(p.residue.size).append(" 个（NAS 上有、无记录）\n")
        }
        if (p.dedupeArtifacts.isNotEmpty()) {
            sb.append("⚠️ 去重产物：").append(p.dedupeArtifacts.size).append(" 个（名字里的 ~sha8 是本 App 加的）\n")
        }
        if (p.inconsistent.isNotEmpty()) {
            sb.append("⚠️ 归档目录名不合规：").append(p.inconsistent.size).append(" 个（已排除）\n")
        }
        if (p.unknownSize > 0) {
            sb.append("⚠️ 有 ").append(p.unknownSize).append(" 个拿不到大小，不会自动放行\n")
        }
        sb.append("手机可用：").append(fmt(free)).append(" → ")
        sb.append(if (fits) "✅ 装得下" else "⛔ 装不下，不会开始")
        if (!fits && p.unknownSize == 0 && p.knownBytes > 0) {
            sb.append("\n建议先清理空间，或缩小筛选范围分次进行")
        }
        tvSummary.text = sb.toString()
    }

    // ------------------------------------------------------------------ 回灌

    private fun run() {
        val p = picked ?: return
        val isFiltered = p.total != (full?.total ?: p.total)
        lifecycleScope.launch {
            setBusy(true)
            log(if (isFiltered) "开始回灌（已筛选 ${p.total} 个）…" else "开始回灌…")
            val r = engine.runRestorePlan(p) { m -> runOnUiThread { log(m) } }
            log(
                "完成：恢复 ${r.done} · 重复跳过 ${r.duplicate} · 失败 ${r.failed}" +
                    (if (r.truncated) " · ⚠️ 触到单轮上限，可再点一次继续" else "")
            )
            // ⚠️ 回灌完必须**重新扫描**：否则摘要还停在旧数字上，
            //    再点一次"回灌"会让人以为"没生效"（实际是已经被幂等跳过了）。
            val p2 = engine.planRestore { m -> runOnUiThread { log(m) } }
            full = p2
            // 目录集合可能变了（刚灌完的目录里已经没有候选）⇒ 下拉必须重建，
            // 否则会出现"选了个已经空掉的目录、结果显示 0 个"的困惑。
            fillDirSpinner(p2)
            boxFilter.visibility = if (p2.ok && p2.total > 0) View.VISIBLE else View.GONE
            setBusy(false)
            applyFilter()
        }
    }
}
