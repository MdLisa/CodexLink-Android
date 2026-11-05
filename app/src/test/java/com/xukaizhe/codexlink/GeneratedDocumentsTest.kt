package com.xukaizhe.codexlink

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class GeneratedDocumentsTest {
    private val id="12345678-1234-1234-1234-123456789abc"
    private fun document(body:String="document")=GeneratedDocument(id,"方案.txt","text/plain",body.toByteArray().size.toLong(),MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString(""){"%02x".format(it)},true)
    private fun withServer(block:(MockWebServer,GeneratedDocumentDownloader,Context)->Unit){
        MockWebServer().use{server->
            val context:Context=RuntimeEnvironment.getApplication()
            val client=BridgeApi(Session(server.url("/").toString().trimEnd('/'),"private-token","device"),identity=TestDeviceIdentity())
            try{block(server,GeneratedDocumentDownloader(context,client),context)}finally{client.client.connectionPool.evictAll();client.client.dispatcher.executorService.shutdown()}
        }
    }
    private fun reject(block:suspend()->Unit){var error:Exception?=null;try{runBlocking{block()}}catch(e:Exception){error=e};assertNotNull("Expected unsafe download to fail",error)}
    @Test fun downloadsWithDeviceProofAndVerifiesContentBeforeOpening()=withServer{server,repo,context->
        server.enqueue(MockResponse().setBody("document"));var percent=0
        val file=runBlocking{repo.download(document()){percent=it}}
        assertEquals("document",file.readText());assertEquals(100,percent)
        val request=server.takeRequest();assertEquals("/api/generated-files/$id",request.path);assertEquals("Bearer private-token",request.getHeader("Authorization"));assertNotNull(request.getHeader("x-device-signature"))
        file.delete()
    }
    @Test fun documentProviderConfigurationOnlyExposesTheDocumentCache(){
        val context:Context=RuntimeEnvironment.getApplication()
        val provider=context.packageManager.resolveContentProvider(context.packageName+".documents",android.content.pm.PackageManager.GET_META_DATA)!!
        assertFalse(provider.exported);assertTrue(provider.grantUriPermissions);assertEquals(GeneratedDocumentProvider::class.java.name,provider.name)
        val xml=provider.loadXmlMetaData(context.packageManager,"android.support.FILE_PROVIDER_PATHS")!!
        val roots=mutableListOf<Pair<String,String>>()
        xml.use{while(it.next()!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT){if(it.eventType==org.xmlpull.v1.XmlPullParser.START_TAG&&it.name!="paths")roots.add(it.name to it.getAttributeValue(null,"path"))}}
        assertEquals(listOf("cache-path" to "generated_documents/"),roots)
    }
    @Test fun fileProviderCanOpenTheDocumentAndRejectsSiblingCaches()=withServer{server,repo,context->
        // AndroidX FileProvider uses root + '/' internally; Windows JVM paths use backslashes.
        // Exercise actual URI mapping on POSIX/Android; Windows still verifies the manifest roots above.
        assumeFalse("AndroidX FileProvider URI mapping requires POSIX file paths",File.separatorChar=='\\')
        server.enqueue(MockResponse().setBody("document"));val file=runBlocking{repo.download(document()){}}
        val intent=generatedDocumentIntent(context,file,"text/plain");assertEquals(Intent.ACTION_VIEW,intent.action);assertEquals("content",intent.data!!.scheme);assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("document",context.contentResolver.openInputStream(intent.data!!)!!.bufferedReader().use{it.readText()})
        val outside=File(context.cacheDir,"outside-private.txt").apply{writeText("secret")}
        try{var denied=false;try{generatedDocumentIntent(context,outside,"text/plain")}catch(_:IllegalArgumentException){denied=true};assertTrue(denied)}finally{outside.delete();file.delete()}
    }
    @Test fun rejectsUnsafeMetadataBeforeAnyNetworkRequest()=withServer{server,repo,_->
        val good=document()
        for(doc in listOf(good.copy(id="../secret"),good.copy(name="../secret.txt"),good.copy(name="C:\\private.txt"),good.copy(size=GENERATED_DOCUMENT_LIMIT+1),good.copy(size=-1),good.copy(sha256="bad"),good.copy(available=false))){reject{repo.download(doc){}}}
        assertEquals(0,server.requestCount)
        good.copy(size=GENERATED_DOCUMENT_LIMIT).validate()
    }
    @Test fun rejectsChangedTruncatedAndChunkedOversizedContentAndRemovesPartials()=withServer{server,repo,context->
        for(response in listOf(MockResponse().setBody("tampered"),MockResponse().setBody("short"),MockResponse().setChunkedBody("document-extra",3),MockResponse().setHeader("Content-Length",GENERATED_DOCUMENT_LIMIT+1))){
            server.enqueue(response);reject{repo.download(document()){}}
            assertFalse(File(context.cacheDir,"generated_documents/$id/.partial").exists())
        }
    }
    @Test fun redirectsAndRevokedPermissionsNeverProduceAFile()=withServer{server,repo,_->
        for(response in listOf(MockResponse().setResponseCode(302).addHeader("Location","https://example.com/private"),MockResponse().setResponseCode(403),MockResponse().setResponseCode(404))){server.enqueue(response);reject{repo.download(document()){}}}
        assertEquals(3,server.requestCount)
    }
    @Test fun codecAttachesDeliveriesOnlyToAssistantMessages(){
        val file=JSONObject().put("id",id).put("name","方案.txt").put("size",8).put("mime","text/plain").put("sha256",document().sha256).put("available",true)
        val item=JSONObject().put("id","reply").put("type","agentMessage").put("text","已生成").put("generatedFiles",org.json.JSONArray().put(file))
        assertEquals(id,BridgeCodec.item("turn",item)!!.generatedFiles.single().id)
        item.put("type","userMessage");assertTrue(BridgeCodec.item("turn",item)!!.generatedFiles.isEmpty())
    }
}
