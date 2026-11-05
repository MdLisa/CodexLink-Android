package com.xukaizhe.codexlink

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject.str(key: String, fallback: String = ""): String = if (isNull(key)) fallback else optString(key, fallback)
data class Session(val address: String, val token: String, val deviceId: String, val clockOffset: Long = 0)
data class Chat(val id: String, val name: String, val cwd: String, val control: String, val ownerName:String="",val ownerCode:String="") {
    val sharedReadOnly get()=control=="sharedReadOnly"
    val ownerLabel get()=listOf(ownerName.ifBlank{"其他手机"},ownerCode).filter{it.isNotBlank()}.joinToString(" · ")
}
data class ImageAttachment(val id: String, val mime: String = "", val available: Boolean = false)
data class Entry(val id: String, val turnId: String, val role: String, val text: String, val detail: String = "", val live: Boolean = false, val images: List<ImageAttachment> = emptyList(),val files:List<FileAttachment> = emptyList(),val startedAtMs:Long?=null,val completedAtMs:Long?=null,val generatedFiles:List<GeneratedDocument> = emptyList())
fun validImageId(id: String) = Regex("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}").matches(id)
data class Task(val id: String, val threadId: String, val state: String, val turnId: String, val error: String, val approvalsReviewer: String = "") {
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
    fun chat(j: JSONObject) = Chat(j.str("id"), j.str("name", "未命名聊天"), j.str("cwd"), j.str("control"),j.optJSONObject("owner")?.str("name").orEmpty(),j.optJSONObject("owner")?.str("code").orEmpty())
    fun task(j: JSONObject) = Task(j.str("id"), j.str("threadId"), j.str("state"), j.str("turnId"), j.str("error"), j.str("approvalsReviewer"))
    fun item(turnId: String, j: JSONObject): Entry? {
        val key = "$turnId:${j.str("id")}"; val type = j.str("type")
        val entry=when (type) {
            "agentMessage" -> Entry(key, turnId, "assistant", j.str("text"))
            "userMessage" -> {
                val content=j.optJSONArray("content")?.objects().orEmpty()
                val images=content.filter{it.str("type")!="text"}.map{part->
                    val image=part.optJSONObject("image");val id=image?.str("id").orEmpty()
                    ImageAttachment(if(validImageId(id))id else "",image?.str("mime").orEmpty(),validImageId(id)&&image?.optBoolean("available")==true)
                }
                Entry(key,turnId,"user",content.filter{it.str("type")=="text"}.joinToString("\n"){it.str("text")},images=images,files=j.optJSONArray("files")?.objects().orEmpty().map{FileAttachment(it.str("id"),it.str("name","附件"),it.optLong("size"),it.optBoolean("available"))})
            }
            "commandExecution" -> Entry(key, turnId, "tool", when(j.str("status")) { "inProgress" -> "正在执行命令"; "failed" -> "命令执行失败"; "declined" -> "命令已拒绝"; else -> "命令执行结果" }, j.str("command") + "\n\n" + j.str("aggregatedOutput"))
            "fileChange" -> Entry(key, turnId, "tool", "文件修改 · ${j.str("status")}", j.optJSONArray("changes")?.toString(2).orEmpty())
            "mcpToolCall" -> Entry(key, turnId, "tool", "${j.str("server")} · ${j.str("tool")} · ${j.str("status")}", (j.opt("result") ?: j.opt("error"))?.toString().orEmpty())
            "webSearch" -> Entry(key, turnId, "tool", "联网搜索", j.str("query"))
            else -> null
        }
        return entry?.copy(generatedFiles=if(type=="agentMessage")j.optJSONArray("generatedFiles")?.objects().orEmpty().map(GeneratedDocument::from) else emptyList(),startedAtMs=j.messageMillis("startedAtMs"),completedAtMs=j.messageMillis("completedAtMs"))
    }
    fun history(j: JSONObject): List<Entry> = j.optJSONArray("turns")?.objects().orEmpty().asReversed().flatMap { turn -> turn.optJSONArray("items")?.objects().orEmpty().mapNotNull { item(turn.str("id"), it) } }
}

