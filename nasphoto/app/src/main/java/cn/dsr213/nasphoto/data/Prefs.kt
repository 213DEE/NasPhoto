package cn.dsr213.nasphoto.data

import android.content.Context
import java.net.URL

/**
 * 用户配置。NAS 连接 + 卸载策略。
 */
class Prefs(ctx: Context) {

    private val sp = ctx.getSharedPreferences("nasphoto", Context.MODE_PRIVATE)

    /**
     * WebDAV 基址（小米 NAS 自带服务，nginx :5000）。
     * `/pool0/data` 经 `/home/<user>/pool0/data` 符号链接指向 `/nas/pool0/<user>/data`。
     */
    var webdavBase: String
        get() = sp.getString("webdavBase", "https://192.168.31.253:5000/pool0/data")
            ?: "https://192.168.31.253:5000/pool0/data"
        set(v) = sp.edit().putString("webdavBase", v).apply()

    var user: String
        get() = sp.getString("user", "u133630987") ?: "u133630987"
        set(v) = sp.edit().putString("user", v).apply()

    var password: String
        get() = sp.getString("password", "") ?: ""
        set(v) = sp.edit().putString("password", v).apply()

    /** 是否允许自签设备证书（NAS 的证书由小米 IoT 网关注发、约 21 天轮换） */
    var allowSelfSigned: Boolean
        get() = sp.getBoolean("allowSelfSigned", true)
        set(v) = sp.edit().putBoolean("allowSelfSigned", v).apply()

    /** 上传后是否读回算 SHA-256 校验（慢一点，但能证明字节真的落对了） */
    var verifyReadBack: Boolean
        get() = sp.getBoolean("verifyReadBack", true)
        set(v) = sp.edit().putBoolean("verifyReadBack", v).apply()

    /** NAS 上的归档根目录（相对 WebDAV 基址） */
    var remoteRoot: String
        get() = sp.getString("remoteRoot", "WorkSpace/NasPhoto归档") ?: "WorkSpace/NasPhoto归档"
        set(v) = sp.edit().putString("remoteRoot", v).apply()

    /**
     * 【旧项，仅作兼容】全局的"只处理超过这么多天的照片"。
     *
     * 已被 [photoDowngradeDelayDays] / [videoDowngradeDelayDays] 取代（照片/视频分开）。
     * 保留它是因为**老配置里只存了这一个值** —— 新字段首次读取时拿它当默认值，
     * 这样升级 App 不会把用户原来的 30 天设置弄丢。
     */
    var minAgeDays: Int
        get() = sp.getInt("minAgeDays", 30)
        set(v) = sp.edit().putInt("minAgeDays", v).apply()

    /**
     * **照片**：备份完成后，隔多少天才在本地降级。单位「天」。
     *
     * - `0` = **备份完成后立刻降级**（最省空间，本地马上换成小图）
     * - `>0` = 这几天的照片本地保留原图，过了才降级（给自己留一个"反悔窗口"）
     *
     * 注：这个门槛**只影响是否降级**；所有照片无论如何都会先完整备份到 NAS。
     */
    var photoDowngradeDelayDays: Int
        get() = sp.getInt("photoDowngradeDelayDays", sp.getInt("minAgeDays", 30))
            .coerceAtLeast(0)
        set(v) = sp.edit().putInt("photoDowngradeDelayDays", v.coerceAtLeast(0)).apply()

    /** **视频**：同 [photoDowngradeDelayDays]，两者独立设置。 */
    var videoDowngradeDelayDays: Int
        get() = sp.getInt("videoDowngradeDelayDays", sp.getInt("minAgeDays", 30))
            .coerceAtLeast(0)
        set(v) = sp.edit().putInt("videoDowngradeDelayDays", v.coerceAtLeast(0)).apply()

    /** 该类型当前的降级延迟天数（0 = 立刻降级） */
    fun downgradeDelayDays(isVideo: Boolean): Int =
        if (isVideo) videoDowngradeDelayDays else photoDowngradeDelayDays

