package com.floatclip.app.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.floatclip.app.R
import com.floatclip.app.files.FileRuntime
import com.floatclip.app.UnifiedActivity
import com.floatclip.app.clipboard.ClipStore
import com.floatclip.app.clipboard.ClipboardWrites
import com.floatclip.app.clipboard.DeviceIdentity
import com.floatclip.app.prefs.OverlayPreferences
import com.floatclip.probe.BluetoothPairHost
import com.floatclip.probe.LanSyncHost
import com.floatclip.share.ClipboardSync
import com.floatclip.share.ShareSession
import com.floatclip.share.ShareWire
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.concurrent.atomic.AtomicBoolean

/** Connection ownership belongs to this foreground service, never to an Activity. */
class ClipboardSyncService: Service() {
    private val actor=Executors.newSingleThreadScheduledExecutor()
    private val main=Handler(Looper.getMainLooper())
    private val hosts=ConcurrentHashMap<String,LanSyncHost>()
    private val links=ConcurrentHashMap<String,Link>()
    private val connectionTokens=ConcurrentHashMap<String,UUID>()
    private lateinit var prefs: SyncPreferences
    private lateinit var engine: ClipboardSync
    private lateinit var library: ClipStore
    private lateinit var clipboard: ClipboardManager
    private lateinit var networkManager: ConnectivityManager
    @Volatile private var paused=false
    @Volatile private var closing=false
    @Volatile private var pairingUntil=0L
    @Volatile private var lastCapture=""
    private lateinit var trust: android.content.SharedPreferences
    private lateinit var credentials: TrustedPeerStore
    private var backgroundCapture: com.floatclip.app.capture.BackgroundClipboardCapture?=null
    val backgroundCaptureConnected get()=backgroundCapture?.connected==true
    fun captureBootstrap()=if(!closing&&!paused&&prefs.enabled()&&prefs.backgroundCapture())backgroundCapture?.bootstrap() else null
    private var bluetooth: BluetoothSyncTransport?=null
    private var bluetoothPairing: BluetoothPairHost?=null
    private val trustListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,_ -> if(!closing)actor.execute {
        if(::engine.isInitialized) { for(key in engine.peerKeys())if(!authorized(key)){links.remove(key)?.close();engine.forget(key)} };refreshUi()
    }}
    private fun authorized(key: String)=trust.getBoolean("peer-"+key,false)
    private var pairRequest: Consumer<Boolean>?=null
    private var registered=false
    @Volatile var status="正在准备共享";private set
    val connectedCount get()=links.size
    @Volatile private var pendingOutgoingCount=0
    fun pairingOpen()=prefs.enabled()&&System.currentTimeMillis()<pairingUntil
    fun closePairing() {
        pairingUntil=0L
        pairRequest?.accept(false);pairRequest=null
        bluetoothPairing?.close();bluetoothPairing=null
        if(closing)return
        actor.execute {
            setStatus(if(links.isEmpty()) "配对窗口已关闭 · 等待已授权设备" else "配对窗口已关闭 · 已授权设备保持连接")
            refreshUi()
        }
    }
    fun firstSession(): ShareSession?=links.values.firstOrNull()?.session

    private data class Queued(val frame: ShareWire.Frame,val sendGeneration: String,val receiveGeneration: String)
    private inner class Link(val key: String,val session: ShareSession) {
        private val writer=Executors.newSingleThreadExecutor()
        private val pending=ConcurrentHashMap<Int,Queued>()
        private val scheduled=AtomicBoolean()
        fun send(frame: ShareWire.Frame) {
            if(closing||paused)return
            val p=prefs.policy();pending[frame.type]=Queued(frame,p.appliedSend,p.appliedReceive)
            schedule()
        }
        private fun schedule(){if(scheduled.compareAndSet(false,true))runCatching {writer.execute {
            try {
                while(!closing&&!paused&&prefs.enabled()&&links[key]===this) {
                    val queued=listOf(ClipboardSync.HELLO,ClipboardSync.ACK,ClipboardSync.CLIP).firstNotNullOfOrNull{pending.remove(it)}?:break
                    val frame=queued.frame;val p=prefs.policy()
                    if((frame.type!=ClipboardSync.CLIP||p.sending&&queued.sendGeneration==p.sendGeneration)&&(frame.type!=ClipboardSync.ACK||p.receiving&&queued.receiveGeneration==p.receiveGeneration)&&authorized(key)&&engine.maySend(key,frame))runCatching {session.sendSync(frame)}.onFailure{session.close()}
                }
            } finally {scheduled.set(false);if(pending.isNotEmpty()&&!closing&&!paused&&links[key]===this)schedule()}
        }}.onFailure{scheduled.set(false)}}
        fun close(){pending.clear();writer.shutdownNow();session.close()}
    }
    private val clipListener=ClipboardManager.OnPrimaryClipChangedListener {
        runCatching {clipboard.primaryClip?.let{SyncRuntime.observe(this,it)}}
    }
    private val networkCallback=object: ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network){refreshNetwork()}
        override fun onLost(network: Network){refreshNetwork()}
        override fun onLinkPropertiesChanged(network: Network,properties: android.net.LinkProperties){refreshNetwork()}
    }
    private val hotspotReceiver=object: BroadcastReceiver(){override fun onReceive(c: Context?,i: Intent?){refreshNetwork()}}
    private val bluetoothReceiver=object: BroadcastReceiver(){override fun onReceive(c: Context?,i: Intent?){
        if(i?.action==android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED&&!closing)actor.execute{
            bluetooth?.close();bluetooth=null
            bluetoothPairing?.close();bluetoothPairing=null
            if(::engine.isInitialized){refreshBluetooth();refreshBluetoothPairing()}
        }
    }}

    override fun onCreate() {
        super.onCreate();prefs=SyncPreferences(this);clipboard=getSystemService(ClipboardManager::class.java)
        trust=getSharedPreferences("share-pairs",MODE_PRIVATE);trust.registerOnSharedPreferenceChangeListener(trustListener)
        credentials=TrustedPeerStore(this)
        lastCapture=runCatching{clipboard.primaryClip?.let{SyncRuntime.stamp(it)}}.getOrNull().orEmpty()
        val notifications=getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,"设备共享",NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION,notification())
        SyncRuntime.attach(this)
        library=ClipStore(this)
        networkManager=getSystemService(ConnectivityManager::class.java)
        actor.execute {
            runCatching {
                val local=DeviceIdentity.json(this)
                engine=ClipboardSync(ClipboardSync.Device(UUID.fromString(local.getString("id")),local.getString("name"),"android"),EncryptedSyncStorage(this),object:ClipboardSync.Events {
                    override fun send(peer: String,frame: ShareWire.Frame){links[peer]?.send(frame)}
                    override fun history(entry: ClipboardSync.Entry){library.receiveShared(entry);historyChanged()}
                    override fun apply(entry: ClipboardSync.Entry){val grant=engine.applicationGrant();val generation=prefs.policy().appliedApply;main.post {
                        val current=runCatching{clipboard.primaryClip}.getOrNull()
                        if(current!=null&&!ClipboardWrites.isOwnClip(this@ClipboardSyncService,current.description)&&SyncRuntime.stamp(current)!=lastCapture) {
                            SyncRuntime.observe(this@ClipboardSyncService,current);return@post
                        }
                        if(!closing&&!paused&&prefs.enabled()&&prefs.receiving()&&prefs.applying()&&prefs.policy().applyGeneration==generation&&engine.peerViews().any{it.device.id==entry.source.id&&authorized(it.key)}&&engine.mayApply(entry,grant)) {
                            runCatching{ClipboardWrites.write(this@ClipboardSyncService,clipboard,entry.text)}.onFailure{setStatus("内容已收到，当前系统未允许写入剪贴板")}
                        }
                    }}
                })
                // Pairing grants remain authoritative during migration. The encrypted token
                // store supplements legacy hash-only grants and is pruned when trust is revoked.
                val authorizedKeys=trust.all.keys.filter{it.startsWith("peer-")&&trust.getBoolean(it,false)}.map{it.removePrefix("peer-")}.toSet()
                credentials.retain(authorizedKeys)
                for(key in engine.peerKeys())if(!authorized(key))engine.forget(key)
                configureEngine();setStatus("等待已授权设备 · Android 11 后台读取受系统限制")
                refreshBluetooth()
                actor.scheduleWithFixedDelay({
                    if(!closing&&!paused&&::engine.isInitialized)runCatching {
                        configureEngine()
                        val now=System.currentTimeMillis()
                        for(key in links.keys)engine.retry(key,now)
                        refreshHosts();refreshBluetooth()
                        val outbox=engine.pendingPeers(now);pendingOutgoingCount=outbox.size
                        val pending=outbox.filterNot{links.containsKey(it)}.toSet()
                        if(pending.isNotEmpty())bluetooth?.nudge(pending)
                    }.onFailure{setStatus("待发送内容未保存，请检查本机存储")}
                },5,5,TimeUnit.SECONDS)
                refreshHosts()
            }.onFailure {setStatus("无法读取共享状态，请保留数据后检查存储");pauseImmediately()}
        }
        clipboard.addPrimaryClipChangedListener(clipListener)
        networkManager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),networkCallback)
        registerReceiver(hotspotReceiver,IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED"))
        registerReceiver(bluetoothReceiver,IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED));registered=true
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action==PAUSE){prefs.save(enabled=false);pauseImmediately();stopSelf();return START_NOT_STICKY}
        if(!prefs.enabled()){pauseImmediately();stopSelf();return START_NOT_STICKY}
        if(intent?.action==PAIR){
            val until=System.currentTimeMillis()+90000;pairingUntil=until
            actor.execute{refreshBluetoothPairing()}
            actor.schedule({if(!closing&&pairingUntil==until){bluetoothPairing?.close();bluetoothPairing=null;if(links.isEmpty())setStatus("等待已授权设备自动连接")}},90,TimeUnit.SECONDS)
        }
        actor.execute {if(::engine.isInitialized&&!closing){runCatching{configureEngine();refreshHosts();refreshBluetooth();refreshBluetoothPairing()}.onFailure{setStatus("共享配置未保存，已暂停");pauseImmediately()}}}
        return if (prefs.keepAlive()) START_STICKY else START_NOT_STICKY
    }
    private fun configureEngine(){
        paused=!SyncPolicy.apply(prefs,engine).enabled
        if(closing||paused||!prefs.backgroundCapture()) {
            backgroundCapture?.close();backgroundCapture=null
        } else if(backgroundCapture==null) {
            backgroundCapture=com.floatclip.app.capture.BackgroundClipboardCapture(prefs,
                {text,event,excluded,generation->copied(text,event,excluded,generation)},
                {refreshUi()})
        }
        backgroundCapture?.refreshConsent()
    }
    fun copied(text: String,event: String,excluded: Boolean=false,shareGeneration: String?=null,recordHistory: Boolean=true) {
        if(closing||paused||!prefs.enabled())return
        actor.execute {
            if(!closing&&!paused&&::engine.isInitialized&&event!=lastCapture&&prefs.enabled()) {
                runCatching {configureEngine();refreshBluetooth();val p=prefs.policy();val mayShare=!excluded&&SyncPolicy.mayShare(p,shareGeneration);if(!excluded&&recordHistory)library.addText(text);engine.copied(text,System.currentTimeMillis(),!mayShare);pendingOutgoingCount=engine.pendingPeers(System.currentTimeMillis()).size;lastCapture=event;if(!excluded&&recordHistory)historyChanged();if(mayShare)bluetooth?.nudge()}
                    .onFailure{setStatus("当前内容未同步，请检查存储或文字长度")}
            }
        }
    }
    fun answerPairing(answer: Boolean){pairRequest?.accept(answer);pairRequest=null}
    fun refreshUi(){SyncRuntime.changed()}
    fun pauseImmediately(){
        if(closing)return
        backgroundCapture?.close();backgroundCapture=null
        paused=true;pairingUntil=0;pairRequest?.accept(false);pairRequest=null
        bluetoothPairing?.close();bluetoothPairing=null
        for((key,link) in links){FileRuntime.get(this).disconnected(key,link.session);link.close()};links.clear()
        actor.execute{for(host in hosts.values)host.close();hosts.clear();bluetooth?.close();bluetooth=null;if(::engine.isInitialized)runCatching{engine.configure(false,prefs.sending(),prefs.receiving(),prefs.applying())}}
        setStatus("共享已暂停")
    }
    private fun refreshBluetooth(){
        if(closing||paused||!prefs.enabled()||!prefs.reconnect()){
            bluetooth?.close();bluetooth=null;return
        }
        if(bluetooth!=null){bluetooth?.start();return}
        bluetooth=BluetoothSyncTransport(this,credentials,::authorized,
            {key->!closing&&!paused&&prefs.enabled()&&prefs.reconnect()&&!links.containsKey(key)},
            {key->peerMessages(key,false)},
            {message->if(!closing&&links.isEmpty())setStatus(message)}
        ).also{it.start()}
    }

    private fun refreshBluetoothPairing(){
        if(closing||paused||!prefs.enabled()||!pairingOpen()||!SyncRuntime.hasObserver()){
            bluetoothPairing?.close();bluetoothPairing=null;return
        }
        if(bluetoothPairing!=null)return
        lateinit var host: BluetoothPairHost
        host=BluetoothPairHost(object:BluetoothPairHost.Events{
            override fun pairingAllowed()=!closing&&!paused&&prefs.enabled()&&pairingOpen()&&SyncRuntime.hasObserver()
            override fun verify(code:String,answer:Consumer<Boolean>){main.post{
                if(!pairingAllowed()){answer.accept(false);return@post}
                pairRequest?.accept(false);pairRequest=answer;SyncRuntime.verify(code,answer)
            }}
            override fun paired(token:ByteArray,bluetoothAddress:String){
                val key=TrustedPeerStore.hash(token)
                var trustSaved=false
                try {
                    credentials.putVerified(key,token)
                    if(!trust.edit().putBoolean("peer-"+key,true).commit())throw java.io.IOException("Cannot persist authorization")
                    trustSaved=true
                    credentials.rememberBluetooth(key,bluetoothAddress)
                } catch(e: Exception) {
                    if(trustSaved)trust.edit().remove("peer-"+key).commit()
                    runCatching{credentials.remove(key)}
                    throw e
                }
                actor.execute{setStatus("蓝牙设备已授权 · 后续可短连接自动同步");refreshUi()}
            }
            override fun stopped(message:String){if(!closing)actor.execute{
                if(bluetoothPairing===host)bluetoothPairing=null
                if(pairingOpen())setStatus(message)
            }}
        })
        if(host.start())bluetoothPairing=host else host.close()
    }

    private fun peerMessages(peer: String,replaceExisting: Boolean=true): ShareSession.Events {
        val token=UUID.randomUUID()
        return object:ShareSession.Events {
            override fun attached(session: ShareSession){actor.execute {
                if(closing||paused||!prefs.enabled()||!authorized(peer)||session.isClosed||(!replaceExisting&&!prefs.reconnect())){session.close();return@execute}
                val previous=links[peer]
                if(previous!=null&&!replaceExisting){session.close();return@execute}
                // Publish ownership only after admission. A rejected Bluetooth candidate
                // must never invalidate callbacks belonging to the existing LAN session.
                connectionTokens[peer]=token
                if(previous!=null){FileRuntime.get(this@ClipboardSyncService).disconnected(peer,previous.session);links.remove(peer,previous);previous.close()}
                links[peer]=Link(peer,session)
                FileRuntime.get(this@ClipboardSyncService).attach(peer,session){!closing&&!paused&&prefs.enabled()&&authorized(peer)&&connectionTokens[peer]==token&&engine.peerViews().none{it.key==peer&&!it.enabled}}
                runCatching{configureEngine();engine.connected(peer);setStatus("${links.size} 台设备已连接 · 加密共享")}.onFailure{links.remove(peer)?.close();setStatus("设备授权状态未保存")}
            }}
            override fun sync(frame: ShareWire.Frame){if(!closing)actor.execute{if(!closing&&!paused&&prefs.enabled()&&(frame.type!=ClipboardSync.CLIP||prefs.receiving())&&authorized(peer)&&connectionTokens[peer]==token)runCatching{
                configureEngine();engine.accept(peer,frame,System.currentTimeMillis());pendingOutgoingCount=engine.pendingPeers(System.currentTimeMillis()).size
                if(frame.type==ClipboardSync.HELLO) {
                    engine.peerViews().firstOrNull{it.key==peer}?.let{credentials.rememberDevice(peer,it.device)}
                    FileRuntime.get(this@ClipboardSyncService).publish()
                }
            }.onFailure{links.remove(peer)?.close();setStatus("设备授权或同步校验失败，请重新核对")}}}
            override fun text(id: UUID,value: String,outgoing: Boolean)=Unit
            override fun catalog(data:ByteArray){if(!closing)actor.execute{if(connectionTokens[peer]==token)runCatching{FileRuntime.get(this@ClipboardSyncService).catalog(peer,data)}.onFailure{links[peer]?.close();setStatus("文件清单校验失败，可重新连接后重试")}}}
            override fun offered(offer: ShareWire.Offer){if(connectionTokens[peer]!=token)return;if(FileRuntime.get(this@ClipboardSyncService).offered(peer,offer))return;main.post{if(SyncRuntime.hasObserver())SyncRuntime.offer(peer,offer) else links[peer]?.session?.reject(offer.id)}}
            override fun progress(id: UUID,name: String,done: Long,total: Long,outgoing: Boolean){if(connectionTokens[peer]!=token)return;FileRuntime.get(this@ClipboardSyncService).progress(peer,id,done,total,outgoing);main.post{SyncRuntime.progress(name,done,total,outgoing)}}
            override fun file(offer: ShareWire.Offer,file: File,outgoing: Boolean){if(connectionTokens[peer]!=token)return;FileRuntime.get(this@ClipboardSyncService).received(peer,offer,file,outgoing);main.post{SyncRuntime.file(offer,file,outgoing)}}
            override fun fileFailed(message:String){if(connectionTokens[peer]==token)FileRuntime.get(this@ClipboardSyncService).failed(peer,message)}
            override fun notice(message: String){setStatus(message)}
            override fun closed(){if(!closing)actor.execute {
                val link=links[peer]
                if(link!=null&&connectionTokens[peer]==token&&link.session.isClosed){FileRuntime.get(this@ClipboardSyncService).disconnected(peer,link.session);links.remove(peer,link);link.close();engine.disconnected(peer);setStatus(if(links.isEmpty())"等待设备自动重连" else "${links.size} 台设备已连接")}
            }}
        }
    }
    private fun refreshNetwork(){if(!closing)actor.execute{if(::engine.isInitialized)refreshHosts()}}
    private fun refreshHosts(){
        if(closing||paused||!prefs.enabled())return
        // Enumerate only the phone's Wi-Fi/hotspot interfaces; never probe address ranges or change routes.
        val addresses=runCatching{Collections.list(NetworkInterface.getNetworkInterfaces()).filter{it.isUp&&(it.name.startsWith("wlan")||it.name.startsWith("ap"))}
            .flatMap{Collections.list(it.inetAddresses)}.filter{it is Inet4Address&&it.isSiteLocalAddress}.mapNotNull{it.hostAddress}.toSet()}.getOrDefault(emptySet())
        for(ip in hosts.keys.toList())if(ip !in addresses){hosts.remove(ip)?.close()}
        for(ip in addresses)if(!hosts.containsKey(ip)){
            val host=LanSyncHost(File(cacheDir,"sync-files"),trust,object:LanSyncHost.Events {
                override fun pairingAllowed()=!paused&&prefs.enabled()&&System.currentTimeMillis()<pairingUntil&&SyncRuntime.hasObserver()
                override fun reconnectAllowed()=!paused&&prefs.enabled()&&(prefs.reconnect()||pairingAllowed())
                override fun verify(code: String,answer: Consumer<Boolean>){main.post {
                    if(!pairingAllowed()){answer.accept(false);return@post}
                    pairRequest?.accept(false);pairRequest=answer;SyncRuntime.verify(code,answer)
                }}
                override fun credential(peer: String,token: ByteArray){credentials.putVerified(peer,token)}
                override fun messages(peer: String)=peerMessages(peer)
                override fun connected(peer: String,session: ShareSession)=Unit
                override fun stopped(message: String){if(!closing)actor.execute{hosts.remove(ip);setStatus(message)}}
            })
            hosts[ip]=host;host.listen(ip)
        }
        if(addresses.isEmpty()&&links.isEmpty())setStatus("等待蓝牙或局域网受信设备")
        else if(links.isEmpty())setStatus(if(System.currentTimeMillis()<pairingUntil)"允许添加设备，90 秒内有效" else "等待已授权设备自动连接")
    }
    fun peers(): List<ClipboardSync.PeerView> = if(::engine.isInitialized)engine.peerViews() else emptyList()
    fun peerEnabled(key: String,on: Boolean){if(!closing)actor.execute{runCatching{engine.peerEnabled(key,on);if(!on)links[key]?.close()}.onFailure{setStatus("设备共享开关未保存")};main.post{refreshUi()}}}
    fun session(peer: String)=links[peer]?.session
    private fun setStatus(value: String){status=value;main.post{if(!closing){getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification());SyncRuntime.changed()}}}
    private fun historyChanged(){sendBroadcast(Intent(HISTORY_CHANGED).setPackage(packageName),"$packageName.permission.INTERNAL")}
    private fun notification(): Notification {
        val flags=PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open=PendingIntent.getActivity(this,7080,Intent(this,UnifiedActivity::class.java),flags)
        val pause=PendingIntent.getService(this,7081,Intent(this,ClipboardSyncService::class.java).setAction(PAUSE),flags)
        val detailed=OverlayPreferences(this).detailedNotification()
        return Notification.Builder(this,CHANNEL)
            .setSmallIcon(R.drawable.ic_floatclip_app)
            .setContentTitle(if(detailed) "FloatClip 设备共享" else "FloatClip")
            .setContentText(if(detailed) status else "")
            .setContentIntent(open)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .apply {
                if(detailed) addAction(Notification.Action.Builder(null,"暂停共享",pause).build())
            }
            .build()
    }
    override fun dump(fd:java.io.FileDescriptor,writer:java.io.PrintWriter,args:Array<out String>?) {
        writer.println("FloatClip sync: enabled=${prefs.enabled()} connectedPeers=${links.size} pendingOutgoing=$pendingOutgoingCount")
        writer.println("File catalog: peers=${FileRuntime.get(this).connectedFilePeers()} local=${FileRuntime.get(this).snapshot().count{it.peer=="local"}} remote=${FileRuntime.get(this).snapshot().count{it.peer!="local"}}")
        writer.println("ADB capture: enabled=${prefs.backgroundCapture()} connected=${backgroundCaptureConnected}")
        writer.println("ADB capture frames=${backgroundCapture?.receivedFrames?:0} lastFrameAt=${backgroundCapture?.lastFrameAt?:0}")
    }
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onTaskRemoved(rootIntent: Intent?) {
        if(SyncKeepAliveScheduler.shouldRun(this))SyncKeepAliveScheduler.schedule(this)
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy(){
        backgroundCapture?.close();backgroundCapture=null
        closing=true;paused=true;pairRequest?.accept(false);pairRequest=null
        trust.unregisterOnSharedPreferenceChangeListener(trustListener)
        clipboard.removePrimaryClipChangedListener(clipListener)
        if(registered){unregisterReceiver(hotspotReceiver);unregisterReceiver(bluetoothReceiver);runCatching{networkManager.unregisterNetworkCallback(networkCallback)}}
        bluetoothPairing?.close();bluetoothPairing=null
        bluetooth?.close();bluetooth=null
        for(host in hosts.values.toList())host.close();for(link in links.values)link.close();links.clear();actor.shutdownNow();SyncRuntime.detach(this)
        if(SyncKeepAliveScheduler.shouldRun(this))SyncKeepAliveScheduler.schedule(this)
        super.onDestroy()
    }
    companion object {
        const val HISTORY_CHANGED="com.floatclip.app.CLIPBOARD_CHANGED"
        const val PAIR="com.floatclip.app.sync.PAIR"
        const val PAUSE="com.floatclip.app.sync.PAUSE"
        private const val CHANNEL="floatclip-device-sync"
        private const val NOTIFICATION=7080
    }
}
