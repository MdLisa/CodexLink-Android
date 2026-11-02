package com.xukaizhe.codexlink

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable fun FileCard(file:FileAttachment){
    Surface(shape=androidx.compose.foundation.shape.RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceVariant){
        Column(Modifier.widthIn(max=290.dp).padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text("文件 · "+file.name,fontWeight=FontWeight.Medium,maxLines=3,overflow=TextOverflow.Ellipsis)
            Text(fileSize(file.size)+(if(file.available)" · 已上传到电脑" else " · 电脑附件已不可用"),style=MaterialTheme.typography.labelSmall)
        }
    }
}
@Composable fun IncomingFilesDialog(s:ScreenState,choose:(Chat)->Unit,newChat:()->Unit,more:()->Unit,dismiss:()->Unit){
    AlertDialog(onDismissRequest=dismiss,title={Text("把文件添加到对话")},text={
        Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            s.incomingFiles.forEach{Text(it.name+" · "+fileSize(it.size),style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)}
            Text("选好对话后，文件会加入草稿。补充要求并点击发送后，才会上传电脑。",style=MaterialTheme.typography.bodySmall)
            FilledTonalButton(onClick=newChat,enabled=!s.busy&&!s.importing&&s.workspaces.isNotEmpty(),modifier=Modifier.fillMaxWidth()){Text("＋ 新建对话")}
            HorizontalDivider();Text("已有对话",fontWeight=FontWeight.Medium)
            if(s.chats.isEmpty())Text("连接电脑后会加载对话列表。",style=MaterialTheme.typography.bodySmall)
            s.chats.forEach{chat->OutlinedButton(onClick={choose(chat)},enabled=!s.busy&&!s.importing,modifier=Modifier.fillMaxWidth()){Text(chat.name,maxLines=2,overflow=TextOverflow.Ellipsis)}}
            if(s.chatCursor.isNotEmpty())TextButton(onClick=more,enabled=!s.busy){Text("加载更多对话")}
            s.error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        }
    },confirmButton={TextButton(onClick=dismiss){Text("稍后选择")}})
}
