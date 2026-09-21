package cn.dsr213.nasphoto.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import cn.dsr213.nasphoto.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.Collections
import javax.net.ssl.SSLSocket

/**
 * 通道类型。
 *
 * 顺序就是**优先级**（见 [EndpointResolver.orderOf]）：能用近的就不用远的。
 * 局域网 > 公网 IPv6 直连 > 隧道。
 */
enum class ChannelKind(val key: String, val label: String) {
    /** 同一 WiFi 内直连 NAS（192.168.31.x）。实测 73~90 MB/s，是唯一适合"灌全量"的通道 */
    LAN("lan", "局域网直连"),

    /**
     * 公网 IPv6 直连。
     *
     * ⚠️ **当前处于「已预埋、未打通」状态**：家里是「光猫（路由模式，拦全部入站）
     * → 小米路由器 → NAS」两级结构，光猫后台**根本没有 IPv6 入站放行模块**
     * （126+91+26 个路径全 404，判据是 LuCI 调度层的 `No page is registered`），
     * 所以从公网进来的包止步于光猫。
     *
     * 这条路的价值在于：**一旦防火墙问题解决，App 侧零改动即可启用**
     * —— 前缀自动学习、地址拼装、探测降级、失败熔断都已就位（见 [Ipv6Learner]）。
     */
    WAN_V6("v6", "公网 IPv6 直连"),

    /**
     * frp 中转 —— NAS 主动连到云服务器，公网流量经云端转回 NAS。
     *
     * ⭐ **这是当前唯一真正可用的外网通道，而且基础设施早就跑着了**：
     * NAS 上 `/data/frp/frpc` 与腾讯云 `1.12.251.37:7000` 一直保持长连接，
     * 现有那条 `homeassistant` 隧道（本地 8123 → 远端 18123）就是 `ha.dsr213.cn` 在用的。
     *
     * 为什么外网只有它可行：
     * - 它是**出站连接**，完全不受光猫那道「拦全部入站」的 IPv6 防火墙影响
     * - 不依赖 IPv6（云服务器是 IPv4），也不需要 DDNS（入口地址由 frps 固定）
     * - **不需要 TUN 内核模块** —— 而这恰恰是 Tailscale 在这里的死穴（见 [TAILSCALE]）
     *
     * 代价：走云服务器中转，实测约 0.4~0.75 MB/s，只适合增量同步，首次全量请回家走 LAN。
     */
    FRP("frp", "frp 中转（腾讯云）"),

    /**
     * Tailscale 官方。
     *
     * ⚠️⚠️ **实测在 NAS 上跑不起来**：小米 NAS 的内核 `6.6.35-yocto-standard`
     * **完全没有 TUN 支持**，三重判据全部为负 ——
     * `/proc/misc` 里没有 tun、`modules.builtin` 里没有 tun、
     * `modprobe -n -v tun` 直接报 `FATAL: Module tun not found`（模块目录里连 .ko 都没有）。
     *
     * 而 tailscaled 两种模式都绕不过去：内核态必须有 `/dev/net/tun`；
     * userspace 模式虽然不需要 TUN，但它**只能作出站客户端、不能作为服务端被访问** ——
     * 而我们的需求恰恰是「手机连进来访问 NAS 的 WebDAV」，方向正好相反。
     *
     * 通道保留的意义：**代码已完全就位**，将来若换设备或内核补上 TUN，
     * 打开开关填个地址即可启用，App 侧零改动。
     */
    TAILSCALE("ts", "Tailscale 官方"),

    /** 自建 Headscale（control server 在腾讯云 1.12.251.37）。App 侧与 Tailscale 无差别 */
    HEADSCALE("hs", "Headscale 自建");

    companion object {
        fun byKey(k: String?): ChannelKind? = values().firstOrNull { it.key == k }
    }
}

/**
 * 一个可直接发起 WebDAV 请求的端点。
 *
 * @param origin 界面上解释"这个地址是怎么来的"，排查时一眼看出是自动学的还是手填的
 */
data class Endpoint(
    val kind: ChannelKind,
    val baseUrl: String,
    val origin: String = ""
) {
    /** 日志/界面用：隐去路径，只留 `scheme://host:port` */
    val display: String
        get() = runCatching {
            val u = URL(baseUrl)
            val p = if (u.port > 0) ":${u.port}" else ""
            "${u.protocol}://${u.host}$p"
        }.getOrDefault(baseUrl)
}

