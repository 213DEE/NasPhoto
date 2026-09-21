package cn.dsr213.nasphoto.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

const val STATE_OFFLOADED = "OFFLOADED"

/** 只备份到 NAS，本地原样保留（高风险/未知格式） */
const val STATE_BACKED_UP = "BACKED_UP"

/**
 * 已恢复：用户把 NAS 上的母本**要回了本地**。
 *
 * ⚠️ 与 [STATE_BACKED_UP] 有本质区别，**绝不能合并成一个**：
 * - `BACKED_UP` = "备份好了，只是还没轮到降级" → **允许被重新评估**（策略变了就该降级）
 * - `RESTORED`  = "用户明确要它留在本地"      → **默认不许再被降级**
 *
 * 混为一谈的后果（2026-09-16 修）：用户点一次「恢复原图」，下一轮全量同步
 * 又把它压回去 —— "恢复"这个动作等于没做，而且每轮还白传一份原图。
 *
 * 2026-09-17 起这条从"绝对不降级"放宽为**可配置的宽限期**：
 * 默认仍然永不降级（保住用户按下按钮的语义）；用户若在设置里显式打开
 * 「恢复后允许再次降级」，则按 [PhotoRecord.restoredAt] + 宽限天数判定，
 * 到点后才重新纳入候选。判定入口见 `OffloadEngine.pickFrom`。
 */
const val STATE_RESTORED = "RESTORED"

@Entity(tableName = "photos")
data class PhotoRecord(
    /** 本地绝对路径，唯一主键 */
    @PrimaryKey val path: String,
    /** NAS 上的绝对路径 */
    val remotePath: String,
    /** 原图的 SHA-256，用于校验与去重 */
    val sha256: String,
    /** 原图字节数 */
    val originalSize: Long,
    /** 缩略化之后的字节数 */
    val localSize: Long,
    /** 拍摄时间（毫秒），用于保持相册排序 */
    val takenAt: Long,
    /** 原文件的 lastModified，恢复时用来还原时间戳 */
    val origLastModified: Long,
    val state: String,
    val offloadedAt: Long,
    /**
     * 用户点「恢复原图」的**时刻**（毫秒）。
     *
     * 只对 `RESTORED` 记录有意义，是「恢复后宽限期」的计时起点。
     * 其它状态一律 0（不清理，反正不读）。
     *
     * ⚠️ 之所以要新开一列而不是复用 [offloadedAt]：那个记的是**上次降级时间**，
     * 恢复发生在降级之后，两者必然不同。混用会导致「恢复完立刻又能降级」。
     *
     * ⚠️ 老记录（本次升级前恢复的）由 `MIGRATION_1_2` 统一补成**迁移时刻** ——
     * 语义是"从升级那一刻起重新计时"，避免用户一升级就被立刻压回去。
     */
    val restoredAt: Long = 0,

    /**
     * MediaStore 的 `_id` —— **这条照片的身份**，与路径无关。
     *
     * ## 为什么非要加它（2026-09-17，修 #86「假母本」）
     * 主键是 [path]，而 path 是个**会变**的东西：
     * - 用户改名 / 移动相册 → `_data` 变了
     * - 同一个目录被不同 App 写成不同大小写 → `Pictures/` vs `pictures/`（实测，见 `MediaRepo.canonical`）
     *
     * 而 `_id` 在上面这些情况下**都不变**（MediaStore 里是 update，不是 delete+insert）。
     * 所以它是"这是不是同一条照片"的唯一精确判据。
     *
     * 没有它的时候，改名/大小写变化的实际后果：
     * ① 新路径在 `offloaded`/`backedUp` 集合里找不到 ⇒ 当作**从没备份过**，重新传一份 ⇒
     *    NAS 上多出一份同内容副本（假母本）；
     * ② 旧路径的文件"消失"了 ⇒ 被判成手机侧删除，可能把真母本搬进回收站。
     *
     * ## 语义
     * - `0` = 未知（迁移前的老记录，以及 NAS 侧回灌等拿不到 MediaItem 的场景）
     * - `>0` = 该照片在 MediaStore 里的 id；**认领时只在 >0 之间比**
     *
     * 认领入口见 `engine/PathReconciler`。
     */
    val mediaId: Long = 0
)