    /** 供界面展示的一句话描述 */
    fun downgradeDelayText(isVideo: Boolean): String {
        val d = downgradeDelayDays(isVideo)
        return if (d <= 0) "备份后立刻降级" else "超过 $d 天"
    }

    /**
     * 用户手动「恢复原图」之后，**是否允许过一段时间再把它降级回去**。
     *
     * 默认 **false** —— 保持「恢复是粘性的」：用户按下按钮就该得到那张原图，
     * 不该过几天又莫名其妙变回小图。这是 2026-09-16 那个 bug 的修复语义。
     *
     * 打开后按 [restoreRedowngradeDays] 的天数走宽限期：宽限期内本地保持原图，
     * 到期后这条重新回到候选、可被正常策略降级（适合"我只是想临时看一眼原图"的用户）。
     */
    var restoreRedowngrade: Boolean
        get() = sp.getBoolean("restoreRedowngrade", false)
        set(v) = sp.edit().putBoolean("restoreRedowngrade", v).apply()

    /**
     * 「恢复原图」后允许被再次降级的宽限天数（仅当 [restoreRedowngrade] 打开时生效）。
     *
     * 界面只提供 3 / 7 / 15 / 30 四档，但这里不把值锁死 —— 万一以后要加自定义天数，
     * 老配置读出来仍要是合法的（所以只做 1..3650 的宽松兜底）。
     */
    var restoreRedowngradeDays: Int
        get() = sp.getInt("restoreRedowngradeDays", 7).coerceIn(1, 3650)
        set(v) = sp.edit().putInt("restoreRedowngradeDays", v.coerceIn(1, 3650)).apply()

    /** 供界面/日志展示的一句话描述 */
    fun restorePolicyText(): String =
        if (!restoreRedowngrade) "恢复后不再降级"
        else "恢复后 ${restoreRedowngradeDays} 天可再降级"

    /** 宽限期毫秒数；未开启返回 -1（调用方据此直接跳过候选） */
    val restoreGraceMs: Long
        get() = if (!restoreRedowngrade) -1L
        else restoreRedowngradeDays * 86_400_000L

    /** 小于这个体积的图不动（本来就不占地方） */
    var minSizeKb: Int
        get() = sp.getInt("minSizeKb", 300)
        set(v) = sp.edit().putInt("minSizeKb", v).apply()

    /** 本地预览的长边像素 */
    var previewLongEdge: Int
        get() = sp.getInt("previewLongEdge", 2560)
        set(v) = sp.edit().putInt("previewLongEdge", v).apply()

    var previewQuality: Int
        get() = sp.getInt("previewQuality", 90)
        set(v) = sp.edit().putInt("previewQuality", v).apply()

    /** 白名单相册：这些相册里的照片永不卸载。注意别把主相册（相机）加进来 */
    var protectedBuckets: Set<String>
        get() = sp.getStringSet("protectedBuckets", setOf("宝宝相册")) ?: emptySet()
        set(v) = sp.edit().putStringSet("protectedBuckets", v).apply()

    /** 只处理这个相册（留空 = 全部）。也可以用来做安全的试跑 */
    var onlyBucket: String
        get() = sp.getString("onlyBucket", "") ?: ""
        set(v) = sp.edit().putString("onlyBucket", v).apply()

    /** 单轮最多处理多少张（0 = 不限）。试跑时设成 1 最稳 */
    var maxBatch: Int
        get() = sp.getInt("maxBatch", 0)
        set(v) = sp.edit().putInt("maxBatch", v).apply()

    // ---------------------------------------------------------------- 视频
    /** 视频是否纳入处理 */
    var videoEnabled: Boolean
        get() = sp.getBoolean("videoEnabled", true)
        set(v) = sp.edit().putBoolean("videoEnabled", v).apply()

    /**
     * **视频**同步是否要求「充电中 + WiFi」。默认 **true**。
     *
     * 关掉 = 该类型**实时同步**（一有新内容就传，不挑电量和网络）。
     * 照片/视频分开，因为两者耗电与流量差别很大。
     */
    var videoRequireChargingWifi: Boolean
        get() = sp.getBoolean("videoChargingRequired", true) // 沿用旧键，老配置无缝继承
        set(v) = sp.edit().putBoolean("videoChargingRequired", v).apply()

