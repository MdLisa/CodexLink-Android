package com.xukaizhe.codexlink

import android.os.Looper
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class GeneratedDownloadViewModelTest {
    private val doc=GeneratedDocument("12345678-1234-1234-1234-123456789abc","方案.txt","text/plain",8,java.security.MessageDigest.getInstance("SHA-256").digest("document".toByteArray()).joinToString(""){"%02x".format(it)},true)
    @Suppress("UNCHECKED_CAST") private fun mutable(vm:BridgeViewModel)=BridgeViewModel::class.java.getDeclaredField("_state").apply{isAccessible=true}.get(vm) as MutableStateFlow<ScreenState>
    private fun await(check:()->Boolean){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!check()&&System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)};assertTrue("Timed out waiting for download state",check())}
    private fun withModel(block:(BridgeViewModel,MockWebServer,BridgeApi)->Unit){
        MockWebServer().use{server->
            val vm=BridgeViewModel(RuntimeEnvironment.getApplication());val store=ViewModelStore();store.put("test",vm)
            val api=BridgeApi(Session(server.url("/").toString().trimEnd('/'),"token","phone"),identity=TestDeviceIdentity())
            try{await{!vm.state.value.loading};BridgeViewModel::class.java.getDeclaredField("api").apply{isAccessible=true}.set(vm,api)
                mutable(vm).value=ScreenState(loading=false,session=api.session,selected=Chat("thread","聊天","project","bridge"),generatedFilesAllowed=true,entries=listOf(Entry("reply","turn","assistant","已生成",generatedFiles=listOf(doc))))
                block(vm,server,api)
            }finally{store.clear();api.client.connectionPool.evictAll();api.client.dispatcher.executorService.shutdown()}
        }
    }
    @Test fun unapprovedChatOnlyAndSharedViewsCannotStartDownloads()=withModel{vm,server,_->
        val initial=vm.state.value
        for(state in listOf(initial.copy(generatedFilesAllowed=false),initial.copy(approvalSettings=ApprovalSettings("chat_only")),initial.copy(selected=initial.selected!!.copy(control="sharedReadOnly")))){
            mutable(vm).value=state;vm.downloadGenerated(doc);await{vm.state.value.error!=null};assertEquals("",vm.state.value.generatedDownloadId)
        }
        assertEquals(0,server.requestCount)
    }
    @Test fun revocationCancelsAnInFlightDownloadAndNeverOpensItsResult()=withModel{vm,server,_->
        val entered=CountDownLatch(1);val release=CountDownLatch(1);var opened=false
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{entered.countDown();release.await(5,TimeUnit.SECONDS);return MockResponse().setBody("document")}}
        vm.downloadGenerated(doc){opened=true};await{entered.count==0L}
        BridgeViewModel::class.java.getDeclaredMethod("handleEvent",JSONObject::class.java).apply{isAccessible=true}.invoke(vm,JSONObject("""{"type":"generated-files.settings.changed","data":{"enabled":false}}"""))
        release.countDown();await{vm.state.value.generatedDownloadId.isEmpty()}
        assertFalse(vm.state.value.generatedFilesAllowed);assertTrue(vm.state.value.entries.all{it.generatedFiles.isEmpty()});assertFalse(opened)
    }
    @Test fun savingWritesTheVerifiedDocumentToTheChosenLocation()=withModel{vm,server,_->
        val context:android.content.Context=RuntimeEnvironment.getApplication()
        val target=java.io.File(context.cacheDir,"chosen-document.txt")
        val provider=object:android.content.ContentProvider(){
            override fun onCreate()=true
            override fun getType(uri:android.net.Uri)="text/plain"
            override fun query(uri:android.net.Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):android.database.Cursor?=null
            override fun insert(uri:android.net.Uri,values:android.content.ContentValues?):android.net.Uri?=null
            override fun update(uri:android.net.Uri,values:android.content.ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0
            override fun delete(uri:android.net.Uri,selection:String?,selectionArgs:Array<out String>?):Int {target.delete();return 1}
            override fun openFile(uri:android.net.Uri,mode:String)=android.os.ParcelFileDescriptor.open(target,android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_WRITE_ONLY or android.os.ParcelFileDescriptor.MODE_TRUNCATE)
        }
        provider.attachInfo(context,android.content.pm.ProviderInfo().apply{authority="codex-save-test"})
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("codex-save-test",provider)
        server.enqueue(MockResponse().setBody("document"));vm.downloadGenerated(doc,android.net.Uri.parse("content://codex-save-test/chosen"))
        await{vm.state.value.notice?.startsWith("已保存")==true};assertEquals("document",target.readText());assertEquals("",vm.state.value.generatedDownloadId)
        assertFalse(java.io.File(context.cacheDir,"generated_documents/${doc.id}/${doc.name}").exists());target.delete()
    }
}
