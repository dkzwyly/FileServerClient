package com.dkc.fileserverclient

import androidx.recyclerview.widget.DiffUtil

class GalleryItemDiffCallback : DiffUtil.ItemCallback<GalleryItem>() {
    override fun areItemsTheSame(oldItem: GalleryItem, newItem: GalleryItem): Boolean {
        return when {
            oldItem is GalleryItem.DateHeader && newItem is GalleryItem.DateHeader ->
                oldItem.dateText == newItem.dateText
            oldItem is GalleryItem.ImageEntry && newItem is GalleryItem.ImageEntry ->
                oldItem.image.path == newItem.image.path
            else -> false
        }
    }

    override fun areContentsTheSame(oldItem: GalleryItem, newItem: GalleryItem): Boolean {
        return when {
            oldItem is GalleryItem.DateHeader && newItem is GalleryItem.DateHeader ->
                oldItem.dateText == newItem.dateText
            oldItem is GalleryItem.ImageEntry && newItem is GalleryItem.ImageEntry -> {
                val oldImg = oldItem.image
                val newImg = newItem.image
                oldImg.name == newImg.name &&
                        oldImg.size == newImg.size &&
                        oldImg.lastModified == newImg.lastModified &&
                        oldImg.isImage == newImg.isImage &&
                        oldImg.hasThumbnail == newImg.hasThumbnail &&
                        oldImg.dateTaken == newImg.dateTaken
            }
            else -> false
        }
    }
}