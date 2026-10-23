package com.floatclip.app.clipboard

import android.content.Context
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.sync.TrustedPeerStore

/**
 * First-class clipboard scopes shared by the full app and floating panel.
 * A trusted device remains visible even before it has delivered its first clip.
 */
object ClipboardSources {
    const val IMAGE_SCOPE = "__images__"
    data class Source(val id: String, val name: String, val platform: String)

    fun tabs(context: Context, entries: List<ClipEntry>): List<Source> =
        tabs(entries, runCatching { TrustedPeerStore(context).peers() }.getOrDefault(emptyList()))

    internal fun tabs(entries: List<ClipEntry>, peers: List<TrustedPeerStore.Peer>): List<Source> {
        val result = linkedMapOf(
            "" to Source("", "全部", "all"),
            IMAGE_SCOPE to Source(IMAGE_SCOPE, "图片", "image"),
            "local" to Source("local", "本机", "android"),
        )
        peers.forEach { peer ->
            val id = peer.deviceId?.toString() ?: return@forEach
            result.putIfAbsent(id, Source(id, peer.name ?: "已授权设备", peer.platform ?: "unknown"))
        }
        entries.asSequence().filter { it.sourceDeviceId != "local" }.forEach { entry ->
            result[entry.sourceDeviceId] = Source(entry.sourceDeviceId, entry.sourceDeviceName, entry.sourcePlatform)
        }
        return result.values.toList()
    }
}
