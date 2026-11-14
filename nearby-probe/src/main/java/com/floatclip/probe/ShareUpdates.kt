package com.floatclip.probe

import org.json.JSONObject
import org.json.JSONArray
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
    const val BUILD = 57
    const val VERSION = "0.7.9"
    const val FEED = "https://raw.githubusercontent.com/SteveBrilien/FloatClip/native-updates-v2/updates/native-updates-v2.json"
    const val FALLBACK_FEED = "https://cdn.jsdelivr.net/gh/SteveBrilien/FloatClip@native-updates-v2/updates/native-updates-v2.json"
    const val CANDIDATE_FEED = "https://api.github.com/repos/SteveBrilien/FloatClip/releases?per_page=12"
    // V3 uses a new pinned public key. Older clients continue using the untouched v2 branch.
    const val NEW_FEED = "https://raw.githubusercontent.com/SteveBrilien/FloatClip/native-updates-v3/updates/native-updates-v3.json"
    const val NEW_FALLBACK_FEED = "https://cdn.jsdelivr.net/gh/SteveBrilien/FloatClip@native-updates-v3/updates/native-updates-v3.json"
    private const val PUBLIC_FEED_ID = "share-preview"
    const val PACKAGE = "com.floatclip.probe"
    const val SIGNER = "0368593f82ab885737b99d78c939111a1ec8ea3d88e8a67b2c3c9a4bd90c3f2c"
    data class Release(val build:Int,val version:String,val notes:String,val url:String,val sha256:String,val bytes:Long,val packageName:String,val signerSha256:String,val candidate:Boolean=false)
    internal class SourceUnavailable(message:String,cause:Throwable?=null):IOException(message,cause)
    class CheckCancellation {
        @Volatile private var cancelled=false
        @Volatile private var active:HttpsURLConnection?=null
        fun ensureActive() {
            if(cancelled || Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException()
        }
        fun attach(connection:HttpsURLConnection) { active=connection;ensureActive() }
        fun detach(connection:HttpsURLConnection) { if(active===connection)active=null }
        fun cancel() {
            cancelled=true
            active?.let { connection ->
                Thread({ runCatching { connection.disconnect() } },"FloatClip cancel update").apply { isDaemon=true }.start()
            }
        }
    }
    fun check(
        fetcher:((String)->ByteArray)?=null,
        cancellation:CheckCancellation=CheckCancellation(),
        onSource:(String)->Unit={},
        verify:(ByteArray)->JSONObject={ verifySigned(it) },
    ):Release {
        val failures=mutableListOf<String>()
        for(source in listOf(FEED,FALLBACK_FEED)) {
            cancellation.ensureActive()
            val label=if(source==FEED)"公网更新源" else "GitHub CDN 备用源"
            onSource(label)
            val content=try { fetcher?.invoke(source) ?: fetch(source,cancellation) }
                catch(error:SourceUnavailable) { failures+=label+"："+error.message;continue }
            cancellation.ensureActive()
            // A bad signature is a hard failure; it must never be hidden by another source.
            return parse(verify(content))
        }
        throw IOException("更新源暂不可用：\n"+failures.joinToString("\n"))
    }
    /** The host holds the v3 private key; only the public verifier is shipped in the APK. */
    private fun checkNewSigned(
        fetcher:((String)->ByteArray)?, cancellation:CheckCancellation,
        onSource:(String)->Unit,
    ):Release? {
        for(source in listOf(NEW_FEED,NEW_FALLBACK_FEED)) {
            cancellation.ensureActive()
            onSource(if(source==NEW_FEED)"FloatClip 签名更新源 v3" else "FloatClip v3 CDN 备用源")
            val body=try { fetcher?.invoke(source) ?: fetch(source,cancellation) }
                catch(error:SourceUnavailable) { continue }
            cancellation.ensureActive()
            // Never conceal signature/identity failures behind the legacy or candidate feed.
            return parse(verifySigned(body,publicKey=UpdateTrust.NEW_PUBLIC_KEY,keyId=UpdateTrust.NEW_KEY_ID))
        }
        return null
    }

    /** Signed production feed remains authoritative. The separately labelled candidate feed
     * provides release discovery, not a replacement for native signature checks. Its APK must
     * still pass exact hash, package name and pinned installed-app signer verification.
     */
    fun checkLatest(
        cancellation:CheckCancellation=CheckCancellation(),
        onSource:(String)->Unit={},
        fetcher:((String)->ByteArray)?=null,
        verify:(ByteArray)->JSONObject={verifySigned(it)},
    ):Release {
        val latestSigned=checkNewSigned(fetcher,cancellation,onSource)
        val signed=latestSigned ?: try { check(fetcher,cancellation,onSource,verify) }
            catch(error:IOException) { null } // Both old signed URLs unreachable: try official repo.
        cancellation.ensureActive()
        onSource("GitHub 候选发布")
        val candidate=try {
            val bytes=fetcher?.invoke(CANDIDATE_FEED) ?: fetch(CANDIDATE_FEED,cancellation)
            cancellation.ensureActive()
            parseCandidateList(JSONArray(String(bytes,Charsets.UTF_8)))
        } catch(error:SourceUnavailable) { null }
          catch(error:IOException) { null }
          catch(error:org.json.JSONException) { null }
        return when {
            candidate!=null && (signed==null || compareVersion(candidate.version,signed.version)>0 ||
                candidate.version==signed.version&&candidate.build>signed.build) -> candidate
            signed!=null -> signed
            else -> throw IOException("签名更新源及 GitHub 候选发布均不可用，请检查网络")
        }
    }

    internal fun parseCandidateList(releases:JSONArray):Release? {
        val pattern=Regex("v([0-9]+\\.[0-9]+\\.[0-9]+)-build([0-9]+)(?:-android)?-candidate")
        var newest:Release?=null
        for(i in 0 until minOf(releases.length(),12)) {
            val release=releases.optJSONObject(i)?:continue
            if(!release.optBoolean("prerelease") || release.optBoolean("draft",true))continue
            val tag=release.optString("tag_name")
            val match=pattern.matchEntire(tag)?:continue
            val version=match.groupValues[1]
            val build=match.groupValues[2].toIntOrNull()?.takeIf { it in 1..100000 }?:continue
            val name="FloatClip-Android-$version-build$build.apk"
            val assets=release.optJSONArray("assets")?:continue
            val asset=(0 until assets.length()).mapNotNull(assets::optJSONObject)
                .firstOrNull { it.optString("name")==name }?:continue
            val url="https://github.com/SteveBrilien/FloatClip/releases/download/$tag/$name"
            if(asset.optString("browser_download_url")!=url)continue
            val digest=asset.optString("digest")
            if(!digest.matches(Regex("sha256:[a-fA-F0-9]{64}")))continue
            val bytes=asset.optLong("size",0L)
            if(bytes !in 1..(100L*1024*1024))continue
            val found=Release(build,version,"GitHub 验收候选版 · 安装包仍需通过 SHA-256 和 FloatClip 原签名校验。\n"+
                release.optString("body").take(650),url,digest.removePrefix("sha256:").lowercase(),bytes,PACKAGE,SIGNER,true)
            if(newest==null || compareVersion(found.version,newest.version)>0 ||
                found.version==newest.version&&found.build>newest.build)newest=found
        }
        return newest
    }

    private fun fetch(source:String,cancellation:CheckCancellation):ByteArray {
        val connection=URL(source).openConnection() as HttpsURLConnection
        connection.connectTimeout=4000;connection.readTimeout=4000;connection.instanceFollowRedirects=false
        connection.useCaches=false;connection.setRequestProperty("User-Agent","FloatClip-Android/$VERSION");connection.setRequestProperty("Cache-Control","no-cache")
        try {
            cancellation.attach(connection)
            if(connection.responseCode!=200)throw SourceUnavailable("HTTP "+connection.responseCode)
            return connection.inputStream.use { input ->
                val data=ByteArrayOutputStream();val buffer=ByteArray(8192);var size=input.read(buffer)
                while(size>=0) {
                    cancellation.ensureActive()
                    require(data.size()+size<=if(source==CANDIDATE_FEED)524288 else 131072) { "更新信息过大" }
                    data.write(buffer,0,size);size=input.read(buffer)
                }
                data.toByteArray()
            }
        } catch(error:SourceUnavailable) { throw error }
        catch(error:java.net.SocketTimeoutException) { cancellation.ensureActive();throw SourceUnavailable("连接超时",error) }
        catch(error:IOException) { cancellation.ensureActive();throw SourceUnavailable("网络连接失败，请稍后重试",error) }
        finally { cancellation.detach(connection);connection.disconnect() }
    }
    internal fun verifySigned(data:ByteArray,now:Long=System.currentTimeMillis()/1000,publicKey:String=UpdateTrust.PUBLIC_KEY,keyId:String=UpdateTrust.KEY_ID):JSONObject {
        require(data.size<=131072) { "更新信息过大" }
        val token=JSONObject(String(data,Charsets.UTF_8)).getJSONObject("nativeUpdates").getString(PUBLIC_FEED_ID)
        val parts=token.split('.');require(parts.size==3) { "更新清单签名格式无效" }
        fun decode(value:String):ByteArray { require(value.length<=100000);return Base64.getUrlDecoder().decode(value) }
        val header=JSONObject(String(decode(parts[0]),Charsets.UTF_8))
        require(!header.has("crit") && !header.has("b64")) { "更新清单使用了不受支持的签名扩展" }
        require(header.getString("alg")=="RS256" && header.getString("kid")==keyId && header.getString("typ")=="JOSE") { "更新签名算法或密钥不受支持" }
        val key=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
        val verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(key);verifier.update((parts[0]+"."+parts[1]).toByteArray(Charsets.US_ASCII))
        require(verifier.verify(decode(parts[2]))) { "更新清单签名校验失败" }
        val payload=JSONObject(String(decode(parts[1]),Charsets.UTF_8));val issued=payload.getLong("issuedAt");val expiry=payload.getLong("expiresAt")
        require(payload.getInt("schema")==1 && expiry>now && issued<=now+900 && expiry>issued) { "更新清单已过期或时间无效" }
        return payload.getJSONObject("release").also { require(it.getString("channel")==PUBLIC_FEED_ID) { "更新来源不匹配" } }
    }
    internal fun parse(json:JSONObject):Release {
        require(json.getInt("schema")==1 && json.getString("channel")==PUBLIC_FEED_ID) { "更新来源不匹配" }
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
