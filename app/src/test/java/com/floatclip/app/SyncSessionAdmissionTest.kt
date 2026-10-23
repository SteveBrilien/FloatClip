package com.floatclip.app

import android.content.Context
import com.floatclip.app.security.VaultCipher
import com.floatclip.app.sync.BluetoothSyncTransport
import com.floatclip.app.sync.ClipboardSyncService
import com.floatclip.app.sync.SyncPreferences
import com.floatclip.app.sync.TrustedPeerStore
import com.floatclip.probe.BluetoothSyncHost
import com.floatclip.share.ClipboardSync
import com.floatclip.share.ShareSession
import com.floatclip.share.ShareWire
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class SyncSessionAdmissionTest {
    private fun get(target: Any, name: String): Any = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)!!
    private fun set(target: Any, name: String, value: Any) { target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value) }
    private fun events(service: ClipboardSyncService, key: String, replace: Boolean) =
        service.javaClass.getDeclaredMethod("peerMessages", String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(service, key, replace) as ShareSession.Events
    private fun session(context: Context, events: ShareSession.Events) = ShareSession(
        ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), Closeable {}, File(context.cacheDir, "admission-test"), events, true)
    private open class EmptyEvents : ShareSession.Events {
        override fun text(id: UUID, text: String, outgoing: Boolean) = Unit
        override fun offered(offer: ShareWire.Offer) = Unit
        override fun progress(id: UUID, name: String, done: Long, total: Long, outgoing: Boolean) = Unit
        override fun file(offer: ShareWire.Offer, local: File, outgoing: Boolean) = Unit
        override fun notice(message: String) = Unit
        override fun closed() = Unit
    }

    @Test fun rejectedBluetoothCandidateDoesNotInvalidateLiveLanSession() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("floatclip_sync", Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = SyncPreferences(context).apply { save(enabled = true) }
        val key = "a".repeat(64)
        val trust = context.getSharedPreferences("share-pairs", Context.MODE_PRIVATE)
        trust.edit().clear().putBoolean("peer-$key", true).commit()
        val service = Robolectric.buildService(ClipboardSyncService::class.java).get()
        val actor = get(service, "actor") as ScheduledExecutorService
        fun drain() { actor.submit {}.get(3, TimeUnit.SECONDS) }
        val engine = ClipboardSync(ClipboardSync.Device(UUID.randomUUID(), "test", "android"),
            object : ClipboardSync.Storage {
                override fun load(): ByteArray? = null
                override fun save(bytes: ByteArray) = Unit
            }, object : ClipboardSync.Events {
                override fun send(peer: String, frame: ShareWire.Frame) = Unit
                override fun history(entry: ClipboardSync.Entry) = Unit
                override fun apply(entry: ClipboardSync.Entry) = Unit
            })
        set(service, "prefs", prefs); set(service, "trust", trust); set(service, "engine", engine)
        val liveEvents = events(service, key, true)
        val live = session(context, liveEvents)
        var candidate: ShareSession? = null
        try {
            liveEvents.attached(live); drain()
            assertSame(live, service.firstSession())
            val owners = get(service, "connectionTokens") as Map<*, *>
            val owner = owners[key]
            assertNotNull(owner)
            val candidateEvents = events(service, key, false)
            assertEquals("creating a candidate must not steal ownership", owner, owners[key])
            candidate = session(context, candidateEvents)
            candidateEvents.attached(candidate); drain()
            assertTrue(candidate.isClosed)
            assertFalse(live.isClosed)
            assertSame(live, service.firstSession())
            assertEquals(owner, owners[key])
            live.close(); drain()
            assertEquals("the original close callback must still own its slot", 0, service.connectedCount)
        } finally {
            set(service, "closing", true)
            live.close(); candidate?.close(); actor.shutdownNow()
        }
    }

    @Test fun bluetoothAddressCallbackDoesNotRejectItsOwnAdmittedSession() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "trusted_peers.v1.bin").delete()
        val codec = object : VaultCipher {
            override fun encrypt(plain: ByteArray) = plain.copyOf()
            override fun decrypt(payload: ByteArray) = payload.copyOf()
        }
        val credentials = TrustedPeerStore(context, codec)
        val token = ByteArray(32) { (it + 1).toByte() }
        val key = TrustedPeerStore.hash(token)
        credentials.putVerified(key, token)
        var authorized = true
        val transport = BluetoothSyncTransport(context, credentials, { authorized },
            { false }, { EmptyEvents() }, {})
        val host = get(transport, "host")
        val callbacks = get(host, "events") as BluetoothSyncHost.Events
        val admitted = session(context, EmptyEvents())
        try {
            // canAttach is false because this same session already occupies the peer slot.
            callbacks.connected(key, "AA:BB:CC:DD:EE:FF", admitted)
            assertFalse(admitted.isClosed)
            assertEquals("AA:BB:CC:DD:EE:FF", credentials.get(key)!!.bluetoothAddress)
            authorized = false
            callbacks.connected(key, "AA:BB:CC:DD:EE:FF", admitted)
            assertTrue("revoked authorization must still close the session", admitted.isClosed)
        } finally {
            admitted.close(); transport.close()
        }
    }
}
