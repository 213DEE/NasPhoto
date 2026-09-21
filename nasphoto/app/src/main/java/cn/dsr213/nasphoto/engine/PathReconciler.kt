package cn.dsr213.nasphoto.engine

import android.content.Context
import android.util.Log
import cn.dsr213.nasphoto.data.AppDb
import cn.dsr213.nasphoto.data.SyncLog
import cn.dsr213.nasphoto.media.MediaRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **路径对账** —— 认出「同一条照片换了路径」，让数据库记录跟着走。
 *
 * ## 为什么需要它（Task #86：手机改名/移动照片 → NAS 产生假母本）
 *
 * 数据库的主键是本地**路径**，而路径是**会变**的东西：
 * - 用户在相册里改名 / 移动到另一个目录
 * - 同一个目录被不同 App 写成不同大小写（小米上实测：微信存成 `pictures/`，
 *   别的 App 存成 `Pictures/` —— 同一个目录，见 `MediaRepo.canonical`）
 *
 * 而我们所有"要不要处理这条"的判断**全是按 path 查集合**的
 * （`pickFrom` 里的 `offloaded` / `backedUp` / `restored`）。于是路径一变，两条线同时出问题：
 *
 * | | 现象 | 后果 |
 * |---|---|---|
 * | 新路径 | 不在任何集合里 ⇒ 当成"从没备份过" | **重新传一份** ⇒ NAS 上多出一份同内容副本（假母本） |
 * | 旧路径 | 文件"消失"了 | 可能被判成"手机侧删除" ⇒ **真母本被搬进回收站** |
 *
 * ## 判据：MediaStore 的 `_id`
 *
 * 上面这些情况下 `_id` **都不变**（MediaStore 内部是 UPDATE，不是 delete + insert）。
 * 所以拿它当身份，比拿路径当身份可靠得多。
 *
 * ## 只做两件事，都不删文件
 * 1. **补身份**：`mediaId = 0` 的老记录，按 path 与媒体库对上号后回填 id
 * 2. **跟随路径**：`mediaId` 相同但媒体库里的 path 变了 ⇒ 把记录的 path 改过去
 *
 * ⚠️ **不动 NAS 上的母本**。记录的 `remotePath` 原样保留，好处是：
 * - 恢复原图、删除同步仍然按 `remotePath` 精确命中，功能不受影响
 * - 不需要为"改个名"做一次网络 MOVE（那会在断网时留下指向不存在文件的记录）
 *
 * 代价是 NAS 的目录结构可能与手机不再一致 —— 这是**刻意的取舍**：
 * 归档树的职责是"存住母本"，不是镜像手机的目录。
 *
 * ## 位置
 * 必须在**候选筛选之前**跑（见 `RealtimeRunner.runOnce` 与 `OffloadEngine.run`），
 * 否则那一轮仍会按旧路径判定，把这一轮该省的上传又做一遍。
 */
object PathReconciler {

    private const val TAG = "NasPhotoRecon"

    data class Result(
        /** 新补上身份的条数 */
        val filled: Int = 0,
        /** 认领了路径变化的条数 */
        val moved: Int = 0
    ) {
        val any: Boolean get() = filled > 0 || moved > 0
    }

    /**
     * 跑一轮对账。**幂等**，没有变化时开销只有一次媒体库扫描 + 一次全表读。
     *
     * 媒体库读不到（抛异常）时直接返回 —— 什么都不知道就别动记录。
     */
    suspend fun reconcile(ctx: Context): Result = withContext(Dispatchers.IO) {
        val dao = AppDb.get(ctx).photos()

        val media = try {
            MediaRepo(ctx).all()
        } catch (t: Throwable) {
            Log.w(TAG, "读媒体库失败，本轮对账跳过：${t.message}")
            return@withContext Result()
        }
        if (media.isEmpty()) return@withContext Result()

        // path → 媒体（补身份用）、id → 媒体（认领路径用）
        val byPath = HashMap<String, cn.dsr213.nasphoto.media.MediaItem>(media.size * 2)
        val byId = HashMap<Long, cn.dsr213.nasphoto.media.MediaItem>(media.size * 2)
        for (m in media) {
            byPath[m.path] = m
            byId[m.id] = m
        }

        // 取全表快照后再遍历：边遍历边写库没关系，records 是内存里的 List
        val records = dao.allRecords()
        var filled = 0
        var moved = 0
        val movedLog = ArrayList<String>()

        for (r in records) {
            // ---- ① 老记录补身份 ----
            if (r.mediaId <= 0L) {
                val hit = byPath[r.path] ?: continue
                dao.upsert(r.copy(mediaId = hit.id))
                filled++
                continue
            }

            // ---- ② 身份还在，但路径变了 ----
            val hit = byId[r.mediaId] ?: continue
            if (hit.path == r.path) continue

            // 新路径上已经有别的记录 ⇒ 不覆盖。
            // 典型来源：迁移前就已经存在的重复记录（同一条照片的大小写两种写法各一条），
            // 那种情况要人工核对"哪条才是对的"，不是这里能决定的（见记忆里的存量清理）。
            if (dao.byPath(hit.path) != null) {
                Log.i(TAG, "新路径已有记录，跳过认领：${hit.path}")
                continue
            }

            dao.deleteByPath(r.path)
            dao.upsert(r.copy(path = hit.path))
            moved++
            if (movedLog.size < 5) movedLog.add(hit.name)
        }

        if (filled > 0) Log.i(TAG, "补身份 $filled 条")
        if (moved > 0) {
            Log.i(TAG, "认领路径变化 $moved 条")
            SyncLog.add(
                "·改名  发现 $moved 个文件换了路径（改名/移动/大小写），记录已跟随：" +
                    movedLog.joinToString("、") + (if (moved > movedLog.size) " 等" else "")
            )
        }
        Result(filled, moved)
    }

    /**
     * 给全部记录补一次 `mediaId`（**只补身份，不认领路径变化**）。
     *
     * 单独抽出来是给设置里的迁移入口用：首次升级到带 `mediaId` 的版本后跑一次，
     * 让存量记录也享受"改名不再重传"的保护 —— 否则老记录永远是 `mediaId = 0`，
     * 一改名就退回到老行为，而用户完全看不出差别。
     *
     * @return 补上的条数
     */
    suspend fun backfill(ctx: Context): Int = withContext(Dispatchers.IO) {
        val dao = AppDb.get(ctx).photos()
        val media = try {
            MediaRepo(ctx).all()
        } catch (t: Throwable) {
            return@withContext 0
        }
        val byPath = media.associateBy { it.path }
        var n = 0
        for (r in dao.allRecords()) {
            if (r.mediaId > 0L) continue
            val hit = byPath[r.path] ?: continue
            dao.upsert(r.copy(mediaId = hit.id))
            n++
        }
        Log.i(TAG, "backfill: $n 条")
        n
    }
}
