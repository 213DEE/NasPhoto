package cn.dsr213.nasphoto.service

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cn.dsr213.nasphoto.data.Prefs
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程内互斥闸。
 *
 * 后台 Worker 与界面上的「开始上传并卸载」按钮**都是写 NAS + DB 的长任务**，
 * 同时跑会互相踩（WebDAV 连接、Room 事务、临时文件）。所以两边共用同一把闸。
 */
object SyncGate {
    private val busy = AtomicBoolean(false)

    /**
     * 谁拿到的、从哪儿拿的、拿了多久 —— **只为了出事时能一眼定位**。
     *
     * 为什么需要它：日志里只会出现「已有同步在跑」这一句，它只说"门被占了"，
     * 不说"被谁占了"。实测排查时看到门一直占着、日志全静默，只能靠猜 ——
     * 于是加了这行"占门者签名"。代价是一次 `stackTrace` 取值（拿门时才有，可忽略）。
     */
    @Volatile
    private var holder: String? = null

    @Volatile
    private var heldSince: Long = 0L

    fun tryEnter(): Boolean {
        val ok = busy.compareAndSet(false, true)
        if (ok) {
            holder = Thread.currentThread().stackTrace
                .drop(1)
                .take(6)
                .joinToString(" ← ") { it.toString().substringAfter("(") }
            heldSince = System.currentTimeMillis()
        }
        return ok
    }

    fun leave() {
        busy.set(false)
        holder = null
        heldSince = 0L
    }

    val isBusy: Boolean get() = busy.get()

    /** 人话描述"当前谁占着门、占了多久"，空闲时返回 null */
    fun describe(): String? {
        if (!busy.get()) return null
        val held = System.currentTimeMillis() - heldSince
        return "SyncGate 被占用 ${held}ms，占门者：${holder ?: "（未知，可能是 leave 未配对）"}"
    }
}

/**
 * 同步调度：**让它在后台自己跑，不用记着打开 App**。
 *
 * ## 触发策略
 * - **周期任务**：每 6 小时一次（WorkManager 最短周期是 15 分钟，但对相册同步没必要那么勤）
 * - **一次性任务**：每次打开 App 时补一次「尽快跑」
 *
 * ## 约束（对齐用户要求：「充电且连上 WiFi 才转码上传」）
 * - `setRequiresCharging(true)` —— 只在充电时跑
 * - `setRequiredNetworkType(UNMETERED)` —— 只在非计费网络（即 WiFi）跑
 *
 * 两个约束都满足 WorkManager 才会唤起 Worker，所以**不会半夜用流量跑**。
 *
 * ## 单轮有上限
 * `Prefs.maxBatch`（默认 50）限制每轮处理多少个，
 * 避免一次跑几小时被系统干掉，也让进度可预期。
 *
 * ## ⚠️ HyperOS / 国产 ROM 的额外注意
 * 系统可能限制后台活动。WorkManager 是系统认可的调度器，一般没问题，
 * 但建议把本 App 加入「自启动白名单 / 电池不优化」，否则可能被冻结。
 * 界面上的「当前状态」会显示调度是否已登记。
 */
object SyncScheduler {

    private const val TAG = "NasPhotoSync"

    const val PERIODIC_WORK = "nasphoto.sync.periodic"
    const val ONESHOT_WORK = "nasphoto.sync.oneshot"

    private const val PERIOD_HOURS = 6L

    /** 内容触发器的 JobId（0x4E50 段是本 App 自用） */
    private const val TRIGGER_JOB_ID = 0x4E53

    /** 登记去抖窗口：这么短时间内重复要求登记就跳过 */
    private const val ARM_DEBOUNCE_MS = 5_000L

    @Volatile
    private var lastTriggerArmAt = 0L

    /**
     * 兜底任务（每 6 小时）的约束，**跟随用户的开关**：
     * - 照片、视频**都**要求「充电 + WiFi」→ 任务本身也只在充电 + WiFi 时被唤起（最省电）
     * - 只要有一类被设成"实时"→ 任务不再设约束，交给引擎按类型逐条判断
     *
     * 实时性由 [SyncService] 负责，这个周期任务只是**兜底**（漏网、服务被杀、Doze 延迟）。
     */
    private fun constraints(ctx: Context): Constraints {
        val p = Prefs(ctx)
        val b = Constraints.Builder()
        if (p.photoRequireChargingWifi && p.videoRequireChargingWifi) {
            b.setRequiresCharging(true)
            b.setRequiredNetworkType(NetworkType.UNMETERED)
        }
        return b.build()
    }

