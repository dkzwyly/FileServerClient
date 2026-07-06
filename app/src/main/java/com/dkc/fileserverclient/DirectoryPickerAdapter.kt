package com.dkc.fileserverclient

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dkc.fileserverclient.databinding.ItemDirectoryPickerBinding

class DirectoryPickerAdapter(
    private val onItemClick: (DirectoryPickerActivity.DirectoryItem) -> Unit
) : RecyclerView.Adapter<DirectoryPickerAdapter.ViewHolder>() {

    private val items = mutableListOf<DirectoryPickerActivity.DirectoryItem>()

    fun submitList(list: List<DirectoryPickerActivity.DirectoryItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDirectoryPickerBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.folderName.text = item.name
        holder.binding.folderIcon.setImageResource(
            if (item.name == "..") R.drawable.ic_arrow_back else R.drawable.ic_folder
        )
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(val binding: ItemDirectoryPickerBinding) :
        RecyclerView.ViewHolder(binding.root)
}