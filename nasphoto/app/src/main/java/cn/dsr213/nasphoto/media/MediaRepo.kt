package cn.dsr213.nasphoto.media

import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import cn.dsr213.nasphoto.engine.StoragePermission
import java.io.File

/**
 * 媒体库的"现状快照" —— 用来和数据库比对，判断**手机侧删掉了什么**。
 *
 * 刻意把"系统回收站"分出来：小米相册删除时是**移进系统回收站**（Android 11+ 的
 * 「最近删除」，30 天），不是立刻销毁。这类条目用户随时可能点"恢复"，
 * 所以**不能**当成"已删除"处理。真到 30 天后被系统清掉，那时才算。
 */
data class LibraryPaths(
    /** 正常可见的条目 */
    val visible: Set<String>,
    /** 躺在系统回收站里的条目 */
    val trashed: Set<String>
) {
    val all: Set<String> get() = visible + trashed
}

data class MediaItem(
    val id: Long,
    val path: String,
    val name: String,
    val mime: String,
    val size: Long,
    /** 拍摄时间（毫秒） */
    val takenAt: Long,
    val bucket: String,
    val isFavorite: Boolean,
    /** MediaStore 记录的宽高，0 表示未知 */
    val width: Int,
    val height: Int,
    val isVideo: Boolean = false,
    val durationMs: Long = 0,
    /** 视频色彩元数据（MediaStore，API30+）；0/NULL 表示未知 */
    val colorTransfer: Int = 0,
    val colorStandard: Int = 0,
    val colorRange: Int = 0
) {
    val longEdge: Int get() = maxOf(width, height)
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
}

class MediaRepo(private val ctx: Context) {

    /** 图片 + 视频 */
    fun all(): List<MediaItem> = images() + videos()

    // ---------------------------------------------------------------- 路径规范化

    /**
     * 把 MediaStore 的 `_data` 规范化成**唯一形态**，消除大小写分裂。
     *
     * ⚠️ 这不是洁癖，是必须的。实测（2026-09-16）媒体库里同时存在两套路径：
     * ```
     * _id=1927  /storage/emulated/0/DCIM/Camera/IMG_20231202_113034.jpg   ← 相机写的
     * _id=5906  /storage/emulated/0/dcim/Screenshots/Screenshot_…jpg      ← MIUI 截图写的
     * ```
     * Android 的 `/storage/emulated/0` 是大小写**不敏感**的 FUSE，两者指向同一个真实目录；
     * 但我们的 `remotePath` 是拿这个字符串拼出来的，于是 NAS 上真的分裂出了
     * `dcim/` 和 `DCIM/` 两个目录 —— 同一批照片散在两边。
     *
     * 只规范化**紧跟在存储根之后的第一段**（Android 公共目录所在位置），
     * 不去动更深层的目录名 —— 免得误伤用户自己建的 `Download/dcim/`。
     *
     * ## ⚠️⚠️ 2026-09-17 扩展：只处理 `dcim` 是不够的（#86「假母本」的真因）
     *
     * 原实现只认 `dcim` 一段，结果 `pictures` 漏了。实测（小米 HyperOS）同一个微信图片
     * 在 MediaStore 里以两种写法出现，**取决于写入它的 App**：
     * ```
     * 微信保存 →  /storage/emulated/0/pictures/WeiXin/x.jpeg   （小写）
     * 早先的扫描 → /storage/emulated/0/Pictures/WeiXin/x.jpeg   （大写）
     * ```
     * 于是同一张照片在库里有了**两条记录**、NAS 上有了**两份母本**：
     * 大写那份是真原图（2.8 MB，09-15 降级时上传），小写那份是后来把**已经降级的小图**
     * （646 KB）又当新文件传了一遍 —— 这就是「假母本」。
     * 更糟的是往下游传：`pickFrom` 用 path 判「已降级」，`pictures/…` 不在
     * `offloaded` 集合里 ⇒ 每一轮全量扫描都会把它再传一次。
     *
     * ⇒ 所以改成**公共目录白名单**，把第一段归一到 Android 的标准写法。
     * 白名单之外的目录（用户自建的 `MyFolder/`、SD 卡根的散图）**一律不碰**。
     */
    private fun canonical(p: String): String = canonicalPath(p)

