package com.floatclip.app.clipboard

import com.floatclip.app.model.ClipEntry

object ClipboardQuery {
    fun matches(entry: ClipEntry, query: String, source: String = ""): Boolean =
        (source.isEmpty() || entry.sourceDeviceId == source) && query.trim().let { q ->
            q.isEmpty() || entry.text.contains(q, true) || entry.category.contains(q, true) ||
                entry.sourceDeviceName.contains(q, true) || entry.kind=="image" && "图片 image png".contains(q,true)
        }
}
