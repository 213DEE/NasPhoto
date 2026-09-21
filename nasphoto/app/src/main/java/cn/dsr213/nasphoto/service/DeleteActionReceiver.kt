package cn.dsr213.nasphoto.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * 删除确认的「确认 / 保留」按钮落点 —— **两个方向共用**。
 *
 * | action | 含义 | 落到哪 |
 * |---|---|---|
 * | [ACTION_TRASH] | 手机删了 → 把 NAS 备份移进回收站 | [DeletionWatcher.applyTrash] |
 * | [ACTION_KEEP] | 手机删了 → 保留 NAS 备份 | [DeletionWatcher.keepAll] |
 * | [ACTION_DEL_LOCAL] | NAS 删了 → 把手机本地也删掉 | [RemoteDeletionWatcher.applyDeleteLocal] |
 * | [ACTION_KEEP_LOCAL] | NAS 删了 → 保留手机本地 | [RemoteDeletionWatcher.keepAll] |
 *
 * 悬浮卡片和通知的按钮**都走这里**，只有一条代码路径 —— 否则两边行为迟早不一致。
 * 用静态 action 广播而不是绑定服务，是为了在进程被杀后点通知按钮也能生效
 * （系统会重新拉起进程并投递广播）。
 *
 * 真正干活在后台线程：要连 NAS、逐个 MOVE/删除，放主线程必 ANR。
 */
class DeleteActionReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in ALL) return

        val app = ctx.applicationContext
        val pending = goAsync()          // 告诉系统"我还在干活，别急着回收进程"
        Thread {
            try {
                when (action) {
                    ACTION_TRASH -> {
                        Log.i(TAG, "用户选择：NAS 备份一起删")
                        val n = runBlocking { DeletionWatcher.applyTrash(app) }
                        Log.i(TAG, "已移入回收站 $n 个")
                    }
                    ACTION_KEEP -> {
                        Log.i(TAG, "用户选择：保留 NAS 备份")
                        DeletionWatcher.keepAll(app)
                    }
                    ACTION_DEL_LOCAL -> {
                        Log.i(TAG, "用户选择：手机本地也一起删")
                        val n = runBlocking { RemoteDeletionWatcher.applyDeleteLocal(app) }
                        Log.i(TAG, "已删除本地 $n 个")
                    }
                    else -> {
                        Log.i(TAG, "用户选择：保留手机本地副本")
                        RemoteDeletionWatcher.keepAll(app)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "处理删除确认失败", t)
            } finally {
                runCatching { pending.finish() }
            }
        }.start()
    }

    companion object {
        private const val TAG = "NasPhotoDel"

        const val ACTION_TRASH = "cn.dsr213.nasphoto.DELETE_SYNC_TRASH"
        const val ACTION_KEEP = "cn.dsr213.nasphoto.DELETE_SYNC_KEEP"
        const val ACTION_DEL_LOCAL = "cn.dsr213.nasphoto.DELETE_SYNC_DEL_LOCAL"
        const val ACTION_KEEP_LOCAL = "cn.dsr213.nasphoto.DELETE_SYNC_KEEP_LOCAL"

        /** 全部 action —— 忘了登记某个新 action 的话，按钮就会静默失灵 */
        private val ALL = setOf(ACTION_TRASH, ACTION_KEEP, ACTION_DEL_LOCAL, ACTION_KEEP_LOCAL)

        fun send(ctx: Context, action: String) {
            ctx.sendBroadcast(Intent(action).setPackage(ctx.packageName))
        }

        fun pending(ctx: Context, action: String) = android.app.PendingIntent.getBroadcast(
            ctx,
            action.hashCode(),
            Intent(action).setPackage(ctx.packageName),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
