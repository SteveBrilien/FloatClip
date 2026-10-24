package com.floatclip.app.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.floatclip.share.ShareSession
import com.floatclip.share.ShareWire
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class SharedFile(
    val peer:String,val id:String,val version:String,val name:String,val size:Long,
    val modified:Long,val created:Long,val shared:Long,
    var source:String="",var cached:String="",var kept:Boolean=false,var online:Boolean=false,
    var done:Long=0,var error:String="",
) {val key get()="$peer:$id"}

object FileRuntime {
    const val CHANGED="com.floatclip.app.FILES_CHANGED"
    @Volatile private var repository:FileRepository?=null
    fun get(context:Context):FileRepository=repository?:synchronized(this){repository?:FileRepository(context.applicationContext).also{repository=it}}
}

/** Only explicitly imported files are advertised over authenticated catalog-capable sessions. */
class FileRepository(private val context:Context):AutoCloseable {
    private class Pending(val row:SharedFile){val id=UUID.randomUUID();val future=CompletableFuture<File>()}
    private class Link(val session:ShareSession,val allowed:()->Boolean){var pending:Pending?=null;var serving:UUID?=null}
    private val lock=Any()
    private val root=File(context.filesDir,"shared-files").apply{mkdirs()}
    private val sources=File(root,"sources").apply{mkdirs()}
    private val wireCache=File(context.cacheDir,"sync-files").apply{mkdirs()}
    private val database=AtomicFile(File(root,"catalog.json"))
    private val rows=linkedMapOf<String,SharedFile>()
    private val links=mutableMapOf<String,Link>()
    private val worker=Executors.newSingleThreadExecutor()
    private val deadlines=Executors.newSingleThreadScheduledExecutor()
    @Volatile var warning:String=""
        private set
    init {
        if(database.baseFile.exists())try{
            check(database.baseFile.length()<=1048576){"文件清单过大"}
            val all=JSONArray(database.readFully().toString(Charsets.UTF_8))
            check(all.length()<=512){"文件清单过大"}
            for(i in 0 until all.length()){
                val json=all.getJSONObject(i);val row=parse(json,json.getString("peer"))
                row.source=json.optString("source").takeIf{safeFile(it,sources)!=null}.orEmpty()
                row.cached=json.optString("cached").takeIf{safeFile(it,wireCache)!=null}.orEmpty()
                row.kept=json.optBoolean("kept");rows[row.key]=row
            }
        }catch(e:Exception){rows.clear();runCatching{database.baseFile.copyTo(File(root,"catalog.recovery-${UUID.randomUUID()}.json"))}}
    }
    private fun safeFile(path:String,parent:File):File?=runCatching{val f=File(path).canonicalFile;f.takeIf{it.isFile&&it.path.startsWith(parent.canonicalPath+File.separator)}}.getOrNull()
    fun local(row:SharedFile):File?=safeFile(row.source,sources)?:safeFile(row.cached,wireCache)
    fun snapshot():List<SharedFile> = synchronized(lock){rows.values.sortedByDescending{it.shared}.toList()}
    fun connectedFilePeers():Int=synchronized(lock){links.values.count{!it.session.isClosed&&it.allowed()}}
    fun byKey(key:String):SharedFile?=synchronized(lock){rows[key]}
    private fun encode(row:SharedFile)=JSONObject().put("id",row.id).put("version",row.version).put("name",row.name).put("size",row.size).put("modified",row.modified).put("created",row.created).put("shared",row.shared)
    private fun parse(json:JSONObject,peer:String):SharedFile {
        val row=SharedFile(peer,json.getString("id"),json.getString("version"),ShareWire.safeName(json.getString("name")),json.getLong("size"),json.getLong("modified"),json.getLong("created"),json.getLong("shared"))
        check(row.id.matches(Regex("(?:[a-f0-9]{32}|[a-f0-9]{64})"))&&row.version.matches(Regex("[a-f0-9]{64}"))&&row.size in 0..ShareWire.MAX_FILE&&row.modified>=0&&row.created>=0&&row.shared>=0){"文件清单无效"};return row
    }
    private fun save(){
        val all=JSONArray();rows.values.forEach{row->all.put(encode(row).put("peer",row.peer).put("source",row.source).put("cached",row.cached).put("kept",row.kept))}
        val data=all.toString().toByteArray();check(data.size<=1048576){"文件清单已满"}
        val stream=database.startWrite();try{stream.write(data);database.finishWrite(stream)}catch(e:Exception){database.failWrite(stream);throw e}
    }
    private fun changed(){context.sendBroadcast(Intent(FileRuntime.CHANGED).setPackage(context.packageName),context.packageName+".permission.INTERNAL")}
    private fun send(link:Link,json:JSONObject){if(link.allowed())link.session.sendCatalog(json.put("v",1).toString().toByteArray(Charsets.UTF_8))}
    fun publish(){val data=synchronized(lock){JSONArray().apply{rows.values.filter{it.peer=="local"&&local(it)!=null}.take(64).forEach{put(encode(it))}}};val peers=synchronized(lock){links.values.toList()};peers.forEach{link->runCatching{send(link,JSONObject().put("op","list").put("files",data))}.onFailure{link.session.close()}};changed()}
    fun attach(peer:String,session:ShareSession,allowed:()->Boolean){if(!session.catalogEnabled)return;synchronized(lock){links[peer]=Link(session,allowed)};publish()}
    fun disconnected(peer:String,session:ShareSession){synchronized(lock){val link=links[peer];if(link?.session!==session)return;links.remove(peer);rows.values.filter{it.peer==peer}.forEach{it.online=false};link.pending?.future?.completeExceptionally(IOException("来源设备已离线"))};changed()}
    fun catalog(peer:String,data:ByteArray){
        val link=synchronized(lock){links[peer]}?:return;if(!link.allowed())return
        val json=JSONObject(ShareWire.text(data));check(json.getInt("v")==1)
        when(json.getString("op")){
            "list"->{val list=json.getJSONArray("files");check(list.length()<=64);val parsed=(0 until list.length()).map{parse(list.getJSONObject(it),peer)};check(parsed.map{it.id}.distinct().size==parsed.size)
                synchronized(lock){
                    warning=""
                    rows.values.filter{it.peer==peer}.forEach{it.online=false}
                    for(row in parsed){
                        val old=rows[row.key]
                        if(old==null&&rows.size>=512){
                            val victim=rows.values.firstOrNull{!it.online&&!it.kept&&local(it)==null}
                            if(victim==null){warning="文件记录已满，请在管理中清理后刷新；文字同步不受影响";continue}
                            rows.remove(victim.key)
                        }
                        row.kept=old?.kept?:false;if(old?.version==row.version)row.cached=old.cached;row.online=true;rows[row.key]=row
                    }
                    save()
                };changed()}
            "get"->{val requestText=json.getString("request");check(requestText.matches(Regex("[a-f0-9]{32}")));val request=UUID.fromString(requestText.replace(Regex("^(.{8})(.{4})(.{4})(.{4})(.{12})$"),"$1-$2-$3-$4-$5"));val id=json.getString("id");val version=json.getString("version")
                worker.execute{try{check(link.allowed());val row=synchronized(lock){rows["local:$id"]?.takeIf{it.version==version}}?:throw IOException("源文件已移除或变化");val file=local(row)?:throw IOException("源文件不可用");synchronized(lock){check(link.serving==null){"设备正在传输其他文件"};link.serving=request};link.session.sendFile(file,row.name,request)}catch(e:Exception){synchronized(lock){if(link.serving==request)link.serving=null};runCatching{send(link,JSONObject().put("op","error").put("request",request.toString().replace("-","")).put("message","文件不可用，请刷新清单"))}}}}
            "error"->{val request=json.getString("request");synchronized(lock){val pending=link.pending;if(pending!=null&&pending.id.toString().replace("-","")==request){link.pending=null;pending.row.error="来源设备未能提供文件";pending.future.completeExceptionally(IOException(pending.row.error))}};changed()}
            else->throw IOException("文件清单操作无效")
        }
    }
    fun download(row:SharedFile):CompletableFuture<File> {
        local(row)?.let{return CompletableFuture.completedFuture(it)}
        if(row.peer=="local")return failure("本机文件副本不可用，请重新添加")
        if(synchronized(lock){links[row.peer]?.let{!it.session.isClosed&&it.allowed()&&rows[row.key]?.online==true}!=true}) {
            val result=CompletableFuture<File>()
            com.floatclip.app.sync.SyncRuntime.current()?.requestFileConnection(row.peer)
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25)
            fun poll() {
                if(result.isDone)return
                val current=synchronized(lock){rows[row.key]?.takeIf{it.online&&links[row.peer]?.let{l->!l.session.isClosed&&l.allowed()}==true}}
                if(current!=null) {
                    if(current.version!=row.version){result.completeExceptionally(IOException("源文件已变化，请重新选择"));return}
                    val transfer=downloadConnected(current)
                    result.whenComplete{_,_->if(result.isCancelled)transfer.cancel(true)}
                    transfer.whenComplete{file,error->if(error==null)result.complete(file) else result.completeExceptionally(error)}
                } else if(System.nanoTime()>=until)result.completeExceptionally(IOException("未能连接来源设备，请检查蓝牙或连接同一 Wi-Fi / 热点"))
                else deadlines.schedule({poll()},200,TimeUnit.MILLISECONDS)
            }
            poll();return result
        }
        return downloadConnected(row)
    }
    private fun downloadConnected(row:SharedFile):CompletableFuture<File>{val link:Link;val pending=Pending(row)
        synchronized(lock){link=links[row.peer]?:return failure("来源设备连接已结束，请重试");if(!link.allowed()||!row.online)return failure("设备共享已暂停或文件已撤回");if(link.pending!=null)return failure("请等待该设备当前下载完成");link.pending=pending;row.done=0;row.error=""}
        try{send(link,JSONObject().put("op","get").put("id",row.id).put("version",row.version).put("request",pending.id.toString().replace("-","")))}catch(e:Exception){synchronized(lock){link.pending=null};pending.future.completeExceptionally(e)}
        val timeout=deadlines.schedule({if(!pending.future.isDone){pending.future.completeExceptionally(IOException("下载超时，可重新连接后重试"));link.session.close()}},15,TimeUnit.MINUTES)
        pending.future.whenComplete{_,_->timeout.cancel(false);if(pending.future.isCancelled)link.session.close()};return pending.future
    }
    fun offered(peer:String,offer:ShareWire.Offer):Boolean {
        val link=synchronized(lock){links[peer]}?:return false;val pending=synchronized(lock){link.pending}?:return false;if(pending.id!=offer.id)return false
        if(!link.allowed()||offer.name!=pending.row.name||offer.size!=pending.row.size){link.session.reject(offer.id);failed(peer,"文件与所选条目不一致");return true}
        runCatching{link.session.accept(offer.id)}.onFailure{failed(peer,it.message?:"接收失败");link.session.close()};return true
    }
    fun progress(peer:String,id:UUID,done:Long,total:Long,outgoing:Boolean){if(outgoing)return;synchronized(lock){val p=links[peer]?.pending?:return;if(p.id!=id)return;p.row.done=done;rows[p.row.key]?.takeIf{it.version==p.row.version}?.done=done};changed()}
    fun received(peer:String,offer:ShareWire.Offer,file:File,outgoing:Boolean){synchronized(lock){val link=links[peer];if(outgoing){if(link?.serving==offer.id)link.serving=null;return};val p=link?.pending
        val target=if(p!=null&&p.id==offer.id)rows[p.row.key]?.takeIf{it.version==p.row.version}?:p.row else SharedFile(peer,offer.id.toString().replace("-",""),offer.hash.joinToString(""){"%02x".format(it.toInt()and 255)},offer.name,offer.size,0,0,System.currentTimeMillis())
        target.cached=safeFile(file.path,wireCache)?.path?:throw IOException("文件缓存路径无效");target.done=offer.size;target.error="";rows[target.key]=target;save();if(p!=null&&p.id==offer.id){link.pending=null;p.future.complete(file)}};changed()}
    fun failed(peer:String,message:String){synchronized(lock){val link=links[peer]?:return;val pending=link.pending;link.pending=null;pending?.future?.completeExceptionally(IOException(message));val serving=link.serving;link.serving=null;if(serving!=null)runCatching{send(link,JSONObject().put("op","error").put("request",serving.toString().replace("-","")).put("message","文件传输未完成"))}};changed()}
    fun import(uri:Uri):SharedFile {
        check(synchronized(lock){rows.values.count{it.peer=="local"}<64}){"最多共享 64 个文件"}
        var name="文件";var modified=0L
        context.contentResolver.query(uri,null,null,null,null)?.use{c->if(c.moveToFirst()){val i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0)name=c.getString(i);val m=c.getColumnIndex("last_modified");if(m>=0&&!c.isNull(m))modified=c.getLong(m)}}
        ShareWire.safeName(name);val id=UUID.randomUUID().toString().replace("-","");val destination=File(sources,id);val temp=File(sources,"$id.part");val hash=ShareWire.sha();var size=0L
        try{context.contentResolver.openInputStream(uri)?.use{input->temp.outputStream().use{output->val buffer=ByteArray(32768);while(true){val count=input.read(buffer);if(count<0)break;size+=count;check(size<=ShareWire.MAX_FILE){"单个文件最大 64 MiB"};check(sources.listFiles().orEmpty().sumOf{it.length()}<=256L*1024*1024){"共享文件空间已满，请移除不需要的文件"};hash.update(buffer,0,count);output.write(buffer,0,count)}}}?:throw IOException("无法读取所选文件");check(temp.renameTo(destination)){"文件未能保存"}}
        finally{temp.delete()}
        val row=SharedFile("local",id,hash.digest().joinToString(""){"%02x".format(it.toInt()and 255)},name,size,modified,0,System.currentTimeMillis(),source=destination.path,online=true)
        synchronized(lock){rows[row.key]=row;save()};publish();com.floatclip.app.sync.SyncRuntime.current()?.requestFileConnection();return row
    }
    private fun failure(message:String)=CompletableFuture<File>().apply{completeExceptionally(IOException(message))}
    fun keep(row:SharedFile,value:Boolean){synchronized(lock){rows[row.key]?.kept=value;save()};changed()}
    fun clear(items:List<SharedFile>,records:Boolean,contents:Boolean){synchronized(lock){items.mapNotNull{rows[it.key]?.takeIf{row->row.version==it.version}}.filter{!it.kept}.forEach{row->if(contents)safeFile(row.cached,wireCache)?.delete();if(contents)row.cached="";if(records){if(row.peer=="local")safeFile(row.source,sources)?.delete();rows.remove(row.key)}};save()};publish()}
    override fun close(){val peers=synchronized(lock){links.values.toList()};peers.forEach{it.session.close()};worker.shutdownNow();deadlines.shutdownNow()}
}