/**
 * 从**本机网卡**反推当前所在网络的 IPv6 /64 前缀。
 *
 * 这是「公网 IPv6 通道」能自动适配动态前缀的关键：
 * 手机连上家里 WiFi 时会自己拿到一个同前缀的全局地址，把它的前 64 位取出来就是家里的前缀。
 * **用户回家一次，前缀就自动更新一次** —— 于是连 DDNS 都不需要。
 *
 * ⚠️ 只在**确认在家**（局域网端点可达）时才允许写回缓存。在外面连别人的 WiFi，
 * 学到的会是别人家的前缀，拼出来的地址毫无意义（见 [EndpointResolver.resolve]）。
 */
object Ipv6Learner {

    /**
     * 返回形如 `240e:3bc:34bb:9b11` 的 **WiFi 网卡** IPv6 /64 前缀；找不到返回 null。
     *
     * ⚠️⚠️ **必须只认 WiFi 网卡，不能随便挑一个全局 IPv6 地址就返回**。
     *
     * 实测踩过这个坑：手机同时连着家里 WiFi 和蜂窝时，直接遍历网卡会**先撞上蜂窝那张卡**
     * （`2408:...` 联通段），于是把**运营商蜂窝的前缀**当成家里的前缀学了回去。
     * 拼出来的地址 `2408:8556:...::8f8` 根本不存在，而界面上还理直气壮地显示"前缀已学到" ——
     * 属于最难查的一类错：看起来一切正常。
     *
     * 所以主路径走 ConnectivityManager，**只在 `TRANSPORT_WIFI` 那个网络里找地址**，
     * 从机制上把蜂窝排除掉；兜底才遍历网卡，且只接受名字以 `wlan` 开头的接口。
     *
     * ⚠️ 前缀必须走 `Inet6Address.address`（16 字节原始数组）而不是 `hostAddress` 字符串 ——
     * 后者在部分地址上会做 `::` 压缩（如 `240e:3bc::1`），按 `:` 切分取前 4 段会直接切错。
     */
    fun globalPrefix(ctx: Context?): String? {
        if (ctx != null) {
            try {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as ConnectivityManager
                for (n in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(n) ?: continue
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                    val lp = cm.getLinkProperties(n) ?: continue
                    for (la in lp.linkAddresses) {
                        val a = la.address
                        if (a is Inet6Address) prefixOf(a)?.let { return it }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        // 兜底：遍历网卡，但只认 wlan 开头的接口名（排除 rmnet/ccmni 等蜂窝接口）
        return try {
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp || nif.isLoopback) continue
                if (!(nif.name ?: "").lowercase().startsWith("wlan")) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr !is Inet6Address) continue
                    prefixOf(addr)?.let { return it }
                }
            }
            null
        } catch (t: Throwable) {
            null
        }
    }

    /** 取全局单播（2000::/3）地址的前 64 位；不是全局单播返回 null */
    private fun prefixOf(a: Inet6Address): String? {
        val b = a.address ?: return null
        if (b.size != 16) return null
        // 全局单播 2000::/3 —— 一次排除 fe80::(链路本地)/fc00::(ULA)/ff00::(组播)
        if ((b[0].toInt() and 0xe0) != 0x20) return null
        val parts = (0 until 4).map { i ->
            (((b[i * 2].toInt() and 0xff) shl 8) or (b[i * 2 + 1].toInt() and 0xff)).toString(16)
        }
        return parts.joinToString(":")
    }
}

/**
 * 端点解析：把「一堆可能的通道」变成「一个当前真正能用的通道」。
 *
 * 三条设计原则（都是踩过坑换来的）：
 *
 * 1. **探测必须能失败，且失败要快、要能区分** —— 探测用的是和真实传输**完全相同**的
 *    TLS 策略与请求路径（[Tls.permissiveFactory]），判据是「服务器回了任意 HTTP 状态码」。
 *    连不上 / 握手失败 / 超时一律 -1。绝不用"DNS 能解析"这种假判据。
 *
 * 2. **不许因为探测失败就做破坏性动作** —— 所有通道都不通时返回的 `endpoint == null`
 *    只有一个含义：**这轮什么都别做**。尤其不能把"读不到 NAS"当成"NAS 上文件被删了"
 *    （见 `OffloadEngine` / `RemoteDeletionWatcher` 里那条铁律）。
 *
 * 3. **并发探测 + 优先级选优** —— 串行探 4 个通道最坏要 12 秒。并发跑，总耗时 = 最慢那个，
 *    然后按 (优先级, 耗时) 取最优：局域网永远优先于隧道，与谁先返回无关。
 */
