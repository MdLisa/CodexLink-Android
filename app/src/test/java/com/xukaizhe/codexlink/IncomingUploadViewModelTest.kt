package com.xukaizhe.codexlink

import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class IncomingUploadViewModelTest {
    @Suppress("UNCHECKED_CAST") private fun mutable(vm:BridgeViewModel)=BridgeViewModel::class.java.getDeclaredField("_state").apply{isAccessible=true}.get(vm) as MutableStateFlow<ScreenState>
    private fun await(check:()->Boolean){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!check()&&System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)};assertTrue("Timed out waiting for import/upload",check())}
    private fun withModel(block:(BridgeViewModel,MockWebServer,BridgeApi)->Unit){
        MockWebServer().use{server->
            val context:android.app.Application=RuntimeEnvironment.getApplication()
            context.getSharedPreferences("codexlink_private",0).edit().clear().commit()
            java.security.Security.addProvider(object:java.security.Provider("AndroidKeyStore", 1.0, "Test-only in-memory vault key"){init{put("KeyStore.AndroidKeyStore",TestVaultStore::class.java.name)}})
            val vm=BridgeViewModel(context);val store=ViewModelStore();store.put("test",vm)
            val api=BridgeApi(Session(server.url("/").toString().trimEnd('/'),"token","phone"),identity=TestDeviceIdentity())
            try{await{!vm.state.value.loading};BridgeViewModel::class.java.getDeclaredField("api").apply{isAccessible=true}.set(vm,api)
                mutable(vm).value=ScreenState(loading=false,session=api.session,online=true,selected=Chat("thread","聊天","仅聊天","chatOnly"),approvalSettings=ApprovalSettings("chat_only",true,"r1"))
                block(vm,server,api)
            }finally{store.clear();api.client.connectionPool.evictAll();api.client.dispatcher.executorService.shutdown();java.security.Security.removeProvider("AndroidKeyStore")}
        }
    }
    private fun uri():Uri {
        val context:android.content.Context=RuntimeEnvironment.getApplication();val file=java.io.File(context.cacheDir,"incoming-source.txt").apply{writeText("主动上传的文档")}
        val provider=object:android.content.ContentProvider(){
            override fun onCreate()=true
            override fun getType(uri:Uri)="text/plain"
            override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?)=android.database.MatrixCursor(arrayOf("_display_name","_size")).apply{addRow(arrayOf("微信文档.txt",file.length()))}
            override fun insert(uri:Uri,values:android.content.ContentValues?):Uri?=null
            override fun update(uri:Uri,values:android.content.ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0
            override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=0
            override fun openFile(uri:Uri,mode:String)=android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        }
        provider.attachInfo(context,android.content.pm.ProviderInfo().apply{authority="codex-import-test"})
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("codex-import-test",provider)
        return Uri.parse("content://codex-import-test/document")
    }
    @Test fun chatOnlyFilePickerAutomaticallyUploadsAndRestoresUploadMarker()=withModel{vm,server,api->
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"));vm.addDocuments(listOf(uri()))
        await{!vm.state.value.busy&&vm.state.value.files.singleOrNull()?.uploadedScope?.isNotEmpty()==true}
        assertNull(vm.state.value.error);assertEquals(1,server.requestCount)
        val request=server.takeRequest();assertEquals("/api/files",request.requestUrl!!.encodedPath);assertEquals("r1",request.requestUrl!!.queryParameter("modeRevision"));assertEquals("主动上传的文档",request.body.readUtf8())
        val saved=LocalStore(RuntimeEnvironment.getApplication()).load();val file=saved.documentDrafts.values.single().single();assertEquals(documentUploadScope(api.session,"chat_only","r1"),file.uploadedScope)
        vm.retryDocumentUploads();await{!vm.state.value.busy};assertEquals(1,server.requestCount)
    }
    @Test fun failedAutomaticUploadKeepsDraftForExplicitRetry()=withModel{vm,server,_->
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"));vm.addDocuments(listOf(uri()));await{vm.state.value.error!=null&&!vm.state.value.busy}
        val id=vm.state.value.files.single().id;assertEquals("",vm.state.value.files.single().uploadedScope)
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"));vm.retryDocumentUploads();await{!vm.state.value.busy&&vm.state.value.files.single().uploadedScope.isNotEmpty()}
        assertEquals(id,vm.state.value.files.single().id);assertEquals(2,server.requestCount);assertNull(vm.state.value.pending)
    }
    @Test fun externalReceiptIsDeduplicatedAndWaitsForChatSelection()=withModel{vm,server,_->
        val source=uri();vm.importExternal(listOf(source),"receipt-1");await{vm.state.value.incomingFiles.size==1&&!vm.state.value.importing}
        vm.importExternal(listOf(source),"receipt-1");shadowOf(Looper.getMainLooper()).idle();assertEquals(1,vm.state.value.incomingFiles.size);assertEquals(0,server.requestCount)
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=MockResponse().setBody(when(request.requestUrl!!.encodedPath){
            "/api/status"->"""{"online":true,"capabilities":{"perDeviceApprovals":true,"files":true},"executionSettings":{"approvalsReviewer":"chat_only","modeRevision":"r1"}}"""
            "/api/files"->"{}"
            else->"""{"jobs":[],"turns":[]}"""
        })}
        vm.openForIncoming(vm.state.value.selected!!);await{!vm.state.value.busy&&vm.state.value.files.singleOrNull()?.uploadedScope?.isNotEmpty()==true}
        assertNull(vm.state.value.error);assertTrue(vm.state.value.incomingFiles.isEmpty());assertNull(vm.state.value.pending)
        val requests=(0 until server.requestCount).map{server.takeRequest()};assertEquals(1,requests.count{it.requestUrl!!.encodedPath=="/api/files"});assertFalse(requests.any{it.requestUrl!!.encodedPath.endsWith("/messages")})
    }
}
