package com.xukaizhe.codexlink

import org.json.JSONArray
import org.json.JSONObject

/** Persist progress before posting so a lost response can be retried with the same identity. */
class MessageSender(private val api: BridgeApi) {
    suspend fun send(initial: Pending, persist: suspend (Pending?) -> Unit, read: suspend (String) -> ByteArray, progress:(String)->Unit={}): JSONObject {
        require(initial.address == api.session.address) { "这条消息属于另一台电脑，请先切回原连接。" }
        var pending = initial
        while (pending.uploaded.size < pending.photos.size) {
            progress("上传图片 ${pending.uploaded.size+1}/${pending.photos.size}…")
            val image = read(pending.photos[pending.uploaded.size])
            val upload = api.request("/api/uploads", "POST", bytes = image)
            pending = pending.copy(uploaded = pending.uploaded + upload.getString("id"))
            persist(pending)
        }
        while(pending.uploadedFiles.size<pending.files.size){
            val document=pending.files[pending.uploadedFiles.size];var last=-1
            progress("正在上传："+document.name)
            val upload=api.uploadDocument(document,read(document.path)){sent,total->val percent=if(total>0)((sent*100)/total).toInt() else 100;if(percent!=last){last=percent;progress("上传 "+document.name+" · "+percent+"%")}}
            pending=pending.copy(uploadedFiles=pending.uploadedFiles+upload.getString("id"));persist(pending)
        }
        progress("正在发送消息…")
        pending = pending.copy(submitted = true)
        persist(pending)
        val result = api.request(
            "/api/threads/${pending.threadId}/messages", "POST",
            JSONObject().put("messageId", pending.messageId).put("text", pending.text).put("attachmentIds", JSONArray(pending.uploaded)).apply{if(pending.uploadedFiles.isNotEmpty())put("fileIds",JSONArray(pending.uploadedFiles))}
        )
        persist(null)
        return result
    }
}
