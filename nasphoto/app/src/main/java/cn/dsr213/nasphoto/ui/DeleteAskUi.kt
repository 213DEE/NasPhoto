package cn.dsr213.nasphoto.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Html
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import cn.dsr213.nasphoto.engine.fmt
import cn.dsr213.nasphoto.service.DeleteActionReceiver

/**
 * 「这边删了，那边要不要一起删？」的询问 UI —— **两个方向共用**。
 *
 * ## 两个方向
 * | 方向 | 触发 | 确认按钮 | 保留按钮 |
 * |---|---|---|---|
 * | 手机删 → NAS（[ask]） | 相册里删了照片 | 一起删（移进 NAS 回收站） | 保留（NAS 不动） |
 * | NAS 删 → 手机（[askRemote]） | NAS 归档里没了 | 手机上也删掉 | 保留（手机不动） |
 *
 * 文案与按钮必须能一眼分清方向，否则用户点错就是删错地方 —— 这两侧的后果完全不对称：
 * 那边动的是"还能还原的回收站"，这边动的是**手机上的文件**。
 *
 * ## 为什么是"先删再问"，而不是"删之前拦下来问"
 * 拦不下来。小米相册是 Flutter（0 dex），LSPosed 钩不到它本体；钩 `MediaProvider`
 * 又是在**系统进程里弹 UI 并阻塞删除**等回答 —— 大概率 ANR，一崩还影响整个系统。
 * 而我们本来就能秒级感知 MediaStore 变化，所以做成"顺手问一句"既可靠又无侵入。
 *
 * ## 两条路
 * - 有悬浮窗权限 → 屏幕上方浮一张卡片，两个按钮（体验最好）
 * - 没有 → 高优先级通知，同样两个按钮（不需要任何特殊权限）
 *
 * 无论哪条路，**不问就不动另一边**；点「保留」也一个字节都不动。
 */
object DeleteAskUi {

    private const val TAG = "NasPhotoDel"
    const val CHANNEL_ASK = "nasphoto.ask"
    private const val NOTIF_ID_ASK = 0x4E51
    private const val NOTIF_ID_ASK_REMOTE = 0x4E53
    private const val NOTIF_ID_BULK = 0x4E52
    private const val NOTIF_ID_BULK_REMOTE = 0x4E54

    /** 悬浮卡片最多停留多久，超时自动转成通知（免得一直杵在屏幕上） */
    private const val OVERLAY_TIMEOUT_MS = 45_000L

    /**
     * 悬浮卡片宽度上限（dp）。
     *
     * 折叠屏内屏展开后是 608dp 宽，一张提示卡铺满全屏读起来很累。
     * 实际宽度 = `min(窗口宽 - 32dp, 520dp)`：
     * 外屏 425dp → 393dp，内屏 608dp → 520dp，两头都不溢出。
     */
    private const val CARD_MAX_WIDTH_DP = 520

    /**
     * 一次询问的全部可变内容 —— 两个方向的差异都收敛在这里。
     *
     * [body] 里可以放 `<b>` 之类的极简 HTML：悬浮卡片用 [Html.fromHtml] 渲染，
     * 通知那边会自动剥掉标签（且把 `<br>` 还原成换行）。
     *
     * ⚠️ **要换行就写 `<br>`，别写 `\n`** —— `Html.fromHtml` 把 `\n` 当普通空白
     * 折叠掉，两段话会连成一整行超长文字，直接顶出卡片外（踩过）。
     *
     * ⚠️ **别写 markdown 的星号** —— TextView 不认，用户会看到字面的 `**`（踩过）。
     */
    private data class Ask(
        val title: String,
        val body: String,
        val confirmText: String,
        val keepText: String,
        val confirmAction: String,
        val keepAction: String,
        val notifId: Int,
        val count: Int,
        val bytes: Long,
        val sampleName: String
    )

    private var overlayView: View? = null
    private val ui = Handler(Looper.getMainLooper())

    /** 最近一次询问 —— 卡片超时收起时要用它转成通知 */
    private var lastAsk: Ask? = null

