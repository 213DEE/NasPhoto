package cn.dsr213.nasphoto.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import cn.dsr213.nasphoto.R
import cn.dsr213.nasphoto.data.PhotoRecord
import cn.dsr213.nasphoto.engine.fmt
import java.io.File

class OffloadedAdapter(
    private val items: MutableList<PhotoRecord> = ArrayList(),
    private val onRestore: (PhotoRecord) -> Unit
) : RecyclerView.Adapter<OffloadedAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvName)
        val info: TextView = v.findViewById(R.id.tvInfo)
        val btn: Button = v.findViewById(R.id.btnRowRestore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_offloaded, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        holder.name.text = File(r.path).name
        holder.info.text = "原 ${fmt(r.originalSize)} → 本地 ${fmt(r.localSize)}"
        holder.btn.setOnClickListener { onRestore(r) }
    }

    fun submit(list: List<PhotoRecord>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }
}
