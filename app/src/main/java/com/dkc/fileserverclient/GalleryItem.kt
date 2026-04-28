package com.dkc.fileserverclient

sealed class GalleryItem {
    data class DateHeader(val dateText: String) : GalleryItem()
    data class ImageEntry(val image: FileSystemItem) : GalleryItem()
}