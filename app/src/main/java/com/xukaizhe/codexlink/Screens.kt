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
@Composable fun CodexApp(vm:BridgeViewModel,networkAllowed:Boolean,requestNetwork:()->Unit){
    val s by vm.state.collectAsState();val snack=remember{SnackbarHostState()}
    var newChat by remember{mutableStateOf(false)};var settings by remember{mutableStateOf(false)}
    var request by remember{mutableStateOf<JSONObject?>(null)};var confirm by remember{mutableStateOf("")}
    LaunchedEffect(s.notice){s.notice?.let{snack.showSnackbar(it)}}
    LaunchedEffect(s.requests){request?.let{r->if(s.requests.none{it.str("id")==r.str("id")})request=null}}
    BackHandler(s.selected!=null){if(!s.busy)vm.back()}
    Scaffold(containerColor=MaterialTheme.colorScheme.background,snackbarHost={SnackbarHost(snack)},
        topBar={
            if(s.session!=null)Column{
                TopAppBar(
                    title={Column{
                        Text(s.selected?.name?:"掌上 Codex",maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.SemiBold)
                        Text(s.connection,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                    }},
                    navigationIcon={if(s.selected!=null)TextButton(onClick=vm::back,enabled=!s.busy){Text("返回")}},
                    actions={
                        TextButton(onClick=vm::refreshNow,enabled=!s.busy&&networkAllowed){Text("刷新")}
                        TextButton(onClick={settings=true;vm.loadDevices()},enabled=!s.busy){Text("连接")}
                    }
                )
                if(s.selected!=null)Surface(color=MaterialTheme.colorScheme.surface){
                    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
                        Text(folder(s.selected!!.cwd),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                        FilledTonalButton(onClick={confirm="release"},enabled=!s.busy,modifier=Modifier.testTag("releaseToDesktop")){Text("交回电脑")}
                    }
                }
            }
        },
        bottomBar={if(s.session!=null&&s.selected!=null)Composer(s,vm,networkAllowed,requestNetwork){confirm="abandon"}}
    ){padding->Column(Modifier.fillMaxSize().padding(padding)){
        if(!networkAllowed)Surface(color=MaterialTheme.colorScheme.secondaryContainer){Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){Text("需要允许访问局域网，才能连接电脑。",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall);TextButton(onClick=requestNetwork){Text("允许访问")}}}
        s.error?.let{error->Surface(color=MaterialTheme.colorScheme.errorContainer){Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Text(error,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall);TextButton(onClick=vm::dismissError){Text("知道了")}}}}
        if(s.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        when{ s.loading->Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
            s.session==null->ConnectScreen(s,networkAllowed){address,username,password->if(networkAllowed)vm.login(address,username,password) else requestNetwork()}
            s.selected==null->ChatsScreen(s,vm,{newChat=true},{request=it})
            else->Conversation(s,vm,{request=it})
        }
    }}
    if(newChat)NewChatDialog(s,{newChat=false}){name,cwd->newChat=false;vm.create(name,cwd)}
    if(settings)SettingsDialog(s,vm,{settings=false}){confirm="forget"}
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
        Text("个人使用 · v0.3.1",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary)
    }
}
@Composable private fun ChatsScreen(s:ScreenState,vm:BridgeViewModel,newChat:()->Unit,request:(JSONObject)->Unit){
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.fillMaxSize()){
        item{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("你的聊天",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Text("电脑与手机，共用聊天记录",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)};FilledTonalButton(onClick=newChat,enabled=!s.busy&&s.online,modifier=Modifier.testTag("newChat")){Text("＋ 新建")}}}
        if(!s.online)item{Info("电脑后台尚未连接 Codex","请在电脑检查登录和网络，恢复后点击刷新。")}
        s.pending?.let{item{Info("有一条消息尚未确认","重试会沿用原消息编号，避免重复执行。","打开消息",vm::openPending)}}
        items(s.requests,key={"req:"+it.str("id")}){r->RequestBanner(r){request(r)}}
        if(s.chats.isEmpty())item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text("从第一条任务开始",style=MaterialTheme.typography.titleMedium);Text("点击“新建”，或者刷新电脑上已有的聊天。",color=MaterialTheme.colorScheme.secondary)}}}
        items(s.chats,key=Chat::id){chat->Card(onClick={vm.open(chat)},enabled=!s.busy,shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),modifier=Modifier.fillMaxWidth()){Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(chat.name,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis);Text(folder(chat.cwd),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.secondary)};Text("›",fontSize=26.sp,color=MaterialTheme.colorScheme.primary)}}}
        if(s.chatCursor.isNotEmpty())item{TextButton(onClick=vm::moreChats,enabled=!s.busy,modifier=Modifier.fillMaxWidth()){Text("加载更多聊天")}}
        item{Text("已有聊天可直接刷新查看。继续发送时，如果桌面版仍占用它，应用会提示你先释放。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary,lineHeight=21.sp)}
    }
}
private fun folder(path:String)=path.trimEnd('\\','/').substringAfterLast('\\').substringAfterLast('/')
@Composable private fun Info(title:String,body:String,button:String?=null,action:()->Unit={}){Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.primaryContainer){Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(title,fontWeight=FontWeight.SemiBold);Text(body,style=MaterialTheme.typography.bodySmall);if(button!=null)TextButton(onClick=action){Text(button)}}}}
@Composable private fun RequestBanner(r:JSONObject,action:()->Unit){Card(onClick=action,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.tertiaryContainer),modifier=Modifier.fillMaxWidth()){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(if(r.str("method").endsWith("requestUserInput"))"Codex 需要你回答" else "有一项操作需要授权",fontWeight=FontWeight.SemiBold);Text("查看具体内容后，再决定是否继续",style=MaterialTheme.typography.bodySmall)};Text("查看 ›")}}}
@Composable private fun Conversation(s:ScreenState,vm:BridgeViewModel,request:(JSONObject)->Unit){
    val list=rememberLazyListState();var follow by remember(s.selected?.id){mutableStateOf(true)}
    var preview by remember(s.selected?.id,s.session?.address,s.session?.deviceId){mutableStateOf<ImageAttachment?>(null)}
    LaunchedEffect(list.isScrollInProgress){if(list.isScrollInProgress)follow=list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let{it>=list.layoutInfo.totalItemsCount-2}?:true}
    LaunchedEffect(s.entries.size,s.entries.lastOrNull()?.text){if(follow){withFrameNanos{};if(list.layoutInfo.totalItemsCount>0)list.scrollToItem(list.layoutInfo.totalItemsCount-1)}}
    LazyColumn(state=list,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        if(s.historyCursor.isNotEmpty())item{TextButton(onClick=vm::moreHistory,enabled=!s.busy,modifier=Modifier.fillMaxWidth()){Text("加载更早的消息")}}
        if(s.entries.isEmpty())item{Column(Modifier.fillMaxWidth().padding(vertical=44.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){Text(">_",fontSize=36.sp,fontFamily=FontFamily.Monospace,color=MaterialTheme.colorScheme.primary);Text("想让电脑做什么？",style=MaterialTheme.typography.titleLarge);Text("发一条消息，或附上一张图片。",color=MaterialTheme.colorScheme.secondary)}}
        items(s.entries,key=Entry::id){entry->Message(entry,vm){preview=it}}
        items(s.tasks.filter{it.active||it.state=="needsReview"||it.state=="failed"}.take(8),key={"job:"+it.id}){task->Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant){Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(taskLabel(task.state),fontWeight=FontWeight.Medium);if(task.error.isNotBlank())Text(task.error,style=MaterialTheme.typography.bodySmall)};if(task.active)TextButton(onClick={vm.stop(task)},enabled=!s.busy){Text(if(task.state=="queued")"取消排队" else "停止")}}}}
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
            if(entry.text.isNotEmpty()||entry.images.isEmpty())SelectionContainer{Text(entry.text.ifEmpty{if(entry.live)"正在回复…" else " "},style=MaterialTheme.typography.bodyLarge,lineHeight=25.sp)}
            entry.images.forEach{photo->HistoryImage(photo,vm){openImage(photo)}}
        }
    }}
}
@Composable private fun Composer(s:ScreenState,vm:BridgeViewModel,networkAllowed:Boolean,requestNetwork:()->Unit,abandon:()->Unit){
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)){if(it.isNotEmpty())vm.addPhotos(it)}
    Surface(shadowElevation=3.dp,color=MaterialTheme.colorScheme.surface){Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal=16.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        s.pending?.let{p->Column{Text("有一条消息尚未确认，重试会使用原编号。",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary);Row{TextButton(onClick={if(p.threadId==s.selected?.id&&p.address==s.session?.address)vm.send() else vm.openPending()},enabled=!s.busy){Text(if(p.threadId==s.selected?.id)"重试原消息" else "打开原聊天")};TextButton(onClick=abandon,enabled=!s.busy){Text("放弃重试")}}}}
        if(s.photos.isNotEmpty())LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){items(s.photos,key=Photo::path){photo->Column{val bitmap by produceState<android.graphics.Bitmap?>(null,photo.path){value=withContext(Dispatchers.IO){BitmapFactory.decodeFile(photo.path,BitmapFactory.Options().apply{inSampleSize=4})}};bitmap?.let{Image(it.asImageBitmap(),"待发送图片",Modifier.size(66.dp))};TextButton(onClick={vm.removePhoto(photo)},enabled=!s.busy&&s.pending==null){Text("移除",style=MaterialTheme.typography.labelSmall)}}}}
        OutlinedTextField(s.draft,vm::draft,placeholder={Text("给 Codex 发消息…")},enabled=!s.busy&&s.pending==null,maxLines=5,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().testTag("draft"))
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){TextButton(onClick={picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},enabled=!s.busy&&s.pending==null&&s.photos.size<8){Text("＋ 图片")};Spacer(Modifier.weight(1f));Button(onClick={if(networkAllowed)vm.send() else requestNetwork()},enabled=!s.busy&&s.pending==null&&(s.draft.isNotBlank()||s.photos.isNotEmpty()),shape=RoundedCornerShape(12.dp),modifier=Modifier.testTag("send")){Text("发送 ↑")}}
    }}
}
@Composable private fun NewChatDialog(s:ScreenState,dismiss:()->Unit,create:(String,String)->Unit){var name by rememberSaveable{mutableStateOf("")};var workspace by rememberSaveable{mutableStateOf(s.workspaces.firstOrNull().orEmpty())}
    AlertDialog(onDismissRequest=dismiss,title={Text("新建聊天")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){OutlinedTextField(name,{name=it.take(100)},label={Text("聊天名称")},modifier=Modifier.testTag("chatName"));Text("选择电脑上的项目",style=MaterialTheme.typography.labelLarge);s.workspaces.forEach{path->Surface(onClick={workspace=path},color=if(path==workspace)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(12.dp)){Column(Modifier.fillMaxWidth().padding(12.dp)){Text(folder(path),fontWeight=FontWeight.Medium);Text(path,style=MaterialTheme.typography.bodySmall)}}}}},confirmButton={TextButton(onClick={create(name,workspace)},enabled=workspace.isNotEmpty()){Text("创建")}},dismissButton={TextButton(onClick=dismiss){Text("取消")}})
}
@Composable private fun SettingsDialog(s:ScreenState,vm:BridgeViewModel,dismiss:()->Unit,forget:()->Unit){var revoke by remember{mutableStateOf<String?>(null)}
    AlertDialog(
        onDismissRequest=dismiss,
        title={Text("电脑连接")},
        text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){
                Text(s.session?.address.orEmpty())
                Text(s.connection,color=MaterialTheme.colorScheme.primary)
                HorizontalDivider()
                Text("已登录设备",fontWeight=FontWeight.SemiBold)
                s.devices.filter{it.isNull("revoked_at")}.forEach{device->
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        val label=device.str("name")+(if(device.str("id")==s.session?.deviceId)"（本机）" else "")
                        Text(label,Modifier.weight(1f))
                        TextButton(onClick={revoke=device.str("id")},enabled=!s.busy){Text("撤销")}
                    }
                }
                Text("关闭手机应用不会停止电脑任务。重新打开后会自动连接，刷新即可查看结果。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)
                TextButton(onClick=forget,enabled=!s.busy){Text("移除本机连接信息")}
                Text("掌上 Codex · 0.3.1",style=MaterialTheme.typography.labelSmall)
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
