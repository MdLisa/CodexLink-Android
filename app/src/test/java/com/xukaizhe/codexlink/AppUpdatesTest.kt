package com.xukaizhe.codexlink

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AppUpdatesTest {
    private fun manifest():JSONObject {val sha="a".repeat(64);val file="CodexLink-0.10.0-16-${sha.take(16)}.apk";return JSONObject().put("schemaVersion",1).put("packageName","com.xukaizhe.codexlink").put("versionCode",16).put("versionName","0.10.0").put("size",10).put("sha256",sha).put("minSdk",26).put("notes","更新说明").put("file",file).put("downloadUrl",UPDATE_BASE+file)}
    private fun reject(block:()->Unit){try{block();fail("Should reject unsafe update")}catch(_:IllegalArgumentException){}}
    @Test fun parsesTrustedManifestAndRejectsUntrustedOrMalformedFields(){
        assertEquals(16L,AndroidRelease.parse(manifest()).versionCode)
        for((key,value) in listOf("schemaVersion" to 2,"packageName" to "another.app","versionCode" to 16.5,"versionCode" to "16","size" to MAX_UPDATE_BYTES+1,"sha256" to "bad","file" to "../x.apk","downloadUrl" to "https://evil.test/x.apk","notes" to "a".repeat(8001),"minSdk" to 25))reject{AndroidRelease.parse(manifest().put(key,value))}
    }
    @Test fun rejectsPackageVersionAndSigningMismatchAndDowngrade(){
        val release=AndroidRelease.parse(manifest());val pkg="com.xukaizhe.codexlink";val signer=setOf("trusted")
        fun check(p:String=pkg,code:Long=16,name:String="0.10.0",signers:Set<String> = signer,installed:Long=15)=validateIdentity(p,code,name,signers,pkg,installed,signer,release)
        check();reject{check(p="another.app")};reject{check(code=17)};reject{check(name="0.9.2")};reject{check(signers=setOf("other"))};reject{check(signers=emptySet())};reject{check(installed=16)};reject{check(installed=17)}
    }
    private fun withServer(block:(MockWebServer,UpdateRepository,Context)->Unit){
        val server=MockWebServer();server.start()
        val context:Context=RuntimeEnvironment.getApplication()
        // Reroute requests only in this test; production always uses the pinned HTTPS origin.
        val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).addInterceptor{chain->chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())}.build()
        try{block(server,UpdateRepository(context,client),context)}finally{server.shutdown();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()}
    }
    @Test fun versionCheckSendsNoLoginCredentialsAndRejectsRedirectsAndOversizedResponses()=withServer{server,repo,_->
        server.enqueue(MockResponse().setBody(manifest().toString()));assertEquals(16L,repo.latest().versionCode)
        val request=server.takeRequest();assertEquals("/updates/android/latest.json",request.path);assertNull(request.getHeader("Authorization"));assertNull(request.getHeader("Cookie"))
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location","https://evil.test/update"));reject{repo.latest()}
        server.enqueue(MockResponse().setBody("x".repeat(32769)));reject{repo.latest()}
        server.enqueue(MockResponse().setChunkedBody("x".repeat(32769),4096));reject{repo.latest()}
    }
    @Test fun incompleteOversizedOrCorruptDownloadsNeverLeaveAnInstallableFile()=withServer{server,repo,context->
        val release=AndroidRelease.parse(manifest())
        for(body in listOf("short","x".repeat(11),"x".repeat(10))){server.enqueue(MockResponse().setBody(body));reject{repo.download(release){}};val files=java.io.File(context.cacheDir,"app_updates").listFiles().orEmpty();assertTrue(files.none{it.extension=="apk"||it.name=="download.part"})}
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location","https://evil.test/app.apk"));reject{repo.download(release){}}
        server.enqueue(MockResponse().setChunkedBody("x".repeat(11),4));reject{repo.download(release){}}
    }
    @Test fun cancellingBeforeRequestCreationPreventsTheRequestAndCancellationDuringDownloadCleansPartialFile()=withServer{server,repo,context->
        repo.beginOperation();repo.cancel()
        try{repo.latest();fail("Cancelled request must fail")}catch(_:java.io.IOException){}
        assertEquals(0,server.requestCount)
        repo.beginOperation();server.enqueue(MockResponse().setBody("x".repeat(10)).throttleBody(1,50,java.util.concurrent.TimeUnit.MILLISECONDS))
        try{repo.download(AndroidRelease.parse(manifest())){repo.cancel()};fail("Cancelled download must fail")}catch(_:java.io.IOException){}
        assertTrue(java.io.File(context.cacheDir,"app_updates").listFiles().orEmpty().none{it.extension=="apk"||it.name=="download.part"})
    }
}
