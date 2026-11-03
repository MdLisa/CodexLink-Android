package com.xukaizhe.codexlink

import org.json.JSONObject

data class SharedChatAccess(val supported:Boolean=false,val enabled:Boolean=false,val revision:String="") {
    companion object {
        fun fromStatus(status:JSONObject):SharedChatAccess {
            val access=status.optJSONObject("sharedChatAccess")?:return SharedChatAccess()
            val supported=access.optBoolean("supported")
            return SharedChatAccess(supported,supported&&access.optBoolean("enabled"),access.str("revision"))
        }
    }
}

internal fun chatCatalogPath(shared:Boolean)=if(shared)"/api/shared-chats/threads" else "/api/threads"
internal fun Chat.readPath():String {
    if(sharedReadOnly)require(validImageId(id)){"只读聊天编号无效。"}
    return chatCatalogPath(sharedReadOnly)+"/"+id
}
internal fun chatImagePath(id:String,sharedThreadId:String?=null):String {
    require(validImageId(id)){"图片编号无效。"}
    if(sharedThreadId==null)return "/api/uploads/$id"
    require(validImageId(sharedThreadId)){"只读聊天编号无效。"}
    return "/api/shared-chats/threads/$sharedThreadId/images/$id"
}
internal fun requireOwnChat(chat:Chat?) {
    require(chat?.sharedReadOnly!=true){"其他手机的会话仅供查看，不能发送消息或操作任务。"}
}
