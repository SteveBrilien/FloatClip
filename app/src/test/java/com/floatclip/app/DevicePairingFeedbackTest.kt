package com.floatclip.app

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.floatclip.app.sync.ClipboardSyncService
import com.floatclip.app.sync.SyncPreferences
import com.floatclip.app.sync.SyncRuntime
import com.floatclip.probe.ShareActivity
import com.floatclip.share.ShareSession
import com.floatclip.share.ShareWire
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.io.*
import java.util.UUID
import java.util.function.Consumer

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class DevicePairingFeedbackTest {
    private fun texts(view:View):List<TextView> = (if(view is TextView)listOf(view) else emptyList()) +
        (if(view is ViewGroup)(0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList())

    @Test fun connectedPageKeepsAddDeviceAndPausePreservesMasterAndContent() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("floatclip_sync",Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("share-pairs",Context.MODE_PRIVATE).edit().clear().commit()
        SyncRuntime.current()?.let { SyncRuntime.detach(it) }
        val controller=Robolectric.buildActivity(DeviceActivity::class.java,Intent(context,DeviceActivity::class.java).putExtra("devicesOnly",true))
        val callbacks=object:ShareSession.Events {
            override fun text(id:UUID,value:String,outgoing:Boolean)=Unit
            override fun offered(offer:ShareWire.Offer)=Unit
            override fun progress(id:UUID,name:String,done:Long,total:Long,outgoing:Boolean)=Unit
            override fun file(offer:ShareWire.Offer,file:File,outgoing:Boolean)=Unit
            override fun notice(message:String)=Unit
            override fun closed()=Unit
        }
        val session=ShareSession(ByteArrayInputStream(byteArrayOf()),ByteArrayOutputStream(),Closeable {},File(context.cacheDir,"pair-ui"),callbacks,true)
        try {
            val activity=controller.create().start().resume().get()
            SyncPreferences(context).save(enabled=true)
            ShareActivity::class.java.getDeclaredMethod("managedState",ShareSession::class.java,String::class.java,Boolean::class.javaPrimitiveType).apply { isAccessible=true }
                .invoke(activity,session,"1 台设备已连接",false)
            fun labels()=texts(activity.findViewById(android.R.id.content))
            assertTrue("a live session must not hide adding another device",labels().any { it.text.toString()=="添加设备" })
            labels().first { it.text.toString()=="暂停共享" }.performClick()
            assertTrue(SyncPreferences(context).enabled())
            assertTrue(SyncPreferences(context).connectionsPaused())
            assertTrue("pause must leave an operable device page",labels().any { it.text.toString()=="添加设备" })
            assertTrue(labels().any { it.text.toString()=="设备授权" })
        } finally { session.close();controller.pause().stop().destroy() }
    }

    @Test fun workerStateChangeIsDeliveredOnMainAndDetachedServiceIsIgnored() {
        val service=Robolectric.buildService(ClipboardSyncService::class.java).get()
        var calls=0
        val observer=object:SyncRuntime.Observer {
            override fun changed(service:ClipboardSyncService){assertSame(Looper.getMainLooper(),Looper.myLooper());calls++}
            override fun verify(code:String,answer:Consumer<Boolean>)=Unit
            override fun offer(peer:String,offer:ShareWire.Offer)=Unit
            override fun progress(name:String,done:Long,total:Long,outgoing:Boolean)=Unit
            override fun file(offer:ShareWire.Offer,file:File,outgoing:Boolean)=Unit
        }
        try {
            SyncRuntime.attach(service);SyncRuntime.setObserver(observer);calls=0
            Thread { SyncRuntime.changed() }.apply { start();join() }
            assertEquals(0,calls)
            shadowOf(Looper.getMainLooper()).idle();assertEquals(1,calls)
            Thread { SyncRuntime.changed() }.apply { start();join() }
            SyncRuntime.detach(service);shadowOf(Looper.getMainLooper()).idle();assertEquals(1,calls)
        } finally {
            SyncRuntime.detach(service);SyncRuntime.clearObserver(observer)
            val field=service.javaClass.getDeclaredField("actor").apply { isAccessible=true }
            (field.get(service) as java.util.concurrent.ExecutorService).shutdownNow()
        }
    }
}
