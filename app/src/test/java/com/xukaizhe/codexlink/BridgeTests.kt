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
        assertEquals(listOf("first","[图片附件]","second"),entries.map{it.text});assertFalse(entries.any{it.text.contains("private")});assertFalse(entries.any{it.live})
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

}
