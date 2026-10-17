package com.dkc.fileserverclient.sync

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.dkc.fileserverclient.R

class SyncAdapter(
    private val items: MutableList<LocalImage>
) : RecyclerView.Adapter<SyncAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.syncItemThumb)
        val name: TextView = view.findViewById(R.id.syncItemName)
        val hash: TextView = view.findViewById(R.id.syncItemHash)
        val status: TextView = view.findViewById(R.id.syncItemStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sync_image, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.displayName
        holder.thumb.load(item.uri)
        holder.hash.text = item.hash?.take(16)?.let { "$it…" } ?: "—"

        holder.status.text = when {
            item.errorMessage != null -> "❌ ${item.errorMessage}"
            item.alreadyOnServer -> "✅ 已存在"
            item.quickUploaded -> "⚡ 秒传"
            item.uploadQueued -> "⬆️ 上传中"
            item.hash != null -> "⏳ 待同步"
            else -> "⏳"
        }
        holder.status.setTextColor(
            when {
                item.errorMessage != null -> 0xFFE53935.toInt()
                item.alreadyOnServer -> 0xFF43A047.toInt()
                item.quickUploaded -> 0xFF1E88E5.toInt()
                else -> 0xFF757575.toInt()
            }
        )
    }

    override fun getItemCount(): Int = items.size

    fun update(newList: List<LocalImage>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }
}