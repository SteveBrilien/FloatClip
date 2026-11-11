package com.floatclip.app.sync

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.floatclip.app.ui.FloatDialog

/** Entered by the active app, or by a user tapping the background transfer notification. */
class WifiDirectConsentActivity:Activity() {
    private val peer get()=intent.getStringExtra("peer").orEmpty()
    private val request get()=intent.getStringExtra("request").orEmpty()
    private var proceeding=false
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(savedInstanceState==null)advance()
    }
    private fun advance() {
        if(SyncRuntime.current()==null){finish();return}
        if(!WifiDirectTransferClient.wifiOn(this)) {
            FloatDialog.show(this,"打开 Wi-Fi","开启本机 Wi-Fi 后即可继续直连。",negative="暂不",positive="去开启",
                onNegative=::finish,onCancel=::finish,onPositive={
                    runCatching{startActivityForResult(Intent(if(Build.VERSION.SDK_INT>=29)Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS),1)}.onFailure{finish()};true
                })
        } else if(!WifiDirectTransferClient.hasPermission(this)) {
            FloatDialog.show(this,"授权发现直连网络","Android 需要位置权限来选择对方的 Wi-Fi 直连网络。FloatClip 不读取定位结果。",
                negative="暂不",positive="继续",onNegative=::finish,onCancel=::finish,onPositive={
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION),2);true
                })
        } else {
            val transfer=SyncRuntime.current()?.continueDirectTransfer(peer,request)?:run{finish();return}
            val waiting=FloatDialog.show(this,"连接 Wi-Fi 直连","请在系统弹窗确认连接。",negative="取消",positive=null,
                onNegative=::finish,onCancel=::finish)
            transfer.whenComplete { success,_ -> runOnUiThread {
                if(!isFinishing) {
                    proceeding=success==true
                    waiting.dialog.dismiss()
                    finish()
                }
            } }
        }
    }
    @Deprecated("Native Wi-Fi panel result")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==1){if(WifiDirectTransferClient.wifiOn(this))advance() else finish()}
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==2){if(WifiDirectTransferClient.hasPermission(this))advance() else finish()}
    }
    override fun finish() {
        if(!proceeding)SyncRuntime.current()?.declineDirectTransfer(peer,request)
        super.finish()
    }
}
