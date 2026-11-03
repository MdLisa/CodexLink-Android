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
class SharedChatViewModelTest {
    private val chat=Chat("12345678-1234-1234-1234-123456789abc","作业","仅聊天","sharedReadOnly","妹妹","ABCDEF12")
    @Suppress("UNCHECKED_CAST") private fun mutable(vm:BridgeViewModel)=BridgeViewModel::class.java.getDeclaredField("_state").apply{isAccessible=true}.get(vm) as MutableStateFlow<ScreenState>
    private fun await(check:()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
        while(!check()&&System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
        assertTrue("Timed out waiting for view model",check())
    }
    private fun withModel(block:(BridgeViewModel)->Unit) {
        val vm=BridgeViewModel(RuntimeEnvironment.getApplication());val store=ViewModelStore();store.put("test",vm)
        try{await{!vm.state.value.loading};block(vm)}finally{store.clear()}
    }
    @Test fun readOnlySelectionBlocksSendAttachmentsCancelReleaseAndApproval()=withModel { vm ->
        MockWebServer().use { server ->
            val api=BridgeApi(Session(server.url("/").toString().trimEnd('/'),"token","viewer"),identity=TestDeviceIdentity())
            BridgeViewModel::class.java.getDeclaredField("api").apply{isAccessible=true}.set(vm,api)
            mutable(vm).value=ScreenState(loading=false,session=api.session,selected=chat,browsingShared=true,sharedChatAccess=SharedChatAccess(true,true,"one"),draft="must not send")
            val actions=listOf<()->Unit>({vm.send()},{vm.addPhotos(emptyList())},{vm.addDocuments(emptyList())},{vm.stop(Task("foreign",chat.id,"running","turn",""))},{vm.release()},{vm.prepareTakeover()},{vm.respond("foreign",JSONObject())})
            for(action in actions){mutable(vm).value=vm.state.value.copy(error=null);action();await{!vm.state.value.busy&&vm.state.value.error!=null};assertTrue(vm.state.value.error!!.contains("仅供查看"))}
            assertEquals(0,server.requestCount);assertNull(vm.state.value.pending)
        }
    }
    @Test fun revokedPermissionDiscardsAlreadyInFlightHistoryAndRemovesTheSelection()=withModel { vm ->
        MockWebServer().use { server ->
            val entered=CountDownLatch(1);val release=CountDownLatch(1);var revoked=false
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.path=="/api/status")return MockResponse().setBody("""{"online":true,"sharedChatAccess":{"supported":true,"enabled":${!revoked},"revision":"${if(revoked)"two" else "one"}"}}""")
                if(request.path!!.endsWith("/history?limit=20")){entered.countDown();release.await(5,TimeUnit.SECONDS);return MockResponse().setBody("""{"turns":[{"id":"turn","items":[{"id":"secret","type":"agentMessage","text":"STALE_PRIVATE_CONTENT"}]}]}""")}
                if(request.path==chat.readPath())return MockResponse().setBody("""{"id":"${chat.id}","control":"sharedReadOnly"}""")
                return MockResponse().setBody("""{"threads":[]}""")
            }}
            val api=BridgeApi(Session(server.url("/").toString().trimEnd('/'),"token","viewer"),identity=TestDeviceIdentity())
            BridgeViewModel::class.java.getDeclaredField("api").apply{isAccessible=true}.set(vm,api)
            mutable(vm).value=ScreenState(loading=false,session=api.session,selected=chat,browsingShared=true,sharedChatAccess=SharedChatAccess(true,true,"one"),entries=listOf(Entry("old","turn","assistant","previous private text")))
            vm.refreshNow();await{entered.count==0L};revoked=true
            BridgeViewModel::class.java.getDeclaredMethod("handleEvent",JSONObject::class.java).apply{isAccessible=true}.invoke(vm,JSONObject("""{"type":"shared-chat.settings.changed","data":{"enabled":false,"revision":"two"}}"""))
            assertNull(vm.state.value.selected);assertTrue(vm.state.value.entries.isEmpty());release.countDown()
            await{!vm.state.value.busy};assertFalse(vm.state.value.browsingShared);assertFalse(vm.state.value.sharedChatAccess.enabled);assertTrue(vm.state.value.entries.isEmpty())
        }
    }
}
