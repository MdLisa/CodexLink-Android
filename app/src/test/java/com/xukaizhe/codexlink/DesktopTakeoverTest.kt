package com.xukaizhe.codexlink

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DesktopTakeoverTest {
    private val plan = TakeoverPlan("original", "12345678-1111-2222-3333-444444444444", 1000, 1)
    private fun client(server: MockWebServer) = BridgeApi(
        Session(server.url("/").toString().trimEnd('/'), "token", "phone"), identity = TestDeviceIdentity(),
    )

    @Test fun preparationRequiresAnOpaqueConfirmationId() {
        try { TakeoverPlan.from(JSONObject("""{"threadId":"thread","confirmationId":"bad"}""")); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(plan, TakeoverPlan.from(JSONObject().put("threadId", plan.threadId).put("confirmationId", plan.confirmationId).put("expiresAt", 1000).put("desktopCount", 1)))
    }

    @Test fun confirmedOperationPollsOriginalChatWithoutSendingAnyMessage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(202).setBody("""{"state":"checking"}"""))
            server.enqueue(MockResponse().setBody("""{"state":"closingDesktop"}"""))
            server.enqueue(MockResponse().setBody("""{"state":"acquiring"}"""))
            server.enqueue(MockResponse().setBody("""{"state":"succeeded","threadId":"original"}"""))
            val progress = mutableListOf<String>()
            assertEquals("succeeded", client(server).confirmDesktopTakeover(plan, progress::add, pause = {}).str("state"))
            val first = server.takeRequest()
            assertEquals("POST", first.method)
            assertEquals(plan.confirmationId, JSONObject(first.body.readUtf8()).str("confirmationId"))
            repeat(3) { val request = server.takeRequest(); assertEquals("GET", request.method); assertEquals("/api/threads/original/takeover/${plan.confirmationId}", request.path) }
            assertEquals(2, progress.size)
        }
    }

    @Test fun lostConfirmationResponseNeverResendsDesktopClose() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setBody("""{"state":"succeeded"}"""))
            assertEquals("succeeded", client(server).confirmDesktopTakeover(plan, {}, pause = {}).str("state"))
            assertEquals("POST", server.takeRequest().method)
            assertEquals("GET", server.takeRequest().method)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun rejectedConfirmationDoesNotPollOrRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"TAKEOVER_DENIED","message":"not allowed"}}"""))
            try { client(server).confirmDesktopTakeover(plan, {}, pause = {}); fail() }
            catch (e: BridgeException) { assertEquals(403, e.status) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun failedGracefulExitIsShownAsFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
            server.enqueue(MockResponse().setBody("""{"state":"failed","error":"desktop did not exit"}"""))
            try { client(server).confirmDesktopTakeover(plan, {}, pause = {}); fail() }
            catch (e: IllegalStateException) { assertEquals("desktop did not exit", e.message) }
            assertEquals(2, server.requestCount)
        }
    }
}
