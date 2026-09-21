package cn.dsr213.nasphoto.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import cn.dsr213.nasphoto.net.NetGate

/** 设备状态门控：视频转码/上传要求「充电中 + WiFi」 */
object DeviceState {

    fun isCharging(ctx: Context): Boolean {
        return try {
            val i: Intent? = ctx.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * ⚠️ 实现委托给 [NetGate.isOnWifi]，它**遍历全部网络**而不是看 `activeNetwork`。
     *
     * 为什么不能看 activeNetwork：手机上开着 VPN 时（Tailscale / Headscale 客户端本身
     * 就是一个 VpnService），`activeNetwork` 会变成那个 VPN 网络，它的 NetworkCapabilities
     * 里**没有** `TRANSPORT_WIFI` —— 于是"明明连着家里 WiFi"被判成"不在 WiFi"，
     * 「充电 + WiFi」门控在开隧道的手机上会**永久卡住**，而且从界面完全看不出原因。
     */
    fun isOnWifi(ctx: Context): Boolean = NetGate.isOnWifi(ctx)

    /**
     * 「充电 + WiFi」门槛是否满足。
     *
     * 用在哪里由**用户开关**决定（照片/视频分开，见 `Prefs.gatedByChargingWifi`）：
     * 开关打开 → 必须满足才会同步；开关关闭 → 不看这个值，直接实时同步。
     */
    fun gateOpen(ctx: Context): Boolean = isCharging(ctx) && isOnWifi(ctx)

    fun describe(ctx: Context): String {
        val c = if (isCharging(ctx)) "充电中" else "未充电"
        val w = if (isOnWifi(ctx)) "WiFi" else "非WiFi"
        return "$c · $w"
    }
}
