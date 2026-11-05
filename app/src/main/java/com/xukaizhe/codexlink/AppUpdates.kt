package com.xukaizhe.codexlink

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal const val UPDATE_BASE="https://codex.shbj.site/updates/android/"
internal const val MAX_UPDATE_BYTES=200L*1024*1024
internal data class AndroidRelease(val versionCode:Long,val versionName:String,val notes:String,val size:Long,val sha256:String,val url:String,val minSdk:Int) {
    companion object {
        fun parse(j:JSONObject):AndroidRelease {
            fun integer(key:String):Long {val n=j.get(key);require(n is Int||n is Long){"更新信息中的数值无效。"};return (n as Number).toLong()}
            require(integer("schemaVersion")==1L&&j.getString("packageName")=="com.xukaizhe.codexlink"){"更新信息不适用于本应用。"}
            val code=integer("versionCode");val name=j.getString("versionName");val size=integer("size");val sha=j.getString("sha256");val notes=j.getString("notes");val sdk=integer("minSdk")
            require(code in 1..Int.MAX_VALUE.toLong()&&name.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))&&size in 1..MAX_UPDATE_BYTES&&sha.matches(Regex("[a-f0-9]{64}"))&&notes.length<=8000&&sdk in 26..Int.MAX_VALUE.toLong()){"更新信息格式不正确。"}
            val file="CodexLink-$name-$code-${sha.take(16)}.apk";val url=j.getString("downloadUrl")
            require(j.getString("file")==file&&url==UPDATE_BASE+file){"更新下载地址不受信任。"}
            return AndroidRelease(code,name,notes,size,sha,url,sdk.toInt())
        }
    }
}

internal class UpdateRepository(private val context:Context,private val client:OkHttpClient=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(10,TimeUnit.MINUTES).followRedirects(false).followSslRedirects(false).build()) {
    @Volatile private var call:okhttp3.Call?=null
    @Volatile private var cancelled=false
    private val dir get()=File(context.cacheDir,"app_updates").apply{mkdirs()}
    fun beginOperation(){cancelled=false;call=null}
    fun cancel(){cancelled=true;call?.cancel()}
    private fun response(url:String):okhttp3.Response {
        val u=url.toHttpUrl();require(u.scheme=="https"&&u.host=="codex.shbj.site"&&u.port==443&&u.username.isEmpty()&&u.password.isEmpty()&&u.query==null&&u.fragment==null&&u.encodedPath.startsWith("/updates/android/"))
        val request=Request.Builder().url(u).header("Cache-Control","no-cache").build()
        return client.newCall(request).also{call=it;if(cancelled)it.cancel()}.execute()
    }
    fun latest():AndroidRelease=response(UPDATE_BASE+"latest.json").use{r->
        require(r.isSuccessful){"暂时无法检查更新，请确认电脑后台和网络可用。"}
        val body=requireNotNull(r.body);require(body.contentLength()<=32768){"更新信息过大。"}
        val bytes=body.byteStream().use{it.readBytesLimited(32768)}
        AndroidRelease.parse(JSONObject(bytes.toString(Charsets.UTF_8)))
    }
    fun installed():PackageInfo=readUpdatePackageInfo{flags->context.packageManager.getPackageInfo(context.packageName,flags)}?:error("无法读取当前应用信息。")
    fun installedVersion():Long=installed().code()
    fun download(release:AndroidRelease,progress:(Int)->Unit):File {
        val target=File(dir,release.sha256+".apk")
        if(target.isFile&&runCatching{verify(target,release)}.isSuccess)return target
        dir.listFiles()?.filter{it.isFile}?.forEach{it.delete()}
        // Keep the APK extension for package managers which depend on the filename when parsing.
        val partial=File(dir,"download.apk")
        try {
            response(release.url).use{r->
                require(r.code==200){"下载失败，请稍后重试。"};val body=requireNotNull(r.body)
                require(body.contentLength()==-1L||body.contentLength()==release.size){"安装包大小不匹配。"}
                body.byteStream().use{input->partial.outputStream().use{out->
                    val buffer=ByteArray(64*1024);var total=0L;var percent=-1
                    while(true){if(Thread.currentThread().isInterrupted)throw InterruptedException();val n=input.read(buffer);if(n<0)break;total+=n;require(total<=release.size){"安装包大小不匹配。"};out.write(buffer,0,n);val p=(total*100/release.size).toInt();if(p!=percent){percent=p;progress(p)}}
                    require(total==release.size){"下载未完成，请重试。"}
                }}
            }
            verify(partial,release);check(partial.renameTo(target)){"无法保存更新文件，请检查存储空间。"};return target
        }finally{partial.delete()}
    }
    fun verify(file:File,release:AndroidRelease){
        require(file.isFile&&file.length()==release.size){"更新文件已失效，请重新下载。"}
        val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val buffer=ByteArray(64*1024);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        require(digest.digest().hex()==release.sha256){"安装包校验失败，请重新下载。"}
        val archive=readUpdatePackageInfo{flags->context.packageManager.getPackageArchiveInfo(file.absolutePath,flags)}?:error("无法读取安装包。")
        val current=installed()
        validateIdentity(archive.packageName,archive.code(),archive.versionName,updateSigners(archive),context.packageName,current.code(),updateSigners(current),release)
        require((archive.applicationInfo?.minSdkVersion?:Int.MAX_VALUE)<=Build.VERSION.SDK_INT){"新版不支持当前安卓系统。"}
    }
    fun installIntent(file:File):Intent {
        val uri=FileProvider.getUriForFile(context,context.packageName+".updates",file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply{clipData=android.content.ClipData.newRawUri("应用更新",uri)}
    }
}
@Suppress("DEPRECATION") private fun PackageInfo.code():Long=if(Build.VERSION.SDK_INT>=28)longVersionCode else versionCode.toLong()
private fun ByteArray.hex()=joinToString(""){"%02x".format(it.toInt() and 255)}
private fun java.io.InputStream.readBytesLimited(limit:Int):ByteArray {val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096);while(true){val n=read(buffer);if(n<0)break;require(out.size()+n<=limit){"更新信息过大。"};out.write(buffer,0,n)};return out.toByteArray()}
internal fun validateIdentity(pkg:String,code:Long,name:String?,signers:Set<String>,installedPkg:String,installedCode:Long,installedSigners:Set<String>,release:AndroidRelease){
    require(pkg==installedPkg&&code==release.versionCode&&code>installedCode&&name==release.versionName){"安装包应用或版本不匹配。"}
    require(installedSigners.isNotEmpty()){"无法读取当前应用的签名，已停止更新。请保留应用，使用原发布APK覆盖安装。"}
    require(signers.isNotEmpty()){"无法读取新版安装包的签名，已停止更新。请保留应用，使用原发布APK覆盖安装。"}
    require(signers==installedSigners){"安装包签名不一致，已停止更新。\n当前：${installedSigners.sorted().joinToString { it.take(16) }}\n新版：${signers.sorted().joinToString { it.take(16) }}"}
}