    /**
     * **照片**同步是否要求「充电中 + WiFi」。默认 **true**。
     * 关掉 = 照片实时同步。
     */
    var photoRequireChargingWifi: Boolean
        get() = sp.getBoolean("photoRequireChargingWifi", true)
        set(v) = sp.edit().putBoolean("photoRequireChargingWifi", v).apply()

    /**
     * **实时监听**总开关（默认 true）。
     *
     * 打开时 App 会跑一个常驻前台服务，监听相册变化并**及时发现新照片/新视频**
     * （这是「拍完一会儿就同步」的关键，对标小米智能存储的做法——
     * 它就是靠 `XMListenerService` 前台服务 + ContentObserver 实现的）。
     * 代价：通知栏常驻一条「正在监听」。
     *
     * 关掉后只保留每 6 小时的定时兜底。
     */
    var realtimeListen: Boolean
        get() = sp.getBoolean("realtimeListen", true)
        set(v) = sp.edit().putBoolean("realtimeListen", v).apply()

    /** 该类型此刻是否受「充电 + WiFi」门槛限制 */
    fun gatedByChargingWifi(isVideo: Boolean): Boolean =
        if (isVideo) videoRequireChargingWifi else photoRequireChargingWifi

    // ---------------------------------------------------------------- 删除同步

    /**
     * 手机相册里删掉照片后，NAS 上的备份怎么处理。
     *
     * - `ask`（默认）：弹窗问一句「NAS 备份一起删吗？」—— **不问就不动 NAS**
     * - `auto`：不打扰，直接移进 NAS 回收站（反正能恢复）
     * - `off`：完全不管，NAS 上那份原样保留
     *
     * 无论哪种模式，NAS 侧都**只移进回收站、绝不硬删** —— 手机端删的是降级小图，
     * NAS 上那份才是唯一母本。
     */
    var deleteSyncMode: String
        get() = sp.getString("deleteSyncMode", "ask") ?: "ask"
        set(v) = sp.edit().putString("deleteSyncMode", v).apply()

    /**
     * 已经问过用户、但还没回答的删除批次（JSON 数组：`[{"path":..., "at":...}]`）。
     *
     * 放持久化里是因为：弹窗/通知可能过了一会儿才被点，甚至跨进程重启；
     * 而在这期间**不能重复弹**（否则删一张照片弹十次）。
     * 记录一直留着也意味着"没回答 = 保留 NAS 备份"，语义正好。
     */
    var pendingDeletions: String
        get() = sp.getString("pendingDeletions", "") ?: ""
        set(v) = sp.edit().putString("pendingDeletions", v).apply()

    /**
     * 上次弹「NAS 备份要一起删吗」的时间。
     *
     * 用途：用户可能根本没看到那次询问（卡片超时、通知被划掉、当时不在手机旁）。
     * 待办一直挂着但不提示 = 等于没问过，所以隔一段时间要**再问一次**
     * （见 `DeletionWatcher.REASK_INTERVAL_MS`）。
     */
    var lastDeleteAskAt: Long
        get() = sp.getLong("lastDeleteAskAt", 0L)
        set(v) = sp.edit().putLong("lastDeleteAskAt", v).apply()

    /** 上次扫描"手机侧删了什么"的时间（节流用，避免每次相册变动都全库比对） */
    var lastDeletionScanAt: Long
        get() = sp.getLong("lastDeletionScanAt", 0L)
        set(v) = sp.edit().putLong("lastDeletionScanAt", v).apply()

    /** 上次清理过期的回收站批次的时间 */
    var lastTrashPurgeAt: Long
        get() = sp.getLong("lastTrashPurgeAt", 0L)
        set(v) = sp.edit().putLong("lastTrashPurgeAt", v).apply()

    // ---------------------------------------------------------------- 删除同步（反向：NAS → 手机）