/**
 * `RESTORED` 记录的「路径 + 恢复时刻」。给 `OffloadEngine.pickFrom` 判宽限期用。
 *
 * 做成 POJO 而不是返回 `List<PhotoRecord>`：筛选每轮都跑，
 * 拉全表没必要（原始尺寸、sha 这些字段一个都用不上）。
 */
data class RestoredRow(val path: String, val restoredAt: Long)

@Dao
interface PhotoDao {

    @Query("SELECT * FROM photos WHERE path = :path LIMIT 1")
    fun byPath(path: String): PhotoRecord?

    /** 按远端路径反查 —— 用于检测"同一归档路径已被另一份不同内容占用" */
    @Query("SELECT * FROM photos WHERE remotePath = :remote LIMIT 1")
    fun byRemotePath(remote: String): PhotoRecord?

    /**
     * 按 MediaStore 身份反查 —— 用于识别「同一条照片换了路径」（改名 / 移动 / 大小写变化）。
     *
     * ⚠️ 必须带 `mediaId != 0`：老记录以及回灌记录的 mediaId 都是 0，
     * 不带这个条件的话它们会**互相匹配**（0 = 0），认领时把一堆无关记录搅在一起。
     */
    @Query("SELECT * FROM photos WHERE mediaId = :id AND mediaId != 0 LIMIT 1")
    fun byMediaId(id: Long): PhotoRecord?

    /** 已降级：本地已是预览图，永不再处理 */
    @Query("SELECT path FROM photos WHERE state = '$STATE_OFFLOADED'")
    fun offloadedPaths(): List<String>

    /**
     * 仅备份：本地仍是原图。**这类记录允许被重新评估** ——
     * 若策略变更后判定应降级，需要把它们重新放回候选。
     */
    @Query("SELECT path FROM photos WHERE state = '$STATE_BACKED_UP'")
    fun backedUpPaths(): List<String>

    /**
     * 用户已恢复原图：本地常驻，**默认永不作为降级候选**。
     *
     * 消费方是 `OffloadEngine.pickFrom` 里的"直接跳过" —— 与 [offloadedPaths] 同一待遇，
     * 但理由相反：那个是"本地已经是小图了，不用再动"，这个是"本地就是用户要的原图，不许动"。
     *
     * 带出 `restoredAt` 是因为「宽限期」要按时间判：只有用户显式打开了
     * 「恢复后允许再次降级」，且已过 N 天，这条才会重新进候选。
     */
    @Query("SELECT path, restoredAt FROM photos WHERE state = '$STATE_RESTORED'")
    fun restoredRows(): List<RestoredRow>

    /**
     * 把**所有** `RESTORED` 记录的恢复时刻改写成 [at]，返回受影响行数。
     *
     * 只给诊断探针用（`--es ageRestored <N>` 伪造"已经过了 N 天"），
     * 用来验证宽限期到期这条路径真的会放回候选 —— 否则要真等 3 天才能测。
     * 业务代码不该调它。
     */
    @Query("UPDATE photos SET restoredAt = :at WHERE state = '$STATE_RESTORED'")
    fun markAllRestoredAt(at: Long): Int

    @Query("SELECT * FROM photos WHERE state = '$STATE_OFFLOADED' ORDER BY offloadedAt DESC")
    fun offloaded(): List<PhotoRecord>

    @Query("SELECT COUNT(*) FROM photos WHERE state = '$STATE_OFFLOADED'")
    fun countOffloaded(): Int

    @Query("SELECT COUNT(*) FROM photos WHERE state = '$STATE_BACKED_UP'")
    fun countBackedUp(): Int

