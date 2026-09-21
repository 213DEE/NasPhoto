package cn.dsr213.nasphoto.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 「备份时机」三档策略 —— 决定**什么时候允许发起同步**。
 *
 * 与 [ChannelKind] 的分工：
 * - [NetPolicy] 回答「现在该不该动手」
 * - [EndpointResolver] 回答「动手的话走哪条路」
 *
 * 两者是正交的：同一档策略下可以有多条候选通道，同一条通道也可以服务多档策略。
 */
enum class NetPolicy(val key: String, val label: String, val desc: String) {

    /** 只有在能直连 NAS 时才同步 —— 速度是外网的 25~30 倍，也最省电 */
    LAN_ONLY(
        "lan_only", "仅局域网",
        "只有连上家里 WiFi（能直连 NAS）时才同步。最快、最省流量，但出门就停"
    ),

    /** 外面任意 WiFi 也同步（走隧道/穿透通道），刻意不吃手机流量 */
    ANY_WIFI(
        "any_wifi", "任意 WiFi",
        "连上任意 WiFi 也同步（在外走隧道通道）。不消耗蜂窝流量"
    ),

    /** 完全放开，含蜂窝。上行只有 ~3 MB/s，适合增量，首次全量建议回家做 */
    ANY_NETWORK(
        "any_network", "任意网络",
        "含蜂窝流量，随时随地都能同步。上行受限（约 3 MB/s），适合增量"
    );

    companion object {
        fun byKey(k: String?): NetPolicy = values().firstOrNull { it.key == k } ?: LAN_ONLY
    }
}

/**
 * 网络环境门控。
 *
 * ⚠️ 这里有一个**必须绕开的 Android 陷阱**：**不能拿 `activeNetwork` 判断"是否在 WiFi"**。
 *
 * 一旦手机上开着 VPN（Tailscale / Headscale 客户端就是一个 VpnService），
 * `activeNetwork` 会变成那个 VPN 网络，它的 `NetworkCapabilities` 里**没有**
 * `TRANSPORT_WIFI`。于是"明明连着家里 WiFi"会被判成"不在 WiFi" ——
 * 后果是所有带 WiFi 门槛的逻辑（包括本 App 原有的「充电 + WiFi」门控）
 * 在开着尾隧道的手机上会**永久卡死**，而且从界面完全看不出原因。
 *
 * 正确做法：遍历全部网络，只要有**任意一个** WiFi 连着就算连着。
 */
object NetGate {

    fun isOnWifi(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.any { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@any false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
    } catch (t: Throwable) {
        false
    }

    /** 有没有任何一条能上互联网的链路（含蜂窝、含 VPN） */
    fun isOnline(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (t: Throwable) {
        false
    }

    /** 当前是否允许发起同步 */
    fun allowed(ctx: Context, policy: NetPolicy): Boolean = when (policy) {
        // 注意：这一档不校验"是不是**自己家**的 WiFi" —— 那件事由
        // EndpointResolver 用「局域网端点能否连通」来判定，天然准确（改了网段也不会误判）
        NetPolicy.LAN_ONLY -> isOnWifi(ctx)
        NetPolicy.ANY_WIFI -> isOnWifi(ctx)
        NetPolicy.ANY_NETWORK -> isOnline(ctx)
    }

    /** 不允许时给出原因（直接进日志/界面提示）；允许返回 null */
    fun blockReason(ctx: Context, policy: NetPolicy): String? = when {
        allowed(ctx, policy) -> null
        policy == NetPolicy.LAN_ONLY -> "未连接 WiFi（策略：${policy.label}）"
        policy == NetPolicy.ANY_WIFI -> "未连接 WiFi（策略：${policy.label}）"
        else -> "当前无网络连接"
    }

    /** 该策略下会用到哪些通道 —— 界面上用来解释"为什么有些通道用不上" */
    fun channelsInUse(policy: NetPolicy): List<ChannelKind> = when (policy) {
        // 只在家同步 ⇒ 永远只有局域网这一条路。其余通道配了也不会被选中。
        NetPolicy.LAN_ONLY -> listOf(ChannelKind.LAN)
        else -> ChannelKind.values().toList()
    }

    /** 一行话描述当前环境，直接进日志 */
    fun describe(ctx: Context, policy: NetPolicy): String {
        val env = when {
            isOnWifi(ctx) -> "WiFi"
            isOnline(ctx) -> "移动网络"
            else -> "离线"
        }
        val verdict = if (allowed(ctx, policy)) "允许同步" else "本轮跳过"
        return "$env · $verdict（${policy.label}）"
    }
}
