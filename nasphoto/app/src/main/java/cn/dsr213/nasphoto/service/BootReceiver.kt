package cn.dsr213.nasphoto.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import cn.dsr213.nasphoto.data.Prefs

/**
 * 系统事件复活点：把实时监听服务拉起来。
 *
 * 这里接的每个 action **都在 Android 12+「允许从后台启动前台服务」的豁免清单里**：
 * `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` /
 * `TIME_SET` / `TIMEZONE_CHANGED` / `LOCALE_CHANGED`。
 *
 * ⇒ 这是**仅有的几个能自动把常驻监听救回来**的系统时机。
 * （`ACTION_POWER_CONNECTED` **不在**豁免清单里，所以"插上充电就拉起服务"这条路走不通；
 *   它只能作为"服务已经在跑时"的补跑信号，见 [SyncService]。）
 *
 * 小米智能存储同样只在开机时拉起（清单里就是 `BootReceiver` ← `BOOT_COMPLETED`）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent?) {
        val a = intent?.action ?: return
        if (a !in ACTIONS) return
        if (!Prefs(ctx).realtimeListen) {
            Log.i(TAG, "实时监听已关闭，$a 不拉起服务")
            return
        }
        Log.i(TAG, "系统事件 $a → 拉起实时监听")
        SyncService.start(ctx)
        // 顺带保证两条兜底通道都在（WorkManager 自己也会在重启后恢复，这里是双保险）
        SyncScheduler.ensureScheduled(ctx)
        SyncScheduler.ensureContentTrigger(ctx)
    }

    private companion object {
        const val TAG = "NasPhotoBoot"

        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED
        )
    }
}
