package cn.dsr213.nasphoto.service

import android.content.Context
import android.util.Log
import cn.dsr213.nasphoto.data.SyncLog
import cn.dsr213.nasphoto.data.Prefs
import cn.dsr213.nasphoto.device.DeviceState
import cn.dsr213.nasphoto.engine.OffloadEngine
import cn.dsr213.nasphoto.engine.PathReconciler
import cn.dsr213.nasphoto.engine.fmt
import cn.dsr213.nasphoto.media.MediaItem
import cn.dsr213.nasphoto.media.MediaRepo
import java.io.File

/**
 * **实时同步的核心逻辑**：增量取新、稳定窗口、门槛过滤、跑引擎、推进水位线。
 *
 * 抽出来是因为它有**两个调用方**，必须共用同一套判定，否则两边会互相踩：
 * - [SyncService]：常驻前台服务 + `ContentObserver`（快，~5-10s）
 * - [MediaTriggerJobService]：JobScheduler 内容触发器（系统代管，**常驻服务被杀时仍能跑**）
 *
 * 两者靠 [SyncGate] 互斥。
 */
object RealtimeRunner {

    private const val TAG = "NasPhotoRealtime"

    /** 单轮硬上限：实时场景本来就是零星的，给个保险 */
    const val BATCH_LIMIT = 20

    /** 变化去抖：连拍会连发多次回调，攒一下再处理 */
    const val DEBOUNCE_MS = 4_000L

    /** 发现"还在写"的文件后，过多久再看一眼 */
    const val RETRY_MS = 8_000L

    /** 文件最近多久没被写过，才认为写完了 */
    private const val STABLE_MS = 5_000L

    data class Outcome(
        /** 本轮新增（`_id` 大于水位线）的个数 */
        val fresh: Int = 0,
        /** 其中已"写稳定"、可以处理的个数 */
        val pending: Int = 0,
        /** 实际交给引擎处理的个数 */
        val todo: Int = 0,
        /** 还在写、这轮先跳过的个数 */
        val unstable: Int = 0,
        /** 被「充电 + WiFi」开关挡下的个数 */
        val blocked: Boolean = false,
        /** 是否真的跑了一轮引擎 */
        val ran: Boolean = false,
        val backedUp: Int = 0,
        val downgraded: Int = 0,
        val failed: Int = 0,
        val bytesSaved: Long = 0L
    ) {
        val hasUnstable: Boolean get() = unstable > 0
    }

    /**
     * 跑一轮。
     *
     * @param onlyWhenGateOpen true 时若「充电 + WiFi」不满足就直接返回（不消耗配额）——
     *   JobScheduler 那条通道用它，因为它的约束可能并没有带上充电/网络。
     */
    suspend fun runOnce(ctx: Context, onlyWhenGateOpen: Boolean = false): Outcome {
        val prefs = Prefs(ctx)
        val repo = MediaRepo(ctx)

        // ---- ⓪ 路径对账：同一条照片换了路径（改名 / 移动 / 大小写）先把记录跟过去 ----
        //
        // ⚠️ 必须在**删除判定之前**，而且两条线都靠它：
        //    · 旧路径不跟过去 ⇒ 会被读成"手机侧删除"，可能把真母本搬进回收站
        //    · 新路径不跟过去 ⇒ 会被读成"从没备份过"，重新传一份 ⇒ NAS 上多出假母本
        //    纯本地操作（只读媒体库 + 改数据库），不联网、不传字节，见 `PathReconciler`。
        runCatching { PathReconciler.reconcile(ctx) }
            .onFailure { Log.w(TAG, "路径对账失败：${it.message}") }

        // ---- 删除同步：手机侧删了什么？----
        //
        // ⚠️ 必须放在下面那句"没有新文件就早退"**之前**。
        //    用户删照片的场景**本来就没有新增**，放在后面等于这条逻辑永远轮不到。
        runCatching {
            // scan 返回 null = 本轮放弃判定（媒体库没读全），绝不能当成"没有删除"
            val suspects = DeletionWatcher.scan(ctx)
            if (!suspects.isNullOrEmpty()) DeletionWatcher.handle(ctx, suspects)
        }.onFailure { Log.w(TAG, "删除检查失败：${it.message}") }

        val wm0 = prefs.realtimeWatermark
        val fresh = runCatching { repo.all(wm0) }.getOrDefault(emptyList())
        if (fresh.isEmpty()) return Outcome()

        // ---- 稳定窗口：相机边写边可见，刚拍的 4K 视频还在长，早了会读到半个文件 ----
        val unstable = fresh.filter { !isStable(it) }
        val ready = fresh.filter { isStable(it) }
        // 水位线只能推进到「第一个还没稳定下来的项之前」，否则会把它永久漏掉
        val safeMax = (unstable.minOfOrNull { it.id } ?: Long.MAX_VALUE) - 1
        val pending = ready.filter { it.id <= safeMax }

        if (pending.isEmpty()) {
            return Outcome(fresh = fresh.size, unstable = unstable.size)
        }

        val gate = DeviceState.gateOpen(ctx)
        // 只要有一类被要求「充电 + WiFi」，就必须先满足条件 —— 否则整轮交给调用方决定
        val needsGate = pending.any { prefs.gatedByChargingWifi(it.isVideo) }
        if (onlyWhenGateOpen && needsGate && !gate) {
            return Outcome(fresh = fresh.size, pending = pending.size, blocked = true)
        }

        val todo = pending.filter { !(prefs.gatedByChargingWifi(it.isVideo) && !gate) }
        SyncLog.add(
            "·触发  新增 ${fresh.size} · 待处理 ${pending.size} · 门槛挡下 ${pending.size - todo.size}" +
                " · 未稳定 ${unstable.size}（${DeviceState.describe(ctx)}）"
        )

        var outcome = Outcome(
            fresh = fresh.size, pending = pending.size, todo = todo.size,
            unstable = unstable.size, blocked = pending.size != todo.size
        )
        if (todo.isNotEmpty()) {
            val engine = OffloadEngine(ctx)
            SyncLog.add("·开始  处理 ${todo.size} 个新文件…")
            val r = engine.run({ m -> SyncLog.add(m) }, null, BATCH_LIMIT, todo)
            SyncLog.add(
                "·本轮  上传 ${r.backedUp} · 降级 ${r.downgraded} · 失败 ${r.failed}" +
                    (if (r.bytesSaved > 0) " · 释放 ${fmt(r.bytesSaved)}" else "")
            )
            outcome = outcome.copy(
                ran = true, backedUp = r.backedUp, downgraded = r.downgraded,
                failed = r.failed, bytesSaved = r.bytesSaved
            )
        }
        // 处理过的（含被门槛挡下的）都算"看过"，水位线推进；没稳定的留给下一轮
        prefs.realtimeWatermark = maxOf(wm0, pending.maxOf { it.id })
        return outcome
    }

    /**
     * 文件是否已经写完了。
     *
     * 判据三条：文件在、非空、**大小与 MediaStore 记录一致**、且**最近 [STABLE_MS] 内没被写过**。
     */
    fun isStable(it: MediaItem): Boolean {
        val f = File(it.path)
        if (!f.isFile) return false
        val len = f.length()
        if (len <= 0L) return false
        if (it.size > 0L && len != it.size) return false
        if (System.currentTimeMillis() - f.lastModified() < STABLE_MS) return false
        return true
    }
}
