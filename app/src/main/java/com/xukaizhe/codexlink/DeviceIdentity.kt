package com.xukaizhe.codexlink

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

interface DeviceIdentity {
    fun publicKey(): String
    fun sign(bytes: ByteArray): String
}

object DeviceProof {
    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun code(publicKey: String): String = sha(Base64.getDecoder().decode(publicKey)).take(16).uppercase().chunked(4).joinToString("-")
    fun text(token: String, method: String, host: String, path: String, time: String, nonce: String, body: ByteArray): ByteArray =
        listOf("codex-device-v1",sha(token.toByteArray(Charsets.UTF_8)),method,host.lowercase().replace(Regex(":(80|443)$"),""),path,time,nonce,sha(body)).joinToString("\n").toByteArray(Charsets.UTF_8)
    fun create(identity: DeviceIdentity, token: String, method: String, host: String, path: String, body: ByteArray, offset: Long): Map<String,String> {
        val time=(System.currentTimeMillis()+offset).toString();val nonce=UUID.randomUUID().toString()
        return mapOf("x-device-time" to time,"x-device-nonce" to nonce,"x-device-signature" to identity.sign(text(token,method,host,path,time,nonce,body)))
    }
}
