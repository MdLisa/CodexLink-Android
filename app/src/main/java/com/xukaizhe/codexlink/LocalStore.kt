package com.xukaizhe.codexlink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Photo(val path: String)
data class Pending(val messageId: String, val address: String, val threadId: String, val text: String, val photos: List<String>, val uploaded: List<String> = emptyList(), val submitted: Boolean = false, val files: List<LocalDocument> = emptyList(), val uploadedFiles: List<String> = emptyList(), val executionMode: String = "operate", val modeRevision: String = "") {
    fun json() = JSONObject().put("messageId", messageId).put("address", address).put("threadId", threadId).put("text", text).put("photos", JSONArray(photos)).put("uploaded", JSONArray(uploaded)).put("submitted", submitted).put("files",JSONArray(files.map{it.json()})).put("uploadedFiles",JSONArray(uploadedFiles)).put("executionMode",executionMode).put("modeRevision",modeRevision)
    companion object { fun from(j: JSONObject) = Pending(j.str("messageId"), j.str("address"), j.str("threadId"), j.str("text"), j.optJSONArray("photos").strings(), j.optJSONArray("uploaded").strings(), j.optBoolean("submitted"),j.optJSONArray("files")?.objects().orEmpty().map(LocalDocument::from),j.optJSONArray("uploadedFiles").strings(),j.str("executionMode","operate"),j.str("modeRevision")) }
}
private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
data class Saved(val session: Session?, val pending: Pending?, val lastChat: String,val incoming:List<LocalDocument> = emptyList(),val documentDrafts:Map<String,List<LocalDocument>> = emptyMap(),val externalReceipts:List<String> = emptyList())
class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("codexlink_private", Context.MODE_PRIVATE)
    private val alias = "codexlink_connection_v1"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply { init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build()) }.generateKey()
    }
    private fun root(): JSONObject {
        val value = prefs.getString("vault", null) ?: return JSONObject()
        val parts = value.split('.');val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
    }
    private fun write(j: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE, key())
        val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." + Base64.encodeToString(cipher.doFinal(j.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString("vault", value).commit()) { "无法保存本机连接状态，请检查手机存储空间。" }
    }
    @Synchronized fun load(): Saved { val j=root();val s=j.optJSONObject("session");return Saved(s?.let { Session(it.str("address"), it.str("token"), it.str("deviceId"),it.optLong("clockOffset")) }, j.optJSONObject("pending")?.let(Pending::from), j.str("lastChat"),j.optJSONArray("incoming")?.objects().orEmpty().map(LocalDocument::from),j.optJSONObject("documentDrafts")?.let{d->d.keys().asSequence().associateWith{k->d.optJSONArray(k)?.objects().orEmpty().map(LocalDocument::from)}}.orEmpty(),j.optJSONArray("externalReceipts").strings()) }
    @Synchronized fun session(s: Session?) { val j=root();if(s==null)j.remove("session") else j.put("session",JSONObject().put("address",s.address).put("token",s.token).put("deviceId",s.deviceId).put("clockOffset",s.clockOffset));write(j) }
    @Synchronized fun pending(p: Pending?) { val j=root();if(p==null)j.remove("pending") else j.put("pending",p.json());write(j) }
    @Synchronized fun lastChat(id: String) { write(root().put("lastChat",id)) }
    @Synchronized fun incoming(files:List<LocalDocument>,receipt:String?=null){val j=root();j.put("incoming",JSONArray(files.map{it.json()}));if(receipt!=null)j.put("externalReceipts",JSONArray((j.optJSONArray("externalReceipts").strings()+receipt).takeLast(64)));write(j)}
    @Synchronized fun documentDraft(key:String,files:List<LocalDocument>){val j=root();val drafts=j.optJSONObject("documentDrafts")?:JSONObject();if(files.isEmpty())drafts.remove(key) else drafts.put(key,JSONArray(files.map{it.json()}));j.put("documentDrafts",drafts);write(j)}
    @Synchronized fun clearDocumentDrafts(){val j=root();j.remove("documentDrafts");write(j)}
    @Synchronized fun forgetConnection(){val old=root();val j=JSONObject();for(key in listOf("incoming","externalReceipts"))if(old.has(key))j.put(key,old.get(key));write(j)}
    @Synchronized fun clear() { check(prefs.edit().clear().commit()) { "无法清除本机连接信息，请检查手机存储空间。" } }
}

fun preparePhoto(context: Context, uri: Uri): Photo {
    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
    context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it,null,bounds) }
    require(bounds.outWidth>0&&bounds.outHeight>0) { "无法读取这张图片，请换一张 PNG 或 JPEG 图片。" }
    var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>2048)sample*=2
    var bitmap=context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply { inSampleSize=sample }) } ?: error("图片读取失败。")
    val orientation=runCatching { context.contentResolver.openInputStream(uri).use { ExifInterface(requireNotNull(it)).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL) } }.getOrDefault(1)
    val matrix=Matrix().apply { when(orientation) { 2->setScale(-1f,1f);3->setRotate(180f);4->setScale(1f,-1f);5->{setRotate(90f);postScale(-1f,1f)};6->setRotate(90f);7->{setRotate(270f);postScale(-1f,1f)};8->setRotate(270f) } }
    if(!matrix.isIdentity){val rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true);if(rotated!==bitmap)bitmap.recycle();bitmap=rotated}
    val output=ByteArrayOutputStream();val format=if(bitmap.hasAlpha())Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
    bitmap.compress(format,88,output);bitmap.recycle();require(output.size()<=10*1024*1024) { "图片处理后仍超过 10 MB，请先缩小图片。" }
    val dir=File(context.filesDir,"selected_images").apply { mkdirs() };val file=File(dir,UUID.randomUUID().toString()+if(format==Bitmap.CompressFormat.PNG)".png" else ".jpg");file.writeBytes(output.toByteArray());return Photo(file.absolutePath)
}