    /**
     * 在 NAS 上删掉照片后，手机里那份怎么处理。
     *
     * - `ask`（默认）：弹窗问一句「手机上这份要一起删吗？」—— **不问就不动手机**
     * - `auto`：本地是**降级小图**时直接删；本地还是**原图**时仍然只提示
     *   （原图是仅存的完整副本，自动删风险太高）
     * - `off`：完全不管
     *
     * 与 [deleteSyncMode] 是两个独立方向，刻意分开配置：一边是"我主动删了，剩下那份也删掉"，
     * 另一边是"备份没了，要不要顺手清掉本地"，风险和用户预期都不一样。
     */
    var remoteDeleteSyncMode: String
        get() = sp.getString("remoteDeleteSyncMode", "ask") ?: "ask"
        set(v) = sp.edit().putString("remoteDeleteSyncMode", v).apply()

    /** 上次扫描"NAS 上被删了什么"的时间（节流用；远端扫描比本地全库比对贵得多） */
    var lastRemoteScanAt: Long
        get() = sp.getLong("lastRemoteScanAt", 0L)
        set(v) = sp.edit().putLong("lastRemoteScanAt", v).apply()

    /** 上次弹「NAS 上删了，手机这份也要删吗」的时间（同 [lastDeleteAskAt]，用于定期重问） */
    var lastRemoteAskAt: Long
        get() = sp.getLong("lastRemoteAskAt", 0L)
        set(v) = sp.edit().putLong("lastRemoteAskAt", v).apply()

    /**
     * 「NAS 删了、手机还留着」的未决批次（JSON 数组，格式同 [pendingDeletions]）。
     *
     * **必须与 [pendingDeletions] 分开存**：两个方向的按钮动作完全不同
     * （一个删 NAS、一个删手机），混在一个队列里迟早会删错方向。
     */
    var pendingRemoteDeletions: String
        get() = sp.getString("pendingRemoteDeletions", "") ?: ""
        set(v) = sp.edit().putString("pendingRemoteDeletions", v).apply()

    /**
     * 最近被移进 NAS 回收站的条目索引（JSON：`[{"path","trashRel","at"}]`）。
     *
     * 存在的唯一理由：用户从**系统回收站**点"恢复"后，本地文件的 `_id` 没变，
     * 增量水位线扫不到它 —— 不反查的话这张照片就再也不会被备份了（裸奔）。
     * 有了它就能把 NAS 母本直接搬回原位，而不是重传一遍。
     */
    var trashIndex: String
        get() = sp.getString("trashIndex", "") ?: ""
        set(v) = sp.edit().putString("trashIndex", v).apply()

    /**
     * 实时同步的**增量水位线**：MediaStore 里已处理到的最大 `_id`。
     *
     * 不能每次变化都全库扫描 —— 相册任何变动（缩略图、别的 App 写入）都会触发回调。
     * 只处理 `_id` 大于它的新增项。（初始 0 = 还没建立，服务启动时会先对齐到当前最大值，
     * 避免第一次启动就把整个历史库当成"新增"。）
     */
    var realtimeWatermark: Long
        get() = sp.getLong("realtimeWatermark", 0L)
        set(v) = sp.edit().putLong("realtimeWatermark", v).apply()

    /**
     * 内容触发器上一次被派发的时间，用于**防触发风暴**。
     *
     * 实测踩坑：内容触发型 job 跑完必须重新登记（否则被系统移除、以后永不触发），
     * 但如果"重新登记"发生得太密（例如同时从 `jobFinished(true)`、显式 rearm、
     * `onStopJob` 返回值三处一起要求重排），系统会立刻再派发 → 再重排 → **紧循环**，
     * 30 秒被唤起 **429 次**，纯粹烧电。
     * 所以：距上次派发不足 [STORM_WINDOW_MS] 就**不再重排**，改由其他通道择机重新装备。
     */
    var triggerLastDispatchAt: Long
        get() = sp.getLong("triggerLastDispatchAt", 0L)
        set(v) = sp.edit().putLong("triggerLastDispatchAt", v).apply()


