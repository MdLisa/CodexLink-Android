package com.xukaizhe.codexlink

import android.app.Application
import android.net.Uri
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.WebSocket
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ScreenState(
    val deviceApprovalCode: String? = null, val loginAddress: String = "", val loading: Boolean = true, val busy: Boolean = false, val session: Session? = null,
    val chats: List<Chat> = emptyList(), val chatCursor: String = "", val selected: Chat? = null,
    val entries: List<Entry> = emptyList(), val tasks: List<Task> = emptyList(), val requests: List<JSONObject> = emptyList(),
    val historyCursor: String = "", val workspaces: List<String> = emptyList(), val connection: String = "尚未连接",
    val online: Boolean = false, val error: String? = null, val notice: String? = null,
    val draft: String = "", val photos: List<Photo> = emptyList(), val pending: Pending? = null,
    val devices: List<JSONObject> = emptyList()
)
class BridgeViewModel(application: Application) : AndroidViewModel(application) {
    private val _state=MutableStateFlow(ScreenState());val state=_state.asStateFlow()
    private val store=LocalStore(application);private var api: BridgeApi?=null
    private val ready=CompletableDeferred<Unit>();private val refreshLock=Mutex()
    private var foreground=false;private var observer: Job?=null;private var socket: WebSocket?=null
    private var socketGeneration=0;private var lastSeq=0L;private var live=false;private var refreshJob: Job?=null
    private val drafts=mutableMapOf<String,Pair<String,List<Photo>>>()
    private val imageCache=object:LruCache<String,ByteArray>(16*1024*1024){override fun sizeOf(key:String,value:ByteArray)=value.size}
    suspend fun loadImage(id:String):ByteArray {
        val client=api?:throw IllegalStateException("请先连接电脑。")
        val key=client.session.address+"|"+client.session.deviceId+"|"+id
        imageCache.get(key)?.let{return it}
        val bytes=client.image(id)
        check(client===api){"连接已更换，请重新打开图片。"}
        imageCache.put(key,bytes);return bytes
    }
    init { viewModelScope.launch {
        try { val saved=withContext(Dispatchers.IO){store.load()};api=saved.session?.let(::BridgeApi);_state.update{it.copy(loading=false,session=saved.session,loginAddress=saved.session?.address.orEmpty(),pending=saved.pending)} }
        catch(_:Exception){withContext(Dispatchers.IO){store.clear()};_state.update{it.copy(loading=false,error="本机连接信息无法读取，请重新登录；之前发给电脑的任务不会因此停止。")}}
        ready.complete(Unit);if(foreground)startObserving()
    } }
    fun startObserving(){foreground=true;if(!ready.isCompleted||observer?.isActive==true||api==null)return
        val generation=++socketGeneration
        observer=viewModelScope.launch {
            var ownSocket:WebSocket?=null
            refreshQuiet(true)
            val poll=launch { while(isActive){delay(6000);refreshQuiet(false,history=!live)} }
            var retry=1000L
            try { while(isActive&&api!=null){
                val ended=CompletableDeferred<Int>();live=false
                _state.update{it.copy(connection=if(retry==1000L)"连接中" else "正在重新连接")}
                ownSocket=api!!.socket(lastSeq,{ event->viewModelScope.launch { if(generation==socketGeneration)handleEvent(event) } },{code->ended.complete(code)});socket=ownSocket
                val code=ended.await();if(generation!=socketGeneration)break
                live=false;_state.update{it.copy(connection="连接中断，正在重试")}
                if(code in setOf(4001,4003,401)){expired();break}
                delay(retry);retry=(retry*2).coerceAtMost(15000)
            } } finally {poll.cancel();ownSocket?.cancel();if(generation==socketGeneration)live=false}
        }
    }
    fun stopObserving(){foreground=false;socketGeneration++;observer?.cancel();observer=null;socket?.cancel();socket=null;live=false}
    private suspend fun expired(){api=null;imageCache.evictAll();withContext(Dispatchers.IO){store.session(null)};_state.update{it.copy(loginAddress=it.session?.address?:it.loginAddress,session=null,online=false,connection="需要重新登录",error="登录已失效，请重新输入账号密码。")}}
    private fun action(block:suspend()->Unit){if(_state.value.busy)return;viewModelScope.launch{_state.update{it.copy(busy=true,error=null,notice=null)};try{ready.await();block()}catch(e:CancellationException){throw e}catch(e:Exception){failure(e)}finally{_state.update{it.copy(busy=false)}}}}
    private suspend fun failure(e:Exception){if(e is DeviceApprovalRequired){_state.update{it.copy(deviceApprovalCode=e.verificationCode,error=null)};return;};if(e is BridgeException&&e.status==401&&_state.value.session!=null){expired();return};_state.update{it.copy(error=when(e){is BridgeException,is IllegalArgumentException,is IllegalStateException->e.message;else->"连接电脑失败，请确认电脑后台正在运行，手机与电脑网络互通。未确认的消息可用原编号重试。"})}}
    fun dismissError(){_state.update{it.copy(error=null,notice=null)}}
    fun login(address:String,username:String,password:String)=action {
        val normalized=BridgeCodec.address(address);require(username.trim().isNotEmpty()&&password.isNotEmpty()){ "请输入账号和密码。" }
        val client=BridgeApi(Session(normalized,"",""));val health=client.request("/health",anonymous=true)
        require(health.str("service")=="codex-phone-bridge"){"这个地址不是 Codex 电脑连接程序。"}
        require(health.str("authMode")=="password"&&health.optBoolean("deviceSecurity")){"请先更新电脑后台，启用账号密码登录。"}
        val session=client.login(username.trim(),password,android.os.Build.MODEL)
        val resume=foreground;stopObserving();foreground=resume;imageCache.evictAll();withContext(Dispatchers.IO){store.session(session)};api=BridgeApi(session);lastSeq=0
        _state.update{ScreenState(loading=false,busy=true,session=session,loginAddress=normalized,pending=it.pending,notice="登录成功")};if(foreground)startObserving()
    }
    private suspend fun refreshQuiet(list:Boolean,history:Boolean=true){try{refresh(list,history)}catch(e:CancellationException){throw e}catch(e:Exception){if(e is BridgeException&&e.status==401)expired() else _state.update{it.copy(connection="无法刷新，正在重试")}}}
    private suspend fun refresh(list:Boolean,history:Boolean=true)=refreshLock.withLock {
        val client=api?:return@withLock;val status=client.request("/api/status")
        if(client!==api)return@withLock
        _state.update{it.copy(online=status.optBoolean("online"),requests=status.optJSONArray("requests")?.objects().orEmpty(),workspaces=status.optJSONArray("workspaces")?.let{a->(0 until a.length()).map{a.getString(it)}}.orEmpty(),connection=if(live)"实时连接" else "已连接电脑")}
        if(!status.optBoolean("online"))return@withLock
        if(list){val catalog=client.request("/api/threads");if(client===api)_state.update{it.copy(chats=catalog.optJSONArray("threads")?.objects().orEmpty().map(BridgeCodec::chat),chatCursor=catalog.str("nextCursor"))}}
        val selected=_state.value.selected?:return@withLock
        val jobs=client.request("/api/threads/${selected.id}/jobs")
        if(client!==api||_state.value.selected?.id!=selected.id)return@withLock
        _state.update{it.copy(tasks=jobs.optJSONArray("jobs")?.objects().orEmpty().map(BridgeCodec::task))}
        if(history){val result=client.request("/api/threads/${selected.id}/history?limit=20");if(client!==api||_state.value.selected?.id!=selected.id)return@withLock
            val fresh=BridgeCodec.history(result);_state.update{s->
                val running=s.tasks.filter{it.active}.map{it.turnId}.toSet()
                val overlay=s.entries.filter{it.live&&it.turnId in running}
                val merged=fresh.map{entry->overlay.find{it.id==entry.id&&it.text.length>entry.text.length}?:entry}+overlay.filter{old->fresh.none{it.id==old.id}}
                s.copy(entries=merged,historyCursor=result.str("nextCursor"))
            }
        }
    }
    fun refreshNow()=action{refresh(true);_state.update{it.copy(notice="已刷新电脑上的聊天记录")}}
    fun moreChats()=action{val cursor=_state.value.chatCursor;if(cursor.isNotEmpty()){val result=api!!.request("/api/threads?cursor="+encode(cursor));_state.update{it.copy(chats=(it.chats+result.optJSONArray("threads")?.objects().orEmpty().map(BridgeCodec::chat)).distinctBy(Chat::id),chatCursor=result.str("nextCursor"))}}}
    fun open(chat:Chat)=action{openInternal(chat)}
    private suspend fun openInternal(chat:Chat){rememberDraft();val draft=drafts[chat.id];_state.update{it.copy(selected=chat,entries=emptyList(),tasks=emptyList(),historyCursor="",draft=draft?.first.orEmpty(),photos=draft?.second.orEmpty())};withContext(Dispatchers.IO){store.lastChat(chat.id)};refresh(false)}
    private fun rememberDraft(){_state.value.selected?.id?.let{drafts[it]=_state.value.draft to _state.value.photos}}
    fun back(){rememberDraft();_state.update{it.copy(selected=null,entries=emptyList(),tasks=emptyList(),draft="",photos=emptyList())}}
    fun create(name:String,cwd:String)=action{val result=api!!.request("/api/threads","POST",JSONObject().put("name",name.ifBlank{"手机聊天"}).put("cwd",cwd));val chat=BridgeCodec.chat(result);_state.update{it.copy(chats=listOf(chat)+it.chats)};openInternal(chat)}
    fun moreHistory()=action{val chat=_state.value.selected?:return@action;val cursor=_state.value.historyCursor;if(cursor.isNotEmpty()){val result=api!!.request("/api/threads/${chat.id}/history?limit=20&cursor="+encode(cursor));if(_state.value.selected?.id==chat.id)_state.update{it.copy(entries=(BridgeCodec.history(result)+it.entries).distinctBy(Entry::id),historyCursor=result.str("nextCursor"))}}}
    fun draft(text:String){if(text.length<=64000)_state.update{it.copy(draft=text)}}
    fun removePhoto(photo:Photo){_state.update{it.copy(photos=it.photos-photo)}}
    fun addPhotos(uris:List<Uri>)=action{val count=8-_state.value.photos.size;val photos=withContext(Dispatchers.IO){uris.take(count).map{preparePhoto(getApplication(),it)}};_state.update{it.copy(photos=it.photos+photos)}}
    private suspend fun persist(p:Pending?){withContext(Dispatchers.IO){store.pending(p)};_state.update{it.copy(pending=p)}}
    fun send()=action {
        val s=_state.value;val chat=s.selected?:return@action;val client=api?:return@action
        val pending=s.pending?:run{require(s.draft.isNotBlank()||s.photos.isNotEmpty()){"请输入文字或选择图片。"};val created=Pending(UUID.randomUUID().toString(),client.session.address,chat.id,s.draft,s.photos.map(Photo::path));persist(created);created}
        require(pending.address==client.session.address&&pending.threadId==chat.id){"还有一条未确认的消息，请先打开它所属的聊天重试，或刷新检查结果。"}
        MessageSender(client).send(pending,::persist){file->withContext(Dispatchers.IO){File(file).readBytes()}}
        drafts.remove(chat.id);_state.update{it.copy(draft="",photos=emptyList(),notice="已交给电脑")};refreshQuiet(true)
    }
    fun openPending()=action{val p=_state.value.pending?:return@action;require(p.address==api?.session?.address){"这条未确认消息属于另一台电脑。"};val chat=_state.value.chats.find{it.id==p.threadId}?:BridgeCodec.chat(api!!.request("/api/threads/${p.threadId}"));openInternal(chat)}
    fun abandonPending()=action{persist(null);_state.update{it.copy(notice="已放弃本机重试；电脑上已经收到的任务仍会继续。")}}
    fun stop(task:Task)=action{api!!.request("/api/jobs/${task.id}/cancel","POST");refresh(false);_state.update{it.copy(notice="停止请求已发送")}}
    fun release()=action{val chat=_state.value.selected?:return@action;api!!.request("/api/threads/${chat.id}/release","POST");_state.update{it.copy(selected=it.selected?.copy(control="readOnly"),notice="已交回电脑，可以在桌面版继续这个聊天")};refresh(true)}
    fun respond(id:String,body:JSONObject)=action{api!!.request("/api/requests/$id/respond","POST",body);refresh(false)}
    fun loadDevices()=action{val result=api!!.request("/api/devices");_state.update{it.copy(devices=result.optJSONArray("devices")?.objects().orEmpty())}}
    fun revoke(id:String)=action{api!!.request("/api/devices/$id","DELETE");if(id==_state.value.session?.deviceId)forgetInternal() else {val result=api!!.request("/api/devices");_state.update{it.copy(devices=result.optJSONArray("devices")?.objects().orEmpty())}}}
    fun forget()=action{forgetInternal()}
    private suspend fun forgetInternal(){val resume=foreground;stopObserving();foreground=resume;withContext(Dispatchers.IO){store.clear()};api=null;lastSeq=0;drafts.clear();imageCache.evictAll();_state.value=ScreenState(loading=false,notice="已移除本机连接信息；电脑任务不会因此停止。")}
    private fun scheduleRefresh(){refreshJob?.cancel();refreshJob=viewModelScope.launch{delay(400);refreshQuiet(true)}}
    private fun handleEvent(e:JSONObject){
        val type=e.str("type");val data=e.optJSONObject("data")?:JSONObject()
        if(type=="authenticated"){lastSeq=data.optLong("lastSeq",lastSeq);live=true;_state.update{it.copy(connection="实时连接")};scheduleRefresh();return}
        if(type=="refresh.required"){scheduleRefresh();return}
        val seq=e.optLong("seq");if(seq<=lastSeq)return;lastSeq=seq
        // Replay arrives before the authenticated marker; refresh supplies a canonical snapshot.
        if(!live)return
        when(type){
            "request.created","request.resolved"->scheduleRefresh()
            "server.status"->{_state.update{it.copy(online=data.optBoolean("online"))};if(data.optBoolean("online"))scheduleRefresh()}
            "job.updated"->{val task=BridgeCodec.task(data);if(task.threadId==_state.value.selected?.id)_state.update{it.copy(tasks=(listOf(task)+it.tasks.filterNot{old->old.id==task.id}))};if(!task.active)scheduleRefresh()}
            "thread.event"->{if(data.str("threadId")!=_state.value.selected?.id)return;val turn=data.str("turnId");val method=data.str("method")
                when(method){
                    "item/agentMessage/delta"->{val id="$turn:${data.str("itemId")}";_state.update{s->val old=s.entries.find{it.id==id};val entry=Entry(id,turn,"assistant",old?.text.orEmpty()+data.str("delta"),live=true);s.copy(entries=if(old==null)s.entries+entry else s.entries.map{if(it.id==id)entry else it})}}
                    "item/started","item/completed"->{val item=data.optJSONObject("item")?:return;val entry=BridgeCodec.item(turn,item)?:return;_state.update{s->s.copy(entries=if(s.entries.none{it.id==entry.id})s.entries+entry else s.entries.map{if(it.id==entry.id)entry else it})}}
                    "item/commandExecution/outputDelta"->{val id="$turn:${data.str("itemId")}";_state.update{s->s.copy(entries=s.entries.map{if(it.id==id)it.copy(detail=(it.detail+data.str("delta")).takeLast(100000)) else it})}}
                    "turn/completed"->scheduleRefresh()
                    "error"->_state.update{it.copy(notice=data.optJSONObject("error")?.str("message"))}
                }
            }
        }
    }
    override fun onCleared(){stopObserving();api?.client?.dispatcher?.executorService?.shutdown();super.onCleared()}
    private fun encode(s:String)=java.net.URLEncoder.encode(s,"UTF-8")
}
