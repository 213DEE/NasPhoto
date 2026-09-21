package cn.dsr213.nasphoto.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import cn.dsr213.nasphoto.data.SyncLog
import cn.dsr213.nasphoto.data.Prefs
import cn.dsr213.nasphoto.media.MediaRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * **实时监听服务** —— 「拍完一会儿就自动同步到 NAS」的那一环。
 *
 * ## 为什么是常驻前台服务（而不是 WorkManager）
 * Android 没有「相册新增」这种系统广播；唯一的实时途径是给 `MediaStore` 注册
 * `ContentObserver`，而它**要求进程活着**。
 * 更麻烦的是 Android 12+ **禁止从后台启动前台服务**，所以"收到广播再起服务"这条路是断的
 * —— 只能在 `MainActivity`（前台，允许）、开机（`BOOT_COMPLETED` 是豁免项）这类时机启动。
 *
 * 这不是我拍的脑袋：**小米智能存储（`com.xiaomi.station`）就是这么干的** ——
 * 它的 `XMListenerService` 是一个 `isForeground=true`、`stopIfKilled=false`、
 * 跑在独立进程 `:backup` 里的前台服务（实测连续存活 6 小时以上），
 * 监听变化后发自己的 `MEDIA_CHANGE_BROADCAST` 唤醒同步。
 * 本服务即对标该架构（实现独立，未借用其任何代码）。
 *
 * ## 三个关键设计
 * 1. **增量水位线**：只处理 `_id > 水位线` 的新增媒体。
 *    `ContentObserver` 对相册的**任何**变动都会回调（缩略图生成、别的 App 写入…），
 *    全库扫描会把电耗打上去。
 * 2. **稳定窗口**：相机是**边写边可见**的，刚拍的 4K 视频文件还在持续增长，
 *    这时读会拿到半个文件。所以必须等「文件大小与 MediaStore 记录一致 + 最近若干秒没再被写过」。
 * 3. **门槛按类型分开**：照片/视频各有一个「必须充电 + WiFi」开关，默认都开。
 *    关掉开关的那一类就变成**实时上传**（不挑电量和网络）。
 *
 * ## 省电
 * 通知栏常驻一条低优先级通知；没有新增时完全是空转（一次查询 + 立即返回）。
 * 另外注册了「插上充电器」「网络变化」两个回调，条件刚好满足时会立刻补跑一次。
 */
class SyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: Prefs
    private lateinit var repo: MediaRepo
    private var observer: ContentObserver? = null
    private var debounce: Job? = null
    private var connectivity: ConnectivityManager.NetworkCallback? = null
    private var powerReceiver: android.content.BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        repo = MediaRepo(this)
        SyncLog.init(this)

        ensureChannel(this)
        // 首次启动：把水位线对齐到当前最大 id，避免把整个历史库当"新增"处理。
        // 历史积压由「手动运行 / 6 小时定时」那条链路负责。
        if (prefs.realtimeWatermark <= 0L) {
            val m = runCatching { repo.maxId() }.getOrDefault(0L)
            prefs.realtimeWatermark = m
            Log.i(TAG, "水位线初始化 = $m")
        }

        registerMediaObserver()
        registerDeviceHooks()
        startRemoteScanLoop()
        SyncLog.add("·服务  实时监听已启动（水位线 ${prefs.realtimeWatermark}）")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须在 5 秒内进入前台，否则系统直接 ANR/杀服务
        startForegroundCompat(notifText("实时监听中，有新照片就会同步"))
        // 每次被拉起都补跑一次（可能上次被杀时还有没处理完的）
        scheduleRun(0L)
        // 顺手把第二通道重新装备好（内容触发器被消费掉/因防风暴停用后靠这里恢复）
        runCatching { SyncScheduler.ensureContentTrigger(this) }
        // START_STICKY：被系统杀掉后自动重建 —— 与小米智能存储的 stopIfKilled=false 同效
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observer?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        connectivity?.let { runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } }
        powerReceiver?.let { runCatching { unregisterReceiver(it) } }
        scope.cancel()
        SyncLog.add("·服务  实时监听已停止")
        super.onDestroy()
    }

    // ------------------------------------------------------------ 监听注册

    /** 监听相册（图片 + 视频）变化。去抖后触发一次增量同步。 */
    private fun registerMediaObserver() {
        val h = Handler(Looper.getMainLooper())
        val o = object : ContentObserver(h) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                scheduleRun(RealtimeRunner.DEBOUNCE_MS)
            }
        }
        val cr = contentResolver
        runCatching {
            cr.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o)
            cr.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, o)
        }.onFailure { Log.e(TAG, "注册 ContentObserver 失败", it) }
        observer = o
    }

    /**
     * 插上充电器 / 网络变化时补跑：
     * 这些正是「充电 + WiFi」门槛**刚刚满足**的时刻，此时补跑比死等定时任务体验好得多。
     */
    private fun registerDeviceHooks() {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                scheduleRun(1_500L)
            }
        }
        runCatching {
            registerReceiver(r, IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            })
        }
        powerReceiver = r

        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = scheduleRun(1_500L)
                override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) =
                    scheduleRun(1_500L)
            }
            cm.registerDefaultNetworkCallback(cb)
            connectivity = cb
        }
    }

    // ------------------------------------------------------------ 远端删除扫描

    /**
     * 定时看「NAS 上少了什么」。
     *
     * 为什么要有这条独立的定时：相册**没有任何变化**时 `ContentObserver` 不会回调，
     * 而"用户在电脑上把 NAS 里的照片删了"恰恰跟手机相册无关 —— 只靠相册变动触发的链路
     * 永远等不到这个事件。所以必须自己按时间戳轮询。
     *
     * 成本很低：整棵归档树逐层列目录，实测一百多个文件约 0.6 秒。
     * 真正控制频率的是 [RemoteDeletionWatcher.SCAN_THROTTLE_MS]（这里是它的 2 倍）。
     */
    private fun startRemoteScanLoop() {
        scope.launch {
            while (true) {
                delay(REMOTE_SCAN_INTERVAL_MS)
                runCatching {
                    val n = RemoteDeletionWatcher.scanAndHandle(this@SyncService)
                    if (n > 0) Log.i(TAG, "远端删除扫描：处理 $n 个")
                }.onFailure { Log.w(TAG, "远端删除扫描失败：${it.message}") }
            }
        }
    }

    // ------------------------------------------------------------ 同步主流程

    private fun scheduleRun(delayMs: Long) {
        debounce?.cancel()
        debounce = scope.launch {
            if (delayMs > 0) delay(delayMs)
            runOnce()
        }
    }

    private suspend fun runOnce() {
        // 与界面手动按钮、WorkManager 共用互斥闸
        if (!SyncGate.tryEnter()) {
            SyncLog.add("·等待  已有同步在跑，稍后重试")
            scheduleRun(RealtimeRunner.RETRY_MS)
            return
        }
        try {
            val r = RealtimeRunner.runOnce(this)
            if (r.ran) startForegroundCompat(notifText(idleText()))
            // 还有在写的文件 → 过会儿再看一眼
            if (r.hasUnstable) scheduleRun(RealtimeRunner.RETRY_MS)
        } catch (t: Throwable) {
            Log.e(TAG, "实时同步异常", t)
        } finally {
            SyncGate.leave()
        }
    }


    // ------------------------------------------------------------ 通知

    private fun notifText(s: String) = s

    private fun idleText(): String {
        val g = when {
            !prefs.photoRequireChargingWifi && !prefs.videoRequireChargingWifi -> "照片/视频实时同步"
            !prefs.photoRequireChargingWifi -> "照片实时·视频待充电+WiFi"
            !prefs.videoRequireChargingWifi -> "视频实时·照片待充电+WiFi"
            else -> "等待充电 + WiFi"
        }
        return "实时监听中（$g）"
    }

    private fun startForegroundCompat(text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("NAS 照片管家")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        private const val TAG = "NasPhotoRealtime"
        private const val CHANNEL_ID = "nasphoto.realtime"
        private const val NOTIF_ID = 0x4E52 // "NR"

        /**
         * 远端删除扫描周期。取 20 分钟 = 节流值（10 分钟）的 2 倍，
         * 保证每次循环都能真的扫一次（而不是被自己的节流挡掉）。
         */
        private const val REMOTE_SCAN_INTERVAL_MS = 20 * 60_000L





        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "实时同步",
                    NotificationManager.IMPORTANCE_MIN
                ).apply { description = "监听相册变化，新照片/新视频自动备份到 NAS" }
            )
        }

        /** 启动实时监听（幂等）。Android 12+ 只允许从**前台**或开机时启动前台服务。 */
        fun start(ctx: Context) {
            if (!Prefs(ctx).realtimeListen) {
                Log.i(TAG, "实时监听开关关闭，不启动")
                return
            }
            runCatching {
                ctx.startForegroundService(Intent(ctx, SyncService::class.java))
            }.onFailure { Log.e(TAG, "启动实时监听失败", it) }
        }

        /**
         * **只有在确定合法时才启动** —— 供后台调用方（如 MediaTriggerJobService）使用。
         *
         * Android 12+「后台启动前台服务」的豁免清单里，我们能靠的只有两条：
         * - **用户已对本 App 关闭电池优化**（`isIgnoringBatteryOptimizations`）
         * - **App 持有 SYSTEM_ALERT_WINDOW（悬浮窗）权限**（`Settings.canDrawOverlays`）
         *
         * 都不满足就静默跳过：功能不受影响，只是少一条"快速通道"，
         * 由调用方自己把这一轮干完。
         */
        fun startIfAllowed(ctx: Context) {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val batteryOk = runCatching {
                pm.isIgnoringBatteryOptimizations(ctx.packageName)
            }.getOrDefault(false)
            val overlayOk = runCatching {
                android.provider.Settings.canDrawOverlays(ctx)
            }.getOrDefault(false)
            if (batteryOk || overlayOk) {
                SyncLog.add("·服务  后台启动条件满足（电池优化已关=$batteryOk / 悬浮窗=$overlayOk），拉起常驻监听")
                start(ctx)
            } else {
                SyncLog.add("·服务  无后台启动前台服务的权限（去设置里关掉电池优化可修复），本轮由调用方自行处理")
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, SyncService::class.java)) }
        }
    }
}