object EndpointResolver {

    private const val TAG = "NasPhoto/Chan"

    /** NAS WebDAV 端口（nginx，小米自带服务） */
    const val DEFAULT_PORT = 5000

    /** `/pool0/data` 经 `/home/<user>/pool0/data` 软链指向 `/nas/pool0/<user>/data` */
    const val DEFAULT_PATH = "/pool0/data"

    /** 单个通道的探测结果 */
    data class ProbeResult(
        val endpoint: Endpoint,
        val ok: Boolean,
        val ms: Long,
        val code: Int
    )

    /**
     * 一次解析的完整结果。
     *
     * @param endpoint 最优端点；**null = 当前一个通道都不通，本轮应直接跳过**
     * @param probes   所有探测明细（界面/日志用，能看出"哪条路挂了、为什么"）
     * @param lanOk    是否在家（局域网可达）—— 同时也是"前缀可以更新"的判据
     * @param prefixUpdated 本次更新到的前缀（非 null 表示"刚回家、前缀已刷新"）
     */
    data class Outcome(
        val endpoint: Endpoint?,
        val probes: List<ProbeResult>,
        val lanOk: Boolean,
        val prefixUpdated: String? = null
    ) {
        /** 一行话总结，直接进同步日志 */
        fun summary(): String {
            val best = endpoint ?: return "所有通道均不可达（本轮跳过）"
            return "选用 ${best.kind.label} → ${best.display}"
        }
    }

    /** 便捷入口：只要最优端点 */
    suspend fun best(prefs: Prefs, ctx: Context? = null): Endpoint? =
        resolve(prefs, ctx).endpoint

    /**
     * 诊断用：直接探一个 URL，**不读也不改任何配置**。
     *
     * 存在的理由：IPv6 通道目前因为光猫拦入站而走不通外网，但**代码路径可以在局域网内先验证**
     * （手机和 NAS 同一 LAN 时，v6 直连不经过路由器防火墙）。
     * 于是"前缀学得对不对、地址拼得对不对、v6 的方括号与 Host 头写没写对"
     * 这些都能提前钉死，等外部条件解决时只剩"能不能进来"这一个变量。
     */
    suspend fun probeUrl(url: String, timeoutMs: Int = 3_000): Boolean =
        withContext(Dispatchers.IO) {
            probe(Endpoint(ChannelKind.WAN_V6, url), timeoutMs).ok
        }

    /**
     * 列出当前配置下**所有**候选端点（按优先级）。
     *
     * 注意这里会产出**多个** `WAN_V6` 候选（EUI-64 后缀 + DHCPv6 后缀各一个）——
     * NAS 的两个 v6 地址里，EUI-64 那个由网卡 MAC 派生、永不变；
     * DHCPv6 那个由路由器分配、可能随重启变化。两个都探、哪个通用哪个，
     * 比"猜一个然后经常失败"稳得多。
     */
    fun candidates(prefs: Prefs): List<Endpoint> {
        val out = ArrayList<Endpoint>(6)
        val path = prefs.webdavPath

        // ---- ① 局域网 ----
        if (prefs.chanLanEnabled) {
            val base = prefs.chanLanBase.ifBlank { prefs.webdavBase }
            if (base.isNotBlank()) {
                out += Endpoint(ChannelKind.LAN, base, "同一 WiFi 内直连")
            }
        }

        // ---- ② 公网 IPv6（预埋通道）----
        if (prefs.chanV6Enabled) {
            val scheme = prefs.chanV6Scheme
            val port = prefs.chanV6Port
            val manual = prefs.chanV6ManualAddr.trim().trim('[', ']')
            if (manual.isNotEmpty()) {
                out += Endpoint(
                    ChannelKind.WAN_V6, "$scheme://[$manual]:$port$path", "手动指定地址"
                )
            } else {
                val prefix = prefs.chanV6LearnedPrefix.trim()
                if (prefix.isNotEmpty()) {
                    for ((frag, why) in v6SuffixFragments(prefs)) {
                        out += Endpoint(
                            ChannelKind.WAN_V6, "$scheme://[$prefix$frag]:$port$path", why
                        )
                    }
                }
            }
        }

        // ---- ③ frp 中转（当前唯一可用的外网通道）----
        if (prefs.chanFrpEnabled && prefs.chanFrpBase.isNotBlank()) {
            out += Endpoint(ChannelKind.FRP, prefs.chanFrpBase.trim(), "云服务器中转")
        }

        // ---- ④⑤ 隧道 ----
        if (prefs.chanTsEnabled && prefs.chanTsBase.isNotBlank()) {
            out += Endpoint(ChannelKind.TAILSCALE, prefs.chanTsBase.trim(), "Tailscale 官方")
        }
        if (prefs.chanHsEnabled && prefs.chanHsBase.isNotBlank()) {
            out += Endpoint(ChannelKind.HEADSCALE, prefs.chanHsBase.trim(), "Headscale 自建")
        }
        return out
    }