    /**
     * 卡片超时。
     *
     * ⚠️ **必须转成通知**，不能只是收起来。实测踩过：卡片 45 秒后自己消失，
     * 用户既没点、也没别的地方能看到这件事 —— 等于这次询问被静默吞掉，
     * 「待办」永远挂着，用户永远不知道自己被问过。
     */
    private val dismiss = Runnable {
        val info = lastAsk
        hideOverlay()
        val ctx = appCtx
        if (info != null && ctx != null) notifyAsk(ctx, info)
    }

    /** 超时转通知时要用（Service/Worker 传进来的多是 application context） */
    private var appCtx: Context? = null

    // ------------------------------------------------------------------ 入口

    /** 方向一：手机相册里删了照片 → 问要不要把 NAS 备份也移进回收站 */
    fun ask(ctx: Context, count: Int, bytes: Long, sampleName: String) {
        show(
            ctx,
            Ask(
                title = "NAS 备份要一起删吗？",
                body = "手机相册里删了 $count 项" +
                    (if (bytes > 0) "（${fmt(bytes)}）" else "") +
                    "，例：$sampleName\n" +
                    "选「一起删」会移进 NAS 回收站，之后还能在「照片回收站」里还原。",
                confirmText = "一起删",
                keepText = "保留",
                confirmAction = DeleteActionReceiver.ACTION_TRASH,
                keepAction = DeleteActionReceiver.ACTION_KEEP,
                notifId = NOTIF_ID_ASK,
                count = count, bytes = bytes, sampleName = sampleName
            )
        )
    }

    /** 方向二：NAS 上删了照片 → 问要不要把手机里这份也删掉 */
    fun askRemote(ctx: Context, count: Int, bytes: Long, sampleName: String) {
        show(
            ctx,
            Ask(
                title = "手机上也删掉吗？",
                body = "NAS 归档里少了 $count 项" +
                    (if (bytes > 0) "，手机本地约 ${fmt(bytes)}" else "") +
                    "，例：$sampleName\n" +
                    "手机上这些已经是<b>仅存的副本</b>了，选「手机上也删掉」之后无法再找回。",
                confirmText = "手机上也删掉",
                keepText = "保留",
                confirmAction = DeleteActionReceiver.ACTION_DEL_LOCAL,
                keepAction = DeleteActionReceiver.ACTION_KEEP_LOCAL,
                notifId = NOTIF_ID_ASK_REMOTE,
                count = count, bytes = bytes, sampleName = sampleName
            )
        )
    }

    /**
     * 熔断提示（方向一）：只告知，不给"一起删"按钮 —— 这种情况本来就该人工去查
     */
    fun warnBulk(ctx: Context, count: Int, total: Int) = warnBulkImpl(
        ctx, NOTIF_ID_BULK, "已暂停删除同步", count, total,
        "常见原因是 NAS 上的归档目录被改名/移动过。请先打开 App 看看。"
    )

    /** 熔断提示（方向二） */
    fun warnBulkRemote(ctx: Context, count: Int, total: Int) = warnBulkImpl(
        ctx, NOTIF_ID_BULK_REMOTE, "已暂停「NAS→手机」删除同步", count, total,
        "NAS 归档树可能被改名/移走，也可能是网络读取异常。" +
            "没有动手机上的任何文件，请先打开 App 确认。"
    )

