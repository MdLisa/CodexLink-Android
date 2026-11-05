package com.xukaizhe.codexlink

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable internal fun AppUpdateDialog(vm:AppUpdateViewModel){
    val state by vm.state.collectAsState();val context=LocalContext.current
    val installer=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){
        if(context.packageManager.canRequestPackageInstalls())vm.install{installer.launch(it)}else vm.permissionError()
    }
    if(!state.visible)return
    val busy=state.stage in setOf("checking","downloading","verifying")
    AlertDialog(onDismissRequest=vm::dismiss,title={Text(if(state.release!=null)"发现新版本 ${state.release!!.versionName}" else "检查更新")},text={
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            state.release?.let{release->
                Text("更新大小：%.1f MB".format(release.size/1024.0/1024.0))
                if(release.notes.isNotBlank())Text(release.notes)
                Text("保留原有数据。安装期间应用会关闭，请先保存正在编辑的内容。",style=MaterialTheme.typography.bodySmall)
            }
            when(state.stage){
                "checking"->{LinearProgressIndicator(Modifier.fillMaxWidth());Text("正在检查更新…")}
                "downloading"->{LinearProgressIndicator(progress={state.progress/100f},modifier=Modifier.fillMaxWidth());Text("正在下载 ${state.progress}%")}
                "verifying"->{LinearProgressIndicator(Modifier.fillMaxWidth());Text("正在校验安装包…")}
                "ready"->Text("已下载并校验，点击后由系统确认安装。首次可能需要允许本应用安装更新。")
            }
            if(state.message.isNotBlank())SelectionContainer{Text(state.message)}
        }
    },confirmButton={
        if(!busy)TextButton(onClick={
            when{
                state.stage=="ready"->if(context.packageManager.canRequestPackageInstalls())vm.install{installer.launch(it)}else{
                    vm.permissionError()
                    runCatching{permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+context.packageName)))}.onFailure{vm.permissionError()}
                }
                state.release!=null->vm.download()
                state.stage=="error"->vm.check()
                else->vm.dismiss()
            }
        }){Text(when{state.stage=="ready"->"安装更新";state.release!=null->if(state.stage=="error")"重试下载" else "立即更新";state.stage=="error"->"重新检查";else->"知道了"})}
    },dismissButton={if(state.release!=null||busy||state.stage=="error")TextButton(onClick=vm::dismiss){Text(if(busy)"取消" else "稍后")}})
}
