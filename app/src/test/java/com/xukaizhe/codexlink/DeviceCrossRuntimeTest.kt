package com.xukaizhe.codexlink

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DeviceCrossRuntimeTest {
    @Test fun javaPhoneSignaturesAreAcceptedByTheActualNodeBackend()=runBlocking {
        val root=System.getenv("CODEXLINK_SECURITY_FIXTURE")
        assumeTrue(root!=null)
        val node=File(System.getenv("USERPROFILE"),".cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe")
        val process=ProcessBuilder(node.absolutePath,File(root,"work/step6/jvm-fixture.mjs").absolutePath).directory(File(root)).start()
        try {
            val input=process.inputStream.bufferedReader();val control=process.outputStream.bufferedWriter()
            val address=JSONObject(input.readLine()).getString("address")
            val key=TestDeviceIdentity();val api=BridgeApi(Session(address,"",""),identity=key)
            api.request("/health",anonymous=true)
            var code=""
            try{api.login("fixture","only-for-cross-runtime-test","JVM phone");fail()}catch(e:DeviceApprovalRequired){code=e.verificationCode}
            assertEquals(DeviceProof.code(key.publicKey()),code)
            control.write(JSONObject().put("command","approve").put("publicKey",key.publicKey()).put("code",code).toString()+"\n");control.flush()
            assertTrue(JSONObject(input.readLine()).getBoolean("approved"))
            val session=api.login("fixture","only-for-cross-runtime-test","JVM phone")
            val connected=BridgeApi(session,identity=key)
            assertTrue(connected.request("/api/status").getBoolean("online"))
            assertEquals(0,connected.request("/api/threads").getJSONArray("threads").length())
            val png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
            assertEquals(png.size,connected.request("/api/uploads","POST",bytes=png).getInt("size"))
            try{BridgeApi(session,identity=TestDeviceIdentity()).request("/api/status");fail()}catch(e:BridgeException){assertEquals(403,e.status)}
            val received=CountDownLatch(1)
            val socket=connected.socket(0,{if(it.str("type")=="authenticated")received.countDown()},{})
            assertTrue(received.await(8,TimeUnit.SECONDS));socket.cancel()
            control.close()
            assertTrue(process.waitFor(8,TimeUnit.SECONDS))
        } finally {process.destroyForcibly()}
    }
}
