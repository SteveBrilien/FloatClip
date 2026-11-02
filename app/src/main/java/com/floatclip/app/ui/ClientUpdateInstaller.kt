package com.floatclip.app.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.floatclip.app.update.UpdateApkProvider
import com.floatclip.probe.ShareUpdates
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Download, verify and hand an authenticated FloatClip APK to the Android package installer. */
internal class ClientUpdateInstaller(
    private val activity:Activity,
    private val worker:ExecutorService=Executors.newSingleThreadExecutor { task ->
        Thread(task,"FloatClip update download").apply { isDaemon=true }
    },
    private val main:Handler=Handler(Looper.getMainLooper()),
):AutoCloseable {
    enum class Phase { IDLE,DOWNLOADING,READY,FAILED }
    data class State(val phase:Phase=Phase.IDLE,val percent:Int=0,val message:String="")
    enum class InstallResult { STARTED,PERMISSION_REQUESTED,PERMISSION_REQUIRED,MISSING,FAILED }

    private val prefs=activity.getSharedPreferences("floatclip_update_installer",Activity.MODE_PRIVATE)
    private val directory=File(activity.filesDir,"updates")
    private val apk=File(directory,"FloatClip-update.apk")
    private val partial=File(directory,"FloatClip-update.apk.part")
    @Volatile private var cancelled=false
    private var future:Future<*>?=null

    fun start(release:ShareUpdates.Release,listener:(State)->Unit) {
        cancel();cancelled=false
        if(!directory.exists()&&!directory.mkdirs()) { listener(State(Phase.FAILED,message="无法创建更新缓存"));return }
        partial.delete()
        listener(State(Phase.DOWNLOADING,0,"正在下载更新…"))
        future=worker.submit {
            val result=runCatching { downloadAndVerify(release,listener) }
            main.post {
                result.onSuccess { listener(State(Phase.READY,100,"更新包校验完成")) }
                    .onFailure { listener(State(Phase.FAILED,message=it.message?:"更新下载失败")) }
            }
        }
    }

    fun cancel() { cancelled=true;future?.cancel(true);future=null;partial.delete() }

    fun installPending(requestPermission:Boolean=true):InstallResult {
        val release=loadPending()?:return InstallResult.MISSING
        if(!apk.isFile || apk.length()!=release.bytes)return InstallResult.MISSING
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O&&!activity.packageManager.canRequestPackageInstalls()) {
            if(!requestPermission)return InstallResult.PERMISSION_REQUIRED
            return try {
                val intent=Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${activity.packageName}"))
                activity.startActivity(intent);InstallResult.PERMISSION_REQUESTED
            } catch(_:Exception) {
                runCatching { activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                    .fold({InstallResult.PERMISSION_REQUESTED},{InstallResult.FAILED})
            }
        }
        return try {
            val intent=Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(UpdateApkProvider.uri(activity.packageName),APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent);InstallResult.STARTED
        } catch(_:Exception) { InstallResult.FAILED }
    }

    private fun downloadAndVerify(release:ShareUpdates.Release,listener:(State)->Unit) {
        val digest=MessageDigest.getInstance("SHA-256")
        var connection:HttpURLConnection?=null
        try {
            connection=open(release.url)
            val declared=connection.getHeaderFieldLong("Content-Length",-1L)
            if(declared>0&&declared!=release.bytes)throw IOException("更新包大小与签名清单不一致")
            var total=0L;var lastPercent=-1
            connection.inputStream.use { input -> FileOutputStream(partial).use { output ->
                val buffer=ByteArray(32768)
                while(true) {
                    ensureActive();val count=input.read(buffer);if(count<0)break
                    total+=count;if(total>release.bytes)throw IOException("更新包大小超出签名清单")
                    digest.update(buffer,0,count);output.write(buffer,0,count)
                    val percent=((total*100L/release.bytes).coerceIn(0,100)).toInt()
                    if(percent!=lastPercent&&percent%2==0) { lastPercent=percent;main.post{listener(State(Phase.DOWNLOADING,percent,"正在下载更新… $percent%"))} }
                }
                output.fd.sync()
            }}
            ensureActive()
            if(total!=release.bytes)throw IOException("更新包下载不完整")
            val hash=digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
            if(hash!=release.sha256)throw IOException("更新包 SHA-256 校验失败")
            verifyArchive(partial,release)
            if(apk.exists()&&!apk.delete())throw IOException("旧更新缓存无法替换")
            if(!partial.renameTo(apk))throw IOException("更新包无法保存")
            savePending(release)
        } finally { connection?.disconnect();if(cancelled)partial.delete() }
    }

    private fun open(source:String):HttpURLConnection {
        var url=URL(source)
        repeat(6) {
            ensureSafe(url)
            val connection=url.openConnection() as HttpURLConnection
            connection.connectTimeout=8000;connection.readTimeout=15000;connection.instanceFollowRedirects=false;connection.useCaches=false
            connection.setRequestProperty("User-Agent","FloatClip-Android-Updater")
            when(val code=connection.responseCode) {
                HttpURLConnection.HTTP_OK -> return connection
                HttpURLConnection.HTTP_MOVED_PERM,HttpURLConnection.HTTP_MOVED_TEMP,307,308 -> {
                    val next=connection.getHeaderField("Location")?:throw IOException("更新下载跳转无效")
                    val resolved=URL(url,next);connection.disconnect();url=resolved
                }
                else -> {connection.disconnect();throw IOException("更新下载失败：HTTP $code")}
            }
        }
        throw IOException("更新下载跳转过多")
    }

    private fun ensureSafe(url:URL) {
        val host=url.host.lowercase()
        val trusted=host=="mcp.wmy-cloud.cn"||host=="github.com"||host.endsWith(".githubusercontent.com")||host.endsWith(".githubassets.com")
        require(url.protocol=="https"&&url.userInfo==null&&url.port in listOf(-1,443)&&trusted){"更新下载地址不受支持"}
    }

    @Suppress("DEPRECATION")
    private fun verifyArchive(file:File,release:ShareUpdates.Release) {
        val flags=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.P)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info=activity.packageManager.getPackageArchiveInfo(file.absolutePath,flags)?:throw IOException("更新包无法解析")
        if(info.packageName!=release.packageName)throw IOException("更新包包名不匹配")
        val version=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.P)info.longVersionCode else info.versionCode.toLong()
        if(version!=release.build.toLong()||info.versionName!=release.version)throw IOException("更新包版本与签名清单不匹配")
        val signatures=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.P)info.signingInfo?.apkContentsSigners.orEmpty() else info.signatures.orEmpty()
        if(signatures.isEmpty())throw IOException("更新包缺少签名")
        val certDigest=MessageDigest.getInstance("SHA-256")
        val expected=release.signerSha256.lowercase()
        if(signatures.none { signature -> certDigest.digest(signature.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}==expected })
            throw IOException("更新包签名证书不匹配")
    }

    private fun savePending(release:ShareUpdates.Release) {
        val value=JSONObject().put("build",release.build).put("version",release.version).put("sha256",release.sha256)
            .put("bytes",release.bytes).put("package",release.packageName).put("signer",release.signerSha256).toString()
        if(!prefs.edit().putString("pending_release",value).commit())throw IOException("更新状态无法保存")
    }
    private fun loadPending():ShareUpdates.Release?=runCatching {
        val json=JSONObject(prefs.getString("pending_release",null)?:return null)
        ShareUpdates.Release(json.getInt("build"),json.getString("version"),"","",json.getString("sha256"),json.getLong("bytes"),json.getString("package"),json.getString("signer"))
    }.getOrNull()
    private fun ensureActive(){if(cancelled||Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException("更新下载已取消")}
    override fun close(){cancel();worker.shutdownNow()}
    companion object { const val APK_MIME="application/vnd.android.package-archive" }
}
