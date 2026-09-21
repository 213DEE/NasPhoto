package cn.dsr213.nasphoto.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import cn.dsr213.nasphoto.data.SyncLog
import cn.dsr213.nasphoto.engine.OffloadEngine

/**
 * 后台同步 Worker —— 「充电 + WiFi」时自动把相册备份到 NAS 并降级本地。
 *
 * ## 为什么用前台服务
 * 一轮可能要处理 `maxBatch` 个文件（默认 50），几百 MB 的上下行，
 * 普通后台 Worker 会被系统的 10 分钟上限掐掉。提升为前台服务后不受该限制，
 * 而且用户能看见进度、知道它在干活。
 *
 * ## 失败策略
 * - 单张失败：引擎内部自己处理（记日志 + 跳过），不算整轮失败
 * - 整轮抛异常 / 有失败项：返回 `retry()`，交给 WorkManager 按指数退避重试
 * - **任何情况下都不会删本地原图**：母本先传 NAS 并校验 sha256 才降级，
 *   上传失败就跳过，绝不丢数据（这条在引擎里保证，这里只是兜底）
 */
class OffloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // 与界面上的手动按钮互斥，避免两条链路同时写 NAS / DB
        SyncLog.init(applicationContext)
        if (!SyncGate.tryEnter()) {
            SyncLog.add("·等待  定时任务：已有同步在跑，本次跳过")
            return Result.success()
        }
        try {
            setForeground(info("正在同步到 NAS…"))
            SyncLog.add("·开始  定时任务（每 6h 兜底）开始一轮同步")

            val engine = OffloadEngine(applicationContext)
            var lastMsg = ""
            val onLog: (String) -> Unit = { msg: String ->
                lastMsg = msg
                SyncLog.add(msg)
            }
            val r = engine.run(onLog, null, BACKGROUND_BATCH_LIMIT)

            val summary = buildString {
                append("备份 ${r.backedUp} · 降级 ${r.downgraded}")
                if (r.skippedByGate > 0) append(" · 门槛跳过 ${r.skippedByGate}")
                if (r.failed > 0) append(" · 失败 ${r.failed}")
                if (r.bytesSaved > 0) append(" · 释放 ${mb(r.bytesSaved)}")
            }
            SyncLog.add("·本轮  定时任务完成：$summary")
            setForeground(info("已完成：$summary"))

            // ⚠️ 权限被撤销必须**可见**地告知（#103）。系统既不发广播也不回调，
            //    不主动说这一句，用户只会看到"降级 0"，然后以为功能坏了 ——
            //    而真正的原因是设置里那个开关被关掉了，界面上没有任何线索。
            if (r.permissionBlocked) {
                SyncLog.add("!权限  缺少「所有文件访问」权限，本轮未做降级（备份不受影响）")
                notifyAlert(
                    "缺少「所有文件访问」权限",
                    "照片备份照常，但无法降级 —— 为安全起见没有改动你手机上的任何文件。" +
                        "请到「系统设置 → 应用 → NAS照片管家 → 权限」里打开「所有文件访问」。"
                )
            }

            // 回收站的"保留 N 天后彻底删"—— 光有显示没有执行等于没做，这里真去清
            purgeExpiredTrash()

            // ⚠️ 顺序：**先清过期回收站、再扫远端删除**。
            // 反过来的话，这一轮刚被 purge 掉的批次还没从回收站消失，
            // 会被 `trashOrigins` 当成"还在回收站里"而漏判 —— 白等一个周期。
            runCatching { RemoteDeletionWatcher.scanAndHandle(applicationContext) }
                .onFailure { Log.w(TAG, "远端删除扫描失败：${it.message}") }

            return if (r.failed > 0) Result.retry() else Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "同步异常", t)
            return Result.retry()
        } finally {
            SyncGate.leave()
            // 让通知能收起来
            runCatching {
                (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(NOTIF_ID)
            }
        }
    }

    /**
     * 清掉超过保留天数的回收站批次（每天最多跑一次）。
     *
     * 保留天数的**唯一来源是 NAS 上插件写的 `.trash_config.json`** —— App 这边不另存一份，
     * 否则用户在回收站页面改了天数，App 还在按旧值删，那就是拿人数据开玩笑。
     */
    private suspend fun purgeExpiredTrash() {
        runCatching {
            val prefs = cn.dsr213.nasphoto.data.Prefs(applicationContext)
            val now = System.currentTimeMillis()
            if (now - prefs.lastTrashPurgeAt < PURGE_INTERVAL_MS) return
            prefs.lastTrashPurgeAt = now

            val client = OffloadEngine(applicationContext).newClientForDeletion()
            client.connect()
            val r = cn.dsr213.nasphoto.engine.TrashStore(client, prefs.remoteRoot)
                .purgeExpired(now)
            when {
                // ⚠️ 读不到就**必须说出来**。旧实现把"读不到"和"没有东西可删"混成同一件事
                //    （`listEntries` 内部 `?: emptyList()`），安静地报"清理完成" ——
                //    用户永远不会知道"保留 N 天"其实一次都没生效过。
                r.unreadable ->
                    SyncLog.add("!失败  回收站目录读不出来，本次未清理（下轮再试）")

                // ⚠️ 熔断：一个字节都没动，但**必须让人知道**。
                //    自动路径静默停手 = 回收站偷偷涨到占满盘，而且没人会去查。
                r.blockedBatches > 0 -> {
                    SyncLog.add(
                        "!暂停  有 ${r.blockedBatches} 个过期批次超过自动清理上限，未删除任何文件；" +
                            "请到「回收站」页手动确认"
                    )
                    notifyAlert(
                        "回收站自动清理已暂停",
                        "有 ${r.blockedBatches} 个过期批次超过安全上限，为避免误删已停手，" +
                            "没有删除任何文件。请打开「回收站」确认后再清。"
                    )
                }

                else -> {
                    if (r.deleted > 0)
                        SyncLog.add("·回收站  已彻底删除 ${r.deleted} 个过期文件（超过保留天数）")
                    // 「删了一半」单独说 —— 这是最容易被糊过去、也最该被说出来的状态
                    if (r.partialBatches > 0)
                        SyncLog.add(
                            "!删除  有 ${r.partialBatches} 个批次只删掉一部分（其余未动），" +
                                "请在「回收站」页重试"
                        )
                    if (r.badBatches > 0)
                        SyncLog.add("!失败  有 ${r.badBatches} 个过期批次没清干净，下次再试")
                }
            }
        }.onFailure { Log.w(TAG, "清理回收站失败：${it.message}") }
    }

    /**
     * 发一条**用户看得见**的提醒。
     *
     * 独立通知 id —— 前台服务那条是 `setOngoing(true)` 的，共用 id 会被它顶掉。
     *
     * 只用在"自动路径主动停手"这类事件上：这类事**必须**让人知道，
     * 否则就成了一个悄悄恶化的故障（比如回收站因为熔断一直不清理，最后把盘塞满）。
     */
    private fun notifyAlert(title: String, text: String) {
        runCatching {
            ensureChannel(applicationContext)
            val nm = applicationContext
                .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val n = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID_ALERT, n)
        }.onFailure { Log.w(TAG, "发提醒通知失败：${it.message}") }
    }

    private fun info(text: String): ForegroundInfo {
        ensureChannel(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("NAS 照片管家")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, n)
        }
    }

    companion object {
        private const val TAG = "NasPhotoWorker"
        private const val CHANNEL_ID = "nasphoto.sync"
        private const val NOTIF_ID = 0x4E50 // "NP"
        /** 「自动路径主动停手」这类提醒用 —— 与前台服务那条分开，免得互相覆盖 */
        private const val NOTIF_ID_ALERT = 0x4E51 // "NQ"

        /**
         * 后台无人值守单轮硬上限。
         *
         * ⚠️ 为什么不能只信 `Prefs.maxBatch`：配置里 **0 表示"不限"**，
         * 前台手动点一次"不限"没问题，但后台任务真的不限就会跑几小时被系统杀掉，
         * 还会长时间占用前台服务。所以后台这边再压一层。
         *
         * ⚠️ 可见性刻意不是 private：诊断探针 `--es cand 1` 要用**同一个值**模拟
         * "这轮批次实际会取哪 30 个"。探针里硬编码 30 的话，这里一改就悄悄失准，
         * 而失准的诊断比没有诊断更糟（会把饥饿问题藏起来）。
         */
        internal const val BACKGROUND_BATCH_LIMIT = 30

        /** 回收站清理间隔（一天一次足够） */
        private const val PURGE_INTERVAL_MS = 24L * 3600_000L

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "后台同步",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "充电 + WiFi 时自动备份相册到 NAS" }
            )
        }

        private fun mb(b: Long): String = when {
            b >= 1L shl 30 -> String.format("%.1f GB", b / 1073741824.0)
            b >= 1L shl 20 -> String.format("%.1f MB", b / 1048576.0)
            else -> "${b / 1024} KB"
        }
    }
}
