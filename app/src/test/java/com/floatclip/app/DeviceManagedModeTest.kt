package com.floatclip.app

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.floatclip.app.sync.SyncPreferences
import com.floatclip.app.sync.ClipboardSyncService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DeviceManagedModeTest {
    private fun texts(view: View): List<String> {
        val here=(view as? TextView)?.text?.toString()?.let(::listOf).orEmpty()
        if(view !is ViewGroup)return here
        return here + (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
    }

    @Test fun disabledMasterStillUsesManagedDeviceUi() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("floatclip_sync",Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("share-pairs",Context.MODE_PRIVATE).edit().clear().commit()
        assertFalse(SyncPreferences(context).enabled())

        val controller=Robolectric.buildActivity(DeviceActivity::class.java)
        try {
            val activity=controller.create().start().resume().get()
            val navId=activity.resources.getIdentifier("nav_devices","id",activity.packageName)
            assertTrue("device navigation id must exist",navId!=0)
            activity.findViewById<View>(navId).performClick()
            val labels=texts(activity.findViewById(android.R.id.content))
            assertTrue("managed device page must offer Add device",labels.any { it=="添加设备" })
            assertTrue("managed device page must explain Bluetooth-first discovery",labels.any { it.contains("蓝牙可直接添加设备") })
            assertTrue("managed device page must explain automatic reconnect",labels.any { it.contains("已授权设备会通过蓝牙或局域网自动重连") })
            assertFalse("master off must never fall back to the legacy foreground Share flow",labels.any { it=="允许附近设备连接" })
            assertFalse("opening the device page must not silently enable sharing",SyncPreferences(activity).enabled())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun enabledManagedAddDeviceDoesNotRequireLanAddress() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("floatclip_sync",Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("share-pairs",Context.MODE_PRIVATE).edit().clear().commit()
        SyncPreferences(context).save(enabled=true)

        val controller=Robolectric.buildActivity(DeviceActivity::class.java)
        try {
            val activity=controller.create().start().resume().get()
            val navId=activity.resources.getIdentifier("nav_devices","id",activity.packageName)
            activity.findViewById<View>(navId).performClick()
            val root=activity.findViewById<View>(android.R.id.content)
            fun find(view: View): TextView? {
                if(view is TextView && view.text?.toString()=="添加设备")return view
                if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let{return it}
                return null
            }
            val add=find(root)
            assertTrue("managed Add device button must exist",add!=null)
            val shadow=org.robolectric.Shadows.shadowOf(context as android.app.Application)
            while(shadow.nextStartedService!=null) { /* drain service start caused by onResume refresh */ }
            add!!.performClick()
            val started=shadow.nextStartedService
            assertTrue("managed pairing must start ClipboardSyncService even with no LAN address",
                started?.component?.className==ClipboardSyncService::class.java.name && started.action==ClipboardSyncService.PAIR)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
