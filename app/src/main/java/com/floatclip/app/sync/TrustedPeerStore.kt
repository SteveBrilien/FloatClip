package com.floatclip.app.sync

import android.content.Context
import android.util.AtomicFile
import com.floatclip.app.security.LocalVaultCrypto
import com.floatclip.app.security.VaultCipher
import com.floatclip.share.ClipboardSync
import com.floatclip.share.ShareWire
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Device-bound transport credentials. The relationship key is sha256(token), the
 * same identity already used by Android's legacy share-pairs preference.
 *
 * Legacy installs can contain only the hash. The raw token is recovered lazily
 * when a previously authorized peer reconnects and proves that token again.
 */
class TrustedPeerStore(
    context: Context,
    private val cipher: VaultCipher = LocalVaultCrypto(),
) {
    data class Peer(
        val key: String,
        val token: ByteArray,
        val deviceId: UUID? = null,
        val name: String? = null,
        val platform: String? = null,
        val bluetoothAddress: String? = null,
    )

    private val file = AtomicFile(File(context.filesDir, "trusted_peers.v1.bin"))

    @Synchronized
    fun keys(): Set<String> = load().keys

    @Synchronized
    fun get(key: String): Peer? {
        val peer=load()[key.lowercase()] ?: return null
        return peer.copy(token=peer.token.copyOf())
    }

    @Synchronized
    fun putVerified(key: String, token: ByteArray) {
        val normalized = key.lowercase()
        require(validKey(normalized) && token.size == 32) { "Invalid trusted credential" }
        require(hash(token) == normalized) { "Credential relation mismatch" }
        val current = load().toMutableMap()
        if (normalized !in current && current.size >= MAX_PEERS) throw IOException("Too many trusted devices")
        val previous = current[normalized]
        if (previous != null && previous.token.contentEquals(token)) return
        current[normalized] = Peer(normalized, token.copyOf(), previous?.deviceId, previous?.name, previous?.platform, previous?.bluetoothAddress)
        save(current)
    }

    @Synchronized
    fun rememberDevice(key: String, device: ClipboardSync.Device) {
        val normalized = key.lowercase()
        val current = load().toMutableMap()
        val previous = current[normalized] ?: throw IOException("Trusted credential missing")
        if (previous.deviceId != null && previous.deviceId != device.id) throw IOException("Remembered device identity changed")
        if (previous.deviceId == device.id && previous.name == device.name && previous.platform == device.platform) return
        current[normalized] = previous.copy(deviceId = device.id, name = device.name, platform = device.platform)
        save(current)
    }

    @Synchronized
    fun rememberBluetooth(key: String,address: String) {
        val normalized=key.lowercase()
        val value=address.uppercase()
        require(BLUETOOTH.matches(value)) { "Invalid Bluetooth address" }
        val current=load().toMutableMap()
        val previous=current[normalized] ?: throw IOException("Trusted credential missing")
        if(previous.bluetoothAddress==value)return
        current[normalized]=previous.copy(bluetoothAddress=value)
        save(current)
    }

    @Synchronized
    fun remove(key: String) {
        val current = load().toMutableMap()
        if (current.remove(key.lowercase()) != null) save(current)
    }

    @Synchronized
    fun retain(valid: Set<String>) {
        val keep = valid.map { it.lowercase() }.toSet()
        val current = load()
        if (current.keys.all { it in keep }) return
        save(current.filterKeys { it in keep })
    }

    @Synchronized
    fun clear() {
        if (file.baseFile.exists()) file.delete()
    }

    private fun load(): Map<String, Peer> {
        if (!file.baseFile.exists()) return emptyMap()
        check(file.baseFile.length() <= 32768) { "Trusted peer state too large" }
        val plain = cipher.decrypt(file.readFully())
        val input = DataInputStream(ByteArrayInputStream(plain))
        if (input.readInt() != MAGIC) throw IOException("Trusted peer state version")
        val version=input.readUnsignedByte()
        if(version !in 1..VERSION)throw IOException("Trusted peer state version")
        val count = input.readUnsignedByte()
        if (count > MAX_PEERS) throw IOException("Too many trusted devices")
        val result = linkedMapOf<String, Peer>()
        repeat(count) {
            val key = input.readUTF().lowercase()
            if (!validKey(key) || result.containsKey(key)) throw IOException("Invalid trusted relation")
            val token = ByteArray(32).also(input::readFully)
            if (hash(token) != key) throw IOException("Trusted credential relation mismatch")
            val hasDevice = input.readBoolean()
            val peer = if (hasDevice) {
                val id = UUID(input.readLong(), input.readLong())
                val name = input.readUTF()
                val platform = input.readUTF()
                if (id == ZERO || name.isBlank() || name.length > 80 || name.any(Char::isISOControl) ||
                    platform !in setOf("android", "windows", "ipad", "web")
                ) throw IOException("Invalid trusted device")
                Peer(key, token, id, name, platform)
            } else Peer(key, token)
            val withBluetooth=if(version>=2&&input.readBoolean()){
                val address=input.readUTF().uppercase();if(!BLUETOOTH.matches(address))throw IOException("Invalid Bluetooth address");peer.copy(bluetoothAddress=address)
            }else peer
            result[key] = withBluetooth
        }
        if (input.available() != 0) throw IOException("Trailing trusted peer bytes")
        return result
    }

    private fun save(peers: Map<String, Peer>) {
        if (peers.size > MAX_PEERS) throw IOException("Too many trusted devices")
        val plain = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeByte(VERSION)
                out.writeByte(peers.size)
                for ((key, peer) in peers.toSortedMap()) {
                    require(validKey(key) && peer.token.size == 32 && hash(peer.token) == key)
                    out.writeUTF(key)
                    out.write(peer.token)
                    out.writeBoolean(peer.deviceId != null)
                    if (peer.deviceId != null) {
                        out.writeLong(peer.deviceId.mostSignificantBits)
                        out.writeLong(peer.deviceId.leastSignificantBits)
                        out.writeUTF(peer.name ?: throw IOException("Trusted device name missing"))
                        out.writeUTF(peer.platform ?: throw IOException("Trusted device platform missing"))
                    }
                    out.writeBoolean(peer.bluetoothAddress!=null)
                    if(peer.bluetoothAddress!=null){require(BLUETOOTH.matches(peer.bluetoothAddress));out.writeUTF(peer.bluetoothAddress)}
                }
            }
            bytes.toByteArray()
        }
        val output = file.startWrite()
        try {
            output.write(cipher.encrypt(plain))
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    companion object {
        private const val MAGIC = 0x46545031 // FTP1
        private const val VERSION = 2
        private const val MAX_PEERS = 8
        private val ZERO = UUID(0, 0)
        private val BLUETOOTH=Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")
        private fun validKey(value: String) = value.matches(Regex("[a-f0-9]{64}"))
        fun hash(token: ByteArray): String = ShareWire.digest(token).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
