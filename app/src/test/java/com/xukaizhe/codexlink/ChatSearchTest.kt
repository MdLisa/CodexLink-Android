package com.xukaizhe.codexlink

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatSearchTest {
    private fun client(server: MockWebServer) = BridgeApi(
        Session(server.url("/").toString().trimEnd('/'), "search-token", "phone"),
        identity = TestDeviceIdentity(),
    )
    private fun page(vararg chats: Pair<String, String>, cursor: String? = null): MockResponse {
        val entries = chats.map { (id, name) -> JSONObject().put("id", id).put("name", name).put("cwd", "D:/Projects/Android") }
        return MockResponse().setBody(JSONObject().put("threads", JSONArray(entries)).put("nextCursor", cursor ?: JSONObject.NULL).toString())
    }

    @Test fun findsOlderChatsPastEmptyPagesAndEncodesSignedCursors() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(page("first" to "其他聊天", cursor = "next +/&?中文"))
            server.enqueue(page(cursor = "third"))
            server.enqueue(page("older" to "高中数学", "another" to "数学练习"))
            assertEquals(listOf("older", "another"), client(server).searchChatCatalog(" 数学 ").map { it.id })
            val first = server.takeRequest()
            assertEquals("/api/threads", first.path)
            assertEquals("Bearer search-token", first.getHeader("Authorization"))
            assertNotNull(first.getHeader("x-device-signature"))
            val second = server.takeRequest()
            assertEquals("next +/&?中文", second.requestUrl!!.queryParameter("cursor"))
            assertEquals(1, second.requestUrl!!.queryParameterNames.size)
            assertNotNull(second.getHeader("x-device-signature"))
            assertEquals("third", server.takeRequest().requestUrl!!.queryParameter("cursor"))
        }
    }

    @Test fun matchesProjectPathsIgnoresCaseAndDeduplicatesChats() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(page("same" to "一个聊天", cursor = "next"))
            server.enqueue(page("same" to "更新后的名称", "other" to "另一个聊天"))
            val results = client(server).searchChatCatalog("projects/android")
            assertEquals(listOf("same", "other"), results.map { it.id })
            assertEquals("更新后的名称", results.first().name)
        }
    }

    @Test fun blankSearchDoesNotContactComputer() = runBlocking {
        MockWebServer().use { server ->
            assertTrue(client(server).searchChatCatalog("  ").isEmpty())
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun repeatedCursorFailsInsteadOfLoopingOrReportingIncompleteResults() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(page(cursor = "same")); server.enqueue(page(cursor = "same"))
            try { client(server).searchChatCatalog("数学"); fail("Repeated cursor must fail") }
            catch (e: IllegalStateException) { assertTrue(e.message!!.contains("重复分页")) }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun pageFailureDoesNotBecomeAnEmptySuccessfulSearch() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(page("match" to "数学", cursor = "next"))
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"CODEX_OFFLINE","message":"后台已断开"}}"""))
            try { client(server).searchChatCatalog("数学"); fail("Incomplete search must fail") }
            catch (e: BridgeException) { assertEquals(503, e.status) }
        }
    }

    @Test fun cancellationDoesNotContinueFetchingPages() = runBlocking {
        MockWebServer().use { server ->
            lateinit var search: Job
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    search.cancel()
                    return page(cursor = "must-not-fetch")
                }
            }
            search = launch(start = CoroutineStart.LAZY) { client(server).searchChatCatalog("数学") }
            search.start(); search.join()
            assertTrue(search.isCancelled)
            assertEquals(1, server.requestCount)
        }
    }
}
