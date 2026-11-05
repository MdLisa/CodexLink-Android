package com.xukaizhe.codexlink

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable internal fun GeneratedFileCard(doc:GeneratedDocument,vm:BridgeViewModel){
    val state by vm.state.collectAsState();val context=LocalContext.current
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(doc.mime.ifBlank{"application/octet-stream"})){uri->if(uri!=null)vm.downloadGenerated(doc,uri)}
    val downloading=state.generatedDownloadId==doc.id
    val enabled=doc.available&&state.generatedFilesAllowed&&!state.approvalSettings.chatOnly&&!state.busy&&state.generatedDownloadId.isEmpty()
    OutlinedCard(Modifier.fillMaxWidth()){
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text(doc.name,style=MaterialTheme.typography.titleSmall)
            if(doc.available)Text(fileSize(doc.size),style=MaterialTheme.typography.bodySmall)
            else Text(doc.error.ifBlank{"文件已不可用，请重新生成。"},style=MaterialTheme.typography.bodySmall)
            if(downloading){
                LinearProgressIndicator(progress={state.generatedProgress/100f},modifier=Modifier.fillMaxWidth())
                Row{Text("正在下载 ${state.generatedProgress}%",Modifier.weight(1f));TextButton(onClick=vm::cancelDocumentDownload){Text("取消")}}
            }else if(doc.available)Row{
                TextButton(enabled=enabled,onClick={vm.downloadGenerated(doc){file->
                    try{context.startActivity(generatedDocumentIntent(context,file,doc.mime))}
                    catch(_:android.content.ActivityNotFoundException){throw IllegalStateException("手机没有可打开此文件的应用，请使用“保存到手机”。")}
                }}){Text("打开")}
                TextButton(enabled=enabled,onClick={save.launch(doc.name)}){Text("保存到手机")}
            }
        }
    }
}