    /**
     * 登记周期任务。**幂等** —— 用 UPDATE 策略，改了周期/约束会原地更新，
     * 不会堆出多个任务。
     */
    fun ensureScheduled(ctx: Context) {
        try {
            val req = PeriodicWorkRequestBuilder<OffloadWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints(ctx))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                req
            )
            Log.i(TAG, "周期兜底任务已登记：每 ${PERIOD_HOURS}h")
        } catch (t: Throwable) {
            Log.e(TAG, "登记周期任务失败", t)
        }
    }

    /**
     * 补一次「尽快跑」。`KEEP` 策略保证不会把已经排队的那次顶掉。
     * 约束没满足时它就一直待命，等插上电 + 连上 WiFi 才跑 —— 这正是想要的行为。
     */
    fun kickOnce(ctx: Context) {
        try {
            val req = OneTimeWorkRequestBuilder<OffloadWorker>()
                .setConstraints(constraints(ctx))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                ONESHOT_WORK,
                ExistingWorkPolicy.REPLACE,
                req
            )
            Log.i(TAG, "已排队一次即时同步")
        } catch (t: Throwable) {
            Log.e(TAG, "排队即时同步失败", t)
        }
    }

    // ------------------------------------------------------------ 通道②：内容触发器

    /**
     * 登记 **JobScheduler 内容触发器** —— 系统在框架层盯着 MediaStore，
     * 相册一变就唤起 [MediaTriggerJobService]（**不要求我们的进程活着**）。
     *
     * 这是 [SyncService] 之外的第二条通道，用于兜底"常驻服务被系统杀掉/被 ROM 冻结"的情况。
     * 重启后由 `BootReceiver`（`BOOT_COMPLETED`）重新登记 —— 注意**不能**用 `setPersisted(true)`，
     * 见下面那行注释里的实测异常。
     *
     * 约束跟随用户开关：两类都要求"充电 + WiFi"时给任务也加上同样约束（最省电）；
     * 只要有一类设成实时，任务就不设约束 —— 具体挡不挡由 [RealtimeRunner] 逐条判断。
     */
    fun ensureContentTrigger(ctx: Context) = scheduleTrigger(ctx, rearming = false)

    /**
     * 供 [MediaTriggerJobService] 在**自己刚跑完**时重新登记。
     *
     * 不能走 [ensureContentTrigger]：它会先 `cancel()`，
     * 而此刻我们正在这个 job 里，取消自己会把流程打断。
     * `schedule()` 对同 id 的 job 是替换语义，直接排即可。
     */
    fun rearmContentTrigger(ctx: Context) = scheduleTrigger(ctx, rearming = true)

    private fun scheduleTrigger(ctx: Context, rearming: Boolean) {
        // 去重：App 启动时 MainActivity / SyncService / BootReceiver 可能同时要求登记，
        // 而每次登记都是 cancel + schedule —— 短时间内反复重排有可能让系统立刻再派发。
        val now = System.currentTimeMillis()
        if (now - lastTriggerArmAt < ARM_DEBOUNCE_MS) {
            Log.i(TAG, "刚刚登记过，跳过重复登记")
            return
        }
        lastTriggerArmAt = now
        try {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (!rearming) js.cancel(TRIGGER_JOB_ID) // 约束可能变了，先取消再重排（幂等）
            val b = JobInfo.Builder(
                TRIGGER_JOB_ID,
                ComponentName(ctx, MediaTriggerJobService::class.java)
            )
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                    )
                )
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                    )
                )
                .setTriggerContentUpdateDelay(5_000)   // 去抖：变化后至少等 5s 再报
                .setTriggerContentMaxDelay(120_000)    // 兜底：最多憋 2 分钟必报一次
            // ⚠️ 绝对不要加 .setPersisted(true)：
            //    实测会抛 IllegalArgumentException: Can't call addTriggerContentUri() on a persisted job
            //    （内容 URI 触发器与"持久化 Job"互斥）。
            //    重启后的重新登记交给 BootReceiver 的 BOOT_COMPLETED —— 我们本来就是这么做的。
            val p = Prefs(ctx)
            if (p.photoRequireChargingWifi && p.videoRequireChargingWifi) {
                b.setRequiresCharging(true)
                b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            }
            val r = js.schedule(b.build())
            Log.i(TAG, "内容触发器已登记（result=$r，5s 去抖 / 最长 2 分钟）")
        } catch (t: Throwable) {
            Log.e(TAG, "登记内容触发器失败", t)
        }
    }

    fun cancelContentTrigger(ctx: Context) {
        runCatching {
            (ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler)
                .cancel(TRIGGER_JOB_ID)
        }
    }

    /** 供界面展示：调度当前是什么状态 */
    fun describe(ctx: Context): String = try {
        val wm = WorkManager.getInstance(ctx)
        val infos = wm.getWorkInfosForUniqueWork(PERIODIC_WORK).get()
        val i = infos.firstOrNull()
        when {
            i == null -> "未登记"
            i.state == WorkInfo.State.ENQUEUED -> "已登记（等待 充电+WiFi）"
            i.state == WorkInfo.State.RUNNING -> "正在同步"
            i.state.name == "BLOCKED" -> "已登记（等待前置任务）"
            else -> "已登记（${i.state}）"
        }
    } catch (t: Throwable) {
        "读取失败：${t.javaClass.simpleName}"
    }
}