    companion object {

        /**
         * 路径规范化 —— **唯一的实现**，实例方法 [canonical] 与存量修复探针都走它。
         *
         * 抽成 companion 的公开方法是因为**存量数据修复需要同一套规则**：
         * 库里那批用小写写法记下来的记录，必须按与扫描时完全一致的口径改回标准写法，
         * 各写一套的话早晚会分叉（改完仍然对不上，问题看起来像"没修好"）。
         */
        fun canonicalPath(p: String): String {
            for (root in STORAGE_ROOTS) {
                val i = p.indexOf(root)
                if (i < 0) continue
                val head = p.substring(0, i + root.length)
                val rest = p.substring(i + root.length)
                val seg = rest.substringBefore('/')
                // 不在公共目录白名单里 → 原样返回（用户自建目录不动）
                val std = PUBLIC_DIRS[seg.lowercase()] ?: return p
                // 已经是标准写法 → 原样返回（省一次字符串拼接）
                if (seg == std) return p
                return head + std + rest.substring(seg.length)
            }
            return p
        }

        /**
         * **远端路径**规范化 —— 作用于 `remotePath`（`Prefs.remoteRoot + "/" + 相对路径`）。
         *
         * ⚠️⚠️ **绝不能拿 [canonicalPath] 来处理 `remotePath`**：
         * 那个函数按 `/storage/emulated/0/` 这类**本地存储根**匹配，
         * 而 `remotePath` 形如 `WorkSpace/NasPhoto归档/pictures/WeiXin/x.jpg` ——
         * **一个存储根都不含**，于是它走到最后 `return p`，**原样返回、什么都不改**。
         *
         * 后果不是崩溃而是**假日志**：调用点通常写成
         * `val ok = newRemote == oldRemote || client.exists(newRemote)` ——
         * 相等分支恒真 ⇒ 永远打印"已修远端"，而 `remotePath` 一个字都没动。
         * 我就这样漏过一条指向不存在路径的 `remotePath`（点「恢复」直接 404）。
         *
         * → 两类路径**两套规则**，别共用；判据要挑**改了才会变**的那个。
         */
        fun canonicalRemotePath(remotePath: String, remoteRoot: String): String {
            val prefix = remoteRoot.trimEnd('/') + "/"
            if (!remotePath.startsWith(prefix)) return remotePath
            val rel = remotePath.substring(prefix.length)
            val seg = rel.substringBefore('/')
            val std = PUBLIC_DIRS[seg.lowercase()] ?: return remotePath
            if (seg == std) return remotePath
            return prefix + std + rel.substring(seg.length)
        }

        val STORAGE_ROOTS = listOf(
            "/storage/emulated/0/",
            "/storage/emulated/legacy/",
            "/storage/self/primary/",
            "/sdcard/"
        )

        /**
         * 存储根之后**第一段**的标准写法 —— Android 的公共目录集合。
         *
         * 只列「由系统/各家 App 按公共约定写入、因而可能大小写不一致」的那些；
         * 不在此列的目录名保持原样，避免把用户自建目录改名。
         */
        val PUBLIC_DIRS = mapOf(
            "dcim" to "DCIM",
            "pictures" to "Pictures",
            "download" to "Download",
            "movies" to "Movies",
            "music" to "Music",
            "documents" to "Documents",
            "audiobooks" to "Audiobooks",
            "podcasts" to "Podcasts",
            "recordings" to "Recordings",
            "ringtones" to "Ringtones",
            "alarms" to "Alarms",
            "notifications" to "Notifications"
        )
    }

    // ---------------------------------------------------------------- 删除检测用

    private val libUris = listOf(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    )

