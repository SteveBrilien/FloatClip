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
    private fun verifyLegacy(data:ByteArray, now:Long=issued(data)+1)=ShareUpdates.verifySigned(
        data,now,"MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAsXW4FLhY9+7Mzp3TOrSy5Viq+6fC+g425k0khzgUTiXswWGXaCMPVnqtP0v1UwBQv9wt3TGsOqr5oHWJ4HgggS4EYU/ANi2eU/DO/3VOvlA55IjzlkstgAAVH1+MS9A+bGjERbFc6huDaM0rkOiTz44SX+N3ByxWXGwkUZa5rE3P4nDHCYVZcMvEWOtnPClUBE7DqX4nkOqpsyL1JewV3COsTpwh0uWy6b14njcZ35T97igQeaxXZgOegpnlBbDH4kj9GVkI+Z/lQxKrNpJwX+xICi4TWDGernA2UBnkFwcyx07YxyCrUmiTnS0oFMDTU0hSTMYbe2KzaZVmZMfA4ig5Bsax57eCaP+4PibOf/EoAPRJFdOpxOaVyR8OltgY1fUC2NdDEYvkYH+5w+h8VggrH9+s9p6yvuyr317pUo7BmJAYdY+BOe6AaYvSJgYipM8++AaFFFrlJAVlIkUS6OEK/ApnBTwlBJPsRg5VGmogxwpiVzKZcuEqx2EdI9itAgMBAAE=","floatclip-updates-20261023"
    )
    private fun altered():ByteArray {
        val json=JSONObject(String(fixture()));val updates=json.getJSONObject("nativeUpdates");val token=updates.getString("share-preview").split('.').toMutableList()
        token[2]=(if(token[2][0]=='A') "B" else "A")+token[2].substring(1);updates.put("share-preview",token.joinToString("."));return json.toString().toByteArray()
    }
    private fun rejected(action:()->Unit) {try { action();fail("Invalid update metadata accepted") } catch(error:Exception) {assertFalse(error is org.junit.internal.AssumptionViolatedException)} }
    @Test fun signedPublicRelease() {
        val release = ShareUpdates.parse(verifyLegacy(fixture()))
        assertEquals("0.7.1", release.version)
        assertEquals(14, release.build)
        assertEquals(ShareUpdates.PACKAGE, release.packageName)
        assertEquals(ShareUpdates.SIGNER, release.signerSha256)
        assertTrue(release.bytes > 0)
    }
    @Test fun rejectsTamperedExpiredAndDevelopmentOnlyManifests() {
        rejected { verifyLegacy(altered()) }
        rejected { verifyLegacy(fixture(), issued(fixture()) + 366 * 86400) }
        val json = JSONObject(String(fixture()))
        val updates = json.getJSONObject("nativeUpdates")
        val development = updates.getString("share-dev")
        updates.remove("share-preview")
        rejected { verifyLegacy(json.toString().toByteArray(), issued(fixture()) + 1) }
        updates.put("share-preview", development)
        rejected { verifyLegacy(json.toString().toByteArray(), issued(fixture()) + 1) }
    }
    @Test fun primaryFailureUsesFallbackButSignatureFailureDoesNot() {
        var calls=0
        val release=ShareUpdates.check(fetcher={calls++;if(calls==1)throw ShareUpdates.SourceUnavailable("HTTP 503");fixture()},verify={verifyLegacy(it)})
        assertEquals(2,calls);assertEquals("0.7.1",release.version)
        calls=0;rejected { ShareUpdates.check(fetcher={calls++;altered()}) };assertEquals(1,calls)
        calls=0;rejected { ShareUpdates.check(fetcher={calls++;throw ShareUpdates.SourceUnavailable("offline")}) };assertEquals(2,calls)
    }
    @Test fun rotatedClientRejectsTheLegacySigner() { rejected { ShareUpdates.verifySigned(fixture(),issued(fixture())+1) } }
    @Test fun validatesPackageIdentityUrlsAndPlatformVersion() {
        val release=verifyLegacy(fixture())
        release.put("version","9.9.9");assertEquals("0.7.1",ShareUpdates.parse(release).version)
        for(url in listOf("http://mcp.wmy-cloud.cn/x","https://mcp.wmy-cloud.cn@evil.test/x","https://evil.test/x","https://github.com:444/x")) {
            val copy=JSONObject(release.toString());copy.getJSONObject("android").put("url",url);rejected { ShareUpdates.parse(copy) }
        }
        for(field in listOf("package","signerSha256","sha256")) {val copy=JSONObject(release.toString());copy.getJSONObject("android").put(field,"wrong");rejected { ShareUpdates.parse(copy) }}
    }
    @Test fun comparesSemanticVersionsWithoutDowngrade() {assertTrue(ShareUpdates.compareVersion("0.7.1","0.7.7")<0);assertTrue(ShareUpdates.compareVersion("0.7.10","0.7.9")>0);assertEquals(0,ShareUpdates.compareVersion("0.7.7","0.7.7"))}
    @Test fun reportsBothSourceFailuresAndCancelledCheckDoesNotFetch() {
        var calls=0
        try {
            ShareUpdates.check(fetcher={ calls++;throw ShareUpdates.SourceUnavailable(if(calls==1)"HTTP 503" else "HTTP 504") })
            fail("offline check claimed success")
        } catch(error:java.io.IOException) {
            assertTrue(error.message!!.contains("HTTP 503"));assertTrue(error.message!!.contains("HTTP 504"))
            assertTrue(error.message!!.contains("公网更新源"));assertTrue(error.message!!.contains("GitHub"))
        }
        calls=0
        val cancellation=ShareUpdates.CheckCancellation();cancellation.cancel()
        try {
            ShareUpdates.check(fetcher={ calls++;fixture() },cancellation=cancellation)
            fail("cancelled request ran")
        } catch(_:java.util.concurrent.CancellationException) {}
        assertEquals(0,calls)
    }

}
