package com.floatclip.app

import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.floatclip.app.files.*
import com.floatclip.share.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
@ConscryptMode(ConscryptMode.Mode.OFF)
class FileShelfTest {
    private fun context():Context {val base=RuntimeEnvironment.getApplication() as Context;val root=File(base.cacheDir,"file-test-${UUID.randomUUID()}").apply{mkdirs()};return object:ContextWrapper(base){override fun getFilesDir()=File(root,"files").apply{mkdirs()};override fun getCacheDir()=File(root,"cache").apply{mkdirs()}}}
    private fun events(repo:FileRepository,peer:String)=object:ShareSession.Events{
        override fun text(id:UUID,text:String,outgoing:Boolean)=Unit
        override fun offered(offer:ShareWire.Offer){check(repo.offered(peer,offer))}
        override fun progress(id:UUID,name:String,done:Long,total:Long,outgoing:Boolean){repo.progress(peer,id,done,total,outgoing)}
        override fun file(offer:ShareWire.Offer,local:File,outgoing:Boolean){repo.received(peer,offer,local,outgoing)}
        override fun notice(message:String)=Unit
        override fun closed()=Unit
        override fun catalog(data:ByteArray){repo.catalog(peer,data)}
        override fun fileFailed(message:String){repo.failed(peer,message)}
        override fun sync(frame:ShareWire.Frame)=Unit
    }
    private fun waitFor(check:()->Boolean){val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!check()){if(System.nanoTime()>until)fail("timed out waiting for file catalog");Thread.sleep(10)}}
    @Test fun catalogPullRequiresSelectionVerifiesBytesAndPreservesProtectedFiles(){
        val aContext=context();val bContext=context();val id="1".repeat(32);val bytes=ByteArray(95000){(it*13).toByte()};val file=File(aContext.filesDir,"shared-files/sources/$id").apply{parentFile!!.mkdirs();writeBytes(bytes)}
        val metadata=JSONObject().put("peer","local").put("id",id).put("version","a".repeat(64)).put("name","合同 😀.pdf").put("size",bytes.size).put("modified",1).put("created",0).put("shared",System.currentTimeMillis()).put("source",file.path)
        File(aContext.filesDir,"shared-files/catalog.json").writeText(JSONArray().put(metadata).toString())
        val a=FileRepository(aContext);val b=FileRepository(bContext);val listener=ServerSocket(0);val outgoing=Socket("127.0.0.1",listener.localPort);val incoming=listener.accept();listener.close()
        val aSession=ShareSession(outgoing,File(aContext.cacheDir,"sync-files"),events(a,"phone"),true,true);val bSession=ShareSession(incoming,File(bContext.cacheDir,"sync-files"),events(b,"windows"),true,true)
        try{
            a.attach("phone",aSession){true};b.attach("windows",bSession){true};aSession.start();bSession.start();waitFor{b.snapshot().size==1};val row=b.snapshot().single();assertEquals("合同 😀.pdf",row.name);assertNull(b.local(row));assertEquals(0,File(bContext.cacheDir,"sync-files").listFiles()!!.size)
            assertEquals(1,a.connectedFilePeers());assertEquals(1,b.connectedFilePeers())
            val received=b.download(row).get(8,TimeUnit.SECONDS);assertArrayEquals(bytes,received.readBytes());assertArrayEquals(bytes,file.readBytes());b.keep(row,true);b.clear(listOf(row),true,true);assertTrue(received.exists());assertEquals(1,b.snapshot().size)
            FileRepository(bContext).use{restored->assertTrue(restored.snapshot().single().kept);assertNotNull(restored.local(restored.snapshot().single()));assertFalse(restored.snapshot().single().online)}
            b.keep(row,false);b.clear(listOf(row),false,true);assertFalse(received.exists());assertEquals(1,b.snapshot().size)
            row.online=false
            val reconnecting=b.download(row)
            assertFalse(reconnecting.isDone)
            a.publish()
            assertArrayEquals(bytes,reconnecting.get(8,TimeUnit.SECONDS).readBytes())
            b.clear(listOf(row),true,true);assertTrue(b.snapshot().isEmpty());assertTrue(file.exists())
        }finally{aSession.close();bSession.close();a.close();b.close()}
    }
    @Test fun oldSessionCannotSendCatalogAndPrivatePathsNeverEscape(){
        val ctx=context();val session=ShareSession(java.io.ByteArrayInputStream(byteArrayOf()),java.io.ByteArrayOutputStream(),java.io.Closeable{},File(ctx.cacheDir,"wire"),events(FileRepository(ctx),"old"),true)
        try{assertFalse(session.catalogEnabled);assertTrue(runCatching{session.sendCatalog("{}".toByteArray())}.isFailure);FileRepository(ctx).use{repo->repo.attach("old",session){true};assertEquals(0,repo.connectedFilePeers())}}finally{session.close()}
        FileRepository(ctx).use{repo->val external=File(ctx.filesDir,"unrelated.txt").apply{writeText("keep")};val row=SharedFile("local","1".repeat(32),"a".repeat(64),"x.txt",4,0,0,0,source=external.path,cached=external.path);assertNull(repo.local(row));repo.clear(listOf(row),true,true);assertEquals("keep",external.readText())}
    }
    private fun descendants(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{descendants(view.getChildAt(it))} else emptyList()
    @Test fun fullProtectedCatalogDoesNotCloseTheClipboardSession(){
        val ctx=context();val saved=JSONArray()
        repeat(512){i->saved.put(JSONObject().put("peer","archived").put("id",i.toString(16).padStart(32,'0')).put("version","a".repeat(64)).put("name","保留-$i.pdf").put("size",0).put("modified",0).put("created",0).put("shared",1).put("kept",true))}
        File(ctx.filesDir,"shared-files/catalog.json").apply{parentFile!!.mkdirs();writeText(saved.toString())}
        FileRepository(ctx).use{repo->
            var closed=false;val output=java.io.ByteArrayOutputStream()
            val session=ShareSession(java.io.ByteArrayInputStream(byteArrayOf()),output,java.io.Closeable{closed=true},File(ctx.cacheDir,"sync-files"),events(repo,"windows"),true,true)
            try{
                repo.attach("windows",session){true}
                val entry=JSONObject().put("id","f".repeat(32)).put("version","b".repeat(64)).put("name","新的文件.pdf").put("size",0).put("modified",0).put("created",0).put("shared",2)
                repo.catalog("windows",JSONObject().put("v",1).put("op","list").put("files",JSONArray().put(entry)).toString().toByteArray())
                assertEquals(512,repo.snapshot().size);assertTrue(repo.snapshot().all{it.kept});assertTrue(repo.warning.isNotEmpty());assertFalse(closed)
                val before=output.size();session.sendText("容量已满后仍可同步 😀");assertTrue(output.size()>before);assertFalse(closed)
                FileRepository(ctx).use{restored->assertEquals(512,restored.snapshot().size);assertTrue(restored.snapshot().all{it.kept})}
            }finally{session.close()}
        }
    }
    @Test fun nativeCardUsesTwelveDpCornersAndAlignsStatusToFilenameFirstLine(){
        val controller=Robolectric.buildActivity(FileActivity::class.java).create().start().resume();val activity=controller.get();val density=activity.resources.displayMetrics.density
        try{val file=SharedFile("test","1".repeat(32),"a".repeat(64),"会议纪要与下一步安排.docx",62000,0,0,1,online=true);val method=FileActivity::class.java.getDeclaredMethod("card",SharedFile::class.java).apply{isAccessible=true};val view=method.invoke(activity,file) as ViewGroup
            view.measure(View.MeasureSpec.makeMeasureSpec((320*density).toInt(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));view.layout(0,0,view.measuredWidth,view.measuredHeight)
            val front=view.getChildAt(view.childCount-1);assertEquals(12*density,(front.background as GradientDrawable).cornerRadius,1f)
            val status=descendants(front).filterIsInstance<ImageView>().single();val name=descendants(front).filterIsInstance<TextView>().single{it.text.toString()==file.name}
            val s=android.graphics.Rect(0,0,status.width,status.height);val n=android.graphics.Rect(0,0,name.width,name.height);view.offsetDescendantRectToMyCoords(status,s);view.offsetDescendantRectToMyCoords(name,n)
            assertTrue("status center=${s.exactCenterY()}, filename first line=${n.top+name.lineHeight/2f}, density=$density",kotlin.math.abs(s.exactCenterY()-(n.top+name.lineHeight/2f))<=4*density);assertTrue("card exceeds 320dp",view.measuredWidth<=320*density+1)
        }finally{controller.pause().stop().destroy()}
    }
}