    /** 视频预览目标长边 */
    var videoMaxEdge: Int
        get() = sp.getInt("videoMaxEdge", 1920)
        set(v) = sp.edit().putInt("videoMaxEdge", v).apply()

    /** 视频预览码率 kbps（设定上限；实际还会按体积上限再压） */
    var videoBitrateKbps: Int
        get() = sp.getInt("videoBitrateKbps", 6000)
        set(v) = sp.edit().putInt("videoBitrateKbps", v).apply()

    /**
     * 单个视频小版本的体积上限（MB）。
     * iCloud 的本地视频代理硬上限就是 **100 MB/段**，这里对齐；
     * 超过该体积的长视频会自动降码率来适应。
     */
    var videoMaxMb: Int
        get() = sp.getInt("videoMaxMb", 100)
        set(v) = sp.edit().putInt("videoMaxMb", v).apply()

    // ---------------------------------------------------------------- 策略开关
    /**
     * Ultra HDR（带增益图的 JPEG）是否也降级为 SDR 小版本。
     * 默认**开** —— 按 iCloud / 小米云模型：本地只放小版本，含 HDR 的原图完整留在 NAS。
     * 关掉则本地原样保留（不省空间，但本地也能看到 HDR 效果）。
     */
    var ultraHdrDowngrade: Boolean
        get() = sp.getBoolean("ultraHdrDowngrade", true)
        set(v) = sp.edit().putBoolean("ultraHdrDowngrade", v).apply()

    /**
     * 动态照片（MotionPhoto：JPEG 里内嵌一段短视频）是否降级。
     *
     * 默认**开** —— 走「**保动态降级**」：底图缩小 + 内嵌视频转码后重新拼装，
     * 生成的小版本**依然能长按播放**（已在小米相册实测通过），
     * 所以"省空间"和"保动态"可以兼得。
     *
     * 关掉则完全不碰（本地原样保留，一个字节都不省）。
     */
    var motionPhotoDowngrade: Boolean
        get() = sp.getBoolean("motionPhotoDowngrade", true)
        set(v) = sp.edit().putBoolean("motionPhotoDowngrade", v).apply()

    // ================================================================== 网络通道
    //
    // 设计要点：**每个通道存一份完整的基址**，而不是拆成 host/port/path 再拼。
    // 因为隧道通道（Tailscale / Headscale）的地址形态和局域网完全不同
    // （MagicDNS 域名、100.x.y.z、HTTPS 与否都可能不一样），强行统一字段反而到处是特例。
    // 唯一的例外是公网 IPv6 —— 它的前缀是动态的，必须由前缀 + 后缀拼出来（见下）。

    /**
     * WebDAV 路径部分（`/pool0/data`）。
     *
     * 从 [webdavBase] 里抽出来**复用**给其它通道 —— 换通道只换主机，路径始终一致。
     * 这样用户改一次归档路径，所有通道自动跟着变，不会出现"局域网传 A 目录、
     * 隧道传 B 目录"这种极难发现的分裂。
     */
    val webdavPath: String
        get() = runCatching {
            URL(webdavBase).path.ifBlank { "/pool0/data" }
        }.getOrDefault("/pool0/data")

    /** 局域网通道开关。默认开 —— 这是唯一能跑满速率的通道（实测 73~90 MB/s） */
    var chanLanEnabled: Boolean
        get() = sp.getBoolean("chanLanEnabled", true)
        set(v) = sp.edit().putBoolean("chanLanEnabled", v).apply()

    /**
     * 局域网通道基址。默认空 = 回落用 [webdavBase]（保证老配置无缝升级，
     * 不填也不会把原来的连接弄丢）。
     */
    var chanLanBase: String
        get() = sp.getString("chanLanBase", "") ?: ""
        set(v) = sp.edit().putString("chanLanBase", v).apply()

    // ---------------------------------------------------------------- 公网 IPv6（预埋）

