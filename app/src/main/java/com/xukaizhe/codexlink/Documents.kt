package com.xukaizhe.codexlink

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.util.UUID

const val DOCUMENT_LIMIT=10L*1024*1024
data class LocalDocument(val id:String,val name:String,val mime:String,val path:String,val size:Long,val uploadedScope:String=""){
    fun json()=JSONObject().put("id",id).put("name",name).put("mime",mime).put("path",path).put("size",size).put("uploadedScope",uploadedScope)
    companion object{fun from(j:JSONObject)=LocalDocument(j.str("id"),j.str("name"),j.str("mime"),j.str("path"),j.optLong("size"),j.str("uploadedScope"))}
}
data class FileAttachment(val id:String,val name:String,val size:Long,val available:Boolean)
fun fileSize(size:Long):String=when{size<1024->"$size B";size<1024*1024->"%.1f KB".format(size/1024.0);else->"%.1f MB".format(size/(1024.0*1024))}

fun prepareDocument(context:Context,uri:Uri):LocalDocument {
    require(uri.scheme=="content"||uri.scheme=="file"){"请通过文件选择器或其他应用分享文件。"}
    if(uri.scheme=="file"){
        val file=File(requireNotNull(uri.path)).canonicalFile
        val publicRoot=Environment.getExternalStorageDirectory().canonicalFile
        require(file.path.startsWith(publicRoot.path+File.separator)&&!file.path.contains(File.separator+"Android"+File.separator+"data"+File.separator)){"请先保存到手机，再通过“＋文件”选择。"}
    }
    var name=uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank{"附件"};var size=-1L
    if(uri.scheme=="content")runCatching{context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use{cursor->
        if(cursor.moveToFirst()){
            val n=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(n>=0&&!cursor.isNull(n))name=cursor.getString(n)
            val s=cursor.getColumnIndex(OpenableColumns.SIZE);if(s>=0&&!cursor.isNull(s))size=cursor.getLong(s)
        }
    }}
    require(size<=DOCUMENT_LIMIT){"单个文件不能超过 10 MB。"}
    name=name.replace(Regex("[\\u0000-\\u001f\\u007f]"),"_").take(255).ifBlank{"附件"}
    val mime=runCatching{context.contentResolver.getType(uri)}.getOrNull().orEmpty().ifBlank{"application/octet-stream"}
    val id=UUID.randomUUID().toString();val extension=name.substringAfterLast('.',"").take(16).filter{it.isLetterOrDigit()}
    val dir=File(context.filesDir,"selected_documents").apply{mkdirs()}
    val target=File(dir,id+(if(extension.isNotBlank())".$extension" else ""))
    try{
        val input=context.contentResolver.openInputStream(uri)?:error("无法读取文件，请先下载到手机。")
        var total=0L
        input.use{stream->target.outputStream().use{output->val buffer=ByteArray(8192);while(true){val count=stream.read(buffer);if(count<0)break;total+=count;require(total<=DOCUMENT_LIMIT){"单个文件不能超过 10 MB。"};output.write(buffer,0,count)}}}
        return LocalDocument(id,name,mime,target.absolutePath,total)
    }catch(e:Exception){target.delete();throw e}
}

fun documentUploadScope(session:Session,mode:String,revision:String)=session.address+"|"+session.deviceId+"|"+mode+"|"+revision