    /**
     * 待拼接的 IPv6 后缀片段（**含前导冒号**，直接贴在前缀后面）。
     *
     * 两种写法的冒号数量不一样，别写错：
     * - EUI-64：前缀 `240e:...:9b11` + `:d653:2aff:fec6:dbda` = 完整 8 段地址
     * - DHCPv6：前缀 `240e:...:9b11` + `::8f8` = `...:9b11::8f8`
     */
    private fun v6SuffixFragments(prefs: Prefs): List<Pair<String, String>> {
        val eui = prefs.chanV6Eui64.trim().trim(':')
        val dhcp = prefs.chanV6DhcpTail.trim().trim(':')
        return when (prefs.chanV6Mode) {
            "eui64" -> if (eui.isEmpty()) emptyList()
            else listOf(":$eui" to "EUI-64 后缀（由网卡 MAC 派生，永不变）")

            "dhcp" -> if (dhcp.isEmpty()) emptyList()
            else listOf("::$dhcp" to "DHCPv6 后缀（由路由器分配，可能变）")

            else -> buildList {
                if (eui.isNotEmpty()) add(":$eui" to "EUI-64 后缀（永不变）")
                if (dhcp.isNotEmpty()) add("::$dhcp" to "DHCPv6 后缀（可能变）")
            }
        }
    }

    /**
     * 探测全部候选，返回当前最优通道。
     *
     * 耗时上限 = 最慢那条通道的超时（约 3.5 秒），因为全部并发。
     *
     * @param ctx 传进来是为了让 [Ipv6Learner] 能**只在 WiFi 网络里**找前缀
     *            （传 null 会退化成遍历网卡，可能学到蜂窝前缀，见那边的注释）
     */
    suspend fun resolve(prefs: Prefs, ctx: Context? = null): Outcome = coroutineScope {
        // 本机前缀先读出来**但不写回** —— 只有在确认"在家"之后才允许写（见下）
        val localPrefix = withContext(Dispatchers.IO) { Ipv6Learner.globalPrefix(ctx) }
        val all = withContext(Dispatchers.IO) { candidates(prefs) }
        if (all.isEmpty()) {
            return@coroutineScope Outcome(null, emptyList(), lanOk = false)
        }

        val results = all
            .map { ep -> async(Dispatchers.IO) { probe(ep, timeoutFor(ep.kind)) } }
            .awaitAll()

        val lanOk = results.any { it.endpoint.kind == ChannelKind.LAN && it.ok }

        // ⭐ 只有"局域网可达"才等价于"我在自己家"。在外面连别人的 WiFi 时本机也有
        //    全局 v6 地址，但那是别人家的前缀 —— 写进去只会把 v6 通道彻底带偏。
        var updated: String? = null
        if (lanOk && localPrefix != null &&
            localPrefix != withContext(Dispatchers.IO) { prefs.chanV6LearnedPrefix }
        ) {
            withContext(Dispatchers.IO) {
                prefs.chanV6LearnedPrefix = localPrefix
                prefs.chanV6LearnedAt = System.currentTimeMillis()
            }
            updated = localPrefix
            Log.i(TAG, "已刷新家庭 IPv6 前缀：$localPrefix")
        }

        val best = results
            .filter { it.ok }
            .minWithOrNull(
                compareBy<ProbeResult>({ orderOf(it.endpoint.kind) }, { it.ms })
            )

        best?.let { withContext(Dispatchers.IO) { prefs.lastGoodChannel = it.endpoint.kind.key } }

        if (best == null) {
            Log.w(TAG, "所有通道均不可达：" + results.joinToString(" | ") {
                "${it.endpoint.kind.key}=${if (it.ok) "${it.ms}ms" else "fail"}"
            })
        }
        Outcome(best?.endpoint, results, lanOk, updated)
    }

