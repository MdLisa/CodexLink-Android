package com.xukaizhe.codexlink

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BridgeTests {
    private val identity=TestDeviceIdentity()
    private fun client(server:MockWebServer)=BridgeApi(Session(server.url("/").toString().trimEnd('/'),"private-device-token","device-1"),identity=identity)
    @Test fun addressAllowsComputerHttpAndHttpsButRejectsCredentialsAndPaths(){
        assertEquals("http://192.168.1.7:18766",BridgeCodec.address("192.168.1.7:18766"))
        assertEquals("https://personal.example",BridgeCodec.address("https://personal.example/"))
        assertEquals("http://49.140.62.116:18766",BridgeCodec.address("http://49.140.62.116:18766"))
        for(input in listOf("https://user:password@personal.example","http://192.168.1.7:18766/health","https://personal.example?token=secret")){
            try{BridgeCodec.address(input);fail(input)}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun historyIsChronologicalAndDoesNotRenderReasoningOrTrustStoredStatus(){
        val history=JSONObject("""{"turns":[{"id":"t2","status":"completed","items":[{"id":"u2","type":"userMessage","content":[{"type":"localImage","attachment":true}]},{"id":"r2","type":"reasoning","text":"private"},{"id":"a2","type":"agentMessage","text":"second"}]},{"id":"t1","items":[{"id":"a1","type":"agentMessage","text":"first"}]}]}""")
        val entries=BridgeCodec.history(history)
        assertEquals(listOf("first","","second"),entries.map{it.text});assertEquals(1,entries[1].images.size);assertFalse(entries[1].images[0].available);assertFalse(entries.any{it.text.contains("private")});assertFalse(entries.any{it.live})
    }
    @Test fun authIsSentInHeaderAndPasswordLoginReturnsOnlySessionToken()=runBlocking {
        MockWebServer().use{server->server.enqueue(MockResponse().setBody("{}"));server.enqueue(MockResponse().setBody("""{"token":"fresh-session-token","device":{"id":"phone-2"}}"""));val api=client(server)
            api.request("/api/status");val session=api.login("owner","a-private-test-passphrase","phone");assertEquals("fresh-session-token",session.token);assertEquals("phone-2",session.deviceId)
            assertEquals("Bearer private-device-token",server.takeRequest().getHeader("Authorization"));val pair=server.takeRequest();assertNull(pair.getHeader("Authorization"));assertEquals("/api/login",pair.path);val loginBody=JSONObject(pair.body.readUtf8());assertEquals("owner",loginBody.getString("username"));assertEquals("a-private-test-passphrase",loginBody.getString("password"))
        }
    }
    @Test fun occupiedChatErrorIsPreservedWithoutCreatingAnotherChat()=runBlocking {
        MockWebServer().use{server->server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"THREAD_BUSY","message":"桌面正在占用这个聊天"}}"""));val api=client(server)
            try{api.request("/api/threads/original/messages","POST",JSONObject());fail()}catch(e:BridgeException){assertEquals(409,e.status);assertEquals("THREAD_BUSY",e.code);assertTrue(e.message.contains("桌面"))}
            assertEquals(1,server.requestCount);assertEquals("/api/threads/original/messages",server.takeRequest().path)
        }
    }
    @Test fun redirectNeverForwardsDeviceCredentialsToAnotherServer()=runBlocking {
        MockWebServer().use{other->MockWebServer().use{server->server.enqueue(MockResponse().setResponseCode(302).addHeader("Location",other.url("/")))
            try{client(server).request("/api/status");fail()}catch(_:BridgeException){};assertEquals(0,other.requestCount)
        }}
    }
    @Test fun aLostReplyRetainsIdentityAndUploadsAcrossRestart()=runBlocking {
        MockWebServer().use{server->
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"image-1"}"""))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val api=client(server);var saved:Pending?=Pending("stable-message-123",api.session.address,"thread-1","read image",listOf("photo.png"))
            val initial=saved!!
            try{MessageSender(api).send(initial,{saved=it},{byteArrayOf(1,2,3)});fail()}catch(_:java.io.IOException){}
            assertNotNull(saved);assertTrue(saved!!.submitted);assertEquals(listOf("image-1"),saved!!.uploaded)
            val upload=server.takeRequest();assertEquals("/api/uploads",upload.path);assertArrayEquals(byteArrayOf(1,2,3),upload.body.readByteArray())
            val lost=server.takeRequest();val originalBody=JSONObject(lost.body.readUtf8())
            val restored=Pending.from(JSONObject(saved!!.json().toString()))
            server.enqueue(MockResponse().setResponseCode(202).setBody("""{"duplicate":true,"job":{"id":"same-job"}}"""))
            val result=MessageSender(client(server)).send(restored,{saved=it},{error("must not upload a second time")})
            val retried=server.takeRequest();assertEquals(originalBody.toString(),JSONObject(retried.body.readUtf8()).toString());assertEquals("/api/threads/thread-1/messages",retried.path)
            assertTrue(result.getBoolean("duplicate"));assertNull(saved);assertEquals(3,server.requestCount)
        }
    }
    @Test fun uploadProgressIsDurableBeforeMessagePost()=runBlocking {
        MockWebServer().use{server->server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"image-1"}"""));val api=client(server)
            val pending=Pending("stable-message-456",api.session.address,"thread-1","image",listOf("photo.png"));var saved:Pending?=pending
            try{MessageSender(api).send(pending,{p->saved=p;if(p?.submitted==true)error("simulated process death")},{byteArrayOf(1)});fail()}catch(_:IllegalStateException){}
            assertEquals(1,server.requestCount);assertEquals(listOf("image-1"),saved!!.uploaded);assertEquals(pending.messageId,saved!!.messageId)
        }
    }
    @Test fun pendingMessageCannotBeSentToAnotherComputer()=runBlocking {
        MockWebServer().use{server->val api=client(server);val p=Pending("stable-message-789","https://another.example","thread-1","hello",emptyList())
            try{MessageSender(api).send(p,{}, {byteArrayOf()});fail()}catch(_:IllegalArgumentException){};assertEquals(0,server.requestCount)
        }
    }
    @Test fun socketAuthenticatesInFirstFrameAndCarriesReplayCursor(){
        MockWebServer().use{server->val arrived=CountDownLatch(1);var auth:JSONObject?=null
            server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onMessage(webSocket:WebSocket,text:String){auth=JSONObject(text);webSocket.send("""{"type":"authenticated","data":{"lastSeq":42}}""")};override fun onClosing(webSocket:WebSocket,code:Int,reason:String){webSocket.close(code,reason)}}))
            val api=client(server);val ws=api.socket(37,{if(it.str("type")=="authenticated")arrived.countDown()},{})
            assertTrue(arrived.await(8,TimeUnit.SECONDS));assertEquals(37L,auth!!.getLong("after"));assertEquals("private-device-token",auth!!.getString("token"));assertNotNull(auth!!.opt("deviceSignature"));assertTrue(auth!!.getString("deviceSignature").isNotEmpty());val upgrade=server.takeRequest();assertEquals("/events",upgrade.path);assertNull(upgrade.getHeader("Authorization"));ws.close(1000,null)
        }
    }
    @Test fun signaturesBindTheExactBodyPathTokenAndServer()=runBlocking {
        MockWebServer().use{server->
            server.enqueue(MockResponse().setBody("{}"));val api=client(server)
            api.request("/api/requests/test/respond","POST",JSONObject().put("decision","accept"))
            val r=server.takeRequest();val body=r.body.readByteArray()
            val message=DeviceProof.text(api.session.token,r.method!!,r.getHeader("Host")!!,r.path!!,r.getHeader("x-device-time")!!,r.getHeader("x-device-nonce")!!,body)
            val signature=java.util.Base64.getDecoder().decode(r.getHeader("x-device-signature"))
            val verifier=java.security.Signature.getInstance("SHA256withECDSA");verifier.initVerify(identity.key.public);verifier.update(message);assertTrue(verifier.verify(signature))
            verifier.initVerify(identity.key.public);verifier.update(DeviceProof.text("stolen-other-token",r.method!!,r.getHeader("Host")!!,r.path!!,r.getHeader("x-device-time")!!,r.getHeader("x-device-nonce")!!,body));assertFalse(verifier.verify(signature))
        }
    }
    @Test fun pendingApprovalNeverProducesASession()=runBlocking {
        MockWebServer().use{server->
            server.enqueue(MockResponse().setResponseCode(202).setBody("""{"approvalRequired":true,"verificationCode":"ABCD-1234-5678-90EF"}"""))
            try{client(server).login("owner","password-for-test","phone");fail()}catch(e:DeviceApprovalRequired){assertEquals("ABCD-1234-5678-90EF",e.verificationCode)}
        }
    }

    @Test fun historyPreservesSafeImageReferencesAndKeepsTextSeparate(){
        val id="12345678-1234-1234-1234-123456789abc"
        val entry=BridgeCodec.item("t1",JSONObject("""{"id":"i1","type":"userMessage","content":[{"type":"text","text":"photo question"},{"type":"localImage","image":{"id":"$id","mime":"image/png","available":true}},{"type":"localImage","image":{"available":false}}]}"""))!!
        assertEquals("photo question",entry.text);assertEquals(2,entry.images.size);assertEquals(id,entry.images[0].id);assertTrue(entry.images[0].available);assertFalse(entry.images[1].available)
        val unsafe=BridgeCodec.item("t1",JSONObject("""{"id":"i2","type":"userMessage","content":[{"type":"localImage","image":{"id":"../../secrets","available":true}}]}"""))!!
        assertFalse(unsafe.images[0].available);assertEquals("",unsafe.images[0].id)
    }
    @Test fun imageDownloadUsesDeviceSignatureAndDoesNotAcceptPaths()=runBlocking {
        MockWebServer().use{server->
            val png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
            server.enqueue(MockResponse().addHeader("Content-Type","image/png").setBody(okio.Buffer().write(png)))
            val api=client(server);val id="12345678-1234-1234-1234-123456789abc"
            assertArrayEquals(png,api.image(id))
            val r=server.takeRequest();assertEquals("/api/uploads/$id",r.path);assertEquals("Bearer private-device-token",r.getHeader("Authorization"))
            val verifier=java.security.Signature.getInstance("SHA256withECDSA");verifier.initVerify(identity.key.public)
            verifier.update(DeviceProof.text(api.session.token,"GET",r.getHeader("Host")!!,r.path!!,r.getHeader("x-device-time")!!,r.getHeader("x-device-nonce")!!,ByteArray(0)))
            assertTrue(verifier.verify(java.util.Base64.getDecoder().decode(r.getHeader("x-device-signature"))))
            try{api.image("../../secret");fail()}catch(_:IllegalArgumentException){};assertEquals(1,server.requestCount)
        }
    }
    @Test fun missingImagesAndExternalRedirectsAreHandledWithoutLeakingCredentials()=runBlocking {
        MockWebServer().use{other->MockWebServer().use{server->
            val api=client(server);val id="12345678-1234-1234-1234-123456789abc"
            server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
            try{api.image(id);fail()}catch(e:BridgeException){assertEquals(404,e.status);assertTrue(e.message.contains("图片已不可用"))}
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location",other.url("/private")))
            try{api.image(id);fail()}catch(_:BridgeException){};assertEquals(0,other.requestCount)
            server.enqueue(MockResponse().setBody("<html>not an image</html>").addHeader("Content-Type","text/html"))
            try{api.image(id);fail()}catch(e:BridgeException){assertEquals("IMAGE_REQUIRED",e.code)}
        }}
    }

    @Test fun documentMetadataAndPendingUploadsSurviveSerialization(){
        val document=LocalDocument("12345678-1234-1234-1234-123456789abc","任务书.docx","application/octet-stream","document.docx",3)
        val pending=Pending("file-message-0001","https://computer.example","thread","read it",emptyList(),files=listOf(document),uploadedFiles=listOf(document.id))
        val restored=Pending.from(JSONObject(pending.json().toString()));assertEquals(document,restored.files.single());assertEquals(listOf(document.id),restored.uploadedFiles)
        val old=Pending.from(JSONObject("""{"messageId":"old-message-001","address":"http://localhost","threadId":"t","text":"hi","photos":[],"uploaded":[],"submitted":false}"""));assertTrue(old.files.isEmpty());assertTrue(old.uploadedFiles.isEmpty())
    }
    @Test fun documentUploadSignsFilenameAndBodyAndIncludesTheFileInTheMessage()=runBlocking {
        MockWebServer().use{server->
            val id="12345678-1234-1234-1234-123456789abc";val api=client(server)
            server.enqueue(MockResponse().setResponseCode(201).setBody(JSONObject().put("id",id).toString()));server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
            val document=LocalDocument(id,"任务书.docx","application/octet-stream","document.docx",3);var pending:Pending?=Pending("file-message-0002",api.session.address,"t","read it",emptyList(),files=listOf(document));val progress=mutableListOf<String>()
            MessageSender(api).send(pending!!,{pending=it},{byteArrayOf(1,2,3)},{progress.add(it)})
            val upload=server.takeRequest();assertEquals("/api/files",upload.requestUrl!!.encodedPath);assertEquals(document.name,upload.requestUrl!!.queryParameter("name"));assertArrayEquals(byteArrayOf(1,2,3),upload.body.clone().readByteArray())
            val verifier=java.security.Signature.getInstance("SHA256withECDSA");verifier.initVerify(identity.key.public);verifier.update(DeviceProof.text(api.session.token,"POST",upload.getHeader("Host")!!,upload.path!!,upload.getHeader("x-device-time")!!,upload.getHeader("x-device-nonce")!!,byteArrayOf(1,2,3)));assertTrue(verifier.verify(java.util.Base64.getDecoder().decode(upload.getHeader("x-device-signature"))))
            val message=JSONObject(server.takeRequest().body.readUtf8());assertEquals(id,message.getJSONArray("fileIds").getString(0));assertEquals("read it",message.getString("text"));assertNull(pending);assertTrue(progress.any{it.contains("100%")})
        }
    }
    @Test fun completedDocumentUploadIsNotRepeatedWhenMessageDeliveryIsRetried()=runBlocking {
        MockWebServer().use{server->
            val api=client(server);val id="12345678-1234-1234-1234-123456789abc";val document=LocalDocument(id,"notes.txt","text/plain","notes.txt",3)
            var pending:Pending?=Pending("file-message-0003",api.session.address,"t","read",emptyList(),files=listOf(document),uploadedFiles=listOf(id),submitted=true)
            server.enqueue(MockResponse().setResponseCode(202).setBody("{}"));MessageSender(api).send(pending!!,{pending=it},{error("should not upload again")})
            assertEquals(1,server.requestCount);assertEquals("/api/threads/t/messages",server.takeRequest().requestUrl!!.encodedPath);assertNull(pending)
        }
    }
    @Test fun preuploadedChatOnlyDraftSendsWithoutUploadingAgain()=runBlocking {
        MockWebServer().use{server->
            val api=client(server);val scope=documentUploadScope(api.session,"chat_only","r1")
            val document=LocalDocument("12345678-1234-1234-1234-123456789abc","笔记.txt","text/plain","private.txt",3,scope)
            val restored=LocalDocument.from(JSONObject(document.json().toString()));assertEquals(document,restored)
            server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
            MessageSender(api).send(Pending("pre-uploaded-message",api.session.address,"t","总结",emptyList(),files=listOf(restored),executionMode="chat_only",modeRevision="r1"),{},{error("Already uploaded")})
            assertEquals(1,server.requestCount);val request=server.takeRequest();assertEquals("/api/threads/t/messages",request.path)
            val body=JSONObject(request.body.readUtf8());assertEquals("r1",body.getString("modeRevision"));assertEquals(document.id,body.getJSONArray("fileIds").getString(0))
        }
    }
    @Test fun oldModeRevisionNeverReusesDraftUploadMarker()=runBlocking {
        MockWebServer().use{server->
            val api=client(server);val id="12345678-1234-1234-1234-123456789abc"
            val document=LocalDocument(id,"notes.txt","text/plain","private.txt",3,documentUploadScope(api.session,"chat_only","old"))
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"MODE_CHANGED","message":"Changed"}}"""))
            try{MessageSender(api).send(Pending("stale-upload-message",api.session.address,"t","read",emptyList(),files=listOf(document),executionMode="chat_only",modeRevision="new"),{},{byteArrayOf(1,2,3)});fail()}catch(e:BridgeException){assertEquals(409,e.status)}
            val request=server.takeRequest();assertEquals("/api/files",request.requestUrl!!.encodedPath);assertEquals("new",request.requestUrl!!.queryParameter("modeRevision"));assertEquals(1,server.requestCount)
        }
    }
    @Test fun fileCardsArePreservedInHistoryWithoutInventingImageAttachments(){
        val entry=BridgeCodec.item("t",JSONObject("""{"id":"u","type":"userMessage","content":[{"type":"text","text":"read"}],"files":[{"id":"f","name":"report.pdf","size":42,"available":true}]}"""))!!
        assertEquals("read",entry.text);assertTrue(entry.images.isEmpty());assertEquals("report.pdf",entry.files.single().name);assertTrue(entry.files.single().available)
    }

}