    /**
     * 媒体库现状（只看路径，不建对象，比 [all] 轻）。
     *
     * **任何一个查询抛异常就整体返回 null** —— 调用方据此放弃本轮删除判定。
     * 这条非常重要：半截的结果会被误读成"一大批文件被删了"，而删除是不可逆的方向，
     * 宁可这一轮什么都不做。
     */
    fun libraryPaths(): LibraryPaths? {
        // 「可见集」读不到 = 什么都不知道 → 整轮放弃（绝不能拿半截结果去判"删了"）
        val visible = try {
            queryPaths(matchTrashed = -1)
        } catch (t: Throwable) {
            android.util.Log.w("NasPhotoDel", "读取媒体库失败，本轮放弃删除判定：${t.message}")
            return null
        }

        // 「系统回收站集」只影响"要不要问一句"，读不到就降级成空集继续 ——
        // 不能因为它把整个功能拖死（实测有些 ROM 的 MediaProvider 对 Bundle 查询支持不一）。
        val trashed = try {
            queryPaths(matchTrashed = MediaStore.MATCH_ONLY)
        } catch (t: Throwable) {
            android.util.Log.w("NasPhotoDel", "回收站查询不可用，已降级：${t.message}")
            emptySet()
        }
        return LibraryPaths(visible, trashed)
    }

