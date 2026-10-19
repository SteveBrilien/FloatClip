package com.floatclip.app.model

data class ClipEntry(
    val id: Long,
    val text: String,
    val pinned: Boolean = false,
    val category: String = "未分类",
    val createdAt: Long = System.currentTimeMillis(),
    val key: String = id.toString(),
    val updatedAt: Long = createdAt,
    val sourceDeviceId: String = "local",
    val sourceDeviceName: String = "本机",
    val sourcePlatform: String = "android",
)
