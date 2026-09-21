package cn.dsr213.nasphoto.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

/**
 * 「能不能改手机上的文件」—— 后台链路唯一该问的地方。
 *
 * ## 为什么需要它（#103）
 * `MANAGE_EXTERNAL_STORAGE` 是**特殊权限，被撤销时系统不会通知 App**：
 * 没有回调、没有广播，除了主动去查没有任何信号。而 `ensurePermissions()` 过去只在
 * `MainActivity` 里调用，`service/` 与 `engine/` 两个目录下**一处权限检查都没有** ——
 * 于是用户在系统设置里关掉「所有文件访问」之后，降级与删除会**静默失败**
 * （被 `runCatching` 兜住，只在 logcat 留一行 `NasPhotoDel` 警告），界面毫无提示。
 * 用户看到的现象是「手机照片删了、NAS 母本不动」，且完全无法归因。
 *
 * ## 判据
 * - Android 11+（API 30）：`Environment.isExternalStorageManager()`
 * - 更早：`READ_EXTERNAL_STORAGE` 运行时权限（那个年代写公共目录靠它）
 *
 * ## ⚠️ 调用方注意：这是「能不能写」，不是「能不能用」
 * 读取相册走的是 `READ_MEDIA_IMAGES/VIDEO`，与本权限**互相独立**。
 * 缺了本权限**备份照做**（读得到就传得上去），只是不能降级、不能删本地。
 * 所以**不要**用它一刀切地停掉整条链路 —— 应当精确地跳过"会改动用户文件"的那几步，
 * 否则一个可以正常备份的状态会被误判成"什么都干不了"。
 */
object StoragePermission {

    fun canWritePublicStorage(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
}