    /** @param matchTrashed -1 = 默认（不含回收站）；其余值直接传给 `QUERY_ARG_MATCH_TRASHED` */
    private fun queryPaths(matchTrashed: Int): Set<String> {
        val proj = arrayOf(MediaStore.MediaColumns.DATA)
        val out = HashSet<String>(1024)
        for (uri in libUris) {
            val cur = if (matchTrashed < 0) {
                ctx.contentResolver.query(uri, proj, null, null, null)
            } else {
                val args = Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, matchTrashed)
                }
                ctx.contentResolver.query(uri, proj, args, null)
            }
            cur?.use { c ->
                while (c.moveToNext()) {
                    // 个别条目 DATA 可能为 null（还在 pending 等），跳过即可
                    c.getString(0)?.takeIf { it.isNotBlank() }?.let { out.add(canonical(it)) }
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- 删除本地（反向同步用）

    /**
     * 真正把本地媒体删掉：**先删文件，再清 MediaStore 条目**。
     *
     * 两步缺一不可：
     * - 只删文件 → MediaStore 里留一条指向不存在文件的**僵尸记录**，相册里会出现打不开的灰块
     * - 只删条目 → 文件还在，下次媒体扫描又把它收回来（删了个寂寞）
     *
     * @return true = 文件确实已经不在了（删成功，或本来就不在）
     */
    fun deleteLocal(path: String): Boolean {
        // ⚠️ 先问权限（#103）。`MANAGE_EXTERNAL_STORAGE` 被撤销时系统**不会通知 App**，
        //    而这里删的都是相机/微信创建的文件 —— 没有该权限时 `f.delete()` 必然返回 false。
        //    提前挡掉并写明原因，好过让调用方对着一个 `false` 猜是"文件本来就没了"
        //    还是"权限没了"。两种情况的处理完全不同：前者该清库记录，后者绝不能清。
        if (!StoragePermission.canWritePublicStorage(ctx)) {
            android.util.Log.w(
                "NasPhotoDel",
                "缺「所有文件访问」权限，拒删本地文件（未做任何改动）：$path"
            )
            return false
        }

        val f = File(path)

        // ① 文件本身（有 MANAGE_EXTERNAL_STORAGE，直接删即可）
        val fileGone = if (!f.exists()) true else runCatching { f.delete() }.getOrDefault(false)
        if (!fileGone) {
            android.util.Log.w("NasPhotoDel", "删不掉本地文件：$path")
            return false
        }

        // ② MediaStore 条目。删 Files 表覆盖图片/视频/其他所有类型。
        //
        // ⚠️ **不能**只用 `_data = ?` 精确匹配：库里存的可能是另一套大小写
        //    （见 canonical()），精确匹配会一条都删不掉，留下指向已删文件的僵尸记录，
        //    相册里就是一块打不开的灰。故先做大小写不敏感匹配，再退回精确匹配。
        val uri = MediaStore.Files.getContentUri("external")
        val byLower = runCatching {
            ctx.contentResolver.delete(
                uri, "LOWER(${MediaStore.MediaColumns.DATA}) = LOWER(?)", arrayOf(path)
            )
        }.getOrDefault(0)
        val removed = if (byLower > 0) byLower else runCatching {
            ctx.contentResolver.delete(
                uri, "${MediaStore.MediaColumns.DATA} = ?", arrayOf(path)
            )
        }.getOrDefault(0)

        if (removed <= 0) {
            runCatching {
                android.media.MediaScannerConnection.scanFile(ctx, arrayOf(path), null, null)
            }.onFailure { android.util.Log.w("NasPhotoDel", "媒体扫描兜底失败：${it.message}") }
        }
        return !f.exists()
    }

    // ---------------------------------------------------------------- 增量游标
    //
    // 实时监听（见 service/SyncService）**不能**每次变化都全库扫描 ——
    // MediaStore 的任何变动（缩略图生成、别的 App 写入）都会触发 ContentObserver，
    // 全表扫几百上千条会立刻把电耗打上去。
    // 所以用一个 `_id` 水位线：只取比上次更大的 id。

    /** 当前媒体库里最大的 `_id`，用作增量水位线 */
    fun maxId(): Long {
        var m = 0L
        for (uri in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )) {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        if (id > m) m = id
                    }
                }
        }
        return m
    }

    /**
     * 列出**媒体库里真实存在**的相册名 → 该相册的条目数（图片 + 视频合并计数）。
     *
     * 为什么需要它：设置页里「保护相册」（`Prefs.protectedBuckets`）原先只能靠改 prefs 设置，
     * 界面上没有入口；而如果做成"让用户手打相册名"，打错一个不存在的名字，
     * 界面上照样显示"已保护 XXX"，实际一个文件都不会命中 —— **静默失效**，
     * 用户以为自己保护好了。所以列表必须来自 MediaStore 实测。
     *
     * ⚠️ 只取 `bucket_display_name` 一列，不拉整库 —— 这是零成本可得的元信息。
     * ⚠️ 用 `runCatching` 兜住：读不到时返回**空列表**，由调用方决定怎么呈现。
     * 调用方**必须**把空列表当"读失败"如实报出来，不能渲染成"没有相册"（见设置页的处理）。
     */
    fun buckets(): List<Pair<String, Int>> {
        val counts = HashMap<String, Int>()
        for (uri in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )) {
            runCatching {
                // `bucket_display_name` 在 Images / Video 两张表里同名，用字面量省掉分表常量
                ctx.contentResolver.query(uri, arrayOf("bucket_display_name"), null, null, null)
                    ?.use { c ->
                        val i = c.getColumnIndex("bucket_display_name")
                        if (i < 0) return@use
                        while (c.moveToNext()) {
                            val b = c.getString(i)?.trim().orEmpty()
                            if (b.isNotEmpty()) counts[b] = (counts[b] ?: 0) + 1
                        }
                    }
            }
        }
        // 条目多的排前面 —— 用户要找的（相机、微信、截图）通常都是大相册
        return counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /** 只取 `_id > sinceId` 的图片 + 视频（增量） */
    fun all(sinceId: Long): List<MediaItem> =
        images(sinceId) + videos(sinceId)

    fun images(): List<MediaItem> {
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.IS_FAVORITE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )
        return query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, isVideo = false)
    }

    fun images(sinceId: Long): List<MediaItem> = if (sinceId <= 0) images() else {
        val proj = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.IS_FAVORITE, MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )
        query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, isVideo = false,
            selection = "${MediaStore.Images.Media._ID} > ?",
            args = arrayOf(sinceId.toString())
        )
    }

    fun videos(sinceId: Long): List<MediaItem> = if (sinceId <= 0) videos() else {
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.IS_FAVORITE, MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.COLOR_TRANSFER, MediaStore.Video.Media.COLOR_STANDARD,
            MediaStore.Video.Media.COLOR_RANGE
        )
        query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, isVideo = true,
            selection = "${MediaStore.Video.Media._ID} > ?",
            args = arrayOf(sinceId.toString())
        )
    }

    fun videos(): List<MediaItem> {
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.IS_FAVORITE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.COLOR_TRANSFER,
            MediaStore.Video.Media.COLOR_STANDARD,
            MediaStore.Video.Media.COLOR_RANGE
        )
        return query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, isVideo = true)
    }

    private fun query(
        uri: android.net.Uri,
        proj: Array<String>,
        isVideo: Boolean,
        selection: String? = null,
        args: Array<String>? = null
    ): List<MediaItem> {
        val out = ArrayList<MediaItem>(8192)
        ctx.contentResolver.query(uri, proj, selection, args, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow("_id")
            val iData = c.getColumnIndexOrThrow("_data")
            val iName = c.getColumnIndexOrThrow("_display_name")
            val iMime = c.getColumnIndexOrThrow("mime_type")
            val iSize = c.getColumnIndexOrThrow("_size")
            val iTaken = c.getColumnIndexOrThrow("datetaken")
            val iAdded = c.getColumnIndexOrThrow("date_added")
            val iBucket = c.getColumnIndex("bucket_display_name")
            val iFav = c.getColumnIndex("is_favorite")
            val iW = c.getColumnIndex("width")
            val iH = c.getColumnIndex("height")
            val iDur = if (isVideo) c.getColumnIndex("duration") else -1
            val iCt = if (isVideo) c.getColumnIndex("color_transfer") else -1
            val iCs = if (isVideo) c.getColumnIndex("color_standard") else -1
            val iCr = if (isVideo) c.getColumnIndex("color_range") else -1

            while (c.moveToNext()) {
                val raw = c.getString(iData) ?: continue
                if (raw.isBlank()) continue
                // ⚠️ 归一化必须在这里也做一遍（不能只做 queryPaths）。
                //    漏掉的话，同一批文件会出现两套路径：删除检测那边看到的是大写（不再误判），
                //    而同步这边拿到的还是小写 → 上传到 NAS 的 `dcim/`，两个目录再次分裂。
                //    实测漏过一次：照片被传成 `dcim/Camera/xxx`，与 `DCIM/Camera/xxx` 并存。
                val path = canonical(raw)
                val taken =
                    if (iTaken < 0 || c.isNull(iTaken)) c.getLong(iAdded) * 1000L else c.getLong(iTaken)
                out.add(
                    MediaItem(
                        id = c.getLong(iId),
                        path = path,
                        name = c.getString(iName) ?: File(path).name,
                        mime = c.getString(iMime) ?: "",
                        size = c.getLong(iSize),
                        takenAt = taken,
                        bucket = if (iBucket >= 0) (c.getString(iBucket) ?: "") else "",
                        isFavorite = iFav >= 0 && c.getInt(iFav) == 1,
                        width = if (iW >= 0 && !c.isNull(iW)) c.getInt(iW) else 0,
                        height = if (iH >= 0 && !c.isNull(iH)) c.getInt(iH) else 0,
                        isVideo = isVideo,
                        durationMs = if (iDur >= 0 && !c.isNull(iDur)) c.getLong(iDur) else 0,
                        colorTransfer = if (iCt >= 0 && !c.isNull(iCt)) c.getInt(iCt) else 0,
                        colorStandard = if (iCs >= 0 && !c.isNull(iCs)) c.getInt(iCs) else 0,
                        colorRange = if (iCr >= 0 && !c.isNull(iCr)) c.getInt(iCr) else 0
                    )
                )
            }
        }
        return out
    }
}
