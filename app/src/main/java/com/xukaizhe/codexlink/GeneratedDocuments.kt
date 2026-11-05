package com.xukaizhe.codexlink

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal const val GENERATED_DOCUMENT_LIMIT=20L*1024*1024
// Separate provider component so its document roots cannot overlap the APK provider.
class GeneratedDocumentProvider:FileProvider()
data class GeneratedDocument(val id:String,val name:String,val mime:String,val size:Long,val sha256:String,val available:Boolean,val error:String="") {
    companion object {
        fun from(j:JSONObject)=GeneratedDocument(j.str("id"),j.str("name","文档"),j.str("mime"),j.optLong("size"),j.str("sha256"),j.optBoolean("available"),j.str("error"))
    }
    fun validate(){
        require(validImageId(id)&&size in 0..GENERATED_DOCUMENT_LIMIT&&Regex("[a-f0-9]{64}").matches(sha256)){"文档信息无效或超过 20 MB，请刷新聊天。"}
        require(available&&name.isNotBlank()&&name.length<=160&&name !in setOf(".","..")&&name.none{it=='/'||it=='\\'||it.code<32||it==':'}){"文档名称无效或已不可用。"}
    }
}
internal class GeneratedDocumentDownloader(private val context:Context,private val api:BridgeApi) {
    private val client=api.client.newBuilder().callTimeout(5,TimeUnit.MINUTES).followRedirects(false).followSslRedirects(false).build()
    @Volatile private var call:Call?=null
    fun cancel(){call?.cancel()}
    suspend fun download(doc:GeneratedDocument,progress:(Int)->Unit):File=withContext(Dispatchers.IO){
        doc.validate()
        val dir=File(context.cacheDir,"generated_documents/${doc.id}").apply{mkdirs()}
        val partial=File(dir,".partial");val target=File(dir,doc.name)
        currentCoroutineContext().ensureActive()
        val next=client.newCall(api.generatedDownloadRequest(doc.id));call=next
        try {
            next.execute().use{response->
                if(!response.isSuccessful){
                    val message=when(response.code){401->"登录已失效，请重新连接电脑。";403->"文档下载未授权或项目权限已改变。";404->"文件已不可用，请重新生成。";429->"正在下载的文件过多，请稍后重试。";else->"下载失败（${response.code}），请重试。"}
                    throw BridgeException(response.code,"GENERATED_DOWNLOAD_FAILED",message)
                }
                val body=response.body?:throw IOException("文件内容为空。")
                require(body.contentLength()<=GENERATED_DOCUMENT_LIMIT&&(body.contentLength()<0||body.contentLength()==doc.size)){"文件大小与交付记录不符或超过 20 MB。"}
                val sha=MessageDigest.getInstance("SHA-256");var total=0L
                body.byteStream().use{input->partial.outputStream().use{output->
                    val buffer=ByteArray(8192)
                    while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;total+=n
                        require(total<=GENERATED_DOCUMENT_LIMIT&&total<=doc.size){"文件超过允许大小。"};sha.update(buffer,0,n);output.write(buffer,0,n)
                        progress(if(doc.size==0L)100 else (total*100/doc.size).toInt())
                    }
                }}
                require(total==doc.size&&sha.digest().joinToString(""){"%02x".format(it)}==doc.sha256){"文件不完整或校验失败，请重试。"}
                currentCoroutineContext().ensureActive()
                if(target.exists())target.delete()
                check(partial.renameTo(target)){"无法保存下载文件。"};target
            }
        }catch(e:Exception){partial.delete();throw e}finally{call=null}
    }
}
internal fun generatedDocumentIntent(context:Context,file:File,mime:String):Intent {
    val uri=FileProvider.getUriForFile(context,context.packageName+".documents",file)
    return Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime.ifBlank{"application/octet-stream"})
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply{clipData=ClipData.newRawUri("文档",uri)}
}
