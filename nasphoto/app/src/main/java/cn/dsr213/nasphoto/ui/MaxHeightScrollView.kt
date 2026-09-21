package cn.dsr213.nasphoto.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * 带**最大高度**的 ScrollView。
 *
 * ## 为什么需要它
 * `AlertDialog` 用 `setView()` 塞进去的普通 View **不会自动获得滚动能力**
 * （只有 `setMessage()` 那条路才会自动包一层 ScrollView）。选项一多，
 * 内容直接超出屏幕被裁掉，底部选项既看不见也滚不到 —— 折叠屏的阔屏形态
 * 竖直空间更紧张，这个问题更明显。
 *
 * 仅仅套一层 ScrollView 还不够：Dialog 窗口高度是 `wrap_content`，
 * 内容多高窗口就多高、照样顶出屏幕。所以这里再压一道**最大高度**，
 * 保证「标题 + 可滚动内容 + 底部按钮」整体始终留在屏内。
 *
 * 用法：`maxHeightPx = (屏幕可用高度 * 0.6).toInt()`
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    /** 上限（像素）。默认 [Int.MAX_VALUE] 表示不限制。 */
    var maxHeightPx: Int = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val limit = when {
            maxHeightPx == Int.MAX_VALUE -> size
            mode == MeasureSpec.UNSPECIFIED -> maxHeightPx
            else -> minOf(size, maxHeightPx)
        }
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST)
        )
    }
}
