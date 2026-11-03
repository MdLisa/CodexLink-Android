package com.xukaizhe.codexlink

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.Signature
import java.util.Base64

class SharedChatsTest {
    private val thread="12345678-1234-1234-1234-123456789abc"
    private val image="22345678-1234-1234-1234-123456789abc"
    @Test fun oldBackendsAndDisabledPermissionsNeverEnableSharedBrowsing() {
        assertFalse(SharedChatAccess.fromStatus(JSONObject("""{"version":"0.8.0"}""")).enabled)
        assertFalse(SharedChatAccess.fromStatus(JSONObject("""{"sharedChatAccess":{"enabled":true}}""")).enabled)
        assertFalse(SharedChatAccess.fromStatus(JSONObject("""{"sharedChatAccess":{"supported":true,"enabled":false}}""")).enabled)
        assertTrue(SharedChatAccess.fromStatus(JSONObject("""{"sharedChatAccess":{"supported":true,"enabled":true,"revision":"new"}}""")).enabled)
    }
    @Test fun sharedChatsPreserveOwnershipAndCannotBeUsedForMutations() {
        val shared=BridgeCodec.chat(JSONObject("""{"id":"$thread","name":"数学","control":"sharedReadOnly","owner":{"name":"妹妹的手机","code":"ABCDEF12"}}"""))
        assertTrue(shared.sharedReadOnly);assertEquals("妹妹的手机 · ABCDEF12",shared.ownerLabel)
        assertEquals("/api/shared-chats/threads/$thread",shared.readPath())
        try { requireOwnChat(shared);fail("Shared chat must reject mutations") } catch(_:IllegalArgumentException) {}
        val own=BridgeCodec.chat(JSONObject("""{"id":"ordinary","control":"readOnly"}"""))
        requireOwnChat(own);assertFalse(own.sharedReadOnly);assertEquals("/api/threads/ordinary",own.readPath())
        try { chatImagePath(image,"../../private");fail() } catch(_:IllegalArgumentException) {}
    }
    @Test fun sharedImageUsesItsOwnSignedRouteAndNeverFallsBackToOwnerUploads()=runBlocking {
        MockWebServer().use { server ->
            val identity=TestDeviceIdentity();val api=BridgeApi(Session(server.url("/").toString().trimEnd('/'),"token","viewer"),identity=identity)
            server.enqueue(MockResponse().addHeader("Content-Type","image/png").setBody(okio.Buffer().write(byteArrayOf(1,2,3))))
            assertArrayEquals(byteArrayOf(1,2,3),api.image(image,thread))
            val request=server.takeRequest();assertEquals("/api/shared-chats/threads/$thread/images/$image",request.path)
            val verifier=Signature.getInstance("SHA256withECDSA");verifier.initVerify(identity.key.public)
            verifier.update(DeviceProof.text("token","GET",request.getHeader("Host")!!,request.path!!,request.getHeader("x-device-time")!!,request.getHeader("x-device-nonce")!!,ByteArray(0)))
            assertTrue(verifier.verify(Base64.getDecoder().decode(request.getHeader("x-device-signature"))))
            server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
            try { api.image(image,thread);fail() } catch(e:BridgeException) { assertEquals(403,e.status) }
            assertEquals(request.path,server.takeRequest().path);assertEquals(2,server.requestCount)
        }
    }
    @Test fun sharedSearchMatchesOwnerAndPaginatesWithoutMixingOwnChats()=runBlocking {
        MockWebServer().use { server ->
            fun page(id:String,cursor:String?=null)=MockResponse().setBody(JSONObject().put("threads",JSONArray().put(JSONObject().put("id",id).put("name","数学作业").put("control","sharedReadOnly").put("owner",JSONObject().put("name","妹妹的手机").put("code","ABCDEF12")))).put("nextCursor",cursor?:JSONObject.NULL).toString())
            server.enqueue(page(thread,"next +/中文"));server.enqueue(page(image))
            val api=BridgeApi(Session(server.url("/").toString().trimEnd('/'),"token","viewer"),identity=TestDeviceIdentity())
            assertEquals(2,api.searchChatCatalog("妹妹",shared=true).size)
            assertEquals("/api/shared-chats/threads",server.takeRequest().path)
            val next=server.takeRequest();assertEquals("/api/shared-chats/threads",next.requestUrl!!.encodedPath);assertEquals("next +/中文",next.requestUrl!!.queryParameter("cursor"));assertNotNull(next.getHeader("x-device-signature"))
        }
    }
}
