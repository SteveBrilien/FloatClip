package com.floatclip.app.capture

import com.floatclip.app.sync.SyncPreferences
import com.floatclip.share.TransportCrypto
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors

/** Loopback only, authenticated/encrypted with an ephemeral app-owned secret. */
class BackgroundClipboardCapture(
    private val prefs: SyncPreferences,
    private val copied: (String,String,Boolean,String?)->Unit,
    private val changed: ()->Unit
):Closeable {
    private var sendGeneration=prefs.policy().sendGeneration
    private val token=ByteArray(32).also{SecureRandom().nextBytes(it)}
    private val relation=hex(TransportCrypto.relation(token))
    private val server=ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))
    private val acceptor=Executors.newSingleThreadExecutor()
    private val reader=java.util.concurrent.ThreadPoolExecutor(0,2,30,java.util.concurrent.TimeUnit.SECONDS,java.util.concurrent.SynchronousQueue<Runnable>())
    @Volatile private var closed=false
    @Volatile private var client:Socket?=null
    @Volatile var connected=false;private set
    @Volatile var receivedFrames=0L;private set
    @Volatile var lastFrameAt=0L;private set

    fun refreshConsent(){
        val generation=prefs.policy().sendGeneration
        if(generation!=sendGeneration){sendGeneration=generation;runCatching{client?.close()}}
    }
    /** Called only after the provider verifies Binder calling UID=2000 and DUMP permission. */
    fun bootstrap()=android.os.Bundle().apply{
        putInt("port",server.localPort);putString("token",hex(token));putString("state","ready")
        putInt("uid",android.os.Process.myUid())
    }
    init {acceptor.execute {
        while(!closed)try{
            val socket=server.accept();socket.soTimeout=3000
            try{reader.execute{consume(socket)}}catch(_:java.util.concurrent.RejectedExecutionException){socket.close()}
        }catch(_:Exception){if(!closed)Thread.yield()}
    }}
    private fun consume(socket:Socket) {
        try{
            val secure=TransportCrypto.accept(socket.getInputStream(),socket.getOutputStream()){key->
                token.takeIf{!closed&&key==relation}
            }
            val consent=prefs.policy()
            if(!consent.enabled||!prefs.backgroundCapture())return
            // A caller without the secret cannot evict an authenticated companion.
            synchronized(this){if(closed)return;client?.close();client=socket}
            val input=DataInputStream(secure.input)
            val output=DataOutputStream(secure.output)
            output.writeInt(MAGIC);output.flush();socket.soTimeout=0
            connected=true;changed()
            while(!closed&&client===socket) {
                val frame=CaptureFrame.read(input)
                val current=prefs.policy()
                if(!current.enabled||!prefs.backgroundCapture()||current.sendGeneration!=consent.sendGeneration)break
                receivedFrames++;lastFrameAt=System.currentTimeMillis()
                copied(frame.text,frame.stamp,frame.excluded,consent.sendGeneration.takeIf{consent.sending})
            }
        }catch(_:Exception){}
        finally {
            synchronized(this){if(client===socket){client=null;connected=false;changed()}}
            runCatching{socket.close()}
        }
    }
    override fun close() {
        closed=true;connected=false
        runCatching{client?.close()};runCatching{server.close()}
        client=null;acceptor.shutdownNow();reader.shutdownNow()
    }
    companion object{
        private const val MAGIC=0x46434331
        private fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it.toInt() and 255)}
    }
}
