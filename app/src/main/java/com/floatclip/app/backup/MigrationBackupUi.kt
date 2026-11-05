package com.floatclip.app.backup

import android.app.Activity
import android.content.Intent
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.floatclip.app.clipboard.ClipStore
import com.floatclip.app.security.PortableVaultCrypto
import com.floatclip.app.ui.FloatDialog
import org.json.JSONArray

/** Manual, portable migration. PIN remains in memory only while a document picker is open. */
class MigrationBackupUi(private val owner:Activity,private val store:ClipStore,private val changed:()->Unit) {
    private var pendingPin:String?=null
    fun export() {
        val first=pinField("设置 6 位迁移 PIN")
        val confirm=pinField("再次输入 PIN")
        val box=LinearLayout(owner).apply { orientation=LinearLayout.VERTICAL;addView(first);addView(confirm) }
        FloatDialog.show(owner,"导出迁移包","包含文字、图片、分类及固定/置顶记录。请记住 PIN，在新设备导入时使用。",custom=box,negative="取消",positive="选择保存位置") {
            val pin=first.text.toString()
            if(!pin.matches(Regex("\\d{6}")) || pin!=confirm.text.toString()) {
                confirm.error="请输入两次相同的 6 位数字";false
            } else {
                pendingPin=pin
                owner.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type="application/octet-stream";addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_TITLE,"FloatClip-"+java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.ROOT).format(java.util.Date())+".vault")
                },EXPORT)
                true
            }
        }
    }
    fun importBackup() {
        owner.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type="*/*";addCategory(Intent.CATEGORY_OPENABLE)
        },IMPORT)
    }
    fun onResult(request:Int,result:Int,data:Intent?):Boolean {
        if(request!=EXPORT && request!=IMPORT)return false
        val uri=data?.data
        if(result!=Activity.RESULT_OK || uri==null) { pendingPin=null;return true }
        if(request==EXPORT) {
            val pin=pendingPin;pendingPin=null
            if(pin==null) { toast("请重新开始导出");return true }
            toast("正在生成迁移包…")
            Thread {
                val result=runCatching {
                    val envelope=PortableVaultCrypto.encrypt(store.exportJson(),pin)
                    owner.contentResolver.openOutputStream(uri,"wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(envelope) }
                        ?: error("无法写入文件")
                }
                owner.runOnUiThread { if(!owner.isFinishing && !owner.isDestroyed)toast(if(result.isSuccess)"迁移包已保存" else "导出未完成，请检查保存位置") }
            }.apply { name="FloatClip migration export";start() }
        } else {
            val pin=pinField("输入迁移包的 6 位 PIN")
            FloatDialog.show(owner,"导入迁移包","先验证文件，确认后合并到本机历史。设备授权需要重新配对。",custom=pin,negative="取消",positive="验证") { handle ->
                val value=pin.text.toString()
                if(!value.matches(Regex("\\d{6}"))) {pin.error="请输入 6 位数字";false} else {
                    handle.primary?.isEnabled=false
                    handle.primary?.text="正在验证…"
                    Thread {
                        val decoded=runCatching {
                            val input=owner.contentResolver.openInputStream(uri) ?: error("无法读取")
                            val envelope=input.bufferedReader(Charsets.UTF_8).use { reader ->
                                val text=StringBuilder();val buffer=CharArray(8192)
                                while(true) {
                                    val count=reader.read(buffer);if(count<0)break
                                    require(text.length.toLong()+count<=96L*1024*1024) { "迁移包过大" }
                                    text.append(buffer,0,count)
                                };text.toString()
                            }
                            PortableVaultCrypto.decrypt(envelope,value).also { JSONArray(it) }
                        }
                        owner.runOnUiThread {
                            if(!owner.isFinishing && !owner.isDestroyed) {
                                handle.primary?.isEnabled=true
                                handle.primary?.text="验证"
                                decoded.fold({ raw -> handle.dialog.dismiss();confirmImport(raw) },{
                                    pin.error="PIN 不正确，或迁移包不可用"
                                })
                            }
                        }
                    }.apply {name="FloatClip migration verify";start()}
                    false
                }
            }
        }
        return true
    }
    private fun confirmImport(raw:String) {
        val rows=JSONArray(raw)
        val images=(0 until rows.length()).count { rows.getJSONObject(it).optString("kind")=="image" }
        FloatDialog.show(owner,"合并导入","共 "+rows.length()+" 条记录，包含 "+images+" 张图片。本机已有记录保留；相同编号保留本机版本。",negative="取消",positive="合并导入") { handle ->
            handle.primary?.isEnabled=false
            handle.primary?.text="正在导入…"
            Thread {
                val ok=store.mergeImportJson(raw)
                owner.runOnUiThread {
                    if(!owner.isFinishing && !owner.isDestroyed) {
                        if(ok) {handle.dialog.dismiss();toast("迁移完成");changed()}
                        else {handle.primary?.isEnabled=true;handle.primary?.text="合并导入";toast("导入未完成：文件损坏或本机空间不足，原记录已保留")}
                    }
                }
            }.apply {name="FloatClip migration merge";start()}
            false
        }
    }
    private fun pinField(hintText:String)=EditText(owner).apply {
        hint=hintText;isSingleLine=true
        inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters=arrayOf(InputFilter.LengthFilter(6))
        val palette=com.floatclip.app.ui.FileUi.palette(owner)
        setTextColor(palette.text);setHintTextColor(palette.muted)
        background=com.floatclip.app.ui.FileUi.shape(owner,palette.surface,palette.border)
        val padding=(12*resources.displayMetrics.density).toInt()
        setPadding(padding,padding,padding,padding)
    }
    private fun toast(text:String)=Toast.makeText(owner,text,Toast.LENGTH_LONG).show()
    companion object { const val EXPORT=6241;const val IMPORT=6242 }
}
