package cn.dsr213.nasphoto.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cn.dsr213.nasphoto.R
import cn.dsr213.nasphoto.data.PhotoRecord
import cn.dsr213.nasphoto.engine.OffloadEngine
import cn.dsr213.nasphoto.engine.fmt
import kotlinx.coroutines.launch

/** 已卸载列表 + 单张/批量恢复 */
class OffloadedActivity : AppCompatActivity() {

    private lateinit var engine: OffloadEngine
    private lateinit var adapter: OffloadedAdapter
    private lateinit var tvSummary: TextView
    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_offloaded)

        engine = OffloadEngine(this)
        tvSummary = findViewById(R.id.tvSummary)
        tvLog = findViewById(R.id.tvLog)

        adapter = OffloadedAdapter { rec -> restoreOne(rec) }
        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@OffloadedActivity)
            adapter = this@OffloadedActivity.adapter
        }
        findViewById<Button>(R.id.btnRestoreAll).setOnClickListener { restoreAll() }
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        load()
    }

    private fun log(msg: String) {
        tvLog.text = msg
    }

    private fun load() {
        lifecycleScope.launch {
            val list = engine.offloadedList()
            adapter.submit(list)
            val saved = list.sumOf { it.originalSize - it.localSize }
            tvSummary.text = "共 ${list.size} 张已卸载，累计释放 ${fmt(saved)}"
            if (list.isEmpty()) log("没有已卸载的照片")
        }
    }

    private fun restoreOne(rec: PhotoRecord) {
        lifecycleScope.launch {
            log("正在恢复…")
            val ok = engine.restore(rec.path) { m -> runOnUiThread { log(m) } }
            if (ok) {
                load()
            } else {
                log("恢复失败，本地文件保持不变")
            }
        }
    }

    private fun restoreAll() {
        lifecycleScope.launch {
            log("开始批量恢复…")
            engine.restoreAll { m -> runOnUiThread { log(m) } }
            load()
        }
    }
}
