package com.floatclip.app.model

data class ClipEntry(
    val id: Long,
    val text: String,
    val pinned: Boolean = false,
    val category: String = "未分类",
    val createdAt: Long = System.currentTimeMillis(),
)
