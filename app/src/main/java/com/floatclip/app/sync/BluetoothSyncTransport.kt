package com.floatclip.app.sync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import com.floatclip.probe.BluetoothSyncHost
import com.floatclip.share.ShareSession
import com.floatclip.share.TransportCrypto
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Short-lived Bluetooth RFCOMM adapter. RFCOMM only carries bytes: the durable
 * FloatClip relation token authenticates each burst and TransportCrypto encrypts
 * every ShareWire/FCP3 frame before it reaches the Bluetooth socket.
 *
 * General Bluetooth discovery is deliberately not performed here, avoiding a
 * background-location permission solely for discovery on the fixed Android 11 target.
 * Once a trusted peer's Bluetooth address is learned, local clipboard events can
 * open a short outbound burst directly.
 */
@SuppressLint("MissingPermission")
class BluetoothSyncTransport(
    context: android.content.Context,
    private val credentials: TrustedPeerStore,
    private val authorized: (String) -> Boolean,
    private val canAttach: (String) -> Boolean,
    private val messages: (String) -> ShareSession.Events,
    private val status: (String) -> Unit,
) : Closeable {
    private val adapter: BluetoothAdapter?=BluetoothAdapter.getDefaultAdapter()
    private val cache=File(context.cacheDir,"sync-files")
    private val workers=Executors.newCachedThreadPool()
    private val timer=Executors.newSingleThreadScheduledExecutor()
    private val connecting=AtomicBoolean()
    private val started=AtomicBoolean()
    @Volatile private var closed=false

    private val host=BluetoothSyncHost(cache,object:BluetoothSyncHost.Events{
        override fun token(relation:String):ByteArray? =
            credentials.get(relation)?.token?.takeIf{authorized(relation)&&canAttach(relation)}
        override fun messages(relation:String)=this@BluetoothSyncTransport.messages(relation)
        override fun connected(relation:String,bluetoothAddress:String,session:ShareSession){
            // attached() has already queued service admission. The actor may have
            // accepted this very session, so canAttach() can now be false for itself.
            if(closed||!authorized(relation)){session.close();return}
            runCatching{credentials.rememberBluetooth(relation,bluetoothAddress)}
                .onFailure{session.close()}
        }
        override fun stopped(message:String){if(!closed)status(message)}
    })

    fun start(){
        if(adapter==null){status("此设备没有可用蓝牙适配器");return}
        if(!started.compareAndSet(false,true))return
        if(adapter.isEnabled)host.start()
    }

    /** Called only after a new shareable local clipboard entry was captured. */
    fun nudge(){
        if(closed||adapter?.isEnabled!=true||!connecting.compareAndSet(false,true))return
        workers.execute{
            try{
                val peers=credentials.keys().mapNotNull{key->
                    credentials.get(key)?.takeIf{authorized(key)&&canAttach(key)&&it.bluetoothAddress!=null}
                }
                for(peer in peers){
                    if(closed||!canAttach(peer.key))break
                    if(runCatching{connect(peer)}.isSuccess)break
                }
            }finally{connecting.set(false)}
        }
    }

    private fun connect(peer:TrustedPeerStore.Peer){
        val address=peer.bluetoothAddress?:return
        val socket=adapter!!.getRemoteDevice(address)
            .createRfcommSocketToServiceRecord(BluetoothSyncHost.SERVICE_UUID)
        var handedOff=false
        try{
            adapter.cancelDiscovery()
            socket.connect()
            val secure=TransportCrypto.connect(socket.inputStream,socket.outputStream,peer.token)
            check(secure.relationHex==peer.key&&authorized(peer.key)&&canAttach(peer.key)){"Trusted relation changed"}
            credentials.rememberBluetooth(peer.key,socket.remoteDevice.address)
            attach(peer.key,socket,secure)
            handedOff=true
        }finally{if(!handedOff)runCatching{socket.close()}}
    }

    private fun attach(key:String,socket:BluetoothSocket,secure:TransportCrypto.SecureStreams){
        if(closed||!authorized(key)||!canAttach(key)){socket.close();return}
        val delegate=messages(key)
        val session=ShareSession(secure.input,secure.output,Closeable{socket.close()},cache,delegate,true)
        if(closed||!authorized(key)||!canAttach(key)){session.close();return}
        delegate.attached(session)
        session.start()
        timer.schedule({session.close()},SHORT_SESSION_SECONDS,TimeUnit.SECONDS)
    }

    override fun close(){
        if(closed)return
        closed=true
        host.close()
        workers.shutdownNow()
        timer.shutdownNow()
    }

    companion object{
        private const val SHORT_SESSION_SECONDS=12L
    }
}