    private fun warnBulkImpl(
        ctx: Context, notifId: Int, title: String, count: Int, total: Int, advice: String
    ) {
        val app = ctx.applicationContext
        ensureChannel(app)
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(app, CHANNEL_ASK)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText("检测到 $count/$total 条同时消失，疑似异常，已停下等你确认")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "一次性消失 $count 条（共 $total 条），超过安全阈值，已暂停自动处理。\n\n$advice"
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(notifId, n) }
    }

    private fun canOverlay(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

    // ------------------------------------------------------------------ 分发

    private fun show(ctx: Context, ask: Ask) {
        val app = ctx.applicationContext
        appCtx = app
        lastAsk = ask
        if (canOverlay(app)) showOverlay(app, ask) else notifyAsk(app, ask)
    }

    // ------------------------------------------------------------------ 悬浮卡片

    private fun showOverlay(ctx: Context, ask: Ask) {
        ui.post {
            try {
                hideOverlay()
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                // 只用于日志对照：**不再参与任何宽度计算**（它曾经算错过）
                val dm = ctx.resources.displayMetrics

                val pad = dp(ctx, 16)
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(pad, pad, pad, pad)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(ctx, 18).toFloat()
                        setColor(Color.parseColor("#F2202126"))
                        setStroke(dp(ctx, 1), Color.parseColor("#33FFFFFF"))
                    }
                }

                card.addView(TextView(ctx).apply {
                    text = ask.title
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setPadding(0, 0, 0, dp(ctx, 6))
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
                card.addView(TextView(ctx).apply {
                    // ⚠️ `Html.fromHtml` 会把文案里的 `\n` 当**普通空白折叠掉** ——
                    //    换行必须写成 `<br>`。踩过：两段话连成一整行超长文字，
                    //    长度直接顶出卡片外，右半句根本读不到。
                    text = Html.fromHtml(
                        ask.body.replace("\n", "<br>"), Html.FROM_HTML_MODE_LEGACY
                    )
                    setTextColor(Color.parseColor("#B9BAC4"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    maxLines = 5
                    ellipsize = TextUtils.TruncateAt.END
                })

                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(ctx, 14), 0, 0)
                }
                row.addView(overlayButton(ctx, ask.confirmText) {
                    hideOverlay()
                    DeleteActionReceiver.send(ctx, ask.confirmAction)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(overlayButton(ctx, ask.keepText) {
                    hideOverlay()
                    DeleteActionReceiver.send(ctx, ask.keepAction)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                card.addView(row)

                // ⚠️⚠️ 宽度**交给窗口系统**，我们一个数都不猜。
                //
                // 踩过的大坑：原来用 `ctx.resources.displayMetrics.widthPixels` 亲自算宽度，
                // 还顺手写了 `lp.horizontalMargin = 16f` —— 后者是"占容器宽度的**比例**"
                // （0~1 的小数），根本不是 dp。那次算出来的卡片是屏幕的两倍半宽：
                // 左边缘贴死屏幕、右半张连「保留」按钮整颗跑到屏幕外 ——
                // 屏幕上只剩「手机上也删掉」，问句看起来像"只能删"。**危险且不报任何错。**
                //
                // ⚠️ 事后用单变量实验复现过：同样的代码今天算出来是正常的 1080px ——
                //    说明那次是 `resources.displayMetrics` 给了一个离谱的屏幕宽度。
                //    也就是说**这个数不能信**，所以现在一个数都不算。
                //
                // 现在：窗口用 MATCH_PARENT（由 WMS 按容器算，它不会错），
                // 16dp 留白做成外层容器的 padding；宽度上限靠**实测**宽度事后去夹。
                val root = FrameLayout(ctx).apply { setPadding(pad, 0, pad, 0) }
                root.addView(
                    card,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER_HORIZONTAL
                    )
                )

                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // 不抢输入焦点（否则会顶掉输入法），但**触摸照常**，按钮能点
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP
                    y = dp(ctx, 72)
                    // ⚠️ 千万别再碰 horizontalMargin（见上）
                }

                wm.addView(root, lp)
                overlayView = root

                // 宽度上限：**等实测出来再夹**（内屏 608dp 会铺太宽，收到 520dp 居中；
                // 外屏 425dp 根本触发不到）。用实测宽度而不是预算宽度，是这里的关键 ——
                // 预算法在折叠屏上已经被证明会算错。
                card.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                    private var logged = false
                    override fun onLayoutChange(
                        v: View, l: Int, t: Int, r: Int, b: Int,
                        ol: Int, ot: Int, orr: Int, ob: Int
                    ) {
                        if (!logged) {
                            logged = true
                            Log.i(
                                TAG,
                                "卡片宽度：窗口实测 ${root.width}px（留白后 ${r - l}px）｜" +
                                    "应用资源 ${dm.widthPixels}px 密度 ${dm.density}" +
                                    " 字缩放 ${(dm.scaledDensity / dm.density)}｜" +
                                    widthSources(wm, dm)
                            )
                        }
                        val cap = dp(ctx, CARD_MAX_WIDTH_DP)
                        val p = card.layoutParams as FrameLayout.LayoutParams
                        if (r - l > cap && p.width != cap) {
                            p.width = cap
                            card.layoutParams = p
                        }
                    }
                })

                ui.postDelayed(dismiss, OVERLAY_TIMEOUT_MS)
                Log.i(TAG, "已弹出悬浮确认卡片（${ask.count} 项）")
            } catch (t: Throwable) {
                // 悬浮窗被 ROM 拦了之类 —— 退回通知，功能不能因此失效
                Log.w(TAG, "悬浮卡片失败，改用通知：${t.message}")
                notifyAsk(ctx, ask)
            }
        }
    }

    private fun hideOverlay() {
        ui.removeCallbacks(dismiss)
        val v = overlayView ?: return
        overlayView = null
        runCatching {
            (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        }
    }

    /**
     * 卡片上的按钮：**必须能塞进半张卡片**。
     *
     * Material 的 `Button` 自带 `minWidth`（58dp）和两端内边距，
     * 「手机上也删掉」这种 6 字按钮在窄卡片上会被顶出边界 ——
     * 所以 minWidth 清零、内边距收窄、强制单行 + 省略号。
     */
    private fun overlayButton(ctx: Context, text: String, onClick: () -> Unit) =
        Button(ctx).apply {
            this.text = text
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(ctx, 4), paddingTop, dp(ctx, 4), paddingBottom)
            setOnClickListener { onClick() }
        }

    /**
     * 三路宽度来源的对照串（**只用于排查**，不参与任何计算）。
     *
     * 卡片撑出屏幕那次就是因为轻信了其中一路。留着这三行，
     * 下次再出问题能一眼看出是哪一路在说瞎话：
     *   · `cur`  —— `currentWindowMetrics`，理论上权威的窗口指标
     *   · `phys` —— 物理面板宽度（折叠屏会随开合变化）
     *   · `res`  —— `resources.displayMetrics`，应用自己那套配置推出来的，最可疑
     */
    private fun widthSources(wm: WindowManager, dm: android.util.DisplayMetrics): String {
        val cur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { wm.currentWindowMetrics.bounds.width() }.getOrNull()
        } else null
        val phys = runCatching {
            val p = android.graphics.Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p.x
        }.getOrNull()
        return "窗口指标 ${cur ?: "-"} / 物理 ${phys ?: "-"} / 资源 ${dm.widthPixels}"
    }

    // ------------------------------------------------------------------ 通知兜底

    private fun notifyAsk(ctx: Context, ask: Ask) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val tap = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, cn.dsr213.nasphoto.ui.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // 通知不认 HTML，剥掉标签再进文案（否则用户会看到字面的 <b>）；
        // `<br>` 要还原成真换行，不然两段话会连成一行。
        val plain = ask.body.replace("<br>", "\n").replace(Regex("<[^>]+>"), "")
        val n = NotificationCompat.Builder(ctx, CHANNEL_ASK)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(ask.title)
            .setContentText(plain.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(plain))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .addAction(0, ask.confirmText, DeleteActionReceiver.pending(ctx, ask.confirmAction))
            .addAction(0, ask.keepText, DeleteActionReceiver.pending(ctx, ask.keepAction))
            .build()

        runCatching { nm.notify(ask.notifId, n) }
            .onFailure { Log.w(TAG, "通知也发不出去（可能没给通知权限）：${it.message}") }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ASK) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ASK,
                "删除确认",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "删除后询问是否同步到另一侧（NAS ↔ 手机）" }
        )
    }

    private fun dp(ctx: Context, v: Int): Int =
        (ctx.resources.displayMetrics.density * v).toInt()

    /** 有没有悬浮窗权限（界面用来显示状态） */
    fun overlayGranted(ctx: Context): Boolean = canOverlay(ctx)

    /** 申请悬浮窗权限的 Intent（界面用；某些 ROM 会改写这个开关） */
    fun overlaySettingsIntent(ctx: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        android.net.Uri.parse("package:${ctx.packageName}")
    )
}
