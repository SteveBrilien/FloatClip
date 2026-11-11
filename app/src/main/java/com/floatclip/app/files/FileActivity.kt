package com.floatclip.app.files

import android.app.Activity
import android.content.*
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.floatclip.app.DeviceActivity
import com.floatclip.app.R
import com.floatclip.app.overlay.ui.EntryGestureRow
import com.floatclip.app.prefs.OverlayPreferences
import com.floatclip.app.sync.*
import com.floatclip.app.ui.FloatDialog
import com.floatclip.app.ui.FileDetailsSheet
import com.floatclip.app.ui.FileUi
import com.floatclip.app.clipboard.ImageImportActivity
import com.floatclip.share.ShareWire
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.function.Consumer

/** File metadata and selected downloads share the clipboard service's authenticated transport. */
class FileActivity:Activity(),SyncRuntime.Observer {
    private val repo by lazy{FileRuntime.get(this)}
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private val selected=linkedSetOf<String>()
    private var selecting=false
    private var range=0
    private var menu:PopupWindow?=null
    private var treeSelection=emptyList<SharedFile>()
    private var active:CompletableFuture<File>?=null
    @Volatile private var canceled=false
    @Volatile private var busy=false
    private var resumed=false
    private var pendingHandoff:(()->Unit)?=null
    private var batchDialog:FloatDialog.Handle?=null
    private var detailSheet:FileDetailsSheet?=null
    private var page=Color.WHITE;private var card=Color.WHITE;private var text=Color.BLACK
    private var muted=Color.GRAY;private var line=Color.LTGRAY;private var accent=Color.BLUE;private var tint=Color.LTGRAY
    private lateinit var colors:FileUi.Palette
    private lateinit var list:LinearLayout
    private lateinit var scroll:ScrollView
    private val repaint=Runnable{if(!isFinishing&&!busy)render()}
    private val receiver=object:BroadcastReceiver(){override fun onReceive(c:Context?,i:Intent?){main.removeCallbacks(repaint);main.postDelayed(repaint,180)}}
    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    override fun onCreate(state:Bundle?){super.onCreate(state);range=state?.getInt("range")?:0;selecting=state?.getBoolean("selecting")?:false;selected.addAll(state?.getStringArrayList("selected").orEmpty());
        val filter=IntentFilter(FileRuntime.CHANGED).apply{addAction(SyncRuntime.STATE_CHANGED)}
        if(android.os.Build.VERSION.SDK_INT>=33)registerReceiver(receiver,filter,"$packageName.permission.INTERNAL",null,Context.RECEIVER_NOT_EXPORTED) else registerReceiver(receiver,filter,"$packageName.permission.INTERNAL",null)
        render()
    }
    override fun onSaveInstanceState(out:Bundle){out.putInt("range",range);out.putBoolean("selecting",selecting);out.putStringArrayList("selected",ArrayList(selected));super.onSaveInstanceState(out)}
    override fun onResume(){super.onResume();resumed=true;SyncRuntime.setForeground(this);SyncRuntime.setObserver(this);worker.execute{repo.publish();SyncRuntime.current()?.requestFileConnection()};render();pendingHandoff?.let{pendingHandoff=null;it()}}
    override fun onPause(){menu?.dismiss();resumed=false;SyncRuntime.clearForeground(this);SyncRuntime.clearObserver(this);super.onPause()}
    override fun onDestroy(){menu?.dismiss();canceled=true;active?.cancel(true);batchDialog?.dialog?.dismiss();detailSheet?.dismiss();main.removeCallbacksAndMessages(null);unregisterReceiver(receiver);worker.shutdownNow();super.onDestroy()}
    override fun onConfigurationChanged(config:Configuration){super.onConfigurationChanged(config);if(!busy)render()}
    override fun dispatchTouchEvent(event:android.view.MotionEvent):Boolean{if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN)EntryGestureRow.closeOutside(event.rawX,event.rawY);return super.dispatchTouchEvent(event)}
    private fun palette(){
        colors=FileUi.palette(this)
        val dark=when(OverlayPreferences(this).themeMode().name){"DARK"->true;"LIGHT"->false;else->resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES}
        page=colors.page;card=colors.surface;text=colors.text;muted=colors.muted;line=colors.border;accent=colors.accent;tint=colors.selected
        window.statusBarColor=page;window.navigationBarColor=page
        window.decorView.systemUiVisibility=if(dark)0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
    private fun shape(fill:Int=card,stroke:Int=line,radius:Int=FileUi.CARD_RADIUS)=FileUi.shape(this,fill,stroke,radius)
    private fun label(value:String,size:Float=14f,color:Int=text)=TextView(this).apply{this.text=value;textSize=size;setTextColor(color);includeFontPadding=false;gravity=Gravity.CENTER_VERTICAL}
    private fun column()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    private fun row()=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    private fun button(value:String,action:()->Unit)=label(value,14f,accent).apply{gravity=Gravity.CENTER;minHeight=dp(FileUi.HIT);setPadding(dp(12),0,dp(12),0);isClickable=true;isFocusable=true;background=FileUi.action(this@FileActivity,colors);setOnClickListener{action()}}
    private fun icon(resource:Int,name:String,action:()->Unit)=ImageView(this).apply{setImageResource(resource);imageTintList=android.content.res.ColorStateList.valueOf(accent);contentDescription=name;setPadding(dp(14),dp(14),dp(14),dp(14));isFocusable=true;background=FileUi.action(this@FileActivity,colors);setOnClickListener{action()}}
    private fun time(value:Long)=if(value<=0)"未知" else DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(value))
    private fun size(value:Long)=FileUi.size(value)
    private fun peer(value:String)=if(value=="local")"本机分享" else SyncRuntime.current()?.peers()?.firstOrNull{it.key==value}?.device?.name?:"已配对设备"
    private fun visible():List<SharedFile>{val cutoff=when(range){1->System.currentTimeMillis()-86400000;2->System.currentTimeMillis()-7*86400000L;3->System.currentTimeMillis()-30*86400000L;else->0};return repo.snapshot().filter{it.shared>=cutoff}}
    private fun picked()=visible().filter{it.key in selected}
    private fun render(){
        if(busy)return;palette();val oldY=if(::scroll.isInitialized)scroll.scrollY else 0
        selected.retainAll(repo.snapshot().map{it.key}.toSet())
        val shell=column().apply{setPadding(dp(16),dp(8),dp(16),dp(12));setBackgroundColor(page)}
        val title=row()
        title.addView(icon(R.drawable.ic_chevron_left,"返回"){finish()},LinearLayout.LayoutParams(dp(48),dp(48)).apply{marginStart=-dp(14)})
        title.addView(label("文件",24f).apply{typeface=Typeface.DEFAULT_BOLD},LinearLayout.LayoutParams(0,dp(52),1f))
        title.addView(icon(R.drawable.ic_add,"添加文件"){pickFiles()},LinearLayout.LayoutParams(dp(48),dp(48)))
        val manageButton=button("管理"){}
        manageButton.setOnClickListener{manage(manageButton)}
        title.addView(manageButton);shell.addView(title)
        val peers=repo.connectedFilePeers()
        val connection=if(peers>0)"$peers 台设备已连接" else "设备未连接"
        val connectionRow=row().apply{background=shape(tint,tint);setPadding(dp(12),dp(4),dp(4),dp(4))}
        connectionRow.addView(ImageView(this).apply{setImageResource(R.drawable.ic_device_computer);imageTintList=android.content.res.ColorStateList.valueOf(if(peers>0)colors.success else muted)},LinearLayout.LayoutParams(dp(20),dp(20)).apply{marginEnd=dp(10)})
        connectionRow.addView(label(connection,12f,muted).apply{maxLines=2},LinearLayout.LayoutParams(0,-2,1f))
        connectionRow.addView(button("设备"){devices()});shell.addView(connectionRow,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10);bottomMargin=dp(4)})
        if(repo.warning.isNotEmpty())shell.addView(label(repo.warning,12f,muted).apply{setPadding(0,0,0,dp(8))})
        val filter=row()
        val timeFilter=button(arrayOf("全部时间⌄","最近24小时⌄","最近7天⌄","最近30天⌄")[range]){}
        timeFilter.setOnClickListener{menu?.dismiss();menu=com.floatclip.app.ui.FloatMenu.show(timeFilter,listOf("全部时间","最近24小时","最近7天","最近30天"),range){range=it;render()}}
        filter.addView(timeFilter)
        filter.addView(View(this),LinearLayout.LayoutParams(0,1,1f));filter.addView(button(if(selecting)"完成" else "选择"){selecting=!selecting;selected.clear();render()});shell.addView(filter)
        if(selecting){val actions=row();actions.addView(button(if(selected.size==visible().size&&selected.isNotEmpty())"取消全选" else "全选"){if(selected.size==visible().size)selected.clear() else selected.addAll(visible().map{it.key});render()});actions.addView(label("已选 ${selected.size}",12f,muted).apply{setPadding(dp(8),0,dp(8),0)});actions.addView(button("下载"){chooseFolder(picked())}.apply{isEnabled=selected.isNotEmpty();alpha=if(isEnabled)1f else .45f});actions.addView(button("分享"){prepare(picked(),"share",null)}.apply{isEnabled=selected.isNotEmpty();alpha=if(isEnabled)1f else .45f});shell.addView(HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;addView(actions)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4)})}
        list=column();val files=visible();if(files.isEmpty())list.addView(label("暂无文件",15f,muted).apply{gravity=Gravity.CENTER;setPadding(dp(8),dp(48),dp(8),dp(32))}) else files.forEach{list.addView(card(it),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})}
        scroll=ScrollView(this).apply{isFillViewport=true;addView(list)};shell.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(shell);scroll.post{scroll.scrollTo(0,oldY)}
    }
    private fun fileIcon(resource:Int,color:Int,size:Int): ImageView = ImageView(this).apply {
        setImageResource(resource)
        imageTintList=android.content.res.ColorStateList.valueOf(color)
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams=LinearLayout.LayoutParams(dp(size),dp(size))
    }

    private fun card(file:SharedFile):View {
        val local=repo.local(file)!=null
        val checked=file.key in selected
        val status=when {
            file.kept -> "已保留"
            local -> "本机已有"
            file.online -> "可下载"
            else -> "来源离线"
        }
        val glyph=if(SharedFileProvider.mime(file.name).startsWith("image/"))R.drawable.ic_image else R.drawable.ic_document
        val front=row().apply {
            background=shape(if(checked)tint else card,if(checked)accent else line)
            minimumHeight=dp(80)
            setPadding(dp(12),dp(12),dp(12),dp(12))
        }
        front.addView(android.widget.FrameLayout(this).apply {
            background=shape(tint,tint,FileUi.CONTROL_RADIUS)
            addView(fileIcon(glyph,accent,22),android.widget.FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER))
        },LinearLayout.LayoutParams(dp(40),dp(40)).apply { marginEnd=dp(12) })
        front.addView(column().apply {
            addView(label(file.name,15f).apply {
                typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END
            })
            addView(label("${size(file.size)} · ${peer(file.peer)}",12f,muted).apply {
                maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
            },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(5) })
        },LinearLayout.LayoutParams(0,-2,1f))
        val endIcon=when {
            selecting -> if(checked)R.drawable.ic_file_downloaded else R.drawable.ic_file_remote
            file.kept -> R.drawable.ic_pin
            else -> R.drawable.ic_chevron_right
        }
        front.addView(fileIcon(endIcon,if(checked||file.kept)accent else muted,20),
            LinearLayout.LayoutParams(dp(20),dp(20)).apply { marginStart=dp(8) })
        val actions=row()
        actions.addView(button("下载"){chooseFolder(listOf(file))},LinearLayout.LayoutParams(dp(64),-1))
        actions.addView(button("分享"){prepare(listOf(file),"share",null)},LinearLayout.LayoutParams(dp(64),-1))
        return EntryGestureRow(this).apply {
            bind(front,if(selecting)null else actions,dp(128))
            isSelected=checked
            contentDescription="${file.name}，${size(file.size)}，$status"
            onSingleTap={
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                if(selecting){if(!selected.add(file.key))selected.remove(file.key);render()}else details(file)
            }
            onLongPress={selecting=true;selected.add(file.key);render()}
        }
    }

    private fun details(file:SharedFile){
        detailSheet?.dismiss()
        val body=column().apply { setPadding(dp(18),dp(2),dp(18),dp(18)) }
        val local=repo.local(file)!=null
        val status=if(local)"本机已有" else if(file.online)"可下载" else "来源离线"
        val properties=column().apply {
            background=shape(page,line)
            setPadding(dp(12),dp(6),dp(12),dp(6))
            listOf("大小" to size(file.size),"来源" to peer(file.peer),"修改时间" to time(file.modified),"状态" to status).forEach { (name,value) ->
                addView(row().apply {
                    minimumHeight=dp(29)
                    addView(label(name,13f,muted),LinearLayout.LayoutParams(dp(68),-2))
                    addView(label(value,13f),LinearLayout.LayoutParams(0,-2,1f))
                })
            }
        }
        body.addView(properties,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(10)})
        lateinit var sheet:FileDetailsSheet
        fun action(name:String,icon:Int,run:()->Unit){
            body.addView(row().apply {
                minimumHeight=dp(FileUi.HIT)
                setPadding(dp(8),0,dp(8),0)
                background=FileUi.action(this@FileActivity,colors)
                isFocusable=true
                addView(fileIcon(icon,accent,20),LinearLayout.LayoutParams(dp(20),dp(20)).apply { marginEnd=dp(12) })
                addView(label(name,14f),LinearLayout.LayoutParams(0,-2,1f))
                setOnClickListener{sheet.close(run)}
            },LinearLayout.LayoutParams(-1,-2))
        }
        action("应用内预览",R.drawable.ic_document){prepare(listOf(file),"open",null)}
        action("下载到文件夹",R.drawable.ic_update){chooseFolder(listOf(file))}
        action("分享 / 转发",R.drawable.ic_share){prepare(listOf(file),"share",null)}
        if(SharedFileProvider.mime(file.name).startsWith("image/")&&local)action("加入剪贴板",R.drawable.ic_copy_paste){
            startActivity(Intent(this,ImageImportActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/*")
                .putExtra(Intent.EXTRA_STREAM,SharedFileProvider.uri(packageName,file.key)))
        }
        action(if(file.kept)"取消保留" else "保留此文件",if(file.kept)R.drawable.ic_pin_filled else R.drawable.ic_pin){repo.keep(file,!file.kept);render()}
        sheet=FileDetailsSheet(this,file.name,body,colors)
        sheet.setOnDismissListener { if(detailSheet===sheet)detailSheet=null }
        detailSheet=sheet
        sheet.show()
    }
    private fun devices(){startActivity(Intent(this,DeviceActivity::class.java).putExtra("devicesOnly",true))}
    private fun pickFiles(){if(busy)return;startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="*/*";addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)},100)}
    private fun chooseFolder(files:List<SharedFile>){if(files.isEmpty()){message("请先选择文件");return};if(busy)return;treeSelection=files;startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),101)}
    @Deprecated("Native Activity results") override fun onActivityResult(request:Int,result:Int,data:Intent?){super.onActivityResult(request,result,data);if(result!=RESULT_OK)return
        if(request==100){val uris=if(data?.clipData!=null)(0 until data.clipData!!.itemCount).map{data.clipData!!.getItemAt(it).uri} else listOfNotNull(data?.data);worker.execute{var count=0;var error="";for(uri in uris)try{repo.import(uri);count++}catch(e:Exception){error=e.message?:"无法读取文件";break};main.post{message(if(error.isEmpty())"已添加 $count 个文件" else "已添加 $count 个文件：$error");render()}}}
        if(request==101){val uri=data?.data?:return;runCatching{
            val grants=data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            when(grants){
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION->contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                Intent.FLAG_GRANT_READ_URI_PERMISSION->contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION->contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        };prepare(treeSelection,"folder",uri);treeSelection=emptyList()}
    }
    private fun manage(anchor:View){menu?.dismiss();menu=com.floatclip.app.ui.FloatMenu.show(anchor,listOf("设备与配对","清理当前时间范围内的下载缓存","清除当前时间范围内的文件记录")){when(it){0->devices();1->clear(false);2->clear(true)}}}
    private fun clear(records:Boolean){val files=if(selecting)picked() else visible();val eligible=files.filter{!it.kept};FloatDialog.show(this,if(records)"清除文件记录" else "清理下载缓存","将处理 ${eligible.size} 个文件，跳过 ${files.size-eligible.size} 个保留文件。"+if(records)"本机分享的应用内副本也会移除，原始文件不受影响。" else "保留文件清单，之后可以重新下载。",onPositive={repo.clear(files,records,true);selected.clear();render();true})}
    private fun prepare(files:List<SharedFile>,mode:String,tree:Uri?){
        if(busy||files.isEmpty()){if(files.isEmpty())message("请先选择文件");return};busy=true;canceled=false
        val progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=1000;progressTintList=android.content.res.ColorStateList.valueOf(accent)};val info=label("正在准备…",13f,muted);val body=column().apply{addView(info);addView(progress,LinearLayout.LayoutParams(-1,dp(20)))}
        fun cancel(){canceled=true;active?.cancel(true)}
        batchDialog=FloatDialog.show(this,if(mode=="share")"准备分享" else "准备文件",custom=body,negative="取消",positive=null,onNegative={cancel()},onCancel={cancel()})
        val total=files.sumOf{it.size}.coerceAtLeast(1);val started=System.currentTimeMillis();var completed=0L;var index=0
        val tick=object:Runnable{override fun run(){if(!busy)return;val row=files.getOrNull(index);val bytes=(completed+(row?.done?:0)).coerceAtMost(total);val elapsed=(System.currentTimeMillis()-started)/1000.0;val remaining=if(bytes>0&&elapsed>1)((total-bytes)*elapsed/bytes).toLong() else -1;info.text="${(index+1).coerceAtMost(files.size)} / ${files.size} · ${row?.name.orEmpty()}\n${size(bytes)} / ${size(total)} · "+if(remaining>=0)"预计剩余 ${remaining/60}分${remaining%60}秒" else "正在计算剩余时间";progress.progress=(bytes*1000/total).toInt();main.postDelayed(this,250)}};main.post(tick)
        worker.execute{try{
            files.forEachIndexed{i,entry->index=i;if(canceled)throw IOException("操作已取消");val current=repo.byKey(entry.key)?:throw IOException("文件记录已移除");val future=repo.download(current);active=future;val file=future.get();active=null;if(canceled)throw IOException("操作已取消");if(mode=="folder")saveToTree(tree!!,current,file);completed+=entry.size;index=i+1}
            main.post{if(isDestroyed)return@post;finishBatch();val handoff={if(mode=="folder")message("已保存 ${files.size} 个文件到所选文件夹") else launchFiles(files,mode)};if(resumed)handoff() else pendingHandoff=handoff}
        }catch(e:Exception){main.post{if(isDestroyed)return@post;finishBatch();if(!canceled)FloatDialog.show(this,"文件准备未完成",(e.cause?.message?:e.message?:"连接已中断")+"\n已完成文件保留；不会自动转发部分文件。",positive="重试",onPositive={prepare(if(mode=="folder")files.drop(index) else files,mode,tree);true}) else message("操作已取消，未完成的传输可重连后重试")}}}
    }
    private fun finishBatch(){busy=false;active=null;batchDialog?.dialog?.dismiss();batchDialog=null;render()}
    private fun saveToTree(tree:Uri,row:SharedFile,file:File){val parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));val target=DocumentsContract.createDocument(contentResolver,parent,SharedFileProvider.mime(row.name),row.name)?:throw IOException("文件夹无法写入");try{contentResolver.openOutputStream(target,"w")?.use{out->file.inputStream().use{it.copyTo(out)}}?:throw IOException("无法写入所选文件夹")}catch(e:Exception){runCatching{DocumentsContract.deleteDocument(contentResolver,target)};throw e}}
    private fun launchFiles(files:List<SharedFile>,mode:String){
        if(mode=="open") { startActivity(Intent(this,FilePreviewActivity::class.java).putExtra("key",files.first().key)); return }
        val uris=files.map{SharedFileProvider.uri(packageName,it.key)};val intent=Intent(if(files.size==1)Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply{type=files.map{SharedFileProvider.mime(it.name)}.distinct().singleOrNull()?:"*/*";if(files.size==1)putExtra(Intent.EXTRA_STREAM,uris.first()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM,ArrayList(uris))}
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.clipData=ClipData.newUri(contentResolver,files.first().name,uris.first()).apply{uris.drop(1).forEach{addItem(ClipData.Item(it))}}
        runCatching{startActivity(Intent.createChooser(intent,"分享文件"))}.onFailure{message("没有可处理此文件的应用，可先下载到文件夹")}
    }
    private fun message(value:String){Toast.makeText(this,value,Toast.LENGTH_LONG).show()}
    override fun changed(service:ClipboardSyncService){main.removeCallbacks(repaint);main.postDelayed(repaint,180)}
    override fun verify(code:String,answer:Consumer<Boolean>){FloatDialog.show(this,"核对配对码",code,onNegative={answer.accept(false)},onCancel={answer.accept(false)},onPositive={answer.accept(true);true})}
    override fun offer(peer:String,offer:ShareWire.Offer){FloatDialog.show(this,"接收文件？","${offer.name}\n${size(offer.size)}",onNegative={SyncRuntime.current()?.session(peer)?.reject(offer.id)},onCancel={SyncRuntime.current()?.session(peer)?.reject(offer.id)},onPositive={SyncRuntime.current()?.session(peer)?.accept(offer.id);true})}
    override fun progress(name:String,done:Long,total:Long,outgoing:Boolean)=Unit
    override fun file(offer:ShareWire.Offer,file:File,outgoing:Boolean){if(!outgoing)message("${offer.name} 已接收并校验")}
}