class BridgeApi(val session: Session, val client: OkHttpClient = defaultClient(), val identity: DeviceIdentity = AndroidDeviceIdentity) {
    @Volatile private var clockOffset=session.clockOffset
    private fun authority(url:HttpUrl):String { val host=if(url.host.contains(':')) "[${url.host}]" else url.host;return if(url.port==80||url.port==443)host else "$host:${url.port}" }
    suspend fun login(username:String,password:String,deviceName:String):Session = withContext(Dispatchers.IO) {
        val result=request("/api/login","POST",JSONObject().put("username",username).put("password",password).put("deviceName",deviceName).put("publicKey",identity.publicKey()),anonymous=true)
        if(result.optBoolean("approvalRequired"))throw DeviceApprovalRequired(result.getString("verificationCode"))
        Session(session.address,result.getString("token"),result.getJSONObject("device").getString("id"),clockOffset)
    }
    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
    internal fun generatedDownloadRequest(id:String):Request {
        require(validImageId(id)){"文档编号无效。"}
        val path="/api/generated-files/$id";val url=(session.address+path).toHttpUrl()
        val builder=Request.Builder().url(url).header("Authorization","Bearer "+session.token)
        DeviceProof.create(identity,session.token,"GET",authority(url),path,ByteArray(0),clockOffset).forEach{(k,v)->builder.header(k,v)}
        return builder.build()
    }
    suspend fun image(id: String,sharedThreadId:String?=null): ByteArray = withContext(Dispatchers.IO) {
        val path=chatImagePath(id,sharedThreadId);val url=(session.address+path).toHttpUrl()
        val builder=Request.Builder().url(url).header("Authorization","Bearer "+session.token)
        DeviceProof.create(identity,session.token,"GET",authority(url),url.encodedPath,ByteArray(0),clockOffset).forEach{(key,value)->builder.header(key,value)}
        client.newCall(builder.build()).execute().use{response->
            if(!response.isSuccessful){
                val message=when(response.code){401,403->"登录已失效，请重新连接电脑。";404->"图片已不可用，电脑上的原图可能已删除。";else->"图片加载失败（${response.code}），请重试。"}
                throw BridgeException(response.code,"IMAGE_LOAD_FAILED",message)
            }
            val mime=response.header("Content-Type").orEmpty().substringBefore(';').lowercase()
            if(mime !in setOf("image/png","image/jpeg","image/webp","image/gif"))throw BridgeException(response.code,"IMAGE_REQUIRED","电脑返回的内容不是图片。")
            val body=response.body?:throw IOException("图片内容为空。")
            val limit=10*1024*1024
            if(body.contentLength()>limit)throw IOException("图片超过 10 MB。")
            val bytes=ByteArrayOutputStream();val buffer=ByteArray(8192)
            body.byteStream().use{input->while(true){val count=input.read(buffer);if(count<0)break;if(bytes.size()+count>limit)throw IOException("图片超过 10 MB。");bytes.write(buffer,0,count)}}
            bytes.toByteArray()
        }
    }
    suspend fun request(path: String, method: String = "GET", body: JSONObject? = null, bytes: ByteArray? = null, anonymous: Boolean = false, uploadProgress:((Long,Long)->Unit)?=null): JSONObject = withContext(Dispatchers.IO) {
        val url=(session.address+path).toHttpUrl()
        val payload=if(method=="GET")ByteArray(0) else bytes?:(body?:JSONObject()).toString().toByteArray(Charsets.UTF_8)
        val builder=Request.Builder().url(url)
        if(!anonymous)builder.header("Authorization","Bearer "+session.token)
        if(!anonymous||path=="/api/login"){
            val target=url.encodedPath+(url.encodedQuery?.let{"?$it"}?:"")
            DeviceProof.create(identity,if(anonymous)"" else session.token,method,authority(url),target,payload,clockOffset).forEach{(key,value)->builder.header(key,value)}
        }
        if(method!="GET"){
            val original=payload.toRequestBody((if(bytes!=null)"application/octet-stream" else "application/json; charset=utf-8").toMediaType())
            val requestBody=if(uploadProgress==null)original else object:RequestBody(){
                override fun contentType()=original.contentType()
                override fun contentLength()=original.contentLength()
                override fun writeTo(sink:BufferedSink){var sent=0L;val counting=object:ForwardingSink(sink){override fun write(source:okio.Buffer,byteCount:Long){super.write(source,byteCount);sent+=byteCount;uploadProgress.invoke(sent,payload.size.toLong())}}.buffer();original.writeTo(counting);counting.flush()}
            }
            builder.method(method,requestBody)
        }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = try { JSONObject(text) } catch (_: Exception) { throw BridgeException(response.code, "INVALID_RESPONSE", "电脑没有返回有效数据，请检查地址是否指向 Codex 连接程序。") }
            if(json.has("serverTime"))clockOffset=json.optLong("serverTime")-System.currentTimeMillis()
            if (!response.isSuccessful) throw BridgeException(response.code, json.optJSONObject("error")?.str("code").orEmpty(), json.optJSONObject("error")?.str("message", "请求失败（${response.code}）") ?: "请求失败（${response.code}）")
            json
        }
    }
    suspend fun uploadDocument(file:LocalDocument,bytes:ByteArray,progress:((Long,Long)->Unit)?=null,modeRevision:String=""):JSONObject {
        require(validImageId(file.id)&&bytes.size.toLong()==file.size&&file.size<=DOCUMENT_LIMIT){"附件内容已改变或超过 10 MB，请重新选择。"}
        val url=session.address.toHttpUrl().newBuilder().addPathSegment("api").addPathSegment("files").addQueryParameter("id",file.id).addQueryParameter("name",file.name).addQueryParameter("mime",file.mime).apply{if(modeRevision.isNotEmpty())addQueryParameter("modeRevision",modeRevision)}.build()
        return request(url.encodedPath+"?"+url.encodedQuery,"POST",bytes=bytes,uploadProgress=progress)
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