    /**
     * 公网 IPv6 通道开关。
     *
     * ⚠️ **当前默认关闭，因为家里还不通**：光猫（路由模式）的 IPv6 防火墙默认拦全部入站，
     * 且后台**没有**放行模块可用（详见 `ChannelKind.WAN_V6` 的注释）。
     * 一旦外部条件解决，这里打开开关、填好后缀即可，App 侧零改动 —— 这就是「预埋」的含义。
     */
    var chanV6Enabled: Boolean
        get() = sp.getBoolean("chanV6Enabled", false)
        set(v) = sp.edit().putBoolean("chanV6Enabled", v).apply()

    var chanV6Scheme: String
        get() = sp.getString("chanV6Scheme", "https") ?: "https"
        set(v) = sp.edit().putString("chanV6Scheme", v).apply()

    var chanV6Port: Int
        get() = sp.getInt("chanV6Port", 5000)
        set(v) = sp.edit().putInt("chanV6Port", v).apply()

    /**
     * 用哪个后缀拼 NAS 地址：
     * - `both`（默认）：两个都试，哪个通用哪个 —— 最稳，代价只是多探一次
     * - `eui64`：只用 EUI-64 后缀
     * - `dhcp`：只用 DHCPv6 后缀
     */
    var chanV6Mode: String
        get() = sp.getString("chanV6Mode", "both") ?: "both"
        set(v) = sp.edit().putString("chanV6Mode", v).apply()

    /**
     * NAS 的 **EUI-64 后缀**（不含前缀）。
     *
     * 由网卡 MAC 派生（MAC 第 7 位置反 + 插入 `ff:fe`），**只要不换网卡就永不变**，
     * 所以它比 DHCPv6 分配的那个地址可靠得多。家里这台是 MAC `d4:53:2a:c6:db:da`。
     */
    var chanV6Eui64: String
        get() = sp.getString("chanV6Eui64", "d653:2aff:fec6:dbda") ?: "d653:2aff:fec6:dbda"
        set(v) = sp.edit().putString("chanV6Eui64", v).apply()

    /** NAS 的 **DHCPv6 后缀尾巴**（`::` 之后那几位，家里这台是 `8f8`）。路由器重启可能变。 */
    var chanV6DhcpTail: String
        get() = sp.getString("chanV6DhcpTail", "8f8") ?: "8f8"
        set(v) = sp.edit().putString("chanV6DhcpTail", v).apply()

    /** 手填完整 IPv6 地址（填了就**只**用它，不再自动拼）；留空 = 自动 */
    var chanV6ManualAddr: String
        get() = sp.getString("chanV6ManualAddr", "") ?: ""
        set(v) = sp.edit().putString("chanV6ManualAddr", v).apply()

    /**
     * ⭐ **自动学到的家庭 IPv6 前缀**（形如 `240e:3bc:34bb:9b11`）。
     *
     * 手机上连着家里 WiFi 时，它自己也会拿到一个同前缀的全局 v6 地址 ——
     * 取出前 64 位就是家里的前缀。**用户回家一次，前缀自动更新一次**，
     * 所以连 DDNS 都省了（对手机 App 而言）。
     *
     * ⚠️ 只在**局域网端点确认可达**时才允许写回：在外面连别人的 WiFi 也能拿到 v6，
     * 但那是别人家的前缀，写进来只会把通道带偏（判据见 `EndpointResolver.resolve`）。
     */
    var chanV6LearnedPrefix: String
        get() = sp.getString("chanV6LearnedPrefix", "") ?: ""
        set(v) = sp.edit().putString("chanV6LearnedPrefix", v).apply()

    /** 上次学到前缀的时间（界面展示"前缀新不新鲜"） */
    var chanV6LearnedAt: Long
        get() = sp.getLong("chanV6LearnedAt", 0L)
        set(v) = sp.edit().putLong("chanV6LearnedAt", v).apply()

    // ---------------------------------------------------------------- 中转与隧道通道

