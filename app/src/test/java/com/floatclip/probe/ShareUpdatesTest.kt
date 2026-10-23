package com.floatclip.probe

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
// Use the JVM RSA provider, as the other host tests do. Conscrypt has no Linux ARM64 JNI artifact.
@ConscryptMode(ConscryptMode.Mode.OFF)
class ShareUpdatesTest {
    private fun fixture()=javaClass.classLoader!!.getResourceAsStream("native-updates.json")!!.readBytes()
    private fun issued(data:ByteArray):Long {
        val token=JSONObject(String(data)).getJSONObject("nativeUpdates").getString("share-preview")
        return JSONObject(String(Base64.getUrlDecoder().decode(token.split('.')[1]))).getLong("issuedAt")
    }
    private fun altered():ByteArray {
        val json=JSONObject(String(fixture()));val updates=json.getJSONObject("nativeUpdates");val token=updates.getString("share-preview").split('.').toMutableList()
        token[2]=(if(token[2][0]=='A') "B" else "A")+token[2].substring(1);updates.put("share-preview",token.joinToString("."));return json.toString().toByteArray()
    }
    private fun rejected(action:()->Unit) {try { action();fail("Invalid update metadata accepted") } catch(error:Exception) {assertFalse(error is org.junit.internal.AssumptionViolatedException)} }
    @Test fun signedPublicationAndChannels() {
        for(channel in ShareUpdates.Channel.values()) {
            val signed=ShareUpdates.verifySigned(fixture(),channel,issued(fixture())+1)
            val release=ShareUpdates.parse(signed,channel)
            assertEquals("0.7.1",release.version);assertEquals(14,release.build);assertEquals(ShareUpdates.PACKAGE,release.packageName);assertEquals(ShareUpdates.SIGNER,release.signerSha256);assertTrue(release.bytes>0)
        }
    }
    @Test fun rejectsTamperedAndExpiredManifest() {
        rejected { ShareUpdates.verifySigned(altered(),ShareUpdates.Channel.PREVIEW,issued(fixture())+1) }
        rejected { ShareUpdates.verifySigned(fixture(),ShareUpdates.Channel.PREVIEW,issued(fixture())+366*86400) }
        val json=JSONObject(String(fixture()));json.getJSONObject("nativeUpdates").remove("share-dev")
        rejected { ShareUpdates.verifySigned(json.toString().toByteArray(),ShareUpdates.Channel.DEV,issued(fixture())+1) }
    }
    @Test fun primaryFailureUsesFallbackButSignatureFailureDoesNot() {
        var calls=0
        val release=ShareUpdates.check(fetcher={calls++;if(calls==1)throw ShareUpdates.SourceUnavailable("HTTP 503");fixture()})
        assertEquals(2,calls);assertEquals("0.7.1",release.version)
        calls=0;rejected { ShareUpdates.check(fetcher={calls++;altered()}) };assertEquals(1,calls)
        calls=0;rejected { ShareUpdates.check(fetcher={calls++;throw ShareUpdates.SourceUnavailable("offline")}) };assertEquals(2,calls)
    }
    @Test fun validatesPackageIdentityUrlsAndPlatformVersion() {
        val release=ShareUpdates.verifySigned(fixture(),ShareUpdates.Channel.PREVIEW,issued(fixture())+1)
        release.put("version","9.9.9");assertEquals("0.7.1",ShareUpdates.parse(release,ShareUpdates.Channel.PREVIEW).version)
        for(url in listOf("http://mcp.wmy-cloud.cn/x","https://mcp.wmy-cloud.cn@evil.test/x","https://evil.test/x","https://github.com:444/x")) {
            val copy=JSONObject(release.toString());copy.getJSONObject("android").put("url",url);rejected { ShareUpdates.parse(copy,ShareUpdates.Channel.PREVIEW) }
        }
        for(field in listOf("package","signerSha256","sha256")) {val copy=JSONObject(release.toString());copy.getJSONObject("android").put(field,"wrong");rejected { ShareUpdates.parse(copy,ShareUpdates.Channel.PREVIEW) }}
    }
    @Test fun comparesSemanticVersionsWithoutDowngrade() {assertTrue(ShareUpdates.compareVersion("0.7.1","0.7.7")<0);assertTrue(ShareUpdates.compareVersion("0.7.10","0.7.9")>0);assertEquals(0,ShareUpdates.compareVersion("0.7.7","0.7.7"))}
}
