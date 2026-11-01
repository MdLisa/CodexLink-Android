package com.xukaizhe.codexlink
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
class TestDeviceIdentity : DeviceIdentity {
    val key=KeyPairGenerator.getInstance("EC").apply{initialize(ECGenParameterSpec("secp256r1"))}.generateKeyPair()
    override fun publicKey()=Base64.getEncoder().encodeToString(key.public.encoded)
    override fun sign(bytes:ByteArray):String=Signature.getInstance("SHA256withECDSA").run{initSign(key.private);update(bytes);Base64.getEncoder().encodeToString(sign())}
}
