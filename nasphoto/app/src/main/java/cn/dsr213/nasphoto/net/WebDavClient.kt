package cn.dsr213.nasphoto.net

import android.util.Base64
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * NAS 归档通道 —— 走**小米 NAS 自带的 WebDAV**（nginx :5000）。
 *
 * 为什么不用 SFTP：实测同一条链路（PC→NAS 对照实验），
 *   JSch **0.41 MB/s** / sshj 1.53 MB/s / paramiko 12.8 MB/s / **WebDAV 73~90 MB/s**。
 * WebDAV 是纯 HTTP，App 里连一个 SSH 库都不用带，体积和攻击面都更小，还自带 Range 断点续传。
 *
 * 两个 Java 侧的坑（已绕过）：
 * 1. `HttpURLConnection` 只允许 OPTIONS/GET/HEAD/POST/PUT/DELETE/TRACE/PATCH，
 *    **不接受 MKCOL / PROPFIND** → 建目录改用原生 socket 发 MKCOL（见 [mkcol]）。
 * 2. 所以列目录也改走 nginx 的 `autoindex`（GET 目录返回 HTML），不用 PROPFIND。
 */
class WebDavClient(
    baseUrl: String,
    private val user: String,
    private val password: String,
    private val allowSelfSigned: Boolean = true
) : AutoCloseable {

    /** 形如 https://192.168.31.253:5000/pool0/data */
    val base: String = baseUrl.trimEnd('/')

    var tlsMode: String = "未连接"
        private set

    var lastError: String? = null
        private set

    private val auth: String = "Basic " + Base64.encodeToString(
        "$user:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
    )

    /** null = 走系统信任链（严格校验）；非 null = 已回退到宽松模式 */
    private var permissiveFactory: SSLSocketFactory? = null

    private val baseUrlObj = URL(base)

    /**
     * ⚠️ **IPv6 字面量必须剥掉方括号**。
     *
     * `URL("https://[240e:3bc::8f8]:5000/x").host` 返回的是**带方括号**的 `[240e:3bc::8f8]`，
     * 直接喂给 `InetSocketAddress` 会解析失败。
     *
     * 但 HTTP 的 `Host` 头**反而必须带**方括号 —— 所以两个变量分开存，绝不能混用。
     *
     * 为什么之前没暴露：局域网通道是纯 IPv4，`HttpURLConnection` 那条路它自己处理得对，
     * 只有 [rawRequest] 这条手写 socket 的路（MKCOL / MOVE）会踩。
     * 一旦启用 IPv6 通道，**所有"移进回收站"的操作都会挂**，而且报错完全看不出来是方括号问题。
     */
    private val host: String = baseUrlObj.host.trim('[', ']')
    private val hostHeader: String = if (host.contains(':')) "[$host]" else host
    private val port: Int = if (baseUrlObj.port > 0) baseUrlObj.port
    else if (baseUrlObj.protocol == "https") 443 else 80
    private val basePath: String = baseUrlObj.path.trimEnd('/')
    private val isHttps: Boolean = baseUrlObj.protocol == "https"

    // ------------------------------------------------------------------ 连接
    /**
     * 先用**系统信任链**校验；只有握手失败才退回宽松模式。
     * 之所以允许宽松：NAS 用的是小米 IoT 网关注发的设备证书（有效期仅 ~21 天、会轮换），
     * 固定指纹必然失效。链路是局域网/Tailscale，配合 Basic 认证可接受。
     *
     * 实测：**手机侧系统信任链不认这张证书**，所以实际会走宽松模式（界面会显示模式）。
     */
    fun connect(timeoutMs: Int = 15000) {
        lastError = null
        try {
            val code = probe(timeoutMs, permissive = false)
            tlsMode = "系统信任链（严格校验）"
            if (code !in 200..499) lastError = "探测返回 $code"
            return
        } catch (t: Throwable) {
            val msg = t.message ?: t.javaClass.simpleName
            if (!allowSelfSigned) {
                lastError = "TLS 校验失败：$msg"
                throw t
            }
            lastError = "严格校验不通过，已回退宽松模式"
        }
        val code = probe(timeoutMs, permissive = true)
        tlsMode = "自签设备证书（未做 CA 校验）"
        if (code !in 200..499) lastError = "探测返回 $code"
    }

    private fun probe(timeoutMs: Int, permissive: Boolean): Int {
        val c = open("OPTIONS", "", permissive, timeoutMs)
        return try {
            c.responseCode
        } finally {
            c.disconnect()
        }
    }

    /**
     * 宽松 TLS 工厂直接取自共享的 [Tls]。
     *
     * 为什么不各写一份：**通道探测和真实传输必须是同一个 factory 实例**，
     * 否则迟早出现"探测说通、真传时握手失败"的错配 —— 两边日志都显示成功过，
     * 这类问题极难定位。所以全 App 统一走 [Tls.permissiveFactory]。
     */
    private fun trustAllFactory(): SSLSocketFactory =
        Tls.permissiveFactory().also { permissiveFactory = it }

    private val permissive: Boolean get() = permissiveFactory != null

    // ------------------------------------------------------------------ URL
    private fun encodePath(rel: String): String =
        rel.split('/').filter { it.isNotEmpty() }
            .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun urlOf(rel: String): String {
        val p = encodePath(rel)
        return if (p.isEmpty()) "$base/" else "$base/$p"
    }

    private fun open(
        method: String,
        rel: String,
        permissive: Boolean = this.permissive,
        timeoutMs: Int = 15000
    ): HttpURLConnection {
        val c = URL(urlOf(rel)).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeoutMs
        c.readTimeout = maxOf(timeoutMs, 60000)
        c.instanceFollowRedirects = true
        c.setRequestProperty("Authorization", auth)
        c.setRequestProperty("User-Agent", "NasPhoto/0.1")
        if (c is HttpsURLConnection && permissive) {
            c.sslSocketFactory = trustAllFactory()
            c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        return c
    }

    // ------------------------------------------------------------------ 目录
    /**
     * 逐级 MKCOL。`HttpURLConnection` 不支持 MKCOL，所以这里用一次性原生 socket 手写请求。
     * 实测服务端：201=已创建，405=已存在（视为成功）。
     */
    fun ensureDirs(relDir: String) {
        var cur = ""
        for (p in relDir.split('/').filter { it.isNotEmpty() }) {
            cur = if (cur.isEmpty()) p else "$cur/$p"
            val code = mkcol(cur)
            if (code !in 200..299 && code != 405) {
                lastError = "MKCOL $cur -> $code"
            }
        }
    }

    private fun mkcol(rel: String): Int = rawRequest("MKCOL", rel)

    /**
     * 发一个 `HttpURLConnection` **不支持**的请求（MKCOL / MOVE / PROPFIND…），走一次性原生 socket。
     *
     * 为什么要手写：`HttpURLConnection` 的 `requestMethod` 有方法白名单
     * （只允许 OPTIONS/GET/HEAD/POST/PUT/DELETE/TRACE/PATCH），
     * 设成 MKCOL / MOVE 会抛 `ProtocolException`。幸好这几个方法都不需要请求体，
     * 手写 HTTP/1.1 报文足够，也不用额外引 HTTP 库。
     *
     * 只读第一行状态码 —— 调用方基本只需要知道成没成。
     */
    private fun rawRequest(
        method: String,
        rel: String,
        extraHeaders: List<Pair<String, String>> = emptyList()
    ): Int {
        var sock: Socket? = null
        return try {
            val plain = Socket()
            plain.connect(InetSocketAddress(host, port), 15000)
            plain.soTimeout = 20000
            sock = if (isHttps) {
                val f: SSLSocketFactory = permissiveFactory ?: SSLContext.getDefault().socketFactory
                (f.createSocket(plain, host, port, true) as SSLSocket).apply {
                    if (!permissive) {
                        val prm = sslParameters
                        prm.endpointIdentificationAlgorithm = "HTTPS"
                        sslParameters = prm
                    }
                    startHandshake()
                }
            } else plain

            val path = if (rel.isEmpty()) basePath else "$basePath/${encodePath(rel)}"
            val req = buildString {
                append("$method $path HTTP/1.1\r\n")
                // 用 hostHeader 而不是 host —— IPv6 地址在 Host 头里必须带方括号
                append("Host: $hostHeader:$port\r\n")
                append("Authorization: $auth\r\n")
                append("User-Agent: NasPhoto/0.1\r\n")
                for ((k, v) in extraHeaders) append("$k: $v\r\n")
                append("Content-Length: 0\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            sock.getOutputStream().apply { write(req.toByteArray(Charsets.UTF_8)); flush() }
            val status = sock.getInputStream().bufferedReader().readLine() ?: ""
            status.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
        } catch (t: Throwable) {
            lastError = "$method 异常：${t.message}"
            -1
        } finally {
            try {
                sock?.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * MOVE：在 NAS 上把文件搬到另一个位置。返回 HTTP 状态码（2xx 为成功，实测 204）。
     *
     * 用途：**删除一律先"移进回收站"，绝不硬删** —— 母本只有一份。
     * 移完原位立刻 404，`_回收站/<日期>/<原相对路径>` 剥掉日期目录就是原位，还原 = 再一次 MOVE。
     *
     * ⚠️ 两个必须遵守的点（都实测踩过）：
     * 1. 不能用 `HttpURLConnection` —— 方法白名单里没有 MOVE（见 [rawRequest]）。
     * 2. `Destination` 头**必须是绝对 URL，且路径整体百分号编码**。
     *    路径里有中文时不编码会在**客户端**就炸（Python 版是 `UnicodeEncodeError: latin-1`），
     *    跟服务端无关，报错信息还极具误导性。这里复用 [urlOf]，它按段编码。
     */
    fun move(fromRel: String, toRel: String): Int = rawRequest(
        "MOVE", fromRel,
        listOf("Destination" to urlOf(toRel), "Overwrite" to "F")
    )

    /** 小文本文件读取（配置之类）；失败返回 null */
    fun readText(rel: String): String? {
        val c = open("GET", rel)
        return try {
            if (c.responseCode !in 200..299) null
            else c.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Throwable) {
            null
        } finally {
            c.disconnect()
        }
    }

    /** 文件/目录是否存在（用 HEAD，目录在 WebDAV 上也有长度信息） */
    fun existsPath(rel: String): Boolean {
        val c = open("HEAD", rel)
        return try {
            c.responseCode in 200..299
        } catch (_: Throwable) {
            false
        } finally {
            c.disconnect()
        }
    }

    /** 列目录的结果项：名字 + 是不是目录 */
    /**
     * 远端条目。
     *
     * ⭐ [size] 与 [mtime] 来自 **nginx autoindex 的 HTML 表格**，实测该服务器返回的标准格式
     * 内部就带着这两列：
     * ```
     * <a href="rec.mp4">rec.mp4</a>            17-Sep-2026 10:00        1505179
     * <a href="DCIM/">DCIM/</a>                 17-Sep-2026 10:00              -
     * ```
     * ⇒ **一次 GET 就能拿到整目录的大小与时间**，不必逐文件 HEAD
     * （整库回灌规划里，这省下了「N 个文件 = N 次请求」）。
     *
     * ⚠️ `size < 0` 表示**没解析出来**（服务器换了 autoindex 格式、或行被改写），
     * **不是「0 字节」**。调用方拿它累加总量时必须单独对待，
     * 否则会把"未知"当成"空文件"，算出一个虚低的空间需求。
     *
     * ⚠️ [mtime] 是 **GMT**：nginx 默认 `autoindex_localtime off`，
     * 本机实测目录项显示 `16:11`（GMT）而 NAS 本地 `ls` 是次日 `00:11`（+08）。
     */
    data class RemoteEntry(
        val name: String,
        val isDir: Boolean,
        val size: Long = -1L,
        val mtime: Long = 0L
    )

    /**
     * 列目录，**能分辨目录与文件**（[list] 只返回名字，做递归扫描会撞墙）。
     *
     * 走的是 nginx `autoindex` 生成的 HTML：目录项的 href 以 `/` 结尾，文件项不以 `/` 结尾。
     * 用 `PROPFIND` 会更规范，但 `HttpURLConnection` 不支持该方法（见 [rawRequest]）。
     */
    fun listEntries(relDir: String): List<RemoteEntry> = listEntriesOrNull(relDir) ?: emptyList()

    /**
     * 同 [listEntries]，但**失败与"空目录"分得开**：读不到返回 `null`。
     *
     * ⚠️ 这个区分是删除同步的生死线。用 `listEntries` 时，一次网络抖动返回的空列表
     * 和"目录里真的没东西"长得一模一样 —— 递归扫描会把整批文件判成"NAS 上被删了"，
     * 接着就是一次不可逆的误删。凡是拿目录内容去做**删/移**决策的地方，必须用这个。
     */
    fun listEntriesOrNull(relDir: String): List<RemoteEntry>? {
        val c = open("GET", relDir)
        return try {
            if (c.responseCode !in 200..299) return null
            val html = c.inputStream.bufferedReader().use { it.readText() }
            ANCHOR.findAll(html)
                .mapNotNull { m ->
                    val raw = m.groupValues[1]
                    val dec = try {
                        URLDecoder.decode(raw, "UTF-8")
                    } catch (_: Throwable) {
                        raw
                    }
                    val isDir = dec.endsWith("/")
                    val name = dec.trimEnd('/').substringAfterLast('/')
                    // 跳过父目录项与排序参数链接（nginx 的 "?C=N;O=D"）
                    if (name.isEmpty() || name == ".." || name == "." || raw.startsWith("?")) return@mapNotNull null
                    // `</a>` 之后的文本才是 nginx 的「日期 时间 大小」列（见 RemoteEntry 注释）
                    val meta = parseAutoindexMeta(m.groupValues[3])
                    RemoteEntry(name, isDir, if (isDir) -1L else meta.first, meta.second)
                }
                .distinctBy { it.name }
                .toList()
        } catch (t: Throwable) {
            lastError = "列目录失败：${t.message}"
            null
        } finally {
            c.disconnect()
        }
    }

    /**
     * 递归列出整棵子树下所有**文件**的相对路径（相对 [base]）。
     *
     * ⚠️ **不能一次 `PROPFIND Depth: infinity`** —— nginx 的 dav 模块默认拒绝无限深度，
     * 返回 **403**。只能逐层 `GET` + autoindex 递归。
     * 好在成本极低：实测 115 个文件 / 9 个目录 = 10 次请求、约 0.6 秒。
     *
     * @param skipDirs 顶层要跳过的子目录名（App 用来排除 `_回收站`）
     * @return null 表示**中途有目录列不出来**（网络异常）——
     *   调用方必须据此放弃本轮判定，否则会把"扫描失败"误当成"文件被删了"。
     */
    fun listFilesRecursive(rootRel: String, skipDirs: Set<String> = emptySet()): List<String>? =
        walkFilesRecursive(rootRel, skipDirs)?.let { it.keys.toList() }

    /**
     * 同 [listFilesRecursive]，但**连每条的大小与时间一起带回来**。
     *
     * 回灌规划需要"整库一共多少字节"才能做空间熔断；走 [size] 逐文件 HEAD
     * 等于 N 次请求，而 autoindex 的 HTML 里本来就写着（见 [RemoteEntry]）。
     * ⇒ 扫描一次、元信息全有。
     *
     * ⚠️ 返回 `null` 依旧表示**中途有目录列不出来**，与"这棵树是空的"严格区分 ——
     * 拿它做"要不要回灌"的决策时，`null` 必须走报错分支。
     */
    fun walkFilesRecursive(
        rootRel: String,
        skipDirs: Set<String> = emptySet()
    ): Map<String, RemoteEntry>? {
        val out = LinkedHashMap<String, RemoteEntry>(512)
        var level = listOf(rootRel)
        var depth = 0
        while (level.isNotEmpty()) {
            if (depth++ > 12) break // 防环
            val next = ArrayList<String>()
            for (dir in level) {
                // ⚠️ 必须用 strict 版：`listEntries` 在读失败时也返回空列表，
                //    于是整棵子树会被当成"空目录"→ 上层判定为"文件都被删了"（不可逆误删）。
                val entries = listEntriesOrNull(dir) ?: return null
                for (e in entries) {
                    val child = if (dir.isEmpty()) e.name else "$dir/${e.name}"
                    if (e.isDir) {
                        if (depth == 1 && e.name in skipDirs) continue
                        next.add(child)
                    } else {
                        if (depth == 1 && e.name.startsWith(".")) continue // 归档根下的点文件
                        out[child] = e
                    }
                }
            }
            level = next
        }
        return out
    }

    /** 远端文件大小；不存在返回 -1 */
    fun size(rel: String): Long {
        val c = open("HEAD", rel)
        return try {
            if (c.responseCode in 200..299) c.contentLengthLong else -1L
        } catch (t: Throwable) {
            -1L
        } finally {
            c.disconnect()
        }
    }

    fun exists(rel: String): Boolean = size(rel) >= 0

    /**
     * 删除远端一个文件或目录。
     *
     * **true 的两种确定情况**：`2xx` 真的删掉了；`404/410` 本来就不存在。
     * 后者算成功是刻意的 —— 删一个已经不在的文件，目标已经达成，若判为失败，
     * 批量删除的计数永远对不上，上层还会一直重试一个不可能成功的操作。
     *
     * ⚠️ **异常一律吞掉返回 false，绝不外抛**：调用方都在 `for` 循环里批量删，
     * 一次网络抖动外抛会把整个循环**从中间打断** —— 前面已删的不会回来，
     * 后面的全没删，而上层只看到一句"失败"，以为什么都没发生。这是 #90 排查到的
     * 最危险的一类情况，所以这里必须把异常挡住，把真相交给调用方统计。
     *
     * ⚠️ 超时（响应读不到）时返回 false = **"不确定"，不是"没删"**。
     * 服务端可能已经删了，只是响应没回来。调用方按"没删"处理是安全方向
     * （最坏是下次重试一次幂等的 DELETE），但**不能**据此认为数据还在。
     */
    fun delete(rel: String): Boolean {
        val c = try {
            open("DELETE", rel)
        } catch (t: Throwable) {
            lastError = "删除失败（连接异常）：${t.message}"
            return false
        }
        return try {
            when (val code = c.responseCode) {
                in 200..299 -> true
                404, 410 -> true
                else -> {
                    lastError = "删除 $rel 返回 HTTP $code"
                    false
                }
            }
        } catch (t: Throwable) {
            lastError = "删除 $rel 读响应异常：${t.message}"
            false
        } finally {
            c.disconnect()
        }
    }

    /** 列目录：走 nginx autoindex 的 HTML（PROPFIND 不被 HttpURLConnection 支持） */
    fun list(relDir: String): List<String> {
        val c = open("GET", relDir)
        return try {
            if (c.responseCode !in 200..299) return emptyList()
            val html = c.inputStream.bufferedReader().use { it.readText() }
            Regex("<a\\s+href=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { URLDecoder.decode(it.groupValues[1], "UTF-8") }
                .filter { it != "../" && it.isNotBlank() }
                .map { it.trimEnd('/').substringAfterLast('/') }
                .toList()
        } catch (t: Throwable) {
            lastError = "列目录失败：${t.message}"
            emptyList()
        } finally {
            c.disconnect()
        }
    }

    // ------------------------------------------------------------------ 上传
    /** PUT 上传（定长流式，不进内存）。返回 HTTP 状态码，2xx 为成功。 */
    fun upload(local: File, rel: String, onProgress: ((Long, Long) -> Unit)? = null): Int {
        val parent = rel.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) ensureDirs(parent)

        val c = open("PUT", rel, timeoutMs = 30000).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/octet-stream")
            setFixedLengthStreamingMode(local.length())
        }
        return try {
            FileInputStream(local).use { ins ->
                c.outputStream.use { out ->
                    val buf = ByteArray(256 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        onProgress?.invoke(sent, local.length())
                    }
                    out.flush()
                }
            }
            c.responseCode
        } catch (t: Throwable) {
            lastError = "上传失败：${t.message}"
            -1
        } finally {
            c.disconnect()
        }
    }

    /** 流式读回并算 SHA-256（不落盘，内存恒定）—— "确实原样落在 NAS 上"的端到端证据 */
    fun remoteSha256(rel: String): String? {
        val c = open("GET", rel, timeoutMs = 30000)
        return try {
            if (c.responseCode !in 200..299) return null
            val md = MessageDigest.getInstance("SHA-256")
            c.inputStream.use { ins ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            lastError = "读回校验失败：${t.message}"
            null
        } finally {
            c.disconnect()
        }
    }

    // ------------------------------------------------------------------ 下载
    /** GET 下载，本地已有部分内容时自动发 Range 续传。 */
    fun download(rel: String, local: File, onProgress: ((Long, Long) -> Unit)? = null): Boolean {
        local.parentFile?.mkdirs()
        val have = if (local.exists()) local.length() else 0L

        val c = open("GET", rel, timeoutMs = 30000)
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        return try {
            val code = c.responseCode
            if (code !in 200..299) {
                lastError = "下载失败 HTTP $code"
                return false
            }
            val append = (code == 206) && have > 0
            if (!append && local.exists()) local.delete()

            FileOutputStream(local, append).use { out ->
                c.inputStream.use { ins ->
                    val buf = ByteArray(256 * 1024)
                    var got = if (append) have else 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        onProgress?.invoke(got, -1L)
                    }
                    out.flush()
                }
            }

            val remoteLen = size(rel)
            if (remoteLen >= 0 && local.length() != remoteLen) {
                lastError = "长度不符：本地 ${local.length()} vs 远端 $remoteLen"
                return false
            }
            true
        } catch (t: Throwable) {
            lastError = "下载异常：${t.message}"
            false
        } finally {
            c.disconnect()
        }
    }

    override fun close() {
        // HttpURLConnection 逐请求开关连接；原生 socket 用完即关，无常驻会话。
    }

    companion object {
        /**
         * 匹配 autoindex 的一个条目，并**把 `</a>` 之后的列一起抓进来**。
         *
         * group1 = href · group2 = 显示名（⚠️ 长名字会被 nginx 截断成 `xxx..`，
         * **只能看、不能当文件名用** —— 文件名一律取 group1）· group3 = 「日期 时间 大小」列。
         *
         * ⚠️ group3 用 `[^<\n]*` **卡死在行内**；放开的话会一路吃到下一个标签，
         * 把别的条目的日期与大小算到这一条头上 —— 这类错配不会报错，只会让总量悄悄错掉。
         */
        private val ANCHOR = Regex(
            "<a\\s+href=\"([^\"]+)\"[^>]*>(.*?)</a>([^<\\n]*)",
            // ⚠️ Kotlin 的 RegexOption 是 **enum**，没有 `or` —— 那是 Java `Pattern` 的位运算写法。
            //    这里必须给 Set（写成 `IGNORE_CASE or DOT_MATCHES_ALL` 会直接编译不过）。
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        /** nginx autoindex 的「日期 时间 大小」，例：`17-Sep-2026 10:00             1505179` */
        private val AI_META = Regex(
            "(\\d{2})-([A-Za-z]{3})-(\\d{4})\\s+(\\d{2}):(\\d{2})\\s+(\\d+|-)"
        )

        /**
         * ⚠️ `SimpleDateFormat` **不是线程安全的**，而列目录存在并发调用
         * （回灌扫描与删除检查可能同时在跑）⇒ 用 ThreadLocal 各持一份，别做成单例字段。
         */
        private val AI_TIME = object : ThreadLocal<java.text.SimpleDateFormat>() {
            override fun initialValue(): java.text.SimpleDateFormat =
                java.text.SimpleDateFormat("dd-MMM-yyyy HH:mm", java.util.Locale.ENGLISH).apply {
                    // nginx 默认 `autoindex_localtime off` ⇒ 输出的是 GMT，按本地时区解析会偏 8 小时
                    timeZone = java.util.TimeZone.getTimeZone("GMT")
                }
        }

        /**
         * 解析 autoindex 条目尾部 → `(size, mtimeMs)`。
         *
         * 解析不出来时返回 `(-1L, 0L)`：**`-1` 表示"不知道"，不是"0 字节"**。
         * 调用方拿它累加空间需求时必须把 `-1` 单列，否则未知会被当成空文件，算出一个虚低的总量。
         */
        private fun parseAutoindexMeta(tail: String): Pair<Long, Long> {
            val m = AI_META.find(tail) ?: return -1L to 0L
            val size = m.groupValues[6].let { if (it == "-") -1L else it.toLongOrNull() ?: -1L }
            val t = try {
                AI_TIME.get()?.parse(
                    "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]} " +
                        "${m.groupValues[4]}:${m.groupValues[5]}"
                )
            } catch (_: Throwable) {
                null
            }
            return size to (t?.time ?: 0L)
        }
    }
}
