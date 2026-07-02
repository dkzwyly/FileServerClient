package com.dkc.fileserverclient

data class Album(
    val id: String,
    val name: String,
    val imagePaths: MutableList<String>
)