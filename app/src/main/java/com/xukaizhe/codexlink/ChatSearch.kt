package com.xukaizhe.codexlink

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Search all catalog pages the server permits, including empty filtered pages. */
internal suspend fun BridgeApi.searchChatCatalog(query: String): List<Chat> {
    val term = query.trim()
    if (term.isEmpty()) return emptyList()
    val matches = linkedMapOf<String, Chat>()
    val cursors = mutableSetOf<String>()
    var cursor = ""
    do {
        currentCoroutineContext().ensureActive()
        val url = session.address.toHttpUrl().newBuilder()
            .addPathSegment("api").addPathSegment("threads")
            .apply { if (cursor.isNotEmpty()) addQueryParameter("cursor", cursor) }
            .build()
        val result = request(url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: ""))
        currentCoroutineContext().ensureActive()
        for (chat in result.optJSONArray("threads")?.objects().orEmpty().map(BridgeCodec::chat)) {
            if (chat.name.contains(term, ignoreCase = true) || chat.cwd.contains(term, ignoreCase = true)) {
                matches[chat.id] = chat
            }
        }
        cursor = result.str("nextCursor")
        check(cursor.isEmpty() || cursors.add(cursor)) { "电脑返回了重复分页，请刷新后重试。" }
    } while (cursor.isNotEmpty())
    return matches.values.toList()
}
