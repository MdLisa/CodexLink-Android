package com.xukaizhe.codexlink

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CodexApp(vm:BridgeViewModel,networkAllowed:Boolean,checkUpdate:()->Unit={},requestNetwork:()->Unit){
    val s by vm.state.collectAsState();val snack=remember{SnackbarHostState()}
    var newChatForImport by remember{mutableStateOf(false)};var chooseIncoming by remember{mutableStateOf(false)};var newChat by remember{mutableStateOf(false)};var settings by remember{mutableStateOf(false)}
    var request by remember{mutableStateOf<JSONObject?>(null)};var confirm by remember{mutableStateOf("")}
    LaunchedEffect(s.incomingFiles.map{it.id},s.session?.deviceId,s.approvalSettings.chatOnly){if(s.approvalSettings.chatOnly){chooseIncoming=false;newChatForImport=false;request=null;confirm=""}else if(s.incomingFiles.isNotEmpty()&&s.session!=null)chooseIncoming=true}
    LaunchedEffect(s.notice){s.notice?.let{snack.showSnackbar(it)}}
    LaunchedEffect(s.requests){request?.let{r->if(s.requests.none{it.str("id")==r.str("id")})request=null}}
    BackHandler(s.selected!=null){if(!s.busy)vm.back()}
    Scaffold(containerColor=MaterialTheme.colorScheme.background,snackbarHost={SnackbarHost(snack)},
        topBar={
            if(s.session!=null)Column{
                TopAppBar(
                    title={Column{
                        Text(s.selected?.name?:"掌上 Codex",maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.SemiBold)
                        Text((if(s.browsingShared)"其他手机 · 只读 · " else if(s.approvalSettings.chatOnly)"仅聊天 · " else "")+s.connection,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                    }},
                    navigationIcon={if(s.selected!=null)TextButton(onClick=vm::back,enabled=!s.busy){Text("返回")}},
                    actions={
                        TextButton(onClick=vm::refreshNow,enabled=!s.busy&&networkAllowed){Text("刷新")}
                        TextButton(onClick={settings=true;vm.loadDevices()},enabled=!s.busy){Text("连接")}
                    }
                )
                if(s.selected!=null)Surface(color=MaterialTheme.colorScheme.surface){
                    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
                        Text(if(s.selected!!.sharedReadOnly)"只读 · "+s.selected!!.ownerLabel else folder(s.selected!!.cwd),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                        if(!s.selected!!.sharedReadOnly&&!s.approvalSettings.chatOnly&&s.selected?.control!="bridge")FilledTonalButton(onClick=vm::prepareTakeover,enabled=!s.busy&&s.online,modifier=Modifier.testTag("takeOverChat")){Text("接管会话")}
                        if(!s.selected!!.sharedReadOnly&&!s.approvalSettings.chatOnly)FilledTonalButton(onClick={confirm="release"},enabled=!s.busy,modifier=Modifier.testTag("releaseToDesktop")){Text("交回电脑")}
                    }
                }
            }
        },
        bottomBar={if(s.session!=null&&s.selected!=null){if(s.selected!!.sharedReadOnly)Surface{Text("只读查看 · 不能发送消息、操作任务或修改会话",Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),style=MaterialTheme.typography.bodySmall)}else Composer(s,vm,networkAllowed,requestNetwork){confirm="abandon"}}}
    ){padding->Column(Modifier.fillMaxSize().padding(padding)){
        if(!networkAllowed)Surface(color=MaterialTheme.colorScheme.secondaryContainer){Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){Text("需要允许访问局域网，才能连接电脑。",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall);TextButton(onClick=requestNetwork){Text("允许访问")}}}
        s.error?.let{error->Surface(color=MaterialTheme.colorScheme.errorContainer){Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Text(error,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall);TextButton(onClick=vm::dismissError){Text("知道了")}}}}
        if(!s.approvalSettings.chatOnly&&s.incomingFiles.isNotEmpty())Surface(color=MaterialTheme.colorScheme.secondaryContainer){Column(Modifier.fillMaxWidth().padding(12.dp)){
            Text("收到 "+s.incomingFiles.size+" 个待添加文件",fontWeight=FontWeight.Medium)
            Row{if(s.session!=null)TextButton(onClick={chooseIncoming=true},enabled=!s.busy&&!s.importing){Text("选择对话")} else Text("连接电脑后可选择对话",style=MaterialTheme.typography.bodySmall);TextButton(onClick=vm::discardIncoming,enabled=!s.busy&&!s.importing){Text("取消导入")}}
        }}
        if(s.busy||s.importing)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(s.takeoverProgress.isNotEmpty())Text(s.takeoverProgress,Modifier.padding(horizontal=16.dp,vertical=8.dp),style=MaterialTheme.typography.bodySmall)
        when{ s.loading->Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
            s.session==null->Column(Modifier.weight(1f)){Box(Modifier.weight(1f)){ConnectScreen(s,networkAllowed){address,username,password->if(networkAllowed)vm.login(address,username,password) else requestNetwork()}};TextButton(onClick=checkUpdate,modifier=Modifier.align(Alignment.CenterHorizontally)){Text("检查更新")}}
            s.selected==null->ChatsScreen(s,vm,{newChat=true},{request=it})
            else->Conversation(s,vm,{request=it})
        }
    }}
    if(!s.approvalSettings.chatOnly&&chooseIncoming&&s.session!=null&&s.incomingFiles.isNotEmpty()&&!newChat)IncomingFilesDialog(s,{chat->chooseIncoming=false;vm.openForIncoming(chat)},{chooseIncoming=false;newChatForImport=true;newChat=true},vm::moreChats){chooseIncoming=false}
    if(newChat)NewChatDialog(s,{newChat=false;if(newChatForImport)chooseIncoming=true;newChatForImport=false}){name,cwd->val attach=newChatForImport;newChat=false;newChatForImport=false;vm.create(name,cwd,attach)}
    if(settings)SettingsDialog(s,vm,{settings=false},{settings=false;checkUpdate()}){confirm="forget"}
    s.takeoverPlan?.let{plan->AlertDialog(onDismissRequest=vm::dismissTakeover,title={Text("退出电脑端并接管？")},text={Text("将请求退出电脑上的 ${plan.desktopCount} 个 Codex 桌面实例，可能中断其全部会话任务。已完成的文件修改不会撤销。\n\n退出后尝试接管当前原会话，不复制聊天，不自动重发消息。若电脑端未能正常退出，会报告失败，不强杀进程。\n\n关闭手机不会取消已经确认的接管操作。")},confirmButton={TextButton(onClick=vm::confirmTakeover,enabled=!s.busy){Text("退出电脑端并接管")}},dismissButton={TextButton(onClick=vm::dismissTakeover){Text("取消")}})}
    request?.let{ApprovalDialog(it,s.busy,{request=null}){body->vm.respond(it.str("id"),body)}}
    if(confirm.isNotEmpty())AlertDialog(onDismissRequest={confirm=""},title={Text(when(confirm){"release"->"交回电脑";"abandon"->"放弃本机重试？";else->"移除这台电脑？"})},text={Text(when(confirm){"release"->"手机任务全部结束后，会释放后台使用的聊天。之后可在桌面版继续同一个聊天。";"abandon"->"这只会移除手机上的重试记录，不会停止电脑可能已经收到的任务。建议先刷新聊天确认结果。";else->"移除手机保存的连接信息。电脑上已经开始的任务仍会继续；下次需要重新登录。"})},confirmButton={TextButton(onClick={when(confirm){"release"->vm.release();"abandon"->vm.abandonPending();else->{settings=false;vm.forget()}};confirm=""}){Text("确认")}},dismissButton={TextButton(onClick={confirm=""}){Text("取消")}})
}
@Composable private fun Brand(){Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.primary){Text(">_",Modifier.padding(15.dp),fontFamily=FontFamily.Monospace,fontSize=25.sp,fontWeight=FontWeight.Bold)};Column{Text("掌上 Codex",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text("CODEXLINK  /  PERSONAL",style=MaterialTheme.typography.labelSmall,letterSpacing=1.5.sp,color=MaterialTheme.colorScheme.secondary)}}}
@Composable private fun ConnectScreen(s:ScreenState,networkAllowed:Boolean,login:(String,String,String)->Unit){
    val deviceCode by produceState<String?>(null){value=withContext(Dispatchers.IO){runCatching{DeviceProof.code(AndroidDeviceIdentity.publicKey())}.getOrNull()}}
    var address by rememberSaveable(s.loginAddress){mutableStateOf(s.loginAddress)};var username by rememberSaveable{mutableStateOf("codex")};var password by remember{mutableStateOf("")};var visible by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),verticalArrangement=Arrangement.spacedBy(22.dp)){
        Spacer(Modifier.height(20.dp));Brand();Spacer(Modifier.height(12.dp));Text("连接你的电脑",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
        Text("发任务、传图片，随时接着聊。\n电脑保持开机，Codex 在后台继续工作。",color=MaterialTheme.colorScheme.secondary,lineHeight=25.sp)
        Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            OutlinedTextField(address,{address=it},label={Text("电脑地址")},placeholder={Text("http://192.168.1.10:18766")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),modifier=Modifier.fillMaxWidth().testTag("address"))
            OutlinedTextField(username,{username=it.take(64)},label={Text("账号")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii),modifier=Modifier.fillMaxWidth().testTag("username"))
            OutlinedTextField(password,{password=it.take(256)},label={Text("密码")},supportingText={Text("首次使用，请在电脑双击“设置账号密码.cmd”。")},singleLine=true,visualTransformation=if(visible)VisualTransformation.None else PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),trailingIcon={TextButton(onClick={visible=!visible}){Text(if(visible)"隐藏" else "显示")}},modifier=Modifier.fillMaxWidth().testTag("password"))
            if(s.deviceApprovalCode!=null)Info("等待电脑批准","请在电脑双击“审批手机.cmd”，选择这台手机并核对下面的核对码。批准后再次点击登录。")
            deviceCode?.let{Text("本机核对码：$it",style=MaterialTheme.typography.labelMedium,fontFamily=FontFamily.Monospace)}
            Button(onClick={login(address,username,password)},enabled=!s.busy&&address.isNotBlank()&&username.isNotBlank()&&password.isNotEmpty(),shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth().height(52.dp).testTag("login")){Text(if(!networkAllowed)"允许访问并连接" else if(s.deviceApprovalCode!=null)"已在电脑批准，重新登录" else "连接电脑",fontWeight=FontWeight.Bold)}
        }}
        Text("填写电脑后台提供的地址。登录后会安全保存连接凭据，不需要每次输入密码。外网使用 HTTPS 地址。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary,lineHeight=21.sp)
        Text("个人使用 · v0.10.3",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary)
    }
}
@Composable private fun ChatsScreen(s:ScreenState,vm:BridgeViewModel,newChat:()->Unit,request:(JSONObject)->Unit){
    val searching=s.chatSearch.isNotBlank();val chats=if(searching)s.searchResults else s.chats
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.fillMaxSize()){
        if(s.sharedChatAccess.enabled)item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(selected=!s.browsingShared,onClick={vm.browseShared(false)},enabled=!s.busy,label={Text("我的聊天")});FilterChip(selected=s.browsingShared,onClick={vm.browseShared(true)},enabled=!s.busy,label={Text("其他手机 · 只读")},modifier=Modifier.testTag("sharedChats"))}}
        item{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(if(s.browsingShared)"其他手机的聊天" else "你的聊天",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Text(if(s.browsingShared)"仅聊天会话 · 只读查看" else if(s.approvalSettings.chatOnly)"仅聊天 · 允许联网和题目图片" else "电脑与手机，共用聊天记录",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)};if(!s.browsingShared)FilledTonalButton(onClick=newChat,enabled=!s.busy&&s.online,modifier=Modifier.testTag("newChat")){Text("＋ 新建")}}}
        item{OutlinedTextField(value=s.chatSearch,onValueChange=vm::searchChats,label={Text("搜索聊天")},placeholder={Text(if(s.browsingShared)"聊天名称或手机名称" else if(s.approvalSettings.chatOnly)"聊天名称" else "聊天名称或项目路径")},singleLine=true,enabled=!s.busy,trailingIcon={if(s.chatSearch.isNotEmpty())TextButton(onClick={vm.searchChats("")}){Text("清空")}},modifier=Modifier.fillMaxWidth().testTag("chatSearch"))}
        if(searching)item{when{
            s.searching->Column(verticalArrangement=Arrangement.spacedBy(8.dp)){LinearProgressIndicator(Modifier.fillMaxWidth());Text("正在搜索可访问的聊天…",style=MaterialTheme.typography.bodySmall)}
            s.searchError!=null->Info("搜索未完成",s.searchError,"重试"){vm.searchChats(s.chatSearch)}
            chats.isEmpty()->Info("没有找到匹配的聊天","试试其他聊天名称或项目路径。")
            else->Text("找到 ${chats.size} 个聊天",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)
        }}
        if(!s.online)item{Info("电脑后台尚未连接 Codex","请在电脑检查登录和网络，恢复后点击刷新。")}
        s.pending?.let{item{Info("有一条消息尚未确认","重试会沿用原消息编号，避免重复执行。","打开消息",vm::openPending)}}
        items(s.requests,key={"req:"+it.str("id")}){r->RequestBanner(r){request(r)}}
        if(!searching&&s.chats.isEmpty())item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text(if(s.browsingShared)"暂无可查看的会话" else "从第一条任务开始",style=MaterialTheme.typography.titleMedium);Text(if(s.browsingShared)"其他已批准手机在仅聊天模式下创建会话后，可在这里刷新查看。" else if(s.approvalSettings.chatOnly)"点击“新建”，开始本设备独立的聊天。" else "点击“新建”，或者刷新电脑上已有的聊天。",color=MaterialTheme.colorScheme.secondary)}}}
        items(chats,key=Chat::id){chat->Card(onClick={vm.open(chat)},enabled=!s.busy,shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),modifier=Modifier.fillMaxWidth()){Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(chat.name,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis);Text(if(chat.sharedReadOnly)"只读 · "+chat.ownerLabel else folder(chat.cwd),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.secondary)};Text("›",fontSize=26.sp,color=MaterialTheme.colorScheme.primary)}}}
        if(!searching&&s.chatCursor.isNotEmpty())item{TextButton(onClick=vm::moreChats,enabled=!s.busy,modifier=Modifier.fillMaxWidth()){Text("加载更多聊天")}}
        item{Text(if(s.browsingShared)"查看其他已批准手机的仅聊天记录及图片；不能发送消息、停止任务或修改会话。点击刷新可更新记录。" else if(s.approvalSettings.chatOnly)"这里显示本设备独立的聊天；可联网和解题，不能读取文件或操作电脑。" else "已有聊天可直接刷新查看。继续发送时，如果桌面版仍占用它，应用会提示你先释放。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary,lineHeight=21.sp)}
    }
}
private fun folder(path:String)=path.trimEnd('\\','/').substringAfterLast('\\').substringAfterLast('/')
@Composable private fun Info(title:String,body:String,button:String?=null,action:()->Unit={}){Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.primaryContainer){Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(title,fontWeight=FontWeight.SemiBold);Text(body,style=MaterialTheme.typography.bodySmall);if(button!=null)TextButton(onClick=action){Text(button)}}}}
@Composable private fun RequestBanner(r:JSONObject,action:()->Unit){Card(onClick=action,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.tertiaryContainer),modifier=Modifier.fillMaxWidth()){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(if(r.str("method").endsWith("requestUserInput"))"Codex 需要你回答" else "有一项操作需要授权",fontWeight=FontWeight.SemiBold);Text("查看具体内容后，再决定是否继续",style=MaterialTheme.typography.bodySmall)};Text("查看 ›")}}}
@Composable private fun Conversation(s:ScreenState,vm:BridgeViewModel,request:(JSONObject)->Unit){
    val dates=messageDates(s.entries)
    val list=rememberLazyListState();var follow by remember(s.selected?.id){mutableStateOf(true)}
    var preview by remember(s.selected?.id,s.session?.address,s.session?.deviceId){mutableStateOf<ImageAttachment?>(null)}
    LaunchedEffect(list.isScrollInProgress){if(list.isScrollInProgress)follow=list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let{it>=list.layoutInfo.totalItemsCount-2}?:true}
    LaunchedEffect(s.entries.size,s.entries.lastOrNull()?.text){if(follow){withFrameNanos{};if(list.layoutInfo.totalItemsCount>0)list.scrollToItem(list.layoutInfo.totalItemsCount-1)}}
    LazyColumn(state=list,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        if(s.historyCursor.isNotEmpty())item{TextButton(onClick=vm::moreHistory,enabled=!s.busy,modifier=Modifier.fillMaxWidth()){Text("加载更早的消息")}}
        if(s.entries.isEmpty())item{Column(Modifier.fillMaxWidth().padding(vertical=44.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){Text(">_",fontSize=36.sp,fontFamily=FontFamily.Monospace,color=MaterialTheme.colorScheme.primary);Text(if(s.selected?.sharedReadOnly==true)"这个会话还没有消息" else if(s.approvalSettings.chatOnly)"想聊什么？" else "想让电脑做什么？",style=MaterialTheme.typography.titleLarge);Text(if(s.selected?.sharedReadOnly==true)"对方发送消息后，刷新即可查看。" else "发一条消息，或附上一张图片。",color=MaterialTheme.colorScheme.secondary)}}
        items(s.entries,key=Entry::id){entry->Column(Modifier.fillMaxWidth()){
            dates[entry.id]?.let{date->Text(date,Modifier.align(Alignment.CenterHorizontally).padding(vertical=8.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary)}
            Message(entry,vm){preview=it}
        }}
        items(s.tasks.filter{it.active||it.state=="needsReview"||it.state=="failed"}.take(8),key={"job:"+it.id}){task->Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant){Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(taskLabel(task.state),fontWeight=FontWeight.Medium);if(task.approvalsReviewer.isNotEmpty())Text("本任务审批："+approvalLabel(task.approvalsReviewer),style=MaterialTheme.typography.bodySmall);if(task.error.isNotBlank())Text(task.error,style=MaterialTheme.typography.bodySmall)};if(task.active)TextButton(onClick={vm.stop(task)},enabled=!s.busy){Text(if(task.state=="queued")"取消排队" else "停止")}}}}
        items(s.requests.filter{it.str("threadId")==s.selected?.id},key={"req:"+it.str("id")}){r->RequestBanner(r){request(r)}}
        item{Spacer(Modifier.height(4.dp))}
    }
    preview?.let{ImagePreview(it,vm){preview=null}}
}
private fun taskLabel(state:String)=when(state){"queued"->"消息已排队";"starting"->"正在启动任务";"running"->"Codex 正在处理";"waitingApproval"->"等待你的确认";"needsReview"->"结果需要检查，请先刷新聊天";"failed"->"任务未完成";else->state}
@Composable private fun Message(entry:Entry,vm:BridgeViewModel,openImage:(ImageAttachment)->Unit){
    if(entry.role=="tool"){var expanded by remember(entry.id){mutableStateOf(false)};Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant){Column(Modifier.fillMaxWidth().clickable{expanded=!expanded}.padding(14.dp)){Text((if(expanded)"▾  " else "▸  ")+entry.text,style=MaterialTheme.typography.labelLarge);if(expanded)SelectionContainer{Text(entry.detail.takeLast(60000),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=10.dp))}}};return}
    val user=entry.role=="user";Column(Modifier.fillMaxWidth(),horizontalAlignment=if(user)Alignment.End else Alignment.Start){Text(if(user)"你" else "CODEX",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary,modifier=Modifier.padding(horizontal=4.dp,vertical=5.dp));Surface(shape=RoundedCornerShape(18.dp),color=if(user)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,modifier=Modifier.widthIn(max=600.dp)){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(entry.text.isNotEmpty()||(entry.images.isEmpty()&&entry.files.isEmpty())){
                val text=entry.text.ifEmpty{if(entry.live)"正在回复…" else " "}
                if(user)SelectionContainer{Text(text,style=MaterialTheme.typography.bodyLarge,lineHeight=25.sp)}
                else MarkdownMessage(text)
            }
            entry.images.forEach{photo->HistoryImage(photo,vm){openImage(photo)}}
            entry.files.forEach{FileCard(it)}
            MessageTimeStamp(entry,Modifier.align(Alignment.End))
        }
    }}
}
@Composable private fun MessageTimeStamp(entry:Entry,modifier:Modifier=Modifier){
    var showDetails by remember(entry.id){mutableStateOf(false)}
    val fullTime=entry.fullTimeLabel()
    Box(modifier.sizeIn(minWidth=48.dp,minHeight=32.dp).clickable(enabled=fullTime!=null,onClickLabel="查看完整日期和时间"){showDetails=true}.testTag("messageTime"),contentAlignment=Alignment.CenterEnd){
        Text(entry.timeLabel(),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary)
    }
    if(showDetails&&fullTime!=null)AlertDialog(onDismissRequest={showDetails=false},title={Text("消息时间")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        SelectionContainer{Text(fullTime)}
        Text("按手机本地时区显示",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)
    }},confirmButton={TextButton(onClick={showDetails=false}){Text("知道了")}})
}
@Composable private fun Composer(s:ScreenState,vm:BridgeViewModel,networkAllowed:Boolean,requestNetwork:()->Unit,abandon:()->Unit){
    val documents=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){if(it.isNotEmpty())vm.addDocuments(it)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)){if(it.isNotEmpty())vm.addPhotos(it)}
    Surface(shadowElevation=3.dp,color=MaterialTheme.colorScheme.surface){Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal=16.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        s.pending?.let{p->Column{Text("有一条消息尚未确认，重试会使用原编号。",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary);Row{TextButton(onClick={if(p.threadId==s.selected?.id&&p.address==s.session?.address)vm.send() else vm.openPending()},enabled=!s.busy){Text(if(p.threadId==s.selected?.id)"重试原消息" else "打开原聊天")};TextButton(onClick=abandon,enabled=!s.busy){Text("放弃重试")}}}}
        if(s.photos.isNotEmpty())LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){items(s.photos,key=Photo::path){photo->Column{val bitmap by produceState<android.graphics.Bitmap?>(null,photo.path){value=withContext(Dispatchers.IO){BitmapFactory.decodeFile(photo.path,BitmapFactory.Options().apply{inSampleSize=4})}};bitmap?.let{Image(it.asImageBitmap(),"待发送图片",Modifier.size(66.dp))};TextButton(onClick={vm.removePhoto(photo)},enabled=!s.busy&&s.pending==null){Text("移除",style=MaterialTheme.typography.labelSmall)}}}}
        s.files.forEach{file->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("文件 · "+file.name,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium);Text(fileSize(file.size),style=MaterialTheme.typography.labelSmall)};TextButton(onClick={vm.removeDocument(file)},enabled=!s.busy&&s.pending==null){Text("移除")}}}
        if(s.uploadProgress.isNotBlank())Text(s.uploadProgress,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
        OutlinedTextField(s.draft,vm::draft,placeholder={Text("给 Codex 发消息…")},enabled=!s.busy&&s.pending==null,maxLines=5,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().testTag("draft"))
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){TextButton(onClick={picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},enabled=!s.busy&&s.pending==null&&s.photos.size+s.files.size<8){Text("＋ 图片")};if(!s.approvalSettings.chatOnly)TextButton(onClick={documents.launch(arrayOf("*/*"))},enabled=!s.busy&&!s.importing&&s.pending==null&&s.photos.size+s.files.size<8){Text("＋ 文件")};Spacer(Modifier.weight(1f));Button(onClick={if(networkAllowed)vm.send() else requestNetwork()},enabled=!s.busy&&s.pending==null&&(s.draft.isNotBlank()||s.photos.isNotEmpty()||s.files.isNotEmpty()),shape=RoundedCornerShape(12.dp),modifier=Modifier.testTag("send")){Text("发送 ↑")}}
    }}
}
@Composable private fun NewChatDialog(s:ScreenState,dismiss:()->Unit,create:(String,String)->Unit){var name by rememberSaveable{mutableStateOf("")};var workspace by rememberSaveable(s.projectScope.revision,s.workspaces){mutableStateOf(s.workspaces.firstOrNull().orEmpty())}
    AlertDialog(onDismissRequest=dismiss,title={Text("新建聊天")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        OutlinedTextField(name,{name=it.take(100)},label={Text("聊天名称")},modifier=Modifier.testTag("chatName"))
        if(s.approvalSettings.chatOnly)Text("仅聊天（允许联网）\n这是本设备的独立聊天，可发送文字和题目图片，不能操作电脑。")
        else{Text(if(s.projectScope.all)"项目范围：所有目录" else "选择电脑上的项目",style=MaterialTheme.typography.labelLarge);s.workspaces.forEach{path->Surface(onClick={workspace=path},color=if(path==workspace)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(12.dp)){Column(Modifier.fillMaxWidth().padding(12.dp)){Text(folder(path),fontWeight=FontWeight.Medium);Text(path,style=MaterialTheme.typography.bodySmall)}}};if(s.projectScope.all)OutlinedTextField(workspace,{workspace=it.take(1000)},label={Text("项目完整路径")},modifier=Modifier.fillMaxWidth().testTag("workspacePath"))}
    }},confirmButton={TextButton(onClick={create(name,workspace.trim())},enabled=(s.approvalSettings.chatOnly||workspace.isNotBlank())&&s.online){Text("创建")}},dismissButton={TextButton(onClick=dismiss){Text("取消")}})
}
@Composable private fun SettingsDialog(s:ScreenState,vm:BridgeViewModel,dismiss:()->Unit,checkUpdate:()->Unit,forget:()->Unit){var revoke by remember{mutableStateOf<String?>(null)}
    AlertDialog(
        onDismissRequest=dismiss,
        title={Text("电脑连接")},
        text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){
                Text(s.session?.address.orEmpty())
                Text(s.connection,color=MaterialTheme.colorScheme.primary)
                Text(if(s.approvalSettings.chatOnly)"聊天范围：本设备独立聊天" else if(s.projectScope.all)"项目范围：所有目录" else "项目范围：指定目录",fontWeight=FontWeight.Medium)
                if(!s.approvalSettings.chatOnly&&!s.projectScope.all)s.projectScope.roots.forEach{Text(it,style=MaterialTheme.typography.bodySmall)}
                Text(if(s.approvalSettings.chatOnly)"允许联网、聊天和题目图片；不能读取电脑文件、执行命令或操作电脑。" else "项目范围由电脑控制台为本机设备设置。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)
                HorizontalDivider()
                Text("新任务操作审批",fontWeight=FontWeight.Medium)
                Text(approvalLabel(s.approvalSettings.reviewer))
                Text(if(s.approvalSettings.chatOnly)"仅电脑可更改。切换模式前需停止或等待任务结束，旧模式排队任务会取消。" else if(s.approvalSettings.perDevice)"由电脑为本机设置。手动与自动审批切换保留已提交任务的设置；切换仅聊天会取消旧模式排队任务。" else "当前后台使用统一审批设置。更新电脑后台后可按手机分别设置。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)
                if(!s.approvalSettings.chatOnly){Text("退出电脑端并接管："+(if(s.takeoverAllowed)"已授权" else "未授权或后台不支持"),style=MaterialTheme.typography.bodySmall);Text("电脑设备管理中输入 T，可为本机单独授权。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)}
                HorizontalDivider()
                Text("本机登录连接",fontWeight=FontWeight.SemiBold)
                if(s.sharedChatAccess.enabled){
                    Text("查看其他手机仅聊天会话：已授权（只读）",style=MaterialTheme.typography.bodySmall)
                    Text("由电脑设备管理中的 V 授权；只能看聊天和图片，不能代替对方操作。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)
                }
                s.devices.filter{it.isNull("revoked_at")}.forEach{device->
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        val label=device.str("name")+(if(device.str("id")==s.session?.deviceId)"（本机）" else "")
                        Text(label,Modifier.weight(1f))
                        TextButton(onClick={revoke=device.str("id")},enabled=!s.busy){Text("撤销")}
                    }
                }
                Text("关闭手机应用不会停止电脑任务。重新打开后会自动连接，刷新即可查看结果。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)
                TextButton(onClick=forget,enabled=!s.busy){Text("移除本机连接信息")}
                TextButton(onClick=checkUpdate){Text("检查更新")}
                Text("掌上 Codex · 0.10.3",style=MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton={TextButton(onClick=dismiss){Text("完成")}}
    )
    revoke?.let{id->AlertDialog(onDismissRequest={revoke=null},title={Text("撤销这个设备？")},text={Text("撤销后，该设备需要账号密码才能重新连接。")},confirmButton={TextButton(onClick={vm.revoke(id);revoke=null;if(id==s.session?.deviceId)dismiss()}){Text("撤销")}},dismissButton={TextButton(onClick={revoke=null}){Text("取消")}})}
}
@Composable private fun ApprovalDialog(r:JSONObject,busy:Boolean,dismiss:()->Unit,respond:(JSONObject)->Unit){
    val questions=r.optJSONArray("questions")?.objects().orEmpty();val answers=remember(r.str("id")){mutableStateMapOf<String,String>()};val input=r.str("method").endsWith("requestUserInput")
    AlertDialog(onDismissRequest=dismiss,title={Text(if(input)"Codex 需要你回答" else "确认电脑操作")},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        if(input)questions.forEach{q->val id=q.str("id");Text(q.str("question"),fontWeight=FontWeight.SemiBold);q.optJSONArray("options")?.objects()?.forEach{option->OutlinedButton(onClick={answers[id]=option.str("label")},enabled=!busy){Column{Text(option.str("label"));if(option.str("description").isNotEmpty())Text(option.str("description"),style=MaterialTheme.typography.bodySmall)}}};OutlinedTextField(answers[id].orEmpty(),{answers[id]=it},label={Text("你的回答")},visualTransformation=if(q.optBoolean("isSecret"))PasswordVisualTransformation() else VisualTransformation.None,modifier=Modifier.fillMaxWidth())}
        else{Text("只会允许这一次操作。请确认命令、目录和权限。",style=MaterialTheme.typography.bodySmall);SelectionContainer{Text(listOfNotNull(r.str("command").takeIf{it.isNotBlank()},r.str("cwd").takeIf{it.isNotBlank()}?.let{"目录：$it"},r.str("reason").takeIf{it.isNotBlank()},r.opt("changes")?.takeUnless{it==JSONObject.NULL}?.toString(),r.opt("additionalPermissions")?.takeUnless{it==JSONObject.NULL}?.let{"权限：$it"},r.opt("networkApprovalContext")?.takeUnless{it==JSONObject.NULL}?.let{"网络：$it"}).joinToString("\n\n"),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)}}
    }},confirmButton={TextButton(onClick={if(input){val body=JSONObject();questions.forEach{q->body.put(q.str("id"),JSONObject().put("answers",JSONArray(listOf(answers[q.str("id")].orEmpty()))))};respond(JSONObject().put("answers",body))}else respond(JSONObject().put("decision","accept"))},enabled=!busy&&(!input||questions.all{!answers[it.str("id")].isNullOrBlank()})){Text(if(input)"提交回答" else "允许一次")}},dismissButton={if(input)TextButton(onClick=dismiss){Text("稍后回答")} else TextButton(onClick={respond(JSONObject().put("decision","decline"))},enabled=!busy){Text("拒绝")}})
}
