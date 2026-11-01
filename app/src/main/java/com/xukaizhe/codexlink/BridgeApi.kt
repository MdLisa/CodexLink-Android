package com.xukaizhe.codexlink

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject.str(key: String, fallback: String = ""): String = if (isNull(key)) fallback else optString(key, fallback)
data class Session(val address: String, val token: String, val deviceId: String, val clockOffset: Long = 0)
data class Chat(val id: String, val name: String, val cwd: String, val control: String)
data class Entry(val id: String, val turnId: String, val role: String, val text: String, val detail: String = "", val live: Boolean = false)
data class Task(val id: String, val threadId: String, val state: String, val turnId: String, val error: String) {
    val active get() = state in setOf("queued", "starting", "running", "waitingApproval")
}
class BridgeException(val status: Int, val code: String, override val message: String) : IOException(message)
class DeviceApprovalRequired(val verificationCode: String) : IllegalStateException("请在电脑双击“审批手机.cmd”，批准后再点击登录。")
object BridgeCodec {
    fun address(input: String): String {
        val value = input.trim().let { if ("://" in it) it else "http://$it" }
        val url = try { value.toHttpUrl() } catch (_: Exception) { throw IllegalArgumentException("请输入有效电脑地址，例如 http://192.168.1.10:18766") }
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null && url.encodedPath == "/") { "只填写电脑地址和端口，不要带路径、账号或参数。" }
        return url.toString().trimEnd('/')
    }
    fun chat(j: JSONObject) = Chat(j.str("id"), j.str("name", "未命名聊天"), j.str("cwd"), j.str("control"))
    fun task(j: JSONObject) = Task(j.str("id"), j.str("threadId"), j.str("state"), j.str("turnId"), j.str("error"))
    fun item(turnId: String, j: JSONObject): Entry? {
        val key = "$turnId:${j.str("id")}"; val type = j.str("type")
        return when (type) {
            "agentMessage" -> Entry(key, turnId, "assistant", j.str("text"))
            "userMessage" -> Entry(key, turnId, "user", j.optJSONArray("content")?.objects()?.joinToString("\n") { if (it.str("type") == "text") it.str("text") else "[图片附件]" }.orEmpty())
            "commandExecution" -> Entry(key, turnId, "tool", when(j.str("status")) { "inProgress" -> "正在执行命令"; "failed" -> "命令执行失败"; "declined" -> "命令已拒绝"; else -> "命令执行结果" }, j.str("command") + "\n\n" + j.str("aggregatedOutput"))
            "fileChange" -> Entry(key, turnId, "tool", "文件修改 · ${j.str("status")}", j.optJSONArray("changes")?.toString(2).orEmpty())
            "mcpToolCall" -> Entry(key, turnId, "tool", "${j.str("server")} · ${j.str("tool")} · ${j.str("status")}", (j.opt("result") ?: j.opt("error"))?.toString().orEmpty())
            else -> null
        }
    }
    fun history(j: JSONObject): List<Entry> = j.optJSONArray("turns")?.objects().orEmpty().asReversed().flatMap { turn -> turn.optJSONArray("items")?.objects().orEmpty().mapNotNull { item(turn.str("id"), it) } }
}

class BridgeApi(val session: Session, val client: OkHttpClient = defaultClient(), val identity: DeviceIdentity = AndroidDeviceIdentity) {
    private var clockOffset=session.clockOffset
    private fun authority(url:HttpUrl):String { val host=if(url.host.contains(':')) "[${url.host}]" else url.host;return if(url.port==80||url.port==443)host else "$host:${url.port}" }
    suspend fun login(username:String,password:String,deviceName:String):Session = withContext(Dispatchers.IO) {
        val result=request("/api/login","POST",JSONObject().put("username",username).put("password",password).put("deviceName",deviceName).put("publicKey",identity.publicKey()),anonymous=true)
        if(result.optBoolean("approvalRequired"))throw DeviceApprovalRequired(result.getString("verificationCode"))
        Session(session.address,result.getString("token"),result.getJSONObject("device").getString("id"),clockOffset)
    }
    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
    suspend fun request(path: String, method: String = "GET", body: JSONObject? = null, bytes: ByteArray? = null, anonymous: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        val url=(session.address+path).toHttpUrl()
        val payload=if(method=="GET")ByteArray(0) else bytes?:(body?:JSONObject()).toString().toByteArray(Charsets.UTF_8)
        val builder=Request.Builder().url(url)
        if(!anonymous)builder.header("Authorization","Bearer "+session.token)
        if(!anonymous||path=="/api/login"){
            val target=url.encodedPath+(url.encodedQuery?.let{"?$it"}?:"")
            DeviceProof.create(identity,if(anonymous)"" else session.token,method,authority(url),target,payload,clockOffset).forEach{(key,value)->builder.header(key,value)}
        }
        if(method!="GET")builder.method(method,payload.toRequestBody((if(bytes!=null)"application/octet-stream" else "application/json; charset=utf-8").toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = try { JSONObject(text) } catch (_: Exception) { throw BridgeException(response.code, "INVALID_RESPONSE", "电脑没有返回有效数据，请检查地址是否指向 Codex 连接程序。") }
            if(json.has("serverTime"))clockOffset=json.optLong("serverTime")-System.currentTimeMillis()
            if (!response.isSuccessful) throw BridgeException(response.code, json.optJSONObject("error")?.str("code").orEmpty(), json.optJSONObject("error")?.str("message", "请求失败（${response.code}）") ?: "请求失败（${response.code}）")
            json
        }
    }
    fun socket(after: Long, event: (JSONObject) -> Unit, ended: (Int) -> Unit): WebSocket {
        val wsClient = client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
        return wsClient.newWebSocket(Request.Builder().url(session.address.replaceFirst("http", "ws") + "/events").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                try{
                    val proof=DeviceProof.create(identity,session.token,"WS",authority(session.address.toHttpUrl()),"/events",after.toString().toByteArray(Charsets.UTF_8),clockOffset)
                    webSocket.send(JSONObject().put("type","auth").put("token",session.token).put("after",after).put("deviceTime",proof["x-device-time"]).put("deviceNonce",proof["x-device-nonce"]).put("deviceSignature",proof["x-device-signature"]).toString())
                }catch(_:Exception){webSocket.cancel();ended(4001)}
            }
            override fun onMessage(webSocket: WebSocket, text: String) { try { event(JSONObject(text)) } catch (_: Exception) { webSocket.close(4002, "Invalid response") } }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); ended(code) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { ended(response?.code ?: 0) }
        })
    }
}
