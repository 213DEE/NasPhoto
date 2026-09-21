package cn.dsr213.nasphoto.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import cn.dsr213.nasphoto.R
import cn.dsr213.nasphoto.data.Prefs
import cn.dsr213.nasphoto.data.SyncLog
import cn.dsr213.nasphoto.device.DeviceState
import cn.dsr213.nasphoto.engine.OffloadEngine
import cn.dsr213.nasphoto.engine.PathReconciler
import cn.dsr213.nasphoto.engine.Relink
import cn.dsr213.nasphoto.media.MediaRepo
import cn.dsr213.nasphoto.engine.fmt
import cn.dsr213.nasphoto.net.ChannelKind
import cn.dsr213.nasphoto.net.EndpointResolver
import cn.dsr213.nasphoto.net.Ipv6Learner
import cn.dsr213.nasphoto.net.NetGate
import cn.dsr213.nasphoto.net.NetPolicy
import cn.dsr213.nasphoto.policy.Action
import cn.dsr213.nasphoto.service.SyncGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var engine: OffloadEngine
    private lateinit var tvStats: TextView
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var cbAutoScroll: CheckBox

    /** 界面上展示的日志副本。**只在主线程改**，避免多线程读写打架。 */
    private val logLines = ArrayDeque<String>()

    /** 完整配置文案，点摘要时弹窗展示 */
    private var lastStatsFull: String = ""

    /** 设置里勾了「允许悬浮窗」但还没授权 → 关掉对话框后再跳系统页（避免两个界面互相顶掉） */
    private var pendingOverlayRequest = false

    private val ui = Handler(Looper.getMainLooper())
    private var renderScheduled = false

    /**
     * 合并刷新：一批同步会在 1 秒内产生几十行日志，
     * 每行都 `tvLog.text = ...` 就是 O(n²) 的字符串拼接，会明显卡顿。
     * 所以用 120ms 的时间窗把多次变更合并成一次渲染。
     */
    private val renderRunnable = Runnable {
        renderScheduled = false
        tvLog.text = logLines.joinToString("\n")
        if (cbAutoScroll.isChecked) svLog.post { svLog.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    /** 日志总线回调可能来自**任意线程**（后台服务在 IO 线程跑），必须 post 回主线程。 */
    private val logSink: (String) -> Unit = { line ->
        ui.post {
            logLines.addLast(line)
            while (logLines.size > SyncLog.CAPACITY) logLines.removeFirst()
            scheduleRender()
        }
    }

    private fun scheduleRender() {
        if (renderScheduled) return
        renderScheduled = true
        ui.postDelayed(renderRunnable, 120)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 日志总线先初始化：后面任何一行日志（含探针）都要能落到这里
        SyncLog.init(this)

        // ---- 删除同步探针（自动化验证用；不依赖界面和通知按钮）----
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es delScan 1
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es delAction trash
        intent?.getStringExtra("delAction")?.let { act ->
            lifecycleScope.launch {
                val msg = withContext(Dispatchers.IO) {
                    when (act) {
                        "trash" -> {
                            val n = cn.dsr213.nasphoto.service.DeletionWatcher.applyTrash(this@MainActivity)
                            "delAction=trash → 移入回收站 $n 个"
                        }
                        else -> {
                            cn.dsr213.nasphoto.service.DeletionWatcher.keepAll(this@MainActivity)
                            "delAction=keep → 已保留 NAS 备份"
                        }
                    }
                }
                log(msg)
                android.util.Log.i("NasPhotoProbe", msg)
            }
        }
        // ---- 反向删除同步探针（NAS 删 → 手机删）----
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es remAction del|keep
        intent?.getStringExtra("remAction")?.let { act ->
            lifecycleScope.launch {
                val msg = withContext(Dispatchers.IO) {
                    when (act) {
                        "del" -> "remAction=del → 已删除手机本地 " +
                            cn.dsr213.nasphoto.service.RemoteDeletionWatcher.applyDeleteLocal(this@MainActivity) + " 个"
                        else -> {
                            cn.dsr213.nasphoto.service.RemoteDeletionWatcher.keepAll(this@MainActivity)
                            "remAction=keep → 已保留手机本地副本"
                        }
                    }
                }
                log(msg)
                android.util.Log.i("NasPhotoProbe", msg)
            }
        }
        intent?.getStringExtra("remScan")?.let {
            lifecycleScope.launch {
                val msg = withContext(Dispatchers.IO) {
                    val s = cn.dsr213.nasphoto.service.RemoteDeletionWatcher.scan(
                        this@MainActivity, force = true
                    )
                    val pending =
                        cn.dsr213.nasphoto.service.RemoteDeletionWatcher.pendingCount(this@MainActivity)
                    when {
                        s == null -> "remScan → 本轮放弃判定（连不上 / 列不全 / 明显读取异常）"
                        s.isEmpty() -> "remScan → NAS 侧没有发现被删的（待办 $pending 个）"
                        else -> "remScan → NAS 侧发现 ${s.size} 个已删" +
                            "（降级小图 ${s.count { x -> x.offloaded }} / 本地原图 ${s.count { x -> !x.offloaded }}）" +
                            "，待办 $pending 个：" +
                            s.take(5).joinToString(", ") { x -> x.path.substringAfterLast('/') }
                    }
                }
                log(msg)
                android.util.Log.i("NasPhotoProbe", msg)
            }
        }
        intent?.getStringExtra("delScan")?.let {
            lifecycleScope.launch {
                val msg = withContext(Dispatchers.IO) {
                    val repo = cn.dsr213.nasphoto.media.MediaRepo(this@MainActivity)
                    val lib = repo.libraryPaths()
                    val s = cn.dsr213.nasphoto.service.DeletionWatcher.scan(this@MainActivity, force = true)
                    val head = if (lib == null) "媒体库读取失败"
                    else "库内可见 ${lib.visible.size} / 系统回收站 ${lib.trashed.size}"
                    when {
                        lib == null -> "delScan → ❌ $head"
                        s == null -> "delScan → 本轮放弃判定（$head）"
                        s.isEmpty() -> "delScan → 没有发现手机侧删除（$head）"
                        else -> "delScan → 发现 ${s.size} 个（$head）：" +
                            s.take(5).joinToString(", ") { x -> x.path.substringAfterLast('/') }
                    }
                }
                log(msg)
                android.util.Log.i("NasPhotoProbe", msg)
            }
        }
        // ---- 路径 dump（验证大小写归一化真的生效）----
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es pathDump 1
        //
        // 直接把 App **实际读到**的路径打出来。光看数据库是看不出来的：
        // `delScan` 的判据里还有 `File.exists()` 兜底，而 FUSE 大小写不敏感 ——
        // 归一化即使整段失效，它照样不会报错（我就被这一点误导过一次）。
        intent?.getStringExtra("pathDump")?.let {
            lifecycleScope.launch {
                val msg = withContext(Dispatchers.IO) {
                    val lib = MediaRepo(this@MainActivity).libraryPaths()
                    val dcim = lib?.visible?.filter { p -> p.contains("dcim", true) }?.sorted()
                        ?: emptyList()
                    buildString {
                        append("pathDump → 可见集 ${lib?.visible?.size ?: -1} 条，含 dcim 的 ${dcim.size} 条\n")
                        append("    其中仍是小写 dcim/ 的 ${dcim.count { p -> p.contains("/dcim/") }} 条（应为 0）\n")
                        dcim.take(8).forEach { p -> append("    $p\n") }
                        append("    （全大写 DCIM/ = 归一化生效；出现小写 dcim/ = 没生效）")
                    }
                }
                log(msg)
                android.util.Log.i("NasPhotoProbe", msg)
            }
        }
        // ---- 孤儿文件回灌（NAS 有 / DB 无 的历史遗留文件）----
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity \
        //     --es adopt /sdcard/Download/np_orphans.json
        // 清单由工作区脚本 np_adopt.py 生成（NAS 侧算 sha256 后 push 到手机）。
        intent?.getStringExtra("adopt")?.let { p ->
            lifecycleScope.launch {
                val msg = withContext(Dispatchers.IO) { runAdopt(p) }
                log(msg)
                android.util.Log.i("NasPhotoProbe", msg)
            }
        }

        // ---- 确认卡片外观探针（只弹卡片，不碰任何文件）----
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es askUi remote
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es askUi local
        //
        // 用来**肉眼**核对卡片宽度/换行/两颗按钮是否都在屏幕内。
        // 会同时把三路宽度来源打进日志（NasPhotoProbe），溢出时能立刻看出是哪一路算错了。
        intent?.getStringExtra("askUi")?.let { kind -> askUiProbe(kind) }

        prefs = Prefs(this)
        engine = OffloadEngine(this)

        tvStats = findViewById(R.id.tvStats)
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)
        cbAutoScroll = findViewById(R.id.cbAutoScroll)

        tvStats.setOnClickListener { showStatsDetail() }

        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            SyncLog.clear()
            logLines.clear()
            tvLog.text = ""
        }

        findViewById<Button>(R.id.btnScan).setOnClickListener { doScan() }
        findViewById<Button>(R.id.btnOffload).setOnClickListener { doOffload() }
        findViewById<Button>(R.id.btnOpenRestore).setOnClickListener {
            startActivity(
                android.content.Intent(this, OffloadedActivity::class.java)
            )
        }
        findViewById<Button>(R.id.btnOpenRestoreFromNas).setOnClickListener {
            startActivity(
                android.content.Intent(this, RestoreActivity::class.java)
            )
        }
        findViewById<Button>(R.id.btnTrash).setOnClickListener {
            startActivity(
                android.content.Intent(this, TrashActivity::class.java)
            )
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettings() }

        // ---- 归档根迁移探针（一次性运维用，赶在后台同步启动之前跑完）----
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity \
        //     --es migrateRoot "我的文档/NasPhoto归档|OtherSpace/NasPhoto归档"
        // 同时改写【数据库 remotePath 前缀】与【Prefs.remoteRoot】。
        // NAS 上的实体目录（记得连 `thumb/data/<旧根>` 缩略图缓存一起）要用 mv 先搬好。
        //
        // ⚠️ 两者必须一次做完，缺一必出鬼：
        //    · 只改库不改 remoteRoot → 新照片又按旧前缀上传，归档重新分裂成两个目录
        //    · 只改配置不改库     → 「恢复」按旧路径去 NAS 找，全部 404
        //
        // ⚠️ 位置很关键：必须在 `tvLog` 赋值**之后**（否则 `log()` 触碰未初始化的
        //    lateinit 字段直接抛 UninitializedPropertyAccessException 崩溃 —— 踩过），
        //    又必须在 SyncScheduler/SyncService 启动**之前**（否则同步可能先按旧前缀上传）。
        intent?.getStringExtra("migrateRoot")?.let { spec ->
            val cut = spec.indexOf('|')
            if (cut <= 0) {
                log("migrateRoot 参数格式应为「旧前缀|新前缀」")
            } else {
                val oldRoot = spec.substring(0, cut).trim().trim('/')
                val newRoot = spec.substring(cut + 1).trim().trim('/')
                val msg = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    engine.migrateRemoteRoot(oldRoot, newRoot)
                }
                val before = prefs.remoteRoot
                prefs.remoteRoot = newRoot
                val full = msg + "\nremoteRoot：$before  →  ${prefs.remoteRoot}"
                log(full)
                android.util.Log.i("NasPhotoProbe", full)
            }
        }

        ensurePermissions()
        // ---- 重新定位探针（归位 / 大小写归一；一次性运维）----
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity \
        //     --es relink /sdcard/Download/np_relink.json
        //
        // 清单由工作区脚本生成。App 一手做完"NAS 移动 + DB 改写"，逐条原子 ——
        // 分成两步做的话，中间那一小段时间里 DB 指的路径和母本实际所在的位置对不上，
        // 「恢复」会 404、删除同步还可能判定母本没了。
        //
        // ⚠️ 必须**跑完再放开后台同步**：同步若先跑，看到的正是"新 NAS 布局 + 旧 DB"
        //    这个最容易误判的组合 —— 既可能按旧路径判定"NAS 上没了"（转头把本地删了），
        //    也可能按旧路径再传一份上去。故这里用门控，而不是简单地在后面排队。
        // ---- 回收站探针（打印列表 / 直接打开页面）----
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es trash list
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es trash open
        //
        // 走的是与界面**完全相同**的 TrashStore 路径，所以这里打印出什么、页面就显示什么。
        // 同样不启动同步栈：读回收站与同步互不干扰，摘掉同步能让日志干净、结论好判读。
        val trashProbe = intent?.getStringExtra("trash")
        val relinkSpec = intent?.getStringExtra("relink")
        // ---- 熔断测试探针（只跑真实的「扫描 + 处理」，**不启动同步栈**）----
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es remRun 1
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es delRun 1
        //
        // 为什么非要「不启动同步栈」：要构造"NAS 上少了一批文件"的现场，只能把
        // NAS 侧的目录**临时改名**。而同步一旦跑起来，看到 `size(remote)` 返回 -1
        // 就会以为"NAS 上那份没了" → **把文件重新上传一份**，直接把现场污染掉。
        //
        // 上一轮的做法是临时改 `onlyBucket` 把同步冻住 —— 但改配置这件事本身
        // 就有风险（踩过一次：读配置失败却照样写回，差点把用户配置清空）。
        // 现在改成**整条同步栈都不启动**：不动任何配置，风险归零。
        //
        // 走的是 [RemoteDeletionWatcher.scan] + [handle] 的**真实路径**，
        // 与 [startBackgroundSync] 里那两行完全相同 —— 只是把同步摘掉。
        val remRun = intent?.getStringExtra("remRun")
        val delRun = intent?.getStringExtra("delRun")
        val delFake = intent?.getStringExtra("delFake")?.toIntOrNull()
        // ---- 门闸诊断探针（**只读**，不启动同步栈、不碰任何文件）----
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es gate 1
        //
        // 排查"日志一直说已有同步在跑、却看不出谁在跑"：每 3 秒采一次，
        // 直接把**占门者的调用栈**打出来。空闲则报"空闲"。
        val gateProbe = intent?.getStringExtra("gate")
        // ---- 候选分类审计探针（**只读**，不启动同步栈、不碰任何文件）----
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es cand 1
        val candProbe = intent?.getStringExtra("cand")
        // ---- 「恢复后宽限期」探针（**写 DB，但只动 restoredAt 一个字段**）----
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es ageRestored 3
        //     3 = 把 RESTORED 记录的恢复时刻往前推 3 天（伪造"已经过了 3 天"）
        //     0 = 还原成"刚刚恢复"
        //   配合 --es cand 1 使用：先 ageRestored 再 cand，看它有没有回到候选。
        //   为什么必须有这个探针：宽限期最短 3 天，真等 3 天才能验证等于无法验证。
        val ageRestored = intent?.getStringExtra("ageRestored")
        // ---- 「恢复后是否允许再次降级」策略探针（写 SharedPreferences，可逆）----
        //   --es restorePolicy off   → 关闭（恢复后永不降级）
        //   --es restorePolicy 3     → 打开并把宽限期设为 3 天（3/7/15/30）
        //   界面上的复选框+单选就是这个值的可视化；探针只是让"开关本身"可被自动验证，
        //   否则四条组合路径里有两条（关闭、开了但没到期）只能靠推理。
        val restorePolicy = intent?.getStringExtra("restorePolicy")
        // ---- 「立即跑一轮」探针 ----
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es kick 1
        // 只是登记一次"尽快执行"，**不等结果**（一轮可能几分钟）。
        // 复核方式：跑完隔一两分钟再 `--es cand 1`，看「库内 N 条」有没有涨。
        // ⚠️ 它同样受「充电 + WiFi」门槛约束，所以顺带把 DeviceState 报出来 ——
        //    不然"没动静"会被误读成排序没生效。
        val kick = intent?.getStringExtra("kick")
        // ---- 设置对话框探针：只为截图核对 UI，**不写任何值** ----
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es settings 1
        val settingsProbe = intent?.getStringExtra("settings")
        // ---- 通道解析探针（**不启动同步栈、不改任何配置项**）----
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es chan 1
        // 把「网络环境 → 策略门控 → 每条候选通道的探测结果 → 最终选中谁 → 家庭前缀」
        // 整条链路一次性打出来。没有它就只能靠肉眼盯 UI，
        // 而通道恰恰是"看不见摸不着"的东西 —— 填了地址通不通全靠猜。
        val chanProbe = intent?.getStringExtra("chan")
        // ---- IPv6 通道预演探针（**不读也不改任何配置**）----
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es v6try 1
        // 用「已经学到的家庭前缀 + 两个候选后缀」临时拼出 NAS 的 IPv6 地址并探测。
        // 用途：外网防火墙还没开时，也能在**局域网内**验证前缀对不对、地址拼得对不对、
        // v6 的方括号与 Host 头写没写对 —— 把不可验证的部分压到只剩"包能不能进来"。
        val v6Try = intent?.getStringExtra("v6try")
        // ---- 路径对账探针（**会给老记录补 mediaId，并认领"换了路径"的记录**）----
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es pathFix 1
        //
        // 起点是 #86：「手机改名/移动照片 → NAS 上多出一份同内容副本（假母本）」，
        // 以及它更危险的镜像面 —— 旧路径被判成"手机侧删除"，可能把真母本搬进回收站。
        // 根因是**记录的身份挂在路径上**（主键 = path），而路径会变。
        // 现在改用 MediaStore 的 `_id` 当身份，这个探针就是它的**唯一可判读入口**：
        //   ① 跑一轮真实对账（补身份 + 认领路径变化）
        //   ② 打印"同一张照片在库里有多条记录"的**存量重复组** —— 那批是旧版
        //      canonical 只认 `dcim` 时期留下的，必须人工核对后清理，程序不擅自决定。
        val pathFixProbe = intent?.getStringExtra("pathFix")
        // ---- 存量路径规范化探针（**会改 DB 的 path/remotePath、会删重复记录**）----
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es canonFix 1
        //
        // 配合 `pathFix` 用：pathFix 只补身份、不动路径写法；那些**旧版 canonical
        // 只认 dcim 时期**用小写记下的记录，得由这个探针按现在的规则改回来。
        // 前提是 NAS 侧已经先做过目录合并 —— 所以它会**逐条确认远端新路径存在**
        // 才改 remotePath，不存在就只改本地 path（宁可留着旧远端路径也不能改坏）。
        val canonFixProbe = intent?.getStringExtra("canonFix")
        // ---- 远端路径规范化 + 母本存在性体检（**会改 DB 的 remotePath**）----
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es remoteFix 1
        //
        // 为什么单独要它：`PathReconciler` 认领"换了路径"时**刻意不动 remotePath**
        // （设计如此 —— 大多数情况母本确实还在老地方），而 `canonFix` 只处理 path
        // 尚未规范的记录 ⇒ 一旦"本地路径变了、母本也跟着搬了"，就留下一条指向
        // **不存在路径**的 remotePath。症状：库看着一切正常，点「恢复」直接 404。
        // 顺带做一次**母本存在性体检**（逐条 HEAD），把"库里记着、NAS 上没有"的列出来。
        val remoteFixProbe = intent?.getStringExtra("remoteFix")
        // ---- 回收站三条不可逆路径的**隔离测试**探针（#90）----
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es trashTest empty
        //
        // ⚠️ 只在 `<归档根>/_回收站_TEST` 上操作，**绝不碰真实回收站**
        //    （那里躺着用户的文件，不能拿来试"彻底删除"）。
        //    测试数据由生产侧（SSH）造，这个探针只负责**走真实代码路径**去删，
        //    删完再由外部独立核对 —— 三条链路都分开，谁也别给自己的作业打分。
        //
        //   action：`list` 列现状 · `empty` 测 emptyAll · `purge` 测 purgeExpired ·
        //           `del:<相对路径>` 测 deletePermanently · `guard` 测护栏（负向用例）
        val trashTestProbe = intent?.getStringExtra("trashTest")
        // ---- 写权限行为探针（#103）----
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity \
        //       --es permCheck /storage/emulated/0/np_permtest.txt
        //
        // 双向验证 `MediaRepo.deleteLocal` 在缺「所有文件访问」时的行为：
        //   缺权限 → **必须 false 且文件原封不动**
        //   有权限 → true 且文件消失（对照组）
        // **两个方向都要跑**才算数 —— 只跑一边就是"恒真判据"的温床。
        val permCheckProbe = intent?.getStringExtra("permCheck")
        // ---- 整库回灌探针（#87）----
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es restoreProbe plan
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es restoreProbe run
        //
        // `plan` **只读** —— 扫 NAS 归档树算出回灌规划，不下载、不写库、不动任何文件。
        //   正因如此它可以对着**真实归档根**跑，且顺带回答了"库和 NAS 到底差多少"。
        // `run`  会真的往手机写文件并落库 ⇒ 只应在隔离用例上跑。
        val restoreProbe = intent?.getStringExtra("restoreProbe")
        // 库记录清理探针（**只删记录，不碰任何文件**）
        //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es dbPurge "np_ui.png;rec.mp4"
        // 用在"NAS 上的文件已经被手工删掉、但库里还留着记录"的收尾场景。
        // ⚠️ 顺序铁律见 probeDbPurge 的注释：内容先消失、记录后消失。
        val dbPurgeProbe = intent?.getStringExtra("dbPurge")
        if (gateProbe != null) {
            // 必须**同时把同步栈启动起来**：那个"已有同步在跑"是启动竞争产生的，
            // 不启动同步栈就永远采不到占门者。
            startBackgroundSync()
            lifecycleScope.launch {
                repeat(8) { i ->
                    val s = "gate[$i] " + (cn.dsr213.nasphoto.service.SyncGate.describe() ?: "空闲")
                    log(s)
                    android.util.Log.i("NasPhotoProbe", s)
                    delay(2_000)
                }
            }
        } else if (v6Try != null) {
            lifecycleScope.launch {
                val msg = probeV6Try()
                log(msg)
                android.util.Log.i("NasPhotoProbe", msg)
            }
        } else if (pathFixProbe != null) {
            lifecycleScope.launch {
                val msg = withContext(Dispatchers.IO) { probePathFix() }
                log(msg)
                android.util.Log.i("NasPhotoProbe", msg)
            }
        } else if (canonFixProbe != null) {
            lifecycleScope.launch {
                val msg = probeCanonFix()
                log(msg)
                android.util.Log.i("NasPhotoProbe", msg)
            }
        } else if (remoteFixProbe != null) {
            lifecycleScope.launch {
                val msg = probeRemoteFix()
                log(msg)
                android.util.Log.i("NasPhotoProbe", msg)
            }
        } else if (trashTestProbe != null) {
            lifecycleScope.launch {
                val msg = probeTrashTest(trashTestProbe)
                log(msg)
                android.util.Log.i("NasPhotoProbe", msg)
            }
        } else if (permCheckProbe != null) {
            lifecycleScope.launch {
                val msg = probePermCheck(permCheckProbe)
                log(msg)
                android.util.Log.i("NasPhotoProbe", msg)
            }
        } else if (restoreProbe != null) {
            lifecycleScope.launch {
                val msg = if (restoreProbe.startsWith("rollback:")) {
                    probeRestoreRollback(restoreProbe.removePrefix("rollback:"))
                } else {
                    probeRestoreProbe(restoreProbe)
                }
                log(msg)
                android.util.Log.i("NasPhotoProbe", msg)
            }
        } else if (dbPurgeProbe != null) {
            lifecycleScope.launch {
                val msg = probeDbPurge(dbPurgeProbe)
                log(msg)
                android.util.Log.i("NasPhotoProbe", msg)
            }
        } else if (chanProbe != null) {
            lifecycleScope.launch {
                val msg = withContext(Dispatchers.IO) { probeChannelsForAdb() }
                log(msg)
                android.util.Log.i("NasPhotoProbe", msg)
            }
        } else if (candProbe != null) {
            lifecycleScope.launch {
                val msg = withContext(Dispatchers.IO) { probeCandidates() }
                log(msg)
                android.util.Log.i("NasPhotoProbe", msg)
            }
        } else if (ageRestored != null) {
            // 「恢复后宽限期」探针（**会写 DB，但只改 restoredAt 一个字段，可逆**）
            lifecycleScope.launch {
                val msg = withContext(Dispatchers.IO) {
                    probeAgeRestored(ageRestored.toIntOrNull() ?: 0)
                }
                log(msg)
                android.util.Log.i("NasPhotoProbe", msg)
            }
        } else if (restorePolicy != null) {
            val s = restorePolicy.trim()
            val msg = if (s.equals("off", true)) {
                prefs.restoreRedowngrade = false
                "restorePolicy → 已关闭 · 「${prefs.restorePolicyText()}」"
            } else {
                val d = s.toIntOrNull() ?: 7
                prefs.restoreRedowngrade = true
                prefs.restoreRedowngradeDays = d
                "restorePolicy → 已打开 $d 天 · 「${prefs.restorePolicyText()}」"
            }
            log(msg)
            android.util.Log.i("NasPhotoProbe", msg)
        } else if (settingsProbe != null) {
            // 等窗口挂上再弹，否则 AlertDialog 拿不到可用的 window token
            window.decorView.post { showSettings() }
            android.util.Log.i("NasPhotoProbe", "settings → 已打开设置对话框（只读，不保存）")
        } else if (kick != null) {
            cn.dsr213.nasphoto.service.SyncScheduler.kickOnce(this)
            val gate = cn.dsr213.nasphoto.device.DeviceState.describe(this)
            val msg = "kick → 已登记一次尽快同步（后台跑，不等结果）\n" +
                "  当前门槛：$gate\n" +
                "  若门槛未开，这一轮会被整体跳过 —— 复核请隔一两分钟再跑 --es cand 1 看「库内 N 条」"
            log(msg)
            android.util.Log.i("NasPhotoProbe", msg)
        } else if (remRun != null || delRun != null || delFake != null) {
            lifecycleScope.launch {
                val msg = withContext(Dispatchers.IO) {
                    when {
                        remRun != null -> runRemoteDeletionProbe()
                        delRun != null -> runLocalDeletionProbe()
                        else -> runFakeLocalDeletionProbe(delFake ?: 0)
                    }
                }
                log(msg)
                android.util.Log.i("NasPhotoProbe", msg)
            }
        } else if (trashProbe != null) {
            if (trashProbe == "open") {
                startActivity(android.content.Intent(this, TrashActivity::class.java))
            } else {
                lifecycleScope.launch {
                    val msg = withContext(Dispatchers.IO) { probeTrash() }
                    log(msg)
                    android.util.Log.i("NasPhotoProbe", msg)
                }
            }
        } else if (relinkSpec != null) {
            lifecycleScope.launch {
                val msg = withContext(Dispatchers.IO) { runRelink(relinkSpec) }
                log(msg)
                android.util.Log.i("NasPhotoProbe", msg)
                startBackgroundSync()
            }
        } else {
            startBackgroundSync()
        }
    }

    /**
     * 回收站列表探针：把界面用的**同一份数据**打成文本，方便自动化核对。
     *
     * 判读要点：条数、删除日期、原位路径，以及"本地还有没有这份"（`本地无` =
     * 还原回去之后手机相册里也不会出现，它只存在于 NAS）。
     */
    /**
     * 候选分类审计：回答"**某条记录下一轮会不会被动**"。
     *
     * 存在的理由：`pickFrom` 的筛选规则不体现在任何**可见状态**里 ——
     * 一条记录只要 state 不在 `OFFLOADED` / `BACKED_UP` / `RESTORED` 任一集合中，
     * 就会静默地重新成为降级候选，而选它、传它、压它都在下一轮才发生，
     * 日志里只有一句「候选 N 个」，看不出里面有谁。
     *
     * ⭐ 判读要点（单一判据）：**`RESTORED` 必须一条都不在候选里**。
     * 那是用户显式要回本地的原图，出现在候选里 = 下一轮会被压回去。
     *
     * 必须用 [OffloadEngine.pickCandidatesUncapped]：带 `maxBatch` 上限时，
     * "排在第 51 位"和"不合格"长得一模一样，正好会把问题藏住。
     */
    private suspend fun probeCandidates(): String = withContext(Dispatchers.IO) {
        val db = cn.dsr213.nasphoto.data.AppDb.get(this@MainActivity)
        val all = db.photos().allRecords()
        val cands = engine.pickCandidatesUncapped()
        val inCand = cands.map { it.item.path }.toHashSet()

        val sb = StringBuilder(
            "cand → 候选 ${cands.size} 个（不设上限）· 库内 ${all.size} 条 · maxBatch=${prefs.maxBatch}"
        )
        sb.append("\n  状态分布：").append(
            all.groupingBy { it.state }.eachCount().entries
                .sortedBy { it.key }
                .joinToString(" / ") { "${it.key} ${it.value}" }
        )
        sb.append("\n  判定分布：").append(
            cands.groupingBy { it.decision.action.name }.eachCount().entries
                .sortedBy { it.key }
                .joinToString(" / ") { "${it.key} ${it.value}" }
        )

        val restored = all.filter { it.state == cn.dsr213.nasphoto.data.STATE_RESTORED }
        val grace = prefs.restoreGraceMs
        val nowMs = System.currentTimeMillis()
        sb.append("\n  RESTORED ${restored.size} 条 · 策略「${prefs.restorePolicyText()}」")
        sb.append(
            if (grace < 0L) " ← 必须全部「不在候选」："
            else " ← 宽限期内应「不在候选」、到期后应「在候选」："
        )
        if (restored.isEmpty()) sb.append("\n    （无）")
        for (r in restored) {
            val inCandNow = r.path in inCand
            // 期望值由策略算出来，探针自己判对错 —— 不要让人肉去比
            val expired = grace >= 0L && r.restoredAt > 0L && nowMs - r.restoredAt >= grace
            val ok = inCandNow == expired
            val ageDays = if (r.restoredAt > 0L) {
                String.format("%.2f", (nowMs - r.restoredAt) / 86_400_000.0)
            } else "-"
            sb.append("\n    ").append(if (ok) "✅" else "⛔")
                .append(if (inCandNow) "在候选" else "不在候选")
                .append("（期望").append(if (expired) "在候选" else "不在候选").append("）")
                .append("  已恢复 ").append(ageDays).append(" 天")
                .append("  ").append(r.path.substringAfterLast('/'))
                .append("  local=").append(r.localSize)
                .append(" orig=").append(r.originalSize)
        }

        // BACKED_UP 出现在候选里是**正常的**（设计上允许被重新评估），只报数量供对照 ——
        // 有它做对照，"RESTORED 为 0 在候选"才说明是规则生效、而不是候选集整体为空。
        val bak = all.filter { it.state == cn.dsr213.nasphoto.data.STATE_BACKED_UP }
        sb.append("\n  BACKED_UP ${bak.size} 条，其中在候选里 ")
            .append(bak.count { it.path in inCand })
            .append(" 条（正常：它们允许被重新评估）")

        // ---- 后台批次截断模拟：批次饥饿（Task #89）的判据 ----
        // 批次是"取前 N 个"。只要排前面的永远是同一批老面孔，后面的新文件就永远进不来。
        // 判据只有一个：**这前 30 个里有几个是从没进过库的**。
        //   修好前 = 0（新文件全被挡在后面，且每轮 30 个文件名完全不变）
        //   修好后 → 30（新文件被稳定排到最前）
        val knownPaths = all.map { it.path }.toHashSet()
        val unknownAll = cands.count { it.item.path !in knownPaths }
        val batch = engine.pickCandidates(
            limit = cn.dsr213.nasphoto.service.OffloadWorker.BACKGROUND_BATCH_LIMIT
        )
        sb.append("\n  后台批次（前 ")
            .append(cn.dsr213.nasphoto.service.OffloadWorker.BACKGROUND_BATCH_LIMIT)
            .append(" 个，与工作中实际一致）：从没进过库的 ")
            .append(batch.count { it.item.path !in knownPaths })
            .append(" 个 ← 这个数应当等于批次大小；为 0 就是在饿死新文件")
        sb.append("\n  候选整体：从没进过库的 ").append(unknownAll)
            .append(" / 共 ").append(cands.size).append(" 个")
        sb.append("\n  批次前 5 个：")
        batch.take(5).forEach {
            sb.append("\n    ")
                .append(if (it.item.path in knownPaths) "[旧]" else "[新]")
                .append("  ").append(it.item.path.substringAfterLast('/'))
                .append("  ").append(it.decision.action.name)
                .append("  ").append(it.item.size).append("B")
        }
        sb.toString()
    }

    /**
     * 探针：伪造「已恢复原图」的时间流逝，用来验证宽限期到期真的会放回候选。
     *
     * 为什么必须有：最短的宽限期是 3 天 —— **真等 3 天才能验证的机制等于没法验证**，
     * 而这条路径又恰好是"会动用户原图"的高风险路径，不能靠推理。
     *
     * 只写 `restoredAt` 一个字段，`--es ageRestored 0` 即可还原，**不碰任何文件**。
     */
    /**
     * **存量路径规范化探针**（`--es canonFix 1`）—— #86 遗留数据的收尾。
     *
     * 旧版 `canonical` 只认 `dcim`，于是 `pictures/` 这类小写写法在库里留下了
     * **同一张照片的第二条记录**（NAS 上则多出第二个目录）。这条探针按现在的规则改回来。
     *
     * 规则两条，都刻意保守：
     * - 规范化后的路径**已有记录** ⇒ 本条是重复 ⇒ 删除。同一条照片在大小写不敏感的分区上
     *   必然是同一个物理文件，留着两条只会让统计虚高、让状态自相矛盾
     *   （实测那两条：一条说"本地已是小图"、另一条说"本地还是原图"，而实际只有一个小图）
     * - 否则 ⇒ 改 `path`；`remotePath` **只在 NAS 上新路径确实存在时**才跟着改 ——
     *   改错了"恢复原图"就找不到文件，那比不改严重得多
     */
    private suspend fun probeCanonFix(): String = withContext(Dispatchers.IO) {
        val dao = cn.dsr213.nasphoto.data.AppDb.get(this@MainActivity).photos()
        val sb = StringBuilder()
        val all = dao.allRecords()
        val byPath = runCatching {
            MediaRepo(this@MainActivity).all().associateBy { it.path }
        }.getOrDefault(emptyMap())

        val targets = all.filter { MediaRepo.canonicalPath(it.path) != it.path }
        sb.append("canonFix 需规范化 ${targets.size} 条（共 ${all.size} 条）\n")
        if (targets.isEmpty()) return@withContext (sb.toString() + "canonFix 无需处理").trim()

        val client = try {
            OffloadEngine(this@MainActivity).newClientForDeletion().also { it.connect() }
        } catch (t: Throwable) {
            return@withContext (sb.toString() + "canonFix 连不上 NAS —— 为不改坏 remotePath，本轮不动手").trim()
        }
        try {
            var dup = 0
            var fixed = 0
            var keptRemote = 0
            for (r in targets) {
                val c = MediaRepo.canonicalPath(r.path)
                if (dao.byPath(c) != null) {
                    dao.deleteByPath(r.path)
                    dup++
                    sb.append("  删重复[${r.state}] …${r.path.takeLast(38)}\n")
                    continue
                }
                // ⚠️ 这里**必须**用 canonicalRemotePath：
                // 换成 canonicalPath 会因"remotePath 不含本地存储根"而**原样返回**，
                // `newRemote == r.remotePath` 恒真 ⇒ remoteOk 恒为 true ⇒
                // 日志打出"规范化 path+远端"却一个字都没改（这个假判据我踩过，
                // 它让一条指向不存在路径的 remotePath 活了下来）。
                val newRemote = MediaRepo.canonicalRemotePath(
                    r.remotePath, Prefs(this@MainActivity).remoteRoot
                )
                val remoteChanged = newRemote != r.remotePath
                val remoteOk = !remoteChanged ||
                    runCatching { client.exists(newRemote) }.getOrDefault(false)
                if (remoteChanged && !remoteOk) keptRemote++
                dao.deleteByPath(r.path)
                dao.upsert(
                    r.copy(
                        path = c,
                        remotePath = if (remoteOk) newRemote else r.remotePath,
                        mediaId = byPath[c]?.id ?: r.mediaId
                    )
                )
                fixed++
                val remoteNote = when {
                    !remoteChanged -> "（远端无需改）"
                    remoteOk -> " path+远端"
                    else -> " 仅 path（远端未移动，保留旧 remotePath）"
                }
                sb.append("  规范化$remoteNote …${c.takeLast(38)}\n")
            }
            sb.append("canonFix 删重复 $dup · 规范化 $fixed · 远端保留 $keptRemote")
        } finally {
            runCatching { client.close() }
        }
        sb.toString()
    }

    /**
     * **远端路径规范化 + 母本存在性体检**（`--es remoteFix 1`）。
     *
     * 补 `canonFix` 的缺口。两个函数管的是**两件不同的事**：
     * - `canonFix` 的入口条件是 `canonicalPath(path) != path`，只管**本地路径写法**；
     * - `PathReconciler` 认领"换了路径"时**刻意不动 `remotePath`**（设计如此 ——
     *   绝大多数情况下母本确实还在老地方）。
     *
     * ⇒ "本地路径变了 **且** 母本也跟着搬了"这一组合**两个函数都不覆盖**，
     *   于是留下一条**指向不存在路径的 `remotePath`**：库里看不出任何异常，
     *   点「恢复」直接 404。
     *
     * 两道闸，任一不满足就**只报警不动手**：
     * ① 旧 remotePath 确实不存在；② 规范化后的新路径确实存在。
     * 宁可留下一条**看得见的警报**，也不能把 DB 指到一个猜出来的位置。
     *
     * 顺带逐条 HEAD 一遍，把"库里记着、NAS 上却没有"的母本缺失一并列出来。
     */
    private suspend fun probeRemoteFix(): String = withContext(Dispatchers.IO) {
        val dao = cn.dsr213.nasphoto.data.AppDb.get(this@MainActivity).photos()
        val sb = StringBuilder()
        val root = Prefs(this@MainActivity).remoteRoot
        val all = dao.allRecords()
        sb.append("remoteFix 归档根 '$root' · 记录 ${all.size} 条\n")

        val client = try {
            OffloadEngine(this@MainActivity).newClientForDeletion().also { it.connect() }
        } catch (t: Throwable) {
            return@withContext "remoteFix 连不上 NAS —— 为不改坏 remotePath，本轮不动手"
        }
        try {
            var fixed = 0
            var stale = 0
            var missing = 0
            val missingList = mutableListOf<String>()
            for (r in all) {
                val c = MediaRepo.canonicalRemotePath(r.remotePath, root)
                if (c != r.remotePath) {
                    val oldOk = client.exists(r.remotePath)
                    val newOk = client.exists(c)
                    if (!oldOk && newOk) {
                        // 只动 remotePath、path 不变 ⇒ 主键未变，REPLACE 即可，无需先 delete
                        dao.upsert(r.copy(remotePath = c))
                        fixed++
                        sb.append("  修远端 → ${c.split('/').takeLast(3).joinToString("/")}\n")
                        continue
                    }
                    stale++
                    val tail = c.split('/').takeLast(3).joinToString("/")
                    sb.append(
                        "  ⚠ 待人工核对（旧${if (oldOk) "在" else "缺"}·" +
                            "新${if (newOk) "在" else "缺"}）…$tail\n"
                    )
                }
                if (!client.exists(r.remotePath)) {
                    missing++
                    if (missingList.size < 10) missingList.add(r.remotePath)
                }
            }
            sb.append("remoteFix 修远端 $fixed · 待人工核对 $stale · 母本缺失 $missing\n")
            missingList.forEach { sb.append("    缺：$it\n") }
            sb.append("remoteFix 完成")
        } finally {
            runCatching { client.close() }
        }
        sb.toString()
    }

    /**
     * **路径对账探针**（`--es pathFix 1`）—— #86 的唯一可判读入口。
     *
     * 会：① 真实跑一轮对账（补 `mediaId` + 认领"换了路径"的记录）；
     * ② 把**存量重复**打出来 —— 同一张照片在库里有多条记录（旧版 canonical 只认
     * `dcim`、漏了 `pictures` 时期留下的），这批要人工核对后清理，程序不擅自决定保谁。
     *
     * ⚠️ 为什么重复组必须打**远端路径的后三段**而不是文件名：两条记录的文件名完全相同，
     * 差别只在 `Pictures/` 与 `pictures/` 那一段 —— 只打文件名的话两组看起来一模一样。
     */
    private suspend fun probePathFix(): String = withContext(Dispatchers.IO) {
        val dao = cn.dsr213.nasphoto.data.AppDb.get(this@MainActivity).photos()
        val sb = StringBuilder()
        val before = dao.allRecords()
        sb.append("pathFix 记录 ${before.size} 条 · 无身份 ${before.count { it.mediaId <= 0L }} 条\n")

        val r = PathReconciler.reconcile(this@MainActivity)
        sb.append("pathFix 补身份 ${r.filled} · 认领路径变化 ${r.moved}\n")

        val after = dao.allRecords()
        val dup = after.groupBy { it.path.lowercase() }.filter { it.value.size > 1 }
        sb.append("pathFix 大小写重复组 ${dup.size} 组\n")
        for ((_, v) in dup.toList().take(12)) {
            sb.append("  重复组：\n")
            for (rec in v) {
                val f = java.io.File(rec.path)
                val real = if (f.exists()) f.length() else -1L
                val tail = rec.remotePath.split('/').takeLast(3).joinToString("/")
                sb.append(
                    "    ${rec.state} id=${rec.mediaId} 实际${real} 原图${rec.originalSize} → $tail\n"
                )
            }
        }

        val gone = after.count { !java.io.File(it.path).exists() }
        sb.append("pathFix 本地文件缺失 $gone 条（0 = 每条记录都有对应文件）")
        sb.toString()
    }

    /**
     * 回收站三条不可逆路径的**隔离测试**（#90）。
     *
     * 固定在 `_回收站_TEST` 子目录上操作 —— 真实回收站里躺着用户的文件，
     * 不能拿来试「彻底删除」。测试数据由外部（SSH）造，删完再由外部独立核对：
     * **造数据 / 走真实路径删 / 核对** 三段分开，谁也别给自己的作业打分。
     *
     * ⚠️ 输出的是**数字**（删了几个、前后文件数），不是"成功/失败" ——
     *    这次要查的恰恰是"计数可不可信"，把它压成一个布尔就什么都看不出来了。
     */
    private suspend fun probeTrashTest(action: String): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder("=== 回收站隔离测试 ===\n")
        val prefs = cn.dsr213.nasphoto.data.Prefs(this@MainActivity)
        val engine = cn.dsr213.nasphoto.engine.OffloadEngine(this@MainActivity)
        val client = engine.newClientForDeletion()
        try {
            client.connect()
        } catch (t: Throwable) {
            return@withContext "连不上 NAS：${t.message}"
        }
        val testName = cn.dsr213.nasphoto.engine.TrashStore.TRASH_NAME + "_TEST"
        val st = cn.dsr213.nasphoto.engine.TrashStore(client, prefs.remoteRoot, testName)
        try {
            sb.append("隔离目录：").append(st.dir).append("\n\n")
            when {
                action == "list" -> {
                    val files = client.listFilesRecursive(st.dir, emptySet())
                    if (files == null) {
                        sb.append("读取结果：null（读不到）— 注意这与「空目录」是两回事\n")
                    } else {
                        sb.append("文件数：").append(files.size).append('\n')
                        files.sorted().forEach {
                            sb.append("   ").append(it.removePrefix(st.dir + "/")).append('\n')
                        }
                    }
                }

                action == "empty" -> {
                    val before = client.listFilesRecursive(st.dir, emptySet())?.size ?: -1
                    sb.append("删除前文件数：").append(before).append('\n')
                    val r = st.emptyAll()
                    sb.append("emptyAll：deleted=").append(r.deleted)
                        .append(" bad=").append(r.badBatches)
                        .append(" partial=").append(r.partialBatches)
                        .append(" unreadable=").append(r.unreadable)
                        .append(" blocked=").append(r.blockedBatches).append('\n')
                    val after = client.listFilesRecursive(st.dir, emptySet())?.size ?: -1
                    sb.append("删除后文件数：").append(after).append('\n')
                    sb.append(
                        when {
                            // ⚠️ 读不到时 before/after 都是 -1，**不是"0 个文件"**。
                            //    判据必须先把这条分出去，否则会把"正确报错"误判成"计数不符"
                            //    —— 这个坑我第一次跑 T6 时就踩了。
                            r.unreadable ->
                                "✅ 读不到目录 → 未执行删除（正确：不能把「读不到」当成「没东西可删」）"
                            after == 0 && r.deleted == before -> "✅ 计数与实际一致（$before → 0）"
                            else -> "⚠️ 计数与实际不符：报 deleted=${r.deleted}，实测 $before → $after"
                        }
                    )
                }

                action == "purge" -> {
                    val before = client.listFilesRecursive(st.dir, emptySet())?.size ?: -1
                    sb.append("keepDays=").append(st.keepDays())
                        .append("  删除前文件数：").append(before).append('\n')
                    val r = st.purgeExpired()
                    sb.append("purgeExpired：deleted=").append(r.deleted)
                        .append(" bad=").append(r.badBatches)
                        .append(" partial=").append(r.partialBatches)
                        .append(" unreadable=").append(r.unreadable)
                        .append(" blocked=").append(r.blockedBatches).append('\n')
                    val after = client.listFilesRecursive(st.dir, emptySet())?.size ?: -1
                    sb.append("删除后文件数：").append(after)
                }

                action.startsWith("del:") -> {
                    val target = st.dir + "/" + action.removePrefix("del:")
                    sb.append("目标：").append(target).append('\n')
                    sb.append("删除前存在：").append(client.exists(target)).append('\n')
                    val ok = st.deletePermanently(target)
                    sb.append("deletePermanently 返回：").append(ok).append('\n')
                    sb.append("删除后仍存在：").append(client.exists(target))
                }

                action == "guard" -> {
                    // 负向用例：拿一个**回收站之外**的路径调 deletePermanently。
                    // 有护栏 → 立刻返回 false；没护栏 → 会真的发 HTTP DELETE，
                    // 拿到 404，而 404 被 [WebDavClient.delete] 判为"目标已达成" ⇒ 返回 true。
                    // 所以这个用例是**能失败**的，不是恒真的空话。
                    val outside = prefs.remoteRoot + "/__GUARD_NOT_EXIST__.txt"
                    sb.append("目标（回收站之外）：").append(outside).append('\n')
                    val ok = st.deletePermanently(outside)
                    sb.append("返回：").append(ok).append('\n')
                    sb.append(if (!ok) "✅ 被护栏拒绝" else "❌ 护栏未生效（返回了 true）")
                }

                else -> sb.append("未知 action：").append(action)
            }
        } finally {
            runCatching { client.close() }
        }
        sb.toString()
    }

    /**
     * 「所有文件访问」权限的行为探针（#103）。
     *
     * 直接调 [MediaRepo.deleteLocal] —— 它是 All 本地删除的唯一入口，
     * 权限闸就装在那里。要验的就是**缺权限时它是否拒绝、是否真的没动文件**。
     *
     * 参数是一个**可以随便删的**手机侧绝对路径（探针自己造的那种，
     * 别拿真实照片去跑）。
     */
    private suspend fun probePermCheck(path: String): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder("=== 写权限探针 ===\n")
        val granted = cn.dsr213.nasphoto.engine.StoragePermission
            .canWritePublicStorage(this@MainActivity)
        sb.append("canWritePublicStorage = ").append(granted).append('\n')
        val f = java.io.File(path)
        sb.append("测试文件：").append(path).append('\n')
        sb.append("删除前存在：").append(f.exists()).append('\n')
        val ok = cn.dsr213.nasphoto.media.MediaRepo(this@MainActivity).deleteLocal(path)
        sb.append("deleteLocal 返回：").append(ok).append('\n')
        val still = f.exists()
        sb.append("删除后仍存在：").append(still).append('\n')
        sb.append(
            when {
                !granted && !ok && still -> "✅ 缺权限：拒绝删除，文件原封不动（不是静默失败）"
                granted && ok && !still -> "✅ 有权限：正常删除（对照组通过）"
                else -> "⚠️ 行为不符预期 —— 权限=$granted 返回=$ok 仍在=$still"
            }
        )
        sb.toString()
    }

    /**
     * 整库回灌探针（#87）。
     *
     * - `plan` —— **只读**：扫 NAS 归档树算出规划，不下载、不写库、不动任何文件。
     *   正因如此它可以对着**真实归档根**跑，还顺带回答了"库和 NAS 到底差多少"。
     * - `run`  —— 按规划真的回灌（会写手机文件 + 落库）⇒ 只应在隔离用例上跑。
     *
     * ⚠️ 明细要**卡住行数**：logcat 单条消息有长度上限，超了会被静默截断，
     * 而"被截断"看起来与"就这么多"一模一样 —— 这正是最容易自欺的地方。
     */
    private suspend fun probeRestoreProbe(spec: String): String = withContext(Dispatchers.IO) {
        // 形如 `plan` · `run` · `run:free=1000`（假想可用空间）· `run:max=1`（单轮上限）
        // 后两个是**可注入的熔断测试点**：真机可用空间以百 GB 计，
        // "装不下"和"触到上限"这两个分支不注入就永远跑不到 —— 没跑过的分支等于没有。
        // 挑选回灌的参数：`days=30`（近 30 天）· `from`/`to`（毫秒时间戳）· `dir=DCIM/Camera` · `kw=IMG2024`
        val seg = spec.split(':', limit = 2)
        val mode = seg[0].trim()
        var fakeFree: Long? = null
        var fakeMax: Int? = null
        var fFrom: Long? = null
        var fTo: Long? = null
        var fDir: String? = null
        var fKw: String? = null
        if (seg.size > 1) {
            for (kv in seg[1].split(',')) {
                val k = kv.substringBefore('=').trim()
                val vs = kv.substringAfter('=', "").trim()
                when (k) {
                    "free" -> vs.toLongOrNull()?.let { fakeFree = it }
                    "max" -> vs.toIntOrNull()?.let { fakeMax = it }
                    "days" -> vs.toLongOrNull()?.let { fFrom = System.currentTimeMillis() - it * 86_400_000L }
                    "from" -> vs.toLongOrNull()?.let { fFrom = it }
                    "to" -> vs.toLongOrNull()?.let { fTo = it }
                    "dir" -> fDir = vs
                    "kw" -> fKw = vs
                }
            }
        }
        val sb = StringBuilder("=== 回灌探针（$spec）===\n")
        val cap = 40
        var lines = 0
        fun emit(m: String) {
            when {
                lines < cap -> sb.append("   ").append(m).append('\n')
                lines == cap -> sb.append("   …（明细过多，后续省略）\n")
            }
            lines++
        }
        val t0 = System.currentTimeMillis()
        val e = cn.dsr213.nasphoto.engine.OffloadEngine(this@MainActivity)
        val full = e.planRestore { emit(it) }
        // 挑选回灌：纯内存筛选，不重扫 NAS（size/mtime 在规划阶段就已白拿到）
        val filter = cn.dsr213.nasphoto.engine.OffloadEngine.RestoreFilter(fFrom, fTo, fDir, fKw)
        val plan = e.pickRestore(full, filter)
        if (!filter.isEmpty) {
            sb.append("筛选：dir=").append(fDir ?: "-")
                .append(" kw=").append(fKw ?: "-")
                .append(" from=").append(fFrom ?: "-")
                .append(" to=").append(fTo ?: "-").append('\n')
            sb.append("      选中 ").append(plan.total).append(" / 整库 ").append(full.total)
                .append("（筛掉 ").append(full.total - plan.total).append("）\n")
        }
        sb.append("规划：ok=").append(plan.ok)
            .append(" 候选=").append(plan.total)
            .append(" 已知=").append(plan.knownBytes).append('B')
            .append(" 未知=").append(plan.unknownSize).append('\n')
        sb.append("      已有记录=").append(plan.alreadyInDb)
            .append(" 本地已有=").append(plan.alreadyLocal)
            .append(" 残留=").append(plan.residue.size)
            .append(" 去重产物=").append(plan.dedupeArtifacts.size)
            .append(" 不合规=").append(plan.inconsistent.size).append('\n')
        val realFree = e.freeSpaceBytes()
        val useFree = fakeFree ?: realFree
        sb.append("      手机可用=").append(realFree).append('B')
        if (fakeFree != null) sb.append("（注入 ").append(fakeFree).append("B）")
        sb.append(" 放行=").append(plan.fitsIn(useFree)).append('\n')
        if (mode == "dirs") {
            sb.append("目录分布（前 20）：\n")
            e.restoreDirs(full).take(20).forEach { (d, n) ->
                sb.append("      ").append(d).append("  ").append(n).append(" 个\n")
            }
        }
        if (mode == "run") {
            val r = e.runRestorePlan(
                plan,
                maxFiles = fakeMax ?: cn.dsr213.nasphoto.engine.MAX_RESTORE_FILES,
                freeBytesOverride = fakeFree
            ) { emit(it) }
            sb.append("执行：恢复=").append(r.done)
                .append(" 重复=").append(r.duplicate)
                .append(" 失败=").append(r.failed)
                .append(" 截断=").append(r.truncated)
                .append(" 字节=").append(r.bytes).append('\n')
        }
        sb.append("耗时 ").append(System.currentTimeMillis() - t0).append(" ms")
        sb.toString()
    }

    /**
     * 「库记录清理」探针：**只删数据库记录，一个文件都不碰**。
     *
     * 参数是**相对归档根的路径**（分号分隔），如 `np_ui.png;rec.mp4`；
     * 按 `remotePath` 结尾匹配。
     *
     * ⚠️⚠️ 顺序铁律：**内容先消失、记录后消失**。
     * 反过来（先删记录、NAS 上的文件还在）会立刻制造出"NAS 有 / DB 无"的孤儿，
     * 孤儿清扫会把它们搬进**用户的回收站** —— 这正是 2026-09-17 那次
     * 把 467 个测试文件倒进回收站的根因。
     *
     * 反向也一样不能乱来：光删记录不删文件，反向删除同步会认为"用户删了照片"。
     * 所以本探针**只应该**在"文件已经确实不在了"之后调用。
     */
    private suspend fun probeDbPurge(spec: String): String = withContext(Dispatchers.IO) {
        val dao = cn.dsr213.nasphoto.data.AppDb.get(this@MainActivity).photos()
        val want = spec.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val rows = dao.allRecords()
        val sb = StringBuilder("=== 库记录清理 ===\n")
        var n = 0
        for (rel in want) {
            val hit = rows.filter { it.remotePath.endsWith("/$rel") }
            if (hit.isEmpty()) {
                sb.append("  未命中 ").append(rel).append('\n')
                continue
            }
            for (r in hit) {
                dao.deleteByPath(r.path)
                n++
                sb.append("  已删记录 ").append(rel).append("   ← path=").append(r.path).append('\n')
            }
        }
        sb.append("共清 ").append(n).append(" 条（**只删库记录，一个文件都没动**）")
        sb.toString()
    }

    /**
     * 回灌隔离测试的**清理通道**。隔离用例必须能自我了断，
     * 否则测试数据会永久留在库里，变成下次对账时的"脏记录"。
     *
     * ⚠️ 顺序不能反：**先让记录消失，再删文件**。
     * 反过来先删文件的话，删除同步会看到「库里有、手机相册里没有」——
     * 那正是它判定的"用户删了照片"，会把 NAS 上的母本搬进回收站，
     * 于是**测试数据污染了回收站**（那是给用户的，不是测试沙盒）。
     *
     * 本探针只处理手机侧；NAS 上的测试文件要另行删除。
     */
    private suspend fun probeRestoreRollback(relPrefix: String): String = withContext(Dispatchers.IO) {
        val dao = cn.dsr213.nasphoto.data.AppDb.get(this@MainActivity).photos()
        val repo = cn.dsr213.nasphoto.media.MediaRepo(this@MainActivity)
        val base = "/storage/emulated/0/" + relPrefix.trim().trim('/') + "/"
        val rows = dao.allRecords().filter { it.path.startsWith(base) }
        val sb = StringBuilder("=== 回灌测试清理（$base）===\n")
        sb.append("命中记录 ").append(rows.size).append(" 条\n")
        var gone = 0
        for (r in rows) {
            dao.deleteByPath(r.path) // ① 先清记录
            if (repo.deleteLocal(r.path) || !java.io.File(r.path).exists()) gone++ // ② 再删文件
        }
        sb.append("已清记录 ").append(rows.size).append(" · 文件已消失 ").append(gone).append('\n')
        // ③ 收掉因此变空的目录树。整库回灌回滚后会留下一整棵空目录
        //    （`Download/_np_big/DCIM/Camera/…`，实测 14 个）：不占空间，
        //    但在文件管理器和 SMB 里很扎眼，用户会以为"没删干净"。
        //    stopAt 取 base 的**父目录** ⇒ 只收本次涉及的子树，绝不往上越界。
        val stopAt = java.io.File(base).parentFile?.absolutePath ?: "/storage/emulated/0"
        val engine = cn.dsr213.nasphoto.engine.OffloadEngine(this@MainActivity)
        val pruned = engine.pruneEmptyDirs(rows.map { it.path }, stopAt)
        sb.append("已收空目录 ").append(pruned).append(" 个（止于 ").append(stopAt).append("）\n")
        sb.append("⚠️ NAS 上的测试文件需另行删除（本探针只处理手机侧）")
        sb.toString()
    }

    private suspend fun probeAgeRestored(days: Int): String = withContext(Dispatchers.IO) {
        val dao = cn.dsr213.nasphoto.data.AppDb.get(this@MainActivity).photos()
        val rows = dao.restoredRows()
        val now = System.currentTimeMillis()
        val at = if (days <= 0) now else now - days * 86_400_000L
        val n = dao.markAllRestoredAt(at)
        StringBuilder().apply {
            append("ageRestored → 改了 ").append(n).append(" 条（原有 ").append(rows.size).append(" 条）")
            append(" · 设成 ").append(if (days <= 0) "刚刚恢复" else "${days} 天前")
            append("\n  策略：「").append(prefs.restorePolicyText())
            append("」· 宽限期 ").append(prefs.restoreRedowngradeDays).append(" 天")
            if (rows.isEmpty()) {
                append("\n  ⚠️ 库里没有 RESTORED 记录 —— 先用 --es restoreSample 1 造一条再测")
            }
            rows.forEach {
                append("\n  ").append(it.path.substringAfterLast('/')).append("  restoredAt=").append(it.restoredAt)
            }
            append("\n  下一步：am start ... --es cand 1，看它是否按期望回到候选")
        }.toString()
    }

    private suspend fun probeTrash(): String {
        val st = engine.newTrashStore()
        val list = st.list() ?: return "trash → 读不到回收站（连不上 NAS / 目录列不全）"
        val recs = engine.recordsByRemotePath()
        // 带上「库内记录数」：它是判断下面那些「本地无」到底是**真没有**还是
        // **查询失效**的唯一依据 —— 库里有 100+ 条却全是"本地无"才叫异常。
        val sb = StringBuilder(
            "trash → ${list.size} 条 · 保留 ${st.keepDays()} 天 · 库内 ${recs.size} 条"
        )
        for (e in list) {
            val rec = recs[st.remoteRelOf(e.origRel)]
            sb.append("\n  ").append(e.date).append("  ").append(e.origRel)
                .append("  原").append(rec?.originalSize ?: -1L).append("B")
                .append("  ").append(rec?.state ?: "本地无")
        }
        return sb.toString()
    }

    /**
     * 熔断测试：NAS 侧少了一批文件时的**真实处理路径**（含安全闸 1/2/3）。
     *
     * 返回一句话摘要，方便 `adb logcat -s NasPhotoProbe:I` 直接看到结论。
     * 判读要点：`handle` 返回 **0 且待办没涨** = 熔断拦下了；返回 >0 = 正常询问/处理。
     */
    private suspend fun runRemoteDeletionProbe(): String {
        val before = cn.dsr213.nasphoto.service.RemoteDeletionWatcher.pendingCount(this)
        val s = cn.dsr213.nasphoto.service.RemoteDeletionWatcher.scan(this, force = true)
        if (s == null) return "remRun → 本轮放弃判定（连不上 / 列不全 / 明显读取异常）"
        if (s.isEmpty()) return "remRun → 无嫌疑（待办 $before）"
        val mode = prefs.remoteDeleteSyncMode
        val total = cn.dsr213.nasphoto.data.AppDb.get(this).photos().allRecords().size
        val n = cn.dsr213.nasphoto.service.RemoteDeletionWatcher.handle(this, s)
        val after = cn.dsr213.nasphoto.service.RemoteDeletionWatcher.pendingCount(this)
        val threshold =
            if (mode == "auto") minOf(20, Math.ceil(total * 0.10).toInt())
            else minOf(200, Math.ceil(total * 0.50).toInt())
        return "remRun[$mode] → 嫌疑 ${s.size} 个" +
            "（降级 ${s.count { it.offloaded }} / 本地原图 ${s.count { !it.offloaded }}）" +
            "｜库内总数 $total｜生效阈值 $threshold" +
            "｜handle 返回 $n｜待办 $before → $after"
    }

    /** 熔断测试：手机侧删了一批文件时的真实处理路径 */
    private suspend fun runLocalDeletionProbe(): String {
        val before = cn.dsr213.nasphoto.service.DeletionWatcher.pendingCount(this)
        val lib = cn.dsr213.nasphoto.media.MediaRepo(this).libraryPaths()
        val head = if (lib == null) "媒体库读取失败"
        else "库内可见 ${lib.visible.size} / 系统回收站 ${lib.trashed.size}"
        val s = cn.dsr213.nasphoto.service.DeletionWatcher.scan(this, force = true)
        if (s == null) return "delRun → 本轮放弃判定（$head）"
        if (s.isEmpty()) return "delRun → 无嫌疑（$head，待办 $before）"
        val mode = prefs.deleteSyncMode
        val total = cn.dsr213.nasphoto.data.AppDb.get(this).photos().allRecords().size
        val n = cn.dsr213.nasphoto.service.DeletionWatcher.handle(this, s)
        val after = cn.dsr213.nasphoto.service.DeletionWatcher.pendingCount(this)
        val threshold =
            if (mode == "auto") minOf(50, Math.ceil(total * 0.20).toInt())
            else minOf(200, Math.ceil(total * 0.50).toInt())
        return "delRun[$mode] → 嫌疑 ${s.size} 个｜库内总数 $total｜生效阈值 $threshold" +
            "｜handle 返回 $n｜待办 $before → $after"
    }

    /**
     * 熔断测试（方向一）：把 **N 个合成嫌疑**喂给**真实的** [DeletionWatcher.handle]。
     *
     * ## 为什么要用合成数据
     * 方向一的嫌疑判据是「DB 里有记录 **且** 本地文件真的不存在」。在真机上造这种现场
     * 只有两条路，都不能走：
     *  - 真删用户照片 —— 不可接受；
     *  - 把文件挪走再挪回来 —— MediaStore 会把新位置**再索引成一份新照片**，
     *    相册里凭空多出重复图（这正是一直在避免的事）。
     *
     * ## 这样测还作数吗
     * 走的是与真实调用**一模一样**的 `handle()`：熔断判据、阈值常量、`warnBulk` 通知
     * 全部真实执行。不真实的只有嫌疑的**来源**（不是 `scan()` 产出的）。
     * 换句话说，它验证的是"熔断这道闸"，不是"scan 能不能找出嫌疑"。
     *
     * ## 零文件风险
     * 路径全带 `__breaker_fake__`，NAS 上根本不存在：
     *  - 熔断命中 → 只发通知、直接 return，**一个动作都没有**；
     *  - 熔断未命中（ask）→ 只登记待办 + 弹卡片，**不问就不动 NAS**。
     */
    private suspend fun runFakeLocalDeletionProbe(n: Int): String {
        val fake = (1..n).map {
            cn.dsr213.nasphoto.service.DeletionWatcher.Suspect(
                path = "/storage/emulated/0/__breaker_fake__/f$it.jpg",
                remotePath = "OtherSpace/NasPhoto归档/__breaker_fake__/f$it.jpg",
                size = 1024L * it
            )
        }
        val mode = prefs.deleteSyncMode
        val total = cn.dsr213.nasphoto.data.AppDb.get(this).photos().allRecords().size
        val before = cn.dsr213.nasphoto.service.DeletionWatcher.pendingCount(this)
        val r = cn.dsr213.nasphoto.service.DeletionWatcher.handle(this, fake)
        val after = cn.dsr213.nasphoto.service.DeletionWatcher.pendingCount(this)
        val threshold =
            if (mode == "auto") minOf(50, Math.ceil(total * 0.20).toInt())
            else minOf(200, Math.ceil(total * 0.50).toInt())
        return "delFake[$mode] → 合成嫌疑 $n 个｜库内总数 $total｜生效阈值 $threshold" +
            "｜handle 返回 $r｜待办 $before → $after"
    }

    /**
     * 启动整个后台同步栈。抽成方法是为了让 relink 探针能**卡在它前面**跑完（见上）。
     */
    private fun startBackgroundSync() {
        // 后台自动同步：登记周期兜底任务（跟随开关约束），并补一次「尽快跑」
        cn.dsr213.nasphoto.service.SyncScheduler.ensureScheduled(this)
        cn.dsr213.nasphoto.service.SyncScheduler.kickOnce(this)
        // 实时监听：常驻前台服务，监听相册变化 → 新照片/新视频自动同步（通道①）
        cn.dsr213.nasphoto.service.SyncService.start(this)
        // 系统代管的内容触发器：常驻服务被杀/被冻结时的兜底（通道②）
        cn.dsr213.nasphoto.service.SyncScheduler.ensureContentTrigger(this)
        // ⚠️ 顺序很重要：**先跑删除检测（会登记待办 + 弹窗），再跑 pruneMissing**。
        //
        // 反过来会出事：用户刚在相册里删了照片，pruneMissing 一看"本地文件没了"就直接
        // 把 NAS 母本搬进回收站 —— 用户连"要不要一起删"都没被问过，等于绕过了确认。
        // 实测就这么丢过一次机会（母本进了回收站、记录也删了）。
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val s = cn.dsr213.nasphoto.service.DeletionWatcher.scan(this@MainActivity, force = true)
                if (!s.isNullOrEmpty()) {
                    cn.dsr213.nasphoto.service.DeletionWatcher.handle(this@MainActivity, s)
                }
            }
            // 反方向：NAS 上被删掉的 → 按配置询问 / 自动清本地小图
            // 也必须排在 pruneMissing 之前：反过来的话，那些"NAS 没了、本地还在"的记录
            // 会先被 pruneMissing 判成失效清掉，用户再也没机会看到"NAS 上删了什么"。
            val remoteN = withContext(Dispatchers.IO) {
                val rs = cn.dsr213.nasphoto.service.RemoteDeletionWatcher.scan(
                    this@MainActivity, force = true
                )
                if (rs.isNullOrEmpty()) 0
                else cn.dsr213.nasphoto.service.RemoteDeletionWatcher.handle(this@MainActivity, rs)
            }
            // ⚠️ 这里的数字**不是"删了几个"**：ask 模式下它只是"新登记了几条待办"，
            //    一个文件都没动。措辞必须中性，否则日志会把"问了"说成"删了"。
            if (remoteN > 0) {
                val mode = prefs.remoteDeleteSyncMode
                log("NAS 侧少了 $remoteN 个，已按「NAS→手机 = $mode」处理（详见下方日志）")
            }

            // 再清一次「本地和 NAS 都没了」的彻底失效记录
            val pruned = withContext(Dispatchers.IO) { engine.pruneMissing() }
            if (pruned > 0) log("已清理 $pruned 条失效记录（本地文件已不存在）")
            refreshStats()

            // 精确处理指定文件（自动化 / 记录修复用），不影响其他照片：
            //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity \
            //     --esal onlyPaths "/sdcard/a.jpg" "/sdcard/b.jpg"
            val picked = intent?.getStringArrayListExtra("onlyPaths")
            if (!picked.isNullOrEmpty()) {
                log("指定文件模式：${picked.size} 个")
                doOffload(picked.toSet())
            }

            // 转码 A/B 探针（排查"绿紫重影"用，不参与正常流程）：
            //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity \
            //     --es probe /sdcard/xxx.mp4
            // A = 保留 rotation 交给解码器（旧行为，用于复现 bug）
            // B = 剥掉 rotation（新行为，用于验证修复）
            // 恢复抽样（自动化验证用）：
            //   adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es restoreSample 3
            // 从 NAS 拉回母本 → 算 sha256 → **通过才覆盖本地**（不通过直接放弃，绝不覆盖）
            val restoreN = intent?.getStringExtra("restoreSample")?.toIntOrNull()
            if (restoreN != null && restoreN > 0) {
                val msg = withContext(Dispatchers.IO) { runRestoreSample(restoreN) }
                log(msg)
                android.util.Log.i("NasPhotoProbe", msg)
            }

            val probePath = intent?.getStringExtra("probe")
            if (!probePath.isNullOrEmpty()) {
                val msg = withContext(Dispatchers.IO) { runTranscodeProbe(probePath) }
                log(msg)
                android.util.Log.i("NasPhotoProbe", msg)
            }
        }
    }

    /**
     * 恢复抽样：从 NAS 拉回前 N 条已降级记录的母本，逐条比对大小。
     *
     * `restore()` 内部**先算 sha256，通过了才覆盖本地** —— 所以"能恢复且大小对得上"
     * 就等价于"NAS 上那份母本逐字节可信"。
     */
    private suspend fun runRestoreSample(n: Int): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder("恢复抽样 $n 条\n")
        val recs = engine.offloadedList().take(n)
        if (recs.isEmpty()) return@withContext "没有已降级的记录"
        var ok = 0
        for (r in recs) {
            val f = java.io.File(r.path)
            val before = f.length()
            val good = engine.restore(r.path) { m -> sb.append("   ").append(m).append("\n") }
            val after = f.length()
            val pass = good && after == r.originalSize
            if (pass) ok++
            sb.append("   → ").append(r.path.substringAfterLast('/'))
                .append("  ").append(before).append(" B → ").append(after)
                .append(" B（期望 ").append(r.originalSize).append(" B）")
                .append(if (pass) "  ✅\n" else "  ❌\n")
        }
        sb.append("合计 $ok/${recs.size}\n").toString()
    }

    /**
     * 孤儿文件回灌：读 `np_adopt.py` 生成的清单，逐条从 NAS 拉回母本并落库。
     *
     * 这些文件是早期 `pruneMissing` 只删记录、不清母本留下的 —— NAS 上那份往往
     * 是**仅存副本**，但在 App 里既看不到也管不了。回灌把它们拉回相册，
     * 重新纳入正常的备份 / 降级 / 删除同步管理。
     */
    private suspend fun runAdopt(jsonPath: String): String = withContext(Dispatchers.IO) {
        val f = java.io.File(jsonPath)
        if (!f.exists()) return@withContext "adopt → 清单文件不存在：$jsonPath"
        val items = try {
            val arr = org.json.JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                OffloadEngine.OrphanItem(
                    localPath = o.getString("localPath"),
                    remotePath = o.getString("remotePath"),
                    sha256 = o.getString("sha256"),
                    size = o.getLong("size"),
                    takenAt = o.getLong("takenAt"),
                    lastModified = o.getLong("lastModified")
                )
            }
        } catch (t: Throwable) {
            return@withContext "adopt → 清单解析失败：${t.message}"
        }
        val n = engine.adoptOrphans(items) { m -> runOnUiThread { log(m) } }
        "adopt → 回灌完成，恢复 $n / 共 ${items.size}"
    }

    /**
     * 确认卡片外观探针 —— **只弹卡片，一个文件都不碰**。
     *
     * ```bash
     * adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es askUi remote
     * adb shell am start -n cn.dsr213.nasphoto/.ui.MainActivity --es askUi local
     * ```
     *
     * 为什么需要它：确认卡片是**悬浮窗**，宽度算错时不会报任何错，只是静静地撑到
     * 屏幕外 —— 按钮看不见、文案被裁掉，从日志里完全看不出来（踩过：右半张卡片
     * 加「保留」按钮整颗跑到屏幕外，用户以为"只能删"）。所以要有个能随时复现的入口，
     * 肉眼核对 + 截图留证。
     *
     * 顺带把三路宽度来源打进 `NasPhotoProbe` 日志：窗口指标 / 资源 / 显示，
     * 哪一路算错了能一眼看出来。弹卡片本身不落任何副作用（待办清单为空时点按钮也是空操作）。
     */
    private fun askUiProbe(kind: String) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val dm = resources.displayMetrics
        val cur = runCatching { wm.currentWindowMetrics.bounds }.getOrNull()
        val max = runCatching { wm.maximumWindowMetrics.bounds }.getOrNull()
        val probe = "askUi[$kind] → 资源 ${dm.widthPixels}x${dm.heightPixels}" +
            " 密度 ${dm.density} 字缩放 ${dm.scaledDensity / dm.density}" +
            "｜当前窗口 $cur｜最大窗口 $max｜dp(16)=${dp(16)}px"
        log(probe)
        android.util.Log.i("NasPhotoProbe", probe)

        val sample = "IMG20250729230429 2.jpg"
        if (kind.startsWith("local", ignoreCase = true)) {
            cn.dsr213.nasphoto.ui.DeleteAskUi.ask(this, 3, 12_600_000L, sample)
        } else {
            cn.dsr213.nasphoto.ui.DeleteAskUi.askRemote(this, 8, 17_900_000L, sample)
        }
    }

    /**
     * 重新定位：读清单，把每条记录指向的位置与母本实际所在的位置对齐。
     *
     * 清单每项：
     * ```json
     * {"oldPath":"/storage/emulated/0/xxx/a.jpg",   // 用来定位 DB 记录
     *  "newPath":"/storage/emulated/0/yyy/a.jpg",   // 要改写成的 path
     *  "oldRemote":"OtherSpace/NasPhoto归档/xxx/a.jpg",
     *  "newRemote":"OtherSpace/NasPhoto归档/yyy/a.jpg",
     *  "move":true}
     * ```
     * 实际动作在 [OffloadEngine.relink]：先 MOVE 母本，成功了再改 DB。
     */
    private suspend fun runRelink(jsonPath: String): String = withContext(Dispatchers.IO) {
        val f = java.io.File(jsonPath)
        if (!f.exists()) return@withContext "relink → 清单文件不存在：$jsonPath"
        val items = try {
            val arr = org.json.JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Relink(
                    oldPath = o.getString("oldPath"),
                    newPath = o.optString("newPath", o.getString("oldPath")),
                    oldRemote = o.getString("oldRemote"),
                    newRemote = o.getString("newRemote"),
                    move = o.optBoolean("move", true)
                )
            }
        } catch (t: Throwable) {
            return@withContext "relink → 清单解析失败：${t.message}"
        }
        engine.relink(items) { m -> runOnUiThread { log(m) } }
    }

    /** 同一段视频分别用"保留旋转/剥掉旋转"各转一次，产物落在 /sdcard/DCIM/MotionPoc/ */
    private fun runTranscodeProbe(srcPath: String): String {
        val sb = StringBuilder("转码探针 $srcPath\n")
        val dir = java.io.File("/sdcard/DCIM/MotionPoc")
        dir.mkdirs()

        // 动态照片：直接跑生产路径（MotionPhoto.rebuild = 缩底图 + 内嵌视频转码 + 改 XMP + 拼装）
        if (srcPath.endsWith(".jpg", true) || srcPath.endsWith(".jpeg", true)) {
            val out = java.io.File(dir, "probe_motion.jpg")
            out.delete()
            val t0 = System.currentTimeMillis()
            val err = cn.dsr213.nasphoto.image.MotionPhoto.rebuild(
                src = java.io.File(srcPath),
                dst = out,
                previewLongEdge = prefs.previewLongEdge,
                previewQuality = prefs.previewQuality,
                videoMaxEdge = minOf(prefs.videoMaxEdge, 1280),
                videoBitrateBps = minOf(
                    prefs.videoBitrateKbps * 1000,
                    cn.dsr213.nasphoto.engine.MOTION_VIDEO_BITRATE_CAP
                ),
                videoMaxBytes = minOf(prefs.videoMaxMb.toLong() * 1024 * 1024, 8L * 1024 * 1024)
            )
            sb.append("motion ok=${err == null} err=$err ${out.length()}B ${System.currentTimeMillis() - t0}ms\n")
            return sb.toString()
        }

        // 码率阶梯：1:1 转码，用 SSIM 找"画质拐点"（排查用）
        val ladder = listOf(1_800_000, 3_000_000, 4_500_000, 6_000_000, 9_000_000)
        for (br in ladder) {
            val tag = "br${br / 1000}k"
            val out = java.io.File(dir, "probe_ladder_$tag.mp4")
            out.delete()
            val t0 = System.currentTimeMillis()
            val r = cn.dsr213.nasphoto.video.VideoTranscoder.transcode(
                src = java.io.File(srcPath),
                dst = out,
                maxEdge = 1280,
                bitrateBps = br,
                maxBytes = 64L * 1024 * 1024,
                preserveHdr = false,
                fallbackDurationMs = 3_500L
            )
            sb.append(
                "$tag ok=${r.ok} ${r.width}x${r.height} ${out.length()}B " +
                    "实际=${r.bitrateBps / 1000}kbps ${System.currentTimeMillis() - t0}ms\n"
            )
        }
        return sb.toString()
    }

    /**
     * 写一行日志。**统一走 [SyncLog]**，不再直接改 TextView ——
     * 这样后台服务写的东西和界面写的东西进的是同一条流，
     * 界面没打开时产生的日志也不会丢（下次打开能看到）。
     */
    private fun log(msg: String) = SyncLog.add(msg)

    // ------------------------------------------------------------ 实时日志订阅

    override fun onStart() {
        super.onStart()
        // 先铺一遍历史（含 App 没在前台时后台服务产生的那些），再接增量
        logLines.clear()
        logLines.addAll(SyncLog.snapshot())
        tvLog.text = logLines.joinToString("\n")
        svLog.post { svLog.fullScroll(android.view.View.FOCUS_DOWN) }
        SyncLog.attach(logSink)
    }

    override fun onStop() {
        SyncLog.detach(logSink)
        super.onStop()
    }

    private fun refreshStats() {
        lifecycleScope.launch {
            val off = withContext(Dispatchers.IO) { engine.offloadedCount() }
            val bak = withContext(Dispatchers.IO) { engine.backedUpCount() }
            val saved = withContext(Dispatchers.IO) { engine.savedBytes() }
            val gate = withContext(Dispatchers.IO) { DeviceState.describe(this@MainActivity) }
            val sched = withContext(Dispatchers.IO) {
                cn.dsr213.nasphoto.service.SyncScheduler.describe(this@MainActivity)
            }
            // 这个判断会走 Binder，放到 IO 线程上做
            val batteryOk = withContext(Dispatchers.IO) { ignoringBatteryOptimizations() }
            // 完整配置存起来给弹窗用
            lastStatsFull = buildString {
                append(
                    "NasPhoto " + cn.dsr213.nasphoto.BuildConfig.VERSION_NAME +
                        "（开发版 · 尚未可用，勿用于重要数据）\n"
                )
                append("已降级：$off 个 · 已释放 ${fmt(saved)}\n")
                append("仅备份（本地原样）：$bak 个\n")
                append(
                    if (prefs.isConfigured) "NAS：${prefs.user} @ ${prefs.webdavBase}\n"
                    else "NAS：尚未配置 —— 去「设置」填 NAS 地址\n"
                )
                append("通道：${channelSummary()}\n")
                append("归档：${prefs.remoteRoot}\n")
                append("降级延迟：照片=${prefs.downgradeDelayText(false)} · 视频=${prefs.downgradeDelayText(true)}\n")
                append("已恢复照片：${prefs.restorePolicyText()}\n")
                append("降级门槛：≥${prefs.minSizeKb}KB · 长边>${prefs.previewLongEdge} · 未收藏\n")
                append("Ultra HDR：" + (if (prefs.ultraHdrDowngrade) "降级（原图含 HDR 存 NAS）" else "只备份") + "\n")
                append("动态照片：" + (if (prefs.motionPhotoDowngrade) "保动态降级（仍可播放）" else "不处理（本地原样）") + "\n")
                append("视频：原分辨率重编码 · ${prefs.videoBitrateKbps}kbps · ≤${prefs.videoMaxMb}MB/段 · ")
                append(if (!prefs.videoEnabled) "已关闭" else "已开启")
                append("\n当前状态：$gate")
                append(
                    "\n同步时机：照片=" +
                        (if (prefs.photoRequireChargingWifi) "等充电+WiFi" else "实时") +
                        " · 视频=" +
                        (if (prefs.videoRequireChargingWifi) "等充电+WiFi" else "实时")
                )
                append("\n实时监听：" + (if (prefs.realtimeListen) "开（常驻前台服务 + 内容触发器双通道）" else "关"))
                append(
                    "\n电池优化：" + (if (batteryOk) "已关闭（后台更稳）"
                    else "未关闭（可能被系统冻结）")
                )
                append("\n后台兜底：$sched（每 6 小时）")
                if (prefs.onlyBucket.isNotBlank()) append("\n限定相册：${prefs.onlyBucket}")
                if (prefs.maxBatch > 0) append("\n单轮上限：${prefs.maxBatch} 个")
            }

            // ⚠️ 主界面**只放 4 行摘要**：完整配置有十几行，全铺在顶部会把下面的
            //    实时日志压成零高度（weight=1 抢不到空间）—— 实测踩过。
            tvStats.text = buildString {
                append("已降级 $off 个 · 已释放 ${fmt(saved)}　|　仅备份 $bak 个\n")
                append("状态：$gate\n")
                append("归档：${prefs.remoteRoot}\n")
                append("▸ 点这里看完整配置（同步时机 / 门槛 / 电池优化 / 通道）")
            }
        }
    }

    /** 完整配置弹窗 —— 从摘要点进来，主界面就腾出来了 */
    private fun showStatsDetail() {
        AlertDialog.Builder(this)
            .setTitle("当前配置")
            .setMessage(lastStatsFull.ifEmpty { "正在读取…" })
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun doScan() {
        lifecycleScope.launch {
            val rows = engine.survey()
            if (rows.isEmpty()) {
                log("没有符合策略的候选")
            } else {
                // 这里的数字是**全量**（`survey()` 已改为不受 maxBatch 截断），
                // 所以要和"单轮实际处理多少"分开说 —— 否则又会被读成"就这么多"。
                val cap = prefs.maxBatch
                log(
                    "策略预检：共 ${rows.sumOf { it.count }} 个候选" +
                        "（全量 · 单轮按上限取前 ${if (cap > 0) cap else "不限"}）"
                )
                rows.forEach { r ->
                    val act = when (r.action) {
                        Action.BACKUP_ONLY -> "只备份"
                        Action.DOWNGRADE -> "降级"
                        Action.DOWNGRADE_WEBP -> "转WebP"
                        else -> "跳过"
                    }
                    // ⛔ = 本来能降级、被门槛挡住（保护相册 / 太新 / 已收藏 / 太小 / 长边不够）。
                    // 没有这个标记，用户设了保护相册在界面上看不出任何效果。
                    val mark = if (r.blockedBy != null) "⛔ " else "  "
                    val why = r.blockedBy?.let { " · $it" } ?: ""
                    log(
                        "$mark${if (r.isVideo) "视频" else "图片"} · ${r.tag}$why → $act" +
                            "  ${r.count} 个 / ${fmt(r.bytes)}"
                    )
                }
                val blocked = rows.filter { it.blockedBy != null }
                if (blocked.isNotEmpty()) {
                    log("  ⛔ 合计 ${blocked.sumOf { it.count }} 个降级被门槛挡住（只备份、本地原样保留）")
                }
            }
            refreshStats()
        }
    }

    private fun doOffload(onlyPaths: Set<String>? = null) {
        if (!ensurePermissions()) return
        lifecycleScope.launch {
            // 与后台 Worker 互斥：两条链路都写 NAS + DB，不能同时跑。
            //
            // ⚠️ 但**不能一撞上就失败**。后台任务（实时监听 / 内容触发器 / 6h 兜底）
            //    本就在跑，一轮十几秒到几分钟，直接弹"请稍候"会让人以为按钮坏了 ——
            //    实测 `--esal onlyPaths` 探针也因此被静默吞掉。这里改成**排队等它让出**。
            if (!SyncGate.tryEnter()) {
                log("已有同步在跑，排队等待…")
                var waited = 0L
                while (!SyncGate.tryEnter()) {
                    if (waited >= GATE_WAIT_MS) {
                        log("等待超时（${GATE_WAIT_MS / 1000}s）：后台任务仍在跑，稍后再试")
                        Toast.makeText(this@MainActivity, "后台同步仍在进行，请稍后再试", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    delay(500)
                    waited += 500
                    if (waited % 5_000 == 0L) log("仍在等待后台任务让出（已等 ${waited / 1000}s）…")
                }
                log("轮到我了，开始执行")
            }
            try {
                log("开始执行…")
                engine.run({ msg -> runOnUiThread { log(msg) } }, onlyPaths)
                refreshStats()
                Toast.makeText(this@MainActivity, "本轮完成", Toast.LENGTH_SHORT).show()
            } finally {
                SyncGate.leave()
            }
        }
    }

    private companion object {
        /** 手动执行最多等后台任务让出多久 */
        const val GATE_WAIT_MS = 180_000L
    }

    /** 是否已对本 App 关闭电池优化（Android 12+ 后台启动前台服务的豁免条件之一） */
    private fun ignoringBatteryOptimizations(): Boolean = runCatching {
        (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
    }.getOrDefault(false)

    /**
     * 引导用户关闭电池优化。
     *
     * 这不是"锦上添花" —— 按官方文档，**「用户关闭了本 App 的电池优化」是
     * Android 12+ 允许从后台启动前台服务的豁免条件之一**，
     * 有了它，第二通道（内容触发器）才有能力把常驻监听救回来。
     */
    private fun askIgnoreBatteryOptimizations() {
        if (ignoringBatteryOptimizations()) return
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }.onFailure {
            // 某些 ROM 屏蔽了这个 Intent，退回电池优化设置总页
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun ensurePermissions(): Boolean {
        val need = mutableListOf<String>()
        val media = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        for (p in media) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                need.add(p)
            }
        }
        // 后台前台服务的进度通知（Android 13+ 需要显式授权才看得见）。
        // ⚠️ 这是**可选**权限：拒绝了同步照样能跑，只是没有通知 —— 所以不阻塞流程。
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002
            )
        }
        if (need.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), 1001)
            return false
        }

        // 所有文件访问权限（用于写回原图）
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (t: Throwable) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            Toast.makeText(this, "请授予「所有文件访问权限」后重试", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    // ------------------------------------------------------------ 设置

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

    /**
     * 把时间戳说成人话（「3 天前」）。
     *
     * 用在「家庭 IPv6 前缀是什么时候学的」——这个值新不新鲜直接决定了公网通道能不能用，
     * 光显示一个绝对时间用户没法判断。
     */
    private fun agoText(at: Long): String {
        if (at <= 0L) return "时间未知"
        val d = System.currentTimeMillis() - at
        return when {
            d < 60_000L -> "刚刚学到"
            d < 3_600_000L -> "${d / 60_000L} 分钟前学到"
            d < 86_400_000L -> "${d / 3_600_000L} 小时前学到"
            else -> "${d / 86_400_000L} 天前学到"
        }
    }

    /**
     * 保存设置后**立刻把四条通道都探一遍**，结果打进日志并 Toast 一句。
     *
     * 为什么值得做：通道是"看不见摸不着"的东西 —— 地址填进去到底通不通，
     * 不探一次就只能等下次同步才知道，而且排查"为什么这次走了慢通道"时毫无线索。
     * 探测本身全部并发，最多 3.5 秒。
     */
    private fun probeChannels() {
        lifecycleScope.launch {
            val t0 = System.currentTimeMillis()
            val r = EndpointResolver.resolve(prefs, this@MainActivity)
            val cost = System.currentTimeMillis() - t0
            for (p in r.probes) {
                val verdict = if (p.ok) "✅ ${p.ms}ms（HTTP ${p.code}）" else "❌ 不可达"
                SyncLog.add("·探测  ${p.endpoint.kind.label}  ${p.endpoint.display}  $verdict")
            }
            if (r.prefixUpdated != null) {
                SyncLog.add("·前缀  已刷新为 ${r.prefixUpdated}（下次在外可直接用）")
            }
            val msg = r.endpoint?.let { "最优通道：${it.kind.label}（${cost}ms）" }
                ?: "所有通道都不通（${cost}ms）"
            SyncLog.add("·探测  $msg")
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 「测试连接」按钮的结果文本：把每条候选通道的探测结论**留在设置对话框里**。
     *
     * 与 [probeChannels]（保存后自动跑，结果进 SyncLog + Toast）互补 ——
     * 那个是"事后留痕"，这个是"当场看见"。
     *
     * **只读**：不改任何配置项。唯一的写入是 `EndpointResolver` 内部的
     * `lastGoodChannel` 与家庭前缀缓存 —— 那是「探测成功」的固有副产物，不是按钮在改配置。
     */
    private suspend fun probeChannelsReport(): String = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val r = runCatching { EndpointResolver.resolve(prefs, this@MainActivity) }
            .getOrElse { e ->
                return@withContext "⚠️ 探测本身出错：${e.javaClass.simpleName}: ${e.message ?: ""}"
            }
        val cost = System.currentTimeMillis() - t0
        buildString {
            append("候选 ").append(r.probes.size).append(" 条 · 共 ").append(cost).append("ms\n")
            for (p in r.probes) {
                append(if (p.ok) "✅ " else "❌ ")
                append(p.endpoint.kind.label).append(' ')
                append(if (p.ok) "${p.ms}ms" else "不可达")
                append('\n')
                append("    ").append(p.endpoint.display).append('\n')
            }
            append("────────\n")
            append(
                r.endpoint?.let { "最优：${it.kind.label}" }
                    ?: "⚠️ 全部不可达 —— 本轮同步会跳过（这**不会**被当成「文件被删」，母本安全）"
            )
            if (!r.lanOk) append("\n（当前不在家里的局域网）")
        }
    }

    /**
     * 保护相册选择器。
     *
     * 列表**必须来自 MediaStore 实测**（[cn.dsr213.nasphoto.media.MediaRepo.buckets]）——
     * 不能让用户手打名字：打错一个不存在的相册，界面上照样显示"已保护 XXX"，
     * 实际一个文件都不会命中，属于**静默失效**（用户以为自己保护好了）。
     *
     * 同时把「已保护、但媒体库里现在没有」的陈旧条目也列出来，
     * 否则用户永远取消不掉它 —— 它会一直留在 prefs 里占位。
     */
    private fun showProtectedBucketsPicker(
        current: Set<String>,
        onDone: (Set<String>) -> Unit
    ) {
        lifecycleScope.launch {
            val buckets = withContext(Dispatchers.IO) {
                runCatching { MediaRepo(this@MainActivity).buckets() }.getOrElse { emptyList() }
            }
            if (buckets.isEmpty()) {
                // ⚠️ 读不到就如实报错，绝不渲染成"没有相册" ——
                //    空列表会让用户以为"本来就该没有保护相册"，进而把已有的保护设置丢掉。
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("读不到相册列表")
                    .setMessage(
                        "没能从系统媒体库读到任何相册。\n\n" +
                            "常见原因：媒体权限被撤销，或系统正在重建媒体索引。\n" +
                            "为了不误动你已有的保护设置，这次什么都没改 —— 稍后再试。"
                    )
                    .setPositiveButton("知道了", null)
                    .show()
                return@launch
            }

            val chosen = current.toMutableSet()
            val col = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(8))
            }
            col.addView(hint("勾选 = 该相册永不降级。相册名与条目数来自系统媒体库实测。"))

            /** 大小写不敏感地增删：归档侧 `pictures/` 与 `Pictures/` 是同一目录，
             *  用户不该因为大小写差异看到两个都能勾的条目。 */
            fun applyToggle(name: String, on: Boolean) {
                val hit = chosen.firstOrNull { it.equals(name, true) }
                if (on) {
                    if (hit == null) chosen.add(name)
                } else if (hit != null) {
                    chosen.remove(hit)
                }
            }

            for ((name, count) in buckets) {
                col.addView(CheckBox(this@MainActivity).apply {
                    text = "$name（$count）"
                    isChecked = chosen.any { it.equals(name, true) }
                    setOnCheckedChangeListener { _, on -> applyToggle(name, on) }
                })
            }

            val lower = buckets.map { it.first.lowercase() }.toSet()
            val stale = current.filter { it.lowercase() !in lower }.sorted()
            if (stale.isNotEmpty()) {
                col.addView(TextView(this@MainActivity).apply {
                    text = "以下条目已在保护名单里，但系统媒体库里现在没有（相册被删或改名）"
                    textSize = 12f
                    alpha = 0.75f
                    setPadding(0, dp(12), 0, 0)
                })
                for (name in stale) {
                    col.addView(CheckBox(this@MainActivity).apply {
                        text = "$name（媒体库里没有）"
                        isChecked = true
                        setOnCheckedChangeListener { _, on -> applyToggle(name, on) }
                    })
                }
            }

            val scroll = MaxHeightScrollView(this@MainActivity).apply {
                maxHeightPx = (resources.displayMetrics.heightPixels * 0.62f).toInt()
                isVerticalScrollBarEnabled = true
                isScrollbarFadingEnabled = false
                scrollBarStyle = android.view.View.SCROLLBARS_INSIDE_INSET
                isFillViewport = false
                addView(
                    col,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }

            AlertDialog.Builder(this@MainActivity)
                .setTitle("保护相册（永不降级）")
                .setView(scroll)
                .setPositiveButton("确定") { _, _ -> onDone(chosen.toSet()) }
                .setNeutralButton("全部不保护") { _, _ -> onDone(emptySet()) }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /**
     * 通道诊断探针：把「网络环境 → 策略门控 → 每条候选通道的探测结果 → 最终选中谁 →
     * 家庭 IPv6 前缀」整条链路一次性打出来。
     *
     * **不启动同步栈、不改任何配置项** —— 唯一的写入是 `lastGoodChannel` 与前缀缓存，
     * 那是「探测成功」的固有副产物，不是探针在改配置。
     */
    private suspend fun probeChannelsForAdb(): String {
        val sb = StringBuilder()
        val policy = NetPolicy.byKey(prefs.netPolicy)
        sb.append("chan[环境] ").append(NetGate.describe(this, policy)).append('\n')
        sb.append("chan[策略] ").append(policy.label).append(" → 可用 ")
            .append(NetGate.channelsInUse(policy).joinToString("/") { it.label }).append('\n')

        val cands = EndpointResolver.candidates(prefs)
        sb.append("chan[候选] ").append(cands.size).append(" 条 ")
            .append(cands.joinToString(" | ") { "${it.kind.key}=${it.baseUrl}" }).append('\n')

        val r = EndpointResolver.resolve(prefs, this)
        for (p in r.probes) {
            sb.append("chan[探测] ").append(p.endpoint.kind.key).append(' ')
                .append(p.endpoint.display).append(" → ")
                .append(if (p.ok) "OK ${p.ms}ms http=${p.code}" else "不可达")
                .append('\n')
        }
        sb.append("chan[选择] ")
            .append(r.endpoint?.let { "${it.kind.key} → ${it.baseUrl}" } ?: "无（本轮会跳过）")
            .append('\n')
        sb.append("chan[在家] ").append(r.lanOk).append('\n')
        sb.append("chan[前缀] ").append(prefs.chanV6LearnedPrefix.ifBlank { "（未学到）" })
            .append(" · 本机全局 v6 ").append(Ipv6Learner.globalPrefix(this) ?: "无")
        return sb.toString()
    }

    /**
     * IPv6 通道预演：用学到的家庭前缀 + 两个候选后缀拼出 NAS 的 v6 地址并探测。
     *
     * **不读也不改任何配置**（只读 `chanV6LearnedPrefix` 等展示项，不写回）。
     * 局域网内测通 = 「前缀学习 + 地址拼装 + v6 客户端代码」三段全部正确，
     * 剩下唯一变量就是"公网包能不能进来"。
     */
    private suspend fun probeV6Try(): String {
        val prefix = prefs.chanV6LearnedPrefix.trim()
        if (prefix.isEmpty()) {
            return "v6try[跳过] 还没有家庭前缀 —— 先在局域网里连一次 WiFi（跑 --es chan 1 即可学到）"
        }
        val sb = StringBuilder()
        sb.append("v6try[前缀] ").append(prefix).append('\n')
        val path = prefs.webdavPath
        val port = prefs.chanV6Port
        val trials = listOf(
            ":${prefs.chanV6Eui64.trim().trim(':')}" to "EUI-64（永不变）",
            "::${prefs.chanV6DhcpTail.trim().trim(':')}" to "DHCPv6（可能变）"
        )
        for ((frag, why) in trials) {
            val url = "https://[$prefix$frag]:$port$path"
            val ok = EndpointResolver.probeUrl(url, 3_000)
            sb.append("v6try[").append(why).append("] ")
                .append(url).append(" → ").append(if (ok) "OK" else "不可达")
                .append('\n')
        }
        sb.append("v6try[说明] 局域网内应两条都通 —— 不通说明拼装或客户端代码有问题，而不是防火墙")
        return sb.toString()
    }

    /** 配置摘要里那一行「当前有哪几条通道、什么策略、上次走的哪条」 */
    private fun channelSummary(): String {
        val cands = EndpointResolver.candidates(prefs)
        if (cands.isEmpty()) return "未启用任何通道（回落到上面的基址）"
        val names = cands.map { it.kind.label }.distinct().joinToString(" / ")
        val last = ChannelKind.byKey(prefs.lastGoodChannel)?.label ?: "—"
        val v6 = when {
            !prefs.chanV6Enabled -> ""
            prefs.chanV6LearnedPrefix.isBlank() -> " · 无前缀"
            else -> " · 前缀 ${prefs.chanV6LearnedPrefix}"
        }
        return "$names · 策略 ${NetPolicy.byKey(prefs.netPolicy).label} · 上次 $last$v6"
    }

    /** 分组小标题 */    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(4))
    }

    /** 灰色说明文字 */
    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setPadding(0, dp(2), 0, 0)
        alpha = 0.75f
    }

    /** 「备份后立刻降级 / 超过 N 天再降级」二选一 */
    private class DelayPicker(
        val isVideo: Boolean,
        val now: android.widget.RadioButton,
        val later: android.widget.RadioButton,
        val days: EditText
    ) {
        /** 0 = 立刻降级；否则是天数 */
        fun saveTo(prefs: Prefs) {
            val v = if (now.isChecked) {
                0
            } else {
                (days.text.toString().trim().toIntOrNull() ?: 30).coerceIn(1, 3650)
            }
            if (isVideo) prefs.videoDowngradeDelayDays = v else prefs.photoDowngradeDelayDays = v
        }
    }

    private fun delayPickerRow(
        parent: LinearLayout,
        label: String,
        daysNow: Int,
        isVideo: Boolean
    ): DelayPicker {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setPadding(0, dp(8), 0, 0)
        })
        val row = android.widget.RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
        }
        val rbNow = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "备份后立刻"
        }
        val rbLater = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "超过"
        }
        val etDays = EditText(this).apply {
            setText((if (daysNow <= 0) 30 else daysNow).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(rbNow)
        row.addView(rbLater)
        row.addView(etDays)
        row.addView(TextView(this).apply { text = "天"; textSize = 14f })
        if (daysNow <= 0) rbNow.isChecked = true else rbLater.isChecked = true

        // 选「立刻」时把天数框置灰，避免误以为还在按天数走
        val sync = {
            etDays.isEnabled = rbLater.isChecked
            etDays.alpha = if (rbLater.isChecked) 1f else 0.35f
        }
        row.setOnCheckedChangeListener { _, _ -> sync() }
        sync()
        parent.addView(row)
        return DelayPicker(isVideo, rbNow, rbLater, etDays)
    }

    /**
     * 设置对话框。
     *
     * ⚠️ 两点是**必须**的，否则在折叠屏上直接用不了：
     * 1. 内容要用 [MaxHeightScrollView] 包起来。`setView()` 不会自动给滚动能力
     *    （只有 `setMessage()` 才会），选项一多就超出屏幕、底部既看不到也滚不到。
     * 2. 窗口**限宽**。阔折叠展开后宽 600dp+，不限制的话输入框会被拉成一条横线。
     */
    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        fun field(label: String, value: String): EditText {
            box.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setPadding(0, dp(10), 0, 0)
                alpha = 0.75f
            })
            val et = EditText(this)
            et.setText(value)
            et.textSize = 15f
            box.addView(et)
            return et
        }

        // ================= NAS 连接 =================
        box.addView(sectionTitle("NAS 连接"))
        val etBase = field("WebDAV 基址", prefs.webdavBase)
        val etUser = field("用户名", prefs.user)
        val etPass = field("密码", prefs.password)
        val etRoot = field("归档根目录（相对基址）", prefs.remoteRoot)
        val cbSelfSigned = CheckBox(this).apply {
            text = "允许自签设备证书（NAS 证书约 21 天轮换）"
            isChecked = prefs.allowSelfSigned
        }
        box.addView(cbSelfSigned)
        val cbVerify = CheckBox(this).apply {
            text = "上传后读回算 SHA-256 校验（更稳，稍慢）"
            isChecked = prefs.verifyReadBack
        }
        box.addView(cbVerify)

        // ---- 测试连接 ----
        // 以前只有「保存后自动跑一次 probeChannels()」，结果进 SyncLog + Toast：
        // 用户改完地址**无法主动验证**，只能等下次同步才发现填错了。
        // 这里把逐条结果留在对话框里，不用关窗口去日志区找。
        val tvConnResult = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(6), 0, 0)
        }
        box.addView(Button(this).apply {
            text = "测试连接"
            setOnClickListener {
                isEnabled = false
                text = "探测中…"
                tvConnResult.text = ""
                lifecycleScope.launch {
                    tvConnResult.text = probeChannelsReport()
                    text = "测试连接"
                    isEnabled = true
                    // ⚠️ 结果生成在按钮**下方**，而设置面板是可滚动的、默认停在顶部
                    //    ⇒ 不滚过去，用户点完看到的是"什么也没发生"，只会反复点。
                    //    （实测踩过：结果确实出来了，但首屏一个字都看不到）
                    (tvConnResult.parent as? android.view.View)?.let { inner ->
                        (inner.parent as? ScrollView)?.let { sv ->
                            sv.post { sv.smoothScrollTo(0, tvConnResult.top) }
                        }
                    }
                }
            }
        })
        box.addView(tvConnResult)
        box.addView(hint(
            "⚠️ 探测用的是**已保存**的配置 —— 改完地址请先点「保存」，再回来点这里。" +
                "全部通道并发探测，最长约 3.5 秒。"
        ))

        // ================= 网络通道 =================
        box.addView(sectionTitle("网络通道"))
        box.addView(hint(
            "出门在外时走哪条路连回 NAS。多条可同时启用，每次同步会自动挑「当前最快且能通」的那条。" +
                "全都不可达时本轮直接跳过 —— ⚠️ 绝不把「读不到」当成「NAS 上被删了」。"
        ))

        box.addView(TextView(this).apply {
            text = "备份时机"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(10), 0, dp(2))
        })
        val rbPolLan = android.widget.RadioButton(this).apply {
            text = "仅局域网 —— 只有连上家里 WiFi 才同步（最快、最省）"
        }
        val rbPolWifi = android.widget.RadioButton(this).apply {
            text = "任意 WiFi —— 在外面连上 WiFi 也同步"
        }
        val rbPolAny = android.widget.RadioButton(this).apply {
            text = "任意网络 —— 含蜂窝流量，随时随地同步"
        }
        box.addView(android.widget.RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(rbPolLan); addView(rbPolWifi); addView(rbPolAny)
            when (NetPolicy.byKey(prefs.netPolicy)) {
                NetPolicy.ANY_WIFI -> rbPolWifi.isChecked = true
                NetPolicy.ANY_NETWORK -> rbPolAny.isChecked = true
                else -> rbPolLan.isChecked = true
            }
        })
        box.addView(hint("选「仅局域网」时下面 ②③④ 会被策略挡掉 —— 那一档的语义就是「只在家里传」。"))

        box.addView(TextView(this).apply {
            text = "通道（可多选，自动择优）"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(12), 0, dp(2))
        })
        val cbLan = CheckBox(this).apply {
            text = "① 局域网直连 —— 实测 73~90 MB/s，唯一适合首次全量"
            isChecked = prefs.chanLanEnabled
        }
        box.addView(cbLan)
        val etLanBase = field("   基址（留空 = 用上面的 WebDAV 基址）", prefs.chanLanBase)

        val cbV6 = CheckBox(this).apply {
            text = "② 公网 IPv6 直连 —— 已预埋，当前家里还不通"
            isChecked = prefs.chanV6Enabled
        }
        box.addView(cbV6)
        box.addView(hint(
            "预埋说明：家里是「光猫（路由模式，拦全部入站）→ 小米路由器 → NAS」，" +
                "而光猫后台没有 IPv6 放行模块。一旦外部条件解决，这里打开开关即可 —— " +
                "前缀自动学习、地址拼装、失败熔断都已就位，App 侧不用改任何代码。"
        ))
        val learned0 = prefs.chanV6LearnedPrefix
        box.addView(hint(
            "   " + if (learned0.isBlank()) {
                "家庭前缀：还没学到 —— 回家连一次 WiFi 会自动记住（于是连 DDNS 都不需要）"
            } else {
                "家庭前缀：$learned0（${agoText(prefs.chanV6LearnedAt)}）"
            }
        ))
        val etV6Eui = field("   NAS 的 EUI-64 后缀（由网卡 MAC 派生，永不变）", prefs.chanV6Eui64)
        val etV6Dhcp = field("   NAS 的 DHCPv6 后缀尾巴（路由器重启可能变）", prefs.chanV6DhcpTail)
        val etV6Manual = field("   手填完整 IPv6 地址（填了就只用它，不再自动拼）", prefs.chanV6ManualAddr)

        val cbFrp = CheckBox(this).apply {
            text = "③ frp 中转 —— 走腾讯云，唯一现在就能用的外网通道"
            isChecked = prefs.chanFrpEnabled
        }
        box.addView(cbFrp)
        val etFrpBase = field("   基址（如 https://nas.dsr213.cn/pool0/data）", prefs.chanFrpBase)
        box.addView(hint(
            "   原理：NAS 主动连到 1.12.251.37:7000，公网流量由云端转回 NAS。" +
                "它是**出站**连接 —— 不受光猫「拦全部入站」影响、不依赖 IPv6、也不需要 DDNS。" +
                "代价约 0.4~0.75 MB/s，只适合增量同步。"
        ))

        val cbTs = CheckBox(this).apply {
            text = "④ Tailscale 官方 —— 已预埋；当前 NAS 端跑不起来（内核没有 TUN）"
            isChecked = prefs.chanTsEnabled
        }
        box.addView(cbTs)
        val etTsBase = field("   基址（如 https://nas.xxxx.ts.net:5000/pool0/data）", prefs.chanTsBase)

        val cbHs = CheckBox(this).apply {
            text = "⑤ Headscale 自建 —— 已预埋；同样受 NAS 无 TUN 限制"
            isChecked = prefs.chanHsEnabled
        }
        box.addView(cbHs)
        val etHsBase = field("   基址（自建隧道里 NAS 的地址）", prefs.chanHsBase)
        val etHsCtl = field("   自建 control server（备忘，App 不直连它）", prefs.chanHsControlUrl)
        box.addView(hint(
            "④⑤ 的前提：手机上装好对应客户端并已连上。App 只把它当一张普通网卡用" +
                "（100.x.y.z 或 MagicDNS 名），因此零额外依赖、零密钥落盘。" +
                "⚠️ 但小米 NAS 内核既没有 tun 模块、也没编译进内核，tailscaled 起不来 —— " +
                "这两条留作预埋，等换设备或内核支持后打开即可。"
        ))

        // ================= 同步时机 =================
        box.addView(sectionTitle("同步时机"))
        val cbRealtime = CheckBox(this).apply {
            text = "实时监听（拍完一会儿就同步；会常驻一条通知）"
            isChecked = prefs.realtimeListen
        }
        box.addView(cbRealtime)
        val ignoringBattery = runCatching {
            (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .isIgnoringBatteryOptimizations(packageName)
        }.getOrDefault(false)
        val cbBattery = CheckBox(this).apply {
            text = "关闭电池优化（强烈建议：这是 Android 12+ 允许后台自动拉起服务的关键豁免）"
            isChecked = ignoringBattery
        }
        box.addView(cbBattery)
        val cbPhotoGate = CheckBox(this).apply {
            text = "照片：只在「充电 + WiFi」时上传（取消勾选 = 照片实时上传）"
            isChecked = prefs.photoRequireChargingWifi
        }
        box.addView(cbPhotoGate)
        val cbVideoGate = CheckBox(this).apply {
            text = "视频：只在「充电 + WiFi」时上传（取消勾选 = 视频实时上传）"
            isChecked = prefs.videoRequireChargingWifi
        }
        box.addView(cbVideoGate)
        box.addView(hint("勾选 = 等到充电 + WiFi 才同步（省电省流量）；取消 = 该类型一有新增就立刻上传。"))

        // ================= 删除同步 =================
        box.addView(sectionTitle("删除同步"))
        box.addView(hint("在手机相册里删掉照片后，NAS 上那份备份怎么处理。无论哪种都只移进 NAS 回收站（可还原），绝不硬删。"))
        val rbDelAsk = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "弹窗问我（默认，不问就不动 NAS）"
        }
        val rbDelAuto = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "直接移进 NAS 回收站，不打扰"
        }
        val rbDelOff = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "不同步（NAS 备份原样保留）"
        }
        box.addView(android.widget.RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(rbDelAsk); addView(rbDelAuto); addView(rbDelOff)
            when (prefs.deleteSyncMode) {
                "auto" -> rbDelAuto.isChecked = true
                "off" -> rbDelOff.isChecked = true
                else -> rbDelAsk.isChecked = true
            }
        })
        val cbOverlay = CheckBox(this).apply {
            text = "允许悬浮窗提示（不勾则改用通知，功能一样）"
            isChecked = DeleteAskUi.overlayGranted(this@MainActivity)
        }
        box.addView(cbOverlay)
        box.addView(hint("悬浮卡片会浮在相册上方问一句，比通知显眼。勾上会跳系统授权页。"))

        box.addView(android.widget.TextView(this).apply {
            text = "\n反过来：在 NAS 上删掉照片时，手机里那份怎么办"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(4))
        })
        box.addView(hint("电脑上挂 WebDAV 盘删了照片，手机会定期扫一遍归档树。本地已是降级小图的可自动清；本地还是原图的只提示，绝不自动删（那是仅存的完整副本）。"))
        val rbRemAsk = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "弹窗问我（默认，不问就不动手机）"
        }
        val rbRemAuto = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "自动删（仅限本地已是降级小图的，原图仍只提示）"
        }
        val rbRemOff = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "不同步（手机上一律保留）"
        }
        box.addView(android.widget.RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(rbRemAsk); addView(rbRemAuto); addView(rbRemOff)
            when (prefs.remoteDeleteSyncMode) {
                "auto" -> rbRemAuto.isChecked = true
                "off" -> rbRemOff.isChecked = true
                else -> rbRemAsk.isChecked = true
            }
        })

        // ================= 降级时机 =================
        box.addView(sectionTitle("降级时机"))
        box.addView(hint("备份到 NAS 之后，本地原图保留多久才换成小版本。（只影响降级，备份始终会做）"))
        val pickPhoto = delayPickerRow(
            box, "照片", prefs.photoDowngradeDelayDays, isVideo = false
        )
        val pickVideo = delayPickerRow(
            box, "视频", prefs.videoDowngradeDelayDays, isVideo = true
        )

        // ================= 已恢复的原图 =================
        box.addView(sectionTitle("已恢复的原图"))
        box.addView(
            hint(
                "你在相册里点过「恢复原图」的照片，默认从此不再被降级（本地常驻原图）。" +
                    "若希望过一段时间后重新交给自动策略省空间，在这里放开。"
            )
        )
        val cbRestoreRe = CheckBox(this).apply {
            text = "恢复后允许再次降级"
            isChecked = prefs.restoreRedowngrade
        }
        box.addView(cbRestoreRe)
        val restoreDayOptions = listOf(3, 7, 15, 30)
        val rgRestoreDays = android.widget.RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val rbRestoreDays = restoreDayOptions.map { d ->
            android.widget.RadioButton(this).apply {
                id = android.view.View.generateViewId()
                text = "$d 天"
            }.also { rgRestoreDays.addView(it) }
        }
        // 老配置里可能是四档之外的天数（比如以后手改过），找不到就默认落在 7 天
        val pickedIdx = restoreDayOptions.indexOf(prefs.restoreRedowngradeDays)
        rbRestoreDays[if (pickedIdx >= 0) pickedIdx else 1].isChecked = true
        box.addView(
            TextView(this).apply {
                text = "「恢复原图」之后，隔多久重新纳入自动降级"
                textSize = 14f
                setPadding(0, dp(8), 0, 0)
            }
        )
        box.addView(rgRestoreDays)
        // 总开关关掉时天数行置灰 —— 否则用户会以为"选了 3 天就生效"
        val syncRestoreRow = {
            val on = cbRestoreRe.isChecked
            rgRestoreDays.alpha = if (on) 1f else 0.35f
            rbRestoreDays.forEach { it.isEnabled = on }
        }
        cbRestoreRe.setOnCheckedChangeListener { _, _ -> syncRestoreRow() }
        syncRestoreRow()

        // ================= 降级门槛 =================
        box.addView(sectionTitle("降级门槛"))
        box.addView(hint("满足以下条件的才降级；不满足的只备份、本地原样保留。"))
        val etSize = field("不小于这个体积才降级（KB）", prefs.minSizeKb.toString())
        val etEdge = field("预览长边（像素）", prefs.previewLongEdge.toString())
        val etQ = field("JPEG 质量（50–100）", prefs.previewQuality.toString())
        val cbUltraHdr = CheckBox(this).apply {
            text = "Ultra HDR 照片也降级（原图含 HDR 存在 NAS）"
            isChecked = prefs.ultraHdrDowngrade
        }
        box.addView(cbUltraHdr)
        val cbMotion = CheckBox(this).apply {
            text = "动态照片降级但保留「动态」（仍可长按播放）"
            isChecked = prefs.motionPhotoDowngrade
        }
        box.addView(cbMotion)

        // ================= 视频 =================
        box.addView(sectionTitle("视频"))
        val cbVideo = CheckBox(this).apply {
            text = "处理视频"
            isChecked = prefs.videoEnabled
        }
        box.addView(cbVideo)
        val etVBr = field("码率上限 kbps（画质主要靠它，与分辨率无关）", prefs.videoBitrateKbps.toString())
        val etVMb = field("单个视频体积上限 MB（iCloud 为 100）", prefs.videoMaxMb.toString())

        // ================= 范围 =================
        box.addView(sectionTitle("处理范围"))

        // ---- 保护相册（永不降级）----
        // 取值时机：**点「保存」时才写回 prefs**。中途只改这份局部变量，
        // 用户点「取消」就全部丢弃 —— 与其它设置项的行为保持一致。
        // ⚠️ `prefs.protectedBuckets` 是 SharedPreferences 的 StringSet，
        //    文档明确要求**不得修改它的返回值** ⇒ 选择器里一律用 `toMutableSet()` 拷一份。
        var protectedNow: Set<String> = prefs.protectedBuckets
        val tvProtected = TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
        }
        fun refreshProtectedLabel() {
            tvProtected.text = if (protectedNow.isEmpty()) {
                "当前：无（所有相册都会按策略降级）"
            } else {
                "当前 ${protectedNow.size} 个：${protectedNow.sorted().joinToString("、")}"
            }
        }
        refreshProtectedLabel()
        box.addView(Button(this).apply {
            text = "管理保护相册"
            setOnClickListener {
                showProtectedBucketsPicker(protectedNow) { picked ->
                    protectedNow = picked
                    refreshProtectedLabel()
                }
            }
        })
        box.addView(tvProtected)
        box.addView(hint(
            "保护相册里的内容**永不降级** —— 本地一直保留原图（NAS 侧照常备份）。" +
                "适合证件、合同、宝宝照片这类「必须随时在手边」的东西。" +
                "⚠️ 别把相机主相册加进来 —— 那等于关掉整个降级功能。"
        ))

        val etOnly = field("只处理这个相册（留空 = 全部）", prefs.onlyBucket)
        val etBatch = field("单轮最多处理个数（0 = 不限）", prefs.maxBatch.toString())
        box.addView(hint("「只处理这个相册」可以用来安全试跑；后台任务另有 30 个的硬上限。"))

        // ---- 包一层可滚动的容器（关键）----
        val scroll = MaxHeightScrollView(this).apply {
            maxHeightPx = (resources.displayMetrics.heightPixels * 0.62f).toInt()
            isVerticalScrollBarEnabled = true
            // 常驻显示滚动条：默认是"滚动时才淡入、停下就淡出"，
            // 用户会以为"没有滚动条、滚不动"。设置项多，常显更有指示性。
            isScrollbarFadingEnabled = false
            scrollBarStyle = android.view.View.SCROLLBARS_INSIDE_INSET
            isFillViewport = false
            addView(
                box,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle("设置")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                // 留空就是留空，不塞回落值：塞了的话用户在设置里清空地址、
                // 点保存之后又会被静默改回某个写死的主机，比留空难排查得多。
                prefs.webdavBase = etBase.text.toString().trim()
                prefs.user = etUser.text.toString().trim()
                prefs.password = etPass.text.toString()
                // ⚠️ 回落值必须与 `Prefs.remoteRoot` 的默认值一致。
                //    这里原来写的是旧归档根 `OtherSpace/NasPhoto归档` —— 用户把输入框清空后点保存，
                //    归档根会被**静默重置**回一个 NAS 上已经不存在（09-17 已清空）的目录：
                //    后果是 App 认为「库里所有记录在 NAS 上都没有」⇒ 触发大批量「NAS 删了」判定
                //    （会被熔断拦下，但会持续告警）。改归档根要同时改的地方又多了一处。
                prefs.remoteRoot = etRoot.text.toString().trim().ifEmpty { "WorkSpace/NasPhoto归档" }
                prefs.allowSelfSigned = cbSelfSigned.isChecked
                prefs.verifyReadBack = cbVerify.isChecked
                // ---- 网络通道 ----
                prefs.netPolicy = when {
                    rbPolWifi.isChecked -> "any_wifi"
                    rbPolAny.isChecked -> "any_network"
                    else -> "lan_only"
                }
                prefs.chanLanEnabled = cbLan.isChecked
                prefs.chanLanBase = etLanBase.text.toString().trim()
                prefs.chanV6Enabled = cbV6.isChecked
                prefs.chanV6Eui64 = etV6Eui.text.toString().trim()
                prefs.chanV6DhcpTail = etV6Dhcp.text.toString().trim()
                prefs.chanV6ManualAddr = etV6Manual.text.toString().trim()
                prefs.chanFrpEnabled = cbFrp.isChecked
                prefs.chanFrpBase = etFrpBase.text.toString().trim()
                prefs.chanTsEnabled = cbTs.isChecked
                prefs.chanTsBase = etTsBase.text.toString().trim()
                prefs.chanHsEnabled = cbHs.isChecked
                prefs.chanHsBase = etHsBase.text.toString().trim()
                prefs.chanHsControlUrl = etHsCtl.text.toString().trim()
                prefs.deleteSyncMode = when {
                    rbDelAuto.isChecked -> "auto"
                    rbDelOff.isChecked -> "off"
                    else -> "ask"
                }
                prefs.remoteDeleteSyncMode = when {
                    rbRemAuto.isChecked -> "auto"
                    rbRemOff.isChecked -> "off"
                    else -> "ask"
                }
                if (cbOverlay.isChecked && !DeleteAskUi.overlayGranted(this)) pendingOverlayRequest = true
                // 降级时机：照片 / 视频分开
                pickPhoto.saveTo(prefs)
                pickVideo.saveTo(prefs)
                // 已恢复的原图：是否允许再次降级 + 宽限天数
                prefs.restoreRedowngrade = cbRestoreRe.isChecked
                restoreDayOptions.getOrNull(rbRestoreDays.indexOfFirst { it.isChecked })
                    ?.let { prefs.restoreRedowngradeDays = it }
                prefs.minSizeKb = etSize.text.toString().trim().toIntOrNull() ?: 300
                prefs.previewLongEdge = etEdge.text.toString().trim().toIntOrNull() ?: 2560
                prefs.previewQuality = (etQ.text.toString().trim().toIntOrNull() ?: 90)
                    .coerceIn(50, 100)
                prefs.onlyBucket = etOnly.text.toString().trim()
                prefs.maxBatch = etBatch.text.toString().trim().toIntOrNull() ?: 0
                // 保护相册（选择器里攒的，取消时自动丢弃）
                prefs.protectedBuckets = protectedNow
                prefs.videoEnabled = cbVideo.isChecked
                prefs.realtimeListen = cbRealtime.isChecked
                prefs.photoRequireChargingWifi = cbPhotoGate.isChecked
                prefs.videoRequireChargingWifi = cbVideoGate.isChecked
                prefs.videoBitrateKbps = etVBr.text.toString().trim().toIntOrNull() ?: 6000
                prefs.videoMaxMb = (etVMb.text.toString().trim().toIntOrNull() ?: 100)
                    .coerceIn(10, 4000)
                prefs.ultraHdrDowngrade = cbUltraHdr.isChecked
                prefs.motionPhotoDowngrade = cbMotion.isChecked
                // 开关变了要立刻生效：兜底任务的约束跟着开关走；实时监听服务按总开关起停
                cn.dsr213.nasphoto.service.SyncScheduler.ensureScheduled(this)
                if (prefs.realtimeListen) {
                    cn.dsr213.nasphoto.service.SyncService.start(this)
                    cn.dsr213.nasphoto.service.SyncScheduler.ensureContentTrigger(this)
                } else {
                    cn.dsr213.nasphoto.service.SyncService.stop(this)
                    cn.dsr213.nasphoto.service.SyncScheduler.cancelContentTrigger(this)
                }
                if (cbBattery.isChecked) askIgnoreBatteryOptimizations()
                refreshStats()
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                // 通道配置改完立刻验一遍 —— 否则填对没有只能等下次同步才知道
                probeChannels()
            }
            .setNegativeButton("取消", null)
            .create()

        // 对话框关掉之后再跳系统授权页 —— 否则两个界面会互相顶掉
        dlg.setOnDismissListener {
            if (pendingOverlayRequest) {
                pendingOverlayRequest = false
                runCatching { startActivity(DeleteAskUi.overlaySettingsIntent(this)) }
                    .onFailure {
                        Toast.makeText(this, "打不开悬浮窗设置页，请到系统设置里手动授权", Toast.LENGTH_LONG).show()
                    }
            }
        }

        dlg.setOnShowListener {
            // 阔折叠展开后宽 600dp+，不限宽的话输入框会被拉成一条横线
            val w = minOf(
                resources.displayMetrics.widthPixels - dp(32),
                dp(520)
            )
            dlg.window?.setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dlg.show()
    }

}
