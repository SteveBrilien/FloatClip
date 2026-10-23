package com.floatclip.probe

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

/** One authenticated public manifest for both native clients; no account or clipboard data is sent. */
internal object ShareUpdates {
    const val BUILD = 29
    const val VERSION = "0.7.9"
    const val FEED = "https://clip.wmy-cloud.cn/release.json"
    const val FALLBACK_FEED = "https://raw.githubusercontent.com/SteveBrilien/FloatClip/main/updates/native-updates.json"
    const val DEV_FEED = FEED
    const val PACKAGE = "com.floatclip.probe"
    const val SIGNER = "0368593f82ab885737b99d78c939111a1ec8ea3d88e8a67b2c3c9a4bd90c3f2c"
    enum class Channel(val feed:String,val wire:String,val label:String) {
        PREVIEW(FEED,"share-preview","预览"), DEV(DEV_FEED,"share-dev","开发测试"),
    }
    data class Release(val build:Int,val version:String,val notes:String,val url:String,val sha256:String,val bytes:Long,val packageName:String,val signerSha256:String)
    internal class SourceUnavailable(message:String,cause:Throwable?=null):IOException(message,cause)
    fun check(channel:Channel=Channel.PREVIEW,fetcher:((String)->ByteArray)?=null):Release {
        var failure:SourceUnavailable?=null
        for(source in listOf(channel.feed,FALLBACK_FEED)) {
            val content=try { (fetcher ?: ::fetch)(source) } catch(error:SourceUnavailable) { failure=error;continue }
            // A bad signature is a hard failure. Never hide it behind a fallback request.
            return parse(verifySigned(content,channel),channel)
        }
        throw IOException("所有更新源暂不可用：${failure?.message}",failure)
    }
    private fun fetch(source:String):ByteArray {
        val label=if(source==FEED) "FloatClip 公网更新源" else "GitHub 备用更新源"
        val connection=URL(source).openConnection() as HttpsURLConnection
        connection.connectTimeout=6000;connection.readTimeout=6000;connection.instanceFollowRedirects=false
        connection.useCaches=false;connection.setRequestProperty("User-Agent","FloatClip-Android/$VERSION");connection.setRequestProperty("Cache-Control","no-cache")
        try {
            if(connection.responseCode!=200)throw SourceUnavailable("$label 返回 HTTP ${connection.responseCode}")
            return connection.inputStream.use { input ->
                val data=ByteArrayOutputStream();val buffer=ByteArray(8192);var size=input.read(buffer)
                while(size>=0) { require(data.size()+size<=131072) { "更新信息过大" };data.write(buffer,0,size);size=input.read(buffer) }
                data.toByteArray()
            }
        } catch(error:SourceUnavailable) { throw error }
        catch(error:java.net.SocketTimeoutException) { throw SourceUnavailable("$label 连接超时",error) }
        catch(error:IOException) { throw SourceUnavailable("无法连接$label，请检查网络或安全连接后重试",error) }
        finally { connection.disconnect() }
    }
    internal fun verifySigned(data:ByteArray,channel:Channel,now:Long=System.currentTimeMillis()/1000,publicKey:String=UpdateTrust.PUBLIC_KEY):JSONObject {
        require(data.size<=131072) { "更新信息过大" }
        val token=JSONObject(String(data,Charsets.UTF_8)).getJSONObject("nativeUpdates").getString(channel.wire)
        val parts=token.split('.');require(parts.size==3) { "更新清单签名格式无效" }
        fun decode(value:String):ByteArray { require(value.length<=100000);return Base64.getUrlDecoder().decode(value) }
        val header=JSONObject(String(decode(parts[0]),Charsets.UTF_8))
        require(!header.has("crit") && !header.has("b64")) { "更新清单使用了不受支持的签名扩展" }
        require(header.getString("alg")=="RS256" && header.getString("kid")==UpdateTrust.KEY_ID && header.getString("typ")=="JOSE") { "更新签名算法或密钥不受支持" }
        val key=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
        val verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(key);verifier.update((parts[0]+"."+parts[1]).toByteArray(Charsets.US_ASCII))
        require(verifier.verify(decode(parts[2]))) { "更新清单签名校验失败" }
        val payload=JSONObject(String(decode(parts[1]),Charsets.UTF_8));val issued=payload.getLong("issuedAt");val expiry=payload.getLong("expiresAt")
        require(payload.getInt("schema")==1 && expiry>now && issued<=now+900 && expiry>issued) { "更新清单已过期或时间无效" }
        return payload.getJSONObject("release").also { require(it.getString("channel")==channel.wire) { "更新通道不匹配" } }
    }
    internal fun parse(json:JSONObject,channel:Channel):Release {
        require(json.getInt("schema")==1 && json.getString("channel")==channel.wire) { "更新通道不匹配" }
        val platform=json.getJSONObject("android");val url=platform.getString("url");val target=URL(url)
        require(target.protocol=="https" && target.userInfo==null && target.port in listOf(-1,443) && target.host in listOf("mcp.wmy-cloud.cn","github.com")) { "下载地址不受支持" }
        require(platform.optString("status")=="available") { "Android 更新包尚未发布" }
        val version=platform.optString("version",json.getString("version"));require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "版本信息无效" }
        val build=platform.getInt("build");val hash=platform.getString("sha256");val bytes=platform.getLong("bytes")
        require(build>0 && hash.matches(Regex("[a-fA-F0-9]{64}")) && bytes in 1..(100L*1024*1024)) { "更新包校验信息无效" }
        require(platform.getString("package")==PACKAGE && platform.getString("signerSha256")==SIGNER) { "更新包身份不匹配" }
        return Release(build,version,json.optString("notes").take(1200),url,hash.lowercase(),bytes,PACKAGE,SIGNER)
    }
    internal fun compareVersion(a:String,b:String):Int {
        val first=a.split('.').map { it.toIntOrNull() ?: 0 };val second=b.split('.').map { it.toIntOrNull() ?: 0 }
        for(index in 0..2) { val difference=(first.getOrNull(index) ?: 0).compareTo(second.getOrNull(index) ?: 0);if(difference!=0)return difference }
        return 0
    }
}
