package cn.dsr213.nasphoto.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import cn.dsr213.nasphoto.R
import cn.dsr213.nasphoto.data.STATE_BACKED_UP
import cn.dsr213.nasphoto.data.STATE_OFFLOADED
import cn.dsr213.nasphoto.data.STATE_RESTORED
import cn.dsr213.nasphoto.engine.TrashStore
import cn.dsr213.nasphoto.engine.fmt

/**
 * 回收站列表。行内「还原」，**长按整行**才是彻底删除 ——
 * 把不可逆的那个动作藏到长按里，是刻意的：误触「还原」只是白跑一趟，
 * 误触「彻底删除」就是母本没了。
 */
class TrashAdapter(
    private val items: MutableList<TrashStore.TrashItem> = ArrayList(),
    private val onRestore: (TrashStore.TrashItem) -> Unit,
    private val onDelete: (TrashStore.TrashItem) -> Unit
) : RecyclerView.Adapter<TrashAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvName)
        val path: TextView = v.findViewById(R.id.tvPath)
        val meta: TextView = v.findViewById(R.id.tvMeta)
        val btn: Button = v.findViewById(R.id.btnRowRestore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_trash, parent, false))

    override fun getItemCount(): Int = items.size

    // ⚠️ 局部变量**千万别叫 `it`**：下面 `setOnClickListener { }` 的隐式参数就叫 `it`
    //    （类型是 View），会把外层这个名字整个遮住 —— 报错长这样：
    //    `Argument type mismatch: actual type is 'android.view.View!',
    //     but 'TrashStore.TrashItem' was expected`。踩过一次。
    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.path.text = item.origRel.substringBeforeLast('/', "")
            .let { d -> if (d.isEmpty()) "← 归档根" else d }
        holder.meta.text = metaOf(item)
        holder.btn.setOnClickListener { onRestore(item) }
        holder.itemView.setOnLongClickListener {
            onDelete(item)
            true
        }
    }

    fun submit(list: List<TrashStore.TrashItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    private fun metaOf(item: TrashStore.TrashItem): String = buildString {
        append("删除于 ").append(item.date)
        if (item.origSize >= 0) append(" · ").append(fmt(item.origSize))
        append(" · ")
        append(
            when (item.localState) {
                STATE_OFFLOADED -> "手机上是降级小图"
                STATE_BACKED_UP -> "手机上是原图"
                STATE_RESTORED -> "手机上已恢复原图"
                null -> "手机上没有这份"
                else -> item.localState
            }
        )
    }
}
