package com.xukaizhe.codexlink

import org.json.JSONArray
import org.json.JSONObject

/** Persist progress before posting so a lost response can be retried with the same identity. */
class MessageSender(private val api: BridgeApi) {
    suspend fun send(initial: Pending, persist: suspend (Pending?) -> Unit, read: suspend (String) -> ByteArray): JSONObject {
        require(initial.address == api.session.address) { "这条消息属于另一台电脑，请先切回原连接。" }
        var pending = initial
        while (pending.uploaded.size < pending.photos.size) {
            val image = read(pending.photos[pending.uploaded.size])
            val upload = api.request("/api/uploads", "POST", bytes = image)
            pending = pending.copy(uploaded = pending.uploaded + upload.getString("id"))
            persist(pending)
        }
        pending = pending.copy(submitted = true)
        persist(pending)
        val result = api.request(
            "/api/threads/${pending.threadId}/messages", "POST",
            JSONObject().put("messageId", pending.messageId).put("text", pending.text).put("attachmentIds", JSONArray(pending.uploaded))
        )
        persist(null)
        return result
    }
}
