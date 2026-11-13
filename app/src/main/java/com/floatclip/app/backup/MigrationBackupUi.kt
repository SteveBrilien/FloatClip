package com.floatclip.app.backup

import android.app.Activity
import android.content.Intent
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.Gravity
import android.graphics.Typeface
import com.floatclip.app.clipboard.ClipStore
import com.floatclip.app.security.PortableVaultCrypto
import com.floatclip.app.ui.FloatDialog
import org.json.JSONArray

/** Manual, portable migration. PIN remains in memory only while a document picker is open. */
class MigrationBackupUi(private val owner:Activity,private val store:ClipStore,private val changed:()->Unit) {
    private var pendingPin:String?=null
    private data class Preview(val raw:String,val records:Int,val images:Int)
    fun export() {
        val first=pinField("设置 6 位迁移 PIN")
        val confirm=pinField("再次输入 PIN")
        val box=LinearLayout(owner).apply {
            orientation=LinearLayout.VERTICAL
            addView(pinSection("迁移密码",first))
            addView(pinSection("确认密码",confirm),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
            addView(hint("迁移密码仅用于加密当前导出的文件，不会同步到其他设备。"),
                LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(13)})
        }
        FloatDialog.show(owner,"导出迁移包","加密备份将包含文字、图片、分类及受保护记录。请妥善保管迁移密码。",custom=box,negative="取消",positive="选择保存位置") {
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
            val form=LinearLayout(owner).apply {
                orientation=LinearLayout.VERTICAL
                addView(pinSection("迁移密码",pin))
                addView(hint("验证成功后会展示记录数量，再由你确认是否合并；不会覆盖本机已有记录。"),
                    LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
            }
            FloatDialog.show(owner,"验证迁移文件","导入前先检查文件完整性。设备授权信息不会随备份迁移。",custom=form,negative="取消",positive="验证") { handle ->
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
                            val raw=PortableVaultCrypto.decrypt(envelope,value)
                            val rows=JSONArray(raw)
                            Preview(raw,rows.length(),(0 until rows.length()).count { rows.getJSONObject(it).optString("kind")=="image" })
                        }
                        owner.runOnUiThread {
                            if(!owner.isFinishing && !owner.isDestroyed) {
                                handle.primary?.isEnabled=true
                                handle.primary?.text="验证"
                                decoded.fold({ preview -> handle.dialog.dismiss();confirmImport(preview) },{
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
    private fun confirmImport(preview:Preview) {
        FloatDialog.show(owner,"合并导入","共 "+preview.records+" 条记录，包含 "+preview.images+" 张图片。本机已有记录保留；相同编号保留本机版本。",negative="取消",positive="合并导入") { handle ->
            handle.primary?.isEnabled=false
            handle.primary?.text="正在导入…"
            Thread {
                val ok=store.mergeImportJson(preview.raw)
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
    private fun dp(value:Int)=(value*owner.resources.displayMetrics.density+.5f).toInt()
    private fun hint(value:String)=TextView(owner).apply {
        text=value;textSize=12f
        setTextColor(com.floatclip.app.ui.FileUi.palette(owner).muted)
        setLineSpacing(dp(3).toFloat(),1.04f)
        includeFontPadding=false
    }
    private fun pinSection(title:String,field:EditText)=LinearLayout(owner).apply {
        orientation=LinearLayout.VERTICAL
        addView(TextView(owner).apply {
            text=title;textSize=14f
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            setTextColor(com.floatclip.app.ui.FileUi.palette(owner).text)
            includeFontPadding=false
        },LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
        addView(field,LinearLayout.LayoutParams(-1,dp(52)))
    }
    private fun pinField(hintText:String)=EditText(owner).apply {
        hint=hintText;isSingleLine=true
        inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters=arrayOf(InputFilter.LengthFilter(6))
        val palette=com.floatclip.app.ui.FileUi.palette(owner)
        setTextColor(palette.text);setHintTextColor(palette.muted)
        textSize=17f;letterSpacing=.20f;gravity=Gravity.CENTER_VERTICAL
        setSelectAllOnFocus(false)
        minHeight=dp(52)
        contentDescription=hintText
        background=com.floatclip.app.ui.FileUi.shape(owner,palette.surface,palette.border,12)
        setPadding(dp(16),0,dp(14),0)
    }
    private fun toast(text:String)=Toast.makeText(owner,text,Toast.LENGTH_LONG).show()
    companion object { const val EXPORT=6241;const val IMPORT=6242 }
}
