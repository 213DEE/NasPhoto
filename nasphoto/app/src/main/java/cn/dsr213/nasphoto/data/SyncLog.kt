package cn.dsr213.nasphoto.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 同步日志总线 —— App 内「实时日志」的唯一数据源。
 *
 * ## 为什么不能直接往 TextView 写
 * 真正干活的三个通道（[cn.dsr213.nasphoto.service.SyncService] 常驻前台服务、
 * [cn.dsr213.nasphoto.service.MediaTriggerJobService] 内容触发器、
 * WorkManager 的 `OffloadWorker`）**跟界面的生命周期完全无关** ——
 * 界面可能根本没打开，或者已经退到后台/被销毁。
 * 所以日志必须先汇总到一个**进程级**的地方，界面再来订阅：
 *
 * ```
 * SyncService / Trigger / Worker / MainActivity
 *        └──►  SyncLog.add(msg)  ──► ① 环形缓冲（供界面随时取快照）
 *                                 ├─► ② 落盘 filesDir/sync.log（重启后仍能看到）
 *                                 ├─► ③ logcat（tag=NasPhotoLog，便于 adb 抓）
 *                                 └─► ④ 通知已注册的监听者（界面实时刷新）
 * ```
 *
 * 所有三个后台通道与界面都跑在**同一进程**（清单里没给任何组件指定 `android:process`），
 * 所以一个 object 单例就够了，不需要 Binder / 广播那套。
 *
 * 线程安全：多线程同时写（IO 线程跑同步、主线程点按钮），缓冲区与文件都用同一把锁。
 * ⚠️ 通知监听者时**必须在锁外**调用 —— 监听者会去碰 UI，
 *    在锁内调用等于把锁的持有时间传染给界面代码，容易埋死锁。
 */
object SyncLog {

    /** 内存里保留多少行（界面最多展示这么多） */
    const val CAPACITY = 500

    /** 日志文件超过这个大小就滚动截断，避免无限增长 */
    private const val MAX_FILE_BYTES = 512L * 1024

    /** 截断时保留的行数 */
    private const val KEEP_LINES = 200

    private val lock = Any()
    private val buffer = ArrayDeque<String>()

    @Volatile
    private var sink: File? = null

    @Volatile
    private var listener: ((String) -> Unit)? = null

    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** 只在 App 初始化时调一次；重复调用是安全的空操作。 */
    fun init(ctx: Context) {
        if (sink != null) return
        synchronized(lock) {
            if (sink != null) return
            val f = File(ctx.applicationContext.filesDir, "sync.log")
            sink = f
            // 超过上限就滚动：只留最后 KEEP_LINES 行
            runCatching {
                if (f.exists() && f.length() > MAX_FILE_BYTES) {
                    val keep = f.readLines().takeLast(KEEP_LINES)
                    f.writeText(keep.joinToString("\n") + "\n")
                }
            }
            // 上次运行的日志尾巴载入内存，打开 App 就能看到"刚才发生了什么"
            runCatching {
                if (f.exists()) {
                    val lines = f.readLines().filter { it.isNotBlank() }
                    buffer.clear()
                    buffer.addAll(if (lines.size > CAPACITY) lines.takeLast(CAPACITY) else lines)
                }
            }
        }
    }

    /**
     * 记一行。**所有**组件都用这个，不要自己往 TextView 写。
     *
     * @param msg 正文。约定前缀用于一眼看方向（界面顶部有图例）：
     *  - `↑上传` / `↑备份`  本地 → NAS
     *  - `↓恢复`            NAS → 本地
     *  - `·降级`            纯本地处理（体积变小，不上不下）
     *  - `=已存档`          NAS 上已有完好副本，跳过重传
     *  - `!失败`            出错
     */
    fun add(msg: String) {
        val line = "${stamp.format(Date())}  $msg"
        val notify: ((String) -> Unit)?
        synchronized(lock) {
            buffer.addLast(line)
            while (buffer.size > CAPACITY) buffer.removeFirst()
            runCatching { sink?.appendText(line + "\n") }
            notify = listener
        }
        // logcat 也留一份，方便 adb logcat -s NasPhotoLog 抓
        runCatching { Log.i(TAG, line) }
        // ★ 锁外回调，避免把锁带进界面代码
        runCatching { notify?.invoke(line) }
    }

    /** 取当前全部（界面重建时用）。返回的是快照副本。 */
    fun snapshot(): List<String> = synchronized(lock) { buffer.toList() }

    /** 界面 `onStart` 订阅、`onStop` 退订。回调可能在**任意线程**，需要自己 post 到主线程。 */
    fun attach(l: (String) -> Unit) {
        listener = l
    }

    fun detach(l: (String) -> Unit) {
        if (listener === l) listener = null
    }

    /** 清空内存与文件（界面上的"清空日志"按钮）。 */
    fun clear() {
        synchronized(lock) {
            buffer.clear()
            runCatching { sink?.writeText("") }
        }
    }

    private const val TAG = "NasPhotoLog"
}