internal data class AppUpdateState(val visible:Boolean=false,val stage:String="idle",val release:AndroidRelease?=null,val progress:Int=0,val message:String="",val file:File?=null)
class AppUpdateViewModel(app:Application):AndroidViewModel(app){
    private val repo=UpdateRepository(app)
    private val prefs=app.getSharedPreferences("app_updates",Context.MODE_PRIVATE)
    private val mutable=MutableStateFlow(AppUpdateState())
    internal val state=mutable.asStateFlow()
    private var job:Job?=null
    private var lastCheck=0L
    private var manualRequested=false
    init{check(false)}
    fun onForeground(){if(!mutable.value.visible&&android.os.SystemClock.elapsedRealtime()-lastCheck>=5*60*1000L)check(false)}
    fun check(manual:Boolean=true){
        if(job?.isCompleted==false){if(manual){manualRequested=true;mutable.value=mutable.value.copy(visible=true)};return}
        lastCheck=android.os.SystemClock.elapsedRealtime()
        manualRequested=manual
        repo.beginOperation()
        mutable.value=AppUpdateState(visible=manual,stage="checking")
        job=viewModelScope.launch{
            try{
                val release=withContext(Dispatchers.IO){repo.latest()};val installed=withContext(Dispatchers.IO){repo.installedVersion()}
                if(release.versionCode<=installed)mutable.value=AppUpdateState(visible=manualRequested,message="当前已是最新版本。")
                else if(release.minSdk>Build.VERSION.SDK_INT)mutable.value=AppUpdateState(visible=manualRequested,message="有新版本，但不支持当前安卓系统。")
                else{val now=System.currentTimeMillis();val later=prefs.getLong("laterCode",0)==release.versionCode&&now-prefs.getLong("laterAt",0) in 0..(24*60*60*1000L);mutable.value=AppUpdateState(visible=manualRequested||!later,stage="available",release=release)}
            }catch(e:Exception){if(e is CancellationException)throw e;mutable.value=AppUpdateState(visible=manualRequested,stage="error",message="暂时无法检查更新，请确认电脑后台和网络可用后重试。")}
        }
    }
    fun dismiss(){
        val s=mutable.value
        if(s.stage in setOf("checking","downloading","verifying")){repo.cancel();job?.cancel()}
        s.release?.let{prefs.edit().putLong("laterCode",it.versionCode).putLong("laterAt",System.currentTimeMillis()).apply()}
        mutable.value=s.copy(visible=false,stage=if(s.stage in setOf("checking","downloading","verifying"))"error" else s.stage,message=if(s.stage in setOf("checking","downloading","verifying"))"已取消，可以稍后重试。" else s.message)
    }
    fun download(){
        if(job?.isCompleted==false)return
        val release=mutable.value.release?:return
        repo.beginOperation()
        mutable.value=mutable.value.copy(stage="downloading",message="",progress=0,file=null)
        job=viewModelScope.launch{
            try{val file=withContext(Dispatchers.IO){repo.download(release){p->mutable.value=mutable.value.copy(progress=p)}};ensureActive();mutable.value=mutable.value.copy(stage="ready",file=file)}
            catch(e:Exception){if(e is CancellationException)throw e;mutable.value=mutable.value.copy(stage="error",message=e.message?:"下载失败，请重试。",file=null)}
        }
    }
    internal fun install(launch:(Intent)->Unit){
        if(job?.isCompleted==false)return
        val s=mutable.value;val file=s.file?:return;val release=s.release?:return
        mutable.value=s.copy(stage="verifying",message="")
        job=viewModelScope.launch{
            try{withContext(Dispatchers.IO){repo.verify(file,release)};mutable.value=mutable.value.copy(stage="ready",message="请在系统页面确认更新；取消后可再次点击安装。")
                launch(repo.installIntent(file))
            }catch(e:Exception){if(e is CancellationException)throw e;mutable.value=mutable.value.copy(stage="error",file=null,message=e.message?:"无法打开安装页面，请重试。")}
        }
    }
    internal fun permissionError(){mutable.value=mutable.value.copy(message="请允许安装应用更新，再点击安装更新。")}
    override fun onCleared(){repo.cancel();super.onCleared()}
}