    @Query("SELECT COUNT(*) FROM photos WHERE state = '$STATE_BACKED_UP' AND path LIKE '%.mp4' OR state = '$STATE_BACKED_UP' AND path LIKE '%.mov'")
    fun countBackedUpVideo(): Int

    @Query("SELECT COALESCE(SUM(originalSize - localSize), 0) FROM photos WHERE state = '$STATE_OFFLOADED'")
    fun savedBytes(): Long

    @Query("SELECT * FROM photos")
    fun allRecords(): List<PhotoRecord>

    @Query("DELETE FROM photos WHERE path = :path")
    fun deleteByPath(path: String): Int

    // ---------------------------------------------------------------- 归档根迁移

    @Query("SELECT COUNT(*) FROM photos WHERE remotePath LIKE :prefix || '%'")
    fun countByRemotePrefix(prefix: String): Int

    /**
     * 把远端路径前缀 [oldPrefix] 批量换成 [newPrefix]，返回受影响行数。
     *
     * 归档根换位置时**必须**跑这个：`remotePath` 存的是相对 WebDAV 基址的完整相对路径
     * （形如 `我的文档/NasPhoto归档/DCIM/Camera/a.jpg`），前缀不改，「恢复」就会按旧路径
     * 去 NAS 上找 → 全部 404。
     */
    @Query(
        "UPDATE photos SET remotePath = :newPrefix || SUBSTR(remotePath, LENGTH(:oldPrefix) + 1) " +
            "WHERE remotePath LIKE :oldPrefix || '%'"
    )
    fun rewriteRemotePrefix(oldPrefix: String, newPrefix: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(record: PhotoRecord)

    @Delete
    fun delete(record: PhotoRecord)
}

@Database(entities = [PhotoRecord::class], version = 3, exportSchema = false)
abstract class AppDb : RoomDatabase() {

    abstract fun photos(): PhotoDao

    companion object {
        @Volatile
        private var INSTANCE: AppDb? = null

        /**
         * v1 → v2：新增 `restoredAt` 列（「恢复后宽限期」的计时起点）。
         *
         * ⚠️ 老 `RESTORED` 记录同步补成**迁移发生的时刻**，而不是 0。
         * 若留 0，配上用户新开的「3 天后降级」，`now - 0` 是个天文数字 ⇒
         * 一升级就把用户好不容易恢复的原图全部压回去。补成 now 的语义是
         * "从现在起重新计时"，与用户的心理预期一致。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE photos ADD COLUMN restoredAt INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "UPDATE photos SET restoredAt = ${System.currentTimeMillis()} " +
                        "WHERE state = '$STATE_RESTORED'"
                )
            }
        }

        /**
         * v2 → v3：新增 `mediaId` 列（照片在 MediaStore 里的身份，用于识别"换了路径"）。
         *
         * ⚠️ 存量记录**不回填**，留 0 —— 回填必须有媒体库快照才能做，
         * 那是运行时的活（`PathReconciler.reconcile` 会顺手补上）。
         * 在这里瞎猜一个 id 比留 0 危险得多：认领是按 id 相等的，猜错等于把
         * A 照片的记录挂到 B 照片上。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN mediaId INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(ctx: Context): AppDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                ctx.applicationContext, AppDb::class.java, "nasphoto.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                // ⚠️ 刻意用 OnDowngrade 而不是裸的 fallbackToDestructiveMigration：
                // 后者在"升级方向缺 migration"时也会**静默清库**（库里 150+ 条记录、
                // 是"哪些已降级"的唯一账本，清掉意味着全部重新上传+重新降级）。
                // 用 OnDowngrade 则只在装旧版 APK 时清库；升级方向缺 migration 会直接抛异常，
                // 让我在第一次构建就发现，而不是用户某天发现照片被重压了一遍。
                .fallbackToDestructiveMigrationOnDowngrade()
                .build().also { INSTANCE = it }
        }
    }
}
