package cn.dsr213.nasphoto.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import cn.dsr213.nasphoto.data.SyncLog

/**
 * **通道②：JobScheduler 内容触发器** —— 系统代管的"相册一变就叫醒我"。
 *
 * 它存在的意义是**冗余**：
 * [SyncService] 那套常驻前台服务快（~5-10s），但会被系统杀、被国产 ROM 冻结。
 * 而内容触发器是**系统在框架层盯着 MediaStore**，我们的进程死没死都不影响它被唤起 ——
 * 虽然慢一点（~10s–2min），但**几乎不会失效**。
 *
 * 两条通道共用 [RealtimeRunner] 与 [SyncGate]，所以同时醒来也不会互相踩。
 *
 * ## 关于"顺手把常驻服务救活"
 * Android 12+ 禁止从后台启动前台服务，豁免清单里**没有** JobService。
 * 但豁免清单里有两条我们够得着的：
 * - **用户关闭了本 App 的电池优化**
 * - **App 持有 SYSTEM_ALERT_WINDOW（悬浮窗）权限**
 *
 * 所以 [SyncService.startIfAllowed] 会先判断这两条，满足才尝试拉起；
 * 不满足就只是本通道自己把活干掉（不影响功能，只是少了一条快速通道）。
 */
class MediaTriggerJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        // 每次新建作用域：JobService 实例会被复用，不能把上一个已 cancel 的作用域带过来
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        SyncLog.init(this)
        scope.launch {
            try {
                SyncLog.add("·触发器  被系统唤起（检测到相册变化）")
                // ① 有机会就把常驻监听救回来（不满足豁免条件时会静默失败）
                SyncService.startIfAllowed(this@MediaTriggerJobService)
                // ② 自己直接干一轮 —— 不依赖常驻服务是否活着
                if (SyncGate.tryEnter()) {
                    try {
                        RealtimeRunner.runOnce(this@MediaTriggerJobService)
                    } finally {
                        SyncGate.leave()
                    }
                } else {
                    SyncLog.add("·等待  触发器：已有同步在跑，本次跳过")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "触发任务异常", t)
            } finally {
                // 内容触发型 job 是**一次性**的：传 false 的话跑完就被系统移除
                //（dumpsys 里连注册都查不到），之后**永远不再触发** → 所以正常情况下要传 true。
                //
                // 但"重排"必须限速：实测同时从 jobFinished(true) / 显式 rearm / onStopJob
                // 三处要求重排时，系统会立刻再派发 → 再重排 → 紧循环，30 秒被唤起 429 次。
                val prefs = cn.dsr213.nasphoto.data.Prefs(this@MediaTriggerJobService)
                val now = System.currentTimeMillis()
                val tooSoon = now - prefs.triggerLastDispatchAt < STORM_WINDOW_MS
                prefs.triggerLastDispatchAt = now
                if (tooSoon) {
                    SyncLog.add(
                        "·触发器  过密（距上次 <${STORM_WINDOW_MS / 1000}s），本次不重排；" +
                            "改由「常驻服务启动 / 每 6h 兜底 / 开机」重新装备"
                    )
                    jobFinished(params, false)
                } else {
                    jobFinished(params, true)
                }
                scope.cancel()
            }
        }
        return true // 告诉系统"还没干完，等我调 jobFinished"
    }

    /**
     * 被系统中断（超时/约束不满足）时**不要**请求重排 —— 返回 true 会立刻再派发，
     * 与 `jobFinished(true)` 叠加就变成触发风暴（实测 30s 429 次）。
     * 重新装备交给常驻服务 / 6h 兜底 / 开机这三个时机。
     */
    override fun onStopJob(params: JobParameters?): Boolean {
        Log.i(TAG, "触发任务被系统中断（不再请求重排，避免风暴）")
        return false
    }

    private companion object {
        const val TAG = "NasPhotoTrigger"

        /** 距上次派发小于这个间隔就认为"过于密集"，不再重排（防触发风暴） */
        const val STORM_WINDOW_MS = 15_000L
    }
}
