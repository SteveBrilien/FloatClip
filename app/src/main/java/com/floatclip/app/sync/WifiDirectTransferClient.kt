package com.floatclip.app.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import com.floatclip.share.ShareSession
import com.floatclip.share.TransportCrypto
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Joins only the group advertised over an already authenticated FloatClip connection. */
internal object WifiDirectTransferClient {
    data class Offer(val ssid:String,val password:String,val port:Int,val address:String) {
        init {
            require(ssid.matches(Regex("DIRECT-FC-[A-F0-9]{8}")))
            require(password.matches(Regex("[A-F0-9]{32}")))
            require(port in 1024..65535)
            require(address.isEmpty()||address.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")))
        }
    }

    fun wifiOn(context:Context)=context.applicationContext.getSystemService(WifiManager::class.java).isWifiEnabled
    fun hasPermission(context:Context)=context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED

    fun join(context:Context,offer:Offer,token:ByteArray,cache:File,events:ShareSession.Events,
             opened:(ShareSession)->Unit,failed:()->Unit):Closeable {
        if(Build.VERSION.SDK_INT<29||!wifiOn(context)||!hasPermission(context))throw IOException("Wi-Fi 直连所需权限或连接不可用")
        val connectivity=context.getSystemService(ConnectivityManager::class.java)
        val closed=AtomicBoolean(false)
        val connecting=AtomicBoolean(false)
        val socket=AtomicReference<Socket?>()
        lateinit var callback:ConnectivityManager.NetworkCallback
        fun close() {
            if(!closed.compareAndSet(false,true))return
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            runCatching { socket.getAndSet(null)?.close() }
        }
        fun fail() { if(!closed.get()){close();failed()} }
        callback=object:ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network:Network,properties:LinkProperties) {
                val host=properties.routes.firstOrNull{it.isDefaultRoute&&it.gateway is Inet4Address}?.gateway
                    ?: (if(Build.VERSION.SDK_INT>=30)properties.dhcpServerAddress else null)
                    ?: offer.address.takeIf(String::isNotEmpty)?.let{runCatching{InetAddress.getByName(it)}.getOrNull()}
                    ?: return
                if(!host.isSiteLocalAddress)return
                if(closed.get()||!connecting.compareAndSet(false,true))return
                Thread({
                    try {
                        val peer=network.socketFactory.createSocket()
                        socket.set(peer)
                        if(closed.get()){peer.close();return@Thread}
                        peer.tcpNoDelay=true;peer.soTimeout=6000
                        peer.connect(InetSocketAddress(host,offer.port),7000)
                        val secure=TransportCrypto.connect(peer.getInputStream(),peer.getOutputStream(),token)
                        peer.soTimeout=180000
                        val session=ShareSession(secure.input,secure.output,Closeable{close()},cache,events,true,true)
                        session.enableLargeFiles()
                        if(closed.get())session.close() else opened(session)
                    } catch(_:Exception) { fail() }
                },"FloatClip Wi-Fi direct connection").apply{isDaemon=true;start()}
            }
            override fun onLost(network:Network)=fail()
            override fun onUnavailable()=fail()
        }
        val specifier=WifiNetworkSpecifier.Builder().setSsid(offer.ssid).setWpa2Passphrase(offer.password).build()
        val request=NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build()
        connectivity.requestNetwork(request,callback,35000)
        return Closeable{close()}
    }
}