    /**
     * frp 中转通道开关。
     *
     * ⭐ **当前唯一真正可用的外网通道** —— NAS 上的 frpc 与腾讯云 `1.12.251.37:7000`
     * 早已保持长连接（现有那条 `homeassistant` 隧道就是 `ha.dsr213.cn` 在用的），
     * 加一条指向 WebDAV 的隧道即可。不需要新硬件、不碰光猫、也完全不受 IPv6 影响。
     */
    var chanFrpEnabled: Boolean
        get() = sp.getBoolean("chanFrpEnabled", false)
        set(v) = sp.edit().putBoolean("chanFrpEnabled", v).apply()

    /** 例如 `https://nas.dsr213.cn/pool0/data` 或 `https://1.12.251.37:15000/pool0/data` */
    var chanFrpBase: String
        get() = sp.getString("chanFrpBase", "") ?: ""
        set(v) = sp.edit().putString("chanFrpBase", v).apply()

    /**
     * Tailscale **官方**通道开关。
     *
     * App 侧不需要集成 Tailscale SDK：手机上的 Tailscale 客户端本身就是个 VpnService，
     * 建好隧道后 `100.x.y.z` / MagicDNS 名就是一张普通网卡 —— App 只当它是"另一个 HTTP 端点"。
     * 好处是零额外依赖、零密钥落盘（密钥全在 Tailscale 客户端里管）。
     *
     * ⚠️ 但**当前 NAS 端起不来**：小米 NAS 内核没有 TUN（详见 `ChannelKind.TAILSCALE`）。
     * 这里保留纯属预埋 —— 手机侧配好、NAS 侧将来能跑通时，打开即可用。
     */
    var chanTsEnabled: Boolean
        get() = sp.getBoolean("chanTsEnabled", false)
        set(v) = sp.edit().putBoolean("chanTsEnabled", v).apply()

    /** 例如 `https://nas.tailxxxx.ts.net:5000/pool0/data` 或 `http://100.64.0.7:5000/pool0/data` */
    var chanTsBase: String
        get() = sp.getString("chanTsBase", "") ?: ""
        set(v) = sp.edit().putString("chanTsBase", v).apply()

    /**
     * 自建 Headscale 通道开关。
     *
     * 与 Tailscale 通道**在 App 侧完全同构**（都是走本地 VPN 网卡的普通 HTTP 端点）——
     * 差别只在 control server 是谁：官方 SaaS 还是自己在腾讯云 `1.12.251.37` 上跑的那套。
     * 所以这里刻意分成两个独立通道：可以同时配、同时探，谁快用谁；
     * 迁移时也不必改代码，只是关掉一个、打开另一个。
     */
    var chanHsEnabled: Boolean
        get() = sp.getBoolean("chanHsEnabled", false)
        set(v) = sp.edit().putBoolean("chanHsEnabled", v).apply()

    var chanHsBase: String
        get() = sp.getString("chanHsBase", "") ?: ""
        set(v) = sp.edit().putString("chanHsBase", v).apply()

    /** 自建 control server 地址（仅用于界面引导与备忘，App 不主动连它 —— 那是客户端的事） */
    var chanHsControlUrl: String
        get() = sp.getString("chanHsControlUrl", "https://headscale.dsr213.cn") ?: ""
        set(v) = sp.edit().putString("chanHsControlUrl", v).apply()

    // ---------------------------------------------------------------- 门控与记忆

    /** 备份时机策略，见 `NetPolicy`。默认「仅局域网」—— 最省流量也最省电 */
    var netPolicy: String
        get() = sp.getString("netPolicy", "lan_only") ?: "lan_only"
        set(v) = sp.edit().putString("netPolicy", v).apply()

    /**
     * 上一次**实际连通**的通道（key 见 `ChannelKind`）。
     *
     * 用途：下次同步时可以优先试它。界面上也用它显示"上次走的哪条路"，
     * 排查"为什么这次慢了"时一眼能看出是不是掉到隧道路径上了。
     */
    var lastGoodChannel: String
        get() = sp.getString("lastGoodChannel", "") ?: ""
        set(v) = sp.edit().putString("lastGoodChannel", v).apply()
}