    /** 通道优先级：越小越优先。局域网永远第一，中转/隧道永远最后（要绕远路） */
    private fun orderOf(k: ChannelKind): Int = when (k) {
        ChannelKind.LAN -> 0
        ChannelKind.WAN_V6 -> 1
        // frp 排在 Tailscale 之前：它是实测可用的，而 Tailscale 在 NAS 上根本起不来
        ChannelKind.FRP -> 2
        ChannelKind.TAILSCALE -> 3
        ChannelKind.HEADSCALE -> 4
    }

    /**
     * 超时按通道性质**分别设置**，不是随便拍的：
     * - 局域网在同一个二层里，没通就是"不在家"，多等纯属浪费 —— 给 1.5 秒
     * - 公网 v6 可能撞上防火墙**静默丢包**（不回应、只能等超时），要留足
     * - frp 要先到云端再绕回来，链路最长
     * - 隧道还可能额外花时间协商打洞
     */
    private fun timeoutFor(k: ChannelKind): Int = when (k) {
        ChannelKind.LAN -> 1_500
        ChannelKind.WAN_V6 -> 3_000
        ChannelKind.FRP -> 3_500
        ChannelKind.TAILSCALE -> 3_500
        ChannelKind.HEADSCALE -> 3_500
    }

    /**
     * 单次探测：发一个 `OPTIONS`（WebDAV 必须支持，且不碰任何数据）。
     *
     * 判据是「**拿到了任意 HTTP 状态码**」—— 401/403/404 都算通，
     * 因为它们证明 TCP + TLS + HTTP 三层全都活着；只有超时/连不上/握手失败才算不通。
     *
     * ⚠️ 这里和 [WebDavClient.rawRequest] 一样踩过 IPv6 方括号的坑：
     * `URL.host` 对 IPv6 字面量返回的是**带方括号**的 `[240e:...]`，
     * 直接喂给 `InetSocketAddress` 会解析失败。但 HTTP 的 `Host` 头**反而必须带**方括号。
     * 所以两个变量分开存，不能混用（混用的后果是 v6 通道一上线就全挂，且报错看不懂）。
     */
    private fun probe(ep: Endpoint, timeoutMs: Int): ProbeResult {
        val t0 = System.currentTimeMillis()
        var code = -1
        var sock: Socket? = null
        try {
            val u = URL(ep.baseUrl)
            val bare = u.host.trim('[', ']')
            val hostHeader = if (bare.contains(':')) "[$bare]" else bare
            val port = if (u.port > 0) u.port else if (u.protocol == "https") 443 else 80

            val plain = Socket()
            plain.connect(InetSocketAddress(bare, port), timeoutMs)
            plain.soTimeout = timeoutMs
            sock = if (u.protocol == "https") {
                // 自签证书 + 隧道 IP 会变 ⇒ 主机名校验必然对不上，这里不做校验是有意为之
                // （与 WebDavClient 的宽松分支同一策略，见 [Tls]）
                (Tls.permissiveFactory().createSocket(plain, bare, port, true) as SSLSocket)
                    .apply { startHandshake() }
            } else plain

            val path = (u.path.ifEmpty { "/" }) + (u.query?.let { "?$it" } ?: "")
            val req = buildString {
                append("OPTIONS $path HTTP/1.1\r\n")
                append("Host: $hostHeader:$port\r\n")
                append("User-Agent: NasPhoto/probe\r\n")
                append("Content-Length: 0\r\n")
                append("Connection: close\r\n\r\n")
            }
            sock.getOutputStream().apply {
                write(req.toByteArray(Charsets.UTF_8)); flush()
            }
            val line = sock.getInputStream().bufferedReader().readLine() ?: ""
            code = line.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
        } catch (t: Throwable) {
            code = -1
        } finally {
            runCatching { sock?.close() }
        }
        return ProbeResult(ep, code > 0, System.currentTimeMillis() - t0, code)
    }
}
